package org.archphene.bridge;

import android.graphics.Bitmap;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Stable Java facade for the shared native Wayland compositor. */
public final class NativeCompositor implements AutoCloseable {
    private static final int DISPATCH = 1;
    private static final int CONFIGURE_OUTPUT = 2;
    private static final int ACCEPTED_CLIENTS = 3;
    private static final int SURFACE_COMMITS = 4;
    private static final int FRAME_WIDTH = 5;
    private static final int FRAME_HEIGHT = 6;
    private static final int PENDING_FRAME_CALLBACKS = 7;
    private static final int PRESENT = 8;
    private static final int POINTER_MOTION = 9;
    private static final int POINTER_BUTTON = 10;
    private static final int POINTER_AXIS = 11;
    private static final int POINTER_LEAVE = 12;
    private static final int KEYBOARD_KEY = 13;
    private static final int TOUCH_DOWN = 14;
    private static final int TOUCH_MOTION = 15;
    private static final int TOUCH_UP = 16;
    private static final int TOUCH_CANCEL = 17;
    private static final int IME_ACTIVE = 18;
    private static final int IME_SHOW_REQUESTS = 19;
    private static final int IME_HIDE_REQUESTS = 20;
    private static final int IME_TEXT_LENGTH = 21;
    private static final int IME_CURSOR = 22;
    private static final int IME_ANCHOR = 23;
    private static final int IME_HINT = 24;
    private static final int IME_PURPOSE = 25;
    private static final int IME_RECTANGLE = 26;
    private static final int IME_DELETE = 27;
    private static final int IME_EDITOR_ACTION = 28;
    private static final int CLIPBOARD_ACTIVE = 29;
    private static final int CLIPBOARD_OFFER = 30;
    private static final int ANDROID_PASTE_FD = 31;
    private static final int LINUX_COPY_FD = 32;
    private static final int CURSOR_WIDTH = 33;
    private static final int CURSOR_HEIGHT = 34;
    private static final int CURSOR_HOTSPOT = 35;
    private static final int DISMISS_POPUPS = 36;
    private static final int DAMAGE_COUNT = 37;
    private static final int DAMAGE_COMPONENT = 38;
    private static final int XDG_POPUP_COUNT = 39;
    private static final int SWIPE_BEGIN = 40;
    private static final int SWIPE_UPDATE = 41;
    private static final int SWIPE_END = 42;
    private static final int PINCH_BEGIN = 43;
    private static final int PINCH_UPDATE = 44;
    private static final int PINCH_END = 45;
    private static final int HOLD_BEGIN = 46;
    private static final int HOLD_END = 47;
    private static final int TOPLEVEL_TILING = 48;
    private static final int WINDOW_COUNT = 49;
    private static final int WINDOW_CHANGE_SERIAL = 50;
    private static final int WINDOW_COMPONENT = 51;
    private static final int ACTIVATE_WINDOW = 52;
    private static final int CONFIGURE_WINDOW = 53;
    private static final int CLOSE_WINDOW = 54;
    private static final int TEXT_INPUT_COUNT = 55;
    private static final int POINTER_COUNT = 56;
    private static final int TOUCH_COUNT = 57;
    private static final int POPUP_COMPONENT = 58;
    private static final int ANDROID_DRAG_MOTION = 59;
    private static final int ANDROID_DRAG_CANCEL = 60;
    private static final int LINUX_DRAG_FD = 61;
    private static final int LINUX_DRAG_FINISH = 62;
    private static final int LINUX_DRAG_MIME_LENGTH = 63;

    static { System.loadLibrary("archphene_compositor"); }

    private long handle;

