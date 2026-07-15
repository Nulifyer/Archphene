package org.archphene.bridge;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One serialized native compositor runtime shared by every Linux wrapper. */
public final class ArchpheneCompositorSession implements AutoCloseable {
    private static final String INPUT_TAG = "ArchpheneInput";

    public interface Listener {
        void onClientConnected();
        void onFrame(Bitmap frame);
        default void onWindows(List<WindowFrame> windows) {}
        void onError(String detail);
    }

    public static final class WindowFrame {
        public final NativeCompositor.WindowInfo window;
        public final Bitmap bitmap;

        WindowFrame(NativeCompositor.WindowInfo window, Bitmap bitmap) {
            this.window = window;
            this.bitmap = bitmap;
        }
    }

    private static final int CONFIGURE = 1;
    private static final int POINTER_MOTION = 2;
    private static final int POINTER_BUTTON = 3;
    private static final int POINTER_AXIS = 4;
    private static final int POINTER_LEAVE = 5;
    private static final int KEY = 6;
    private static final int TOUCH_DOWN = 7;
    private static final int TOUCH_MOTION = 8;
    private static final int TOUCH_UP = 9;
    private static final int TOUCH_CANCEL = 10;
    private static final int PRESENT = 11;
    private static final int IME_PREEDIT = 12;
    private static final int IME_COMMIT = 13;
    private static final int IME_DELETE = 14;
    private static final int IME_ACTION = 15;
    private static final int CLIPBOARD_CHANGED = 16;
    private static final int DISMISS_POPUPS = 17;
    private static final int SWIPE_BEGIN = 18;
    private static final int SWIPE_UPDATE = 19;
    private static final int SWIPE_END = 20;
    private static final int PINCH_BEGIN = 21;
    private static final int PINCH_UPDATE = 22;
    private static final int PINCH_END = 23;
    private static final int HOLD_BEGIN = 24;
    private static final int HOLD_END = 25;
    private static final int ACTIVATE_WINDOW = 26;
    private static final int WINDOW_MODE = 27;
    private static final int CONFIGURE_WINDOW = 28;
    private static final int CLOSE_WINDOW = 29;

    private final Activity activity;
    private final ArchpheneInputView view;
    private volatile ArchpheneInputView inputView;
    private volatile int inputViewGeneration;
    private final Listener listener;
    private final ArchpheneClipboardBroker clipboard;
    private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final Set<Integer> pointerFallbackTouches = new HashSet<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile Throwable startupError;
    private final AtomicBoolean frameRequestPosted = new AtomicBoolean();
    private Thread thread;
    private volatile int acceptedClients;
    private volatile int popupCount;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile long lastImeCommitUptime;
    private volatile boolean retainedIme;
    private volatile boolean independentWindows;
    private float gestureInitialSpan;
    private float gestureInitialAngle;
    private float gestureLastCentroidX;
    private float gestureLastCentroidY;
    private boolean gestureActive;
    private boolean holdActive;

    public ArchpheneCompositorSession(
            Activity activity, ArchpheneInputView view, Listener listener) {
        this.activity = activity;
        this.view = view;
        inputView = view;
        this.listener = listener;
        clipboard = new ArchpheneClipboardBroker(
                activity, () -> events.offer(Event.simple(CLIPBOARD_CHANGED)));
    }

