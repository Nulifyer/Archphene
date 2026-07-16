package org.archphene.bridge;

import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Android virtual-view accessibility tree backed by bounded Linux semantics. */
final class ArchpheneAccessibilityBridge extends AccessibilityNodeProvider {
    private static final int MAX_TREE_BYTES = 1024 * 1024;
    private static final int MAX_NODES = 1024;
    private static final int MAX_NODE_ID = 1_000_000;
    private static final int MAX_TEXT = 1024;
    private static final int MAX_ACTIONS = 64;
    private static final int MAX_VIEWPORT = 16384;
    private static final int MAX_POLL_MILLIS = 250;

    static final class LinuxAction {
        final int nodeId;
        final String action;
        final String text;

        LinuxAction(int nodeId, String action, String text) {
            this.nodeId = nodeId;
            this.action = action;
            this.text = text;
        }

        String response() {
            String encoded = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return "OK\t" + nodeId + "\t" + action + "\t" + encoded;
        }
    }

    private static final class Node {
        final int id;
        final int parent;
        final String role;
        final String text;
        final String description;
        final Rect bounds;
        final boolean enabled;
        final boolean focusable;
        final boolean clickable;
        final boolean editable;
        final boolean checkable;
        final boolean checked;
        final boolean password;
        final List<Integer> children = new ArrayList<>();

        Node(JSONObject source) throws JSONException {
            id = boundedInt(source, "id", 1, MAX_NODE_ID);
            parent = boundedInt(source, "parent", 0, MAX_NODE_ID);
            role = boundedString(source.optString("role", "view"), "role", 32)
                    .toLowerCase(Locale.ROOT);
            validateRole(role);
            text = boundedString(source.optString("text", ""), "text", MAX_TEXT);
            description = boundedString(source.optString("description", ""),
                    "description", MAX_TEXT);
            int x = boundedInt(source, "x", -MAX_VIEWPORT, MAX_VIEWPORT);
            int y = boundedInt(source, "y", -MAX_VIEWPORT, MAX_VIEWPORT);
            int width = boundedInt(source, "width", 1, MAX_VIEWPORT);
            int height = boundedInt(source, "height", 1, MAX_VIEWPORT);
            bounds = new Rect(x, y, Math.addExact(x, width), Math.addExact(y, height));
            enabled = source.optBoolean("enabled", true);
            focusable = source.optBoolean("focusable", false);
            clickable = source.optBoolean("clickable", false);
            editable = source.optBoolean("editable", false);
            checkable = source.optBoolean("checkable", false);
            checked = source.optBoolean("checked", false);
            password = source.optBoolean("password", false);
        }
    }

    private final Object lock = new Object();
    private final ArrayBlockingQueue<LinuxAction> actions =
            new ArrayBlockingQueue<>(MAX_ACTIONS);
    private View host;
    private int viewportWidth = 1;
    private int viewportHeight = 1;
    private Map<Integer, Node> nodes = Collections.emptyMap();
    private int accessibilityFocus;
    private int inputFocus;