    public NativeCompositor(File socket) {
        handle = nativeCreate(socket.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        if (handle == 0) throw new IllegalStateException("Could not create native compositor");
    }

    public int dispatch() { return command(DISPATCH); }
    public int setToplevelTiling(boolean enabled) {
        return command(TOPLEVEL_TILING, enabled ? 1 : 0);
    }
    public int configureOutput(int width, int height, int scale) {
        return command(CONFIGURE_OUTPUT, width, height, scale);
    }
    public int acceptedClients() { return command(ACCEPTED_CLIENTS); }
    public int surfaceCommits() { return command(SURFACE_COMMITS); }
    public int frameWidth() { return command(FRAME_WIDTH); }
    public int frameHeight() { return command(FRAME_HEIGHT); }
    public int pendingFrameCallbacks() { return command(PENDING_FRAME_CALLBACKS); }
    public int present(int timeMillis) { return command(PRESENT, timeMillis); }
    public int pointerMotion(int x, int y, int timeMillis) {
        return command(POINTER_MOTION, x, y, timeMillis);
    }
    public int pointerButton(boolean pressed, int timeMillis) {
        return command(POINTER_BUTTON, pressed ? 1 : 0, timeMillis);
    }
    public int pointerAxis(float horizontal, float vertical, int timeMillis) {
        return command(POINTER_AXIS, Math.round(horizontal * 1000),
                Math.round(vertical * 1000), timeMillis);
    }
    public int pointerLeave() { return command(POINTER_LEAVE); }
    public int keyboardKey(int linuxKey, boolean pressed, int timeMillis) {
        return command(KEYBOARD_KEY, linuxKey, pressed ? 1 : 0, timeMillis);
    }
    public int touchDown(int id, int x, int y, int timeMillis) {
        return command(TOUCH_DOWN, id, x, y, timeMillis);
    }
    public int touchMotion(int id, int x, int y, int timeMillis) {
        return command(TOUCH_MOTION, id, x, y, timeMillis);
    }
    public int touchUp(int id, int timeMillis) {
        return command(TOUCH_UP, id, timeMillis);
    }
    public int touchCancel() { return command(TOUCH_CANCEL); }
    public boolean imeActive() { return command(IME_ACTIVE) == 1; }
    public int imeShowRequests() { return command(IME_SHOW_REQUESTS); }
    public int imeHideRequests() { return command(IME_HIDE_REQUESTS); }
    public int imeTextLength() { return command(IME_TEXT_LENGTH); }
    public int imeCursor() { return command(IME_CURSOR); }
    public int imeAnchor() { return command(IME_ANCHOR); }
    public int imeHint() { return command(IME_HINT); }
    public int imePurpose() { return command(IME_PURPOSE); }
    public int imeRectangle(int component) { return command(IME_RECTANGLE, component); }
    public String imeSurroundingText() {
        int length = imeTextLength();
        if (length < 0) return "";
        byte[] text = new byte[length];
        if (nativeBytes(handle, 1, text, 0, 0) != length) return "";
        return new String(text, StandardCharsets.UTF_8);
    }
    public int imeCommit(String text) {
        return nativeBytes(handle, 2, text.getBytes(StandardCharsets.UTF_8), 0, 0);
    }
    public int imePreedit(String text, int begin, int end) {
        return nativeBytes(handle, 3, text.getBytes(StandardCharsets.UTF_8), begin, end);
    }
    public int imeDelete(int before, int after) { return command(IME_DELETE, before, after); }
    public int imeEditorAction(int action, int timeMillis) {
        return command(IME_EDITOR_ACTION, action, timeMillis);
    }
    public int setClipboardActive(boolean active) {
        return command(CLIPBOARD_ACTIVE, active ? 1 : 0);
    }
    public int offerAndroidClipboard() { return command(CLIPBOARD_OFFER); }
    public int takeAndroidPasteFd() { return command(ANDROID_PASTE_FD); }
    public int takeLinuxCopyFd() { return command(LINUX_COPY_FD); }
    public int androidDragMotion(int x, int y, int timeMillis) {
        return command(ANDROID_DRAG_MOTION, x, y, timeMillis);
    }
    public int androidDropText(String text) {
        return nativeBytes(handle, 6, text.getBytes(StandardCharsets.UTF_8), 0, 0);
    }
    public int androidDropUriList(String uriList) {
        return nativeBytes(handle, 7, uriList.getBytes(StandardCharsets.UTF_8), 0, 0);
    }
    public int cancelAndroidDrag() { return command(ANDROID_DRAG_CANCEL); }
    public int takeLinuxDragFd() { return command(LINUX_DRAG_FD); }
    public String takeLinuxDragMimeType() {
        int length = command(LINUX_DRAG_MIME_LENGTH);
        if (length <= 0 || length > 256) return "";
        byte[] mimeType = new byte[length];
        if (nativeBytes(handle, 8, mimeType, 0, 0) != length) return "";
        return new String(mimeType, StandardCharsets.UTF_8);
    }
    public int finishLinuxDrag(boolean accepted) {
        return command(LINUX_DRAG_FINISH, accepted ? 1 : 0);
    }
    public int cursorWidth() { return command(CURSOR_WIDTH); }
    public int cursorHeight() { return command(CURSOR_HEIGHT); }
    public int cursorHotspot(int component) { return command(CURSOR_HOTSPOT, component); }
    public int dismissPopups() { return command(DISMISS_POPUPS); }
    public int damageCount() { return command(DAMAGE_COUNT); }
    public int damageComponent(int component) { return command(DAMAGE_COMPONENT, component); }
    public int xdgPopupCount() { return command(XDG_POPUP_COUNT); }
    public int swipeBegin(int fingers, int time) {
        return command(SWIPE_BEGIN, fingers, time);
    }
    public int swipeUpdate(float dx, float dy, int time) {
        return command(SWIPE_UPDATE, Math.round(dx * 1000), Math.round(dy * 1000), time);
    }
    public int swipeEnd(boolean cancelled, int time) {
        return command(SWIPE_END, cancelled ? 1 : 0, time);
    }
    public int pinchBegin(int fingers, int time) {
        return command(PINCH_BEGIN, fingers, time);
    }
    public int pinchUpdate(float dx, float dy, float scale, float rotation, int time) {
        return command(PINCH_UPDATE, Math.round(dx * 1000), Math.round(dy * 1000),
                Math.round(scale * 1000), Math.round(rotation * 1000), time);
    }
    public int pinchEnd(boolean cancelled, int time) {
        return command(PINCH_END, cancelled ? 1 : 0, time);
    }
    public int holdBegin(int fingers, int time) {
        return command(HOLD_BEGIN, fingers, time);
    }
    public int holdEnd(boolean cancelled, int time) {
        return command(HOLD_END, cancelled ? 1 : 0, time);
    }
    public int windowCount() { return command(WINDOW_COUNT); }
    public int windowChangeSerial() { return command(WINDOW_CHANGE_SERIAL); }
    public int activateWindow(int id) { return command(ACTIVATE_WINDOW, id); }
    public int configureWindow(int id, int width, int height) {
        return command(CONFIGURE_WINDOW, id, width, height);
    }
    public int closeWindow(int id) { return command(CLOSE_WINDOW, id); }
    public int textInputCount() { return command(TEXT_INPUT_COUNT); }
    public int pointerCount() { return command(POINTER_COUNT); }
    public int touchCount() { return command(TOUCH_COUNT); }
    public int popupComponent(int index, int component) {
        return command(POPUP_COMPONENT, index, component);
    }
    public WindowInfo window(int index) {
        int id = command(WINDOW_COMPONENT, index, 0);
        if (id < 0) return null;
        int titleLength = command(WINDOW_COMPONENT, index, 9);
        int appIdLength = command(WINDOW_COMPONENT, index, 10);
        return new WindowInfo(
                id,
                command(WINDOW_COMPONENT, index, 1),
                command(WINDOW_COMPONENT, index, 2) == 1,
                command(WINDOW_COMPONENT, index, 3) == 1,
                command(WINDOW_COMPONENT, index, 4) == 1,
                command(WINDOW_COMPONENT, index, 5),
                command(WINDOW_COMPONENT, index, 6),
                command(WINDOW_COMPONENT, index, 7),
                command(WINDOW_COMPONENT, index, 8),
                command(WINDOW_COMPONENT, index, 11),
                command(WINDOW_COMPONENT, index, 12),
                command(WINDOW_COMPONENT, index, 13),
                command(WINDOW_COMPONENT, index, 14),
                command(WINDOW_COMPONENT, index, 15),
                command(WINDOW_COMPONENT, index, 16),
                command(WINDOW_COMPONENT, index, 17),
                command(WINDOW_COMPONENT, index, 18),
                command(WINDOW_COMPONENT, index, 19),
                command(WINDOW_COMPONENT, index, 20),
                command(WINDOW_COMPONENT, index, 21),
                command(WINDOW_COMPONENT, index, 22),
                command(WINDOW_COMPONENT, index, 23),
                windowText(index, titleLength, 4),
                windowText(index, appIdLength, 5));
    }

    private String windowText(int index, int length, int command) {
        if (length <= 0) return "";
        byte[] value = new byte[length];
        if (nativeBytes(handle, command, value, index, 0) != length) return "";
        return new String(value, StandardCharsets.UTF_8);
    }

    public static final class WindowInfo {
        public final int id;
        public final int parentId;
        public final boolean mapped;
        public final boolean active;
        public final boolean primary;
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final int frameWidth;
        public final int frameHeight;
        public final int bufferScale;
        public final int contentX;
        public final int contentY;
        public final int contentWidth;
        public final int contentHeight;
        public final int canvasWidth;
        public final int canvasHeight;
        public final int compositedFrameX;
        public final int compositedFrameY;
        public final int compositedFrameWidth;
        public final int compositedFrameHeight;
        public final String title;
        public final String appId;

        WindowInfo(int id, int parentId, boolean mapped, boolean active, boolean primary,
                int x, int y, int width, int height, int frameWidth, int frameHeight,
                int bufferScale, int contentX, int contentY, int contentWidth,
                int contentHeight, int canvasWidth, int canvasHeight,
                int compositedFrameX, int compositedFrameY,
                int compositedFrameWidth, int compositedFrameHeight,
                String title, String appId) {
            this.id = id;
            this.parentId = parentId;
            this.mapped = mapped;
            this.active = active;
            this.primary = primary;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.bufferScale = bufferScale;
            this.contentX = contentX;
            this.contentY = contentY;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
            this.canvasWidth = canvasWidth;
            this.canvasHeight = canvasHeight;
            this.compositedFrameX = compositedFrameX;
            this.compositedFrameY = compositedFrameY;
            this.compositedFrameWidth = compositedFrameWidth;
            this.compositedFrameHeight = compositedFrameHeight;
            this.title = title;
            this.appId = appId;
        }
    }
    public int copyFrame(Bitmap bitmap) { return nativeBitmap(handle, 1, 0, bitmap); }
    public int copyCursor(Bitmap bitmap) { return nativeBitmap(handle, 2, 0, bitmap); }
    public int copyWindowFrame(int index, Bitmap bitmap) {
        return nativeBitmap(handle, 3, index, bitmap);
    }

    private int command(int command, int... values) {
        int[] args = new int[5];
        System.arraycopy(values, 0, args, 0, Math.min(values.length, args.length));
        return nativeInt(handle, command, args[0], args[1], args[2], args[3], args[4]);
    }

    @Override
    public void close() {
        long current = handle;
        handle = 0;
        if (current != 0) nativeDestroy(current);
    }

    private static native long nativeCreate(byte[] socketPath);
    private static native int nativeInt(
            long handle, int command, int a, int b, int c, int d, int e);
    private static native int nativeBytes(
            long handle, int command, byte[] value, int a, int b);
    private static native int nativeBitmap(long handle, int command, int value, Bitmap bitmap);
    private static native void nativeDestroy(long handle);
}