package org.archphene.bridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Test-only framework consumer proving virtual nodes reach Android accessibility. */
public final class ProbeAccessibilityService extends AccessibilityService {
    private static final String PACKAGE = "org.archphene.accessibilityprobe";
    private static final String TAG = "ArchpheneAccessibilityProbe";

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 0;
        info.packageNames = new String[] {PACKAGE};
        setServiceInfo(info);
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !PACKAGE.contentEquals(event.getPackageName())) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Log.i(TAG, "Accessibility event type=" + event.getEventType()
                + " root=" + (root != null));
        if (root == null) return;
        StringBuilder output = new StringBuilder();
        appendNode(root, output, 0);
        File target = new File(getFilesDir(), "framework-accessibility-tree.txt");
        try (FileOutputStream stream = new FileOutputStream(target, false)) {
            stream.write(output.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
            Log.i(TAG, "Accessibility framework tree bytes=" + output.length());
        } catch (Exception ignored) {
            // The device test treats a missing result as failure.
        } finally {
            root.recycle();
        }
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
}