    public void start(File socket, int width, int height) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Compositor session already started");
        }
        clipboard.start();
        thread = new Thread(
                () -> run(socket, Math.max(1, width), Math.max(1, height)),
                "archphene-native-compositor");
        thread.start();
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out binding Wayland compositor socket");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding Wayland compositor socket", error);
        }
        if (startupError != null) {
            throw new IllegalStateException("Could not bind Wayland compositor socket", startupError);
        }
    }

    public boolean awaitClient(long timeoutMillis) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (running.get() && SystemClock.uptimeMillis() < deadline) {
            if (acceptedClients > 0) return true;
            Thread.sleep(10);
        }
        return acceptedClients > 0;
    }

    public void configure(int width, int height, int scale) {
        events.offer(new Event(CONFIGURE, width, height, Math.max(1, scale), 0, ""));
    }

    public void setIndependentWindows(boolean enabled) {
        independentWindows = enabled;
        events.offer(new Event(WINDOW_MODE, enabled ? 1 : 0, 0, 0, 0, ""));
    }

    public void activateWindow(int id, ArchpheneInputView target) {
        if (target != null && target != inputView) {
            inputView = target;
            inputViewGeneration++;
        }
        events.offer(new Event(ACTIVATE_WINDOW, id, 0, 0, 0, ""));
    }

    public void configureWindow(int id, int width, int height) {
        events.offer(new Event(CONFIGURE_WINDOW, id, width, height, 0, ""));
    }

    public void closeWindow(int id) {
        events.offer(new Event(CLOSE_WINDOW, id, 0, 0, 0, ""));
    }


    public void pointerMotion(float x, float y, long time) {
        pointerMotion(0, view, frameWidth, frameHeight, x, y, time);
    }

    public void pointerMotion(
            int windowId,
            ArchpheneInputView source,
            int targetWidth,
            int targetHeight,
            float x,
            float y,
            long time) {
        if (windowId != 0) activateWindow(windowId, source);
        events.offer(new Event(
                POINTER_MOTION,
                Math.round(mapCoordinate(x, source, targetWidth, true)),
                Math.round(mapCoordinate(y, source, targetHeight, false)),
                (int) time,
                0,
                ""));
    }

    public void pointerButton(boolean pressed, long time) {
        if (pressed) releaseRetainedIme();
        events.offer(new Event(POINTER_BUTTON, pressed ? 1 : 0, (int) time, 0, 0, ""));
    }

    public void pointerAxis(float horizontal, float vertical, long time) {
        events.offer(new Event(
                POINTER_AXIS,
                Math.round(horizontal * 1000),
                Math.round(vertical * 1000),
                (int) time,
                0,
                ""));
    }

    public void pointerLeave() { events.offer(Event.simple(POINTER_LEAVE)); }

    public void touch(MotionEvent event) {
        touch(0, view, frameWidth, frameHeight, event);
    }

    public void touch(
            int windowId,
            ArchpheneInputView source,
            int targetWidth,
            int targetHeight,
            MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) releaseRetainedIme();
        if (windowId != 0 && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            activateWindow(windowId, source);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && !insideDisplayedImage(source, event.getX(), event.getY())) return;
        routeGesture(event, source, targetWidth, targetHeight);
        int action = event.getActionMasked();
        int time = (int) event.getEventTime();
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                events.offer(new Event(
                        TOUCH_MOTION,
                        event.getPointerId(i),
                        Math.round(mapCoordinate(event.getX(i), source, targetWidth, true)),
                        Math.round(mapCoordinate(event.getY(i), source, targetHeight, false)),
                        time,
                        ""));
            }
            return;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            events.offer(Event.simple(TOUCH_CANCEL));
            return;
        }
        int index = event.getActionIndex();
        int type = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                ? TOUCH_DOWN
                : action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                        ? TOUCH_UP : 0;
        if (type != 0) {
            events.offer(new Event(
                    type,
                    event.getPointerId(index),
                    Math.round(mapCoordinate(event.getX(index), source, targetWidth, true)),
                    Math.round(mapCoordinate(event.getY(index), source, targetHeight, false)),
                    time,
                    ""));
        }
    }

    private void routeGesture(
            MotionEvent event,
            ArchpheneInputView source,
            int targetWidth,
            int targetHeight) {
        int action = event.getActionMasked();
        int time = (int) event.getEventTime();
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            float firstX = mapCoordinate(event.getX(0), source, targetWidth, true);
            float firstY = mapCoordinate(event.getY(0), source, targetHeight, false);
            float secondX = mapCoordinate(event.getX(1), source, targetWidth, true);
            float secondY = mapCoordinate(event.getY(1), source, targetHeight, false);
            float dx = secondX - firstX;
            float dy = secondY - firstY;
            gestureInitialSpan = Math.max(1f, (float) Math.hypot(dx, dy));
            gestureInitialAngle = (float) Math.atan2(dy, dx);
            gestureLastCentroidX = (firstX + secondX) / 2f;
            gestureLastCentroidY = (firstY + secondY) / 2f;
            gestureActive = true;
            holdActive = true;
            events.offer(new Event(SWIPE_BEGIN, 2, time, 0, 0, ""));
            events.offer(new Event(PINCH_BEGIN, 2, time, 0, 0, ""));
            events.offer(new Event(HOLD_BEGIN, 2, time, 0, 0, ""));
            return;
        }
        if (action == MotionEvent.ACTION_MOVE
                && event.getPointerCount() >= 2 && gestureActive) {
            float firstX = mapCoordinate(event.getX(0), source, targetWidth, true);
            float firstY = mapCoordinate(event.getY(0), source, targetHeight, false);
            float secondX = mapCoordinate(event.getX(1), source, targetWidth, true);
            float secondY = mapCoordinate(event.getY(1), source, targetHeight, false);
            float centroidX = (firstX + secondX) / 2f;
            float centroidY = (firstY + secondY) / 2f;
            float dx = secondX - firstX;
            float dy = secondY - firstY;
            float scale = (float) Math.hypot(dx, dy) / gestureInitialSpan;
            float rotation = (float) Math.atan2(dy, dx) - gestureInitialAngle;
            int motionX = Math.round((centroidX - gestureLastCentroidX) * 1000);
            int motionY = Math.round((centroidY - gestureLastCentroidY) * 1000);
            if (holdActive) {
                events.offer(new Event(HOLD_END, 1, time, 0, 0, ""));
                holdActive = false;
            }
            events.offer(new Event(SWIPE_UPDATE, motionX, motionY, time, 0, ""));
            events.offer(new Event(PINCH_UPDATE, motionX, motionY,
                    Math.round(scale * 1000), Math.round(rotation * 1000), time, ""));
            gestureLastCentroidX = centroidX;
            gestureLastCentroidY = centroidY;
            return;
        }
        if ((action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) && gestureActive) {
            int cancelled = action == MotionEvent.ACTION_CANCEL ? 1 : 0;
            events.offer(new Event(SWIPE_END, cancelled, time, 0, 0, ""));
            events.offer(new Event(PINCH_END, cancelled, time, 0, 0, ""));
            if (holdActive) events.offer(new Event(HOLD_END, cancelled, time, 0, 0, ""));
            gestureActive = false;
            holdActive = false;
            gestureInitialSpan = 0f;
        }
    }
    public boolean key(KeyEvent event) {
        int linuxKey = toLinuxKeyCode(event.getKeyCode());
        if (linuxKey == 0 || event.getRepeatCount() != 0) return false;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        events.offer(new Event(
                KEY, linuxKey, action == KeyEvent.ACTION_DOWN ? 1 : 0,
                (int) event.getEventTime(), 0, ""));
        return true;
    }

    public void imePreedit(String text, int begin, int end) {
        events.offer(new Event(IME_PREEDIT, begin, end, 0, 0, text));
    }

    public void imeCommit(String text) {
        lastImeCommitUptime = SystemClock.uptimeMillis();
        events.offer(new Event(IME_COMMIT, 0, 0, 0, 0, text));
    }

    public void imeDelete(int before, int after) {
        events.offer(new Event(IME_DELETE, before, after, 0, 0, ""));
    }

    public void imeAction(int action) {
        events.offer(new Event(
                IME_ACTION, action, (int) SystemClock.uptimeMillis(), 0, 0, ""));
    }

    public void dismissPopups() { events.offer(Event.simple(DISMISS_POPUPS)); }

    public boolean hasPopups() { return popupCount > 0; }

    private void run(File socket, int width, int height) {
        try (NativeCompositor compositor = new NativeCompositor(socket)) {
            compositor.setToplevelTiling(true);
            compositor.configureOutput(width, height, 1);
            ready.countDown();
            int lastCommit = -1;
            int lastShow = 0;
            int lastHide = 0;
            int lastPopupCount = -1;
            String lastPopupSignature = "";
            int lastCursorWidth = -1;
            int lastCursorHeight = -1;
            int lastWindowSerial = -1;
            int lastImeViewGeneration = -1;
            while (running.get()) {
                Event first = events.poll(4, TimeUnit.MILLISECONDS);
                if (first != null) {
                    handleEvent(compositor, first);
                    Event event;
                    while ((event = events.poll()) != null) handleEvent(compositor, event);
                }
                int dispatched = compositor.dispatch();
                if (dispatched < 0) {
                    throw new IllegalStateException("native dispatch failed: " + dispatched);
                }
                popupCount = compositor.xdgPopupCount();
                if (popupCount != lastPopupCount) {
                    lastPopupCount = popupCount;
                    lastPopupSignature = "";
                }
                StringBuilder popupState = new StringBuilder();
                for (int index = 0; index < popupCount; index++) {
                    popupState.append(index).append(':')
                            .append(compositor.popupComponent(index, 0)).append(',')
                            .append(compositor.popupComponent(index, 1)).append(',')
                            .append(compositor.popupComponent(index, 2)).append(',')
                            .append(compositor.popupComponent(index, 3)).append(',')
                            .append(compositor.popupComponent(index, 4)).append(',')
                            .append(compositor.popupComponent(index, 5)).append(',')
                            .append(compositor.popupComponent(index, 6)).append(',')
                            .append(compositor.popupComponent(index, 7)).append(';');
                }
                String popupSignature = popupState.toString();
                if (!popupSignature.equals(lastPopupSignature)) {
                    lastPopupSignature = popupSignature;
                    Log.i(INPUT_TAG, "popup registry=" + popupSignature);
                }
                int windowSerial = compositor.windowChangeSerial();
                if (windowSerial != lastWindowSerial) {
                    lastWindowSerial = windowSerial;
                    int count = compositor.windowCount();
                    for (int index = 0; index < count; index++) {
                        NativeCompositor.WindowInfo window = compositor.window(index);
                        if (window != null) {
                            Log.i(INPUT_TAG, "window id=" + window.id
                                    + " parent=" + window.parentId
                                    + " mapped=" + window.mapped
                                    + " active=" + window.active
                                    + " primary=" + window.primary
                                    + " geometry=" + window.x + "," + window.y + " "
                                    + window.width + "x" + window.height
                                    + " frame=" + window.frameWidth + "x" + window.frameHeight
                                    + " bufferScale=" + window.bufferScale
                                    + " title=" + window.title
                                    + " appId=" + window.appId);
                        }
                    }
                    publishFrame(compositor);
                }
                int currentClients = compositor.acceptedClients();
                if (currentClients != acceptedClients) {
                    acceptedClients = currentClients;
                    compositor.setClipboardActive(currentClients > 0);
                    activity.runOnUiThread(listener::onClientConnected);
                }
                int commit = compositor.surfaceCommits();
                if (commit != lastCommit) {
                    lastCommit = commit;
                    publishFrame(compositor);
                    if (independentWindows) publishWindows(compositor);
                }
                if (compositor.pendingFrameCallbacks() > 0
                        && frameRequestPosted.compareAndSet(false, true)) {
                    activity.runOnUiThread(() -> Choreographer.getInstance()
                            .postFrameCallback(frameTimeNanos -> {
                                frameRequestPosted.set(false);
                                events.offer(new Event(
                                        PRESENT,
                                        (int) (frameTimeNanos / 1_000_000L),
                                        0, 0, 0, ""));
                            }));
                }
                int show = compositor.imeShowRequests();
                int imeViewGeneration = inputViewGeneration;
                if (show != lastShow) {
                    lastShow = show;
                    lastImeViewGeneration = imeViewGeneration;
                    retainedIme = false;
                    Log.d(INPUT_TAG, "Wayland IME show request=" + show);
                    showIme(compositor);
                } else if (compositor.imeActive() && imeViewGeneration != lastImeViewGeneration) {
                    lastImeViewGeneration = imeViewGeneration;
                    Log.d(INPUT_TAG, "Wayland IME retarget generation=" + imeViewGeneration);
                    showIme(compositor);
                }
                int hide = compositor.imeHideRequests();
                if (hide != lastHide) {
                    lastHide = hide;
                    lastImeViewGeneration = -1;
                    long sinceCommit = SystemClock.uptimeMillis() - lastImeCommitUptime;
                    if (lastImeCommitUptime > 0 && sinceCommit >= 0 && sinceCommit < 750) {
                        retainedIme = true;
                        Log.i(INPUT_TAG, "Wayland text input retained for keyboard navigation");
                    } else {
                        retainedIme = false;
                        hideIme();
                    }
                }
                int cursorWidth = compositor.cursorWidth();
                int cursorHeight = compositor.cursorHeight();
                if (cursorWidth != lastCursorWidth || cursorHeight != lastCursorHeight) {
                    lastCursorWidth = cursorWidth;
                    lastCursorHeight = cursorHeight;
                    publishCursor(compositor, cursorWidth, cursorHeight);
                }
                drainClipboard(compositor);
            }
            compositor.setClipboardActive(false);
        } catch (InterruptedException ignored) {
            startupError = ignored;
            ready.countDown();
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            startupError = error;
            ready.countDown();
            if (running.get()) {
                activity.runOnUiThread(() -> listener.onError(error.toString()));
            }
        } finally {
            running.set(false);
        }
    }

    private void handleEvent(NativeCompositor compositor, Event event) {
        switch (event.type) {
            case CONFIGURE -> compositor.configureOutput(event.a, event.b, event.c);
            case POINTER_MOTION -> compositor.pointerMotion(event.a, event.b, event.c);
            case POINTER_BUTTON -> {
                int result = compositor.pointerButton(event.a != 0, event.b);
                Log.d(INPUT_TAG, "pointer button pressed=" + (event.a != 0)
                        + " result=" + result);
            }
            case POINTER_AXIS -> compositor.pointerAxis(event.a / 1000f, event.b / 1000f, event.c);
            case POINTER_LEAVE -> compositor.pointerLeave();
            case KEY -> compositor.keyboardKey(event.a, event.b != 0, event.c);
            case TOUCH_DOWN -> {
                int result = compositor.touchDown(event.a, event.b, event.c, event.d);
                if (result == 0 && pointerFallbackTouches.isEmpty()) {
                    compositor.pointerMotion(event.b, event.c, event.d);
                    if (compositor.pointerButton(true, event.d) == 1) {
                        pointerFallbackTouches.add(event.a);
                    }
                }
                Log.d(INPUT_TAG, "touch down id=" + event.a + " x=" + event.b
                        + " y=" + event.c + " result=" + result
                        + " pointerFallback=" + pointerFallbackTouches.contains(event.a)
                        + " pointers=" + compositor.pointerCount()
                        + " touches=" + compositor.touchCount());
            }
            case TOUCH_MOTION -> {
                if (pointerFallbackTouches.contains(event.a)) {
                    compositor.pointerMotion(event.b, event.c, event.d);
                } else {
                    compositor.touchMotion(event.a, event.b, event.c, event.d);
                }
            }
            case TOUCH_UP -> {
                int result;
                if (pointerFallbackTouches.remove(event.a)) {
                    compositor.pointerMotion(event.b, event.c, event.d);
                    result = compositor.pointerButton(false, event.d);
                } else {
                    result = compositor.touchUp(event.a, event.d);
                }
                Log.d(INPUT_TAG, "touch up id=" + event.a + " result=" + result
                        + " textInputs=" + compositor.textInputCount()
                        + " imeActive=" + compositor.imeActive());
            }
            case TOUCH_CANCEL -> {
                compositor.touchCancel();
                if (!pointerFallbackTouches.isEmpty()) {
                    compositor.pointerButton(false, (int) SystemClock.uptimeMillis());
                    pointerFallbackTouches.clear();
                }
            }
            case PRESENT -> compositor.present(event.a);
            case IME_PREEDIT -> compositor.imePreedit(event.text, event.a, event.b);
            case IME_COMMIT -> {
                if (compositor.imeCommit(event.text) == 0) {
                    sendImeFallbackKeys(compositor, event.text);
                }
            }
            case IME_DELETE -> {
                if (compositor.imeDelete(event.a, event.b) == 0) {
                    int time = (int) SystemClock.uptimeMillis();
                    compositor.keyboardKey(14, true, time);
                    compositor.keyboardKey(14, false, time);
                }
            }
            case IME_ACTION -> compositor.imeEditorAction(event.a, event.b);
            case CLIPBOARD_CHANGED -> compositor.offerAndroidClipboard();
            case DISMISS_POPUPS -> compositor.dismissPopups();
            case SWIPE_BEGIN -> compositor.swipeBegin(event.a, event.b);
            case SWIPE_UPDATE -> compositor.swipeUpdate(event.a / 1000f, event.b / 1000f, event.c);
            case SWIPE_END -> compositor.swipeEnd(event.a != 0, event.b);
            case PINCH_BEGIN -> compositor.pinchBegin(event.a, event.b);
            case PINCH_UPDATE -> compositor.pinchUpdate(event.a / 1000f, event.b / 1000f,
                    event.c / 1000f, event.d / 1000f, event.e);
            case PINCH_END -> compositor.pinchEnd(event.a != 0, event.b);
            case HOLD_BEGIN -> compositor.holdBegin(event.a, event.b);
            case HOLD_END -> compositor.holdEnd(event.a != 0, event.b);
            case ACTIVATE_WINDOW -> compositor.activateWindow(event.a);
            case WINDOW_MODE -> {
                compositor.setToplevelTiling(event.a == 0);
                publishFrame(compositor);
                if (independentWindows) publishWindows(compositor);
            }
            case CONFIGURE_WINDOW -> compositor.configureWindow(event.a, event.b, event.c);
            case CLOSE_WINDOW -> compositor.closeWindow(event.a);
            default -> throw new IllegalStateException("Unknown compositor event " + event.type);
        }
    }

    private void publishFrame(NativeCompositor compositor) {
        int width = compositor.frameWidth();
        int height = compositor.frameHeight();
        if (width <= 0 || height <= 0) return;
        if (width != frameWidth || height != frameHeight) {
            Log.i(INPUT_TAG, "output frame=" + width + "x" + height);
        }
        frameWidth = width;
        frameHeight = height;
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (compositor.copyFrame(frame) != 0) return;
        activity.runOnUiThread(() -> listener.onFrame(frame));
    }

    private void publishWindows(NativeCompositor compositor) {
        List<WindowFrame> windows = new ArrayList<>();
        int count = compositor.windowCount();
        for (int index = 0; index < count; index++) {
            NativeCompositor.WindowInfo window = compositor.window(index);
            if (window == null || !window.mapped || window.width <= 0 || window.height <= 0) {
                continue;
            }
            Bitmap frame = Bitmap.createBitmap(
                    window.width, window.height, Bitmap.Config.ARGB_8888);
            if (compositor.copyWindowFrame(index, frame) == 0) {
                windows.add(new WindowFrame(window, frame));
            }
        }
        List<WindowFrame> snapshot = List.copyOf(windows);
        activity.runOnUiThread(() -> listener.onWindows(snapshot));
    }
    private void publishCursor(NativeCompositor compositor, int width, int height) {
        if (width <= 0 || height <= 0) {
            ArchpheneInputView target = inputView;
            activity.runOnUiThread(() -> target.setPointerIcon(
                    PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_NULL)));
            return;
        }
        Bitmap cursor = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (compositor.copyCursor(cursor) != 0) return;
        float x = compositor.cursorHotspot(0);
        float y = compositor.cursorHotspot(1);
        ArchpheneInputView target = inputView;
        activity.runOnUiThread(
                () -> target.setPointerIcon(PointerIcon.create(cursor, x, y)));
    }

    private void releaseRetainedIme() {
        if (!retainedIme) return;
        retainedIme = false;
        lastImeCommitUptime = 0;
        Log.i(INPUT_TAG, "Wayland retained text input released by app interaction");
        hideIme();
    }

    private static void sendImeFallbackKeys(NativeCompositor compositor, String text) {
        if (text == null || text.isEmpty()) return;
        KeyEvent[] keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(text.toCharArray());
        if (keys == null) return;
        int sent = 0;
        for (KeyEvent key : keys) {
            int linuxKey = toLinuxKeyCode(key.getKeyCode());
            if (linuxKey == 0) continue;
            boolean pressed = key.getAction() == KeyEvent.ACTION_DOWN;
            if (compositor.keyboardKey(linuxKey, pressed,
                    (int) SystemClock.uptimeMillis()) == 1) {
                sent++;
            }
        }
        Log.d(INPUT_TAG, "Android IME keyboard fallback events=" + sent);
    }

    private void showIme(NativeCompositor compositor) {
        String text = compositor.imeSurroundingText();
        int anchor = utf8OffsetToUtf16(text, compositor.imeAnchor());
        int cursor = utf8OffsetToUtf16(text, compositor.imeCursor());
        ArchpheneInputView.EditorState state = new ArchpheneInputView.EditorState(
                text, anchor, cursor, compositor.imeHint(), compositor.imePurpose());
        ArchpheneInputView target = inputView;
        activity.runOnUiThread(() -> {
            target.setEditorState(state);
            target.requestFocus();
            target.postDelayed(() -> {
                InputMethodManager input = (InputMethodManager)
                        activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                input.restartInput(target);
                if (target.getWindowInsetsController() != null) {
                    target.getWindowInsetsController().show(WindowInsets.Type.ime());
                }
                boolean accepted = input.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
                Log.d(INPUT_TAG, "IME show focused=" + target.hasWindowFocus()
                        + " accepted=" + accepted);
            }, 150);
        });
    }

    private void hideIme() {
        activity.runOnUiThread(() -> {
            InputMethodManager input = (InputMethodManager)
                    activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            ArchpheneInputView target = inputView;
            input.hideSoftInputFromWindow(target.getWindowToken(), 0);
        });
    }

    private void drainClipboard(NativeCompositor compositor) {
        int linuxCopyFd;
        while ((linuxCopyFd = compositor.takeLinuxCopyFd()) >= 0) {
            String text = readFd(linuxCopyFd);
            activity.runOnUiThread(() -> clipboard.publishLinuxText(text));
        }
        int androidPasteFd;
        while ((androidPasteFd = compositor.takeAndroidPasteFd()) >= 0) {
            writeFd(androidPasteFd, clipboard.readTextForWaylandPaste());
        }
    }

    private static float mapCoordinate(float value, ArchpheneInputView source,
            int targetSize, boolean horizontal) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int sourceSize = horizontal ? sourceWidth : sourceHeight;
        if (targetSize <= 0 || sourceSize <= 0 || source.getDrawable() == null
                || source.getScaleType() != android.widget.ImageView.ScaleType.FIT_CENTER) {
            return targetSize > 0 && sourceSize > 0
                    ? value * targetSize / sourceSize : value;
        }
        int contentWidth = source.getDrawable().getIntrinsicWidth();
        int contentHeight = source.getDrawable().getIntrinsicHeight();
        if (contentWidth <= 0 || contentHeight <= 0) {
            return value * targetSize / sourceSize;
        }
        float scale = Math.min(sourceWidth / (float) contentWidth,
                sourceHeight / (float) contentHeight);
        int contentSize = horizontal ? contentWidth : contentHeight;
        float displayedSize = contentSize * scale;
        float sourceOffset = (sourceSize - displayedSize) / 2f;
        float local = Math.max(0f, Math.min(contentSize, (value - sourceOffset) / scale));
        float targetOffset = Math.max(0f, (targetSize - contentSize) / 2f);
        return targetOffset + local;
    }

    private static boolean insideDisplayedImage(
            ArchpheneInputView source, float x, float y) {
        if (source.getDrawable() == null
                || source.getScaleType() != android.widget.ImageView.ScaleType.FIT_CENTER) return true;
        int contentWidth = source.getDrawable().getIntrinsicWidth();
        int contentHeight = source.getDrawable().getIntrinsicHeight();
        if (contentWidth <= 0 || contentHeight <= 0
                || source.getWidth() <= 0 || source.getHeight() <= 0) return true;
        float scale = Math.min(source.getWidth() / (float) contentWidth,
                source.getHeight() / (float) contentHeight);
        float left = (source.getWidth() - contentWidth * scale) / 2f;
        float top = (source.getHeight() - contentHeight * scale) / 2f;
        return x >= left && x < left + contentWidth * scale
                && y >= top && y < top + contentHeight * scale;
    }
    private static String readFd(int fd) {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.adoptFd(fd);
                FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > 8 * 1024 * 1024) break;
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void writeFd(int fd, String text) {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.adoptFd(fd);
                FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static int utf8OffsetToUtf16(String value, int offset) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (offset < 0 || offset > encoded.length) return 0;
        return new String(encoded, 0, offset, StandardCharsets.UTF_8).length();
    }

    private static int toLinuxKeyCode(int androidKeyCode) {
        if (androidKeyCode >= KeyEvent.KEYCODE_A && androidKeyCode <= KeyEvent.KEYCODE_Z) {
            int[] linuxLetters = {
                30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
                49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44
            };
            return linuxLetters[androidKeyCode - KeyEvent.KEYCODE_A];
        }
        if (androidKeyCode >= KeyEvent.KEYCODE_0 && androidKeyCode <= KeyEvent.KEYCODE_9) {
            int[] linuxDigits = {11, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            return linuxDigits[androidKeyCode - KeyEvent.KEYCODE_0];
        }
        return switch (androidKeyCode) {
            case KeyEvent.KEYCODE_ENTER -> 28;
            case KeyEvent.KEYCODE_DEL -> 14;
            case KeyEvent.KEYCODE_FORWARD_DEL -> 111;
            case KeyEvent.KEYCODE_SPACE -> 57;
            case KeyEvent.KEYCODE_TAB -> 15;
            case KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> 1;
case KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> 13;
            case KeyEvent.KEYCODE_MINUS -> 12;
            case KeyEvent.KEYCODE_LEFT_BRACKET -> 26;
            case KeyEvent.KEYCODE_RIGHT_BRACKET -> 27;
            case KeyEvent.KEYCODE_SEMICOLON -> 39;
            case KeyEvent.KEYCODE_APOSTROPHE -> 40;
            case KeyEvent.KEYCODE_GRAVE -> 41;
            case KeyEvent.KEYCODE_BACKSLASH -> 43;
            case KeyEvent.KEYCODE_COMMA -> 51;
            case KeyEvent.KEYCODE_PERIOD -> 52;
            case KeyEvent.KEYCODE_SLASH -> 53;
            case KeyEvent.KEYCODE_SHIFT_LEFT -> 42;
            case KeyEvent.KEYCODE_SHIFT_RIGHT -> 54;
            case KeyEvent.KEYCODE_CTRL_LEFT -> 29;
            case KeyEvent.KEYCODE_CTRL_RIGHT -> 97;
            case KeyEvent.KEYCODE_ALT_LEFT -> 56;
            case KeyEvent.KEYCODE_ALT_RIGHT -> 100;
            case KeyEvent.KEYCODE_DPAD_UP -> 103;
            case KeyEvent.KEYCODE_DPAD_LEFT -> 105;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> 106;
            case KeyEvent.KEYCODE_DPAD_DOWN -> 108;
            case KeyEvent.KEYCODE_MOVE_HOME -> 102;
            case KeyEvent.KEYCODE_MOVE_END -> 107;
            case KeyEvent.KEYCODE_PAGE_UP -> 104;
            case KeyEvent.KEYCODE_PAGE_DOWN -> 109;
            case KeyEvent.KEYCODE_INSERT -> 110;
            case KeyEvent.KEYCODE_F1 -> 59;
            case KeyEvent.KEYCODE_F2 -> 60;
            case KeyEvent.KEYCODE_F3 -> 61;
            case KeyEvent.KEYCODE_F4 -> 62;
            case KeyEvent.KEYCODE_F5 -> 63;
            case KeyEvent.KEYCODE_F6 -> 64;
            case KeyEvent.KEYCODE_F7 -> 65;
            case KeyEvent.KEYCODE_F8 -> 66;
            case KeyEvent.KEYCODE_F9 -> 67;
            case KeyEvent.KEYCODE_F10 -> 68;
            case KeyEvent.KEYCODE_F11 -> 87;
            case KeyEvent.KEYCODE_F12 -> 88;
            default -> 0;
        };
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) return;
        clipboard.close();
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            try {
                current.join(3000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Event {
        final int type;
        final int a;
        final int b;
        final int c;
        final int d;
        final int e;
        final String text;

        Event(int type, int a, int b, int c, int d, String text) {
            this(type, a, b, c, d, 0, text);
        }

        Event(int type, int a, int b, int c, int d, int e, String text) {
            this.type = type;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.e = e;
            this.text = text;
        }

        static Event simple(int type) { return new Event(type, 0, 0, 0, 0, ""); }
    }
}