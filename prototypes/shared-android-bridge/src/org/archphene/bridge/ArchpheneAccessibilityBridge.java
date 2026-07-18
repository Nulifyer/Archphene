package org.archphene.bridge;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    static final class PopupFrame {
        final int x;
        final int y;
        final int width;
        final int height;
        final int frameWidth;
        final int frameHeight;

        PopupFrame(int x, int y, int width, int height,
                int frameWidth, int frameHeight) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
        }
    }
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


    static final class WindowDescriptor {
        final int id;
        final int parentId;
        final boolean active;
        final boolean primary;
        final int width;
        final int height;
        final int compositedFrameX;
        final int compositedFrameY;
        final int compositedFrameWidth;
        final int compositedFrameHeight;
        final int canvasWidth;
        final int canvasHeight;
        final String title;

        WindowDescriptor(int id, int parentId, boolean primary,
                int width, int height, String title) {
            this(id, parentId, primary, primary, width, height,
                    0, 0, width, height, width, height, title);
        }

        WindowDescriptor(int id, int parentId, boolean active, boolean primary,
                int width, int height, int compositedFrameX, int compositedFrameY,
                int compositedFrameWidth, int compositedFrameHeight,
                int canvasWidth, int canvasHeight,
                String title) {
            this.id = id;
            this.parentId = parentId;
            this.active = active;
            this.primary = primary;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.compositedFrameX = compositedFrameX;
            this.compositedFrameY = compositedFrameY;
            this.compositedFrameWidth = Math.max(0, compositedFrameWidth);
            this.compositedFrameHeight = Math.max(0, compositedFrameHeight);
            this.canvasWidth = Math.max(0, canvasWidth);
            this.canvasHeight = Math.max(0, canvasHeight);
            this.title = title == null ? "" : title;
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
        final boolean scrollForward;
        final boolean scrollBackward;
        final String windowTitle;
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
            scrollForward = source.optBoolean("scrollForward", false);
            scrollBackward = source.optBoolean("scrollBackward", false);
            windowTitle = boundedString(source.optString("windowTitle", ""),
                    "window title", MAX_TEXT);
        }
    }

    private final Object lock = new Object();
    private final ArchpheneAccessibilityBridge root;
    private final ArrayBlockingQueue<LinuxAction> actions;
    private final Map<Integer, ArchpheneAccessibilityBridge> windowBridges =
            new LinkedHashMap<>();
    private final Map<Integer, WindowDescriptor> windowDescriptors =
            new LinkedHashMap<>();
    private volatile List<PopupFrame> popupFrames =
            Collections.emptyList();
    private final Map<Integer, Integer> semanticWindowAssignments = new HashMap<>();
    private View host;
    private int hostWindowId;
    private int primaryWindowId;
    private int activeWindowId;
    private boolean independentWindows;
    private int viewportLeft;
    private int viewportTop;
    private int viewportWidth = 1;
    private int viewportHeight = 1;
    private int viewportRootId;
    private int targetLeft;
    private int targetTop;
    private int targetWidth = 1;
    private int targetHeight = 1;
    private int targetCanvasWidth = 1;
    private int targetCanvasHeight = 1;
    private Map<Integer, Node> allNodes = Collections.emptyMap();
    private Map<Integer, Node> nodes = Collections.emptyMap();
    private int accessibilityFocus;
    private int inputFocus;
    interface MenuFallback {
        void activate(int windowId, View host, float x, float y, boolean transition);
    }

    private volatile MenuFallback menuFallback;

    ArchpheneAccessibilityBridge() {
        root = this;
        actions = new ArrayBlockingQueue<>(MAX_ACTIONS);
    }

    private ArchpheneAccessibilityBridge(
            ArchpheneAccessibilityBridge root, ArrayBlockingQueue<LinuxAction> actions) {
        this.root = root;
        this.actions = actions;
    }

    void attach(View view) {
        attach(view, 0);
    }

    AccessibilityNodeProvider attach(View view, int windowId) {
        if (this != root) return root.attach(view, windowId);
        ArchpheneAccessibilityBridge provider;
        synchronized (lock) {
            if (host == null || host == view) {
                host = view;
                hostWindowId = windowId;
                provider = this;
            } else {
                provider = windowBridges.get(windowId);
                if (provider == null || provider.host != view) {
                    provider = new ArchpheneAccessibilityBridge(this, actions);
                    provider.host = view;
                    provider.hostWindowId = windowId;
                    windowBridges.put(windowId, provider);
                }
            }
        }
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        redistribute();
        view.post(() -> view.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        return provider;
    }

    void detach(View view) {
        if (this != root) {
            root.detach(view);
            return;
        }
        synchronized (lock) {
            if (host == view) {
                host = null;
            } else {
                windowBridges.values().removeIf(provider -> provider.host == view);
            }
            if (host == null && windowBridges.isEmpty()) actions.clear();
        }
    }

    void setMenuFallback(MenuFallback fallback) {
        root.menuFallback = fallback;
    }

    boolean isTextInputAt(View candidateHost, float x, float y) {
        ArchpheneAccessibilityBridge owner = null;
        synchronized (root.lock) {
            if (root.host == candidateHost) {
                owner = root;
            } else {
                for (ArchpheneAccessibilityBridge provider : root.windowBridges.values()) {
                    if (provider.host == candidateHost) {
                        owner = provider;
                        break;
                    }
                }
            }
        }
        if (owner == null) return false;
        int pointX = Math.round(x);
        int pointY = Math.round(y);
        synchronized (owner.lock) {
            for (Node node : owner.nodes.values()) {
                boolean textInput = node.editable || "edit".equals(node.role)
                        || "text-field".equals(node.role)
                        || ("text".equals(node.role) && node.focusable);
                if (!node.enabled || !node.focusable || !textInput) continue;
                Rect bounds = owner.displayBounds(node, candidateHost);
                if (bounds.contains(pointX, pointY)) return true;
            }
        }
        return false;
    }

    boolean isMenuAt(View candidateHost, float x, float y) {
        ArchpheneAccessibilityBridge owner = null;
        synchronized (root.lock) {
            if (root.host == candidateHost) {
                owner = root;
            } else {
                for (ArchpheneAccessibilityBridge provider : root.windowBridges.values()) {
                    if (provider.host == candidateHost) {
                        owner = provider;
                        break;
                    }
                }
            }
        }
        if (owner == null) return false;
        int pointX = Math.round(x);
        int pointY = Math.round(y);
        synchronized (owner.lock) {
            for (Node node : owner.nodes.values()) {
                boolean menu = "menu".equals(node.role) || "menu-item".equals(node.role);
                if (!node.enabled || !menu) continue;
                Rect bounds = owner.displayBounds(node, candidateHost);
                if (bounds.contains(pointX, pointY)) return true;
            }
        }
        return false;
    }

    boolean isClickableAt(View candidateHost, float x, float y) {
        ArchpheneAccessibilityBridge owner = null;
        synchronized (root.lock) {
            if (root.host == candidateHost) {
                owner = root;
            } else {
                for (ArchpheneAccessibilityBridge provider : root.windowBridges.values()) {
                    if (provider.host == candidateHost) {
                        owner = provider;
                        break;
                    }
                }
            }
        }
        if (owner == null) return false;
        int pointX = Math.round(x);
        int pointY = Math.round(y);
        synchronized (owner.lock) {
            for (Node node : owner.nodes.values()) {
                if (!node.enabled || !node.clickable || !node.children.isEmpty()) continue;
                if (owner.displayBounds(node, candidateHost).contains(pointX, pointY)) {
                    return true;
                }
            }
        }
        return false;
    }

    void activateMenuFallback(int nodeId, boolean transition) {
        MenuFallback fallback = root.menuFallback;
        if (fallback == null) {
            throw new IllegalStateException("Accessibility menu fallback is unavailable");
        }
        ArchpheneAccessibilityBridge owner = root.bridgeForNode(nodeId);
        View currentHost;
        int currentWindowId;
        Rect bounds;
        Rect rawBounds;
        String nodeSummary;
        synchronized (owner.lock) {
            Node node = owner.nodes.get(nodeId);
            if (node == null || owner.host == null) {
                throw new IllegalArgumentException("Accessibility menu node is unavailable");
            }
            currentHost = owner.host;
            currentWindowId = owner.hostWindowId;
            rawBounds = new Rect(node.bounds);
            bounds = owner.displayBounds(node, currentHost);
            nodeSummary = "id=" + node.id + " role=" + node.role
                    + " text=" + node.text + " parent=" + node.parent;
        }
        Log.d("ArchpheneAccessibility", "menu fallback " + nodeSummary
                + " transition=" + transition + " raw=" + rawBounds
                + " display=" + bounds + " host=" + currentHost.getWidth()
                + "x" + currentHost.getHeight());
        fallback.activate(currentWindowId, currentHost,
                bounds.exactCenterX(), bounds.exactCenterY(), transition);
    }

    void updatePopups(List<PopupFrame> frames) {
        root.popupFrames = frames == null || frames.isEmpty()
                ? Collections.emptyList() : List.copyOf(frames);
        root.redistribute();
    }

    void updateWindows(List<WindowDescriptor> frames,
            boolean independent) {
        if (this != root) {
            root.updateWindows(frames, independent);
            return;
        }
        synchronized (lock) {
            windowDescriptors.clear();
            primaryWindowId = 0;
            independentWindows = independent;
            for (WindowDescriptor frame : frames) {
                if (frame.primary) primaryWindowId = frame.id;
            }
            if (primaryWindowId == 0) {
                for (WindowDescriptor frame : frames) {
                    if (frame.parentId == 0) {
                        primaryWindowId = frame.id;
                        break;
                    }
                }
            }
            int nextActiveWindowId = primaryWindowId;
            for (WindowDescriptor frame : frames) {
                windowDescriptors.put(frame.id, frame);
                if (frame.active) nextActiveWindowId = frame.id;
            }
            if (!independent && activeWindowId != nextActiveWindowId) {
                semanticWindowAssignments.clear();
            }
            activeWindowId = nextActiveWindowId;
            semanticWindowAssignments.values().removeIf(
                    windowId -> !windowDescriptors.containsKey(windowId));
        }
        redistribute();
    }

    void publish(FileDescriptor descriptor) throws Exception {
        byte[] json = readBounded(descriptor);
        JSONObject document = new JSONObject(new String(json, StandardCharsets.UTF_8));
        int newWidth = boundedInt(document, "viewportWidth", 1, MAX_VIEWPORT);
        int newHeight = boundedInt(document, "viewportHeight", 1, MAX_VIEWPORT);
        JSONArray sourceNodes = document.getJSONArray("nodes");
        if (sourceNodes.length() == 0) {
            if (!document.optBoolean("clear", false)) {
                throw new IllegalArgumentException("Accessibility tree is empty");
            }
            synchronized (root.lock) {
                root.viewportWidth = newWidth;
                root.viewportHeight = newHeight;
                root.allNodes = Collections.emptyMap();
            }
            actions.clear();
            root.redistribute();
            return;
        }
        if (sourceNodes.length() > MAX_NODES) {
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
        ArchpheneAccessibilityBridge owner = root;
        synchronized (owner.lock) {
            owner.viewportWidth = newWidth;
            owner.viewportHeight = newHeight;
            owner.allNodes = Collections.unmodifiableMap(parsed);
        }
        owner.redistribute();
    }

    void sendNamedEvent(int nodeId, String type) {
        if (this == root) {
            ArchpheneAccessibilityBridge target = bridgeForNode(nodeId);
            if (target != this) {
                target.sendNamedEvent(nodeId, type);
                return;
            }
        }
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

    private void redistribute() {
        if (this != root) {
            root.redistribute();
            return;
        }
        Map<Integer, Node> source;
        int rootWindow;
        List<ArchpheneAccessibilityBridge> providers;
        Map<Integer, Integer> assignments;
        synchronized (lock) {
            source = allNodes;
            int primary = primaryWindowId != 0 ? primaryWindowId : hostWindowId;
            int active = activeWindowId != 0 ? activeWindowId : primary;
            rootWindow = independentWindows ? hostWindowId : active;
            providers = new ArrayList<>(windowBridges.values());
            assignments = assignWindows(source, windowDescriptors, active,
                    independentWindows, semanticWindowAssignments);
        }
        installSubset(source, assignments, rootWindow);
        for (ArchpheneAccessibilityBridge provider : providers) {
            provider.installSubset(source, assignments, provider.hostWindowId);
        }
    }

    private void installSubset(Map<Integer, Node> source,
            Map<Integer, Integer> assignments, int windowId) {
        LinkedHashMap<Integer, Node> subset = new LinkedHashMap<>();
        for (Map.Entry<Integer, Node> entry : source.entrySet()) {
            if (assignments.getOrDefault(entry.getKey(), windowId) == windowId) {
                subset.put(entry.getKey(), entry.getValue());
            }
        }
        int subsetLeft = 0;
        int subsetTop = 0;
        int subsetWidth = root.viewportWidth;
        int subsetHeight = root.viewportHeight;
        WindowDescriptor descriptor;
        boolean independent;
        synchronized (root.lock) {
            descriptor = root.windowDescriptors.get(windowId);
            independent = root.independentWindows;
        }
        Node viewport = null;
        int viewportPriority = -1;
        long viewportArea = -1;
        for (Node node : subset.values()) {
            if (node.parent != 0) continue;
            boolean titleMatch = descriptor != null && !descriptor.title.isBlank()
                    && descriptor.title.equals(node.windowTitle);
            int priority = titleMatch ? 2 : ("window".equals(node.role) ? 1 : 0);
            long area = (long) node.bounds.width() * node.bounds.height();
            if (viewport == null || priority > viewportPriority
                    || (priority == viewportPriority && area > viewportArea)) {
                viewport = node;
                viewportPriority = priority;
                viewportArea = area;
            }
        }
        if (viewport != null) {
            subsetLeft = viewport.bounds.left;
            subsetTop = viewport.bounds.top;
            subsetWidth = Math.max(1, viewport.bounds.width());
            subsetHeight = Math.max(1, viewport.bounds.height());
        } else if (!subset.isEmpty()) {
            int subsetRight = subsetLeft;
            int subsetBottom = subsetTop;
            boolean first = true;
            for (Node node : subset.values()) {
                if (first) {
                    subsetLeft = node.bounds.left;
                    subsetTop = node.bounds.top;
                    subsetRight = node.bounds.right;
                    subsetBottom = node.bounds.bottom;
                    first = false;
                } else {
                    subsetLeft = Math.min(subsetLeft, node.bounds.left);
                    subsetTop = Math.min(subsetTop, node.bounds.top);
                    subsetRight = Math.max(subsetRight, node.bounds.right);
                    subsetBottom = Math.max(subsetBottom, node.bounds.bottom);
                }
            }
            subsetWidth = Math.max(1, subsetRight - subsetLeft);
            subsetHeight = Math.max(1, subsetBottom - subsetTop);
        }
        int mappedLeft = 0;
        int mappedTop = 0;
        int mappedWidth = subsetWidth;
        int mappedHeight = subsetHeight;
        int canvasWidth = subsetWidth;
        int canvasHeight = subsetHeight;
        if (!independent && descriptor != null
                && descriptor.compositedFrameWidth > 0 && descriptor.compositedFrameHeight > 0
                && descriptor.canvasWidth > 0 && descriptor.canvasHeight > 0) {
            mappedLeft = descriptor.compositedFrameX;
            mappedTop = descriptor.compositedFrameY;
            mappedWidth = descriptor.compositedFrameWidth;
            mappedHeight = descriptor.compositedFrameHeight;
            canvasWidth = descriptor.canvasWidth;
            canvasHeight = descriptor.canvasHeight;
        }
        synchronized (lock) {
            viewportLeft = subsetLeft;
            viewportTop = subsetTop;
            viewportWidth = subsetWidth;
            viewportHeight = subsetHeight;
            viewportRootId = viewport == null ? 0 : viewport.id;
            targetLeft = mappedLeft;
            targetTop = mappedTop;
            targetWidth = mappedWidth;
            targetHeight = mappedHeight;
            targetCanvasWidth = canvasWidth;
            targetCanvasHeight = canvasHeight;
            nodes = Collections.unmodifiableMap(subset);
            if (!nodes.containsKey(accessibilityFocus)) accessibilityFocus = 0;
            if (!nodes.containsKey(inputFocus)) inputFocus = 0;
        }
        sendEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }
    private static Map<Integer, Integer> assignWindows(Map<Integer, Node> source,
            Map<Integer, WindowDescriptor> windows, int primary, boolean independent,
            Map<Integer, Integer> stickyAssignments) {
        HashMap<Integer, Integer> result = new HashMap<>();
        HashSet<Integer> roots = new HashSet<>();
        for (Node node : source.values()) {
            if (node.parent == 0) roots.add(node.id);
        }
        stickyAssignments.keySet().removeIf(id -> !roots.contains(id));

        HashSet<Integer> assignedWindows = new HashSet<>();
        for (Node node : source.values()) {
            if (node.parent != 0) continue;
            Integer sticky = stickyAssignments.get(node.id);
            int windowId = sticky != null && windows.containsKey(sticky)
                    && (!independent || !assignedWindows.contains(sticky))
                    ? sticky : 0;
            if (windowId == 0) {
                String title = node.windowTitle.isBlank() ? node.text : node.windowTitle;
                windowId = matchingWindow(windows, title, node.bounds.width(),
                        node.bounds.height(), primary, independent, assignedWindows);
            }
            if (windowId != 0) {
                stickyAssignments.put(node.id, windowId);
                if (independent) assignedWindows.add(windowId);
            } else {
                stickyAssignments.remove(node.id);
            }
            ArrayList<Integer> pending = new ArrayList<>();
            pending.add(node.id);
            for (int index = 0; index < pending.size(); index++) {
                int id = pending.get(index);
                if (result.put(id, windowId) != null) continue;
                Node current = source.get(id);
                if (current != null) pending.addAll(current.children);
            }
        }
        return result;
    }

    private static int matchingWindow(Map<Integer, WindowDescriptor> windows,
            String title, int width, int height, int primary, boolean independent,
            Set<Integer> assignedWindows) {
        if (!title.isBlank()) {
            for (WindowDescriptor window : windows.values()) {
                if (title.equals(window.title)
                        && (!independent || !assignedWindows.contains(window.id))) {
                    return window.id;
                }
            }
        }
        if (!independent) return primary;

        int closest = 0;
        long closestDifference = Long.MAX_VALUE;
        for (WindowDescriptor window : windows.values()) {
            if (assignedWindows.contains(window.id)) continue;
            long difference = Math.abs((long)width - window.width)
                    + Math.abs((long)height - window.height);
            if (difference < closestDifference) {
                closest = window.id;
                closestDifference = difference;
            }
        }
        if (closest != 0) return closest;
        if (primary != 0 && !assignedWindows.contains(primary)) return primary;
        for (int windowId : windows.keySet()) {
            if (!assignedWindows.contains(windowId)) return windowId;
        }
        return 0;
    }
    private ArchpheneAccessibilityBridge bridgeForNode(int nodeId) {
        synchronized (lock) {
            if (nodeId == 0 || nodes.containsKey(nodeId)) return this;
            for (ArchpheneAccessibilityBridge provider : windowBridges.values()) {
                synchronized (provider.lock) {
                    if (provider.nodes.containsKey(nodeId)) return provider;
                }
            }
        }
        return this;
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
        int currentLeft;
        int currentTop;
        int currentWidth;
        int currentHeight;
        int currentViewportRootId;
        int currentTargetLeft;
        int currentTargetTop;
        int currentTargetWidth;
        int currentTargetHeight;
        int currentCanvasWidth;
        int currentCanvasHeight;
        int currentAccessibilityFocus;
        int currentInputFocus;
        synchronized (lock) {
            currentHost = host;
            currentNodes = nodes;
            currentLeft = viewportLeft;
            currentTop = viewportTop;
            currentWidth = viewportWidth;
            currentHeight = viewportHeight;
            currentViewportRootId = viewportRootId;
            currentTargetLeft = targetLeft;
            currentTargetTop = targetTop;
            currentTargetWidth = targetWidth;
            currentTargetHeight = targetHeight;
            currentCanvasWidth = targetCanvasWidth;
            currentCanvasHeight = targetCanvasHeight;
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
        if (node.enabled && node.clickable)
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (node.enabled && node.editable)
            info.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT);
        if (node.enabled && node.scrollForward)
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (node.enabled && node.scrollBackward)
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        if (node.enabled && node.focusable)
            info.addAction(AccessibilityNodeInfo.ACTION_FOCUS);
        info.addAction(node.id == currentAccessibilityFocus
                ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        Rect adjustedBounds = popupAdjustedBounds(
                node, currentNodes, root.popupFrames, currentViewportRootId);
        Rect windowBounds = scaleBounds(adjustedBounds, currentHost,
                currentLeft, currentTop, currentWidth, currentHeight,
                currentTargetLeft, currentTargetTop,
                currentTargetWidth, currentTargetHeight,
                currentCanvasWidth, currentCanvasHeight);
        Rect parentBounds = new Rect(windowBounds);
        if (node.parent != 0) {
            Node parent = currentNodes.get(node.parent);
            if (parent != null) {
                Rect scaledParent = scaleBounds(parent.bounds, currentHost,
                        currentLeft, currentTop, currentWidth, currentHeight,
                        currentTargetLeft, currentTargetTop,
                        currentTargetWidth, currentTargetHeight,
                        currentCanvasWidth, currentCanvasHeight);
                parentBounds.offset(-scaledParent.left, -scaledParent.top);
            }
        }
        info.setBoundsInParent(parentBounds);
        int[] location = new int[2];
        currentHost.getLocationOnScreen(location);
        Rect screenBounds = new Rect(windowBounds);
        screenBounds.offset(location[0], location[1]);
        info.setBoundsInScreen(screenBounds);
        info.setVisibleToUser(currentHost.isShown() && Rect.intersects(
                new Rect(0, 0, currentHost.getWidth(), currentHost.getHeight()),
                windowBounds));
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
        if (!node.enabled) return false;
        if (action == AccessibilityNodeInfo.ACTION_CLICK && node.clickable) name = "click";
        else if (action == AccessibilityNodeInfo.ACTION_FOCUS && node.focusable) name = "focus";
        else if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && node.editable) {
            name = "set-text";
            CharSequence value = arguments == null ? null : arguments.getCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
            try {
                text = boundedString(
                        value == null ? "" : value.toString(), "action text", MAX_TEXT);
                if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) return false;
            } catch (IllegalArgumentException invalidText) {
                return false;
            }
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                && node.scrollForward) name = "scroll-forward";
        else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                && node.scrollBackward) name = "scroll-backward";
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
            AccessibilityManager manager = (AccessibilityManager) currentHost.getContext()
                    .getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null || !manager.isEnabled()) return;
            try {
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
            } catch (IllegalStateException accessibilityDisabled) {
                // Accessibility can be disabled after the state check; event delivery is optional.
            }
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

    private Rect displayBounds(Node node, View candidateHost) {
        Rect adjusted = popupAdjustedBounds(
                node, nodes, root.popupFrames, viewportRootId);
        return scaleBounds(adjusted, candidateHost,
                viewportLeft, viewportTop, viewportWidth, viewportHeight,
                targetLeft, targetTop, targetWidth, targetHeight,
                targetCanvasWidth, targetCanvasHeight);
    }

    private static Rect popupAdjustedBounds(Node node, Map<Integer, Node> nodes,
            List<PopupFrame> popups,
            int viewportRootId) {
        if (popups.isEmpty()) return node.bounds;
        int menuDepth = 0;
        int parentId = node.parent;
        Node rootAncestor = node;
        for (int depth = 0; parentId != 0 && depth < nodes.size(); depth++) {
            Node parent = nodes.get(parentId);
            if (parent == null) break;
            rootAncestor = parent;
            if ("menu".equals(parent.role)
                    && (!parent.text.isBlank() || !parent.description.isBlank())) {
                menuDepth++;
            }
            parentId = parent.parent;
        }
        int popupIndex = menuDepth > 0 ? Math.min(menuDepth - 1, popups.size() - 1) : -1;
        boolean detachedPopupRoot = rootAncestor.parent == 0
                && rootAncestor.id != viewportRootId;
        if (popupIndex < 0 && detachedPopupRoot) {
            popupIndex = popups.size() - 1;
        }
        if (popupIndex < 0) return node.bounds;
        PopupFrame popup = popups.get(popupIndex);
        if (popup.width == popup.frameWidth && popup.height == popup.frameHeight) {
            return node.bounds;
        }
        int frameX = popup.x - Math.floorDiv(popup.frameWidth - popup.width, 2);
        int frameY = popup.y - Math.floorDiv(popup.frameHeight - popup.height, 2);
        return new Rect(
                frameX + node.bounds.left,
                frameY + node.bounds.top,
                frameX + node.bounds.right,
                frameY + node.bounds.bottom);
    }

    private static Rect scaleBounds(Rect source, View host, int viewportLeft,
            int viewportTop, int viewportWidth, int viewportHeight) {
        return scaleBounds(source, host, viewportLeft, viewportTop,
                viewportWidth, viewportHeight, 0, 0, viewportWidth, viewportHeight,
                viewportWidth, viewportHeight);
    }

    private static Rect scaleBounds(Rect source, View host, int viewportLeft,
            int viewportTop, int viewportWidth, int viewportHeight,
            int targetLeft, int targetTop, int targetWidth, int targetHeight,
            int canvasWidth, int canvasHeight) {
        float contentScaleX = targetWidth / (float)Math.max(1, viewportWidth);
        float contentScaleY = targetHeight / (float)Math.max(1, viewportHeight);
        float hostScaleX = host.getWidth() / (float)Math.max(1, canvasWidth);
        float hostScaleY = host.getHeight() / (float)Math.max(1, canvasHeight);
        float offsetX = 0;
        float offsetY = 0;
        if (host instanceof ImageView image
                && image.getScaleType() == ImageView.ScaleType.FIT_CENTER) {
            float uniform = Math.min(hostScaleX, hostScaleY);
            hostScaleX = uniform;
            hostScaleY = uniform;
            offsetX = (host.getWidth() - canvasWidth * uniform) / 2f;
            offsetY = (host.getHeight() - canvasHeight * uniform) / 2f;
        }
        float left = targetLeft + (source.left - viewportLeft) * contentScaleX;
        float top = targetTop + (source.top - viewportTop) * contentScaleY;
        float right = targetLeft + (source.right - viewportLeft) * contentScaleX;
        float bottom = targetTop + (source.bottom - viewportTop) * contentScaleY;
        Rect scaled = new Rect(Math.round(offsetX + left * hostScaleX),
                Math.round(offsetY + top * hostScaleY),
                Math.round(offsetX + right * hostScaleX),
                Math.round(offsetY + bottom * hostScaleY));
        int hostWidth = Math.max(1, host.getWidth());
        int hostHeight = Math.max(1, host.getHeight());
        scaled.left = Math.max(0, Math.min(hostWidth - 1, scaled.left));
        scaled.top = Math.max(0, Math.min(hostHeight - 1, scaled.top));
        scaled.right = Math.max(scaled.left + 1, Math.min(hostWidth, scaled.right));
        scaled.bottom = Math.max(scaled.top + 1, Math.min(hostHeight, scaled.bottom));
        return scaled;
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
