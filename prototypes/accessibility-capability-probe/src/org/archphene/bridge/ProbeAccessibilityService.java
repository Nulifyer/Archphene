package org.archphene.bridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Test-only framework consumer proving virtual nodes reach Android accessibility. */
public final class ProbeAccessibilityService extends AccessibilityService {
    private static final String PACKAGE = "org.archphene.accessibilityprobe";
    private static final String LINUX_PACKAGE_PREFIX = "org.archphene.linux.";
    private static final String TAG = "ArchpheneAccessibilityProbe";
    private static final int MAX_COMMAND_BYTES = 16 * 1024;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable commandPoller = this::pollCommand;

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 0;
        info.packageNames = null;
        setServiceInfo(info);
        handler.post(commandPoller);
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String eventPackage = event.getPackageName().toString();
        if (!PACKAGE.equals(eventPackage)
                && !eventPackage.startsWith(LINUX_PACKAGE_PREFIX)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Log.i(TAG, "Accessibility event package=" + eventPackage
                + " type=" + event.getEventType() + " root=" + (root != null));
        boolean probeTree = PACKAGE.equals(eventPackage);
        StringBuilder output = new StringBuilder();
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo windowRoot = window.getRoot();
            try {
                if (windowRoot == null || !eventPackage.contentEquals(
                        windowRoot.getPackageName())) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                CharSequence title = Build.VERSION.SDK_INT >= 24
                        ? window.getTitle() : null;
                output.append("WINDOW|").append(window.getId()).append('|')
                        .append(window.getType()).append('|').append(title).append('|')
                        .append(bounds.flattenToString()).append('\n');
                if (probeTree) appendNode(windowRoot, output, 0);
                else appendTargetNode(windowRoot, output, "0", 0);
            } finally {
                if (windowRoot != null) windowRoot.recycle();
                window.recycle();
            }
        }
        if (windows.isEmpty() && root != null
                && eventPackage.contentEquals(root.getPackageName())) {
            if (probeTree) appendNode(root, output, 0);
            else appendTargetNode(root, output, "0", 0);
        }
        String name = probeTree ? "framework-accessibility-tree.txt"
                : "framework-accessibility-tree-" + safePackage(eventPackage) + ".txt";
        File target = new File(getFilesDir(), name);
        try (FileOutputStream stream = new FileOutputStream(target, false)) {
            stream.write(output.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
            Log.i(TAG, "Accessibility framework tree package=" + eventPackage
                    + " bytes=" + output.length());
        } catch (Exception ignored) {
            // The device test treats a missing result as failure.
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static void appendTargetNode(AccessibilityNodeInfo node,
            StringBuilder output, String path, int depth) {
        if (depth > 32 || output.length() > 128 * 1024) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        output.append("NODE|").append(path).append('|').append(node.getClassName())
                .append('|').append(node.getText()).append('|')
                .append(node.getContentDescription()).append('|')
                .append(bounds.flattenToString()).append('|')
                .append(node.isClickable()).append('|').append(node.isEditable())
                .append('|').append(node.isFocused()).append('|')
                .append(node.getActions()).append('\n');
        int children = Math.min(node.getChildCount(), 1024);
        for (int index = 0; index < children; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            appendTargetNode(child, output, path + "." + index, depth + 1);
            child.recycle();
        }
    }

    private void pollCommand() {
        try {
            File command = new File(getFilesDir(), "framework-accessibility-command.txt");
            if (command.isFile()) processCommand(command);
        } catch (Exception error) {
            Log.e(TAG, "Accessibility command failed", error);
        } finally {
            handler.postDelayed(commandPoller, 100);
        }
    }

    private void processCommand(File command) throws Exception {
        long length = command.length();
        if (length < 1 || length > MAX_COMMAND_BYTES) {
            command.delete();
            return;
        }
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(command)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != bytes.length) throw new IllegalStateException("Short command read");
        }
        if (!command.delete()) throw new IllegalStateException("Could not consume command");
        String[] fields = new String(bytes, StandardCharsets.UTF_8).trim().split("\\t", -1);
        if (fields.length != 5 || fields[0].length() > 64
                || !fields[1].startsWith(LINUX_PACKAGE_PREFIX)) {
            writeResponse(fields.length > 0 ? fields[0] : "invalid", false);
            return;
        }
        String selector = decode(fields[3]);
        String value = decode(fields[4]);
        boolean accepted = performTargetAction(fields[1], fields[2], selector, value);
        writeResponse(fields[0], accepted);
    }

    private boolean performTargetAction(String targetPackage, String action,
            String selector, String value) {
        List<AccessibilityWindowInfo> windows = new ArrayList<>(getWindows());
        try {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (targetPackage.contentEquals(root.getPackageName())
                            && findAndPerform(root, action, selector, value, 0)) return true;
                } finally {
                    root.recycle();
                }
            }
            return false;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private static boolean findAndPerform(AccessibilityNodeInfo node, String action,
            String selector, String value, int depth) {
        if (depth > 32) return false;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if ((text != null && selector.contentEquals(text))
                || (description != null && selector.contentEquals(description))) {
            int androidAction;
            Bundle arguments = null;
            switch (action) {
                case "click": androidAction = AccessibilityNodeInfo.ACTION_CLICK; break;
                case "focus": androidAction = AccessibilityNodeInfo.ACTION_FOCUS; break;
                case "scroll-forward":
                    androidAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
                case "scroll-backward":
                    androidAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
                case "set-text":
                    androidAction = AccessibilityNodeInfo.ACTION_SET_TEXT;
                    arguments = new Bundle();
                    arguments.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                    break;
                default: return false;
            }
            if (node.performAction(androidAction, arguments)) return true;
        }
        int children = Math.min(node.getChildCount(), 1024);
        for (int index = 0; index < children; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                if (findAndPerform(child, action, selector, value, depth + 1)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private void writeResponse(String id, boolean accepted) throws Exception {
        File response = new File(getFilesDir(), "framework-accessibility-response.txt");
        try (FileOutputStream output = new FileOutputStream(response, false)) {
            output.write((id + "\t" + (accepted ? "OK" : "REJECTED") + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static String decode(String value) {
        byte[] decoded = Base64.decode(value,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        if (decoded.length > 4096) throw new IllegalArgumentException("Command text too large");
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static String safePackage(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
    private static void appendNode(AccessibilityNodeInfo node, StringBuilder output, int depth) {
        if (depth > 32 || output.length() > 128 * 1024) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        output.append(depth).append('|').append(node.getClassName()).append('|')
                .append(node.getText()).append('|').append(node.getContentDescription())
                .append('|').append(bounds.flattenToString()).append('\n');
        int children = Math.min(node.getChildCount(), 1024);
        for (int index = 0; index < children; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            appendNode(child, output, depth + 1);
            child.recycle();
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacks(commandPoller);
        super.onDestroy();
    }
}
