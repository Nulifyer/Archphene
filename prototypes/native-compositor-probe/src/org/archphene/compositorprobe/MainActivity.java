package org.archphene.compositorprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String TAG = "ArchpheneCompositorProbe";
    private final LinkedBlockingQueue<PointerInput> pointerInputs =
            new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<KeyboardInput> keyboardInputs =
            new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<TouchInput> touchInputs =
            new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<GestureInput> gestureInputs =
            new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Integer> presentationTimes =
            new LinkedBlockingQueue<>();
    private volatile boolean pointerInputReady;
    private volatile boolean keyboardInputReady;
    private volatile boolean touchInputReady;
    private volatile boolean gestureInputReady;
    private float gestureInitialSpan;
    private float gestureInitialAngle;
    private float gestureLastCentroidX;
    private float gestureLastCentroidY;
    static { System.loadLibrary("archphene_compositor"); }

    private static native int nativeProtocolVersion();
    private static native long nativeCreateCore();
    private static native int nativeAdoptClient(long handle, int fd);
    private static native int nativeSendShmPoolRequest(
            int socketFd, int poolId, int poolSize, int callbackId);
    private static native int nativeReceiveKeyboardKeymap(int socketFd, int keyboardId);
    private static native int nativeSendDataOfferReceive(int socketFd, int offerId);
    private static native int nativeReceiveDataSourceSend(int socketFd, int sourceId);
    private static native int nativeDispatchOnce(long handle);
    private static native int nativeCompositorBindCount(long handle);
    private static native int nativeSubcompositorBindCount(long handle);
    private static native int nativeSubsurfaceCount(long handle);
    private static native int nativeXdgWmBaseBindCount(long handle);
    private static native int nativeXdgPositionerCount(long handle);
    private static native int nativeXdgPositionerRequestCount(long handle);
    private static native int nativeXdgPopupCount(long handle);
    private static native int nativeXdgPopupDoneCount(long handle);
    private static native int nativeXdgPopupGrabDepth(long handle);
    private static native int nativeDismissPopups(long handle);
    private static native int nativeXdgSurfaceCount(long handle);
    private static native int nativeXdgToplevelCount(long handle);
    private static native int nativeXdgAckCount(long handle);
    private static native int nativeConfigureFocusedToplevel(
            long handle, int width, int height);
    private static native int nativePendingConfigureCount(long handle);
    private static native int nativeOutputBindCount(long handle);
    private static native int nativeOutputCount(long handle);
    private static native int nativeOutputEventCount(long handle);
    private static native int nativeConfigureOutput(
            long handle, int width, int height, int scale);
    private static native int nativeSeatBindCount(long handle);
    private static native int nativeDataDeviceManagerBindCount(long handle);
    private static native int nativeDataSourceCount(long handle);
    private static native int nativeDataDeviceCount(long handle);
    private static native int nativeDataOfferCount(long handle);
    private static native int nativeSetClipboardActive(long handle, boolean active);
    private static native int nativeOfferAndroidClipboardText(long handle);
    private static native int nativeTakeAndroidPasteFd(long handle);
    private static native int nativeTakeLinuxCopyFd(long handle);
    private static native int nativeTextInputManagerBindCount(long handle);
    private static native int nativeTextInputCount(long handle);
    private static native int nativeImeActive(long handle);
    private static native int nativeImeShowRequestCount(long handle);
    private static native int nativeImeHideRequestCount(long handle);
    private static native int nativeImeSurroundingTextLength(long handle);
    private static native int nativeImeSurroundingCursor(long handle);
    private static native int nativeImeSurroundingAnchor(long handle);
    private static native int nativeImeContentHint(long handle);
    private static native int nativeImeContentPurpose(long handle);
    private static native int nativeImeCursorRectangleComponent(
            long handle, int component);
    private static native int nativeImeCommitProbeText(long handle);
    private static native int nativeImePreeditProbeText(long handle);
    private static native int nativeImeDeleteSurrounding(
            long handle, int beforeLength, int afterLength);
    private static native int nativePendingFrameCallbackCount(long handle);
    private static native int nativePendingDamageCount(long handle);
    private static native int nativePendingDamageComponent(long handle, int component);
    private static native int nativePresentFrame(long handle, int time);
    private static native int nativePointerCount(long handle);
    private static native int nativeSwipeBegin(long handle, int fingers, int time);
    private static native int nativeSwipeUpdate(
            long handle, int dxMilli, int dyMilli, int time);
    private static native int nativeSwipeEnd(long handle, boolean cancelled, int time);
    private static native int nativePinchBegin(long handle, int fingers, int time);
    private static native int nativePinchUpdate(
            long handle,
            int dxMilli,
            int dyMilli,
            int scaleMilli,
            int rotationMilli,
            int time);
    private static native int nativePinchEnd(long handle, boolean cancelled, int time);
    private static native int nativeHoldBegin(long handle, int fingers, int time);
    private static native int nativeHoldEnd(long handle, boolean cancelled, int time);
    private static native int nativeGestureEventCount(long handle);
    private static native int nativePointerEnterSerial(long handle);
    private static native int nativeCursorWidth(long handle);
    private static native int nativeCursorHeight(long handle);
    private static native int nativeCursorHotspot(long handle, int component);
    private static native int nativeCopyCursorToBitmap(long handle, Bitmap bitmap);
    private static native int nativeTouchCount(long handle);
    private static native int nativeTouchEventCount(long handle);
    private static native int nativeTouchDown(
            long handle, int id, int x, int y, int time);
    private static native int nativeTouchMotion(
            long handle, int id, int x, int y, int time);
    private static native int nativeTouchUp(long handle, int id, int time);
    private static native int nativeTouchCancel(long handle);
    private static native int nativeKeyboardCount(long handle);
    private static native int nativeKeyboardEventCount(long handle);
    private static native int nativeKeyboardKey(
            long handle, int key, boolean pressed, int time);
    private static native int nativePointerEventCount(long handle);
    private static native int nativePointerMotion(
            long handle, int x, int y, int time);
    private static native int nativePointerButton(
            long handle, boolean pressed, int time);
    private static native int nativePointerAxis(
            long handle, int horizontalMilli, int verticalMilli, int time);
    private static native int nativePointerLeave(long handle);
    private static native int nativeShmBindCount(long handle);
    private static native int nativeShmPoolCount(long handle);
    private static native int nativeShmBufferCount(long handle);
    private static native int nativeLastBufferChecksum(long handle);
    private static native int nativeSurfaceCount(long handle);
    private static native int nativeSurfaceCommitCount(long handle);
    private static native int nativeLastFrameWidth(long handle);
    private static native int nativeLastFrameHeight(long handle);
    private static native int nativeLastFrameChecksum(long handle);
    private static native int nativeCopyLastFrameToBitmap(long handle, Bitmap bitmap);
    private static native void nativeDestroyCore(long handle);

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int linuxKey = toLinuxKeyCode(event.getKeyCode());
        if (keyboardInputReady
                && linuxKey != 0
                && event.getRepeatCount() == 0
                && (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP)) {
            keyboardInputs.offer(new KeyboardInput(
                    linuxKey,
                    action == KeyEvent.ACTION_DOWN,
                    (int) event.getEventTime()));
            Log.i(TAG, "KeyEvent key=" + linuxKey + " pressed="
                    + (action == KeyEvent.ACTION_DOWN));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(24, 24, 24, 24);
        ImageView frameView = new ImageView(this);
        frameView.setBackgroundColor(Color.DKGRAY);
        frameView.setScaleType(ImageView.ScaleType.FIT_XY);
        frameView.setContentDescription("No committed Wayland frame");
        frameView.setClickable(true);
        frameView.setOnTouchListener((view, event) -> {
            if (!pointerInputReady && !touchInputReady) {
                return false;
            }
            int action = event.getActionMasked();
            int actionIndex = event.getActionIndex();
            int time = (int) event.getEventTime();
            if (pointerInputReady
                    && (action == MotionEvent.ACTION_DOWN
                            || action == MotionEvent.ACTION_MOVE
                            || action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL)) {
                int localX = Math.round(event.getX());
                int localY = Math.round(event.getY());
                Log.i(TAG, "MotionEvent action=" + action + " local=" + localX + "," + localY);
                pointerInputs.offer(new PointerInput(
                        action, localX, localY, time, 0.0f, 0.0f));
            }
            if (touchInputReady) {
                if (action == MotionEvent.ACTION_MOVE) {
                    for (int index = 0; index < event.getPointerCount(); index++) {
                        touchInputs.offer(new TouchInput(
                                action,
                                event.getPointerId(index),
                                Math.round(event.getX(index)),
                                Math.round(event.getY(index)),
                                time));
                    }
                } else if (action == MotionEvent.ACTION_DOWN
                        || action == MotionEvent.ACTION_POINTER_DOWN
                        || action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_POINTER_UP) {
                    touchInputs.offer(new TouchInput(
                            action,
                            event.getPointerId(actionIndex),
                            Math.round(event.getX(actionIndex)),
                            Math.round(event.getY(actionIndex)),
                            time));
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    touchInputs.offer(new TouchInput(action, -1, 0, 0, time));
                }
            }
            if (gestureInputReady) {
                if (action == MotionEvent.ACTION_POINTER_DOWN
                        && event.getPointerCount() == 2) {
                    float dx = event.getX(1) - event.getX(0);
                    float dy = event.getY(1) - event.getY(0);
                    gestureInitialSpan = Math.max(1.0f, (float) Math.hypot(dx, dy));
                    gestureInitialAngle = (float) Math.atan2(dy, dx);
                    gestureLastCentroidX = (event.getX(0) + event.getX(1)) / 2.0f;
                    gestureLastCentroidY = (event.getY(0) + event.getY(1)) / 2.0f;
                    gestureInputs.offer(GestureInput.begin(time));
                } else if (action == MotionEvent.ACTION_MOVE
                        && event.getPointerCount() >= 2
                        && gestureInitialSpan > 0.0f) {
                    float centroidX = (event.getX(0) + event.getX(1)) / 2.0f;
                    float centroidY = (event.getY(0) + event.getY(1)) / 2.0f;
                    float dx = event.getX(1) - event.getX(0);
                    float dy = event.getY(1) - event.getY(0);
                    float span = (float) Math.hypot(dx, dy);
                    float angle = (float) Math.atan2(dy, dx);
                    gestureInputs.offer(GestureInput.update(
                            centroidX - gestureLastCentroidX,
                            centroidY - gestureLastCentroidY,
                            span / gestureInitialSpan,
                            angle - gestureInitialAngle,
                            time));
                    gestureLastCentroidX = centroidX;
                    gestureLastCentroidY = centroidY;
                } else if ((action == MotionEvent.ACTION_POINTER_UP
                                || action == MotionEvent.ACTION_CANCEL)
                        && gestureInitialSpan > 0.0f) {
                    gestureInputs.offer(GestureInput.end(
                            action == MotionEvent.ACTION_CANCEL, time));
                    gestureInitialSpan = 0.0f;
                }
            }
            if (action == MotionEvent.ACTION_UP) {
                view.performClick();
            }
            return true;
        });
        frameView.setOnGenericMotionListener((view, event) -> {
            if (!pointerInputReady
                    || event.getActionMasked() != MotionEvent.ACTION_SCROLL) {
                return false;
            }
            float horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            Log.i(TAG, "MotionEvent scroll horizontal=" + horizontal
                    + " vertical=" + vertical);
            pointerInputs.offer(new PointerInput(
                    MotionEvent.ACTION_SCROLL,
                    Math.round(event.getX()),
                    Math.round(event.getY()),
                    (int) event.getEventTime(),
                    horizontal,
                    vertical));
            return true;
        });
        content.addView(frameView, new LinearLayout.LayoutParams(320, 160));
        TextView result = new TextView(this);
        result.setGravity(Gravity.CENTER);
        result.setTextSize(20);
        content.addView(result);
        setContentView(content);
        new Thread(() -> runProbe(result, frameView), "native-compositor-probe").start();
    }

    private void runProbe(TextView result, ImageView frameView) {
        String message;
        boolean passed = false;
        Bitmap renderedFrame = null;
        long core = 0;
        try {
            if (nativeProtocolVersion() != 1) throw new IllegalStateException("protocol version");
            core = nativeCreateCore();
            if (core == 0) throw new IllegalStateException("display creation");
            ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
            try (ParcelFileDescriptor client = pair[0];
                    FileInputStream input = new FileInputStream(client.getFileDescriptor());
                    FileOutputStream output = new FileOutputStream(client.getFileDescriptor())) {
                int serverFd = pair[1].detachFd();
                pair[1].close();
                if (nativeAdoptClient(core, serverFd) != 0) {
                    throw new IllegalStateException("client adoption");
                }

                output.write(getRegistryAndSyncRequest());
                output.flush();
                dispatch(core);

                RegistryGlobals globals = readGlobals(input, 3);
                output.write(bindGlobalAndSyncRequest(
                        globals.compositor, "wl_compositor", 4, 5));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 5);
                if (nativeCompositorBindCount(core) != 1) {
                    throw new IllegalStateException("wl_compositor bind was not dispatched");
                }

                output.write(bindGlobalAndSyncRequest(globals.shm, "wl_shm", 6, 7));
                output.flush();
                dispatch(core);
                readShmFormatsUntilCallback(input, 6, 7);
                if (nativeShmBindCount(core) != 1) {
                    throw new IllegalStateException("wl_shm bind was not dispatched");
                }

                output.write(createSurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 9);
                if (nativeSurfaceCount(core) != 1) {
                    throw new IllegalStateException("wl_surface creation was not dispatched");
                }


                if (nativeSendShmPoolRequest(client.getFd(), 10, 40, 11) != 0) {
                    throw new IllegalStateException("SHM pool FD transfer");
                }
                dispatch(core);
                readUntilCallback(input, 11);
                if (nativeShmPoolCount(core) != 1) {
                    throw new IllegalStateException("wl_shm_pool creation was not dispatched");
                }

                output.write(createShmBufferAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 13);
                if (nativeShmBufferCount(core) != 1 || nativeLastBufferChecksum(core) != 820) {
                    throw new IllegalStateException("wl_buffer creation did not expose SHM pixels");
                }

                output.write(commitShmFrameAndSyncRequest());
                output.flush();
                dispatch(core);
                readFrameCommitUntilSync(input, 12, 14, 15);
                readDeleteId(input, 15);
                if (nativePendingFrameCallbackCount(core) != 1
                        || nativePendingDamageCount(core) != 1
                        || nativeSurfaceCommitCount(core) != 1
                        || nativeLastFrameWidth(core) != 4
                        || nativeLastFrameHeight(core) != 2
                        || nativeLastFrameChecksum(core) != 656) {
                    throw new IllegalStateException("wl_surface commit did not snapshot the SHM frame");
                }
                renderedFrame = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
                if (nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(0, 0) != 0xff030201
                        || renderedFrame.getPixel(0, 1) != 0xff1b1a19) {
                    throw new IllegalStateException("Android bitmap did not match the XRGB SHM frame");
                }
                presentNextFrame(core, input, 14, renderedFrame, frameView);


                output.write(destroyShmResourcesAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 16);
                if (nativeShmBufferCount(core) != 0 || nativeShmPoolCount(core) != 0) {
                    throw new IllegalStateException("SHM resources were not destroyed");
                }

                output.write(destroySurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 17);
                if (nativeSurfaceCount(core) != 0) {
                    throw new IllegalStateException("wl_surface destruction was not dispatched");
                }

                output.write(bindGlobalAndSyncRequest(
                        globals.xdgWmBase, "xdg_wm_base", 18, 19));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 19);
                if (nativeXdgWmBaseBindCount(core) != 1) {
                    throw new IllegalStateException("xdg_wm_base bind was not dispatched");
                }

                output.write(createSecondSurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 21);

                output.write(createXdgToplevelAndSyncRequest());
                output.flush();
                dispatch(core);
                int configureSerial = readXdgConfigureUntilCallback(input, 22, 23, 24);
                if (nativeXdgSurfaceCount(core) != 1 || nativeXdgToplevelCount(core) != 1) {
                    throw new IllegalStateException("xdg toplevel role was not constructed");
                }

                output.write(ackXdgConfigureAndSyncRequest(configureSerial, 25));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 25);
                if (nativeXdgAckCount(core) != 1) {
                    throw new IllegalStateException("xdg configure serial was not acknowledged");
                }

                if (nativeSendShmPoolRequest(client.getFd(), 26, 40, 27) != 0) {
                    throw new IllegalStateException("second SHM pool FD transfer");
                }
                dispatch(core);
                readUntilCallback(input, 27);
                output.write(createSecondShmBufferAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 29);

                output.write(commitXdgFrameAndSyncRequest());
                output.flush();
                dispatch(core);
                readFrameCommitUntilSync(input, 28, 30, 31);
                readDeleteId(input, 31);
                if (nativePendingFrameCallbackCount(core) != 1
                        || nativePendingDamageCount(core) != 1
                        || nativeSurfaceCommitCount(core) != 3
                        || nativeLastFrameWidth(core) != 4
                        || nativeLastFrameHeight(core) != 2
                        || nativeLastFrameChecksum(core) != 656) {
                    throw new IllegalStateException("configured xdg frame was not committed");
                }
                if (nativeCopyLastFrameToBitmap(core, renderedFrame) != 0) {
                    throw new IllegalStateException("configured xdg frame bitmap copy failed");
                }
                presentNextFrame(core, input, 30, renderedFrame, frameView);

                output.write(transformAndScaleXdgFrameAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 31);
                readDeleteId(input, 31);
                Bitmap transformedFrame =
                        Bitmap.createBitmap(1, 2, Bitmap.Config.ARGB_8888);
                int transformedCopyResult =
                        nativeCopyLastFrameToBitmap(core, transformedFrame);
                Log.i(TAG, "transformed frame commits=" + nativeSurfaceCommitCount(core)
                        + " dimensions=" + nativeLastFrameWidth(core) + "x"
                        + nativeLastFrameHeight(core)
                        + " checksum=" + nativeLastFrameChecksum(core)
                        + " copy=" + transformedCopyResult
                        + " pixels=" + Integer.toHexString(transformedFrame.getPixel(0, 0))
                        + "," + Integer.toHexString(transformedFrame.getPixel(0, 1)));
                if (nativeSurfaceCommitCount(core) != 4
                        || nativeLastFrameWidth(core) != 1
                        || nativeLastFrameHeight(core) != 2
                        || nativeLastFrameChecksum(core) != 84
                        || transformedCopyResult != 0
                        || transformedFrame.getPixel(0, 0) != 0xff0f0e0d
                        || transformedFrame.getPixel(0, 1) != 0xff070605) {
                    throw new IllegalStateException(
                            "buffer transform and scale were not applied in surface coordinates");
                }
                presentNextFrame(core, input, 0, transformedFrame, frameView);

                output.write(resetXdgBufferStateAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 31);
                readDeleteId(input, 31);
                if (nativeSurfaceCommitCount(core) != 5
                        || nativeLastFrameWidth(core) != 4
                        || nativeLastFrameHeight(core) != 2
                        || nativeLastFrameChecksum(core) != 656
                        || nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(0, 0) != 0xff030201
                        || renderedFrame.getPixel(3, 1) != 0xff272625) {
                    throw new IllegalStateException(
                            "buffer state reset did not reinterpret the attached source");
                }
                presentNextFrame(core, input, 0, renderedFrame, frameView);

                output.write(bindGlobalAndSyncRequest(globals.seat, "wl_seat", 32, 33));
                output.flush();
                dispatch(core);
                readSeatUntilCallback(input, 32, 33);
                if (nativeSeatBindCount(core) != 1) {
                    throw new IllegalStateException("wl_seat bind was not dispatched");
                }

                output.write(getPointerAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 35);
                readDeleteId(input, 35);
                if (nativePointerCount(core) != 1) {
                    throw new IllegalStateException("wl_pointer was not constructed");
                }

                output.write(getKeyboardAndSyncRequest());
                output.flush();
                dispatch(core);
                int keymapSize = nativeReceiveKeyboardKeymap(client.getFd(), 36);
                if (keymapSize <= 1) {
                    throw new IllegalStateException("wl_keyboard keymap FD validation");
                }
                readKeyboardMetadataUntilCallback(input, 36, 20, 37);
                if (nativeKeyboardCount(core) != 1
                        || nativeKeyboardEventCount(core) != 4) {
                    throw new IllegalStateException("wl_keyboard metadata lifecycle was incomplete");
                }

                keyboardInputReady = true;
                Log.i(TAG, "keyboard target ready");
                KeyboardInput keyDown = awaitKeyboardInput(30);
                if (!keyDown.pressed
                        || keyDown.key != 30
                        || nativeKeyboardKey(
                                core, keyDown.key, true, keyDown.time) != 1) {
                    throw new IllegalStateException("Android key press was not routed");
                }
                dispatch(core);
                int keyPressSerial = readKeyboardKey(
                        input, 36, 30, true, keyDown.time);
                KeyboardInput keyUp = awaitKeyboardInput(5);
                if (keyUp.pressed
                        || keyUp.key != 30
                        || nativeKeyboardKey(
                                core, keyUp.key, false, keyUp.time) != 1) {
                    throw new IllegalStateException("Android key release was not routed");
                }
                dispatch(core);
                int keyReleaseSerial = readKeyboardKey(
                        input, 36, 30, false, keyUp.time);
                keyboardInputReady = false;
                if (keyReleaseSerial <= keyPressSerial
                        || nativeKeyboardEventCount(core) != 6) {
                    throw new IllegalStateException("wl_keyboard key lifecycle was incomplete");
                }

                int firstResizeSerial = nativeConfigureFocusedToplevel(core, 8, 4);
                int secondResizeSerial = nativeConfigureFocusedToplevel(core, 12, 6);
                if (firstResizeSerial <= 0 || secondResizeSerial <= firstResizeSerial) {
                    throw new IllegalStateException("xdg resize serial generation failed");
                }
                output.write(syncRequest(38));
                output.flush();
                dispatch(core);
                readQueuedConfiguresUntilCallback(
                        input,
                        22,
                        23,
                        38,
                        firstResizeSerial,
                        8,
                        4,
                        secondResizeSerial,
                        12,
                        6);
                if (nativePendingConfigureCount(core) != 2) {
                    throw new IllegalStateException("xdg configure queue did not retain both entries");
                }

                output.write(ackXdgConfigureAndSyncRequest(firstResizeSerial, 39));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 39);
                if (nativePendingConfigureCount(core) != 1
                        || nativeXdgAckCount(core) != 2) {
                    throw new IllegalStateException("first queued configure ack was not retained");
                }

                output.write(ackXdgConfigureAndSyncRequest(secondResizeSerial, 40));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 40);
                if (nativePendingConfigureCount(core) != 0
                        || nativeXdgAckCount(core) != 3) {
                    throw new IllegalStateException("xdg configure queue did not drain");
                }

                output.write(unmapXdgToplevelAndSyncRequest());
                output.flush();
                dispatch(core);
                readKeyboardUnmapUntilCallback(input, 36, 20, 41);
                if (nativeLastFrameWidth(core) != 0
                        || nativeLastFrameHeight(core) != 0
                        || nativeKeyboardEventCount(core) != 7) {
                    throw new IllegalStateException("xdg toplevel unmap lifecycle was incomplete");
                }

                output.write(remapXdgToplevelAndSyncRequest());
                output.flush();
                dispatch(core);
                readKeyboardRemapUntilCallback(input, 36, 20, 28, 42);
                if (nativeLastFrameWidth(core) != 4
                        || nativeLastFrameHeight(core) != 2
                        || nativeLastFrameChecksum(core) != 656
                        || nativeKeyboardEventCount(core) != 9) {
                    throw new IllegalStateException("xdg toplevel remap lifecycle was incomplete");
                }

                Bitmap pointerFrame = renderedFrame;
                runOnUiThread(() -> {
                    frameView.setImageBitmap(pointerFrame);
                    frameView.setContentDescription("Committed Wayland XRGB frame");
                    frameView.post(() -> {
                        int[] location = new int[2];
                        frameView.getLocationOnScreen(location);
                        int targetX = location[0] + frameView.getWidth() / 2;
                        int targetY = location[1] + frameView.getHeight() / 2;
                        pointerInputReady = true;
                        Log.i(TAG, "pointer target screen=" + targetX + "," + targetY);
                    });
                });

                PointerInput down = awaitPointerInput(30);
                if (down.action != MotionEvent.ACTION_DOWN
                        || nativePointerMotion(core, down.x, down.y, down.time) != 1) {
                    throw new IllegalStateException("Android pointer down was not focused");
                }
                dispatch(core);
                int enterSerial = readPointerEnterAndFrame(
                        input, 34, 20, down.x, down.y);
                if (nativePointerButton(core, true, down.time) != 1) {
                    throw new IllegalStateException("Android pointer press was not emitted");
                }
                dispatch(core);
                int pressSerial = readPointerButtonAndFrame(
                        input, 34, true, down.time);
                if (pressSerial <= enterSerial) {
                    throw new IllegalStateException("pointer press serial did not advance");
                }

                int expectedPointerEvents = 2;
                int releaseSerial = 0;
                while (releaseSerial == 0) {
                    PointerInput event = awaitPointerInput(5);
                    if (event.action == MotionEvent.ACTION_MOVE) {
                        if (nativePointerMotion(core, event.x, event.y, event.time) != 1) {
                            throw new IllegalStateException("Android pointer motion was not emitted");
                        }
                        dispatch(core);
                        readPointerMotionAndFrame(input, 34, event.x, event.y, event.time);
                        expectedPointerEvents++;
                    } else if (event.action == MotionEvent.ACTION_UP
                            || event.action == MotionEvent.ACTION_CANCEL) {
                        if (nativePointerButton(core, false, event.time) != 1) {
                            throw new IllegalStateException("Android pointer release was not emitted");
                        }
                        dispatch(core);
                        releaseSerial = readPointerButtonAndFrame(
                                input, 34, false, event.time);
                        if (releaseSerial <= pressSerial) {
                            throw new IllegalStateException("pointer release lifecycle was invalid");
                        }
                        expectedPointerEvents++;
                    }
                }

                runOnUiThread(() -> frameView.post(() -> {
                    int[] location = new int[2];
                    frameView.getLocationOnScreen(location);
                    int targetX = location[0] + frameView.getWidth() / 2;
                    int targetY = location[1] + frameView.getHeight() / 2;
                    Log.i(TAG, "scroll target ready screen=" + targetX + "," + targetY);
                }));
                PointerInput scroll = awaitPointerInput(30);
                if (scroll.action != MotionEvent.ACTION_SCROLL
                        || (scroll.horizontalScroll == 0.0f && scroll.verticalScroll == 0.0f)
                        || nativePointerAxis(
                                core,
                                Math.round(scroll.horizontalScroll * 1000.0f),
                                Math.round(scroll.verticalScroll * 1000.0f),
                                scroll.time) != 1) {
                    throw new IllegalStateException("Android pointer scroll was not emitted");
                }
                dispatch(core);
                readPointerAxisAndFrame(
                        input,
                        34,
                        scroll.horizontalScroll,
                        scroll.verticalScroll,
                        scroll.time);
                if (nativePointerLeave(core) != 1) {
                    throw new IllegalStateException("pointer scroll focus was not released");
                }
                dispatch(core);
                int leaveSerial = readPointerLeaveAndFrame(input, 34, 20);
                if (leaveSerial <= releaseSerial) {
                    throw new IllegalStateException("pointer leave serial did not advance");
                }
                expectedPointerEvents += 2;
                if (nativePointerEventCount(core) != expectedPointerEvents) {
                    throw new IllegalStateException("Android pointer events were not counted");
                }

                output.write(releaseInputAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 43);
                if (nativePointerCount(core) != 1 || nativeKeyboardCount(core) != 0) {
                    throw new IllegalStateException("keyboard release changed pointer lifetime");
                }

                output.write(syncRequest(44));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 44);

                if (nativeConfigureOutput(core, 4, 2, 1) != 0) {
                    throw new IllegalStateException("unbound Android output update was not retained");
                }
                output.write(createPositionerAndSyncRequest(configureSerial));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 46);
                if (nativeXdgPositionerCount(core) != 1
                        || nativeXdgPositionerRequestCount(core) != 9) {
                    throw new IllegalStateException("xdg_positioner state was incomplete");
                }
                output.write(createPopupSurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 48);

                output.write(createPopupRoleAndSyncRequest(pressSerial));
                output.flush();
                dispatch(core);
                int popupConfigureSerial =
                        readPopupConfigureUntilCallback(input, 49, 50, 51, 2, 0, 2, 2);
                if (nativeXdgPopupCount(core) != 1
                        || nativeXdgSurfaceCount(core) != 2
                        || nativeSurfaceCount(core) != 2
                        || nativeXdgPopupGrabDepth(core) != 1) {
                    throw new IllegalStateException("xdg_popup role lifecycle was incomplete");
                }

                output.write(ackPopupConfigureAndSyncRequest(popupConfigureSerial, 52));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 52);
                if (nativeXdgAckCount(core) != 4) {
                    throw new IllegalStateException("xdg_popup configure ack failed");
                }

                output.write(mapPopupFrameAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 53);
                if (nativeLastFrameWidth(core) != 4
                        || nativeLastFrameHeight(core) != 2
                        || nativeLastFrameChecksum(core) != 560
                        || nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(2, 0) != 0xff030201
                        || renderedFrame.getPixel(3, 1) != 0xff1f1e1d) {
                    throw new IllegalStateException("parent popup pixels were not composed");
                }
                if (nativePointerMotion(core, 3, 1, 9001) != 1) {
                    throw new IllegalStateException("parent popup pointer routing failed");
                }
                dispatch(core);
                readPointerEnterAndFrame(input, 34, 47, 1, 1);
                expectedPointerEvents++;

                int popupFocusedResizeSerial = nativeConfigureFocusedToplevel(core, 4, 2);
                if (popupFocusedResizeSerial <= 0) {
                    throw new IllegalStateException("popup focus did not resolve to its root toplevel");
                }
                output.write(syncRequest(54));
                output.flush();
                dispatch(core);
                readToplevelConfigureUntilCallback(
                        input, 22, 23, 54, popupFocusedResizeSerial, 4, 2);
                readDeleteId(input, 54);
                output.write(commitParentWindowGeometryAndSyncRequest(
                        popupFocusedResizeSerial, 54));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 54);
                readDeleteId(input, 54);
                if (nativePendingConfigureCount(core) != 0
                        || nativeXdgAckCount(core) != 5) {
                    throw new IllegalStateException("committed parent window geometry was not applied");
                }

                if (nativeConfigureOutput(core, 5, 2, 1) != 0) {
                    throw new IllegalStateException("reactive unbound output update was not retained");
                }
                output.write(syncRequest(54));
                output.flush();
                dispatch(core);
                int reactivePopupConfigureSerial =
                        readPopupConfigureUntilCallback(input, 49, 50, 54, 3, 0, 2, 2);
                if (nativeLastFrameChecksum(core) != 560
                        || nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(2, 0) != 0xff030201) {
                    throw new IllegalStateException("unacknowledged popup geometry became visible");
                }
                output.write(ackPopupConfigureAndSyncRequest(reactivePopupConfigureSerial, 55));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 55);
                if (nativeXdgAckCount(core) != 6
                        || nativeLastFrameChecksum(core) != 584
                        || nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(3, 0) != 0xff030201) {
                    throw new IllegalStateException("reactive popup constraint update failed");
                }
                if (nativePointerMotion(core, 4, 1, 9002) != 1) {
                    throw new IllegalStateException("reactive popup pointer routing failed");
                }
                dispatch(core);
                readPointerMotionAndFrame(input, 34, 1, 1, 9002);
                expectedPointerEvents++;

                output.write(createNestedPositionerAndSyncRequest(reactivePopupConfigureSerial));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 57);
                output.write(createNestedPopupSurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 59);
                output.write(createNestedPopupRoleAndSyncRequest(pressSerial));
                output.flush();
                dispatch(core);
                int nestedConfigureSerial =
                        readPopupConfigureUntilCallback(input, 60, 61, 62, 0, 0, 1, 1);
                if (nativeXdgPopupCount(core) != 2
                        || nativeXdgPositionerCount(core) != 2
                        || nativeXdgPositionerRequestCount(core) != 14
                        || nativeXdgSurfaceCount(core) != 3
                        || nativeSurfaceCount(core) != 3
                        || nativeXdgPopupGrabDepth(core) != 2) {
                    throw new IllegalStateException("nested xdg_popup grab stack was incomplete");
                }

                output.write(ackNestedPopupConfigureAndSyncRequest(nestedConfigureSerial));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 63);
                if (nativeXdgAckCount(core) != 7) {
                    throw new IllegalStateException("nested xdg_popup configure ack failed");
                }
                output.write(mapNestedPopupFrameAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 64);
                if (nativeLastFrameChecksum(core) != 584
                        || nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(3, 0) != 0xff030201) {
                    throw new IllegalStateException("nested popup pixels were not composed");
                }
                output.write(setParentInputRegionAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 66);
                if (nativePointerMotion(core, 3, 1, 9003) != 1) {
                    throw new IllegalStateException("popup input-region exclusion was not routed");
                }
                dispatch(core);
                readPointerLeaveAndFrame(input, 34, 47);
                readPointerEnterAndFrame(input, 34, 20, 3, 1);
                expectedPointerEvents++;

                if (nativePointerMotion(core, 3, 0, 9004) != 1) {
                    throw new IllegalStateException("nested popup pointer routing failed");
                }
                dispatch(core);
                readPointerLeaveAndFrame(input, 34, 20);
                readPointerEnterAndFrame(input, 34, 58, 0, 0);
                expectedPointerEvents++;
                if (nativePointerButton(core, true, 9005) != 1) {
                    throw new IllegalStateException("nested popup pointer press failed");
                }
                dispatch(core);
                readPointerButtonAndFrame(input, 34, true, 9005);
                if (nativePointerButton(core, false, 9006) != 1) {
                    throw new IllegalStateException("nested popup pointer release failed");
                }
                dispatch(core);
                readPointerButtonAndFrame(input, 34, false, 9006);
                expectedPointerEvents += 2;

                if (nativeDismissPopups(core) != 2) {
                    throw new IllegalStateException("nested xdg_popup dismissal was not routed");
                }
                output.write(syncRequest(67));
                output.flush();
                dispatch(core);
                readNestedPopupDoneUntilCallback(input, 61, 50, 34, 58, 20, 67);
                if (nativeLastFrameChecksum(core) != 656
                        || nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(2, 0) != 0xff0b0a09) {
                    throw new IllegalStateException("dismissed popup pixels remained composed");
                }
                if (nativeDismissPopups(core) != 0
                        || nativeXdgPopupDoneCount(core) != 2
                        || nativeXdgPopupGrabDepth(core) != 2) {
                    throw new IllegalStateException("nested xdg_popup dismissal was not idempotent");
                }

                output.write(bindGlobalAndSyncRequest(
                        globals.subcompositor, "wl_subcompositor", 68, 69));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 69);
                if (nativeSubcompositorBindCount(core) != 1) {
                    throw new IllegalStateException("wl_subcompositor bind was not dispatched");
                }
                output.write(createSynchronizedSubsurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 72);
                if (nativeSubsurfaceCount(core) != 1
                        || nativeSurfaceCount(core) != 4
                        || nativeLastFrameChecksum(core) != 656) {
                    throw new IllegalStateException("synchronized child commit leaked before parent");
                }
                output.write(commitSubsurfaceParentAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 73);
                if (nativeLastFrameChecksum(core) != 584
                        || nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(1, 0) != 0xff030201
                        || renderedFrame.getPixel(3, 1) != 0xff232221) {
                    throw new IllegalStateException(
                            "subsurface tree pixels were not composed checksum="
                                    + nativeLastFrameChecksum(core)
                                    + " p10=" + Integer.toHexString(renderedFrame.getPixel(1, 0))
                                    + " p31=" + Integer.toHexString(renderedFrame.getPixel(3, 1)));
                }
                output.write(desyncAndUnmapSubsurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 74);
                if (nativeLastFrameChecksum(core) != 656) {
                    throw new IllegalStateException("desynchronized child unmap waited for parent");
                }
                output.write(remapDesynchronizedSubsurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 75);
                if (nativeLastFrameChecksum(core) != 584) {
                    throw new IllegalStateException("desynchronized child remap waited for parent");
                }
                if (nativePointerMotion(core, 2, 1, 9007) != 1) {
                    throw new IllegalStateException("subsurface pointer routing failed");
                }
                dispatch(core);
                readPointerLeaveAndFrame(input, 34, 20);
                readPointerEnterAndFrame(input, 34, 70, 1, 1);
                expectedPointerEvents++;
                output.write(destroySubsurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 76);
                if (nativeSubsurfaceCount(core) != 0
                        || nativeSurfaceCount(core) != 3
                        || nativeLastFrameChecksum(core) != 656) {
                    throw new IllegalStateException("wl_subsurface teardown failed");
                }

                output.write(destroyPopupResourcesAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 77);
                if (nativeXdgPopupCount(core) != 0
                        || nativeXdgPositionerCount(core) != 0
                        || nativeXdgSurfaceCount(core) != 1
                        || nativeSurfaceCount(core) != 1
                        || nativeShmBufferCount(core) != 0
                        || nativeShmPoolCount(core) != 0
                        || nativePointerCount(core) != 0
                        || nativeXdgPopupGrabDepth(core) != 0
                        || nativePointerEventCount(core) != expectedPointerEvents) {
                    throw new IllegalStateException("nested xdg_popup destruction failed");
                }
                if (nativeConfigureOutput(core, 320, 160, 1) != 0) {
                    throw new IllegalStateException("unbound Android output reset was not retained");
                }
                output.write(bindGlobalAndSyncRequest(
                        globals.output, "wl_output", 78, 79));
                output.flush();
                dispatch(core);
                readOutputUntilCallback(input, 20, 78, 79, 320, 160, 1, true);
                if (nativeOutputBindCount(core) != 1
                        || nativeOutputCount(core) != 1
                        || nativeOutputEventCount(core) != 7) {
                    throw new IllegalStateException("wl_output initial state was incomplete");
                }

                if (nativeConfigureOutput(core, 640, 360, 2) != 1) {
                    throw new IllegalStateException("Android output update was not routed");
                }
                output.write(syncRequest(80));
                output.flush();
                dispatch(core);
                readOutputUntilCallback(input, 20, 78, 80, 640, 360, 2, false);
                if (nativeOutputEventCount(core) != 10) {
                    throw new IllegalStateException("wl_output update events were incomplete");
                }

                output.write(releaseOutputAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 81);
                if (nativeOutputCount(core) != 0) {
                    throw new IllegalStateException("wl_output release failed");
                }


                output.write(syncRequest(82));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 82);
                output.write(bindGlobalAndSyncRequest(globals.seat, "wl_seat", 83, 84));
                output.flush();
                dispatch(core);
                readSeatUntilCallback(input, 83, 84);

                output.write(bindGlobalAndSyncRequest(
                        globals.dataDeviceManager, "wl_data_device_manager", 85, 86));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 86);
                readDeleteId(input, 86);
                if (nativeDataDeviceManagerBindCount(core) != 1) {
                    throw new IllegalStateException(
                            "wl_data_device_manager bind was not dispatched");
                }

                if (nativeSetClipboardActive(core, true) != 1) {
                    throw new IllegalStateException("Android clipboard broker did not activate");
                }
                ClipboardManager clipboard = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);

                output.write(createDataSelectionAndSyncRequest(keyPressSerial));
                output.flush();
                dispatch(core);
                String linuxClipboard = "ARCHPHENE_WAYLAND_TO_ANDROID";
                if (nativeReceiveDataSourceSend(client.getFd(), 87)
                        != linuxClipboard.getBytes(StandardCharsets.UTF_8).length) {
                    throw new IllegalStateException(
                            "Wayland clipboard source FD transfer failed");
                }
                int linuxCopyFd = nativeTakeLinuxCopyFd(core);
                if (linuxCopyFd < 0 || nativeTakeLinuxCopyFd(core) != -1
                        || nativeTakeAndroidPasteFd(core) != -1) {
                    throw new IllegalStateException(
                            "Linux clipboard copy request queue was invalid");
                }
                try (ParcelFileDescriptor source = ParcelFileDescriptor.adoptFd(linuxCopyFd);
                        FileInputStream sourceStream =
                                new FileInputStream(source.getFileDescriptor())) {
                    byte[] linuxClipboardBytes = readExact(
                            sourceStream,
                            linuxClipboard.getBytes(StandardCharsets.UTF_8).length);
                    if (!linuxClipboard.equals(
                            new String(linuxClipboardBytes, StandardCharsets.UTF_8))) {
                        throw new IllegalStateException(
                                "Wayland clipboard copy payload mismatch");
                    }
                }
                clipboard.setPrimaryClip(
                        ClipData.newPlainText("Archphene Linux app", linuxClipboard));

                int dataOfferId = readDataSelectionUntilCallback(input, 88, 89);
                if (nativeDataSourceCount(core) != 1
                        || nativeDataDeviceCount(core) != 1
                        || nativeDataOfferCount(core) != 1) {
                    throw new IllegalStateException(
                            "wl_data_device selection resources were incomplete");
                }

                output.write(clearDataSelectionAndSyncRequest(keyReleaseSerial));
                output.flush();
                dispatch(core);
                readClearedDataSelectionUntilCallback(input, 87, 88, 90);

                String expectedClipboard = "ARCHPHENE_ANDROID_TO_WAYLAND";
                clipboard.setPrimaryClip(
                        ClipData.newPlainText("Archphene probe", expectedClipboard));
                if (nativeOfferAndroidClipboardText(core) != 1
                        || nativeTakeAndroidPasteFd(core) != -1
                        || nativeTakeLinuxCopyFd(core) != -1) {
                    throw new IllegalStateException(
                            "Android clipboard was accessed before a Wayland receive request");
                }
                output.write(syncRequest(91));
                output.flush();
                dispatch(core);
                int androidOfferId = readDataSelectionUntilCallback(input, 88, 91);

                int clientReadFd = nativeSendDataOfferReceive(client.getFd(), androidOfferId);
                if (clientReadFd < 0) {
                    throw new IllegalStateException("Wayland clipboard receive FD transfer");
                }
                dispatch(core);
                int androidWriteFd = nativeTakeAndroidPasteFd(core);
                if (androidWriteFd < 0 || nativeTakeAndroidPasteFd(core) != -1) {
                    throw new IllegalStateException(
                            "Android clipboard paste request queue was invalid");
                }
                CharSequence clipboardValue = clipboard.getPrimaryClip()
                        .getItemAt(0).coerceToText(this);
                byte[] clipboardBytes = clipboardValue.toString()
                        .getBytes(StandardCharsets.UTF_8);
                try (ParcelFileDescriptor destination =
                                ParcelFileDescriptor.adoptFd(androidWriteFd);
                        FileOutputStream destinationStream =
                                new FileOutputStream(destination.getFileDescriptor())) {
                    destinationStream.write(clipboardBytes);
                }
                try (ParcelFileDescriptor source = ParcelFileDescriptor.adoptFd(clientReadFd);
                        FileInputStream sourceStream =
                                new FileInputStream(source.getFileDescriptor())) {
                    byte[] receivedClipboard = readExact(sourceStream, clipboardBytes.length);
                    if (!java.util.Arrays.equals(receivedClipboard, clipboardBytes)) {
                        throw new IllegalStateException(
                                "Android clipboard paste payload mismatch");
                    }
                }

                if (nativeSetClipboardActive(core, false) != 0) {
                    throw new IllegalStateException("Android clipboard broker did not deactivate");
                }
                output.write(syncRequest(92));
                output.flush();
                dispatch(core);
                readNullSelectionUntilCallback(input, 88, 92);
                output.write(syncRequest(93));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 93);

                output.write(bindGlobalAndSyncRequest(
                        globals.textInputManager, "zwp_text_input_manager_v3", 94, 95));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 95);
                if (nativeTextInputManagerBindCount(core) != 1) {
                    throw new IllegalStateException(
                            "zwp_text_input_manager_v3 bind was not dispatched");
                }

                output.write(createTextInputAndSyncRequest());
                output.flush();
                dispatch(core);
                readTextInputEnterUntilCallback(input, 96, 20, 97);
                if (nativeTextInputCount(core) != 1
                        || nativeImeActive(core) != 0
                        || nativeImeShowRequestCount(core) != 0) {
                    throw new IllegalStateException(
                            "text-input focus incorrectly activated Android IME");
                }

                output.write(enableTextInputAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 98);
                if (nativeImeActive(core) != 1
                        || nativeImeShowRequestCount(core) != 1
                        || nativeImeSurroundingTextLength(core) != 5
                        || nativeImeSurroundingCursor(core) != 5
                        || nativeImeSurroundingAnchor(core) != 5
                        || nativeImeContentHint(core) != 3
                        || nativeImeContentPurpose(core) != 6
                        || nativeImeCursorRectangleComponent(core, 0) != 4
                        || nativeImeCursorRectangleComponent(core, 1) != 6
                        || nativeImeCursorRectangleComponent(core, 2) != 2
                        || nativeImeCursorRectangleComponent(core, 3) != 12) {
                    throw new IllegalStateException(
                            "committed text-input state was not exposed to Android");
                }

                if (nativeImePreeditProbeText(core) != 1
                        || nativeImeCommitProbeText(core) != 1
                        || nativeImeDeleteSurrounding(core, 1, 0) != 1) {
                    throw new IllegalStateException("Android IME events were not routed");
                }
                output.write(syncRequest(99));
                output.flush();
                dispatch(core);
                readImeEventsUntilCallback(input, 96, 99, 1);

                output.write(disableTextInputAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 100);
                if (nativeImeActive(core) != 0
                        || nativeImeHideRequestCount(core) != 1) {
                    throw new IllegalStateException(
                            "committed text-input disable did not hide Android IME");
                }

                output.write(destroyTextInputAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 101);
                if (nativeTextInputCount(core) != 0) {
                    throw new IllegalStateException("text-input resource leaked");
                }

                output.write(destroyDataSelectionAndSyncRequest(
                        dataOfferId, androidOfferId));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 102);
                if (nativeDataSourceCount(core) != 0
                        || nativeDataDeviceCount(core) != 0
                        || nativeDataOfferCount(core) != 0
                        || nativeTakeAndroidPasteFd(core) != -1
                        || nativeTakeLinuxCopyFd(core) != -1) {
                    throw new IllegalStateException(
                            "wl_data_device selection resources leaked");
                }
                output.write(destroyXdgToplevelAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 103);
                if (nativeXdgSurfaceCount(core) != 0
                        || nativeXdgToplevelCount(core) != 0
                        || nativeSurfaceCount(core) != 0
                        || nativeShmBufferCount(core) != 0
                        || nativeShmPoolCount(core) != 0) {
                    throw new IllegalStateException("xdg destruction lifecycle failed");
                }
            }
            renderedFrame = runViewportProbe(frameView);
            passed = true;
            message = "Native Wayland compositor passed\n"
                    + "registry, Android bitmap, xdg toplevel, keyboard input, "
                    + "damage-batched buffer scale/transform, viewporter/fractional scaling, Choreographer-paced frames, MotionEvent pointer/wheel/touch input, cursor surfaces, pointer gestures, nested popup grabs, synchronized subsurface trees, "
                    + "committed parent geometry, and bidirectional clipboard and text-input v3 lifecycle complete";
        } catch (Exception error) {
            message = "Native compositor probe failed\n" + error.getMessage();
        } finally {
            pointerInputReady = false;
            keyboardInputReady = false;
            touchInputReady = false;
            gestureInputReady = false;
            pointerInputs.clear();
            keyboardInputs.clear();
            touchInputs.clear();
            gestureInputs.clear();
            if (core != 0) nativeDestroyCore(core);
        }
        boolean finalPassed = passed;
        String finalMessage = message;
        Bitmap finalRenderedFrame = renderedFrame;
        Log.i(TAG, finalMessage.replace('\n', ' '));
        runOnUiThread(() -> {
            if (finalRenderedFrame != null) {
                frameView.setImageBitmap(finalRenderedFrame);
                frameView.setContentDescription("Committed Wayland XRGB frame");
            }
            result.setText(finalMessage);
            result.setContentDescription(finalPassed
                    ? "Native compositor probe passed"
                    : "Native compositor probe failed");
        });
    }

    private static void injectSyntheticGesture(ImageView view) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int index = 0; index < 2; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].pressure = 1.0f;
            coordinates[index].size = 1.0f;
        }
        coordinates[0].x = 140.0f;
        coordinates[0].y = 80.0f;
        coordinates[1].x = 180.0f;
        coordinates[1].y = 80.0f;
        dispatchSyntheticTouch(view, downTime, downTime, MotionEvent.ACTION_DOWN, 1,
                properties, coordinates);
        dispatchSyntheticTouch(
                view,
                downTime,
                downTime + 8,
                MotionEvent.ACTION_POINTER_DOWN
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
                properties,
                coordinates);
        coordinates[0].x = 135.0f;
        coordinates[1].x = 190.0f;
        coordinates[0].y = 78.0f;
        coordinates[1].y = 82.0f;
        dispatchSyntheticTouch(view, downTime, downTime + 16, MotionEvent.ACTION_MOVE, 2,
                properties, coordinates);
        dispatchSyntheticTouch(
                view,
                downTime,
                downTime + 24,
                MotionEvent.ACTION_POINTER_UP
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
                properties,
                coordinates);
        dispatchSyntheticTouch(view, downTime, downTime + 32, MotionEvent.ACTION_UP, 1,
                properties, coordinates);
    }

    private static void dispatchSyntheticTouch(
            ImageView view,
            long downTime,
            long eventTime,
            int action,
            int pointerCount,
            MotionEvent.PointerProperties[] properties,
            MotionEvent.PointerCoords[] coordinates) {
        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                pointerCount,
                properties,
                coordinates,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
    private Bitmap runViewportProbe(ImageView frameView) throws Exception {
        long core = nativeCreateCore();
        if (core == 0) {
            throw new IllegalStateException("viewport display creation");
        }
        try {
            ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        try (ParcelFileDescriptor client = pair[0];
                FileInputStream input = new FileInputStream(client.getFileDescriptor());
                FileOutputStream output = new FileOutputStream(client.getFileDescriptor())) {
            int serverFd = pair[1].detachFd();
            pair[1].close();
            if (nativeAdoptClient(core, serverFd) != 0) {
                throw new IllegalStateException("viewport client adoption");
            }
            output.write(getRegistryAndSyncRequest());
            output.flush();
            dispatch(core);
            RegistryGlobals globals = readGlobals(input, 3);
            output.write(bindGlobalAndSyncRequest(
                    globals.compositor, "wl_compositor", 4, 5));
            output.write(bindGlobalAndSyncRequest(globals.shm, "wl_shm", 6, 7));
            output.flush();
            dispatch(core);
            readUntilCallback(input, 5);
            readShmFormatsUntilCallback(input, 6, 7);
            output.write(createSurfaceAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 9);
            if (nativeSendShmPoolRequest(client.getFd(), 10, 40, 11) != 0) {
                throw new IllegalStateException("viewport SHM pool FD transfer");
            }
            dispatch(core);
            readUntilCallback(input, 11);
            output.write(createShmBufferAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 13);
            output.write(commitShmFrameAndSyncRequest());
            output.flush();
            dispatch(core);
            readFrameCommitUntilSync(input, 12, 14, 15);
            readDeleteId(input, 15);
            Bitmap frame = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
            if (nativeCopyLastFrameToBitmap(core, frame) != 0) {
                throw new IllegalStateException("viewport source frame was not readable");
            }
            presentNextFrame(core, input, 14, frame, frameView);

            output.write(bindGlobalAndSyncRequest(
                    globals.viewporter, "wp_viewporter", 16, 17));
            output.flush();
            dispatch(core);
            readUntilCallback(input, 17);
            output.write(createViewportAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 19);
            output.write(bindGlobalAndSyncRequest(
                    globals.fractionalScaleManager,
                    "wp_fractional_scale_manager_v1",
                    20,
                    21));
            output.flush();
            dispatch(core);
            readUntilCallback(input, 21);
            output.write(createFractionalScaleAndSyncRequest());
            output.flush();
            dispatch(core);
            readFractionalScaleUntilCallback(input, 22, 23, 120);

            output.write(applyViewportAndSyncRequest());
            output.flush();
            dispatch(core);
            readUnpresentedFrameUntilSync(input, 24, 25);
            readDeleteId(input, 25);
            if (nativeLastFrameWidth(core) != 4
                    || nativeLastFrameHeight(core) != 4
                    || nativePendingDamageCount(core) != 1) {
                throw new IllegalStateException("wp_viewport crop and destination were not applied");
            }
            Bitmap viewportFrame = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            int viewportCopy = nativeCopyLastFrameToBitmap(core, viewportFrame);
            int viewportFirst = viewportFrame.getPixel(0, 0);
            int viewportLast = viewportFrame.getPixel(3, 3);
            if (viewportCopy != 0
                    || viewportFirst != 0xff070605
                    || viewportLast != 0xff232221) {
                throw new IllegalStateException(
                        "wp_viewport pixels did not match the crop: copy="
                                + viewportCopy
                                + " first=" + Integer.toHexString(viewportFirst)
                                + " last=" + Integer.toHexString(viewportLast));
            }
            presentNextFrame(core, input, 24, viewportFrame, frameView);
            output.write(resetViewportAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 26);
            if (nativeLastFrameWidth(core) != 4
                    || nativeLastFrameHeight(core) != 2
                    || nativePendingDamageCount(core) != 1) {
                throw new IllegalStateException("wp_viewport reset did not restore buffer size");
            }
            Bitmap resetFrame = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
            if (nativeCopyLastFrameToBitmap(core, resetFrame) != 0) {
                throw new IllegalStateException("reset viewport frame was not readable");
            }
            presentNextFrame(core, input, 0, resetFrame, frameView);

            output.write(bindGlobalAndSyncRequest(globals.seat, "wl_seat", 27, 28));
            output.flush();
            dispatch(core);
            readSeatUntilCallback(input, 27, 28);
            output.write(getTouchAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 30);
            if (nativeTouchCount(core) != 1) {
                throw new IllegalStateException("wl_touch was not constructed");
            }

            runOnUiThread(() -> frameView.post(() -> {
                int[] location = new int[2];
                frameView.getLocationOnScreen(location);
                int targetX = location[0] + frameView.getWidth() / 2;
                int targetY = location[1] + frameView.getHeight() / 2;
                touchInputReady = true;
                Log.i(TAG, "touch target screen=" + targetX + "," + targetY);
            }));
            TouchInput down = awaitTouchInput(30);
            if (down.action != MotionEvent.ACTION_DOWN
                    || nativeTouchDown(core, down.id, down.x, down.y, down.time) != 1) {
                throw new IllegalStateException("Android touch down was not routed");
            }
            dispatch(core);
            int touchDownSerial = readTouchDownAndFrame(
                    input, 29, 8, down.id, down.x, down.y, down.time);
            int touchEvents = 1;
            int touchUpSerial = 0;
            while (touchUpSerial == 0) {
                TouchInput event = awaitTouchInput(5);
                if (event.action == MotionEvent.ACTION_MOVE) {
                    if (nativeTouchMotion(
                            core, event.id, event.x, event.y, event.time) != 1) {
                        throw new IllegalStateException("Android touch motion was not routed");
                    }
                    dispatch(core);
                    readTouchMotionAndFrame(
                            input, 29, event.id, event.x, event.y, event.time);
                    touchEvents++;
                } else if (event.action == MotionEvent.ACTION_UP
                        || event.action == MotionEvent.ACTION_POINTER_UP) {
                    if (nativeTouchUp(core, event.id, event.time) != 1) {
                        throw new IllegalStateException("Android touch up was not routed");
                    }
                    dispatch(core);
                    touchUpSerial = readTouchUpAndFrame(
                            input, 29, event.id, event.time);
                    touchEvents++;
                } else if (event.action == MotionEvent.ACTION_CANCEL) {
                    if (nativeTouchCancel(core) != 1) {
                        throw new IllegalStateException("Android touch cancel was not routed");
                    }
                    dispatch(core);
                    readTouchCancelAndFrame(input, 29);
                    touchUpSerial = touchDownSerial + 1;
                    touchEvents++;
                }
            }
            touchInputReady = false;
            if (touchUpSerial <= touchDownSerial
                    || nativeTouchEventCount(core) != touchEvents) {
                throw new IllegalStateException("wl_touch lifecycle was incomplete");
            }
            output.write(releaseTouchAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 31);
            if (nativeTouchCount(core) != 0) {
                throw new IllegalStateException("wl_touch resource leaked");
            }

            output.write(getModernPointerAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 33);
            if (nativePointerCount(core) != 1
                    || nativePointerMotion(core, 2, 1, 7001) != 1) {
                throw new IllegalStateException("cursor pointer focus was not established");
            }
            dispatch(core);
            int cursorEnterSerial =
                    readPointerEnterAndFrame(input, 32, 8, 2, 1);
            if (nativePointerEnterSerial(core) != cursorEnterSerial) {
                throw new IllegalStateException(
                        "cursor enter serial mismatch: wire=" + cursorEnterSerial
                                + " core=" + nativePointerEnterSerial(core));
            }
            output.write(createCursorSurfaceAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 35);
            output.write(setCursorAndSyncRequest(cursorEnterSerial, 34, 1, 1, 36));
            output.flush();
            dispatch(core);
            readUntilCallback(input, 36);
            if (nativeCursorHotspot(core, 0) != 1
                    || nativeCursorHotspot(core, 1) != 1) {
                throw new IllegalStateException(
                        "wl_pointer.set_cursor was ignored: hotspot="
                                + nativeCursorHotspot(core, 0)
                                + "," + nativeCursorHotspot(core, 1));
            }
            if (nativeSendShmPoolRequest(client.getFd(), 37, 40, 38) != 0) {
                throw new IllegalStateException("cursor SHM pool FD transfer");
            }
            dispatch(core);
            readUntilCallback(input, 38);
            output.write(createCursorBufferAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 40);
            output.write(mapCursorAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 41);
            if (nativeCursorWidth(core) != 2
                    || nativeCursorHeight(core) != 2
                    || nativeCursorHotspot(core, 0) != 1
                    || nativeCursorHotspot(core, 1) != 1
                    || nativeLastFrameWidth(core) != 4
                    || nativeLastFrameHeight(core) != 2) {
                throw new IllegalStateException(
                        "cursor surface state mismatch: cursor="
                                + nativeCursorWidth(core) + "x" + nativeCursorHeight(core)
                                + " hotspot=" + nativeCursorHotspot(core, 0)
                                + "," + nativeCursorHotspot(core, 1)
                                + " app=" + nativeLastFrameWidth(core)
                                + "x" + nativeLastFrameHeight(core));
            }
            Bitmap cursorBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            if (nativeCopyCursorToBitmap(core, cursorBitmap) != 0) {
                throw new IllegalStateException("cursor surface was not readable by Android");
            }
            runOnUiThread(() -> frameView.setPointerIcon(
                    PointerIcon.create(cursorBitmap, 1.0f, 1.0f)));

            output.write(offsetCursorAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 42);
            if (nativeCursorHotspot(core, 0) != 0
                    || nativeCursorHotspot(core, 1) != 1) {
                throw new IllegalStateException("cursor hotspot did not follow wl_surface.offset");
            }
            output.write(setCursorAndSyncRequest(cursorEnterSerial, 0, 0, 0, 43));
            output.flush();
            dispatch(core);
            readUntilCallback(input, 43);
            if (nativeCursorWidth(core) != 0 || nativeCursorHeight(core) != 0) {
                throw new IllegalStateException("null cursor surface did not hide the pointer");
            }
            runOnUiThread(() -> frameView.setPointerIcon(null));

            output.write(bindGlobalAndSyncRequest(
                    globals.pointerGestures, "zwp_pointer_gestures_v1", 44, 45));
            output.flush();
            dispatch(core);
            readUntilCallback(input, 45);
            output.write(createPointerGesturesAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 49);

            gestureInputReady = true;
            runOnUiThread(() -> injectSyntheticGesture(frameView));
            GestureInput gestureBegin = awaitGestureInput(5);
            if (gestureBegin.action != GestureInput.BEGIN
                    || nativeSwipeBegin(core, 2, gestureBegin.time) != 1) {
                throw new IllegalStateException("Android swipe begin was not routed");
            }
            dispatch(core);
            readGestureBegin(input, 46, 8, 2, gestureBegin.time);
            if (nativePinchBegin(core, 2, gestureBegin.time) != 1) {
                throw new IllegalStateException("Android pinch begin was not routed");
            }
            dispatch(core);
            readGestureBegin(input, 47, 8, 2, gestureBegin.time);
            if (nativeHoldBegin(core, 2, gestureBegin.time) != 1) {
                throw new IllegalStateException("Android hold begin was not routed");
            }
            dispatch(core);
            readGestureBegin(input, 48, 8, 2, gestureBegin.time);

            GestureInput gestureUpdate = awaitGestureInput(5);
            if (gestureUpdate.action != GestureInput.UPDATE) {
                throw new IllegalStateException("Android gesture update was missing");
            }
            int gestureDx = Math.round(gestureUpdate.dx * 1000.0f);
            int gestureDy = Math.round(gestureUpdate.dy * 1000.0f);
            int gestureScale = Math.round(gestureUpdate.scale * 1000.0f);
            int gestureRotation = Math.round(gestureUpdate.rotation * 1000.0f);
            if (nativeHoldEnd(core, true, gestureUpdate.time) != 1) {
                throw new IllegalStateException("moving hold was not cancelled");
            }
            dispatch(core);
            readGestureEnd(input, 48, 1, true, gestureUpdate.time);
            if (nativeSwipeUpdate(
                            core, gestureDx, gestureDy, gestureUpdate.time) != 1) {
                throw new IllegalStateException("Android swipe update was not routed");
            }
            dispatch(core);
            readSwipeUpdate(
                    input, 46, gestureDx, gestureDy, gestureUpdate.time);
            if (nativePinchUpdate(
                            core,
                            gestureDx,
                            gestureDy,
                            gestureScale,
                            gestureRotation,
                            gestureUpdate.time) != 1) {
                throw new IllegalStateException("Android pinch update was not routed");
            }
            dispatch(core);
            readPinchUpdate(
                    input,
                    47,
                    gestureDx,
                    gestureDy,
                    gestureScale,
                    gestureRotation,
                    gestureUpdate.time);

            GestureInput gestureEnd = awaitGestureInput(5);
            gestureInputReady = false;
            if (gestureEnd.action != GestureInput.END
                    || nativeSwipeEnd(core, gestureEnd.cancelled, gestureEnd.time) != 1) {
                throw new IllegalStateException("Android swipe end was not routed");
            }
            dispatch(core);
            readGestureEnd(input, 46, 2, gestureEnd.cancelled, gestureEnd.time);
            if (nativePinchEnd(core, gestureEnd.cancelled, gestureEnd.time) != 1) {
                throw new IllegalStateException("Android pinch end was not routed");
            }
            dispatch(core);
            readGestureEnd(input, 47, 2, gestureEnd.cancelled, gestureEnd.time);
            if (nativeGestureEventCount(core) != 8) {
                throw new IllegalStateException("pointer gesture lifecycle was incomplete");
            }
            output.write(destroyPointerGesturesAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 50);

            output.write(releaseModernPointerAndSyncRequest());
            output.flush();
            dispatch(core);
            readUntilCallback(input, 51);
            if (nativePointerCount(core) != 0) {
                throw new IllegalStateException("cursor pointer resource leaked");
            }
            return resetFrame;
            }
        } finally {
            nativeDestroyCore(core);
        }
    }
    private static byte[] getRegistryAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 1, 1, 12);
        request.putInt(2);
        putHeader(request, 1, 0, 12);
        request.putInt(3);
        return request.array();
    }

    private static byte[] bindGlobalAndSyncRequest(
            Global global, String interfaceValue, int objectId, int callbackId) {
        byte[] interfaceName = interfaceValue.getBytes(StandardCharsets.UTF_8);
        int stringLength = interfaceName.length + 1;
        int paddedLength = (stringLength + 3) & ~3;
        int bindSize = 8 + 4 + 4 + paddedLength + 4 + 4;
        ByteBuffer request = buffer(bindSize + 12);
        putHeader(request, 2, 0, bindSize);
        request.putInt(global.name);
        request.putInt(stringLength);
        request.put(interfaceName);
        request.put((byte) 0);
        while (request.position() < 8 + 4 + 4 + paddedLength) request.put((byte) 0);
        int supportedVersion = "wl_seat".equals(interfaceValue) ? 9 : 6;
        request.putInt(Math.min(global.version, supportedVersion));
        request.putInt(objectId);
        putHeader(request, 1, 0, 12);
        request.putInt(callbackId);
        return request.array();
    }

    private static byte[] createSurfaceAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 4, 0, 12);
        request.putInt(8);
        putHeader(request, 1, 0, 12);
        request.putInt(9);
        return request.array();
    }

    private static byte[] createViewportAndSyncRequest() {
        ByteBuffer request = buffer(28);
        putHeader(request, 16, 1, 16);
        request.putInt(18);
        request.putInt(8);
        putHeader(request, 1, 0, 12);
        request.putInt(19);
        return request.array();
    }

    private static byte[] createFractionalScaleAndSyncRequest() {
        ByteBuffer request = buffer(28);
        putHeader(request, 20, 1, 16);
        request.putInt(22);
        request.putInt(8);
        putHeader(request, 1, 0, 12);
        request.putInt(23);
        return request.array();
    }

    private static byte[] applyViewportAndSyncRequest() {
        ByteBuffer request = buffer(96);
        putHeader(request, 18, 1, 24);
        request.putInt(256);
        request.putInt(0);
        request.putInt(512);
        request.putInt(512);
        putHeader(request, 18, 2, 16);
        request.putInt(4);
        request.putInt(4);
        putHeader(request, 8, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 8, 3, 12);
        request.putInt(24);
        putHeader(request, 8, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(25);
        return request.array();
    }

    private static byte[] resetViewportAndSyncRequest() {
        ByteBuffer request = buffer(60);
        putHeader(request, 18, 1, 24);
        request.putInt(-256);
        request.putInt(-256);
        request.putInt(-256);
        request.putInt(-256);
        putHeader(request, 18, 2, 16);
        request.putInt(-1);
        request.putInt(-1);
        putHeader(request, 8, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(26);
        return request.array();
    }
    private static byte[] getTouchAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 27, 2, 12);
        request.putInt(29);
        putHeader(request, 1, 0, 12);
        request.putInt(30);
        return request.array();
    }

    private static byte[] releaseTouchAndSyncRequest() {
        ByteBuffer request = buffer(20);
        putHeader(request, 29, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(31);
        return request.array();
    }
    private static byte[] getModernPointerAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 27, 0, 12);
        request.putInt(32);
        putHeader(request, 1, 0, 12);
        request.putInt(33);
        return request.array();
    }

    private static byte[] createCursorSurfaceAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 4, 0, 12);
        request.putInt(34);
        putHeader(request, 1, 0, 12);
        request.putInt(35);
        return request.array();
    }

    private static byte[] setCursorAndSyncRequest(
            int serial, int surfaceId, int hotspotX, int hotspotY, int callbackId) {
        ByteBuffer request = buffer(36);
        putHeader(request, 32, 0, 24);
        request.putInt(serial);
        request.putInt(surfaceId);
        request.putInt(hotspotX);
        request.putInt(hotspotY);
        putHeader(request, 1, 0, 12);
        request.putInt(callbackId);
        return request.array();
    }

    private static byte[] createCursorBufferAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 37, 0, 32);
        request.putInt(39);
        request.putInt(0);
        request.putInt(2);
        request.putInt(2);
        request.putInt(8);
        request.putInt(1);
        putHeader(request, 1, 0, 12);
        request.putInt(40);
        return request.array();
    }

    private static byte[] mapCursorAndSyncRequest() {
        ByteBuffer request = buffer(64);
        putHeader(request, 34, 1, 20);
        request.putInt(39);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 34, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(2);
        request.putInt(2);
        putHeader(request, 34, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(41);
        return request.array();
    }

    private static byte[] offsetCursorAndSyncRequest() {
        ByteBuffer request = buffer(36);
        putHeader(request, 34, 10, 16);
        request.putInt(1);
        request.putInt(0);
        putHeader(request, 34, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(42);
        return request.array();
    }

    private static byte[] createPointerGesturesAndSyncRequest() {
        ByteBuffer request = buffer(60);
        putHeader(request, 44, 0, 16);
        request.putInt(46);
        request.putInt(32);
        putHeader(request, 44, 1, 16);
        request.putInt(47);
        request.putInt(32);
        putHeader(request, 44, 3, 16);
        request.putInt(48);
        request.putInt(32);
        putHeader(request, 1, 0, 12);
        request.putInt(49);
        return request.array();
    }

    private static byte[] destroyPointerGesturesAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 46, 0, 8);
        putHeader(request, 47, 0, 8);
        putHeader(request, 48, 0, 8);
        putHeader(request, 44, 2, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(50);
        return request.array();
    }
    private static byte[] releaseModernPointerAndSyncRequest() {
        ByteBuffer request = buffer(20);
        putHeader(request, 32, 1, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(51);
        return request.array();
    }
    private static byte[] destroySurfaceAndSyncRequest() {
        ByteBuffer request = buffer(20);
        putHeader(request, 8, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(17);
        return request.array();
    }

    private static byte[] createSecondSurfaceAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 4, 0, 12);
        request.putInt(20);
        putHeader(request, 1, 0, 12);
        request.putInt(21);
        return request.array();
    }

    private static byte[] createXdgToplevelAndSyncRequest() {
        ByteBuffer request = buffer(48);
        putHeader(request, 18, 2, 16);
        request.putInt(22);
        request.putInt(20);
        putHeader(request, 22, 1, 12);
        request.putInt(23);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(24);
        return request.array();
    }

    private static byte[] ackXdgConfigureAndSyncRequest(
            int configureSerial, int callbackId) {
        ByteBuffer request = buffer(24);
        putHeader(request, 22, 4, 12);
        request.putInt(configureSerial);
        putHeader(request, 1, 0, 12);
        request.putInt(callbackId);
        return request.array();
    }

    private static byte[] commitParentWindowGeometryAndSyncRequest(
            int configureSerial, int callbackId) {
        ByteBuffer request = buffer(56);
        putHeader(request, 22, 4, 12);
        request.putInt(configureSerial);
        putHeader(request, 22, 3, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(callbackId);
        return request.array();
    }

    private static byte[] syncRequest(int callbackId) {
        ByteBuffer request = buffer(12);
        putHeader(request, 1, 0, 12);
        request.putInt(callbackId);
        return request.array();
    }

    private static byte[] createDataSelectionAndSyncRequest(int serial) {
        String plain = "text/plain";
        String utf8 = "text/plain;charset=utf-8";
        int plainSize = waylandStringMessageSize(plain);
        int utf8Size = waylandStringMessageSize(utf8);
        ByteBuffer request = buffer(12 + plainSize + utf8Size + 16 + 16 + 12);
        putHeader(request, 85, 0, 12);
        request.putInt(87);
        putWaylandStringMessage(request, 87, 0, plain);
        putWaylandStringMessage(request, 87, 0, utf8);
        putHeader(request, 85, 1, 16);
        request.putInt(88);
        request.putInt(83);
        putHeader(request, 88, 1, 16);
        request.putInt(87);
        request.putInt(serial);
        putHeader(request, 1, 0, 12);
        request.putInt(89);
        return request.array();
    }

    private static byte[] clearDataSelectionAndSyncRequest(int serial) {
        ByteBuffer request = buffer(28);
        putHeader(request, 88, 1, 16);
        request.putInt(0);
        request.putInt(serial);
        putHeader(request, 1, 0, 12);
        request.putInt(90);
        return request.array();
    }

    private static byte[] destroyDataSelectionAndSyncRequest(
            int waylandOfferId, int androidOfferId) {
        ByteBuffer request = buffer(52);
        putHeader(request, waylandOfferId, 2, 8);
        putHeader(request, androidOfferId, 2, 8);
        putHeader(request, 87, 1, 8);
        putHeader(request, 88, 2, 8);
        putHeader(request, 83, 3, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(102);
        return request.array();
    }
    private static byte[] createTextInputAndSyncRequest() {
        ByteBuffer request = buffer(28);
        putHeader(request, 94, 1, 16);
        request.putInt(96);
        request.putInt(83);
        putHeader(request, 1, 0, 12);
        request.putInt(97);
        return request.array();
    }

    private static byte[] enableTextInputAndSyncRequest() {
        byte[] surrounding = "hello".getBytes(StandardCharsets.UTF_8);
        int stringLength = surrounding.length + 1;
        int paddedLength = (stringLength + 3) & ~3;
        int surroundingSize = 8 + 4 + paddedLength + 8;
        ByteBuffer request = buffer(8 + surroundingSize + 16 + 24 + 8 + 12);
        putHeader(request, 96, 1, 8);
        putHeader(request, 96, 3, surroundingSize);
        request.putInt(stringLength);
        request.put(surrounding);
        request.put((byte) 0);
        int stringEnd = 8 + 8 + 4 + paddedLength;
        while (request.position() < stringEnd) request.put((byte) 0);
        request.putInt(5);
        request.putInt(5);
        putHeader(request, 96, 5, 16);
        request.putInt(3);
        request.putInt(6);
        putHeader(request, 96, 6, 24);
        request.putInt(4);
        request.putInt(6);
        request.putInt(2);
        request.putInt(12);
        putHeader(request, 96, 7, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(98);
        return request.array();
    }

    private static byte[] disableTextInputAndSyncRequest() {
        ByteBuffer request = buffer(28);
        putHeader(request, 96, 2, 8);
        putHeader(request, 96, 7, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(100);
        return request.array();
    }

    private static byte[] destroyTextInputAndSyncRequest() {
        ByteBuffer request = buffer(28);
        putHeader(request, 96, 0, 8);
        putHeader(request, 94, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(101);
        return request.array();
    }
    private static byte[] createSecondShmBufferAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 26, 0, 32);
        request.putInt(28);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        request.putInt(24);
        request.putInt(1);
        putHeader(request, 1, 0, 12);
        request.putInt(29);
        return request.array();
    }

    private static byte[] commitXdgFrameAndSyncRequest() {
        ByteBuffer request = buffer(76);
        putHeader(request, 20, 1, 20);
        request.putInt(28);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 20, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 20, 3, 12);
        request.putInt(30);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(31);
        return request.array();
    }

    private static byte[] createPositionerAndSyncRequest(int parentConfigureSerial) {
        ByteBuffer request = buffer(152);
        putHeader(request, 18, 1, 12);
        request.putInt(45);
        putHeader(request, 45, 1, 16);
        request.putInt(2);
        request.putInt(2);
        putHeader(request, 45, 2, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(1);
        request.putInt(1);
        putHeader(request, 45, 3, 12);
        request.putInt(5);
        putHeader(request, 45, 4, 12);
        request.putInt(8);
        putHeader(request, 45, 5, 12);
        request.putInt(15);
        putHeader(request, 45, 6, 16);
        request.putInt(3);
        request.putInt(0);
        putHeader(request, 45, 7, 8);
        putHeader(request, 45, 8, 16);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 45, 9, 12);
        request.putInt(parentConfigureSerial);
        putHeader(request, 1, 0, 12);
        request.putInt(46);
        return request.array();
    }

    private static byte[] createPopupSurfaceAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 4, 0, 12);
        request.putInt(47);
        putHeader(request, 1, 0, 12);
        request.putInt(48);
        return request.array();
    }

    private static byte[] createPopupRoleAndSyncRequest(int inputSerial) {
        ByteBuffer request = buffer(72);
        putHeader(request, 18, 2, 16);
        request.putInt(49);
        request.putInt(47);
        putHeader(request, 49, 2, 20);
        request.putInt(50);
        request.putInt(22);
        request.putInt(45);
        putHeader(request, 50, 1, 16);
        request.putInt(32);
        request.putInt(inputSerial);
        putHeader(request, 47, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(51);
        return request.array();
    }

    private static byte[] ackPopupConfigureAndSyncRequest(int serial, int callbackId) {
        ByteBuffer request = buffer(32);
        putHeader(request, 49, 4, 12);
        request.putInt(serial);
        putHeader(request, 47, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(callbackId);
        return request.array();
    }

    private static byte[] mapPopupFrameAndSyncRequest() {
        ByteBuffer request = buffer(64);
        putHeader(request, 47, 1, 20);
        request.putInt(28);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 47, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 47, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(53);
        return request.array();
    }

    private static byte[] createNestedPositionerAndSyncRequest(int parentConfigureSerial) {
        ByteBuffer request = buffer(100);
        putHeader(request, 18, 1, 12);
        request.putInt(56);
        putHeader(request, 56, 1, 16);
        request.putInt(1);
        request.putInt(1);
        putHeader(request, 56, 2, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(1);
        request.putInt(1);
        putHeader(request, 56, 3, 12);
        request.putInt(5);
        putHeader(request, 56, 4, 12);
        request.putInt(8);
        putHeader(request, 56, 9, 12);
        request.putInt(parentConfigureSerial);
        putHeader(request, 1, 0, 12);
        request.putInt(57);
        return request.array();
    }

    private static byte[] createNestedPopupSurfaceAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 4, 0, 12);
        request.putInt(58);
        putHeader(request, 1, 0, 12);
        request.putInt(59);
        return request.array();
    }

    private static byte[] createNestedPopupRoleAndSyncRequest(int inputSerial) {
        ByteBuffer request = buffer(72);
        putHeader(request, 18, 2, 16);
        request.putInt(60);
        request.putInt(58);
        putHeader(request, 60, 2, 20);
        request.putInt(61);
        request.putInt(49);
        request.putInt(56);
        putHeader(request, 61, 1, 16);
        request.putInt(32);
        request.putInt(inputSerial);
        putHeader(request, 58, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(62);
        return request.array();
    }

    private static byte[] ackNestedPopupConfigureAndSyncRequest(int serial) {
        ByteBuffer request = buffer(32);
        putHeader(request, 60, 4, 12);
        request.putInt(serial);
        putHeader(request, 58, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(63);
        return request.array();
    }

    private static byte[] mapNestedPopupFrameAndSyncRequest() {
        ByteBuffer request = buffer(64);
        putHeader(request, 58, 1, 20);
        request.putInt(28);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 58, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 58, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(64);
        return request.array();
    }

    private static byte[] setParentInputRegionAndSyncRequest() {
        ByteBuffer request = buffer(100);
        putHeader(request, 4, 1, 12);
        request.putInt(65);
        putHeader(request, 65, 1, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(2);
        request.putInt(2);
        putHeader(request, 65, 2, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(1);
        request.putInt(2);
        putHeader(request, 47, 5, 12);
        request.putInt(65);
        putHeader(request, 47, 6, 8);
        putHeader(request, 65, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(66);
        return request.array();
    }

    private static byte[] createSynchronizedSubsurfaceAndSyncRequest() {
        ByteBuffer request = buffer(124);
        putHeader(request, 4, 0, 12);
        request.putInt(70);
        putHeader(request, 68, 1, 20);
        request.putInt(71);
        request.putInt(70);
        request.putInt(20);
        putHeader(request, 71, 1, 16);
        request.putInt(1);
        request.putInt(0);
        putHeader(request, 71, 2, 12);
        request.putInt(20);
        putHeader(request, 70, 1, 20);
        request.putInt(28);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 70, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 70, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(72);
        return request.array();
    }

    private static byte[] commitSubsurfaceParentAndSyncRequest() {
        ByteBuffer request = buffer(20);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(73);
        return request.array();
    }

    private static byte[] desyncAndUnmapSubsurfaceAndSyncRequest() {
        ByteBuffer request = buffer(48);
        putHeader(request, 71, 5, 8);
        putHeader(request, 70, 1, 20);
        request.putInt(0);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 70, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(74);
        return request.array();
    }

    private static byte[] remapDesynchronizedSubsurfaceAndSyncRequest() {
        ByteBuffer request = buffer(64);
        putHeader(request, 70, 1, 20);
        request.putInt(28);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 70, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 70, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(75);
        return request.array();
    }

    private static byte[] destroySubsurfaceAndSyncRequest() {
        ByteBuffer request = buffer(36);
        putHeader(request, 71, 0, 8);
        putHeader(request, 70, 0, 8);
        putHeader(request, 68, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(76);
        return request.array();
    }

    private static byte[] destroyPopupResourcesAndSyncRequest() {
        ByteBuffer request = buffer(108);
        putHeader(request, 61, 0, 8);
        putHeader(request, 60, 0, 8);
        putHeader(request, 58, 0, 8);
        putHeader(request, 56, 0, 8);
        putHeader(request, 50, 0, 8);
        putHeader(request, 49, 0, 8);
        putHeader(request, 47, 0, 8);
        putHeader(request, 45, 0, 8);
        putHeader(request, 28, 0, 8);
        putHeader(request, 26, 1, 8);
        putHeader(request, 34, 1, 8);
        putHeader(request, 32, 3, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(77);
        return request.array();
    }
    private static byte[] transformAndScaleXdgFrameAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 20, 7, 12);
        request.putInt(1);
        putHeader(request, 20, 8, 12);
        request.putInt(2);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(31);
        return request.array();
    }

    private static byte[] resetXdgBufferStateAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 20, 7, 12);
        request.putInt(0);
        putHeader(request, 20, 8, 12);
        request.putInt(1);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(31);
        return request.array();
    }

    private static byte[] getPointerAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 32, 0, 12);
        request.putInt(34);
        putHeader(request, 1, 0, 12);
        request.putInt(35);
        return request.array();
    }

    private static byte[] getKeyboardAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 32, 1, 12);
        request.putInt(36);
        putHeader(request, 1, 0, 12);
        request.putInt(37);
        return request.array();
    }

    private static byte[] unmapXdgToplevelAndSyncRequest() {
        ByteBuffer request = buffer(40);
        putHeader(request, 20, 1, 20);
        request.putInt(0);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(41);
        return request.array();
    }

    private static byte[] remapXdgToplevelAndSyncRequest() {
        ByteBuffer request = buffer(64);
        putHeader(request, 20, 1, 20);
        request.putInt(28);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 20, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(42);
        return request.array();
    }

    private static byte[] releaseInputAndSyncRequest() {
        ByteBuffer request = buffer(20);
        putHeader(request, 36, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(43);
        return request.array();
    }

    private static byte[] releaseOutputAndSyncRequest() {
        ByteBuffer request = buffer(20);
        putHeader(request, 78, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(81);
        return request.array();
    }

    private static byte[] destroyXdgToplevelAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 23, 0, 8);
        putHeader(request, 22, 0, 8);
        putHeader(request, 20, 0, 8);
        putHeader(request, 18, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(103);
        return request.array();
    }
    private static byte[] createShmBufferAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 10, 0, 32);
        request.putInt(12);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        request.putInt(24);
        request.putInt(1);
        putHeader(request, 1, 0, 12);
        request.putInt(13);
        return request.array();
    }

    private static byte[] commitShmFrameAndSyncRequest() {
        ByteBuffer request = buffer(76);
        putHeader(request, 8, 1, 20);
        request.putInt(12);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 8, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 8, 3, 12);
        request.putInt(14);
        putHeader(request, 8, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(15);
        return request.array();
    }

    private static byte[] destroyShmResourcesAndSyncRequest() {
        ByteBuffer request = buffer(28);
        putHeader(request, 12, 0, 8);
        putHeader(request, 10, 1, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(16);
        return request.array();
    }

    private static RegistryGlobals readGlobals(FileInputStream input, int callbackId)
            throws Exception {
        Global compositor = null;
        Global shm = null;
        Global subcompositor = null;
        Global xdgWmBase = null;
        Global seat = null;
        Global dataDeviceManager = null;
        Global textInputManager = null;
        Global pointerGestures = null;
        Global viewporter = null;
        Global fractionalScaleManager = null;
        Global output = null;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == 2 && message.opcode == 0) {
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                int name = body.getInt();
                int length = body.getInt();
                if (length <= 0 || length > body.remaining()) {
                    throw new IllegalStateException("invalid registry interface");
                }
                byte[] encoded = new byte[length];
                body.get(encoded);
                int padding = ((length + 3) & ~3) - length;
                body.position(body.position() + padding);
                int version = body.getInt();
                String interfaceName = new String(encoded, 0, length - 1, StandardCharsets.UTF_8);
                if ("wl_compositor".equals(interfaceName)) {
                    compositor = new Global(name, version);
                } else if ("wl_subcompositor".equals(interfaceName)) {
                    subcompositor = new Global(name, version);
                } else if ("wl_shm".equals(interfaceName)) {
                    shm = new Global(name, version);
                } else if ("xdg_wm_base".equals(interfaceName)) {
                    xdgWmBase = new Global(name, version);
                } else if ("wl_seat".equals(interfaceName)) {
                    seat = new Global(name, version);
                } else if ("wl_data_device_manager".equals(interfaceName)) {
                    dataDeviceManager = new Global(name, version);
                } else if ("zwp_text_input_manager_v3".equals(interfaceName)) {
                    textInputManager = new Global(name, version);
                } else if ("zwp_pointer_gestures_v1".equals(interfaceName)) {
                    pointerGestures = new Global(name, version);
                } else if ("wp_viewporter".equals(interfaceName)) {
                    viewporter = new Global(name, version);
                } else if ("wp_fractional_scale_manager_v1".equals(interfaceName)) {
                    fractionalScaleManager = new Global(name, version);
                } else if ("wl_output".equals(interfaceName)) {
                    output = new Global(name, version);
                }
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (compositor == null
                        || shm == null
                        || subcompositor == null
                        || xdgWmBase == null
                        || seat == null
                        || dataDeviceManager == null
                        || textInputManager == null
                        || pointerGestures == null
                        || viewporter == null
                        || fractionalScaleManager == null
                        || output == null) {
                    throw new IllegalStateException("required Wayland globals not advertised");
                }
                return new RegistryGlobals(compositor, subcompositor, shm, xdgWmBase, seat,
                        dataDeviceManager, textInputManager, pointerGestures, viewporter,
                        fractionalScaleManager, output);
            }
        }
    }

    private static int readDataSelectionUntilCallback(
            FileInputStream input, int dataDeviceId, int callbackId) throws Exception {
        int offerId = 0;
        boolean foundPlain = false;
        boolean foundUtf8 = false;
        boolean selected = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == dataDeviceId
                    && message.opcode == 0
                    && message.body.length == 4) {
                offerId = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
            } else if (offerId != 0 && message.objectId == offerId && message.opcode == 0) {
                String mimeType = readWaylandString(message.body);
                foundPlain |= "text/plain".equals(mimeType);
                foundUtf8 |= "text/plain;charset=utf-8".equals(mimeType);
            } else if (message.objectId == dataDeviceId
                    && message.opcode == 5
                    && message.body.length == 4) {
                int selectedOffer = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
                selected = offerId != 0 && selectedOffer == offerId;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (offerId == 0 || !foundPlain || !foundUtf8 || !selected) {
                    throw new IllegalStateException(
                            "wl_data_device selection offer was incomplete");
                }
                return offerId;
            }
        }
    }

    private static void readClearedDataSelectionUntilCallback(
            FileInputStream input, int sourceId, int dataDeviceId, int callbackId)
            throws Exception {
        boolean cancelled = false;
        boolean cleared = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            cancelled |= message.objectId == sourceId
                    && message.opcode == 2
                    && message.body.length == 0;
            if (message.objectId == dataDeviceId
                    && message.opcode == 5
                    && message.body.length == 4) {
                int selectedOffer = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
                cleared |= selectedOffer == 0;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!cancelled || !cleared) {
                    throw new IllegalStateException(
                            "wl_data_device selection clear was incomplete");
                }
                return;
            }
        }
    }

    private static String readWaylandString(byte[] bodyBytes) {
        ByteBuffer body = ByteBuffer.wrap(bodyBytes).order(ByteOrder.nativeOrder());
        if (body.remaining() < 4) {
            throw new IllegalStateException("missing Wayland string length");
        }
        int length = body.getInt();
        if (length <= 0 || length > body.remaining()) {
            throw new IllegalStateException("invalid Wayland string");
        }
        byte[] encoded = new byte[length];
        body.get(encoded);
        if (encoded[length - 1] != 0) {
            throw new IllegalStateException("unterminated Wayland string");
        }
        return new String(encoded, 0, length - 1, StandardCharsets.UTF_8);
    }
    private static void readTextInputEnterUntilCallback(
            FileInputStream input, int textInputId, int surfaceId, int callbackId)
            throws Exception {
        boolean entered = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == textInputId
                    && message.opcode == 0
                    && message.body.length == 4) {
                entered |= ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt() == surfaceId;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!entered) {
                    throw new IllegalStateException(
                            "text-input did not enter the keyboard-focused surface");
                }
                return;
            }
        }
    }

    private static void readImeEventsUntilCallback(
            FileInputStream input, int textInputId, int callbackId, int serial)
            throws Exception {
        boolean preedit = false;
        boolean committed = false;
        boolean deleted = false;
        int doneCount = 0;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == textInputId && message.opcode == 2) {
                preedit |= "compose".equals(readWaylandString(message.body));
            } else if (message.objectId == textInputId && message.opcode == 3) {
                committed |= "Archphene IME".equals(readWaylandString(message.body));
            } else if (message.objectId == textInputId
                    && message.opcode == 4
                    && message.body.length == 8) {
                ByteBuffer body = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder());
                deleted |= body.getInt() == 1 && body.getInt() == 0;
            } else if (message.objectId == textInputId
                    && message.opcode == 5
                    && message.body.length == 4) {
                int doneSerial = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
                if (doneSerial != serial) {
                    throw new IllegalStateException("text-input done serial mismatch");
                }
                doneCount++;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!preedit || !committed || !deleted || doneCount != 3) {
                    throw new IllegalStateException("Android IME event sequence was incomplete");
                }
                return;
            }
        }
    }
    private static void readNullSelectionUntilCallback(
            FileInputStream input, int dataDeviceId, int callbackId) throws Exception {
        boolean cleared = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == dataDeviceId
                    && message.opcode == 5
                    && message.body.length == 4) {
                cleared |= ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt() == 0;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!cleared) {
                    throw new IllegalStateException(
                            "Android clipboard selection was not withdrawn");
                }
                return;
            }
        }
    }
    private static void readUntilCallback(FileInputStream input, int callbackId) throws Exception {
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == callbackId && message.opcode == 0) return;
        }
    }

    private static void readDeleteId(FileInputStream input, int expectedId) throws Exception {
        Message message = readMessage(input);
        if (message.objectId != 1 || message.opcode != 1 || message.body.length != 4) {
            throw new IllegalStateException("expected wl_display.delete_id");
        }
        int deletedId = ByteBuffer.wrap(message.body)
                .order(ByteOrder.nativeOrder()).getInt();
        if (deletedId != expectedId) {
            throw new IllegalStateException(
                    "unexpected wl_display.delete_id " + deletedId + ", expected " + expectedId);
        }
    }

    private static void readOutputUntilCallback(
            FileInputStream input,
            int surfaceId,
            int outputId,
            int callbackId,
            int expectedWidth,
            int expectedHeight,
            int expectedScale,
            boolean initial)
            throws Exception {
        boolean geometry = false;
        boolean mode = false;
        boolean scale = false;
        boolean done = false;
        boolean named = false;
        boolean described = false;
        boolean entered = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == outputId && message.opcode == 0) {
                geometry = message.body.length >= 32;
            } else if (message.objectId == outputId && message.opcode == 1) {
                if (message.body.length != 16) {
                    throw new IllegalStateException("invalid wl_output.mode event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                mode = body.getInt() == 3
                        && body.getInt() == expectedWidth
                        && body.getInt() == expectedHeight
                        && body.getInt() == 60_000;
            } else if (message.objectId == outputId && message.opcode == 2) {
                done = message.body.length == 0;
            } else if (message.objectId == outputId && message.opcode == 3) {
                scale = message.body.length == 4
                        && ByteBuffer.wrap(message.body)
                                .order(ByteOrder.nativeOrder()).getInt() == expectedScale;
            } else if (message.objectId == outputId && message.opcode == 4) {
                named = readString(message.body).equals("Archphene-0");
            } else if (message.objectId == outputId && message.opcode == 5) {
                described = readString(message.body)
                        .equals("Archphene Android application viewport");
            } else if (message.objectId == surfaceId && message.opcode == 0) {
                entered = message.body.length == 4
                        && ByteBuffer.wrap(message.body)
                                .order(ByteOrder.nativeOrder()).getInt() == outputId;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!mode || !scale || !done
                        || (initial && (!geometry || !named || !described || !entered))) {
                    throw new IllegalStateException("wl_output event sequence was incomplete");
                }
                return;
            }
        }
    }

    private static String readString(byte[] encodedBody) {
        ByteBuffer body = ByteBuffer.wrap(encodedBody).order(ByteOrder.nativeOrder());
        if (body.remaining() < 4) {
            return "";
        }
        int length = body.getInt();
        if (length <= 0 || length > body.remaining()) {
            return "";
        }
        byte[] encoded = new byte[length];
        body.get(encoded);
        return new String(encoded, 0, length - 1, StandardCharsets.UTF_8);
    }

    private static void readSeatUntilCallback(
            FileInputStream input, int seatId, int callbackId) throws Exception {
        boolean pointerAndKeyboard = false;
        boolean named = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == seatId && message.opcode == 0) {
                if (message.body.length != 4) {
                    throw new IllegalStateException("invalid wl_seat.capabilities event");
                }
                int capabilities = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
                pointerAndKeyboard = capabilities == 7;
            } else if (message.objectId == seatId && message.opcode == 1) {
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                int length = body.getInt();
                if (length <= 0 || length > body.remaining()) {
                    throw new IllegalStateException("invalid wl_seat.name event");
                }
                byte[] encoded = new byte[length];
                body.get(encoded);
                named = "Archphene".equals(
                        new String(encoded, 0, length - 1, StandardCharsets.UTF_8));
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!pointerAndKeyboard || !named) {
                    throw new IllegalStateException("wl_seat metadata was incomplete");
                }
                return;
            }
        }
    }

    private static void readKeyboardMetadataUntilCallback(
            FileInputStream input, int keyboardId, int surfaceId, int callbackId)
            throws Exception {
        boolean repeatInfo = false;
        boolean entered = false;
        boolean modifiers = false;
        int enterSerial = 0;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == keyboardId && message.opcode == 5) {
                if (message.body.length != 8) {
                    throw new IllegalStateException("invalid wl_keyboard.repeat_info event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                repeatInfo = body.getInt() == 25 && body.getInt() == 400;
            } else if (message.objectId == keyboardId && message.opcode == 1) {
                if (message.body.length != 12) {
                    throw new IllegalStateException("invalid wl_keyboard.enter event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                enterSerial = body.getInt();
                entered = enterSerial != 0
                        && body.getInt() == surfaceId
                        && body.getInt() == 0;
            } else if (message.objectId == keyboardId && message.opcode == 4) {
                if (message.body.length != 20) {
                    throw new IllegalStateException("invalid wl_keyboard.modifiers event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                modifiers = body.getInt() == enterSerial
                        && body.getInt() == 0
                        && body.getInt() == 0
                        && body.getInt() == 0
                        && body.getInt() == 0;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!repeatInfo || !entered || !modifiers) {
                    throw new IllegalStateException("wl_keyboard metadata was incomplete");
                }
                return;
            }
        }
    }

    private static void readKeyboardUnmapUntilCallback(
            FileInputStream input, int keyboardId, int surfaceId, int callbackId)
            throws Exception {
        boolean left = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == keyboardId && message.opcode == 2) {
                if (message.body.length != 8) {
                    throw new IllegalStateException("invalid wl_keyboard.leave event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                left = body.getInt() != 0 && body.getInt() == surfaceId;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!left) {
                    throw new IllegalStateException("xdg unmap did not clear keyboard focus");
                }
                return;
            }
        }
    }

    private static void readKeyboardRemapUntilCallback(
            FileInputStream input,
            int keyboardId,
            int surfaceId,
            int bufferId,
            int callbackId)
            throws Exception {
        boolean bufferReleased = false;
        boolean entered = false;
        boolean modifiers = false;
        int enterSerial = 0;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            bufferReleased |= message.objectId == bufferId && message.opcode == 0;
            if (message.objectId == keyboardId && message.opcode == 1) {
                if (message.body.length != 12) {
                    throw new IllegalStateException("invalid remap wl_keyboard.enter event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                enterSerial = body.getInt();
                entered = enterSerial != 0
                        && body.getInt() == surfaceId
                        && body.getInt() == 0;
            } else if (message.objectId == keyboardId && message.opcode == 4) {
                if (message.body.length != 20) {
                    throw new IllegalStateException("invalid remap wl_keyboard.modifiers event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                modifiers = body.getInt() == enterSerial
                        && body.getInt() == 0
                        && body.getInt() == 0
                        && body.getInt() == 0
                        && body.getInt() == 0;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!bufferReleased || !entered || !modifiers) {
                    throw new IllegalStateException("xdg remap events were incomplete");
                }
                return;
            }
        }
    }

    private KeyboardInput awaitKeyboardInput(int timeoutSeconds) throws Exception {
        KeyboardInput input = keyboardInputs.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (input == null) {
            throw new IllegalStateException("timed out waiting for Android KeyEvent");
        }
        return input;
    }

    private static int readKeyboardKey(
            FileInputStream input, int keyboardId, int key, boolean pressed, int time)
            throws Exception {
        Message message;
        do {
            message = readMessage(input);
            throwIfDisplayError(message);
        } while (message.objectId == 1 && message.opcode == 1);
        if (message.objectId != keyboardId
                || message.opcode != 3
                || message.body.length != 16) {
            throw new IllegalStateException("invalid wl_keyboard.key event");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int serial = body.getInt();
        if (serial == 0
                || body.getInt() != time
                || body.getInt() != key
                || body.getInt() != (pressed ? 1 : 0)) {
            throw new IllegalStateException("unexpected wl_keyboard.key event");
        }
        return serial;
    }

    private void presentNextFrame(
            long core,
            FileInputStream input,
            int frameCallbackId,
            Bitmap frame,
            ImageView frameView)
            throws Exception {
        int expectedCallbacks = frameCallbackId == 0 ? 0 : 1;
        int damageCount = nativePendingDamageCount(core);
        int damageX = nativePendingDamageComponent(core, 0);
        int damageY = nativePendingDamageComponent(core, 1);
        int damageWidth = nativePendingDamageComponent(core, 2);
        int damageHeight = nativePendingDamageComponent(core, 3);
        if (damageCount <= 0
                || damageX < 0
                || damageY < 0
                || damageWidth <= 0
                || damageHeight <= 0
                || damageX + damageWidth > frame.getWidth()
                || damageY + damageHeight > frame.getHeight()) {
            throw new IllegalStateException("native presentation damage was invalid");
        }
        runOnUiThread(() -> {
            frameView.setImageBitmap(frame);
            int viewWidth = frameView.getWidth();
            int viewHeight = frameView.getHeight();
            int left = damageX * viewWidth / frame.getWidth();
            int top = damageY * viewHeight / frame.getHeight();
            int right = (damageX + damageWidth) * viewWidth / frame.getWidth();
            int bottom = (damageY + damageHeight) * viewHeight / frame.getHeight();
            frameView.postInvalidateOnAnimation(left, top, right, bottom);
            Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
                int frameTime = (int) (frameTimeNanos / 1_000_000L);
                Log.i(TAG, "Choreographer frame time=" + Integer.toUnsignedString(frameTime)
                        + " damage=" + damageX + "," + damageY + " "
                        + damageWidth + "x" + damageHeight);
                presentationTimes.offer(frameTime);
            });
        });
        Integer frameTime = presentationTimes.poll(30, TimeUnit.SECONDS);
        if (frameTime == null) {
            throw new IllegalStateException("timed out waiting for Android Choreographer");
        }
        if (nativePendingFrameCallbackCount(core) != expectedCallbacks
                || nativePresentFrame(core, frameTime) != expectedCallbacks) {
            throw new IllegalStateException("Wayland frame callback was not presentation-gated");
        }
        if (expectedCallbacks != 0) {
            dispatch(core);
            readPresentedFrame(input, frameCallbackId, frameTime);
            readDeleteId(input, frameCallbackId);
        }
        if (nativePendingFrameCallbackCount(core) != 0
                || nativePendingDamageCount(core) != 0) {
            throw new IllegalStateException("presented frame batch was not drained");
        }
    }

    private GestureInput awaitGestureInput(int timeoutSeconds) throws Exception {
        GestureInput input = gestureInputs.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (input == null) {
            throw new IllegalStateException("timed out waiting for Android gesture MotionEvent");
        }
        return input;
    }
    private TouchInput awaitTouchInput(int timeoutSeconds) throws Exception {
        TouchInput input = touchInputs.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (input == null) {
            throw new IllegalStateException("timed out waiting for Android touch MotionEvent");
        }
        return input;
    }
    private PointerInput awaitPointerInput(int timeoutSeconds) throws Exception {
        PointerInput input = pointerInputs.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (input == null) {
            throw new IllegalStateException("timed out waiting for Android MotionEvent");
        }
        return input;
    }

    private static Message readGestureMessage(
            FileInputStream input, int gestureId, int expectedOpcode) throws Exception {
        Message message;
        do {
            message = readMessage(input);
            throwIfDisplayError(message);
        } while (message.objectId == 1 && message.opcode == 1);
        if (message.objectId != gestureId || message.opcode != expectedOpcode) {
            throw new IllegalStateException(
                    "unexpected pointer gesture event object=" + message.objectId
                            + " opcode=" + message.opcode
                            + " expected=" + expectedOpcode);
        }
        return message;
    }

    private static int readGestureBegin(
            FileInputStream input,
            int gestureId,
            int surfaceId,
            int fingers,
            int time)
            throws Exception {
        Message message = readGestureMessage(input, gestureId, 0);
        if (message.body.length != 16) {
            throw new IllegalStateException("invalid pointer gesture begin event");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int serial = body.getInt();
        if (serial == 0
                || body.getInt() != time
                || body.getInt() != surfaceId
                || body.getInt() != fingers) {
            throw new IllegalStateException("pointer gesture begin payload mismatch");
        }
        return serial;
    }

    private static void readSwipeUpdate(
            FileInputStream input,
            int gestureId,
            int dxMilli,
            int dyMilli,
            int time)
            throws Exception {
        Message message = readGestureMessage(input, gestureId, 1);
        if (message.body.length != 12) {
            throw new IllegalStateException("invalid swipe update event");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int expectedDx = Math.round(dxMilli / 1000.0f * 256.0f);
        int expectedDy = Math.round(dyMilli / 1000.0f * 256.0f);
        if (body.getInt() != time
                || body.getInt() != expectedDx
                || body.getInt() != expectedDy) {
            throw new IllegalStateException("swipe update payload mismatch");
        }
    }

    private static void readPinchUpdate(
            FileInputStream input,
            int gestureId,
            int dxMilli,
            int dyMilli,
            int scaleMilli,
            int rotationMilli,
            int time)
            throws Exception {
        Message message = readGestureMessage(input, gestureId, 1);
        if (message.body.length != 20) {
            throw new IllegalStateException("invalid pinch update event");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int expectedDx = Math.round(dxMilli / 1000.0f * 256.0f);
        int expectedDy = Math.round(dyMilli / 1000.0f * 256.0f);
        int expectedScale = Math.round(scaleMilli / 1000.0f * 256.0f);
        int expectedRotation = Math.round(rotationMilli / 1000.0f * 256.0f);
        int actualTime = body.getInt();
        int actualDx = body.getInt();
        int actualDy = body.getInt();
        int actualScale = body.getInt();
        int actualRotation = body.getInt();
        if (actualTime != time
                || Math.abs(actualDx - expectedDx) > 1
                || Math.abs(actualDy - expectedDy) > 1
                || Math.abs(actualScale - expectedScale) > 1
                || Math.abs(actualRotation - expectedRotation) > 1) {
            throw new IllegalStateException(
                    "pinch update payload mismatch: actual="
                            + actualTime + "," + actualDx + "," + actualDy + ","
                            + actualScale + "," + actualRotation
                            + " expected=" + time + "," + expectedDx + "," + expectedDy + ","
                            + expectedScale + "," + expectedRotation);
        }
    }

    private static int readGestureEnd(
            FileInputStream input,
            int gestureId,
            int opcode,
            boolean cancelled,
            int time)
            throws Exception {
        Message message = readGestureMessage(input, gestureId, opcode);
        if (message.body.length != 12) {
            throw new IllegalStateException("invalid pointer gesture end event");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int serial = body.getInt();
        if (serial == 0
                || body.getInt() != time
                || body.getInt() != (cancelled ? 1 : 0)) {
            throw new IllegalStateException("pointer gesture end payload mismatch");
        }
        return serial;
    }
    private static int readTouchDownAndFrame(
            FileInputStream input,
            int touchId,
            int surfaceId,
            int contactId,
            int x,
            int y,
            int time)
            throws Exception {
        Message message = readTouchMessage(input, touchId, 0);
        if (message.body.length != 24) {
            throw new IllegalStateException("invalid wl_touch.down event");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int serial = body.getInt();
        if (serial == 0
                || body.getInt() != time
                || body.getInt() != surfaceId
                || body.getInt() != contactId
                || body.getInt() != x * 256
                || body.getInt() != y * 256) {
            throw new IllegalStateException("wl_touch.down payload mismatch");
        }
        readTouchFrame(input, touchId);
        return serial;
    }

    private static void readTouchMotionAndFrame(
            FileInputStream input, int touchId, int contactId, int x, int y, int time)
            throws Exception {
        Message message = readTouchMessage(input, touchId, 2);
        if (message.body.length != 16) {
            throw new IllegalStateException("invalid wl_touch.motion event");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        if (body.getInt() != time
                || body.getInt() != contactId
                || body.getInt() != x * 256
                || body.getInt() != y * 256) {
            throw new IllegalStateException("wl_touch.motion payload mismatch");
        }
        readTouchFrame(input, touchId);
    }

    private static int readTouchUpAndFrame(
            FileInputStream input, int touchId, int contactId, int time) throws Exception {
        Message message = readTouchMessage(input, touchId, 1);
        if (message.body.length != 12) {
            throw new IllegalStateException("invalid wl_touch.up event");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int serial = body.getInt();
        if (serial == 0 || body.getInt() != time || body.getInt() != contactId) {
            throw new IllegalStateException("wl_touch.up payload mismatch");
        }
        readTouchFrame(input, touchId);
        return serial;
    }

    private static void readTouchCancelAndFrame(FileInputStream input, int touchId)
            throws Exception {
        Message message = readTouchMessage(input, touchId, 4);
        if (message.body.length != 0) {
            throw new IllegalStateException("invalid wl_touch.cancel event");
        }
        readTouchFrame(input, touchId);
    }

    private static Message readTouchMessage(
            FileInputStream input, int touchId, int expectedOpcode) throws Exception {
        Message message;
        do {
            message = readMessage(input);
            throwIfDisplayError(message);
        } while (message.objectId == 1 && message.opcode == 1);
        if (message.objectId != touchId || message.opcode != expectedOpcode) {
            throw new IllegalStateException(
                    "unexpected wl_touch event object=" + message.objectId
                            + " opcode=" + message.opcode
                            + " expected=" + expectedOpcode);
        }
        return message;
    }

    private static void readTouchFrame(FileInputStream input, int touchId) throws Exception {
        Message frame = readTouchMessage(input, touchId, 3);
        if (frame.body.length != 0) {
            throw new IllegalStateException("invalid wl_touch.frame event");
        }
    }
    private static Message readPointerMessage(
            FileInputStream input, int pointerId, int expectedOpcode) throws Exception {
        Message message;
        do {
            message = readMessage(input);
            throwIfDisplayError(message);
        } while (message.objectId == 1 && message.opcode == 1);
        if (message.objectId != pointerId || message.opcode != expectedOpcode) {
            throw new IllegalStateException(
                    "unexpected wl_pointer event object=" + message.objectId
                            + " opcode=" + message.opcode
                            + " expected=" + expectedOpcode);
        }
        return message;
    }

    private static void readPointerFrame(FileInputStream input, int pointerId)
            throws Exception {
        Message frame = readPointerMessage(input, pointerId, 5);
        if (frame.body.length != 0) {
            throw new IllegalStateException("invalid wl_pointer.frame event");
        }
    }

    private static int readPointerEnterAndFrame(
            FileInputStream input, int pointerId, int surfaceId, int x, int y)
            throws Exception {
        Message message = readPointerMessage(input, pointerId, 0);
        if (message.body.length != 16) {
            throw new IllegalStateException("invalid wl_pointer.enter size");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int serial = body.getInt();
        if (serial == 0
                || body.getInt() != surfaceId
                || body.getInt() != x * 256
                || body.getInt() != y * 256) {
            throw new IllegalStateException("invalid wl_pointer.enter event");
        }
        readPointerFrame(input, pointerId);
        return serial;
    }

    private static void readPointerMotionAndFrame(
            FileInputStream input, int pointerId, int x, int y, int time)
            throws Exception {
        Message message = readPointerMessage(input, pointerId, 2);
        if (message.body.length != 12) {
            throw new IllegalStateException("invalid wl_pointer.motion size");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        if (body.getInt() != time
                || body.getInt() != x * 256
                || body.getInt() != y * 256) {
            throw new IllegalStateException("invalid wl_pointer.motion event");
        }
        readPointerFrame(input, pointerId);
    }

    private static void readPointerAxisAndFrame(
            FileInputStream input,
            int pointerId,
            float horizontal,
            float vertical,
            int time)
            throws Exception {
        Message source = readPointerMessage(input, pointerId, 6);
        ByteBuffer sourceBody = ByteBuffer.wrap(source.body).order(ByteOrder.nativeOrder());
        if (source.body.length != 4 || sourceBody.getInt() != 0) {
            throw new IllegalStateException("invalid wl_pointer.axis_source event");
        }
        if (vertical != 0.0f) {
            readPointerAxisPair(
                    input,
                    pointerId,
                    0,
                    Math.round(-vertical),
                    Math.round(-vertical * 15.0f * 256.0f),
                    time);
        }
        if (horizontal != 0.0f) {
            readPointerAxisPair(
                    input,
                    pointerId,
                    1,
                    Math.round(horizontal),
                    Math.round(horizontal * 15.0f * 256.0f),
                    time);
        }
        readPointerFrame(input, pointerId);
    }

    private static void readPointerAxisPair(
            FileInputStream input,
            int pointerId,
            int axis,
            int discrete,
            int fixedValue,
            int time)
            throws Exception {
        Message value120Message = readPointerMessage(input, pointerId, 9);
        ByteBuffer value120Body =
                ByteBuffer.wrap(value120Message.body).order(ByteOrder.nativeOrder());
        if (value120Message.body.length != 8
                || value120Body.getInt() != axis
                || value120Body.getInt() != discrete * 120) {
            throw new IllegalStateException("invalid wl_pointer.axis_value120 event");
        }
        Message relativeDirectionMessage = readPointerMessage(input, pointerId, 10);
        ByteBuffer relativeDirectionBody = ByteBuffer.wrap(relativeDirectionMessage.body)
                .order(ByteOrder.nativeOrder());
        if (relativeDirectionMessage.body.length != 8
                || relativeDirectionBody.getInt() != axis
                || relativeDirectionBody.getInt() != 0) {
            throw new IllegalStateException("invalid wl_pointer.axis_relative_direction event");
        }
        Message axisMessage = readPointerMessage(input, pointerId, 4);
        ByteBuffer axisBody = ByteBuffer.wrap(axisMessage.body).order(ByteOrder.nativeOrder());
        if (axisMessage.body.length != 12
                || axisBody.getInt() != time
                || axisBody.getInt() != axis
                || axisBody.getInt() != fixedValue) {
            throw new IllegalStateException("invalid wl_pointer.axis event");
        }
    }

    private static int readPointerButtonAndFrame(
            FileInputStream input, int pointerId, boolean pressed, int time)
            throws Exception {
        Message message = readPointerMessage(input, pointerId, 3);
        if (message.body.length != 16) {
            throw new IllegalStateException("invalid wl_pointer.button size");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int serial = body.getInt();
        if (serial == 0
                || body.getInt() != time
                || body.getInt() != 272
                || body.getInt() != (pressed ? 1 : 0)) {
            throw new IllegalStateException("invalid wl_pointer.button event");
        }
        readPointerFrame(input, pointerId);
        return serial;
    }

    private static int readPointerLeaveAndFrame(
            FileInputStream input, int pointerId, int surfaceId) throws Exception {
        Message message = readPointerMessage(input, pointerId, 1);
        if (message.body.length != 8) {
            throw new IllegalStateException("invalid wl_pointer.leave size");
        }
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int serial = body.getInt();
        if (serial == 0 || body.getInt() != surfaceId) {
            throw new IllegalStateException("invalid wl_pointer.leave event");
        }
        readPointerFrame(input, pointerId);
        return serial;
    }
    private static void readNestedPopupDoneUntilCallback(
            FileInputStream input,
            int childPopupId,
            int parentPopupId,
            int pointerId,
            int childSurfaceId,
            int rootSurfaceId,
            int callbackId)
            throws Exception {
        int dismissed = 0;
        boolean pointerLeftChild = false;
        boolean pointerEnteredRoot = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.opcode == 1 && message.body.length == 0
                    && (message.objectId == childPopupId || message.objectId == parentPopupId)) {
                int expected = dismissed == 0 ? childPopupId : parentPopupId;
                if (dismissed >= 2 || message.objectId != expected) {
                    throw new IllegalStateException("nested popup_done order was invalid");
                }
                dismissed++;
            } else if (message.objectId == pointerId
                    && message.opcode == 1
                    && message.body.length == 8) {
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                pointerLeftChild = body.getInt() != 0 && body.getInt() == childSurfaceId;
            } else if (message.objectId == pointerId
                    && message.opcode == 0
                    && message.body.length == 16) {
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                pointerEnteredRoot = body.getInt() != 0
                        && body.getInt() == rootSurfaceId
                        && body.getInt() == 3 * 256
                        && body.getInt() == 0;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (dismissed != 2 || !pointerLeftChild || !pointerEnteredRoot) {
                    throw new IllegalStateException(
                            "nested popup dismissal did not restore pointer focus");
                }
                return;
            }
        }
    }
    private static int readPopupConfigureUntilCallback(
            FileInputStream input,
            int xdgSurfaceId,
            int popupId,
            int callbackId,
            int expectedX,
            int expectedY,
            int expectedWidth,
            int expectedHeight)
            throws Exception {
        boolean geometryConfigured = false;
        Integer configureSerial = null;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == popupId && message.opcode == 0) {
                if (message.body.length != 16) {
                    throw new IllegalStateException("invalid xdg_popup.configure event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                geometryConfigured = body.getInt() == expectedX
                        && body.getInt() == expectedY
                        && body.getInt() == expectedWidth
                        && body.getInt() == expectedHeight;
            } else if (message.objectId == xdgSurfaceId && message.opcode == 0) {
                if (message.body.length != 4) {
                    throw new IllegalStateException("invalid popup xdg_surface.configure event");
                }
                configureSerial = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!geometryConfigured
                        || configureSerial == null
                        || configureSerial == 0) {
                    throw new IllegalStateException("xdg_popup configure sequence was incomplete");
                }
                return configureSerial;
            }
        }
    }

    private static void readToplevelConfigureUntilCallback(
            FileInputStream input,
            int xdgSurfaceId,
            int toplevelId,
            int callbackId,
            int expectedSerial,
            int expectedWidth,
            int expectedHeight)
            throws Exception {
        boolean sizeConfigured = false;
        boolean serialConfigured = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == toplevelId && message.opcode == 0) {
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                sizeConfigured = body.remaining() >= 12
                        && body.getInt() == expectedWidth
                        && body.getInt() == expectedHeight;
            } else if (message.objectId == xdgSurfaceId && message.opcode == 0) {
                serialConfigured = message.body.length == 4
                        && ByteBuffer.wrap(message.body)
                                .order(ByteOrder.nativeOrder()).getInt() == expectedSerial;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!sizeConfigured || !serialConfigured) {
                    throw new IllegalStateException("root toplevel configure sequence was incomplete");
                }
                return;
            }
        }
    }

    private static void readQueuedConfiguresUntilCallback(
            FileInputStream input,
            int xdgSurfaceId,
            int toplevelId,
            int callbackId,
            int firstSerial,
            int firstWidth,
            int firstHeight,
            int secondSerial,
            int secondWidth,
            int secondHeight)
            throws Exception {
        int completed = 0;
        boolean awaitingSurfaceConfigure = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == toplevelId && message.opcode == 0) {
                if (awaitingSurfaceConfigure || completed >= 2 || message.body.length < 12) {
                    throw new IllegalStateException("invalid queued xdg_toplevel.configure event");
                }
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                int expectedWidth = completed == 0 ? firstWidth : secondWidth;
                int expectedHeight = completed == 0 ? firstHeight : secondHeight;
                if (body.getInt() != expectedWidth || body.getInt() != expectedHeight) {
                    throw new IllegalStateException("queued xdg_toplevel.configure size mismatch");
                }
                awaitingSurfaceConfigure = true;
            } else if (message.objectId == xdgSurfaceId && message.opcode == 0) {
                if (!awaitingSurfaceConfigure || message.body.length != 4) {
                    throw new IllegalStateException("orphan queued xdg_surface.configure event");
                }
                int serial = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
                int expectedSerial = completed == 0 ? firstSerial : secondSerial;
                if (serial != expectedSerial) {
                    throw new IllegalStateException("queued xdg configure serial mismatch");
                }
                completed++;
                awaitingSurfaceConfigure = false;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (completed != 2 || awaitingSurfaceConfigure) {
                    throw new IllegalStateException("xdg configure queue events were incomplete");
                }
                return;
            }
        }
    }

    private static int readXdgConfigureUntilCallback(
            FileInputStream input, int xdgSurfaceId, int toplevelId, int callbackId)
            throws Exception {
        boolean toplevelConfigured = false;
        Integer configureSerial = null;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == toplevelId && message.opcode == 0) {
                if (message.body.length < 12) {
                    throw new IllegalStateException("invalid xdg_toplevel.configure event");
                }
                toplevelConfigured = true;
            } else if (message.objectId == xdgSurfaceId && message.opcode == 0) {
                if (message.body.length != 4) {
                    throw new IllegalStateException("invalid xdg_surface.configure event");
                }
                configureSerial = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!toplevelConfigured || configureSerial == null || configureSerial == 0) {
                    throw new IllegalStateException("xdg configure sequence was incomplete");
                }
                return configureSerial;
            }
        }
    }

    private static void readFractionalScaleUntilCallback(
            FileInputStream input,
            int fractionalScaleId,
            int callbackId,
            int expectedScale)
            throws Exception {
        boolean preferred = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == fractionalScaleId && message.opcode == 0) {
                preferred = message.body.length == 4
                        && ByteBuffer.wrap(message.body)
                                .order(ByteOrder.nativeOrder()).getInt() == expectedScale;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!preferred) {
                    throw new IllegalStateException(
                            "fractional-scale preferred_scale event was incomplete");
                }
                return;
            }
        }
    }

    private static void readUnpresentedFrameUntilSync(
            FileInputStream input, int frameCallbackId, int syncCallbackId)
            throws Exception {
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == frameCallbackId && message.opcode == 0) {
                throw new IllegalStateException(
                        "viewport frame completed before Android presentation");
            }
            if (message.objectId == syncCallbackId && message.opcode == 0) {
                return;
            }
        }
    }
    private static void readFrameCommitUntilSync(
            FileInputStream input, int bufferId, int frameCallbackId, int syncCallbackId)
            throws Exception {
        boolean bufferReleased = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            bufferReleased |= message.objectId == bufferId && message.opcode == 0;
            if (message.objectId == frameCallbackId && message.opcode == 0) {
                throw new IllegalStateException(
                        "wl_surface.frame completed before Android presentation");
            }
            if (message.objectId == syncCallbackId && message.opcode == 0) {
                if (!bufferReleased) {
                    throw new IllegalStateException("frame buffer was not released before sync");
                }
                return;
            }
        }
    }

    private static void readPresentedFrame(
            FileInputStream input, int frameCallbackId, int expectedTime)
            throws Exception {
        Message message = readMessage(input);
        throwIfDisplayError(message);
        if (message.objectId != frameCallbackId
                || message.opcode != 0
                || message.body.length != 4
                || ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt() != expectedTime) {
            throw new IllegalStateException("invalid presentation-paced wl_callback.done");
        }
    }

    private static void readShmFormatsUntilCallback(
            FileInputStream input, int shmId, int callbackId) throws Exception {
        boolean foundArgb8888 = false;
        boolean foundXrgb8888 = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == shmId && message.opcode == 0 && message.body.length == 4) {
                int format = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder()).getInt();
                foundArgb8888 |= format == 0;
                foundXrgb8888 |= format == 1;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!foundArgb8888 || !foundXrgb8888) {
                    throw new IllegalStateException("required wl_shm formats not advertised");
                }
                return;
            }
        }
    }

    private static void throwIfDisplayError(Message message) {
        if (message.objectId != 1 || message.opcode != 0) return;
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int failedObject = body.getInt();
        int code = body.getInt();
        int length = body.getInt();
        if (length <= 0 || length > body.remaining()) {
            throw new IllegalStateException("Wayland protocol error " + code
                    + " on object " + failedObject);
        }
        byte[] encoded = new byte[length];
        body.get(encoded);
        String detail = new String(encoded, 0, length - 1, StandardCharsets.UTF_8);
        throw new IllegalStateException("Wayland protocol error " + code
                + " on object " + failedObject + ": " + detail);
    }
    private static Message readMessage(FileInputStream input) throws Exception {
        ByteBuffer header = ByteBuffer.wrap(readExact(input, 8)).order(ByteOrder.nativeOrder());
        int objectId = header.getInt();
        int word = header.getInt();
        int opcode = word & 0xffff;
        int size = word >>> 16;
        if (size < 8 || (size & 3) != 0 || size > 65535) {
            throw new IllegalStateException("invalid Wayland message size " + size);
        }
        return new Message(objectId, opcode, readExact(input, size - 8));
    }

    private static byte[] readExact(FileInputStream input, int length) throws Exception {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(result, offset, length - offset);
            if (read < 0) throw new IllegalStateException("Wayland server closed the socket");
            offset += read;
        }
        return result;
    }

    private static void dispatch(long core) {
        if (nativeDispatchOnce(core) < 0) throw new IllegalStateException("native dispatch");
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
    }

    private static void putHeader(ByteBuffer buffer, int objectId, int opcode, int size) {
        buffer.putInt(objectId);
        buffer.putInt((size << 16) | opcode);
    }

    private static int waylandStringMessageSize(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length + 1;
        return 8 + 4 + ((length + 3) & ~3);
    }

    private static void putWaylandStringMessage(
            ByteBuffer request, int objectId, int opcode, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        int length = encoded.length + 1;
        int size = waylandStringMessageSize(value);
        int end = request.position() + size;
        putHeader(request, objectId, opcode, size);
        request.putInt(length);
        request.put(encoded);
        request.put((byte) 0);
        while (request.position() < end) request.put((byte) 0);
    }
    private static int toLinuxKeyCode(int androidKeyCode) {
        if (androidKeyCode >= KeyEvent.KEYCODE_A && androidKeyCode <= KeyEvent.KEYCODE_Z) {
            int[] linuxLetters = {
                30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
                49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44
            };
            return linuxLetters[androidKeyCode - KeyEvent.KEYCODE_A];
        }
        return switch (androidKeyCode) {
            case KeyEvent.KEYCODE_ENTER -> 28;
            case KeyEvent.KEYCODE_DEL -> 14;
            case KeyEvent.KEYCODE_SPACE -> 57;
            case KeyEvent.KEYCODE_TAB -> 15;
            case KeyEvent.KEYCODE_ESCAPE -> 1;
            case KeyEvent.KEYCODE_DPAD_UP -> 103;
            case KeyEvent.KEYCODE_DPAD_LEFT -> 105;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> 106;
            case KeyEvent.KEYCODE_DPAD_DOWN -> 108;
            default -> 0;
        };
    }

    private static final class KeyboardInput {
        final int key;
        final boolean pressed;
        final int time;

        KeyboardInput(int key, boolean pressed, int time) {
            this.key = key;
            this.pressed = pressed;
            this.time = time;
        }
    }

    private static final class GestureInput {
        static final int BEGIN = 0;
        static final int UPDATE = 1;
        static final int END = 2;

        final int action;
        final float dx;
        final float dy;
        final float scale;
        final float rotation;
        final boolean cancelled;
        final int time;

        private GestureInput(
                int action,
                float dx,
                float dy,
                float scale,
                float rotation,
                boolean cancelled,
                int time) {
            this.action = action;
            this.dx = dx;
            this.dy = dy;
            this.scale = scale;
            this.rotation = rotation;
            this.cancelled = cancelled;
            this.time = time;
        }

        static GestureInput begin(int time) {
            return new GestureInput(BEGIN, 0.0f, 0.0f, 1.0f, 0.0f, false, time);
        }

        static GestureInput update(
                float dx, float dy, float scale, float rotation, int time) {
            return new GestureInput(UPDATE, dx, dy, scale, rotation, false, time);
        }

        static GestureInput end(boolean cancelled, int time) {
            return new GestureInput(END, 0.0f, 0.0f, 1.0f, 0.0f, cancelled, time);
        }
    }
    private static final class TouchInput {
        final int action;
        final int id;
        final int x;
        final int y;
        final int time;

        TouchInput(int action, int id, int x, int y, int time) {
            this.action = action;
            this.id = id;
            this.x = x;
            this.y = y;
            this.time = time;
        }
    }
    private static final class PointerInput {
        final int action;
        final int x;
        final int y;
        final int time;
        final float horizontalScroll;
        final float verticalScroll;

        PointerInput(
                int action,
                int x,
                int y,
                int time,
                float horizontalScroll,
                float verticalScroll) {
            this.action = action;
            this.x = x;
            this.y = y;
            this.time = time;
            this.horizontalScroll = horizontalScroll;
            this.verticalScroll = verticalScroll;
        }
    }
    private static final class RegistryGlobals {
        final Global compositor;
        final Global subcompositor;
        final Global shm;
        final Global xdgWmBase;
        final Global seat;
        final Global dataDeviceManager;
        final Global textInputManager;
        final Global pointerGestures;
        final Global viewporter;
        final Global fractionalScaleManager;
        final Global output;

        RegistryGlobals(
                Global compositor,
                Global subcompositor,
                Global shm,
                Global xdgWmBase,
                Global seat,
                Global dataDeviceManager,
                Global textInputManager,
                Global pointerGestures,
                Global viewporter,
                Global fractionalScaleManager,
                Global output) {
            this.compositor = compositor;
            this.subcompositor = subcompositor;
            this.shm = shm;
            this.xdgWmBase = xdgWmBase;
            this.seat = seat;
            this.dataDeviceManager = dataDeviceManager;
            this.textInputManager = textInputManager;
            this.pointerGestures = pointerGestures;
            this.viewporter = viewporter;
            this.fractionalScaleManager = fractionalScaleManager;
            this.output = output;
        }
    }
    private static final class Global {
        final int name;
        final int version;

        Global(int name, int version) {
            this.name = name;
            this.version = version;
        }
    }

    private static final class Message {
        final int objectId;
        final int opcode;
        final byte[] body;

        Message(int objectId, int opcode, byte[] body) {
            this.objectId = objectId;
            this.opcode = opcode;
            this.body = body;
        }
    }
}