    void attach(View view) {
        synchronized (lock) {
            host = view;
        }
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.post(() -> view.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    }

    void detach(View view) {
        synchronized (lock) {
            if (host == view) {
                host = null;
                nodes = Collections.emptyMap();
                accessibilityFocus = 0;
                inputFocus = 0;
            }
        }
        actions.clear();
    }

    void publish(FileDescriptor descriptor) throws Exception {
        byte[] json = readBounded(descriptor);
        JSONObject root = new JSONObject(new String(json, StandardCharsets.UTF_8));
        int newWidth = boundedInt(root, "viewportWidth", 1, MAX_VIEWPORT);
        int newHeight = boundedInt(root, "viewportHeight", 1, MAX_VIEWPORT);
        JSONArray sourceNodes = root.getJSONArray("nodes");
        if (sourceNodes.length() < 1 || sourceNodes.length() > MAX_NODES) {
            throw new IllegalArgumentException("Accessibility node count is invalid");
        }
        LinkedHashMap<Integer, Node> parsed = new LinkedHashMap<>();
        for (int index = 0; index < sourceNodes.length(); index++) {
            Node node = new Node(sourceNodes.getJSONObject(index));
            if (parsed.put(node.id, node) != null) {
                throw new IllegalArgumentException("Duplicate accessibility node ID");
            }
        }
        boolean hasRoot = false;
        for (Node node : parsed.values()) {
            if (node.parent == 0) {
                hasRoot = true;
            } else {
                Node parent = parsed.get(node.parent);
                if (parent == null || parent == node) {
                    throw new IllegalArgumentException("Invalid accessibility parent");
                }
                parent.children.add(node.id);
            }
            validateParentChain(node, parsed);
        }
        if (!hasRoot) throw new IllegalArgumentException("Accessibility tree has no root");
        synchronized (lock) {
            viewportWidth = newWidth;
            viewportHeight = newHeight;
            nodes = Collections.unmodifiableMap(parsed);
            if (!nodes.containsKey(accessibilityFocus)) accessibilityFocus = 0;
            if (!nodes.containsKey(inputFocus)) inputFocus = 0;
        }
        actions.clear();
        sendEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    void sendNamedEvent(int nodeId, String type) {
        int event = switch (type) {
            case "focus" -> AccessibilityEvent.TYPE_VIEW_FOCUSED;
            case "selected" -> AccessibilityEvent.TYPE_VIEW_SELECTED;
            case "text" -> AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
            case "clicked" -> AccessibilityEvent.TYPE_VIEW_CLICKED;
            case "window" -> AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            case "content" -> AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            default -> throw new IllegalArgumentException("Unknown accessibility event");
        };
        synchronized (lock) {
            if (nodeId != 0 && !nodes.containsKey(nodeId)) {
                throw new IllegalArgumentException("Unknown accessibility node");
            }
            if (event == AccessibilityEvent.TYPE_VIEW_FOCUSED) inputFocus = nodeId;
        }
        sendEvent(nodeId, event);
    }

    String takeAction(int timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0 || timeoutMillis > MAX_POLL_MILLIS) {
            throw new IllegalArgumentException("Accessibility action timeout is invalid");
        }
        LinuxAction action = timeoutMillis == 0 ? actions.poll()
                : actions.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        return action == null ? "ERROR\tEMPTY" : action.response();
    }

    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
        View currentHost;
        Map<Integer, Node> currentNodes;
        int currentWidth;
        int currentHeight;
        int currentAccessibilityFocus;
        int currentInputFocus;
        synchronized (lock) {
            currentHost = host;
            currentNodes = nodes;
            currentWidth = viewportWidth;
            currentHeight = viewportHeight;
            currentAccessibilityFocus = accessibilityFocus;
            currentInputFocus = inputFocus;
        }
        if (currentHost == null) return null;
        if (virtualViewId == View.NO_ID) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(currentHost);
            currentHost.onInitializeAccessibilityNodeInfo(info);
            for (Node node : currentNodes.values()) {
                if (node.parent == 0) info.addChild(currentHost, node.id);
            }
            return info;
        }
        Node node = currentNodes.get(virtualViewId);
        if (node == null) return null;
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSource(currentHost, node.id);
        info.setPackageName(currentHost.getContext().getPackageName());
        info.setClassName(androidClass(node.role));
        if (node.parent == 0) info.setParent(currentHost);
        else info.setParent(currentHost, node.parent);
        for (int child : node.children) info.addChild(currentHost, child);
        info.setText(node.text.isEmpty() ? null : node.text);
        info.setContentDescription(node.description.isEmpty() ? null : node.description);
        info.setEnabled(node.enabled);
        info.setFocusable(node.focusable);
        info.setFocused(node.id == currentInputFocus);
        info.setClickable(node.clickable);
        info.setEditable(node.editable);
        info.setCheckable(node.checkable);
        info.setChecked(node.checked);
        info.setPassword(node.password);
        info.setAccessibilityFocused(node.id == currentAccessibilityFocus);
        if (Build.VERSION.SDK_INT >= 28) info.setScreenReaderFocusable(true);
        if (node.clickable) info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (node.editable) info.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT);
        if (node.focusable) info.addAction(AccessibilityNodeInfo.ACTION_FOCUS);
        info.addAction(node.id == currentAccessibilityFocus
                ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        Rect parentBounds = scaleBounds(node.bounds, currentHost, currentWidth, currentHeight);
        info.setBoundsInParent(parentBounds);
        int[] location = new int[2];
        currentHost.getLocationOnScreen(location);
        Rect screen = new Rect(parentBounds);
        screen.offset(location[0], location[1]);
        info.setBoundsInScreen(screen);
        info.setVisibleToUser(currentHost.isShown() && Rect.intersects(
                new Rect(0, 0, currentHost.getWidth(), currentHost.getHeight()), parentBounds));
        return info;
    }

    @Override
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(
            String searched, int virtualViewId) {
        if (searched == null || searched.isBlank()) return Collections.emptyList();
        String match = searched.toLowerCase(Locale.ROOT);
        List<Integer> ids = new ArrayList<>();
        synchronized (lock) {
            for (Node node : nodes.values()) {
                if (node.text.toLowerCase(Locale.ROOT).contains(match)
                        || node.description.toLowerCase(Locale.ROOT).contains(match)) {
                    ids.add(node.id);
                }
            }
        }
        ArrayList<AccessibilityNodeInfo> result = new ArrayList<>();
        for (int id : ids) {
            AccessibilityNodeInfo info = createAccessibilityNodeInfo(id);
            if (info != null) result.add(info);
        }
        return result;
    }

    @Override
    public AccessibilityNodeInfo findFocus(int focus) {
        int id;
        synchronized (lock) {
            id = focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
                    ? accessibilityFocus : inputFocus;
        }
        return id == 0 ? null : createAccessibilityNodeInfo(id);
    }

    @Override
    public boolean performAction(int virtualViewId, int action, Bundle arguments) {
        Node node;
        synchronized (lock) {
            node = nodes.get(virtualViewId);
        }
        if (node == null) return false;
        if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
            synchronized (lock) {
                accessibilityFocus = virtualViewId;
            }
            sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
            synchronized (lock) {
                if (accessibilityFocus != virtualViewId) return false;
                accessibilityFocus = 0;
            }
            sendEvent(virtualViewId,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            return true;
        }
        String name;
        String text = "";
        if (action == AccessibilityNodeInfo.ACTION_CLICK && node.clickable) name = "click";
        else if (action == AccessibilityNodeInfo.ACTION_FOCUS && node.focusable) name = "focus";
        else if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && node.editable) {
            name = "set-text";
            CharSequence value = arguments == null ? null : arguments.getCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
            text = boundedString(value == null ? "" : value.toString(), "action text", MAX_TEXT);
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) name = "scroll-forward";
        else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) name = "scroll-backward";
        else return false;
        if (!actions.offer(new LinuxAction(virtualViewId, name, text))) return false;
        if ("focus".equals(name)) {
            synchronized (lock) {
                inputFocus = virtualViewId;
            }
        }
        return true;
    }

    private void sendEvent(int nodeId, int type) {
        View currentHost;
        Node node;
        synchronized (lock) {
            currentHost = host;
            node = nodes.get(nodeId);
        }
        if (currentHost == null) return;
        currentHost.post(() -> {
            if (nodeId == 0) {
                currentHost.sendAccessibilityEvent(type);
                return;
            }
            if (currentHost.getParent() == null) return;
            AccessibilityEvent event = AccessibilityEvent.obtain(type);
            event.setPackageName(currentHost.getContext().getPackageName());
            event.setClassName(node == null ? "android.view.View" : androidClass(node.role));
            event.setSource(currentHost, nodeId);
            if (node != null && !node.text.isEmpty()) event.getText().add(node.text);
            currentHost.getParent().requestSendAccessibilityEvent(currentHost, event);
        });
    }

    private static byte[] readBounded(FileDescriptor descriptor) throws IOException {
        ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(descriptor);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(duplicate);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_TREE_BYTES) {
                    throw new IOException("Accessibility tree exceeds size limit");
                }
                output.write(buffer, 0, read);
            }
            if (output.size() == 0) throw new IOException("Accessibility tree is empty");
            return output.toByteArray();
        }
    }

    private static void validateParentChain(Node node, Map<Integer, Node> nodes) {
        int current = node.parent;
        for (int depth = 0; current != 0; depth++) {
            if (depth >= nodes.size()) {
                throw new IllegalArgumentException("Accessibility parent cycle");
            }
            Node parent = nodes.get(current);
            if (parent == null) throw new IllegalArgumentException("Invalid accessibility parent");
            current = parent.parent;
        }
    }

    private static Rect scaleBounds(Rect source, View host, int viewportWidth,
            int viewportHeight) {
        float scaleX = host.getWidth() / (float)Math.max(1, viewportWidth);
        float scaleY = host.getHeight() / (float)Math.max(1, viewportHeight);
        return new Rect(Math.round(source.left * scaleX), Math.round(source.top * scaleY),
                Math.round(source.right * scaleX), Math.round(source.bottom * scaleY));
    }

    private static void validateRole(String role) {
        switch (role) {
            case "window":
            case "view":
            case "button":
            case "checkbox":
            case "radio":
            case "edit":
            case "text-field":
            case "image":
            case "list":
            case "list-item":
            case "menu":
            case "menu-item":
            case "slider":
            case "text":
            case "label":
                return;
            default:
                throw new IllegalArgumentException("Unknown accessibility role");
        }
    }
    private static String androidClass(String role) {
        return switch (role) {
            case "button" -> "android.widget.Button";
            case "checkbox" -> "android.widget.CheckBox";
            case "radio" -> "android.widget.RadioButton";
            case "edit", "text-field" -> "android.widget.EditText";
            case "image" -> "android.widget.ImageView";
            case "list" -> "android.widget.ListView";
            case "list-item" -> "android.view.View";
            case "menu" -> "android.widget.ListView";
            case "menu-item" -> "android.widget.Button";
            case "slider" -> "android.widget.SeekBar";
            case "text", "label" -> "android.widget.TextView";
            default -> "android.view.View";
        };
    }

    private static int boundedInt(JSONObject source, String name, int minimum, int maximum)
            throws JSONException {
        int value = source.getInt(name);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Accessibility " + name + " is out of range");
        }
        return value;
    }

    private static String boundedString(String value, String label, int maximum) {
        if (value == null || value.length() > maximum) {
            throw new IllegalArgumentException("Accessibility " + label + " is invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current) && current != '\n' && current != '\t') {
                throw new IllegalArgumentException(
                        "Accessibility " + label + " contains control characters");
            }
        }
        return value;
    }
}
