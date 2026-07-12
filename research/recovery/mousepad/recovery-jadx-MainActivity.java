package org.archphene.linux.mousepad;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Region;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.window.OnBackInvokedCallback;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.archphene.linux.mousepad.MainActivity;

/* JADX INFO: loaded from: C:\Users\Kyle\Documents\source\ArchpheneOS\tooling\recovery-dex\classes.dex */
public final class MainActivity extends Activity {
    private static final String CLIPBOARD_PROBE_PAYLOAD = "libarchphene_qt_clipboard_probe.so";
    private static final String FRAME_CLIENT_PAYLOAD = "libarchphene_frame_client.so";
    private static final String GLIBC_LIBC = "libc.so.6";
    private static final String GLIBC_LOADER = "libarchphene_ld.so";
    private static final String GTK3_CONFORMANCE_PAYLOAD = "libarchphene_gtk3_conformance.so";
    private static final String KCALC_PAYLOAD = "libarchphene_mousepad.so";
    private static final String SHM_FRAME_CLIENT_PAYLOAD = "libarchphene_shm_frame_client.so";
    private static final String SYSCALL_PROBE = "libarchphene_syscall_probe.so";
    private static final String TAG = "ArchpheneMousepad";
    private static final String WAYLAND_ANDROID_API_CLIENT_PAYLOAD = "libarchphene_wayland_android_api_client.so";
    private static final String WAYLAND_ANDROID_API_RENDER_CLIENT_PAYLOAD = "libarchphene_wayland_android_api_render_client.so";
    private static final String WAYLAND_ANDROID_API_XDG_CLIENT_PAYLOAD = "libarchphene_wayland_android_api_xdg_client.so";
    private static final String WAYLAND_ANDROID_CLIENT_LIB = "libarchphene_wayland_client_android.so";
    private static final String WAYLAND_API_CLIENT_PAYLOAD = "libarchphene_wayland_api_client.so";
    private static final String WAYLAND_EVENTED_CLIENT_PAYLOAD = "libarchphene_wayland_evented_client.so";
    private static final String WAYLAND_JNI_PAYLOAD = "libarchphene_wayland_jni.so";
    private static final String WAYLAND_PROBE_PAYLOAD = "libarchphene_wayland_socket_probe.so";
    private static final String WAYLAND_SHM_CLIENT_PAYLOAD = "libarchphene_wayland_shm_client.so";
    private static final String WAYLAND_XDG_CLIENT_PAYLOAD = "libarchphene_wayland_xdg_client.so";
    private volatile RawWaylandShmServer activeInteractiveServer;
    private volatile Process activeLinuxProcess;
    private volatile BridgeRootView bridgeRootView;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private ClipboardManager clipboardManager;
    private String pendingBridgeClipboardText;
    private Runnable pendingViewportResize;
    private boolean popupBackInProgress;
    private boolean suppressNextBackInvocation;
    private float touchDownX;
    private float touchDownY;
    private float touchLastY;
    private boolean touchMayRequestTextInput;
    private boolean touchPointerPressed;
    private boolean touchScrolling;
    private volatile boolean waylandTextInputRequested;
    private static volatile WeakReference<MainActivity> currentActivity = new WeakReference<>(null);
    private static final String JNI_LOAD_ERROR = loadWaylandJni();

    /* JADX INFO: Access modifiers changed from: private */
    public static native FileDescriptor createFilesystemWaylandServer(String str) throws IOException;

    static String loadWaylandJni() {
        try {
            System.loadLibrary("archphene_wayland_jni");
            return "";
        } catch (Throwable th) {
            return th.toString();
        }
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        currentActivity = new WeakReference<>(this);
        String stringExtra = getIntent().getStringExtra("archphene_android_clipboard_text");
        if (stringExtra != null) {
            ((ClipboardManager) getSystemService("clipboard")).setPrimaryClip(ClipData.newPlainText("Archphene test input", stringExtra));
        }
        this.clipboardManager = (ClipboardManager) getSystemService("clipboard");
        this.clipboardListener = new ClipboardManager.OnPrimaryClipChangedListener() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda2
            @Override // android.content.ClipboardManager.OnPrimaryClipChangedListener
            public final void onPrimaryClipChanged() {
                MainActivity.lambda$onCreate$0();
            }
        };
        this.clipboardManager.addPrimaryClipChangedListener(this.clipboardListener);
        getWindow().setStatusBarColor(-1);
        getWindow().setNavigationBarColor(-16777216);
        getWindow().setSoftInputMode(19);
        final ImageView imageView = new ImageView(this);
        imageView.setAdjustViewBounds(false);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        imageView.setBackgroundColor(0);
        final String str = "Mousepad Archphene interactive launch\n";
        writeReportArtifact("Mousepad Archphene interactive launch\n");
        Log.i(TAG, "Starting interactive Wayland client");
        if (getIntent().getBooleanExtra("archphene_glibc_probe", false)) {
            startGlibcRuntimeProbe();
        }
        if (getIntent().getBooleanExtra("archphene_access_probe", false)) {
            startSyscallProbe("access");
        }
        String stringExtra2 = getIntent().getStringExtra("archphene_syscall_probe");
        if (stringExtra2 != null && !stringExtra2.isEmpty()) {
            startSyscallProbe(stringExtra2);
        }
        final BridgeRootView bridgeRootView = new BridgeRootView(this);
        this.bridgeRootView = bridgeRootView;
        bridgeRootView.setBackgroundColor(-1);
        bridgeRootView.setFocusable(true);
        bridgeRootView.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 30) {
            bridgeRootView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda3
                @Override // android.view.View.OnApplyWindowInsetsListener
                public final WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                    return MainActivity.lambda$onCreate$1(view, windowInsets);
                }
            });
            bridgeRootView.requestApplyInsets();
        }
        bridgeRootView.setOnTouchListener(new View.OnTouchListener() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda4
            @Override // android.view.View.OnTouchListener
            public final boolean onTouch(View view, MotionEvent motionEvent) {
                return this.f$0.lambda$onCreate$2(imageView, bridgeRootView, view, motionEvent);
            }
        });
        bridgeRootView.setOnGenericMotionListener(new View.OnGenericMotionListener() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda5
            @Override // android.view.View.OnGenericMotionListener
            public final boolean onGenericMotion(View view, MotionEvent motionEvent) {
                return this.f$0.lambda$onCreate$5(imageView, view, motionEvent);
            }
        });
        bridgeRootView.setOnHoverListener(new View.OnHoverListener() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda6
            @Override // android.view.View.OnHoverListener
            public final boolean onHover(View view, MotionEvent motionEvent) {
                return this.f$0.lambda$onCreate$6(imageView, view, motionEvent);
            }
        });
        bridgeRootView.addView(imageView, new FrameLayout.LayoutParams(-1, -1));
        imageView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda7
            @Override // android.view.View.OnLayoutChangeListener
            public final void onLayoutChange(View view, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8) {
                this.f$0.lambda$onCreate$7(view, i, i2, i3, i4, i5, i6, i7, i8);
            }
        });
        setContentView(bridgeRootView);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, new OnBackInvokedCallback() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda8
                @Override // android.window.OnBackInvokedCallback
                public final void onBackInvoked() {
                    this.f$0.lambda$onCreate$9();
                }
            });
        }
        bridgeRootView.requestFocus();
        bridgeRootView.post(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda9
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$onCreate$10(imageView, str);
            }
        });
    }

    static /* synthetic */ void lambda$onCreate$0() {
    }

    static /* synthetic */ WindowInsets lambda$onCreate$1(View view, WindowInsets windowInsets) {
        Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
        view.setPadding(insets.left, insets.top, insets.right, Math.max(insets.bottom, windowInsets.getInsets(WindowInsets.Type.ime()).bottom));
        return windowInsets;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$onCreate$2(ImageView imageView, final BridgeRootView bridgeRootView, View view, MotionEvent motionEvent) {
        MainActivity mainActivity;
        final RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        if (rawWaylandShmServer == null) {
            return true;
        }
        float[] fArrMapPointerCoordinates = mapPointerCoordinates(imageView, rawWaylandShmServer, motionEvent.getX(), motionEvent.getY());
        boolean z = (motionEvent.getSource() & 8194) == 8194;
        int actionMasked = motionEvent.getActionMasked();
        if (z) {
            rawWaylandShmServer.handleAndroidMotionEvent(actionMasked, fArrMapPointerCoordinates[0], fArrMapPointerCoordinates[1], motionEvent.getEventTime());
            return true;
        }
        if (actionMasked == 0) {
            this.touchDownX = fArrMapPointerCoordinates[0];
            this.touchDownY = fArrMapPointerCoordinates[1];
            this.touchLastY = fArrMapPointerCoordinates[1];
            this.touchScrolling = false;
            this.touchMayRequestTextInput = rawWaylandShmServer.shouldRequestTextInput(fArrMapPointerCoordinates[0], fArrMapPointerCoordinates[1]);
            rawWaylandShmServer.handleAndroidMotionEvent(2, fArrMapPointerCoordinates[0], fArrMapPointerCoordinates[1], motionEvent.getEventTime());
            this.touchPointerPressed = false;
            final float f = fArrMapPointerCoordinates[0];
            final float f2 = fArrMapPointerCoordinates[1];
            motionEvent.getEventTime();
            bridgeRootView.postDelayed(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda18
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.lambda$onCreate$3(rawWaylandShmServer, f, f2);
                }
            }, 12L);
            return true;
        }
        if (actionMasked == 2) {
            float f3 = getResources().getDisplayMetrics().density * 12.0f;
            if (!this.touchScrolling && (Math.abs(fArrMapPointerCoordinates[0] - this.touchDownX) > f3 || Math.abs(fArrMapPointerCoordinates[1] - this.touchDownY) > f3)) {
                this.touchScrolling = true;
                if (this.touchPointerPressed) {
                    rawWaylandShmServer.handleAndroidMotionEvent(3, fArrMapPointerCoordinates[0], fArrMapPointerCoordinates[1], motionEvent.getEventTime());
                    this.touchPointerPressed = false;
                }
            }
            if (this.touchScrolling) {
                float f4 = this.touchLastY - fArrMapPointerCoordinates[1];
                this.touchLastY = fArrMapPointerCoordinates[1];
                if (Math.abs(f4) >= 1.0f) {
                    rawWaylandShmServer.handleAndroidScrollEvent(fArrMapPointerCoordinates[0], fArrMapPointerCoordinates[1], f4 / 48.0f, motionEvent.getEventTime());
                }
            }
            return true;
        }
        if (actionMasked != 1) {
            if (actionMasked != 3) {
                return true;
            }
            if (this.touchPointerPressed) {
                rawWaylandShmServer.handleAndroidMotionEvent(3, fArrMapPointerCoordinates[0], fArrMapPointerCoordinates[1], motionEvent.getEventTime());
                this.touchPointerPressed = false;
            }
            this.touchScrolling = false;
            return true;
        }
        if (this.touchScrolling) {
            mainActivity = this;
        } else {
            final boolean zHasActivePopupGrab = rawWaylandShmServer.hasActivePopupGrab();
            final float f5 = fArrMapPointerCoordinates[0];
            final float f6 = fArrMapPointerCoordinates[1];
            motionEvent.getEventTime();
            Runnable runnable = new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda19
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.lambda$onCreate$4(rawWaylandShmServer, f5, f6, zHasActivePopupGrab, bridgeRootView);
                }
            };
            mainActivity = this;
            bridgeRootView.postDelayed(runnable, 80L);
        }
        mainActivity.touchScrolling = false;
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$3(RawWaylandShmServer rawWaylandShmServer, float f, float f2) {
        if (!this.touchScrolling && !this.touchPointerPressed) {
            this.touchPointerPressed = rawWaylandShmServer.handleAndroidMotionEvent(0, f, f2, SystemClock.uptimeMillis());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$4(RawWaylandShmServer rawWaylandShmServer, float f, float f2, boolean z, BridgeRootView bridgeRootView) {
        RawWaylandShmServer rawWaylandShmServer2;
        if (!this.touchPointerPressed) {
            rawWaylandShmServer2 = rawWaylandShmServer;
        } else {
            rawWaylandShmServer2 = rawWaylandShmServer;
            rawWaylandShmServer2.handleAndroidMotionEvent(1, f, f2, SystemClock.uptimeMillis());
            this.touchPointerPressed = false;
        }
        rawWaylandShmServer2.logRecentProtocol();
        if (!z && this.touchMayRequestTextInput) {
            requestWaylandTextInput(bridgeRootView);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$onCreate$5(ImageView imageView, View view, MotionEvent motionEvent) {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        int actionMasked = motionEvent.getActionMasked();
        if (rawWaylandShmServer != null && actionMasked == 8) {
            float[] fArrMapPointerCoordinates = mapPointerCoordinates(imageView, rawWaylandShmServer, motionEvent.getX(), motionEvent.getY());
            return rawWaylandShmServer.handleAndroidScrollEvent(fArrMapPointerCoordinates[0], fArrMapPointerCoordinates[1], motionEvent.getAxisValue(9), motionEvent.getEventTime());
        }
        if (rawWaylandShmServer != null && (actionMasked == 9 || actionMasked == 7 || actionMasked == 2)) {
            float[] fArrMapPointerCoordinates2 = mapPointerCoordinates(imageView, rawWaylandShmServer, motionEvent.getX(), motionEvent.getY());
            return rawWaylandShmServer.handleAndroidMotionEvent(2, fArrMapPointerCoordinates2[0], fArrMapPointerCoordinates2[1], motionEvent.getEventTime());
        }
        if (rawWaylandShmServer == null || actionMasked != 10) {
            return false;
        }
        return rawWaylandShmServer.handleAndroidPointerExit();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$onCreate$6(ImageView imageView, View view, MotionEvent motionEvent) {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        int actionMasked = motionEvent.getActionMasked();
        if (rawWaylandShmServer != null && (actionMasked == 9 || actionMasked == 7)) {
            float[] fArrMapPointerCoordinates = mapPointerCoordinates(imageView, rawWaylandShmServer, motionEvent.getX(), motionEvent.getY());
            return rawWaylandShmServer.handleAndroidMotionEvent(2, fArrMapPointerCoordinates[0], fArrMapPointerCoordinates[1], motionEvent.getEventTime());
        }
        if (rawWaylandShmServer == null || actionMasked != 10) {
            return false;
        }
        return rawWaylandShmServer.handleAndroidPointerExit();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$7(View view, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8) {
        final int i9 = i3 - i;
        final int i10 = i4 - i2;
        if (i9 == i7 - i5 && i10 == i8 - i6) {
            return;
        }
        if (this.pendingViewportResize != null) {
            view.removeCallbacks(this.pendingViewportResize);
        }
        this.pendingViewportResize = new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda20
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$onCreate$8(i9, i10);
            }
        };
        view.postDelayed(this.pendingViewportResize, 120L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$8(int i, int i2) {
        this.pendingViewportResize = null;
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        if (rawWaylandShmServer != null) {
            rawWaylandShmServer.requestResize(i, i2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$9() {
        if (this.suppressNextBackInvocation) {
            this.suppressNextBackInvocation = false;
            return;
        }
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        if (rawWaylandShmServer == null || !rawWaylandShmServer.sendPopupEscape()) {
            finish();
        }
    }

    private void requestWaylandTextInput(final BridgeRootView bridgeRootView) {
        this.waylandTextInputRequested = true;
        bridgeRootView.requestFocus();
        final InputMethodManager inputMethodManager = (InputMethodManager) getSystemService("input_method");
        bridgeRootView.post(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda22
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.lambda$requestWaylandTextInput$0(inputMethodManager, bridgeRootView);
            }
        });
    }

    static /* synthetic */ void lambda$requestWaylandTextInput$0(InputMethodManager inputMethodManager, BridgeRootView bridgeRootView) {
        inputMethodManager.restartInput(bridgeRootView);
        inputMethodManager.showSoftInput(bridgeRootView, 1);
    }

    private float[] mapPointerCoordinates(ImageView imageView, RawWaylandShmServer rawWaylandShmServer, float f, float f2) {
        return new float[]{(f - imageView.getLeft()) * (rawWaylandShmServer.outputWidth / Math.max(1, imageView.getWidth())), (f2 - imageView.getTop()) * (rawWaylandShmServer.outputHeight / Math.max(1, imageView.getHeight()))};
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setBridgeClipboardText(final String str) {
        this.pendingBridgeClipboardText = str;
        this.clipboardManager.setPrimaryClip(ClipData.newPlainText("Archphene Linux app", str));
        this.bridgeRootView.postDelayed(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda14
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$setBridgeClipboardText$0(str);
            }
        }, 1000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setBridgeClipboardText$0(String str) {
        if (str.equals(this.pendingBridgeClipboardText)) {
            this.pendingBridgeClipboardText = null;
        }
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        if (this.clipboardManager != null && this.clipboardListener != null) {
            this.clipboardManager.removePrimaryClipChangedListener(this.clipboardListener);
        }
        Process process = this.activeLinuxProcess;
        if (process != null) {
            process.destroy();
        }
        if (currentActivity.get() == this) {
            currentActivity.clear();
        }
        super.onDestroy();
    }

    @Override // android.app.Activity, android.content.ComponentCallbacks
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        final BridgeRootView bridgeRootView = this.bridgeRootView;
        if (bridgeRootView == null) {
            return;
        }
        bridgeRootView.post(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda10
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$onConfigurationChanged$0(bridgeRootView);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onConfigurationChanged$0(BridgeRootView bridgeRootView) {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        if (rawWaylandShmServer != null) {
            ImageView imageView = (ImageView) bridgeRootView.getChildAt(0);
            rawWaylandShmServer.requestResize(Math.max(320, imageView.getWidth()), Math.max(240, imageView.getHeight()));
        }
    }

    @Override // android.app.Activity
    public void onBackPressed() {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        if (rawWaylandShmServer != null && rawWaylandShmServer.sendPopupEscape()) {
            return;
        }
        super.onBackPressed();
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public boolean dispatchKeyEvent(KeyEvent keyEvent) {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        if (rawWaylandShmServer != null && keyEvent.getKeyCode() == 4) {
            if (keyEvent.getAction() != 0 || !rawWaylandShmServer.hasVisiblePopups()) {
                if (keyEvent.getAction() == 1 && this.popupBackInProgress) {
                    this.popupBackInProgress = false;
                    rawWaylandShmServer.handleAndroidKeyEvent(1, 111, keyEvent.getEventTime());
                    return true;
                }
            } else {
                this.popupBackInProgress = true;
                this.suppressNextBackInvocation = true;
                rawWaylandShmServer.handleAndroidKeyEvent(0, 111, keyEvent.getEventTime());
                return true;
            }
        }
        if (rawWaylandShmServer != null && rawWaylandShmServer.handleAndroidKeyEvent(keyEvent.getAction(), keyEvent.getKeyCode(), keyEvent.getEventTime())) {
            return true;
        }
        return super.dispatchKeyEvent(keyEvent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    final class BridgeRootView extends FrameLayout {
        BridgeRootView(Context context) {
            super(context);
        }

        @Override // android.view.View
        public boolean onCheckIsTextEditor() {
            return MainActivity.this.waylandTextInputRequested;
        }

        @Override // android.view.View
        public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
            if (!MainActivity.this.waylandTextInputRequested) {
                return null;
            }
            editorInfo.inputType = 524433;
            editorInfo.imeOptions = 33554433;
            RawWaylandShmServer rawWaylandShmServer = MainActivity.this.activeInteractiveServer;
            if (rawWaylandShmServer != null) {
                rawWaylandShmServer.noteAndroidInputConnectionCreated();
            }
            return new BaseInputConnection(this, true) { // from class: org.archphene.linux.mousepad.MainActivity.BridgeRootView.1
                @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
                public boolean commitText(CharSequence charSequence, int i) {
                    RawWaylandShmServer rawWaylandShmServer2 = MainActivity.this.activeInteractiveServer;
                    if (rawWaylandShmServer2 != null && rawWaylandShmServer2.handleAndroidImeCommitText(charSequence)) {
                        return true;
                    }
                    return super.commitText(charSequence, i);
                }

                @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
                public boolean deleteSurroundingText(int i, int i2) {
                    RawWaylandShmServer rawWaylandShmServer2 = MainActivity.this.activeInteractiveServer;
                    if (rawWaylandShmServer2 != null && rawWaylandShmServer2.handleAndroidImeDelete()) {
                        return true;
                    }
                    return super.deleteSurroundingText(i, i2);
                }

                @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
                public boolean sendKeyEvent(KeyEvent keyEvent) {
                    RawWaylandShmServer rawWaylandShmServer2 = MainActivity.this.activeInteractiveServer;
                    if (rawWaylandShmServer2 != null && rawWaylandShmServer2.handleAndroidKeyEvent(keyEvent.getAction(), keyEvent.getKeyCode(), keyEvent.getEventTime())) {
                        return true;
                    }
                    return super.sendKeyEvent(keyEvent);
                }
            };
        }
    }

    private int[] displayPixelSize() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        return new int[]{Math.max(320, Math.min(4096, displayMetrics.widthPixels)), Math.max(240, Math.min(4096, displayMetrics.heightPixels))};
    }

    private void putDisplaySizeEnv(Map<String, String> map) {
        int[] iArrDisplayPixelSize = displayPixelSize();
        map.put("ARCHPHENE_WIDTH", Integer.toString(iArrDisplayPixelSize[0]));
        map.put("ARCHPHENE_HEIGHT", Integer.toString(iArrDisplayPixelSize[1]));
        putQtDensityEnv(map);
    }

    private void putQtDensityEnv(Map<String, String> map) {
        map.put("QT_FONT_DPI", Integer.toString(Math.max(96, Math.min(384, Math.round(getResources().getDisplayMetrics().density * 96.0f)))));
    }

    private void writeReportArtifact(String str) {
        File file = new File(getFilesDir(), "mousepad-report.txt");
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            try {
                fileOutputStream.write(str.getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "Bridge report written to " + file.getAbsolutePath());
                fileOutputStream.close();
            } finally {
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not write bridge report", e);
        }
    }

    private static void logReportSummary(String str, String str2) {
        Log.i(TAG, str + "\n" + summarizeWindowReport(str2));
    }

    private static String summarizeWindowReport(String str) {
        StringBuilder sb = new StringBuilder();
        sb.append("Archphene Mousepad window bridge\n");
        appendSummaryLine(sb, str, "Raw Wayland parsed messages:");
        appendSummaryLine(sb, str, "Raw Wayland fd count:");
        appendSummaryLine(sb, str, "Raw Wayland dimensions:");
        appendSummaryLine(sb, str, "Raw Wayland committed:");
        appendSummaryLine(sb, str, "Raw Wayland bitmap ready:");
        appendSummaryLine(sb, str, "Evented Wayland parsed messages:");
        appendSummaryLine(sb, str, "Evented Wayland registry globals:");
        appendSummaryLine(sb, str, "Evented Wayland dimensions:");
        appendSummaryLine(sb, str, "Evented Wayland committed:");
        appendSummaryLine(sb, str, "Evented Wayland bitmap ready:");
        appendSummaryLine(sb, str, "XDG Wayland parsed messages:");
        appendSummaryLine(sb, str, "XDG Wayland configure sent:");
        appendSummaryLine(sb, str, "XDG Wayland configure acked:");
        appendSummaryLine(sb, str, "XDG Wayland frame callback done:");
        appendSummaryLine(sb, str, "XDG Wayland buffer released:");
        appendSummaryLine(sb, str, "XDG Wayland dimensions:");
        appendSummaryLine(sb, str, "XDG Wayland committed:");
        appendSummaryLine(sb, str, "XDG Wayland bitmap ready:");
        appendSummaryLine(sb, str, "Wayland API client exit code:");
        appendSummaryLine(sb, str, "Wayland API server accepted:");
        appendSummaryLine(sb, str, "Wayland API server sync callbacks:");
        appendSummaryLine(sb, str, "Wayland API server shm formats:");
        appendSummaryLine(sb, str, "Android Wayland API client exit code:");
        appendSummaryLine(sb, str, "Android Wayland API server accepted:");
        appendSummaryLine(sb, str, "Android Wayland API server sync callbacks:");
        appendSummaryLine(sb, str, "Android Wayland API server shm formats:");
        appendSummaryLine(sb, str, "Android Wayland API render exit code:");
        appendSummaryLine(sb, str, "Android Wayland API render committed:");
        appendSummaryLine(sb, str, "Android Wayland API render bitmap ready:");
        appendSummaryLine(sb, str, "Android Wayland API xdg exit code:");
        appendSummaryLine(sb, str, "Android Wayland API xdg configure acked:");
        appendSummaryLine(sb, str, "Android Wayland API xdg output done:");
        appendSummaryLine(sb, str, "Android Wayland API xdg seat capabilities sent:");
        appendSummaryLine(sb, str, "Android Wayland API xdg pointer requested:");
        appendSummaryLine(sb, str, "Android Wayland API xdg pointer events sent:");
        appendSummaryLine(sb, str, "Android Wayland API interactive pointer exit code:");
        appendSummaryLine(sb, str, "Android Wayland API interactive pointer timed out:");
        appendSummaryLine(sb, str, "Android Wayland API interactive pointer android events:");
        appendSummaryLine(sb, str, "Android Wayland API interactive pointer native repaint:");
        appendSummaryLine(sb, str, "Android Wayland API interactive keyboard android events:");
        appendSummaryLine(sb, str, "Android Wayland API interactive keyboard native repaint:");
        appendSummaryLine(sb, str, "Android Wayland API interactive keyboard modifier events:");
        appendSummaryLine(sb, str, "Android Wayland API interactive keyboard repeat info sent:");
        appendSummaryLine(sb, str, "Android Wayland API interactive IME input connections:");
        appendSummaryLine(sb, str, "Android Wayland API interactive IME commit events:");
        appendSummaryLine(sb, str, "Android Wayland API interactive IME synthesized key events:");
        appendSummaryLine(sb, str, "Android Wayland API interactive keyboard last modifiers:");
        appendSummaryLine(sb, str, "Android Wayland API interactive pointer bitmap ready:");
        appendSummaryLine(sb, str, "Android Wayland API xdg frame callback done:");
        appendSummaryLine(sb, str, "Android Wayland API xdg buffer released:");
        appendSummaryLine(sb, str, "Android Wayland API xdg post-commit sync done:");
        appendSummaryLine(sb, str, "Android Wayland API xdg cleanup sync done:");
        appendSummaryLine(sb, str, "Android Wayland API xdg destroy requests:");
        appendSummaryLine(sb, str, "Android Wayland API xdg bitmap ready:");
        appendSummaryLine(sb, str, "glibc loader --list mousepad");
        return sb.toString();
    }

    private static void appendSummaryLine(StringBuilder sb, String str, String str2) {
        int iIndexOf = str.indexOf(str2);
        if (iIndexOf < 0) {
            return;
        }
        int iIndexOf2 = str.indexOf(10, iIndexOf);
        if (iIndexOf2 < 0) {
            iIndexOf2 = str.length();
        }
        sb.append((CharSequence) str, iIndexOf, iIndexOf2).append('\n');
    }

    private String renderReport(ImageView imageView) {
        File file = new File(getApplicationInfo().nativeLibraryDir);
        File file2 = new File(getFilesDir(), "linux-runtime/lib");
        String strPrepareLinuxRuntime = prepareLinuxRuntime(file2);
        File file3 = new File(file, KCALC_PAYLOAD);
        File file4 = new File(file, WAYLAND_PROBE_PAYLOAD);
        File file5 = new File(file, WAYLAND_JNI_PAYLOAD);
        File file6 = new File(file, FRAME_CLIENT_PAYLOAD);
        File file7 = new File(file, SHM_FRAME_CLIENT_PAYLOAD);
        File file8 = new File(file, WAYLAND_SHM_CLIENT_PAYLOAD);
        File file9 = new File(file, WAYLAND_EVENTED_CLIENT_PAYLOAD);
        File file10 = new File(file, WAYLAND_XDG_CLIENT_PAYLOAD);
        File file11 = new File(file, WAYLAND_API_CLIENT_PAYLOAD);
        File file12 = new File(file, WAYLAND_ANDROID_API_CLIENT_PAYLOAD);
        File file13 = new File(file, WAYLAND_ANDROID_API_RENDER_CLIENT_PAYLOAD);
        File file14 = new File(file, WAYLAND_ANDROID_API_XDG_CLIENT_PAYLOAD);
        File file15 = new File(file, WAYLAND_ANDROID_CLIENT_LIB);
        File file16 = new File(file, GLIBC_LOADER);
        File file17 = new File(file, SYSCALL_PROBE);
        File file18 = new File(file2, GLIBC_LIBC);
        File file19 = new File(file2, "libm.so.6");
        File file20 = new File(file2, "libQt6Core.so.6");
        StringBuilder sb = new StringBuilder();
        sb.append("Mousepad Archphene launcher proof\n\n");
        sb.append("Android package: ").append(getPackageName()).append("\n");
        sb.append("App label: Mousepad\n");
        sb.append("UID: ").append(Process.myUid()).append("\n");
        sb.append("nativeLibraryDir: ").append(file.getAbsolutePath()).append("\n");
        sb.append("linuxRuntimeLibDir: ").append(file2.getAbsolutePath()).append("\n");
        sb.append(strPrepareLinuxRuntime).append("\n\n");
        sb.append("Arch package metadata (.PKGINFO)\n");
        sb.append(readAsset("mousepad.PKGINFO", 4096)).append("\n");
        appendFileState(sb, "Native-dir real Arch usr/bin/mousepad ELF entrypoint", file3);
        appendFileState(sb, "Native-dir Wayland socket probe ELF entrypoint", file4);
        appendFileState(sb, "APK-extracted Wayland JNI socket binder", file5);
        appendFileState(sb, "Native-dir Linux frame client", file6);
        appendFileState(sb, "Native-dir Linux wl_shm-style frame client", file7);
        appendFileState(sb, "Native-dir raw Wayland wl_shm client", file8);
        appendFileState(sb, "Native-dir evented Wayland wl_shm client", file9);
        appendFileState(sb, "Native-dir xdg-shell Wayland client", file10);
        appendFileState(sb, "Native-dir libwayland-client API probe", file11);
        appendFileState(sb, "Native-dir Android Wayland API probe", file12);
        appendFileState(sb, "Native-dir Android Wayland API render probe", file13);
        appendFileState(sb, "Native-dir Android Wayland API xdg probe", file14);
        appendFileState(sb, "Native-dir Android Wayland client shim", file15);
        sb.append("Wayland JNI load error: ").append(JNI_LOAD_ERROR).append("\n\n");
        appendFileState(sb, "Native-dir glibc loader entrypoint", file16);
        appendFileState(sb, "Native-dir static syscall probe", file17);
        appendFileState(sb, "App-private packaged glibc libc", file18);
        sb.append(reportPatchBytes(file16, file18));
        sb.append("\n");
        sb.append(runStracedLoaderList(file16, file2, file, file18));
        sb.append("\n");
        sb.append(runRenderedFrameBridge(file6, imageView));
        sb.append("\n");
        sb.append(runShmFrameBridge(file7, imageView));
        sb.append("\n");
        sb.append(runRawWaylandShmBridge(file8, imageView));
        sb.append("\n");
        sb.append(runEventedWaylandShmBridge(file9, imageView));
        sb.append("\n");
        sb.append(runXdgWaylandShmBridge(file10, imageView));
        sb.append("\n");
        sb.append(runWaylandApiClientProbe(file11));
        sb.append("\n");
        sb.append(runAndroidWaylandApiClientProbe(file12));
        sb.append("\n");
        sb.append(runAndroidWaylandApiRenderClientProbe(file13, imageView));
        sb.append("\n");
        sb.append(runAndroidWaylandApiXdgClientProbe(file14, imageView));
        sb.append("\n");
        sb.append(runFilesystemWaylandSocketProbe(file4));
        sb.append("\n");
        sb.append(runWaylandSocketProbe(file4));
        sb.append("\n");
        sb.append(runNamed("Direct mousepad process launch", new String[]{file3.getAbsolutePath()}));
        sb.append("\n");
        sb.append(runNamed("glibc loader --verify mousepad", new String[]{file16.getAbsolutePath(), "--verify", file3.getAbsolutePath()}));
        sb.append("\n");
        sb.append(runNamed("glibc loader --list libc", new String[]{file16.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file.getAbsolutePath(), file18.getAbsolutePath()}));
        sb.append("\n");
        sb.append(runNamed("glibc loader --list libm", new String[]{file16.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file.getAbsolutePath(), file19.getAbsolutePath()}));
        sb.append("\n");
        sb.append(runNamed("glibc loader --list QtCore", new String[]{file16.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file.getAbsolutePath(), file20.getAbsolutePath()}));
        sb.append("\n");
        sb.append(runNamed("glibc loader --list mousepad", new String[]{file16.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file.getAbsolutePath(), file3.getAbsolutePath()}));
        sb.append("\n");
        sb.append(runSyscallProbeMatrix(file17));
        sb.append("\n");
        sb.append("Current expected result: this APK proves Mousepad can be installed and launched as a normal Android app identity, with the real Arch Mousepad ELF in Android nativeLibraryDir and the Arch runtime closure extracted into app-private storage. The remaining pre-GUI blocker is glibc compatibility with the app-spawned Android syscall profile; after that the bridge needs a real Wayland compositor protocol implementation for Qt/KF6 windows.\n");
        return sb.toString();
    }

    private String runStracedLoaderList(File file, File file2, File file3, File file4) {
        File file5 = new File(getCacheDir(), "ld-list-libc.strace");
        if (file5.exists()) {
            file5.delete();
        }
        return "App-spawned strace for glibc loader --list libc\n\n" + runNamed("strace ld.so --list libc", new String[]{"/system/bin/strace", "-f", "-s", "160", "-o", file5.getAbsolutePath(), file.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file3.getAbsolutePath(), file4.getAbsolutePath()}) + "\nTrace tail:\n" + readTail(file5, 12000) + "\n";
    }

    private static String readTail(File file, int i) {
        if (!file.exists()) {
            return "trace file does not exist: " + file.getAbsolutePath() + "\n";
        }
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
            try {
                long length = randomAccessFile.length();
                long jMax = Math.max(0L, length - ((long) i));
                randomAccessFile.seek(jMax);
                byte[] bArr = new byte[(int) (length - jMax)];
                String str = (jMax > 0 ? "... trace truncated to last " + i + " bytes ...\n" : "") + new String(bArr, 0, Math.max(0, randomAccessFile.read(bArr)), StandardCharsets.UTF_8);
                randomAccessFile.close();
                return str;
            } finally {
            }
        } catch (Exception e) {
            return "trace read failed: " + String.valueOf(e) + "\n";
        }
    }

    private String runRenderedFrameBridge(File file, ImageView imageView) {
        StringBuilder sb = new StringBuilder();
        sb.append("Linux-rendered frame bridge proof\n\n");
        appendFileState(sb, "Frame client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-frame-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale frame socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        FrameBridgeServer frameBridgeServer = new FrameBridgeServer(file3);
        Thread thread = new Thread(frameBridgeServer, "archphene-frame-server");
        thread.start();
        try {
            if (!frameBridgeServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Frame bridge server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!frameBridgeServer.listening) {
                sb.append("Frame bridge server failed before listen: ").append(frameBridgeServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-frame-0");
            sb.append(runNamedWithEnv("Linux payload sends RGBA frame to Android UI bridge", new String[]{file.getAbsolutePath()}, map));
            try {
                thread.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining frame bridge server: ").append(e).append("\n");
            }
            if (frameBridgeServer.bitmap != null) {
                imageView.setImageBitmap(frameBridgeServer.bitmap);
                imageView.setMinimumHeight(Math.max(240, frameBridgeServer.height));
            }
            sb.append("Frame bridge server accepted: ").append(frameBridgeServer.accepted).append("\n");
            sb.append("Frame bridge server header: ").append(frameBridgeServer.header).append("\n");
            sb.append("Frame bridge dimensions: ").append(frameBridgeServer.width).append("x").append(frameBridgeServer.height).append("\n");
            sb.append("Frame bridge bytes: ").append(frameBridgeServer.bytesRead).append("\n");
            sb.append("Frame bridge bitmap ready: ").append(frameBridgeServer.bitmap != null).append("\n");
            sb.append("Frame bridge error: ").append(frameBridgeServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for frame bridge server: ").append(e2).append("\n");
            return sb.toString();
        }
    }

    private String runShmFrameBridge(File file, ImageView imageView) {
        StringBuilder sb = new StringBuilder();
        sb.append("Linux wl_shm-style memfd frame bridge proof\n\n");
        appendFileState(sb, "wl_shm-style frame client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-shm-frame-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale shm frame socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        ShmFrameBridgeServer shmFrameBridgeServer = new ShmFrameBridgeServer(file3);
        Thread thread = new Thread(shmFrameBridgeServer, "archphene-shm-frame-server");
        thread.start();
        try {
            if (!shmFrameBridgeServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Shm frame bridge server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!shmFrameBridgeServer.listening) {
                sb.append("Shm frame bridge server failed before listen: ").append(shmFrameBridgeServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-shm-frame-0");
            sb.append(runNamedWithEnv("Linux payload sends memfd-backed wl_shm-style frame", new String[]{file.getAbsolutePath()}, map));
            try {
                thread.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining shm frame bridge server: ").append(e).append("\n");
            }
            if (shmFrameBridgeServer.bitmap != null) {
                imageView.setImageBitmap(shmFrameBridgeServer.bitmap);
                imageView.setMinimumHeight(Math.max(240, shmFrameBridgeServer.height));
            }
            sb.append("Shm frame bridge server accepted: ").append(shmFrameBridgeServer.accepted).append("\n");
            sb.append("Shm frame bridge header: ").append(shmFrameBridgeServer.header).append("\n");
            sb.append("Shm frame bridge fd count: ").append(shmFrameBridgeServer.fdCount).append("\n");
            sb.append("Shm frame bridge dimensions: ").append(shmFrameBridgeServer.width).append("x").append(shmFrameBridgeServer.height).append(" stride=").append(shmFrameBridgeServer.stride).append("\n");
            sb.append("Shm frame bridge bytes: ").append(shmFrameBridgeServer.bytesRead).append("\n");
            sb.append("Shm frame bridge bitmap ready: ").append(shmFrameBridgeServer.bitmap != null).append("\n");
            sb.append("Shm frame bridge error: ").append(shmFrameBridgeServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for shm frame bridge server: ").append(e2).append("\n");
            return sb.toString();
        }
    }

    private String runRawWaylandShmBridge(File file, ImageView imageView) {
        StringBuilder sb = new StringBuilder();
        sb.append("Raw Wayland wl_shm compositor bridge proof\n\n");
        appendFileState(sb, "Raw Wayland wl_shm client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-shm-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale raw Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-raw-wayland-shm-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Raw Wayland server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!rawWaylandShmServer.listening) {
                sb.append("Raw Wayland server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-wayland-shm-0");
            sb.append(runNamedWithEnv("Linux payload sends raw Wayland wl_shm commit", new String[]{file.getAbsolutePath()}, map));
            try {
                thread.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining raw Wayland server: ").append(e).append("\n");
            }
            if (rawWaylandShmServer.bitmap != null) {
                imageView.setImageBitmap(rawWaylandShmServer.bitmap);
                imageView.setMinimumHeight(Math.max(240, rawWaylandShmServer.height));
            }
            sb.append("Raw Wayland server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
            sb.append("Raw Wayland parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
            sb.append("Raw Wayland fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
            sb.append("Raw Wayland dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
            sb.append("Raw Wayland bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
            sb.append("Raw Wayland committed: ").append(rawWaylandShmServer.committed).append("\n");
            sb.append("Raw Wayland bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
            sb.append("Raw Wayland log:\n").append(rawWaylandShmServer.log);
            sb.append("Raw Wayland error: ").append(rawWaylandShmServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for raw Wayland server: ").append(e2).append("\n");
            return sb.toString();
        }
    }

    private String runEventedWaylandShmBridge(File file, ImageView imageView) {
        StringBuilder sb = new StringBuilder();
        sb.append("Evented Wayland registry/compositor bridge proof\n\n");
        appendFileState(sb, "Evented Wayland wl_shm client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-evented-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale evented Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-evented-wayland-shm-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Evented Wayland server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!rawWaylandShmServer.listening) {
                sb.append("Evented Wayland server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-wayland-evented-0");
            sb.append(runNamedWithEnv("Linux payload performs Wayland registry roundtrip then wl_shm commit", new String[]{file.getAbsolutePath()}, map));
            try {
                thread.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining evented Wayland server: ").append(e).append("\n");
            }
            if (rawWaylandShmServer.bitmap != null) {
                imageView.setImageBitmap(rawWaylandShmServer.bitmap);
                imageView.setMinimumHeight(Math.max(240, rawWaylandShmServer.height));
            }
            sb.append("Evented Wayland server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
            sb.append("Evented Wayland parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
            sb.append("Evented Wayland registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
            sb.append("Evented Wayland callback done sent: ").append(rawWaylandShmServer.callbackDoneSent).append("\n");
            sb.append("Evented Wayland shm formats sent: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
            sb.append("Evented Wayland fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
            sb.append("Evented Wayland dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
            sb.append("Evented Wayland bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
            sb.append("Evented Wayland committed: ").append(rawWaylandShmServer.committed).append("\n");
            sb.append("Evented Wayland bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
            sb.append("Evented Wayland log:\n").append(rawWaylandShmServer.log);
            sb.append("Evented Wayland error: ").append(rawWaylandShmServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for evented Wayland server: ").append(e2).append("\n");
            return sb.toString();
        }
    }

    private String runXdgWaylandShmBridge(File file, ImageView imageView) {
        StringBuilder sb = new StringBuilder();
        sb.append("XDG Wayland toplevel configure bridge proof\n\n");
        appendFileState(sb, "XDG Wayland client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-xdg-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale xdg Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        int[] iArrDisplayPixelSize = displayPixelSize();
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true, iArrDisplayPixelSize[0], iArrDisplayPixelSize[1]);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-xdg-wayland-shm-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("XDG Wayland server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!rawWaylandShmServer.listening) {
                sb.append("XDG Wayland server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-wayland-xdg-0");
            sb.append(runNamedWithEnv("Linux payload performs xdg-shell configure/ack then wl_shm commit", new String[]{file.getAbsolutePath()}, map));
            try {
                thread.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining xdg Wayland server: ").append(e).append("\n");
            }
            if (rawWaylandShmServer.bitmap != null) {
                imageView.setImageBitmap(rawWaylandShmServer.bitmap);
                imageView.setMinimumHeight(Math.max(240, rawWaylandShmServer.height));
            }
            sb.append("XDG Wayland server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
            sb.append("XDG Wayland parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
            sb.append("XDG Wayland registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
            sb.append("XDG Wayland callback done sent: ").append(rawWaylandShmServer.callbackDoneSent).append("\n");
            sb.append("XDG Wayland shm formats sent: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
            sb.append("XDG Wayland configure sent: ").append(rawWaylandShmServer.xdgConfigureSent).append(" serial=").append(rawWaylandShmServer.xdgConfigureSerial).append(" configured=").append(rawWaylandShmServer.configureWidth).append("x").append(rawWaylandShmServer.configureHeight).append("\n");
            sb.append("XDG Wayland configure acked: ").append(rawWaylandShmServer.xdgConfigureAcked).append("\n");
            sb.append("XDG Wayland frame callback done: ").append(rawWaylandShmServer.frameCallbackDoneSent).append("\n");
            sb.append("XDG Wayland buffer released: ").append(rawWaylandShmServer.bufferReleaseSent).append("\n");
            sb.append("XDG Wayland fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
            sb.append("XDG Wayland dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
            sb.append("XDG Wayland bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
            sb.append("XDG Wayland committed: ").append(rawWaylandShmServer.committed).append("\n");
            sb.append("XDG Wayland bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
            sb.append("XDG Wayland log:\n").append(rawWaylandShmServer.log);
            sb.append("XDG Wayland error: ").append(rawWaylandShmServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for xdg Wayland server: ").append(e2).append("\n");
            return sb.toString();
        }
    }

    private String runWaylandApiClientProbe(File file) {
        Result result;
        StringBuilder sb = new StringBuilder();
        sb.append("Real libwayland-client API bridge probe\n\n");
        appendFileState(sb, "libwayland-client API client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-api-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale API Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true, 2);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-api-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Wayland API server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!rawWaylandShmServer.listening) {
                sb.append("Wayland API server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-wayland-api-0");
            try {
                Result resultRun = run(new String[]{file.getAbsolutePath()}, map);
                sb.append("libwayland-client API payload performs registry roundtrip and binds globals\n\n").append(formatCommandResult(new String[]{file.getAbsolutePath()}, resultRun));
                result = resultRun;
            } catch (Exception e) {
                result = new Result(-127, false, "", "", e.toString());
                sb.append("libwayland-client API payload failed:\n").append(e).append("\n");
            }
            try {
                thread.join(3000L);
            } catch (InterruptedException e2) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining Wayland API server: ").append(e2).append("\n");
            }
            sb.append("Wayland API client exit code: ").append(result.exitCode).append("\n");
            sb.append("Wayland API server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
            sb.append("Wayland API server parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
            sb.append("Wayland API server registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
            sb.append("Wayland API server sync callbacks: ").append(rawWaylandShmServer.syncCallbackCount).append("\n");
            sb.append("Wayland API server shm formats: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
            sb.append("Wayland API server completed: ").append(rawWaylandShmServer.committed).append("\n");
            sb.append("Wayland API server log:\n").append(rawWaylandShmServer.log);
            sb.append("Wayland API server error: ").append(rawWaylandShmServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e3) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for Wayland API server: ").append(e3).append("\n");
            return sb.toString();
        }
    }

    private String runAndroidWaylandApiClientProbe(File file) {
        Result result;
        StringBuilder sb = new StringBuilder();
        sb.append("Android-built Wayland-client API bridge probe\n\n");
        appendFileState(sb, "Android Wayland API client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-android-api-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale Android API Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true, 2);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-android-api-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Android Wayland API server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!rawWaylandShmServer.listening) {
                sb.append("Android Wayland API server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-0");
            try {
                Result resultRun = run(new String[]{file.getAbsolutePath()}, map);
                sb.append("Android Wayland API payload performs registry roundtrip and binds globals\n\n").append(formatCommandResult(new String[]{file.getAbsolutePath()}, resultRun));
                result = resultRun;
            } catch (Exception e) {
                result = new Result(-127, false, "", "", e.toString());
                sb.append("Android Wayland API payload failed:\n").append(e).append("\n");
            }
            try {
                thread.join(3000L);
            } catch (InterruptedException e2) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining Android Wayland API server: ").append(e2).append("\n");
            }
            sb.append("Android Wayland API client exit code: ").append(result.exitCode).append("\n");
            sb.append("Android Wayland API server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
            sb.append("Android Wayland API server parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
            sb.append("Android Wayland API server registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
            sb.append("Android Wayland API server sync callbacks: ").append(rawWaylandShmServer.syncCallbackCount).append("\n");
            sb.append("Android Wayland API server shm formats: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
            sb.append("Android Wayland API server completed: ").append(rawWaylandShmServer.committed).append("\n");
            sb.append("Android Wayland API server log:\n").append(rawWaylandShmServer.log);
            sb.append("Android Wayland API server error: ").append(rawWaylandShmServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e3) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for Android Wayland API server: ").append(e3).append("\n");
            return sb.toString();
        }
    }

    private String runAndroidWaylandApiRenderClientProbe(File file, ImageView imageView) {
        Result result;
        StringBuilder sb = new StringBuilder();
        sb.append("Android-built Wayland-client API render bridge proof\n\n");
        appendFileState(sb, "Android Wayland API render client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-android-api-render-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale Android API render Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-android-api-render-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Android Wayland API render server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!rawWaylandShmServer.listening) {
                sb.append("Android Wayland API render server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-render-0");
            try {
                Result resultRun = run(new String[]{file.getAbsolutePath()}, map);
                sb.append("Android Wayland API payload creates wl_shm buffer and commits wl_surface\n\n").append(formatCommandResult(new String[]{file.getAbsolutePath()}, resultRun));
                result = resultRun;
            } catch (Exception e) {
                result = new Result(-127, false, "", "", e.toString());
                sb.append("Android Wayland API render payload failed:\n").append(e).append("\n");
            }
            try {
                thread.join(3000L);
            } catch (InterruptedException e2) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining Android Wayland API render server: ").append(e2).append("\n");
            }
            if (rawWaylandShmServer.bitmap != null) {
                imageView.setImageBitmap(rawWaylandShmServer.bitmap);
                imageView.setMinimumHeight(Math.max(240, rawWaylandShmServer.height));
            }
            sb.append("Android Wayland API render exit code: ").append(result.exitCode).append("\n");
            sb.append("Android Wayland API render accepted: ").append(rawWaylandShmServer.accepted).append("\n");
            sb.append("Android Wayland API render parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
            sb.append("Android Wayland API render registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
            sb.append("Android Wayland API render sync callbacks: ").append(rawWaylandShmServer.syncCallbackCount).append("\n");
            sb.append("Android Wayland API render shm formats: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
            sb.append("Android Wayland API render fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
            sb.append("Android Wayland API render dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
            sb.append("Android Wayland API render bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
            sb.append("Android Wayland API render committed: ").append(rawWaylandShmServer.committed).append("\n");
            sb.append("Android Wayland API render bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
            sb.append("Android Wayland API render log:\n").append(rawWaylandShmServer.log);
            sb.append("Android Wayland API render error: ").append(rawWaylandShmServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e3) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for Android Wayland API render server: ").append(e3).append("\n");
            return sb.toString();
        }
    }

    private String runAndroidWaylandApiXdgClientProbe(File file, ImageView imageView) {
        Result result;
        StringBuilder sb = new StringBuilder();
        sb.append("Android-built Wayland-client API xdg-shell bridge proof\n\n");
        appendFileState(sb, "Android Wayland API xdg client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-android-api-xdg-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale Android API xdg Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        int[] iArrDisplayPixelSize = displayPixelSize();
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true, iArrDisplayPixelSize[0], iArrDisplayPixelSize[1], true);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-android-api-xdg-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Android Wayland API xdg server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!rawWaylandShmServer.listening) {
                sb.append("Android Wayland API xdg server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-xdg-0");
            try {
                Result resultRun = run(new String[]{file.getAbsolutePath()}, map);
                sb.append("Android Wayland API payload performs xdg configure/ack and commits wl_shm buffer\n\n").append(formatCommandResult(new String[]{file.getAbsolutePath()}, resultRun));
                result = resultRun;
            } catch (Exception e) {
                Result result2 = new Result(-127, false, "", "", e.toString());
                sb.append("Android Wayland API xdg payload failed:\n").append(e).append("\n");
                result = result2;
            }
            try {
                thread.join(3000L);
            } catch (InterruptedException e2) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining Android Wayland API xdg server: ").append(e2).append("\n");
            }
            if (rawWaylandShmServer.bitmap != null) {
                imageView.setImageBitmap(rawWaylandShmServer.bitmap);
                imageView.setMinimumHeight(Math.max(240, rawWaylandShmServer.height));
            }
            sb.append("Android Wayland API xdg exit code: ").append(result.exitCode).append("\n");
            sb.append("Android Wayland API xdg accepted: ").append(rawWaylandShmServer.accepted).append("\n");
            sb.append("Android Wayland API xdg parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
            sb.append("Android Wayland API xdg registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
            sb.append("Android Wayland API xdg sync callbacks: ").append(rawWaylandShmServer.syncCallbackCount).append("\n");
            sb.append("Android Wayland API xdg shm formats: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
            sb.append("Android Wayland API xdg output done: ").append(rawWaylandShmServer.outputDoneSent).append("\n");
            sb.append("Android Wayland API xdg seat capabilities sent: ").append(rawWaylandShmServer.seatCapabilitiesSent).append("\n");
            sb.append("Android Wayland API xdg pointer requested: ").append(rawWaylandShmServer.pointerRequested).append("\n");
            sb.append("Android Wayland API xdg pointer events sent: ").append(rawWaylandShmServer.pointerEventsSent).append("\n");
            sb.append("Android Wayland API xdg configure sent: ").append(rawWaylandShmServer.xdgConfigureSent).append(" serial=").append(rawWaylandShmServer.xdgConfigureSerial).append(" configured=").append(rawWaylandShmServer.configureWidth).append("x").append(rawWaylandShmServer.configureHeight).append("\n");
            sb.append("Android Wayland API xdg configure acked: ").append(rawWaylandShmServer.xdgConfigureAcked).append("\n");
            sb.append("Android Wayland API xdg frame callback done: ").append(rawWaylandShmServer.frameCallbackDoneSent).append("\n");
            sb.append("Android Wayland API xdg buffer released: ").append(rawWaylandShmServer.bufferReleaseSent).append("\n");
            sb.append("Android Wayland API xdg post-commit sync done: ").append(rawWaylandShmServer.postCommitSyncDone).append("\n");
            sb.append("Android Wayland API xdg cleanup sync done: ").append(rawWaylandShmServer.cleanupSyncDone).append("\n");
            sb.append("Android Wayland API xdg destroy requests: ").append(rawWaylandShmServer.destroyRequestCount).append("\n");
            sb.append("Android Wayland API xdg fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
            sb.append("Android Wayland API xdg dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
            sb.append("Android Wayland API xdg bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
            sb.append("Android Wayland API xdg committed: ").append(rawWaylandShmServer.committed).append("\n");
            sb.append("Android Wayland API xdg bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
            sb.append("Android Wayland API xdg log:\n").append(rawWaylandShmServer.log);
            sb.append("Android Wayland API xdg error: ").append(rawWaylandShmServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e3) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for Android Wayland API xdg server: ").append(e3).append("\n");
            return sb.toString();
        }
    }

    private void startGlibcRuntimeProbe() {
        new Thread(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda13
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$startGlibcRuntimeProbe$0();
            }
        }, "archphene-glibc-runtime-probe").start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startGlibcRuntimeProbe$0() {
        File file = new File(getApplicationInfo().nativeLibraryDir);
        File file2 = new File(getFilesDir(), "linux-runtime/lib");
        File file3 = new File(file, GLIBC_LOADER);
        File file4 = new File(file2, GLIBC_LIBC);
        File file5 = new File(file, KCALC_PAYLOAD);
        StringBuilder sb = new StringBuilder();
        sb.append("Archphene source-built glibc Android app-domain probe\n\n");
        sb.append(prepareLinuxRuntime(file2)).append("\n\n");
        HashMap map = new HashMap();
        map.put("LD_LIBRARY_PATH", file2.getAbsolutePath() + ":" + file.getAbsolutePath());
        map.put("LD_DEBUG", "files,libs,reloc");
        sb.append(runNamedWithEnv("Source-built loader --list libc", new String[]{file3.getAbsolutePath(), "--library-path", map.get("LD_LIBRARY_PATH"), "--list", file4.getAbsolutePath()}, map));
        map.remove("LD_DEBUG");
        map.put("LD_DEBUG", "libs");
        map.put("LD_WARN", "1");
        sb.append("\n");
        sb.append(runNamedWithEnv("Source-built loader --list mousepad", new String[]{file3.getAbsolutePath(), "--library-path", map.get("LD_LIBRARY_PATH"), "--list", file5.getAbsolutePath()}, map));
        map.remove("LD_DEBUG");
        map.remove("LD_WARN");
        map.put("QT_QPA_PLATFORM", "wayland");
        map.put("QT_QPA_PLATFORM_PLUGIN_PATH", file2.getAbsolutePath());
        sb.append("\n");
        sb.append(runNamedWithEnv("Source-built loader direct Mousepad startup", new String[]{file3.getAbsolutePath(), "--library-path", map.get("LD_LIBRARY_PATH"), file5.getAbsolutePath()}, map));
        File file6 = new File(getCacheDir(), "mousepad-startup.strace");
        if (file6.exists()) {
            file6.delete();
        }
        sb.append("\n");
        sb.append(runNamedWithEnv("Straced source-built loader direct Mousepad startup", new String[]{"/system/bin/strace", "-f", "-s", "160", "-o", file6.getAbsolutePath(), file3.getAbsolutePath(), "--library-path", map.get("LD_LIBRARY_PATH"), file5.getAbsolutePath()}, map));
        sb.append("\nMousepad startup trace tail:\n");
        sb.append(readTail(file6, 24000));
        File file7 = new File(getFilesDir(), "glibc-runtime-probe.txt");
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file7);
            try {
                fileOutputStream.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "glibc runtime probe written to " + file7.getAbsolutePath());
                fileOutputStream.close();
            } finally {
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not write glibc runtime probe", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: startInteractivePointerProbe, reason: merged with bridge method [inline-methods] */
    public void lambda$onCreate$10(final ImageView imageView, final String str) {
        final int iMax = Math.max(320, imageView.getWidth());
        final int iMax2 = Math.max(240, imageView.getHeight());
        Log.i(TAG, "Wayland viewport " + iMax + "x" + iMax2);
        new File(getApplicationInfo().nativeLibraryDir);
        new Thread(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda11
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$startInteractivePointerProbe$0(imageView, iMax, iMax2, str);
            }
        }, "archphene-wayland-interactive-pointer-probe").start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startInteractivePointerProbe$0(ImageView imageView, int i, int i2, String str) {
        Log.i(TAG, "Interactive real Mousepad Wayland launch is ready");
        String str2 = str + "\n\n" + runAndroidWaylandApiXdgInteractivePointerProbe(imageView, i, i2);
        writeReportArtifact(str2);
        logReportSummary("Interactive bridge report", str2);
    }

    /* JADX WARN: Removed duplicated region for block: B:58:0x02b2  */
    /* JADX WARN: Removed duplicated region for block: B:65:0x02d1  */
    /* JADX WARN: Removed duplicated region for block: B:68:0x0439  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.lang.String runAndroidWaylandApiXdgInteractivePointerProbe(final android.widget.ImageView r18, int r19, int r20) {
        /*
            Method dump skipped, instruction units count: 1177
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: org.archphene.linux.mousepad.MainActivity.runAndroidWaylandApiXdgInteractivePointerProbe(android.widget.ImageView, int, int):java.lang.String");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$runAndroidWaylandApiXdgInteractivePointerProbe$0(final RawWaylandShmServer rawWaylandShmServer, final ImageView imageView) {
        runOnUiThread(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda21
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.lambda$runAndroidWaylandApiXdgInteractivePointerProbe$1(rawWaylandShmServer, imageView);
            }
        });
    }

    static /* synthetic */ void lambda$runAndroidWaylandApiXdgInteractivePointerProbe$1(RawWaylandShmServer rawWaylandShmServer, ImageView imageView) {
        Bitmap bitmap = rawWaylandShmServer.bitmap;
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        }
    }

    static /* synthetic */ void lambda$runAndroidWaylandApiXdgInteractivePointerProbe$2(ImageView imageView, RawWaylandShmServer rawWaylandShmServer) {
        imageView.setImageBitmap(rawWaylandShmServer.bitmap);
        imageView.setMinimumHeight(Math.max(240, rawWaylandShmServer.height));
    }

    private String runFilesystemWaylandSocketProbe(File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("Wayland filesystem socket JNI probe\n\n");
        if (!JNI_LOAD_ERROR.isEmpty()) {
            sb.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return sb.toString();
        }
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "wayland-0");
        if (file3.exists() && !file3.delete()) {
            sb.append("Could not remove stale socket path: ").append(file3.getAbsolutePath()).append("\n");
            return sb.toString();
        }
        FilesystemBridgeServer filesystemBridgeServer = new FilesystemBridgeServer(file3);
        Thread thread = new Thread(filesystemBridgeServer, "archphene-wayland-filesystem-server");
        thread.start();
        try {
            if (!filesystemBridgeServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Filesystem bridge server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!filesystemBridgeServer.listening) {
                sb.append("Filesystem bridge server failed before listen: ").append(filesystemBridgeServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", "wayland-0");
            sb.append(runNamedWithEnv("Linux payload connects to JNI-owned filesystem wayland-0 socket", new String[]{file.getAbsolutePath()}, map));
            try {
                thread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining filesystem bridge server: ").append(e).append("\n");
            }
            sb.append("Filesystem bridge server accepted: ").append(filesystemBridgeServer.accepted).append("\n");
            sb.append("Filesystem bridge server received: ").append(filesystemBridgeServer.received).append("\n");
            sb.append("Filesystem bridge server error: ").append(filesystemBridgeServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for filesystem bridge server: ").append(e2).append("\n");
            return sb.toString();
        }
    }

    private String runSyscallProbeMatrix(File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("App-spawned Linux syscall probe matrix\n\n");
        appendFileState(sb, "Probe payload", file);
        String[] strArr = {"open", "access", "openat", "openat2", "mkdir", "mkdirat", "unlinkat", "renameat", "readlinkat", "faccessat", "faccessat2", "newfstatat", "statx", "getrandom", "memfd_create", "membarrier", "rt_sigaction", "rt_sigprocmask", "setitimer", "execve_null", "uname", "futex", "sched_setaffinity", "sched_getaffinity", "getcpu", "arch_prctl", "set_tid_address", "prctl", "set_robust_list", "prlimit64", "rseq", "io_uring_setup", "clone3", "pidfd_open", "landlock_create_ruleset", "futex_waitv"};
        for (int i = 0; i < 36; i++) {
            String str = strArr[i];
            sb.append(runNamed("Static syscall probe " + str, new String[]{file.getAbsolutePath(), str}));
            sb.append("\n");
        }
        return sb.toString();
    }

    private void startSyscallProbe(final String str) {
        new Thread(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda12
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$startSyscallProbe$0(str);
            }
        }, "archphene-syscall-probe-" + str).start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startSyscallProbe$0(String str) {
        String strRunNamed = runNamed("Static syscall probe " + str, new String[]{new File(getApplicationInfo().nativeLibraryDir, SYSCALL_PROBE).getAbsolutePath(), str});
        File file = new File(getFilesDir(), "syscall-" + str + "-probe.txt");
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            try {
                fileOutputStream.write(strRunNamed.getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "access syscall probe written to " + file.getAbsolutePath());
                fileOutputStream.close();
            } finally {
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to write access syscall probe", e);
        }
    }

    private String runWaylandSocketProbe(File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("Wayland abstract socket fallback probe\n\n");
        appendFileState(sb, "Probe payload", file);
        File file2 = new File(getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        String str = getPackageName() + ".wayland-0." + Process.myUid();
        BridgeServer bridgeServer = new BridgeServer(str);
        Thread thread = new Thread(bridgeServer, "archphene-wayland-probe-server");
        thread.start();
        try {
            if (!bridgeServer.ready.await(2L, TimeUnit.SECONDS)) {
                sb.append("Bridge server did not become ready before timeout\n");
                return sb.toString();
            }
            if (!bridgeServer.listening) {
                sb.append("Bridge server failed before listen: ").append(bridgeServer.error).append("\n");
                return sb.toString();
            }
            HashMap map = new HashMap();
            putDisplaySizeEnv(map);
            map.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
            map.put("WAYLAND_DISPLAY", str);
            map.put("ARCHPHENE_WAYLAND_ABSTRACT", "1");
            sb.append(runNamedWithEnv("Linux payload connects to Android-owned abstract wayland socket", new String[]{file.getAbsolutePath()}, map));
            try {
                thread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sb.append("Interrupted while joining bridge server: ").append(e).append("\n");
            }
            sb.append("Bridge server accepted: ").append(bridgeServer.accepted).append("\n");
            sb.append("Bridge server received: ").append(bridgeServer.received).append("\n");
            sb.append("Bridge server error: ").append(bridgeServer.error).append("\n");
            return sb.toString();
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            sb.append("Interrupted while waiting for bridge server: ").append(e2).append("\n");
            return sb.toString();
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:111:0x0206 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.lang.String prepareLinuxRuntime(java.io.File r15) {
        /*
            Method dump skipped, instruction units count: 562
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: org.archphene.linux.mousepad.MainActivity.prepareLinuxRuntime(java.io.File):java.lang.String");
    }

    /* JADX WARN: Removed duplicated region for block: B:52:0x0095 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void extractZipAsset(java.lang.String r7, java.io.File r8) throws java.io.IOException {
        /*
            r6 = this;
            java.lang.String r0 = r8.getCanonicalPath()
            java.lang.String r1 = java.io.File.separator
            java.lang.StringBuilder r2 = new java.lang.StringBuilder
            r2.<init>()
            java.lang.StringBuilder r0 = r2.append(r0)
            java.lang.StringBuilder r0 = r0.append(r1)
            java.lang.String r0 = r0.toString()
            android.content.res.AssetManager r1 = r6.getAssets()
            java.io.InputStream r7 = r1.open(r7)
            java.util.zip.ZipInputStream r1 = new java.util.zip.ZipInputStream     // Catch: java.lang.Throwable -> L92
            r1.<init>(r7)     // Catch: java.lang.Throwable -> L92
            r2 = 65536(0x10000, float:9.1835E-41)
            byte[] r2 = new byte[r2]     // Catch: java.lang.Throwable -> L88
        L28:
            java.util.zip.ZipEntry r3 = r1.getNextEntry()     // Catch: java.lang.Throwable -> L88
            if (r3 == 0) goto L7f
            java.io.File r4 = new java.io.File     // Catch: java.lang.Throwable -> L88
            java.lang.String r5 = r3.getName()     // Catch: java.lang.Throwable -> L88
            r4.<init>(r8, r5)     // Catch: java.lang.Throwable -> L88
            java.io.File r4 = r4.getCanonicalFile()     // Catch: java.lang.Throwable -> L88
            java.lang.String r5 = r4.getPath()     // Catch: java.lang.Throwable -> L88
            boolean r5 = r5.startsWith(r0)     // Catch: java.lang.Throwable -> L88
            if (r5 == 0) goto L77
            boolean r3 = r3.isDirectory()     // Catch: java.lang.Throwable -> L88
            if (r3 == 0) goto L4f
            r4.mkdirs()     // Catch: java.lang.Throwable -> L88
            goto L28
        L4f:
            java.io.File r3 = r4.getParentFile()     // Catch: java.lang.Throwable -> L88
            if (r3 == 0) goto L58
            r3.mkdirs()     // Catch: java.lang.Throwable -> L88
        L58:
            java.io.FileOutputStream r3 = new java.io.FileOutputStream     // Catch: java.lang.Throwable -> L88
            r3.<init>(r4)     // Catch: java.lang.Throwable -> L88
        L5d:
            int r4 = r1.read(r2)     // Catch: java.lang.Throwable -> L6d
            r5 = -1
            if (r4 == r5) goto L69
            r5 = 0
            r3.write(r2, r5, r4)     // Catch: java.lang.Throwable -> L6d
            goto L5d
        L69:
            r3.close()     // Catch: java.lang.Throwable -> L88
            goto L28
        L6d:
            r8 = move-exception
            r3.close()     // Catch: java.lang.Throwable -> L72
            goto L76
        L72:
            r0 = move-exception
            r8.addSuppressed(r0)     // Catch: java.lang.Throwable -> L88
        L76:
            throw r8     // Catch: java.lang.Throwable -> L88
        L77:
            java.io.IOException r8 = new java.io.IOException     // Catch: java.lang.Throwable -> L88
            java.lang.String r0 = "data archive path escapes runtime root"
            r8.<init>(r0)     // Catch: java.lang.Throwable -> L88
            throw r8     // Catch: java.lang.Throwable -> L88
        L7f:
            r1.close()     // Catch: java.lang.Throwable -> L92
            if (r7 == 0) goto L87
            r7.close()
        L87:
            return
        L88:
            r8 = move-exception
            r1.close()     // Catch: java.lang.Throwable -> L8d
            goto L91
        L8d:
            r0 = move-exception
            r8.addSuppressed(r0)     // Catch: java.lang.Throwable -> L92
        L91:
            throw r8     // Catch: java.lang.Throwable -> L92
        L92:
            r8 = move-exception
            if (r7 == 0) goto L9d
            r7.close()     // Catch: java.lang.Throwable -> L99
            goto L9d
        L99:
            r7 = move-exception
            r8.addSuppressed(r7)
        L9d:
            throw r8
        */
        throw new UnsupportedOperationException("Method not decompiled: org.archphene.linux.mousepad.MainActivity.extractZipAsset(java.lang.String, java.io.File):void");
    }

    private static void copyFile(File file, File file2) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(file);
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file2);
            try {
                byte[] bArr = new byte[65536];
                while (true) {
                    int i = fileInputStream.read(bArr);
                    if (i != -1) {
                        fileOutputStream.write(bArr, 0, i);
                    } else {
                        fileOutputStream.close();
                        fileInputStream.close();
                        file2.setReadable(true, false);
                        file2.setExecutable(true, false);
                        return;
                    }
                }
            } finally {
            }
        } catch (Throwable th) {
            try {
                fileInputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private static void deleteContents(File file) {
        File[] fileArrListFiles = file.listFiles();
        if (fileArrListFiles == null) {
            return;
        }
        for (File file2 : fileArrListFiles) {
            if (file2.isDirectory()) {
                deleteContents(file2);
            }
            file2.delete();
        }
    }

    private String readAsset(String str, int i) {
        try {
            InputStream inputStreamOpen = getAssets().open(str);
            try {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try {
                    byte[] bArr = new byte[1024];
                    while (i > 0) {
                        int i2 = inputStreamOpen.read(bArr, 0, Math.min(1024, i));
                        if (i2 == -1) {
                            break;
                        }
                        byteArrayOutputStream.write(bArr, 0, i2);
                        i -= i2;
                    }
                    String string = byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
                    byteArrayOutputStream.close();
                    if (inputStreamOpen != null) {
                        inputStreamOpen.close();
                    }
                    return string;
                } finally {
                }
            } finally {
            }
        } catch (Exception e) {
            return "asset read failed: " + String.valueOf(e) + "\n";
        }
    }

    private static void appendFileState(StringBuilder sb, String str, File file) {
        sb.append(str).append("\n");
        sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
        sb.append("Exists: ").append(file.exists()).append("\n");
        sb.append("Length: ").append(file.length()).append("\n");
        sb.append("canExecute: ").append(file.canExecute()).append("\n\n");
    }

    private static String reportPatchBytes(File file, File file2) {
        StringBuilder sb = new StringBuilder();
        sb.append("On-device glibc patch byte report\n\n");
        appendBytes(sb, "loader set_robust_list site", file, 82136L, 8);
        appendBytes(sb, "loader rseq site", file, 82285L, 8);
        appendBytes(sb, "libc startup rt_sigprocmask site", file2, 161637L, 8);
        appendBytes(sb, "libc pthread set_robust_list site", file2, 619725L, 8);
        appendBytes(sb, "libc pthread rseq site", file2, 620467L, 8);
        appendBytes(sb, "libc fork set_robust_list site", file2, 939740L, 8);
        appendBytes(sb, "libc faccessat2 syscall number site", file2, 1089544L, 8);
        appendBytes(sb, "libc faccessat2 syscall site", file2, 1089575L, 8);
        appendBytes(sb, "libc openat2 entry site", file2, 1112816L, 8);
        return sb.toString();
    }

    private static void appendBytes(StringBuilder sb, String str, File file, long j, int i) {
        sb.append(str).append(" @ 0x").append(Long.toHexString(j)).append("\n");
        sb.append("File: ").append(file.getAbsolutePath()).append("\n");
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
            try {
                byte[] bArr = new byte[i];
                randomAccessFile.seek(j);
                int i2 = randomAccessFile.read(bArr);
                sb.append("Bytes: ");
                for (int i3 = 0; i3 < i2; i3++) {
                    if (i3 > 0) {
                        sb.append(" ");
                    }
                    int i4 = bArr[i3] & 255;
                    if (i4 < 16) {
                        sb.append("0");
                    }
                    sb.append(Integer.toHexString(i4));
                }
                sb.append("\n\n");
                randomAccessFile.close();
            } finally {
            }
        } catch (Exception e) {
            sb.append("Read failed: ").append(e).append("\n\n");
        }
    }

    private String runNamed(String str, String[] strArr) {
        return runNamedWithEnv(str, strArr, new HashMap());
    }

    private String runNamedWithEnv(String str, String[] strArr, Map<String, String> map) {
        try {
            return str + "\n\n" + formatCommandResult(strArr, run(strArr, map));
        } catch (Exception e) {
            return str + " failed:\n" + String.valueOf(e) + "\n";
        }
    }

    private Result run(String[] strArr, Map<String, String> map) throws Exception {
        return run(strArr, map, 5);
    }

    private Result run(String[] strArr, Map<String, String> map, int i) throws Exception {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(strArr);
            File filePrepareFontconfig = prepareFontconfig();
            File file = new File(getFilesDir(), "linux-home");
            File file2 = new File(file, ".cache");
            File file3 = new File(file, ".config");
            File file4 = new File(getCacheDir(), "linux-tmp");
            File file5 = new File(getFilesDir(), "wayland-runtime");
            file.mkdirs();
            file2.mkdirs();
            file3.mkdirs();
            file4.mkdirs();
            file5.mkdirs();
            String parent = new File(strArr[0]).getParent();
            processBuilder.directory(file);
            processBuilder.environment().put("LD_LIBRARY_PATH", parent);
            processBuilder.environment().put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
            processBuilder.environment().put("HOME", file.getAbsolutePath());
            processBuilder.environment().put("XDG_CACHE_HOME", file2.getAbsolutePath());
            processBuilder.environment().put("XDG_CONFIG_HOME", file3.getAbsolutePath());
            processBuilder.environment().put("XDG_RUNTIME_DIR", file5.getAbsolutePath());
            processBuilder.environment().put("TMPDIR", file4.getAbsolutePath());
            processBuilder.environment().put("FONTCONFIG_FILE", filePrepareFontconfig.getAbsolutePath());
            processBuilder.environment().put("FONTCONFIG_PATH", filePrepareFontconfig.getParentFile().getAbsolutePath());
            processBuilder.environment().put("QT_QPA_PLATFORM", "wayland");
            putQtDensityEnv(processBuilder.environment());
            processBuilder.environment().put("QT_QPA_PLATFORM_PLUGIN_PATH", new File(getFilesDir(), "linux-runtime/lib").getAbsolutePath());
            processBuilder.environment().put("WAYLAND_DISPLAY", "wayland-0");
            processBuilder.environment().putAll(map);
            final Process processStart = processBuilder.start();
            final String[] strArr2 = {""};
            final String[] strArr3 = {""};
            Thread thread = new Thread(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.lambda$run$0(strArr2, processStart);
                }
            }, "archphene-process-stdout");
            Thread thread2 = new Thread(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.lambda$run$1(strArr3, processStart);
                }
            }, "archphene-process-stderr");
            thread.start();
            thread2.start();
            boolean zWaitFor = true;
            boolean z = i <= 0;
            if (z) {
                this.activeLinuxProcess = processStart;
            }
            if (z) {
                processStart.waitFor();
            } else {
                zWaitFor = processStart.waitFor(i, TimeUnit.SECONDS);
                if (!zWaitFor) {
                    processStart.destroyForcibly();
                    processStart.waitFor(1L, TimeUnit.SECONDS);
                }
            }
            thread.join(2000L);
            thread2.join(2000L);
            String str = strArr2[0];
            String str2 = strArr3[0];
            if (z && this.activeLinuxProcess == processStart) {
                this.activeLinuxProcess = null;
            }
            if (!zWaitFor) {
                return new Result(-1, true, str, str2, "");
            }
            return new Result(processStart.exitValue(), false, str, str2, "");
        } catch (Exception e) {
            return new Result(-127, false, "", "", e.toString());
        }
    }

    static /* synthetic */ void lambda$run$0(String[] strArr, Process process) {
        strArr[0] = readProcessStream(process.getInputStream(), "stdout");
    }

    static /* synthetic */ void lambda$run$1(String[] strArr, Process process) {
        strArr[0] = readProcessStream(process.getErrorStream(), "stderr");
    }

    private File prepareFontconfig() throws Exception {
        File file = new File(getFilesDir(), "linux-runtime/fontconfig");
        file.mkdirs();
        File file2 = new File(file, "fonts.conf");
        InputStream inputStreamOpen = getAssets().open("fonts.conf");
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file2);
            try {
                byte[] bArr = new byte[8192];
                while (true) {
                    int i = inputStreamOpen.read(bArr);
                    if (i == -1) {
                        break;
                    }
                    fileOutputStream.write(bArr, 0, i);
                }
                fileOutputStream.close();
                if (inputStreamOpen != null) {
                    inputStreamOpen.close();
                }
                return file2;
            } finally {
            }
        } catch (Throwable th) {
            if (inputStreamOpen != null) {
                try {
                    inputStreamOpen.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private static String formatCommandResult(String[] strArr, Result result) {
        return "Command: " + Arrays.toString(strArr) + "\nExit code: " + result.exitCode + "\nTimed out: " + result.timedOut + "\nStdout:\n" + result.stdout + "Stderr:\n" + result.stderr + "Start error: " + result.startError + "\n";
    }

    private static String readProcessStream(InputStream inputStream, String str) {
        ByteArrayOutputStream byteArrayOutputStream;
        byte[] bArr;
        boolean z;
        try {
            try {
                byteArrayOutputStream = new ByteArrayOutputStream();
                try {
                    bArr = new byte[4096];
                    z = false;
                } finally {
                }
            } finally {
            }
        } catch (Exception e) {
            return "[stream unavailable after process exit: " + String.valueOf(e) + "]\n";
        }
        while (true) {
            int i = inputStream.read(bArr);
            if (i == -1) {
                break;
            }
            String strTrim = new String(bArr, 0, i, StandardCharsets.UTF_8).trim();
            if (!strTrim.isEmpty()) {
                Log.i(TAG, "Linux " + str + ": " + strTrim);
            }
            if (byteArrayOutputStream.size() + i > 65536) {
                byteArrayOutputStream.reset();
                z = true;
            }
            byteArrayOutputStream.write(bArr, 0, i);
            return "[stream unavailable after process exit: " + String.valueOf(e) + "]\n";
        }
        String str2 = (z ? "[earlier process output truncated]\n" : "") + byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
        byteArrayOutputStream.close();
        if (inputStream != null) {
            inputStream.close();
        }
        return str2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class RawWaylandShmServer implements Runnable {
        volatile boolean accepted;
        private int activePopupGrabId;
        private boolean androidClipboardOfferPending;
        private final Map<Integer, String> androidClipboardOffers;
        volatile int androidImeCommitChars;
        volatile int androidImeCommitEventsSent;
        volatile String androidImeLastText;
        volatile int androidImeSynthKeyEventsSent;
        volatile int androidInputConnectionsCreated;
        volatile int androidKeyEventsSent;
        volatile int androidPointerEventsSent;
        private int attachedBufferId;
        private final Map<Integer, Boolean> auxiliarySurfaceAttachPending;
        private final Map<Integer, Integer> auxiliarySurfaceBuffers;
        private final Map<Integer, Rect> auxiliarySurfaceDamage;
        volatile Bitmap bitmap;
        private int bufferId;
        volatile boolean bufferReleaseSent;
        volatile int bytesRead;
        volatile boolean callbackDoneSent;
        private final Map<Integer, ChildToplevelState> childToplevelsByRole;
        private final Map<Integer, ChildToplevelState> childToplevelsBySurface;
        private final Map<Integer, ChildToplevelState> childToplevelsByXdg;
        private boolean cleanupPending;
        volatile boolean cleanupSyncDone;
        private int clipboardSourceId;
        private final Map<Integer, ClipboardSourceState> clipboardSources;
        volatile int commitCount;
        volatile boolean committed;
        private boolean compactMainPresentation;
        private int compositorId;
        private volatile int configureHeight;
        private volatile int configureWidth;
        private LocalSocket connectedClient;
        private final float coordinateScale;
        private int dataDeviceId;
        private int dataDeviceManagerId;
        volatile int destroyRequestCount;
        volatile String error;
        private final Object eventLock;
        private StringBuilder eventLog;
        volatile int fdCount;
        volatile boolean frameCallbackDoneSent;
        private int frameCallbackId;
        volatile Runnable frameCommittedCallback;
        volatile int height;
        volatile boolean interactivePointerMode;
        volatile boolean keyboardFocusSent;
        private int keyboardFocusSurfaceId;
        private int keyboardId;
        volatile int keyboardKeyEventsSent;
        volatile boolean keyboardKeymapSent;
        volatile int keyboardLastKey;
        volatile int keyboardLastMods;
        volatile int keyboardModifiersSent;
        private int keyboardModsDepressed;
        volatile int keyboardRepeatDelay;
        volatile boolean keyboardRepeatInfoSent;
        volatile int keyboardRepeatRate;
        volatile boolean keyboardRequested;
        private int lastInputSerial;
        private String lastOfferedAndroidClipboardText;
        volatile boolean listening;
        volatile String log;
        volatile Bitmap mainBitmap;
        private boolean mainBufferAttachPending;
        private int mainDamageBottom;
        private int mainDamageLeft;
        private boolean mainDamagePending;
        private int mainDamageRight;
        private int mainDamageTop;
        private int mainDisplayHeight;
        private int mainDisplayWidth;
        private int mainDisplayX;
        private int mainDisplayY;
        private int mainSourceHeight;
        private int mainSourceWidth;
        volatile int messageCount;
        private int nextServerObjectId;
        volatile boolean outputDoneSent;
        private volatile int outputHeight;
        private int outputId;
        private final int outputScale;
        private volatile int outputWidth;
        private final ArrayDeque<FileDescriptor> pendingShmFds;
        private final Map<Integer, Boolean> pendingSurfaceInputInfinite;
        private final Map<Integer, Region> pendingSurfaceInputRegions;
        volatile int pointerButtonEventsSent;
        volatile boolean pointerEventsSent;
        private int pointerFocusSurfaceId;
        private int pointerGrabSurfaceId;
        private int pointerId;
        private boolean pointerInside;
        volatile int pointerLastX;
        volatile int pointerLastY;
        volatile int pointerMotionEventsSent;
        volatile boolean pointerRequested;
        private int pointerSerial;
        private int pointerSurfaceX;
        private int pointerSurfaceY;
        private int poolSize;
        private int popupSequence;
        private final Map<Integer, PopupState> popups;
        private final Map<Integer, PopupState> popupsByXdgSurface;
        private final Map<Integer, PositionerState> positioners;
        private boolean postCommitPending;
        volatile boolean postCommitSyncDone;
        private int preparedTouchSurfaceId;
        final CountDownLatch ready;
        private final ArrayDeque<Integer> recentInputSerials;
        private final Map<Integer, Region> regions;
        volatile int registryGlobalCount;
        private int registryId;
        volatile boolean seatCapabilitiesSent;
        private int seatId;
        private final boolean sendServerEvents;
        private final Map<Integer, ShmBufferState> shmBuffers;
        private FileDescriptor shmFd;
        volatile int shmFormatCount;
        private int shmId;
        private int shmPoolId;
        private final Map<Integer, ShmPoolState> shmPools;
        final File socket;
        private final int stopAfterSyncCallbacks;
        volatile int stride;
        private int subcompositorId;
        private final Map<Integer, SubsurfaceState> subsurfaces;
        private final Map<Integer, SubsurfaceState> subsurfacesBySurface;
        private final Map<Integer, Integer> surfaceBufferScales;
        private int surfaceId;
        private final Map<Integer, Region> surfaceInputRegions;
        volatile int syncCallbackCount;
        private int touchFocusSurfaceId;
        private int touchId;
        private final boolean waitForPostCommitSync;
        volatile int width;
        private final Map<Integer, WindowGeometry> windowGeometries;
        private final Object writeLock;
        volatile boolean xdgConfigureAcked;
        volatile boolean xdgConfigureSent;
        volatile int xdgConfigureSerial;
        private int xdgSurfaceId;
        private final Map<Integer, Integer> xdgSurfaceToWlSurface;
        private int xdgToplevelId;
        private int xdgWmBaseId;

        RawWaylandShmServer(File file) {
            this(file, false, 420, 260, 0);
        }

        RawWaylandShmServer(File file, boolean z) {
            this(file, z, 420, 260, 0);
        }

        RawWaylandShmServer(File file, boolean z, int i) {
            this(file, z, 420, 260, i);
        }

        RawWaylandShmServer(File file, boolean z, int i, int i2) {
            this(file, z, i, i2, 0, false);
        }

        RawWaylandShmServer(File file, boolean z, int i, int i2, boolean z2) {
            this(file, z, i, i2, 0, z2);
        }

        private RawWaylandShmServer(File file, boolean z, int i, int i2, int i3) {
            this(file, z, i, i2, i3, false);
        }

        private RawWaylandShmServer(File file, boolean z, int i, int i2, int i3, boolean z2) {
            this.ready = new CountDownLatch(1);
            this.error = "";
            this.log = "";
            this.androidImeLastText = "";
            this.writeLock = new Object();
            this.eventLock = new Object();
            this.pointerSerial = 200;
            this.recentInputSerials = new ArrayDeque<>();
            this.shmPools = new HashMap();
            this.shmBuffers = new HashMap();
            this.auxiliarySurfaceBuffers = new HashMap();
            this.auxiliarySurfaceAttachPending = new HashMap();
            this.auxiliarySurfaceDamage = new HashMap();
            this.regions = new HashMap();
            this.surfaceInputRegions = new HashMap();
            this.pendingSurfaceInputRegions = new HashMap();
            this.pendingSurfaceInputInfinite = new HashMap();
            this.surfaceBufferScales = new HashMap();
            this.subsurfaces = new HashMap();
            this.subsurfacesBySurface = new HashMap();
            this.xdgSurfaceToWlSurface = new HashMap();
            this.windowGeometries = new HashMap();
            this.positioners = new HashMap();
            this.popups = new HashMap();
            this.popupsByXdgSurface = new HashMap();
            this.childToplevelsByXdg = new HashMap();
            this.childToplevelsByRole = new HashMap();
            this.childToplevelsBySurface = new HashMap();
            this.clipboardSources = new HashMap();
            this.pendingShmFds = new ArrayDeque<>();
            this.shmId = 3;
            this.shmPoolId = 4;
            this.compositorId = 6;
            this.nextServerObjectId = -16777216;
            this.androidClipboardOffers = new HashMap();
            this.registryId = 2;
            this.socket = file;
            this.sendServerEvents = z;
            this.outputScale = 2;
            this.outputWidth = Math.max(320, Math.min(4096, i));
            this.outputHeight = Math.max(240, Math.min(4096, i2));
            this.configureWidth = Math.max(160, this.outputWidth / this.outputScale);
            this.configureHeight = Math.max(120, this.outputHeight / this.outputScale);
            this.coordinateScale = this.outputScale;
            this.stopAfterSyncCallbacks = Math.max(0, i3);
            this.waitForPostCommitSync = z2;
        }

        /* JADX WARN: Multi-variable type inference failed */
        /* JADX WARN: Removed duplicated region for block: B:122:0x00c9 A[EXC_TOP_SPLITTER, SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:137:0x00b8 A[EXC_TOP_SPLITTER, SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:143:? A[Catch: all -> 0x00c1, SYNTHETIC, TRY_LEAVE, TryCatch #1 {all -> 0x00c1, blocks: (B:53:0x00c0, B:52:0x00bd, B:31:0x008d, B:49:0x00b8), top: B:110:0x0027, inners: #17 }] */
        /* JADX WARN: Removed duplicated region for block: B:145:? A[Catch: all -> 0x00d2, SYNTHETIC, TRY_LEAVE, TryCatch #13 {all -> 0x00d2, blocks: (B:33:0x0092, B:64:0x00d1, B:63:0x00ce, B:60:0x00c9), top: B:109:0x001c, inners: #8 }] */
        /* JADX WARN: Type inference failed for: r11v0 */
        /* JADX WARN: Type inference failed for: r11v1 */
        /* JADX WARN: Type inference failed for: r12v0, types: [org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer] */
        /* JADX WARN: Type inference failed for: r1v10 */
        /* JADX WARN: Type inference failed for: r1v11 */
        /* JADX WARN: Type inference failed for: r1v12 */
        /* JADX WARN: Type inference failed for: r1v14, types: [android.net.LocalSocket] */
        /* JADX WARN: Type inference failed for: r1v17 */
        /* JADX WARN: Type inference failed for: r1v22 */
        /* JADX WARN: Type inference failed for: r1v7, types: [java.util.concurrent.CountDownLatch] */
        /* JADX WARN: Type inference failed for: r1v8 */
        /* JADX WARN: Type inference failed for: r1v9, types: [android.net.LocalSocket] */
        /* JADX WARN: Type inference failed for: r2v1 */
        /* JADX WARN: Type inference failed for: r2v10 */
        /* JADX WARN: Type inference failed for: r2v11 */
        /* JADX WARN: Type inference failed for: r2v12 */
        /* JADX WARN: Type inference failed for: r2v13 */
        /* JADX WARN: Type inference failed for: r2v14 */
        /* JADX WARN: Type inference failed for: r2v15, types: [android.net.LocalSocket] */
        /* JADX WARN: Type inference failed for: r2v16, types: [org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer] */
        /* JADX WARN: Type inference failed for: r2v17 */
        /* JADX WARN: Type inference failed for: r2v18 */
        /* JADX WARN: Type inference failed for: r2v2, types: [org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer] */
        /* JADX WARN: Type inference failed for: r2v20 */
        /* JADX WARN: Type inference failed for: r2v21 */
        /* JADX WARN: Type inference failed for: r2v22 */
        /* JADX WARN: Type inference failed for: r2v23 */
        /* JADX WARN: Type inference failed for: r2v3, types: [org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer] */
        /* JADX WARN: Type inference failed for: r2v4 */
        /* JADX WARN: Type inference failed for: r2v5 */
        /* JADX WARN: Type inference failed for: r2v8, types: [android.net.LocalSocket] */
        /* JADX WARN: Type inference failed for: r2v9 */
        /* JADX WARN: Type update failed for variable: r12v0 'this'  ??, new type: org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer
        jadx.core.utils.exceptions.JadxOverflowException: Type inference error: updates count limit reached with updateSeq = 3081. Try increasing type updates limit count.
        	at jadx.core.dex.visitors.typeinference.TypeUpdateInfo.requestUpdate(TypeUpdateInfo.java:37)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:224)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.applyInvokeTypes(TypeUpdate.java:399)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.invokeListener(TypeUpdate.java:364)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
         */
        /* JADX WARN: Type update failed for variable: r12v0 'this'  ??, new type: org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer
        jadx.core.utils.exceptions.JadxOverflowException: Type inference error: updates count limit reached with updateSeq = 3081. Try increasing type updates limit count.
        	at jadx.core.dex.visitors.typeinference.TypeUpdateInfo.requestUpdate(TypeUpdateInfo.java:37)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:224)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:473)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:202)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.moveListener(TypeUpdate.java:454)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeChecked(TypeUpdate.java:119)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.allSameListener(TypeUpdate.java:480)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.runListeners(TypeUpdate.java:241)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.requestUpdate(TypeUpdate.java:225)
        	at jadx.core.dex.visitors.typeinference.TypeUpdate.updateTypeForSsaVar(TypeUpdate.java:197)
         */
        @Override // java.lang.Runnable
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void run() throws java.lang.Throwable {
            /*
                Method dump skipped, instruction units count: 308
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: org.archphene.linux.mousepad.MainActivity.RawWaylandShmServer.run():void");
        }

        /* JADX WARN: Removed duplicated region for block: B:492:0x0ed3  */
        /* JADX WARN: Removed duplicated region for block: B:494:0x0eee  */
        /* JADX WARN: Type inference failed for: r1v117 */
        /* JADX WARN: Type inference failed for: r1v118, types: [boolean, int] */
        /* JADX WARN: Type inference failed for: r1v124 */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        private void handleMessage(android.net.LocalSocket r30, int r31, int r32, byte[] r33, java.lang.StringBuilder r34) throws java.lang.Exception {
            /*
                Method dump skipped, instruction units count: 5160
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: org.archphene.linux.mousepad.MainActivity.RawWaylandShmServer.handleMessage(android.net.LocalSocket, int, int, byte[], java.lang.StringBuilder):void");
        }

        void noteAndroidInputConnectionCreated() {
            this.androidInputConnectionsCreated++;
            appendAsyncEvent("android->bridge input_connection created");
        }

        boolean handleAndroidImeCommitText(CharSequence charSequence) {
            RawWaylandShmServer rawWaylandShmServer;
            LocalSocket localSocket;
            boolean z;
            LocalSocket localSocket2 = this.connectedClient;
            if (localSocket2 == null || this.keyboardId == 0 || !this.keyboardFocusSent || charSequence == null) {
                return false;
            }
            String string = charSequence.toString();
            Log.i(MainActivity.TAG, "Wayland IME text chars=" + string.length() + " keyboardFocus=" + this.keyboardFocusSurfaceId);
            this.androidImeCommitEventsSent++;
            this.androidImeCommitChars += string.length();
            this.androidImeLastText = string;
            appendAsyncEvent("android->bridge ime.commitText chars=" + string.length());
            long jUptimeMillis = SystemClock.uptimeMillis();
            int i = 0;
            boolean z2 = false;
            while (i < string.length()) {
                try {
                    int iEvdevKeyCodeForCharacter = evdevKeyCodeForCharacter(string.charAt(i));
                    if (iEvdevKeyCodeForCharacter == 0) {
                        z = z2;
                        localSocket = localSocket2;
                    } else {
                        this.keyboardLastKey = iEvdevKeyCodeForCharacter;
                        RawWaylandShmServer rawWaylandShmServer2 = this;
                        try {
                            rawWaylandShmServer2.sendKeyboardKey(localSocket2, iEvdevKeyCodeForCharacter, jUptimeMillis, true);
                            rawWaylandShmServer2 = this;
                            rawWaylandShmServer2.sendKeyboardKey(localSocket2, iEvdevKeyCodeForCharacter, jUptimeMillis, false);
                            LocalSocket localSocket3 = localSocket2;
                            rawWaylandShmServer = rawWaylandShmServer2;
                            localSocket = localSocket3;
                        } catch (Exception e) {
                            e = e;
                            rawWaylandShmServer = rawWaylandShmServer2;
                            Exception exc = e;
                            appendAsyncEvent("android ime forwarding failed: " + String.valueOf(exc));
                            rawWaylandShmServer.error = exc.toString();
                            return false;
                        }
                        try {
                            rawWaylandShmServer.androidImeSynthKeyEventsSent += 2;
                            z = true;
                        } catch (Exception e2) {
                            e = e2;
                            Exception exc2 = e;
                            appendAsyncEvent("android ime forwarding failed: " + String.valueOf(exc2));
                            rawWaylandShmServer.error = exc2.toString();
                            return false;
                        }
                    }
                    i++;
                    localSocket2 = localSocket;
                    z2 = z;
                } catch (Exception e3) {
                    e = e3;
                    rawWaylandShmServer = this;
                }
            }
            rawWaylandShmServer = this;
            if (!z2) {
                if (!string.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        boolean handleAndroidImeDelete() {
            RawWaylandShmServer rawWaylandShmServer;
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.keyboardId == 0 || !this.keyboardFocusSent) {
                return false;
            }
            long jUptimeMillis = SystemClock.uptimeMillis();
            try {
                this.keyboardLastKey = 14;
                rawWaylandShmServer = this;
            } catch (Exception e) {
                e = e;
                rawWaylandShmServer = this;
            }
            try {
                rawWaylandShmServer.sendKeyboardKey(localSocket, 14, jUptimeMillis, true);
                rawWaylandShmServer = this;
                rawWaylandShmServer.sendKeyboardKey(localSocket, 14, jUptimeMillis, false);
                rawWaylandShmServer.androidImeSynthKeyEventsSent += 2;
                appendAsyncEvent("android->bridge ime.delete");
                return true;
            } catch (Exception e2) {
                e = e2;
                appendAsyncEvent("android ime delete failed: " + String.valueOf(e));
                rawWaylandShmServer.error = e.toString();
                return false;
            }
        }

        private static int evdevKeyCodeForCharacter(char c) {
            if (c >= 'a' && c <= 'z') {
                return evdevKeyCode((c - 'a') + 29);
            }
            if (c >= 'A' && c <= 'Z') {
                return evdevKeyCode((c - 'A') + 29);
            }
            if (c >= '1' && c <= '9') {
                return evdevKeyCode((c - '1') + 8);
            }
            if (c == '0') {
                return evdevKeyCode(7);
            }
            if (c == ' ') {
                return evdevKeyCode(62);
            }
            if (c == '-') {
                return 12;
            }
            if (c == '.') {
                return 52;
            }
            if (c == '\n') {
                return evdevKeyCode(66);
            }
            return 0;
        }

        /* JADX WARN: Multi-variable type inference failed */
        boolean handleAndroidKeyEvent(int i, int i2, long j) {
            Exception exc;
            RawWaylandShmServer rawWaylandShmServer;
            String str;
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.keyboardId == 0 || !this.keyboardFocusSent) {
                return false;
            }
            int iEvdevKeyCode = evdevKeyCode(i2);
            if (i == 0) {
                String str2 = "Wayland key android=" + i2 + " evdev=" + iEvdevKeyCode + " keyboardFocus=" + this.keyboardFocusSurfaceId;
                str = MainActivity.TAG;
                Log.i(MainActivity.TAG, str2);
            }
            if (iEvdevKeyCode == 0) {
                return false;
            }
            this.androidKeyEventsSent++;
            this.keyboardLastKey = iEvdevKeyCode;
            int iModifierMaskForAndroidKey = modifierMaskForAndroidKey(i2);
            try {
            } catch (Exception e) {
                exc = e;
                rawWaylandShmServer = str;
            }
            if (i != 0) {
                if (i == 1) {
                    sendKeyboardKey(localSocket, iEvdevKeyCode, j, false);
                    if (iModifierMaskForAndroidKey != 0) {
                        this.keyboardModsDepressed &= ~iModifierMaskForAndroidKey;
                        sendKeyboardModifiers(localSocket);
                    }
                    return true;
                }
                return false;
            }
            if (i2 == 50) {
                try {
                    if ((this.keyboardModsDepressed & 4) != 0) {
                        publishAndroidClipboard();
                    }
                } catch (Exception e2) {
                    exc = e2;
                    rawWaylandShmServer = this;
                    appendAsyncEvent("android key forwarding failed: " + String.valueOf(exc));
                    rawWaylandShmServer.error = exc.toString();
                    return false;
                }
            }
            if (iModifierMaskForAndroidKey != 0) {
                this.keyboardModsDepressed |= iModifierMaskForAndroidKey;
            }
            sendKeyboardKey(localSocket, iEvdevKeyCode, j, true);
            if (iModifierMaskForAndroidKey != 0) {
                sendKeyboardModifiers(localSocket);
            }
            return true;
        }

        private boolean isWithinActivePopupGrab(PopupState popupState) {
            if (popupState == null) {
                return false;
            }
            PopupState popupState2 = this.popups.get(Integer.valueOf(this.activePopupGrabId));
            while (popupState2 != null) {
                if (popupState2.popupId == popupState.popupId) {
                    return true;
                }
                popupState2 = this.popupsByXdgSurface.get(Integer.valueOf(popupState2.parentXdgSurfaceId));
            }
            return false;
        }

        private void sendPopupDone(LocalSocket localSocket, PopupState popupState, boolean z) throws Exception {
            int i = 0;
            if (z) {
                ArrayList<PopupState> arrayList = new ArrayList();
                for (PopupState popupState2 : this.popups.values()) {
                    PopupState popupState3 = this.popupsByXdgSurface.get(Integer.valueOf(popupState2.parentXdgSurfaceId));
                    while (true) {
                        if (popupState3 == null) {
                            break;
                        }
                        if (popupState3.popupId == popupState.popupId) {
                            arrayList.add(popupState2);
                            break;
                        }
                        popupState3 = this.popupsByXdgSurface.get(Integer.valueOf(popupState3.parentXdgSurfaceId));
                    }
                }
                arrayList.sort(new Comparator() { // from class: org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer$$ExternalSyntheticLambda0
                    @Override // java.util.Comparator
                    public final int compare(Object obj, Object obj2) {
                        return Integer.compare(((MainActivity.RawWaylandShmServer.PopupState) obj2).sequence, ((MainActivity.RawWaylandShmServer.PopupState) obj).sequence);
                    }
                });
                for (PopupState popupState4 : arrayList) {
                    if (popupState4.visible) {
                        writeMessage(localSocket, popupState4.popupId, 1, new byte[0]);
                        popupState4.visible = false;
                        popupState4.grabbed = false;
                    }
                }
            }
            if (popupState.visible || !popupState.configureSent) {
                writeMessage(localSocket, popupState.popupId, 1, new byte[0]);
            }
            popupState.visible = false;
            popupState.grabbed = false;
            PopupState popupState5 = this.popupsByXdgSurface.get(Integer.valueOf(popupState.parentXdgSurfaceId));
            if (popupState5 != null && popupState5.grabbed) {
                i = popupState5.popupId;
            }
            this.activePopupGrabId = i;
            appendAsyncEvent("server->client xdg_popup.popup_done popup=" + popupState.popupId);
        }

        void logRecentProtocol() {
            String string;
            synchronized (this.eventLock) {
                string = this.eventLog == null ? this.log : this.eventLog.toString();
            }
            int i = 0;
            String strSubstring = string.substring(Math.max(0, string.length() - 6000));
            while (i < strSubstring.length()) {
                int i2 = i + 3000;
                Log.i(MainActivity.TAG, "Wayland trace:\n" + strSubstring.substring(i, Math.min(strSubstring.length(), i2)));
                i = i2;
            }
        }

        boolean hasActivePopupGrab() {
            PopupState popupState = this.popups.get(Integer.valueOf(this.activePopupGrabId));
            return popupState != null && popupState.visible && popupState.grabbed;
        }

        boolean hasVisiblePopups() {
            Iterator<PopupState> it = this.popups.values().iterator();
            while (it.hasNext()) {
                if (it.next().visible) {
                    return true;
                }
            }
            return false;
        }

        boolean sendPopupEscape() {
            ChildToplevelState childToplevelState = topVisibleChildToplevel();
            if (!hasVisiblePopups() && childToplevelState == null) {
                return false;
            }
            if (childToplevelState != null && this.keyboardId != 0 && this.keyboardFocusSurfaceId != childToplevelState.wlSurfaceId) {
                try {
                    sendKeyboardFocus(this.connectedClient, childToplevelState.wlSurfaceId, null);
                } catch (Exception e) {
                    this.error = e.toString();
                    return false;
                }
            }
            long jUptimeMillis = SystemClock.uptimeMillis();
            return handleAndroidKeyEvent(0, 111, jUptimeMillis) && handleAndroidKeyEvent(1, 111, jUptimeMillis);
        }

        private static int modifierMaskForAndroidKey(int i) {
            if (i == 59 || i == 60) {
                return 1;
            }
            if (i == 113 || i == 114) {
                return 4;
            }
            if (i == 57 || i == 58) {
                return 8;
            }
            return 0;
        }

        private static int evdevKeyCode(int i) {
            if (i >= 29 && i <= 54) {
                return new int[]{30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44}[i - 29];
            }
            if (i >= 8 && i <= 16) {
                return (i - 8) + 2;
            }
            if (i == 7) {
                return 11;
            }
            if (i == 66) {
                return 28;
            }
            if (i == 62) {
                return 57;
            }
            if (i == 69) {
                return 12;
            }
            if (i == 56) {
                return 52;
            }
            if (i == 67) {
                return 14;
            }
            if (i == 61) {
                return 15;
            }
            if (i == 19) {
                return 103;
            }
            if (i == 21) {
                return 105;
            }
            if (i == 22) {
                return 106;
            }
            if (i == 20) {
                return 108;
            }
            if (i == 122) {
                return 102;
            }
            if (i == 123) {
                return 107;
            }
            if (i == 92) {
                return 104;
            }
            if (i == 93) {
                return 109;
            }
            if (i == 124) {
                return 110;
            }
            if (i == 112) {
                return 111;
            }
            if (i >= 131 && i <= 140) {
                return (i + 59) - 131;
            }
            if (i == 141) {
                return 87;
            }
            if (i == 142) {
                return 88;
            }
            if (i == 111) {
                return 1;
            }
            if (i == 59) {
                return 42;
            }
            if (i == 60) {
                return 54;
            }
            if (i == 113) {
                return 29;
            }
            if (i == 114) {
                return 97;
            }
            if (i == 57) {
                return 56;
            }
            return i == 58 ? 100 : 0;
        }

        private void sendKeyboardKeymap(LocalSocket localSocket, StringBuilder sb) throws Exception {
            byte[] bytes = (minimalXkbKeymap() + "\u0000").getBytes(StandardCharsets.UTF_8);
            File fileCreateTempFile = File.createTempFile("akm", ".xkb", this.socket.getParentFile());
            FileOutputStream fileOutputStream = new FileOutputStream(fileCreateTempFile);
            try {
                fileOutputStream.write(bytes);
                fileOutputStream.close();
                byte[] bArr = new byte[8];
                putU32(bArr, 0, 1);
                putU32(bArr, 4, bytes.length);
                try {
                    try {
                        FileInputStream fileInputStream = new FileInputStream(fileCreateTempFile);
                        try {
                            try {
                                writeMessageWithFd(localSocket, this.keyboardId, 0, bArr, fileInputStream.getFD());
                                fileInputStream.close();
                                fileCreateTempFile.delete();
                                this.keyboardKeymapSent = true;
                                sb.append("server->client object=").append(this.keyboardId).append(" opcode=0 wl_keyboard.keymap xkb_v1 fd size=").append(bytes.length).append("\n");
                                sendKeyboardRepeatInfo(localSocket, sb);
                            } catch (Throwable th) {
                                th = th;
                                Throwable th2 = th;
                                try {
                                    fileInputStream.close();
                                    throw th2;
                                } catch (Throwable th3) {
                                    th2.addSuppressed(th3);
                                    throw th2;
                                }
                            }
                        } catch (Throwable th4) {
                            th = th4;
                        }
                    } catch (Throwable th5) {
                        th = th5;
                        Throwable th6 = th;
                        fileCreateTempFile.delete();
                        throw th6;
                    }
                } catch (Throwable th7) {
                    th = th7;
                    Throwable th62 = th;
                    fileCreateTempFile.delete();
                    throw th62;
                }
            } finally {
            }
        }

        private static String minimalXkbKeymap() {
            return "xkb_keymap {\nxkb_keycodes \"archphene\" { minimum = 8; maximum = 255; <ESC> = 9; <AE01> = 10; <AE02> = 11; <AE03> = 12; <AE04> = 13; <AE05> = 14; <AE06> = 15; <AE07> = 16; <AE08> = 17; <AE09> = 18; <AE10> = 19; <AE11> = 20; <TAB> = 23; <AD01> = 24; <AD02> = 25; <AD03> = 26; <AD04> = 27; <AD05> = 28; <AD06> = 29; <AD07> = 30; <AD08> = 31; <AD09> = 32; <AD10> = 33; <AC01> = 38; <AC02> = 39; <AC03> = 40; <AC04> = 41; <AC05> = 42; <AC06> = 43; <AC07> = 44; <AC08> = 45; <AC09> = 46; <AB01> = 52; <AB02> = 53; <AB03> = 54; <AB04> = 55; <AB05> = 56; <AB06> = 57; <AB07> = 58; <AB09> = 60; <LFSH> = 50; <RTSH> = 62; <LCTL> = 37; <RCTL> = 105; <LALT> = 64; <RALT> = 108; <SPCE> = 65; <RTRN> = 36; <BKSP> = 22; <UP> = 111; <LEFT> = 113; <RGHT> = 114; <DOWN> = 116; <HOME> = 110; <END> = 115; <PGUP> = 112; <PGDN> = 117; <INS> = 118; <DELE> = 119; <FK01> = 67; <FK02> = 68; <FK03> = 69; <FK04> = 70; <FK05> = 71; <FK06> = 72; <FK07> = 73; <FK08> = 74; <FK09> = 75; <FK10> = 76; <FK11> = 95; <FK12> = 96; };\nxkb_types \"archphene\" { virtual_modifiers NumLock,Alt,LevelThree; type \"ONE_LEVEL\" { modifiers = none; map[None] = Level1; level_name[Level1] = \"Any\"; }; type \"TWO_LEVEL\" { modifiers = Shift; map[None] = Level1; map[Shift] = Level2; level_name[Level1] = \"Base\"; level_name[Level2] = \"Shift\"; }; };\nxkb_compatibility \"archphene\" { };\nxkb_symbols \"archphene\" { key <ESC> { [ Escape ] }; modifier_map Shift { <LFSH>, <RTSH> }; modifier_map Control { <LCTL>, <RCTL> }; modifier_map Mod1 { <LALT>, <RALT> }; key <LFSH> { [ Shift_L ] }; key <RTSH> { [ Shift_R ] }; key <LCTL> { [ Control_L ] }; key <RCTL> { [ Control_R ] }; key <LALT> { [ Alt_L ] }; key <RALT> { [ Alt_R ] }; key <AE01> { [ 1 ] }; key <AE02> { [ 2 ] }; key <AE03> { [ 3 ] }; key <AE04> { [ 4 ] }; key <AE05> { [ 5 ] }; key <AE06> { [ 6 ] }; key <AE07> { [ 7 ] }; key <AE08> { [ 8 ] }; key <AE09> { [ 9 ] }; key <AE10> { [ 0 ] }; key <AE11> { [ minus, underscore ] }; key <AD01> { type=\"TWO_LEVEL\", [ q, Q ] }; key <AD02> { type=\"TWO_LEVEL\", [ w, W ] }; key <AD03> { type=\"TWO_LEVEL\", [ e, E ] }; key <AD04> { type=\"TWO_LEVEL\", [ r, R ] }; key <AD05> { type=\"TWO_LEVEL\", [ t, T ] }; key <AD06> { type=\"TWO_LEVEL\", [ y, Y ] }; key <AD07> { type=\"TWO_LEVEL\", [ u, U ] }; key <AD08> { type=\"TWO_LEVEL\", [ i, I ] }; key <AD09> { type=\"TWO_LEVEL\", [ o, O ] }; key <AD10> { type=\"TWO_LEVEL\", [ p, P ] }; key <AC01> { type=\"TWO_LEVEL\", [ a, A ] }; key <AC02> { type=\"TWO_LEVEL\", [ s, S ] }; key <AC03> { type=\"TWO_LEVEL\", [ d, D ] }; key <AC04> { type=\"TWO_LEVEL\", [ f, F ] }; key <AC05> { type=\"TWO_LEVEL\", [ g, G ] }; key <AC06> { type=\"TWO_LEVEL\", [ h, H ] }; key <AC07> { type=\"TWO_LEVEL\", [ j, J ] }; key <AC08> { type=\"TWO_LEVEL\", [ k, K ] }; key <AC09> { type=\"TWO_LEVEL\", [ l, L ] }; key <AB01> { type=\"TWO_LEVEL\", [ z, Z ] }; key <AB02> { type=\"TWO_LEVEL\", [ x, X ] }; key <AB03> { type=\"TWO_LEVEL\", [ c, C ] }; key <AB04> { type=\"TWO_LEVEL\", [ v, V ] }; key <AB05> { type=\"TWO_LEVEL\", [ b, B ] }; key <AB06> { type=\"TWO_LEVEL\", [ n, N ] }; key <AB07> { type=\"TWO_LEVEL\", [ m, M ] }; key <SPCE> { [ space ] }; key <RTRN> { [ Return ] }; key <BKSP> { [ BackSpace ] }; key <UP> { [ Up ] }; key <LEFT> { [ Left ] }; key <RGHT> { [ Right ] }; key <DOWN> { [ Down ] }; key <HOME> { [ Home ] }; key <END> { [ End ] }; key <PGUP> { [ Prior ] }; key <PGDN> { [ Next ] }; key <INS> { [ Insert ] }; key <DELE> { [ Delete ] }; key <FK01> { [ F1 ] }; key <FK02> { [ F2 ] }; key <FK03> { [ F3 ] }; key <FK04> { [ F4 ] }; key <FK05> { [ F5 ] }; key <FK06> { [ F6 ] }; key <FK07> { [ F7 ] }; key <FK08> { [ F8 ] }; key <FK09> { [ F9 ] }; key <FK10> { [ F10 ] }; key <FK11> { [ F11 ] }; key <FK12> { [ F12 ] }; };\nxkb_geometry \"archphene\" { };\n};\n";
        }

        private void sendKeyboardRepeatInfo(LocalSocket localSocket, StringBuilder sb) throws Exception {
            this.keyboardRepeatRate = 25;
            this.keyboardRepeatDelay = 400;
            byte[] bArr = new byte[8];
            putU32(bArr, 0, this.keyboardRepeatRate);
            putU32(bArr, 4, this.keyboardRepeatDelay);
            writeMessage(localSocket, this.keyboardId, 5, bArr);
            this.keyboardRepeatInfoSent = true;
            sb.append("server->client object=").append(this.keyboardId).append(" opcode=5 wl_keyboard.repeat_info rate=").append(this.keyboardRepeatRate).append(" delay=").append(this.keyboardRepeatDelay).append("\n");
        }

        private void sendKeyboardFocus(LocalSocket localSocket, StringBuilder sb) throws Exception {
            sendKeyboardFocus(localSocket, this.surfaceId, sb);
        }

        private void sendKeyboardFocus(LocalSocket localSocket, int i, StringBuilder sb) throws Exception {
            if (this.keyboardFocusSurfaceId != 0 && this.keyboardFocusSurfaceId != i) {
                byte[] bArr = new byte[8];
                int i2 = this.pointerSerial;
                this.pointerSerial = i2 + 1;
                putU32(bArr, 0, i2);
                putU32(bArr, 4, this.keyboardFocusSurfaceId);
                writeMessage(localSocket, this.keyboardId, 2, bArr);
            }
            byte[] bArr2 = new byte[12];
            int i3 = this.pointerSerial;
            this.pointerSerial = i3 + 1;
            putU32(bArr2, 0, i3);
            putU32(bArr2, 4, i);
            putU32(bArr2, 8, 0);
            writeMessage(localSocket, this.keyboardId, 1, bArr2);
            byte[] bArr3 = new byte[20];
            int i4 = this.pointerSerial;
            this.pointerSerial = i4 + 1;
            putU32(bArr3, 0, i4);
            writeMessage(localSocket, this.keyboardId, 4, bArr3);
            this.keyboardFocusSent = true;
            this.keyboardFocusSurfaceId = i;
            if (sb != null) {
                sb.append("server->client object=").append(this.keyboardId).append(" opcode=1 wl_keyboard.enter surface=").append(i).append("\n");
                sb.append("server->client object=").append(this.keyboardId).append(" opcode=4 wl_keyboard.modifiers zero\n");
            } else {
                appendAsyncEvent("android->wayland wl_keyboard.enter surface=" + i);
            }
        }

        private void sendKeyboardModifiers(LocalSocket localSocket) throws Exception {
            byte[] bArr = new byte[20];
            int i = this.pointerSerial;
            this.pointerSerial = i + 1;
            putU32(bArr, 0, i);
            rememberInputSerial(i);
            putU32(bArr, 4, this.keyboardModsDepressed);
            writeMessage(localSocket, this.keyboardId, 4, bArr);
            this.keyboardModifiersSent++;
            this.keyboardLastMods = this.keyboardModsDepressed;
            appendAsyncEvent("android->wayland object=" + this.keyboardId + " opcode=4 wl_keyboard.modifiers depressed=" + this.keyboardModsDepressed);
        }

        private void sendKeyboardKey(LocalSocket localSocket, int i, long j, boolean z) throws Exception {
            byte[] bArr = new byte[16];
            int i2 = this.pointerSerial;
            this.pointerSerial = i2 + 1;
            putU32(bArr, 0, i2);
            putU32(bArr, 4, (int) j);
            putU32(bArr, 8, i);
            putU32(bArr, 12, z ? 1 : 0);
            writeMessage(localSocket, this.keyboardId, 3, bArr);
            this.keyboardKeyEventsSent++;
            rememberInputSerial(i2);
            appendAsyncEvent("android->wayland object=" + this.keyboardId + " opcode=3 wl_keyboard.key key=" + i + " " + (z ? "pressed" : "released"));
        }

        synchronized void requestResize(int i, int i2) {
            this.outputWidth = Math.max(320, Math.min(4096, i));
            this.outputHeight = Math.max(240, Math.min(4096, i2));
            int iMax = Math.max(160, this.outputWidth / this.outputScale);
            int iMax2 = Math.max(120, this.outputHeight / this.outputScale);
            if (iMax == this.configureWidth && iMax2 == this.configureHeight) {
                return;
            }
            this.configureWidth = iMax;
            this.configureHeight = iMax2;
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.xdgSurfaceId == 0 || this.xdgToplevelId == 0) {
                return;
            }
            try {
                this.xdgConfigureAcked = false;
                StringBuilder sb = new StringBuilder();
                sendXdgConfigure(localSocket, sb);
                for (ChildToplevelState childToplevelState : this.childToplevelsByXdg.values()) {
                    if (childToplevelState.visible && childToplevelState.isFileDialog()) {
                        childToplevelState.configureAcked = false;
                        sendChildToplevelConfigure(localSocket, childToplevelState, sb);
                    }
                }
                for (PopupState popupState : this.popups.values()) {
                    if (popupState.visible && popupState.positioner.reactive) {
                        constrainPopup(popupState);
                        popupState.configureAcked = false;
                        sendPopupConfigure(localSocket, popupState, sb);
                    }
                }
                appendAsyncEvent(sb.toString().trim());
            } catch (Exception e) {
                appendAsyncEvent("android resize forwarding failed: " + String.valueOf(e));
                this.error = e.toString();
            }
        }

        private void applyPendingInputRegion(int i) {
            Boolean boolRemove = this.pendingSurfaceInputInfinite.remove(Integer.valueOf(i));
            if (boolRemove == null) {
                return;
            }
            if (boolRemove.booleanValue()) {
                this.surfaceInputRegions.remove(Integer.valueOf(i));
            } else {
                Region regionRemove = this.pendingSurfaceInputRegions.remove(Integer.valueOf(i));
                this.surfaceInputRegions.put(Integer.valueOf(i), regionRemove == null ? new Region() : new Region(regionRemove));
            }
        }

        private boolean surfaceAcceptsInput(int i, int i2, int i3) {
            Region region = this.surfaceInputRegions.get(Integer.valueOf(i));
            return region == null || region.contains(i2, i3);
        }

        boolean handleAndroidScrollEvent(float f, float f2, float f3, long j) {
            LocalSocket localSocket;
            if (f3 == 0.0f || !handleAndroidMotionEvent(2, f, f2, j) || (localSocket = this.connectedClient) == null || this.pointerId == 0) {
                return false;
            }
            try {
                byte[] bArr = new byte[4];
                putU32(bArr, 0, 0);
                writeMessage(localSocket, this.pointerId, 6, bArr);
                byte[] bArr2 = new byte[8];
                putU32(bArr2, 0, 0);
                putU32(bArr2, 4, f3 > 0.0f ? -1 : 1);
                writeMessage(localSocket, this.pointerId, 8, bArr2);
                byte[] bArr3 = new byte[12];
                putU32(bArr3, 0, (int) j);
                putU32(bArr3, 4, 0);
                putU32(bArr3, 8, Math.round((-f3) * 15.0f * 256.0f));
                writeMessage(localSocket, this.pointerId, 4, bArr3);
                sendPointerFrame(localSocket);
                appendAsyncEvent("android->wayland wl_pointer.axis vertical=" + f3);
                return true;
            } catch (Exception e) {
                appendAsyncEvent("android scroll forwarding failed: " + String.valueOf(e));
                this.error = e.toString();
                return false;
            }
        }

        boolean handleAndroidPointerExit() {
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.pointerId == 0 || !this.pointerInside || this.pointerGrabSurfaceId != 0) {
                return false;
            }
            try {
                sendPointerLeave(localSocket, this.pointerFocusSurfaceId);
                sendPointerFrame(localSocket);
                this.pointerInside = false;
                this.pointerFocusSurfaceId = 0;
                return true;
            } catch (Exception e) {
                appendAsyncEvent("android hover exit forwarding failed: " + String.valueOf(e));
                this.error = e.toString();
                return false;
            }
        }

        boolean shouldRequestTextInput(float f, float f2) {
            ChildToplevelState childToplevelState = topVisibleChildToplevel();
            if (this.compactMainPresentation || hasVisiblePopups()) {
                return false;
            }
            return childToplevelState == null ? f2 > 160.0f : childToplevelState.isSaveDialog() && f > ((float) this.outputWidth) * 0.55f && f2 < 140.0f;
        }

        boolean handleAndroidMotionEvent(int i, float f, float f2, long j) {
            return handleAndroidInputEvent(i, f, f2, j, true);
        }

        boolean prepareAndroidTouchTarget(float f, float f2, long j) {
            return handleAndroidInputEvent(2, f, f2, j, false);
        }

        private boolean handleAndroidInputEvent(int i, float f, float f2, long j, boolean z) {
            int i2;
            int iRound;
            int iRound2;
            LocalSocket localSocket;
            int i3;
            LocalSocket localSocket2;
            boolean z2;
            int i4;
            String str;
            LocalSocket localSocket3 = this.connectedClient;
            if (localSocket3 != null && this.pointerId != 0) {
                if (this.surfaceId == 0) {
                    return false;
                }
                int iMax = Math.max(0, Math.min(this.outputWidth - 1, Math.round(f)));
                int iMax2 = Math.max(0, Math.min(this.outputHeight - 1, Math.round(f2)));
                int iPresentationOriginX = presentationOriginX() + iMax;
                int iPresentationOriginY = presentationOriginY() + iMax2;
                this.pointerLastX = iMax;
                this.pointerLastY = iMax2;
                this.androidPointerEventsSent++;
                PopupState popupStateFindVisiblePopupAt = findVisiblePopupAt(iMax, iMax2);
                if (i == 0 && this.activePopupGrabId != 0 && !isWithinActivePopupGrab(popupStateFindVisiblePopupAt)) {
                    PopupState popupState = this.popups.get(Integer.valueOf(this.activePopupGrabId));
                    if (popupState != null) {
                        try {
                            sendPopupDone(localSocket3, popupState, true);
                            composeSurfaceTree();
                        } catch (Exception e) {
                            appendAsyncEvent("popup dismissal failed: " + String.valueOf(e));
                            this.error = e.toString();
                        }
                    }
                    return true;
                }
                SubsurfaceState subsurfaceStateFindVisibleSubsurfaceAt = popupStateFindVisiblePopupAt == null ? findVisibleSubsurfaceAt(iMax, iMax2) : null;
                ChildToplevelState childToplevelStateFindVisibleChildToplevelAt = (popupStateFindVisiblePopupAt == null && subsurfaceStateFindVisibleSubsurfaceAt == null) ? findVisibleChildToplevelAt(iMax, iMax2) : null;
                if (popupStateFindVisiblePopupAt == null && subsurfaceStateFindVisibleSubsurfaceAt == null && childToplevelStateFindVisibleChildToplevelAt == null && topVisibleChildToplevel() != null) {
                    return true;
                }
                if (popupStateFindVisiblePopupAt == null && subsurfaceStateFindVisibleSubsurfaceAt == null && childToplevelStateFindVisibleChildToplevelAt == null && this.compactMainPresentation && (iMax < this.mainDisplayX || iMax2 < this.mainDisplayY || iMax >= this.mainDisplayX + this.mainDisplayWidth || iMax2 >= this.mainDisplayY + this.mainDisplayHeight)) {
                    return true;
                }
                if (popupStateFindVisiblePopupAt != null) {
                    i2 = popupStateFindVisiblePopupAt.wlSurfaceId;
                } else if (subsurfaceStateFindVisibleSubsurfaceAt != null) {
                    i2 = subsurfaceStateFindVisibleSubsurfaceAt.wlSurfaceId;
                } else {
                    i2 = childToplevelStateFindVisibleChildToplevelAt != null ? childToplevelStateFindVisibleChildToplevelAt.wlSurfaceId : this.surfaceId;
                }
                if (i == 0) {
                    this.pointerGrabSurfaceId = i2;
                } else if (this.pointerGrabSurfaceId != 0) {
                    i2 = this.pointerGrabSurfaceId;
                    popupStateFindVisiblePopupAt = findPopupByWlSurface(i2);
                    subsurfaceStateFindVisibleSubsurfaceAt = popupStateFindVisiblePopupAt == null ? this.subsurfacesBySurface.get(Integer.valueOf(i2)) : null;
                    childToplevelStateFindVisibleChildToplevelAt = (popupStateFindVisiblePopupAt == null && subsurfaceStateFindVisibleSubsurfaceAt == null) ? this.childToplevelsBySurface.get(Integer.valueOf(i2)) : null;
                }
                int iMax3 = Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(i2), 1).intValue());
                if (popupStateFindVisiblePopupAt != null) {
                    iRound = Math.round((iMax - popupStateFindVisiblePopupAt.displayX) / popupStateFindVisiblePopupAt.pixelsPerLogical);
                    iRound2 = Math.round((iMax2 - popupStateFindVisiblePopupAt.displayY) / popupStateFindVisiblePopupAt.pixelsPerLogical);
                } else if (subsurfaceStateFindVisibleSubsurfaceAt != null) {
                    iRound = Math.round((iMax - subsurfaceStateFindVisibleSubsurfaceAt.displayX) / subsurfaceStateFindVisibleSubsurfaceAt.pixelsPerLogical);
                    iRound2 = Math.round((iMax2 - subsurfaceStateFindVisibleSubsurfaceAt.displayY) / subsurfaceStateFindVisibleSubsurfaceAt.pixelsPerLogical);
                } else if (childToplevelStateFindVisibleChildToplevelAt != null) {
                    iRound = (Math.round(((iMax - childToplevelStateFindVisibleChildToplevelAt.displayX) * childToplevelStateFindVisibleChildToplevelAt.sourceWidth) / childToplevelStateFindVisibleChildToplevelAt.displayWidth) + childToplevelStateFindVisibleChildToplevelAt.bufferOriginX) / iMax3;
                    iRound2 = (Math.round(((iMax2 - childToplevelStateFindVisibleChildToplevelAt.displayY) * childToplevelStateFindVisibleChildToplevelAt.sourceHeight) / childToplevelStateFindVisibleChildToplevelAt.displayHeight) + childToplevelStateFindVisibleChildToplevelAt.bufferOriginY) / iMax3;
                } else if (this.compactMainPresentation) {
                    iRound = (Math.round(((iMax - this.mainDisplayX) * this.mainSourceWidth) / Math.max(1, this.mainDisplayWidth)) + presentationOriginX()) / iMax3;
                    iRound2 = (Math.round(((iMax2 - this.mainDisplayY) * this.mainSourceHeight) / Math.max(1, this.mainDisplayHeight)) + presentationOriginY()) / iMax3;
                } else {
                    iRound = iPresentationOriginX / iMax3;
                    iRound2 = iPresentationOriginY / iMax3;
                }
                this.pointerSurfaceX = iRound;
                this.pointerSurfaceY = iRound2;
                if (!z) {
                    this.preparedTouchSurfaceId = i2;
                    return true;
                }
                if (i != 0) {
                    localSocket = localSocket3;
                    i3 = iRound2;
                } else {
                    boolean z3 = childToplevelStateFindVisibleChildToplevelAt != null;
                    if (childToplevelStateFindVisibleChildToplevelAt == null) {
                        str = "";
                        localSocket = localSocket3;
                        i4 = iRound2;
                        z2 = z3;
                    } else {
                        localSocket = localSocket3;
                        z2 = z3;
                        i4 = iRound2;
                        str = " display=" + childToplevelStateFindVisibleChildToplevelAt.displayX + "," + childToplevelStateFindVisibleChildToplevelAt.displayY + " " + childToplevelStateFindVisibleChildToplevelAt.displayWidth + "x" + childToplevelStateFindVisibleChildToplevelAt.displayHeight + " source=" + childToplevelStateFindVisibleChildToplevelAt.sourceWidth + "x" + childToplevelStateFindVisibleChildToplevelAt.sourceHeight + " origin=" + childToplevelStateFindVisibleChildToplevelAt.bufferOriginX + "," + childToplevelStateFindVisibleChildToplevelAt.bufferOriginY + " scale=" + iMax3;
                    }
                    StringBuilder sbAppend = new StringBuilder().append("Wayland pointer px=").append(iMax).append(" py=").append(iMax2).append(" target=").append(i2).append(" local=").append(iRound).append(",");
                    i3 = i4;
                    Log.i(MainActivity.TAG, sbAppend.append(i3).append(" child=").append(z2).append(str).toString());
                }
                try {
                    if (this.pointerInside && this.pointerFocusSurfaceId == i2) {
                        localSocket2 = localSocket;
                    } else {
                        if (this.pointerInside && this.pointerFocusSurfaceId != 0) {
                            localSocket2 = localSocket;
                            sendPointerLeave(localSocket2, this.pointerFocusSurfaceId);
                        } else {
                            localSocket2 = localSocket;
                        }
                        sendPointerEnter(localSocket2, i2, iRound, i3);
                        this.pointerInside = true;
                        this.pointerFocusSurfaceId = i2;
                    }
                    if (i == 0) {
                        sendPointerMotion(localSocket2, iRound, i3, j);
                        sendPointerFrame(localSocket2);
                        if (this.keyboardId != 0 && this.keyboardFocusSurfaceId != i2) {
                            sendKeyboardFocus(localSocket2, i2, null);
                        }
                        sendPointerButton(localSocket2, j, true);
                        sendPointerFrame(localSocket2);
                        return true;
                    }
                    int i5 = iRound;
                    if (i == 2) {
                        sendPointerMotion(localSocket2, i5, i3, j);
                        sendPointerFrame(localSocket2);
                        return true;
                    }
                    if (i != 1 && i != 3) {
                        return false;
                    }
                    sendPointerButton(localSocket2, j, false);
                    sendPointerFrame(localSocket2);
                    this.pointerGrabSurfaceId = 0;
                    return true;
                } catch (Exception e2) {
                    appendAsyncEvent("android pointer forwarding failed: " + String.valueOf(e2));
                    this.error = e2.toString();
                    return false;
                }
            }
            return false;
        }

        private ChildToplevelState topVisibleChildToplevel() {
            ChildToplevelState childToplevelState = null;
            for (ChildToplevelState childToplevelState2 : this.childToplevelsByXdg.values()) {
                if (childToplevelState2.visible) {
                    childToplevelState = childToplevelState2;
                }
            }
            return childToplevelState;
        }

        private ChildToplevelState findVisibleChildToplevelAt(int i, int i2) {
            ChildToplevelState childToplevelState = null;
            for (ChildToplevelState childToplevelState2 : this.childToplevelsByXdg.values()) {
                if (childToplevelState2.visible && i >= childToplevelState2.displayX && i2 >= childToplevelState2.displayY && i < childToplevelState2.displayX + childToplevelState2.displayWidth && i2 < childToplevelState2.displayY + childToplevelState2.displayHeight) {
                    int iMax = Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(childToplevelState2.wlSurfaceId), 1).intValue());
                    if (surfaceAcceptsInput(childToplevelState2.wlSurfaceId, (Math.round(((i - childToplevelState2.displayX) * childToplevelState2.sourceWidth) / childToplevelState2.displayWidth) + childToplevelState2.bufferOriginX) / iMax, (Math.round(((i2 - childToplevelState2.displayY) * childToplevelState2.sourceHeight) / childToplevelState2.displayHeight) + childToplevelState2.bufferOriginY) / iMax)) {
                        childToplevelState = childToplevelState2;
                    }
                }
            }
            return childToplevelState;
        }

        private SubsurfaceState findVisibleSubsurfaceAt(int i, int i2) {
            SubsurfaceState subsurfaceState = null;
            for (SubsurfaceState subsurfaceState2 : this.subsurfaces.values()) {
                if (subsurfaceState2.visible && subsurfaceState2.aboveParent && subsurfaceState2.bitmap != null && i >= subsurfaceState2.displayX && i2 >= subsurfaceState2.displayY && i < subsurfaceState2.displayX + subsurfaceState2.displayWidth && i2 < subsurfaceState2.displayY + subsurfaceState2.displayHeight) {
                    if (surfaceAcceptsInput(subsurfaceState2.wlSurfaceId, Math.round((i - subsurfaceState2.displayX) / subsurfaceState2.pixelsPerLogical), Math.round((i2 - subsurfaceState2.displayY) / subsurfaceState2.pixelsPerLogical)) && (subsurfaceState == null || subsurfaceState2.sequence > subsurfaceState.sequence)) {
                        subsurfaceState = subsurfaceState2;
                    }
                }
            }
            return subsurfaceState;
        }

        private PopupState findVisiblePopupAt(int i, int i2) {
            PopupState popupState = null;
            for (PopupState popupState2 : this.popups.values()) {
                if (popupState2.visible && popupState2.grabbed && i >= popupState2.displayX && i2 >= popupState2.displayY && i < popupState2.displayX + popupState2.displayWidth && i2 < popupState2.displayY + popupState2.displayHeight) {
                    if (surfaceAcceptsInput(popupState2.wlSurfaceId, Math.round((i - popupState2.displayX) / popupState2.pixelsPerLogical), Math.round((i2 - popupState2.displayY) / popupState2.pixelsPerLogical)) && (popupState == null || popupState2.sequence > popupState.sequence)) {
                        popupState = popupState2;
                    }
                }
            }
            return popupState;
        }

        boolean handleAndroidTouchEvent(int i, long j) {
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.touchId == 0) {
                return false;
            }
            try {
            } catch (Exception e) {
                appendAsyncEvent("android touch forwarding failed: " + String.valueOf(e));
                this.error = e.toString();
            }
            if (i == 0) {
                int i2 = this.preparedTouchSurfaceId;
                if (i2 == 0) {
                    return false;
                }
                if (this.keyboardId != 0 && this.keyboardFocusSurfaceId != i2) {
                    sendKeyboardFocus(localSocket, i2, null);
                }
                int i3 = this.pointerSerial;
                this.pointerSerial = i3 + 1;
                byte[] bArr = new byte[24];
                putU32(bArr, 0, i3);
                putU32(bArr, 4, (int) j);
                putU32(bArr, 8, i2);
                putU32(bArr, 12, 0);
                putU32(bArr, 16, this.pointerSurfaceX * 256);
                putU32(bArr, 20, this.pointerSurfaceY * 256);
                writeMessage(localSocket, this.touchId, 0, bArr);
                writeMessage(localSocket, this.touchId, 3, new byte[0]);
                rememberInputSerial(i3);
                this.touchFocusSurfaceId = i2;
                appendAsyncEvent("android->wayland wl_touch.down surface=" + i2 + " x=" + this.pointerSurfaceX + " y=" + this.pointerSurfaceY);
                return true;
            }
            if (i == 1 && this.touchFocusSurfaceId != 0) {
                byte[] bArr2 = new byte[12];
                int i4 = this.pointerSerial;
                this.pointerSerial = i4 + 1;
                putU32(bArr2, 0, i4);
                putU32(bArr2, 4, (int) j);
                putU32(bArr2, 8, 0);
                writeMessage(localSocket, this.touchId, 1, bArr2);
                writeMessage(localSocket, this.touchId, 3, new byte[0]);
                this.touchFocusSurfaceId = 0;
                appendAsyncEvent("android->wayland wl_touch.up");
                return true;
            }
            if (i == 3 && this.touchFocusSurfaceId != 0) {
                writeMessage(localSocket, this.touchId, 4, new byte[0]);
                this.touchFocusSurfaceId = 0;
                appendAsyncEvent("android->wayland wl_touch.cancel");
                return true;
            }
            return false;
        }

        private void sendPointerEnter(LocalSocket localSocket, int i, int i2, int i3) throws Exception {
            byte[] bArr = new byte[16];
            int i4 = this.pointerSerial;
            this.pointerSerial = i4 + 1;
            putU32(bArr, 0, i4);
            putU32(bArr, 4, i);
            putU32(bArr, 8, i2 * 256);
            putU32(bArr, 12, i3 * 256);
            writeMessage(localSocket, this.pointerId, 0, bArr);
            this.pointerEventsSent = true;
            appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=0 wl_pointer.enter surface=" + i + " x=" + i2 + " y=" + i3);
        }

        private void sendPointerLeave(LocalSocket localSocket, int i) throws Exception {
            byte[] bArr = new byte[8];
            int i2 = this.pointerSerial;
            this.pointerSerial = i2 + 1;
            putU32(bArr, 0, i2);
            putU32(bArr, 4, i);
            writeMessage(localSocket, this.pointerId, 1, bArr);
            appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=1 wl_pointer.leave surface=" + i);
        }

        private void sendPointerMotion(LocalSocket localSocket, int i, int i2, long j) throws Exception {
            byte[] bArr = new byte[12];
            putU32(bArr, 0, (int) j);
            putU32(bArr, 4, i * 256);
            putU32(bArr, 8, i2 * 256);
            writeMessage(localSocket, this.pointerId, 2, bArr);
            this.pointerEventsSent = true;
            this.pointerMotionEventsSent++;
            appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=2 wl_pointer.motion x=" + i + " y=" + i2);
        }

        private void sendPointerButton(LocalSocket localSocket, long j, boolean z) throws Exception {
            byte[] bArr = new byte[16];
            int i = this.pointerSerial;
            this.pointerSerial = i + 1;
            putU32(bArr, 0, i);
            putU32(bArr, 4, (int) j);
            putU32(bArr, 8, 272);
            putU32(bArr, 12, z ? 1 : 0);
            writeMessage(localSocket, this.pointerId, 3, bArr);
            this.pointerEventsSent = true;
            this.pointerButtonEventsSent++;
            if (z) {
                rememberInputSerial(i);
            }
            appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=3 wl_pointer.button left " + (z ? "pressed" : "released"));
        }

        private void sendPointerFrame(LocalSocket localSocket) throws Exception {
            writeMessage(localSocket, this.pointerId, 5, new byte[0]);
            appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=5 wl_pointer.frame");
        }

        private void appendAsyncEvent(String str) {
            synchronized (this.eventLock) {
                if (this.eventLog != null) {
                    this.eventLog.append(str).append("\n");
                }
            }
        }

        private synchronized void rememberInputSerial(int i) {
            this.lastInputSerial = i;
            this.recentInputSerials.addLast(Integer.valueOf(i));
            while (this.recentInputSerials.size() > 32) {
                this.recentInputSerials.removeFirst();
            }
        }

        private synchronized boolean isKnownInputSerial(int i) {
            return this.recentInputSerials.contains(Integer.valueOf(i));
        }

        synchronized void publishAndroidClipboard() {
            LocalSocket localSocket = this.connectedClient;
            if (localSocket != null && this.dataDeviceId != 0) {
                StringBuilder sb = new StringBuilder();
                try {
                    sendAndroidClipboardOffer(localSocket, sb);
                    String strTrim = sb.toString().trim();
                    if (!strTrim.isEmpty()) {
                        appendAsyncEvent(strTrim);
                    }
                } catch (Exception e) {
                    appendAsyncEvent("android clipboard offer failed: " + String.valueOf(e));
                    this.error = e.toString();
                }
            }
        }

        private void sendAndroidClipboardOffer(LocalSocket localSocket, StringBuilder sb) throws Exception {
            CharSequence charSequenceCoerceToText;
            MainActivity mainActivity = (MainActivity) MainActivity.currentActivity.get();
            if (mainActivity == null || this.dataDeviceId == 0) {
                return;
            }
            ClipboardManager clipboardManager = (ClipboardManager) mainActivity.getSystemService("clipboard");
            if (!clipboardManager.hasPrimaryClip() || clipboardManager.getPrimaryClip() == null || clipboardManager.getPrimaryClip().getItemCount() == 0 || (charSequenceCoerceToText = clipboardManager.getPrimaryClip().getItemAt(0).coerceToText(mainActivity)) == null) {
                return;
            }
            String string = charSequenceCoerceToText.toString();
            if (string.equals(this.lastOfferedAndroidClipboardText)) {
                return;
            }
            this.lastOfferedAndroidClipboardText = string;
            int i = this.nextServerObjectId;
            this.nextServerObjectId = i + 1;
            this.androidClipboardOffers.put(Integer.valueOf(i), string);
            byte[] bArr = new byte[4];
            putU32(bArr, 0, i);
            writeMessage(localSocket, this.dataDeviceId, 0, bArr);
            writeMessage(localSocket, i, 0, stringPayload("text/plain"));
            writeMessage(localSocket, i, 0, stringPayload("text/plain;charset=utf-8"));
            byte[] bArr2 = new byte[4];
            putU32(bArr2, 0, i);
            writeMessage(localSocket, this.dataDeviceId, 5, bArr2);
            sb.append("server->client wl_data_device.data_offer id=").append(i).append("\n").append("server->client wl_data_offer.offer text MIME types\n").append("server->client wl_data_device.selection offer=").append(i).append("\n");
        }

        private void requestClipboardSourceData(LocalSocket localSocket, ClipboardSourceState clipboardSourceState, StringBuilder sb) throws Exception {
            String strPreferredTextMime = clipboardSourceState.preferredTextMime();
            if (strPreferredTextMime == null) {
                sb.append("clipboard source has no supported text MIME\n");
                return;
            }
            ParcelFileDescriptor[] parcelFileDescriptorArrCreatePipe = ParcelFileDescriptor.createPipe();
            try {
                try {
                    writeMessageWithFd(localSocket, clipboardSourceState.id, 1, stringPayload(strPreferredTextMime), parcelFileDescriptorArrCreatePipe[1].getFileDescriptor());
                    parcelFileDescriptorArrCreatePipe[1].close();
                    sb.append("server->client object=").append(clipboardSourceState.id).append(" opcode=1 wl_data_source.send mime=").append(strPreferredTextMime).append("\n");
                    final ParcelFileDescriptor parcelFileDescriptor = parcelFileDescriptorArrCreatePipe[0];
                    new Thread(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer$$ExternalSyntheticLambda3
                        @Override // java.lang.Runnable
                        public final void run() {
                            this.f$0.lambda$requestClipboardSourceData$0(parcelFileDescriptor);
                        }
                    }, "archphene-clipboard-read").start();
                } catch (Throwable th) {
                    th = th;
                    Throwable th2 = th;
                    parcelFileDescriptorArrCreatePipe[1].close();
                    throw th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* JADX INFO: renamed from: readClipboardPipe, reason: merged with bridge method [inline-methods] */
        public void lambda$requestClipboardSourceData$0(ParcelFileDescriptor parcelFileDescriptor) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream autoCloseInputStream = new ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptor);
                try {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    try {
                        byte[] bArr = new byte[4096];
                        int i = 0;
                        while (true) {
                            int i2 = autoCloseInputStream.read(bArr);
                            if (i2 != -1) {
                                i += i2;
                                if (i > 1048576) {
                                    throw new IOException("clipboard payload exceeds 1 MiB");
                                }
                                byteArrayOutputStream.write(bArr, 0, i2);
                            } else {
                                if (i == 0) {
                                    appendAsyncEvent("wayland->android clipboard empty payload ignored");
                                    byteArrayOutputStream.close();
                                    autoCloseInputStream.close();
                                    return;
                                }
                                final String string = byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
                                final MainActivity mainActivity = (MainActivity) MainActivity.currentActivity.get();
                                if (mainActivity != null) {
                                    mainActivity.runOnUiThread(new Runnable() { // from class: org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer$$ExternalSyntheticLambda2
                                        @Override // java.lang.Runnable
                                        public final void run() {
                                            mainActivity.setBridgeClipboardText(string);
                                        }
                                    });
                                }
                                appendAsyncEvent("wayland->android clipboard bytes=" + i);
                                byteArrayOutputStream.close();
                                autoCloseInputStream.close();
                                return;
                            }
                        }
                    } finally {
                    }
                } finally {
                }
            } catch (Exception e) {
                appendAsyncEvent("wayland->android clipboard failed: " + String.valueOf(e));
            }
        }

        private void sendPointerEvents(LocalSocket localSocket, StringBuilder sb) throws Exception {
            int iMax = Math.max(1, this.configureWidth / 2) * 256;
            int iMax2 = Math.max(1, this.configureHeight / 2) * 256;
            byte[] bArr = new byte[16];
            putU32(bArr, 0, 100);
            putU32(bArr, 4, this.surfaceId);
            putU32(bArr, 8, iMax);
            putU32(bArr, 12, iMax2);
            writeMessage(localSocket, this.pointerId, 0, bArr);
            byte[] bArr2 = new byte[12];
            putU32(bArr2, 0, 16);
            putU32(bArr2, 4, iMax);
            putU32(bArr2, 8, iMax2);
            writeMessage(localSocket, this.pointerId, 2, bArr2);
            byte[] bArr3 = new byte[16];
            putU32(bArr3, 0, 101);
            putU32(bArr3, 4, 17);
            putU32(bArr3, 8, 272);
            putU32(bArr3, 12, 1);
            writeMessage(localSocket, this.pointerId, 3, bArr3);
            this.pointerEventsSent = true;
            sb.append("server->client object=").append(this.pointerId).append(" opcode=0 wl_pointer.enter surface=").append(this.surfaceId).append(" x=").append(this.configureWidth / 2).append(" y=").append(this.configureHeight / 2).append("\n");
            sb.append("server->client object=").append(this.pointerId).append(" opcode=2 wl_pointer.motion x=").append(this.configureWidth / 2).append(" y=").append(this.configureHeight / 2).append("\n");
            sb.append("server->client object=").append(this.pointerId).append(" opcode=3 wl_pointer.button left pressed\n");
        }

        private void sendXdgConfigure(LocalSocket localSocket, StringBuilder sb) throws Exception {
            this.xdgConfigureSerial = Math.max(42, this.xdgConfigureSerial + 1);
            if (this.xdgToplevelId != 0) {
                byte[] bArr = new byte[16];
                putU32(bArr, 0, this.configureWidth);
                putU32(bArr, 4, this.configureHeight);
                putU32(bArr, 8, 4);
                putU32(bArr, 12, 4);
                writeMessage(localSocket, this.xdgToplevelId, 0, bArr);
                sb.append("server->client object=").append(this.xdgToplevelId).append(" opcode=0 xdg_toplevel.configure width=").append(this.configureWidth).append(" height=").append(this.configureHeight).append(" states=activated\n");
            }
            byte[] bArr2 = new byte[4];
            putU32(bArr2, 0, this.xdgConfigureSerial);
            writeMessage(localSocket, this.xdgSurfaceId, 0, bArr2);
            this.xdgConfigureSent = true;
            sb.append("server->client object=").append(this.xdgSurfaceId).append(" opcode=0 xdg_surface.configure serial=").append(this.xdgConfigureSerial).append("\n");
        }

        private void sendOutputEvents(LocalSocket localSocket, int i, StringBuilder sb) throws Exception {
            byte[] bArrStringPayload = stringPayload("Archphene");
            byte[] bArrStringPayload2 = stringPayload("Android Display");
            byte[] bArr = new byte[bArrStringPayload.length + 20 + bArrStringPayload2.length + 4];
            putU32(bArr, 0, 0);
            putU32(bArr, 4, 0);
            putU32(bArr, 8, 68);
            putU32(bArr, 12, 151);
            putU32(bArr, 16, 0);
            System.arraycopy(bArrStringPayload, 0, bArr, 20, bArrStringPayload.length);
            System.arraycopy(bArrStringPayload2, 0, bArr, bArrStringPayload.length + 20, bArrStringPayload2.length);
            putU32(bArr, bArrStringPayload.length + 20 + bArrStringPayload2.length, 0);
            writeMessage(localSocket, i, 0, bArr);
            byte[] bArr2 = new byte[16];
            putU32(bArr2, 0, 1);
            putU32(bArr2, 4, this.outputWidth);
            putU32(bArr2, 8, this.outputHeight);
            putU32(bArr2, 12, 60000);
            writeMessage(localSocket, i, 1, bArr2);
            sendU32Event(localSocket, i, 3, this.outputScale, sb, "wl_output.scale");
            writeMessage(localSocket, i, 2, new byte[0]);
            this.outputDoneSent = true;
            sb.append("server->client object=").append(i).append(" opcode=0 wl_output.geometry make=Archphene model=Android Display\n");
            sb.append("server->client object=").append(i).append(" opcode=1 wl_output.mode current width=").append(this.outputWidth).append(" height=").append(this.outputHeight).append(" refresh=60000\n");
            sb.append("server->client object=").append(i).append(" opcode=2 wl_output.done\n");
        }

        private void sendSeatEvents(LocalSocket localSocket, int i, StringBuilder sb) throws Exception {
            writeMessage(localSocket, i, 1, stringPayload("default"));
            sendU32Event(localSocket, i, 0, 7, sb, "wl_seat.capabilities pointer keyboard touch");
            this.seatCapabilitiesSent = true;
            sb.append("server->client object=").append(i).append(" opcode=1 wl_seat.name default\n");
        }

        private void sendRegistryGlobal(LocalSocket localSocket, int i, int i2, String str, int i3, StringBuilder sb) throws Exception {
            byte[] bytes = (str + "\u0000").getBytes(StandardCharsets.UTF_8);
            int length = ((bytes.length + 3) & (-4)) + 8;
            byte[] bArr = new byte[length + 4];
            putU32(bArr, 0, i2);
            putU32(bArr, 4, bytes.length);
            System.arraycopy(bytes, 0, bArr, 8, bytes.length);
            putU32(bArr, length, i3);
            writeMessage(localSocket, i, 0, bArr);
            this.registryGlobalCount++;
            sb.append("server->client object=").append(i).append(" opcode=0 wl_registry.global name=").append(i2).append(" interface=").append(str).append(" version=").append(i3).append("\n");
        }

        private void sendCallbackDone(LocalSocket localSocket, int i, int i2, StringBuilder sb) throws Exception {
            sendU32Event(localSocket, i, 0, i2, sb, "wl_callback.done");
            this.callbackDoneSent = true;
        }

        private void sendU32Event(LocalSocket localSocket, int i, int i2, int i3, StringBuilder sb, String str) throws Exception {
            byte[] bArr = new byte[4];
            putU32(bArr, 0, i3);
            writeMessage(localSocket, i, i2, bArr);
            sb.append("server->client object=").append(i).append(" opcode=").append(i2).append(" ").append(str).append(" value=").append(i3).append("\n");
        }

        private static byte[] stringPayload(String str) {
            byte[] bytes = (str + "\u0000").getBytes(StandardCharsets.UTF_8);
            byte[] bArr = new byte[((bytes.length + 3) & (-4)) + 4];
            putU32(bArr, 0, bytes.length);
            System.arraycopy(bytes, 0, bArr, 4, bytes.length);
            return bArr;
        }

        private void writeMessageWithFd(LocalSocket localSocket, int i, int i2, byte[] bArr, FileDescriptor fileDescriptor) throws Exception {
            int length = bArr.length + 8;
            byte[] bArr2 = new byte[length];
            putU32(bArr2, 0, i);
            putU32(bArr2, 4, (length << 16) | (i2 & 65535));
            System.arraycopy(bArr, 0, bArr2, 8, bArr.length);
            synchronized (this.writeLock) {
                localSocket.setFileDescriptorsForSend(new FileDescriptor[]{fileDescriptor});
                try {
                    OutputStream outputStream = localSocket.getOutputStream();
                    outputStream.write(bArr2);
                    outputStream.flush();
                } finally {
                    localSocket.setFileDescriptorsForSend(null);
                }
            }
        }

        private void writeMessage(LocalSocket localSocket, int i, int i2, byte[] bArr) throws Exception {
            int length = bArr.length + 8;
            byte[] bArr2 = new byte[length];
            putU32(bArr2, 0, i);
            putU32(bArr2, 4, (length << 16) | (i2 & 65535));
            System.arraycopy(bArr, 0, bArr2, 8, bArr.length);
            synchronized (this.writeLock) {
                OutputStream outputStream = localSocket.getOutputStream();
                outputStream.write(bArr2);
                outputStream.flush();
            }
        }

        private PopupState findPopupByWlSurface(int i) {
            for (PopupState popupState : this.popups.values()) {
                if (popupState.wlSurfaceId == i) {
                    return popupState;
                }
            }
            return null;
        }

        private void sendMainToplevelActivation(LocalSocket localSocket, boolean z, StringBuilder sb) throws Exception {
            if (this.xdgToplevelId == 0 || this.xdgSurfaceId == 0) {
                return;
            }
            byte[] bArr = new byte[z ? 16 : 12];
            putU32(bArr, 0, this.configureWidth);
            putU32(bArr, 4, this.configureHeight);
            putU32(bArr, 8, z ? 4 : 0);
            if (z) {
                putU32(bArr, 12, 4);
            }
            writeMessage(localSocket, this.xdgToplevelId, 0, bArr);
            byte[] bArr2 = new byte[4];
            int i = this.xdgConfigureSerial + 1;
            this.xdgConfigureSerial = i;
            putU32(bArr2, 0, i);
            writeMessage(localSocket, this.xdgSurfaceId, 0, bArr2);
            sb.append("server->client main xdg_toplevel.configure activated=").append(z).append(" serial=").append(i).append("\n");
        }

        private void sendChildToplevelConfigure(LocalSocket localSocket, ChildToplevelState childToplevelState, StringBuilder sb) throws Exception {
            int i = this.xdgConfigureSerial + 1;
            this.xdgConfigureSerial = i;
            childToplevelState.configureSerial = i;
            byte[] bArr = new byte[16];
            int iMax = childToplevelState.isFileDialog() ? this.configureWidth : 0;
            int iMax2 = childToplevelState.isFileDialog() ? this.configureHeight : 0;
            if (iMax > 0) {
                iMax = Math.max(iMax, childToplevelState.minWidth);
                if (childToplevelState.maxWidth > 0) {
                    iMax = Math.min(iMax, childToplevelState.maxWidth);
                }
            }
            if (iMax2 > 0) {
                iMax2 = Math.max(iMax2, childToplevelState.minHeight);
                if (childToplevelState.maxHeight > 0) {
                    iMax2 = Math.min(iMax2, childToplevelState.maxHeight);
                }
            }
            putU32(bArr, 0, iMax);
            putU32(bArr, 4, iMax2);
            putU32(bArr, 8, 4);
            putU32(bArr, 12, 4);
            writeMessage(localSocket, childToplevelState.toplevelId, 0, bArr);
            byte[] bArr2 = new byte[4];
            putU32(bArr2, 0, childToplevelState.configureSerial);
            writeMessage(localSocket, childToplevelState.xdgSurfaceId, 0, bArr2);
            childToplevelState.configureSent = true;
            sb.append("server->client child xdg_toplevel.configure size=").append(iMax).append("x").append(iMax2).append(" serial=").append(childToplevelState.configureSerial).append("\n");
        }

        private static String sampledOpaqueCoverage(Bitmap bitmap) {
            int iMax = Math.max(1, bitmap.getWidth() / 32);
            int iMax2 = Math.max(1, bitmap.getHeight() / 32);
            int i = 0;
            int i2 = 0;
            for (int i3 = 0; i3 < bitmap.getHeight(); i3 += iMax2) {
                for (int i4 = 0; i4 < bitmap.getWidth(); i4 += iMax) {
                    int pixel = bitmap.getPixel(i4, i3);
                    i2++;
                    if (((-16777216) & pixel) != 0 && (pixel & 16777215) != 0) {
                        i++;
                    }
                }
            }
            return i + "/" + i2;
        }

        private Bitmap readShmBitmap(int i) throws Exception {
            ShmBufferState shmBufferState = this.shmBuffers.get(Integer.valueOf(i));
            if (shmBufferState == null) {
                throw new IllegalStateException("child toplevel commit with unknown wl_buffer " + i);
            }
            byte[] exactFromFd = readExactFromFd(shmBufferState.pool.fd, shmBufferState.offset, shmBufferState.stride * shmBufferState.height);
            int[] iArr = new int[shmBufferState.width * shmBufferState.height];
            for (int i2 = 0; i2 < shmBufferState.height; i2++) {
                int i3 = shmBufferState.stride * i2;
                for (int i4 = 0; i4 < shmBufferState.width; i4++) {
                    int i5 = (i4 * 4) + i3;
                    int i6 = exactFromFd[i5] & 255;
                    int i7 = exactFromFd[i5 + 1] & 255;
                    int i8 = exactFromFd[i5 + 2] & 255;
                    int i9 = shmBufferState.format == 0 ? exactFromFd[i5 + 3] & 255 : 255;
                    if (i9 < 255) {
                        int i10 = (255 - i9) * 255;
                        i8 = ((i8 * i9) + i10) / 255;
                        i7 = ((i7 * i9) + i10) / 255;
                        i6 = ((i6 * i9) + i10) / 255;
                    }
                    iArr[(shmBufferState.width * i2) + i4] = i6 | (i8 << 16) | (-16777216) | (i7 << 8);
                }
            }
            return Bitmap.createBitmap(iArr, shmBufferState.width, shmBufferState.height, Bitmap.Config.ARGB_8888);
        }

        private void constrainPopup(PopupState popupState) {
            PositionerState positionerState = popupState.positioner;
            popupState.applyPositioner(positionerState);
            int i = popupState.parentXdgSurfaceId;
            PopupState popupState2 = this.popupsByXdgSurface.get(Integer.valueOf(i));
            int i2 = 0;
            int i3 = 0;
            while (popupState2 != null) {
                i2 += popupState2.configureX;
                i3 += popupState2.configureY;
                i = popupState2.parentXdgSurfaceId;
                popupState2 = this.popupsByXdgSurface.get(Integer.valueOf(i));
            }
            int i4 = this.configureWidth;
            int i5 = this.configureHeight;
            ChildToplevelState childToplevelState = this.childToplevelsByXdg.get(Integer.valueOf(i));
            if (childToplevelState != null && childToplevelState.displayWidth > 1 && childToplevelState.displayHeight > 1) {
                float fMax = Math.max(0.01f, Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(childToplevelState.wlSurfaceId), 1).intValue()) * (childToplevelState.displayWidth / Math.max(1, childToplevelState.sourceWidth)));
                int iMax = Math.max(1, Math.round(childToplevelState.displayWidth / fMax));
                int iMax2 = Math.max(1, Math.round(childToplevelState.displayHeight / fMax));
                i4 = iMax;
                i5 = iMax2;
            }
            int i6 = -i2;
            int i7 = -i3;
            int i8 = i6 + i4;
            int i9 = i7 + i5;
            int i10 = positionerState.constraintAdjustment;
            if ((popupState.configureX < i6 || popupState.configureX + popupState.width > i8) && (i10 & 4) != 0) {
                PositionerState positionerState2 = new PositionerState(positionerState);
                positionerState2.anchor = flipHorizontal(positionerState2.anchor);
                positionerState2.gravity = flipHorizontal(positionerState2.gravity);
                int iPositionerX = positionerX(positionerState2, popupState.width);
                if (overflow(iPositionerX, popupState.width, i6, i8) < overflow(popupState.configureX, popupState.width, i6, i8)) {
                    popupState.configureX = iPositionerX;
                }
            }
            if ((popupState.configureY < i7 || popupState.configureY + popupState.height > i9) && (i10 & 8) != 0) {
                PositionerState positionerState3 = new PositionerState(positionerState);
                positionerState3.anchor = flipVertical(positionerState3.anchor);
                positionerState3.gravity = flipVertical(positionerState3.gravity);
                int iPositionerY = positionerY(positionerState3, popupState.height);
                if (overflow(iPositionerY, popupState.height, i7, i9) < overflow(popupState.configureY, popupState.height, i7, i9)) {
                    popupState.configureY = iPositionerY;
                }
            }
            if ((i10 & 1) != 0) {
                popupState.configureX = clampPosition(popupState.configureX, popupState.width, i6, i8);
            }
            if ((i10 & 2) != 0) {
                popupState.configureY = clampPosition(popupState.configureY, popupState.height, i7, i9);
            }
            if ((i10 & 16) != 0 && popupState.width > i4) {
                popupState.width = i4;
                popupState.configureX = i6;
            }
            if ((i10 & 32) != 0 && popupState.height > i5) {
                popupState.height = i5;
                popupState.configureY = i7;
            }
        }

        private static int positionerX(PositionerState positionerState, int i) {
            return PopupState.gravityX(PopupState.anchorPointX(positionerState), i, positionerState.gravity) + positionerState.offsetX;
        }

        private static int positionerY(PositionerState positionerState, int i) {
            return PopupState.gravityY(PopupState.anchorPointY(positionerState), i, positionerState.gravity) + positionerState.offsetY;
        }

        private static int overflow(int i, int i2, int i3, int i4) {
            return Math.max(0, i3 - i) + Math.max(0, (i + i2) - i4);
        }

        private static int clampPosition(int i, int i2, int i3, int i4) {
            return i2 >= i4 - i3 ? i3 : Math.max(i3, Math.min(i4 - i2, i));
        }

        private static int flipHorizontal(int i) {
            switch (i) {
                case 3:
                    return 4;
                case 4:
                    return 3;
                case 5:
                    return 7;
                case 6:
                    return 8;
                case 7:
                    return 5;
                case 8:
                    return 6;
                default:
                    return i;
            }
        }

        private static int flipVertical(int i) {
            switch (i) {
                case 1:
                    return 2;
                case 2:
                    return 1;
                case 3:
                case 4:
                default:
                    return i;
                case 5:
                    return 6;
                case 6:
                    return 5;
                case 7:
                    return 8;
                case 8:
                    return 7;
            }
        }

        private void sendPopupConfigure(LocalSocket localSocket, PopupState popupState, StringBuilder sb) throws Exception {
            int i = this.xdgConfigureSerial + 1;
            this.xdgConfigureSerial = i;
            popupState.configureSerial = i;
            byte[] bArr = new byte[16];
            putU32(bArr, 0, popupState.configureX);
            putU32(bArr, 4, popupState.configureY);
            putU32(bArr, 8, popupState.width);
            putU32(bArr, 12, popupState.height);
            writeMessage(localSocket, popupState.popupId, 0, bArr);
            byte[] bArr2 = new byte[4];
            putU32(bArr2, 0, popupState.configureSerial);
            writeMessage(localSocket, popupState.xdgSurfaceId, 0, bArr2);
            popupState.configureSent = true;
            sb.append("server->client object=").append(popupState.popupId).append(" opcode=0 xdg_popup.configure x=").append(popupState.configureX).append(" y=").append(popupState.configureY).append(" w=").append(popupState.width).append(" h=").append(popupState.height).append("\n");
            sb.append("server->client object=").append(popupState.xdgSurfaceId).append(" opcode=0 xdg_surface.configure serial=").append(popupState.configureSerial).append("\n");
        }

        private void commitPopupBuffer(PopupState popupState, int i) throws Exception {
            ShmBufferState shmBufferState = this.shmBuffers.get(Integer.valueOf(i));
            if (shmBufferState == null) {
                throw new IllegalStateException("popup commit with unknown wl_buffer " + i);
            }
            long j = ((long) shmBufferState.offset) + (((long) shmBufferState.stride) * ((long) shmBufferState.height));
            if (shmBufferState.width <= 0 || shmBufferState.height <= 0 || shmBufferState.stride < shmBufferState.width * 4 || j > shmBufferState.pool.size) {
                throw new IllegalStateException("invalid popup buffer state before commit");
            }
            byte[] exactFromFd = readExactFromFd(shmBufferState.pool.fd, shmBufferState.offset, shmBufferState.stride * shmBufferState.height);
            int[] iArr = new int[shmBufferState.width * shmBufferState.height];
            for (int i2 = 0; i2 < shmBufferState.height; i2++) {
                int i3 = shmBufferState.stride * i2;
                for (int i4 = 0; i4 < shmBufferState.width; i4++) {
                    int i5 = (i4 * 4) + i3;
                    int i6 = 255;
                    int i7 = exactFromFd[i5] & 255;
                    int i8 = exactFromFd[i5 + 1] & 255;
                    int i9 = exactFromFd[i5 + 2] & 255;
                    if (shmBufferState.format == 0) {
                        i6 = 255 & exactFromFd[i5 + 3];
                    }
                    iArr[(shmBufferState.width * i2) + i4] = i7 | (i6 << 24) | (i9 << 16) | (i8 << 8);
                }
            }
            popupState.bitmap = Bitmap.createBitmap(iArr, shmBufferState.width, shmBufferState.height, Bitmap.Config.ARGB_8888);
            Log.i(MainActivity.TAG, "Popup buffer commit surface=" + popupState.wlSurfaceId + " buffer=" + i + " size=" + shmBufferState.width + "x" + shmBufferState.height + " configure=" + popupState.configureX + "," + popupState.configureY + " " + popupState.width + "x" + popupState.height);
            popupState.visible = true;
            composeSurfaceTree();
            if (popupState.grabbed && this.keyboardId != 0 && this.keyboardFocusSurfaceId != popupState.wlSurfaceId) {
                sendKeyboardFocus(this.connectedClient, popupState.wlSurfaceId, null);
            }
        }

        private void commitSubsurfaceBuffer(SubsurfaceState subsurfaceState, int i) throws Exception {
            ShmBufferState shmBufferState = this.shmBuffers.get(Integer.valueOf(i));
            if (shmBufferState == null) {
                throw new IllegalStateException("subsurface commit with unknown wl_buffer " + i);
            }
            long j = ((long) shmBufferState.offset) + (((long) shmBufferState.stride) * ((long) shmBufferState.height));
            if (shmBufferState.width <= 0 || shmBufferState.height <= 0 || shmBufferState.stride < shmBufferState.width * 4 || j > shmBufferState.pool.size) {
                throw new IllegalStateException("invalid subsurface buffer state before commit");
            }
            byte[] exactFromFd = readExactFromFd(shmBufferState.pool.fd, shmBufferState.offset, shmBufferState.stride * shmBufferState.height);
            int[] iArr = new int[shmBufferState.width * shmBufferState.height];
            for (int i2 = 0; i2 < shmBufferState.height; i2++) {
                int i3 = shmBufferState.stride * i2;
                for (int i4 = 0; i4 < shmBufferState.width; i4++) {
                    int i5 = (i4 * 4) + i3;
                    int i6 = 255;
                    int i7 = exactFromFd[i5] & 255;
                    int i8 = exactFromFd[i5 + 1] & 255;
                    int i9 = exactFromFd[i5 + 2] & 255;
                    if (shmBufferState.format == 0) {
                        i6 = 255 & exactFromFd[i5 + 3];
                    }
                    iArr[(shmBufferState.width * i2) + i4] = i7 | (i6 << 24) | (i9 << 16) | (i8 << 8);
                }
            }
            subsurfaceState.bitmap = Bitmap.createBitmap(iArr, shmBufferState.width, shmBufferState.height, Bitmap.Config.ARGB_8888);
            Log.i(MainActivity.TAG, "Subsurface buffer commit surface=" + subsurfaceState.wlSurfaceId + " parent=" + subsurfaceState.parentSurfaceId + " buffer=" + i + " size=" + shmBufferState.width + "x" + shmBufferState.height + " at=" + subsurfaceState.x + "," + subsurfaceState.y);
            subsurfaceState.visible = true;
            composeSurfaceTree();
        }

        private void composeSurfaceTree() {
            Bitmap bitmapCopy;
            Canvas canvas;
            int iMax;
            int iMax2;
            Bitmap bitmap = this.mainBitmap;
            if (bitmap == null) {
                return;
            }
            int iPresentationOriginX = presentationOriginX();
            int iPresentationOriginY = presentationOriginY();
            WindowGeometry windowGeometry = this.windowGeometries.get(Integer.valueOf(this.xdgSurfaceId));
            int i = 1;
            int iMax3 = Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(this.surfaceId), 1).intValue());
            Bitmap bitmapCreateBitmap = Bitmap.createBitmap(bitmap, iPresentationOriginX, iPresentationOriginY, Math.max(1, windowGeometry == null ? bitmap.getWidth() : Math.min(bitmap.getWidth() - iPresentationOriginX, windowGeometry.width * iMax3)), Math.max(1, windowGeometry == null ? bitmap.getHeight() : Math.min(bitmap.getHeight() - iPresentationOriginY, windowGeometry.height * iMax3)));
            this.mainSourceWidth = bitmapCreateBitmap.getWidth();
            this.mainSourceHeight = bitmapCreateBitmap.getHeight();
            this.compactMainPresentation = ((float) this.mainSourceWidth) < ((float) this.outputWidth) * 0.85f || ((float) this.mainSourceHeight) < ((float) this.outputHeight) * 0.85f;
            if (this.compactMainPresentation) {
                bitmapCopy = Bitmap.createBitmap(this.outputWidth, this.outputHeight, Bitmap.Config.ARGB_8888);
                Canvas canvas2 = new Canvas(bitmapCopy);
                canvas2.drawColor(-1);
                float fMin = Math.min(1.0f, Math.min(this.outputWidth / this.mainSourceWidth, this.outputHeight / this.mainSourceHeight));
                this.mainDisplayWidth = Math.max(1, Math.round(this.mainSourceWidth * fMin));
                this.mainDisplayHeight = Math.max(1, Math.round(this.mainSourceHeight * fMin));
                this.mainDisplayX = (this.outputWidth - this.mainDisplayWidth) / 2;
                this.mainDisplayY = (this.outputHeight - this.mainDisplayHeight) / 2;
                canvas2.drawBitmap(bitmapCreateBitmap, (Rect) null, new Rect(this.mainDisplayX, this.mainDisplayY, this.mainDisplayX + this.mainDisplayWidth, this.mainDisplayY + this.mainDisplayHeight), (Paint) null);
                canvas = canvas2;
                drawSubsurfacesForParent(canvas, this.surfaceId, this.mainDisplayX - (iPresentationOriginX * fMin), this.mainDisplayY - (iPresentationOriginY * fMin), iMax3 * fMin);
            } else {
                bitmapCopy = bitmapCreateBitmap.copy(Bitmap.Config.ARGB_8888, true);
                canvas = new Canvas(bitmapCopy);
                this.mainDisplayX = 0;
                this.mainDisplayY = 0;
                this.mainDisplayWidth = bitmapCopy.getWidth();
                this.mainDisplayHeight = bitmapCopy.getHeight();
                drawSubsurfacesForParent(canvas, this.surfaceId, -iPresentationOriginX, -iPresentationOriginY, this.outputScale);
            }
            for (ChildToplevelState childToplevelState : new ArrayList(this.childToplevelsByXdg.values())) {
                if (childToplevelState.visible && childToplevelState.bitmap != null) {
                    canvas.drawColor(1711276032);
                    WindowGeometry windowGeometry2 = this.windowGeometries.get(Integer.valueOf(childToplevelState.xdgSurfaceId));
                    int iMax4 = Math.max(i, this.surfaceBufferScales.getOrDefault(Integer.valueOf(childToplevelState.wlSurfaceId), Integer.valueOf(i)).intValue());
                    if (windowGeometry2 == null) {
                        iMax2 = 0;
                        iMax = 0;
                    } else {
                        iMax = 0;
                        iMax2 = Math.max(0, windowGeometry2.x * iMax4);
                    }
                    if (windowGeometry2 != null) {
                        iMax = Math.max(iMax, windowGeometry2.y * iMax4);
                    }
                    int width = childToplevelState.bitmap.getWidth();
                    if (windowGeometry2 != null) {
                        width = Math.min(width - iMax2, windowGeometry2.width * iMax4);
                    }
                    Bitmap bitmapCreateBitmap2 = Bitmap.createBitmap(childToplevelState.bitmap, iMax2, iMax, Math.max(i, width), Math.max(i, windowGeometry2 == null ? childToplevelState.bitmap.getHeight() : Math.min(childToplevelState.bitmap.getHeight() - iMax, windowGeometry2.height * iMax4)));
                    childToplevelState.bufferOriginX = iMax2;
                    childToplevelState.bufferOriginY = iMax;
                    childToplevelState.sourceWidth = bitmapCreateBitmap2.getWidth();
                    childToplevelState.sourceHeight = bitmapCreateBitmap2.getHeight();
                    float fMin2 = Math.min(1.0f, Math.min(bitmapCopy.getWidth() / childToplevelState.sourceWidth, bitmapCopy.getHeight() / childToplevelState.sourceHeight));
                    childToplevelState.displayWidth = Math.max(i, Math.round(childToplevelState.sourceWidth * fMin2));
                    childToplevelState.displayHeight = Math.max(i, Math.round(childToplevelState.sourceHeight * fMin2));
                    childToplevelState.displayX = Math.max(0, (bitmapCopy.getWidth() - childToplevelState.displayWidth) / 2);
                    childToplevelState.displayY = Math.max(0, (bitmapCopy.getHeight() - childToplevelState.displayHeight) / 2);
                    canvas.drawBitmap(bitmapCreateBitmap2, (Rect) null, new Rect(childToplevelState.displayX, childToplevelState.displayY, childToplevelState.displayX + childToplevelState.displayWidth, childToplevelState.displayY + childToplevelState.displayHeight), (Paint) null);
                    float f = childToplevelState.displayWidth / childToplevelState.sourceWidth;
                    drawSubsurfacesForParent(canvas, childToplevelState.wlSurfaceId, childToplevelState.displayX - (childToplevelState.bufferOriginX * f), childToplevelState.displayY - (childToplevelState.bufferOriginY * f), iMax4 * f);
                }
                i = 1;
            }
            ArrayList<PopupState> arrayList = new ArrayList(this.popups.values());
            arrayList.sort(new Comparator() { // from class: org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer$$ExternalSyntheticLambda1
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    return Integer.compare(((MainActivity.RawWaylandShmServer.PopupState) obj).sequence, ((MainActivity.RawWaylandShmServer.PopupState) obj2).sequence);
                }
            });
            for (PopupState popupState : arrayList) {
                if (popupState.visible && popupState.bitmap != null) {
                    layoutPopup(popupState);
                    canvas.drawBitmap(popupState.bitmap, (Rect) null, new Rect(popupState.displayX, popupState.displayY, popupState.displayX + popupState.displayWidth, popupState.displayY + popupState.displayHeight), (Paint) null);
                    drawSubsurfacesForParent(canvas, popupState.wlSurfaceId, popupState.displayX, popupState.displayY, popupState.pixelsPerLogical);
                }
            }
            this.bitmap = bitmapCopy;
            Runnable runnable = this.frameCommittedCallback;
            if (runnable != null) {
                runnable.run();
            }
        }

        private void drawSubsurfacesForParent(Canvas canvas, int i, float f, float f2, float f3) {
            ArrayList<SubsurfaceState> arrayList = new ArrayList();
            for (SubsurfaceState subsurfaceState : this.subsurfaces.values()) {
                if (subsurfaceState.parentSurfaceId == i && subsurfaceState.visible && subsurfaceState.aboveParent && subsurfaceState.bitmap != null) {
                    arrayList.add(subsurfaceState);
                }
            }
            arrayList.sort(new Comparator() { // from class: org.archphene.linux.mousepad.MainActivity$RawWaylandShmServer$$ExternalSyntheticLambda4
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    return Integer.compare(((MainActivity.RawWaylandShmServer.SubsurfaceState) obj).sequence, ((MainActivity.RawWaylandShmServer.SubsurfaceState) obj2).sequence);
                }
            });
            for (SubsurfaceState subsurfaceState2 : arrayList) {
                float fMax = f3 / Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(subsurfaceState2.wlSurfaceId), 1).intValue());
                subsurfaceState2.pixelsPerLogical = Math.max(0.01f, f3);
                subsurfaceState2.displayX = Math.round((subsurfaceState2.x * f3) + f);
                subsurfaceState2.displayY = Math.round((subsurfaceState2.y * f3) + f2);
                subsurfaceState2.displayWidth = Math.max(1, Math.round(subsurfaceState2.bitmap.getWidth() * fMax));
                subsurfaceState2.displayHeight = Math.max(1, Math.round(subsurfaceState2.bitmap.getHeight() * fMax));
                canvas.drawBitmap(subsurfaceState2.bitmap, (Rect) null, new Rect(subsurfaceState2.displayX, subsurfaceState2.displayY, subsurfaceState2.displayX + subsurfaceState2.displayWidth, subsurfaceState2.displayY + subsurfaceState2.displayHeight), (Paint) null);
                drawSubsurfacesForParent(canvas, subsurfaceState2.wlSurfaceId, subsurfaceState2.displayX, subsurfaceState2.displayY, f3);
            }
        }

        private void layoutPopup(PopupState popupState) {
            int i;
            int i2;
            float fMax = this.outputScale;
            PopupState popupState2 = popupState;
            int i3 = 0;
            int i4 = 0;
            while (true) {
                if (popupState2 == null) {
                    i = 0;
                    i2 = 0;
                    break;
                }
                i3 += popupState2.configureX;
                i4 += popupState2.configureY;
                PopupState popupState3 = this.popupsByXdgSurface.get(Integer.valueOf(popupState2.parentXdgSurfaceId));
                if (popupState3 != null) {
                    popupState2 = popupState3;
                } else {
                    ChildToplevelState childToplevelState = this.childToplevelsByXdg.get(Integer.valueOf(popupState2.parentXdgSurfaceId));
                    if (childToplevelState == null) {
                        i = 0;
                        i2 = 0;
                    } else {
                        fMax = Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(childToplevelState.wlSurfaceId), 1).intValue()) * (childToplevelState.displayWidth / Math.max(1, childToplevelState.sourceWidth));
                        i2 = childToplevelState.displayX;
                        i = childToplevelState.displayY;
                    }
                }
            }
            WindowGeometry windowGeometry = this.windowGeometries.get(Integer.valueOf(popupState.xdgSurfaceId));
            int i5 = windowGeometry == null ? 0 : windowGeometry.x;
            int i6 = windowGeometry != null ? windowGeometry.y : 0;
            float fMax2 = fMax / Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(popupState.wlSurfaceId), 1).intValue());
            popupState.pixelsPerLogical = Math.max(0.01f, fMax);
            popupState.displayX = i2 + Math.round((i3 - i5) * fMax);
            popupState.displayY = i + Math.round((i4 - i6) * fMax);
            popupState.displayWidth = Math.max(1, Math.round(popupState.bitmap.getWidth() * fMax2));
            popupState.displayHeight = Math.max(1, Math.round(popupState.bitmap.getHeight() * fMax2));
        }

        private int presentationOriginX() {
            WindowGeometry windowGeometry = this.windowGeometries.get(Integer.valueOf(this.xdgSurfaceId));
            int iMax = Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(this.surfaceId), 1).intValue());
            if (windowGeometry == null) {
                return 0;
            }
            return Math.max(0, windowGeometry.x * iMax);
        }

        private int presentationOriginY() {
            WindowGeometry windowGeometry = this.windowGeometries.get(Integer.valueOf(this.xdgSurfaceId));
            int iMax = Math.max(1, this.surfaceBufferScales.getOrDefault(Integer.valueOf(this.surfaceId), 1).intValue());
            if (windowGeometry == null) {
                return 0;
            }
            return Math.max(0, windowGeometry.y * iMax);
        }

        private void restoreMainBitmap() {
            composeSurfaceTree();
        }

        private void addMainDamage(int i, int i2, int i3, int i4) {
            long jMax = ((long) i) + ((long) Math.max(0, i3));
            long jMax2 = ((long) i2) + ((long) Math.max(0, i4));
            int iMax = Math.max(0, i);
            int iMax2 = Math.max(0, i2);
            int iMax3 = (int) Math.max(iMax, Math.min(2147483647L, jMax));
            int iMax4 = (int) Math.max(iMax2, Math.min(2147483647L, jMax2));
            if (!this.mainDamagePending) {
                this.mainDamageLeft = iMax;
                this.mainDamageTop = iMax2;
                this.mainDamageRight = iMax3;
                this.mainDamageBottom = iMax4;
                this.mainDamagePending = true;
                return;
            }
            this.mainDamageLeft = Math.min(this.mainDamageLeft, iMax);
            this.mainDamageTop = Math.min(this.mainDamageTop, iMax2);
            this.mainDamageRight = Math.max(this.mainDamageRight, iMax3);
            this.mainDamageBottom = Math.max(this.mainDamageBottom, iMax4);
        }

        private void commitBuffer() throws Exception {
            if (!this.mainBufferAttachPending) {
                return;
            }
            ShmBufferState shmBufferState = this.shmBuffers.get(Integer.valueOf(this.attachedBufferId));
            if (shmBufferState == null) {
                if (this.attachedBufferId == 0) {
                    return;
                }
                throw new IllegalStateException("commit with unknown wl_buffer " + this.attachedBufferId);
            }
            if (shmBufferState.pool.fd == null) {
                throw new IllegalStateException("commit before shm fd");
            }
            this.width = shmBufferState.width;
            this.height = shmBufferState.height;
            this.stride = shmBufferState.stride;
            long j = ((long) shmBufferState.offset) + (((long) this.stride) * ((long) this.height));
            if (this.width <= 0 || this.height <= 0 || this.stride < this.width * 4 || j > shmBufferState.pool.size) {
                throw new IllegalStateException("invalid buffer state before commit");
            }
            byte[] exactFromFd = readExactFromFd(shmBufferState.pool.fd, shmBufferState.offset, this.stride * this.height);
            this.commitCount++;
            this.bytesRead = exactFromFd.length;
            int[] iArr = new int[this.width * this.height];
            for (int i = 0; i < this.height; i++) {
                int i2 = this.stride * i;
                for (int i3 = 0; i3 < this.width; i3++) {
                    int i4 = (i3 * 4) + i2;
                    int i5 = 255;
                    int i6 = exactFromFd[i4] & 255;
                    int i7 = exactFromFd[i4 + 1] & 255;
                    int i8 = exactFromFd[i4 + 2] & 255;
                    if (shmBufferState.format == 0) {
                        i5 = 255 & exactFromFd[i4 + 3];
                    }
                    iArr[(this.width * i) + i3] = i6 | (i5 << 24) | (i8 << 16) | (i7 << 8);
                }
            }
            Bitmap bitmapCreateBitmap = Bitmap.createBitmap(iArr, this.width, this.height, Bitmap.Config.ARGB_8888);
            if (this.mainBitmap != null && this.mainBitmap.getWidth() == this.width && this.mainBitmap.getHeight() == this.height && this.mainDamagePending) {
                int iMax = Math.max(0, Math.min(this.width, this.mainDamageLeft));
                int iMax2 = Math.max(0, Math.min(this.height, this.mainDamageTop));
                int iMax3 = Math.max(iMax, Math.min(this.width, this.mainDamageRight));
                int iMax4 = Math.max(iMax2, Math.min(this.height, this.mainDamageBottom));
                Bitmap bitmapCopy = this.mainBitmap.copy(Bitmap.Config.ARGB_8888, true);
                if (iMax3 > iMax && iMax4 > iMax2) {
                    Rect rect = new Rect(iMax, iMax2, iMax3, iMax4);
                    new Canvas(bitmapCopy).drawBitmap(bitmapCreateBitmap, rect, rect, (Paint) null);
                }
                this.mainBitmap = bitmapCopy;
            } else {
                this.mainBitmap = bitmapCreateBitmap;
            }
            this.mainDamagePending = false;
            composeSurfaceTree();
        }

        private static byte[] readExact(InputStream inputStream, int i) throws Exception {
            byte[] bArr = new byte[i];
            int i2 = 0;
            while (i2 < i) {
                int i3 = inputStream.read(bArr, i2, i - i2);
                if (i3 == -1) {
                    throw new EOFException("EOF after " + i2 + " of " + i + " bytes");
                }
                i2 += i3;
            }
            return bArr;
        }

        private static byte[] readExactFromFd(FileDescriptor fileDescriptor, int i, int i2) throws Exception {
            byte[] bArr = new byte[i2];
            FileInputStream fileInputStream = new FileInputStream(fileDescriptor);
            fileInputStream.getChannel().position(i);
            int i3 = 0;
            while (i3 < i2) {
                int i4 = fileInputStream.read(bArr, i3, i2 - i3);
                if (i4 == -1) {
                    throw new EOFException("EOF after " + i3 + " of " + i2 + " raw Wayland shm bytes");
                }
                i3 += i4;
            }
            return bArr;
        }

        /* JADX INFO: Access modifiers changed from: private */
        static final class SubsurfaceState {
            Bitmap bitmap;
            int displayX;
            int displayY;
            final int parentSurfaceId;
            final int sequence;
            final int subsurfaceId;
            boolean visible;
            final int wlSurfaceId;
            int x;
            int y;
            int displayWidth = 1;
            int displayHeight = 1;
            float pixelsPerLogical = 1.0f;
            boolean aboveParent = true;
            boolean synchronizedCommit = true;

            SubsurfaceState(int i, int i2, int i3, int i4) {
                this.subsurfaceId = i;
                this.wlSurfaceId = i2;
                this.parentSurfaceId = i3;
                this.sequence = i4;
            }
        }

        private static final class ChildToplevelState {
            Bitmap bitmap;
            int bufferOriginX;
            int bufferOriginY;
            boolean configureAcked;
            boolean configureSent;
            int configureSerial;
            int displayHeight;
            int displayWidth;
            int displayX;
            int displayY;
            int maxHeight;
            int maxWidth;
            int minHeight;
            int minWidth;
            int parentToplevelId;
            final int toplevelId;
            boolean visible;
            final int wlSurfaceId;
            final int xdgSurfaceId;
            int sourceWidth = 1;
            int sourceHeight = 1;
            String title = "";
            String appId = "";

            ChildToplevelState(int i, int i2, int i3) {
                this.toplevelId = i;
                this.xdgSurfaceId = i2;
                this.wlSurfaceId = i3;
            }

            boolean isSaveDialog() {
                return this.title.toLowerCase(Locale.ROOT).contains("save");
            }

            boolean isFileDialog() {
                String lowerCase = this.title.toLowerCase(Locale.ROOT);
                return lowerCase.contains("save") || lowerCase.contains("open") || lowerCase.contains("file chooser") || lowerCase.contains("select a file");
            }
        }

        private static final class WindowGeometry {
            final int height;
            final int width;
            final int x;
            final int y;

            WindowGeometry(int i, int i2, int i3, int i4) {
                this.x = i;
                this.y = i2;
                this.width = i3;
                this.height = i4;
            }
        }

        private static final class PositionerState {
            int anchor;
            int anchorHeight;
            int anchorWidth;
            int anchorX;
            int anchorY;
            int constraintAdjustment;
            int gravity;
            int height;
            int offsetX;
            int offsetY;
            int parentConfigureSerial;
            int parentHeight;
            int parentWidth;
            boolean reactive;
            int width;

            PositionerState() {
                this.width = 1;
                this.height = 1;
            }

            PositionerState(PositionerState positionerState) {
                this.width = 1;
                this.height = 1;
                this.width = positionerState.width;
                this.height = positionerState.height;
                this.anchorX = positionerState.anchorX;
                this.anchorY = positionerState.anchorY;
                this.anchorWidth = positionerState.anchorWidth;
                this.anchorHeight = positionerState.anchorHeight;
                this.anchor = positionerState.anchor;
                this.gravity = positionerState.gravity;
                this.constraintAdjustment = positionerState.constraintAdjustment;
                this.offsetX = positionerState.offsetX;
                this.offsetY = positionerState.offsetY;
                this.parentWidth = positionerState.parentWidth;
                this.parentHeight = positionerState.parentHeight;
                this.parentConfigureSerial = positionerState.parentConfigureSerial;
                this.reactive = positionerState.reactive;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        static final class PopupState {
            Bitmap bitmap;
            boolean configureAcked;
            boolean configureSent;
            int configureSerial;
            int configureX;
            int configureY;
            int displayX;
            int displayY;
            int grabSerial;
            boolean grabbed;
            int height;
            final int parentXdgSurfaceId;
            final int popupId;
            PositionerState positioner;
            final int sequence;
            boolean visible;
            int width;
            final int wlSurfaceId;
            final int xdgSurfaceId;
            int displayWidth = 1;
            int displayHeight = 1;
            float pixelsPerLogical = 1.0f;

            PopupState(int i, int i2, int i3, int i4, PositionerState positionerState, int i5) {
                this.popupId = i;
                this.xdgSurfaceId = i2;
                this.wlSurfaceId = i3;
                this.parentXdgSurfaceId = i4;
                this.sequence = i5;
                applyPositioner(positionerState);
            }

            void applyPositioner(PositionerState positionerState) {
                this.positioner = new PositionerState(positionerState);
                this.width = Math.max(1, this.positioner.width);
                this.height = Math.max(1, this.positioner.height);
                int iAnchorPointX = anchorPointX(this.positioner);
                int iAnchorPointY = anchorPointY(this.positioner);
                this.configureX = gravityX(iAnchorPointX, this.width, this.positioner.gravity) + this.positioner.offsetX;
                this.configureY = gravityY(iAnchorPointY, this.height, this.positioner.gravity) + this.positioner.offsetY;
            }

            /* JADX INFO: Access modifiers changed from: private */
            public static int anchorPointX(PositionerState positionerState) {
                switch (positionerState.anchor) {
                    case 3:
                    case 5:
                    case 6:
                        return positionerState.anchorX;
                    case 4:
                    case 7:
                    case 8:
                        return positionerState.anchorX + positionerState.anchorWidth;
                    default:
                        return positionerState.anchorX + (positionerState.anchorWidth / 2);
                }
            }

            /* JADX INFO: Access modifiers changed from: private */
            public static int anchorPointY(PositionerState positionerState) {
                switch (positionerState.anchor) {
                    case 1:
                    case 5:
                    case 7:
                        return positionerState.anchorY;
                    case 2:
                    case 6:
                    case 8:
                        return positionerState.anchorY + positionerState.anchorHeight;
                    case 3:
                    case 4:
                    default:
                        return positionerState.anchorY + (positionerState.anchorHeight / 2);
                }
            }

            /* JADX INFO: Access modifiers changed from: private */
            public static int gravityX(int i, int i2, int i3) {
                switch (i3) {
                    case 3:
                    case 5:
                    case 6:
                        return i - i2;
                    case 4:
                    case 7:
                    case 8:
                        return i;
                    default:
                        return i - (i2 / 2);
                }
            }

            /* JADX INFO: Access modifiers changed from: private */
            public static int gravityY(int i, int i2, int i3) {
                switch (i3) {
                    case 1:
                    case 5:
                    case 7:
                        return i - i2;
                    case 2:
                    case 6:
                    case 8:
                        return i;
                    case 3:
                    case 4:
                    default:
                        return i - (i2 / 2);
                }
            }
        }

        private static final class ClipboardSourceState {
            int actions;
            final int id;
            final ArrayList<String> mimeTypes = new ArrayList<>();

            ClipboardSourceState(int i) {
                this.id = i;
            }

            String preferredTextMime() {
                if (this.mimeTypes.contains("text/plain")) {
                    return "text/plain";
                }
                if (this.mimeTypes.contains("text/plain;charset=utf-8")) {
                    return "text/plain;charset=utf-8";
                }
                return null;
            }
        }

        private static final class ShmPoolState {
            final FileDescriptor fd;
            int size;

            ShmPoolState(FileDescriptor fileDescriptor, int i) {
                this.fd = fileDescriptor;
                this.size = i;
            }
        }

        private static final class ShmBufferState {
            final int format;
            final int height;
            final int offset;
            final ShmPoolState pool;
            final int stride;
            final int width;

            ShmBufferState(ShmPoolState shmPoolState, int i, int i2, int i3, int i4, int i5) {
                this.pool = shmPoolState;
                this.offset = i;
                this.width = i2;
                this.height = i3;
                this.stride = i4;
                this.format = i5;
            }
        }

        private static int saturatedAdd(int i, int i2) {
            return (int) Math.max(-2147483648L, Math.min(2147483647L, ((long) i) + ((long) i2)));
        }

        private static int saturatedScale(int i, int i2) {
            return (int) Math.max(-2147483648L, Math.min(2147483647L, ((long) i) * ((long) i2)));
        }

        private static int u32(byte[] bArr, int i) {
            return ((bArr[i + 3] & 255) << 24) | (bArr[i] & 255) | ((bArr[i + 1] & 255) << 8) | ((bArr[i + 2] & 255) << 16);
        }

        private static void putU32(byte[] bArr, int i, int i2) {
            bArr[i] = (byte) (i2 & 255);
            bArr[i + 1] = (byte) ((i2 >>> 8) & 255);
            bArr[i + 2] = (byte) ((i2 >>> 16) & 255);
            bArr[i + 3] = (byte) ((i2 >>> 24) & 255);
        }

        private static String stringArg(byte[] bArr, int i) throws Exception {
            int iU32 = u32(bArr, i);
            if (iU32 > 0) {
                int i2 = i + 4;
                if (i2 + iU32 <= bArr.length) {
                    return new String(bArr, i2, iU32 - 1, StandardCharsets.UTF_8);
                }
            }
            throw new IllegalArgumentException("invalid Wayland string length " + iU32);
        }

        private static int stringPaddedLength(byte[] bArr, int i) {
            return ((u32(bArr, i) + 3) & (-4)) + 4;
        }
    }

    private static final class ShmFrameBridgeServer implements Runnable {
        volatile boolean accepted;
        volatile Bitmap bitmap;
        volatile int bytesRead;
        volatile int fdCount;
        volatile int height;
        volatile boolean listening;
        final File socket;
        volatile int stride;
        volatile int width;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile String header = "";
        volatile String error = "";

        ShmFrameBridgeServer(File file) {
            this.socket = file;
        }

        @Override // java.lang.Runnable
        public void run() {
            boolean zExists;
            LocalServerSocket localServerSocket;
            try {
                localServerSocket = new LocalServerSocket(MainActivity.createFilesystemWaylandServer(this.socket.getAbsolutePath()));
            } catch (Throwable th) {
                try {
                    this.error = th.toString();
                    this.ready.countDown();
                    if (!zExists) {
                        return;
                    }
                } finally {
                    if (this.socket.exists()) {
                        this.socket.delete();
                    }
                }
            }
            try {
                this.listening = true;
                this.ready.countDown();
                LocalSocket localSocketAccept = localServerSocket.accept();
                try {
                    InputStream inputStream = localSocketAccept.getInputStream();
                    try {
                        this.accepted = true;
                        this.header = readHeader(inputStream);
                        FileDescriptor[] ancillaryFileDescriptors = localSocketAccept.getAncillaryFileDescriptors();
                        this.fdCount = ancillaryFileDescriptors == null ? 0 : ancillaryFileDescriptors.length;
                        if (ancillaryFileDescriptors == null || ancillaryFileDescriptors.length == 0) {
                            throw new IllegalStateException("no memfd received with shm frame");
                        }
                        String[] strArrSplit = this.header.trim().split(" ");
                        if (strArrSplit.length != 5 || !"ARCHPHENE_SHM_FRAME_V1".equals(strArrSplit[0])) {
                            throw new IllegalArgumentException("unexpected shm frame header: " + this.header);
                        }
                        this.width = Integer.parseInt(strArrSplit[1]);
                        this.height = Integer.parseInt(strArrSplit[2]);
                        this.stride = Integer.parseInt(strArrSplit[3]);
                        int i = Integer.parseInt(strArrSplit[4]);
                        if (this.width <= 0 || this.height <= 0 || this.width > 4096 || this.height > 4096 || this.stride < this.width * 4 || i < this.stride * this.height) {
                            throw new IllegalArgumentException("invalid shm frame metadata: " + this.header);
                        }
                        byte[] exactFromFd = readExactFromFd(ancillaryFileDescriptors[0], i);
                        this.bytesRead = exactFromFd.length;
                        int[] iArr = new int[this.width * this.height];
                        for (int i2 = 0; i2 < this.height; i2++) {
                            int i3 = this.stride * i2;
                            for (int i4 = 0; i4 < this.width; i4++) {
                                int i5 = (i4 * 4) + i3;
                                iArr[(this.width * i2) + i4] = ((exactFromFd[i5 + 3] & 255) << 24) | ((exactFromFd[i5] & 255) << 16) | ((exactFromFd[i5 + 1] & 255) << 8) | (exactFromFd[i5 + 2] & 255);
                            }
                        }
                        this.bitmap = Bitmap.createBitmap(iArr, this.width, this.height, Bitmap.Config.ARGB_8888);
                        if (inputStream != null) {
                            inputStream.close();
                        }
                        if (localSocketAccept != null) {
                            localSocketAccept.close();
                        }
                        localServerSocket.close();
                        if (!this.socket.exists()) {
                        }
                    } finally {
                    }
                } finally {
                }
            } finally {
            }
        }

        private static String readHeader(InputStream inputStream) throws Exception {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            while (byteArrayOutputStream.size() < 128) {
                int i = inputStream.read();
                if (i == -1) {
                    throw new EOFException("EOF before shm frame header");
                }
                byteArrayOutputStream.write(i);
                if (i == 10) {
                    return byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
                }
            }
            throw new IllegalArgumentException("shm frame header too long");
        }

        private static byte[] readExactFromFd(FileDescriptor fileDescriptor, int i) throws Exception {
            byte[] bArr = new byte[i];
            FileInputStream fileInputStream = new FileInputStream(fileDescriptor);
            int i2 = 0;
            while (i2 < i) {
                try {
                    int i3 = fileInputStream.read(bArr, i2, i - i2);
                    if (i3 == -1) {
                        throw new EOFException("EOF after " + i2 + " of " + i + " shm bytes");
                    }
                    i2 += i3;
                } catch (Throwable th) {
                    try {
                        fileInputStream.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                    throw th;
                }
            }
            fileInputStream.close();
            return bArr;
        }
    }

    private static final class FrameBridgeServer implements Runnable {
        volatile boolean accepted;
        volatile Bitmap bitmap;
        volatile int bytesRead;
        volatile int height;
        volatile boolean listening;
        final File socket;
        volatile int width;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile String header = "";
        volatile String error = "";

        FrameBridgeServer(File file) {
            this.socket = file;
        }

        @Override // java.lang.Runnable
        public void run() {
            boolean zExists;
            LocalServerSocket localServerSocket;
            LocalSocket localSocketAccept;
            InputStream inputStream;
            String[] strArrSplit;
            try {
                localServerSocket = new LocalServerSocket(MainActivity.createFilesystemWaylandServer(this.socket.getAbsolutePath()));
                try {
                    this.listening = true;
                    this.ready.countDown();
                    localSocketAccept = localServerSocket.accept();
                    try {
                        inputStream = localSocketAccept.getInputStream();
                        try {
                            this.accepted = true;
                            this.header = readFrameHeader(inputStream);
                            strArrSplit = this.header.trim().split(" ");
                        } finally {
                        }
                    } finally {
                    }
                } finally {
                }
            } catch (Throwable th) {
                try {
                    this.error = th.toString();
                    this.ready.countDown();
                    if (!zExists) {
                        return;
                    }
                } finally {
                    if (this.socket.exists()) {
                        this.socket.delete();
                    }
                }
            }
            if (strArrSplit.length == 3) {
                int i = 0;
                if ("ARCHPHENE_FRAME_V1".equals(strArrSplit[0])) {
                    this.width = Integer.parseInt(strArrSplit[1]);
                    this.height = Integer.parseInt(strArrSplit[2]);
                    if (this.width <= 0 || this.height <= 0 || this.width > 4096 || this.height > 4096) {
                        throw new IllegalArgumentException("invalid frame dimensions: " + this.width + "x" + this.height);
                    }
                    byte[] exact = readExact(inputStream, this.width * this.height * 4);
                    this.bytesRead = exact.length;
                    int i2 = this.width * this.height;
                    int[] iArr = new int[i2];
                    int i3 = 0;
                    while (i < i2) {
                        iArr[i] = ((exact[i3] & 255) << 16) | ((exact[i3 + 3] & 255) << 24) | ((exact[i3 + 1] & 255) << 8) | (exact[i3 + 2] & 255);
                        i++;
                        i3 += 4;
                    }
                    this.bitmap = Bitmap.createBitmap(iArr, this.width, this.height, Bitmap.Config.ARGB_8888);
                    if (inputStream != null) {
                        inputStream.close();
                    }
                    if (localSocketAccept != null) {
                        localSocketAccept.close();
                    }
                    localServerSocket.close();
                    if (!this.socket.exists()) {
                        return;
                    }
                    return;
                }
            }
            throw new IllegalArgumentException("unexpected frame header: " + this.header);
        }

        private static String readFrameHeader(InputStream inputStream) throws Exception {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            while (byteArrayOutputStream.size() < 128) {
                int i = inputStream.read();
                if (i == -1) {
                    throw new EOFException("EOF before frame header");
                }
                byteArrayOutputStream.write(i);
                if (i == 10) {
                    return byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
                }
            }
            throw new IllegalArgumentException("frame header too long");
        }

        private static byte[] readExact(InputStream inputStream, int i) throws Exception {
            byte[] bArr = new byte[i];
            int i2 = 0;
            while (i2 < i) {
                int i3 = inputStream.read(bArr, i2, i - i2);
                if (i3 == -1) {
                    throw new EOFException("EOF after " + i2 + " of " + i + " frame bytes");
                }
                i2 += i3;
            }
            return bArr;
        }
    }

    private static final class FilesystemBridgeServer implements Runnable {
        volatile boolean accepted;
        volatile boolean listening;
        final File socket;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile String received = "";
        volatile String error = "";

        FilesystemBridgeServer(File file) {
            this.socket = file;
        }

        @Override // java.lang.Runnable
        public void run() {
            boolean zExists;
            LocalServerSocket localServerSocket;
            try {
                localServerSocket = new LocalServerSocket(MainActivity.createFilesystemWaylandServer(this.socket.getAbsolutePath()));
            } catch (Throwable th) {
                try {
                    this.error = th.toString();
                    this.ready.countDown();
                    if (!zExists) {
                        return;
                    }
                } finally {
                    if (this.socket.exists()) {
                        this.socket.delete();
                    }
                }
            }
            try {
                this.listening = true;
                this.ready.countDown();
                LocalSocket localSocketAccept = localServerSocket.accept();
                try {
                    InputStream inputStream = localSocketAccept.getInputStream();
                    try {
                        this.accepted = true;
                        byte[] bArr = new byte[128];
                        int i = inputStream.read(bArr);
                        if (i > 0) {
                            this.received = new String(bArr, 0, i, StandardCharsets.UTF_8);
                        }
                        localSocketAccept.getOutputStream().write("ARCHPHENE_WAYLAND_FILESYSTEM_ACK\n".getBytes(StandardCharsets.UTF_8));
                        localSocketAccept.getOutputStream().flush();
                        if (inputStream != null) {
                            inputStream.close();
                        }
                        if (localSocketAccept != null) {
                            localSocketAccept.close();
                        }
                        localServerSocket.close();
                        if (!this.socket.exists()) {
                        }
                    } finally {
                    }
                } finally {
                }
            } finally {
            }
        }
    }

    private static final class BridgeServer implements Runnable {
        volatile boolean accepted;
        volatile boolean listening;
        final String socketName;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile String received = "";
        volatile String error = "";

        BridgeServer(String str) {
            this.socketName = str;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                LocalServerSocket localServerSocket = new LocalServerSocket(this.socketName);
                try {
                    this.listening = true;
                    this.ready.countDown();
                    LocalSocket localSocketAccept = localServerSocket.accept();
                    try {
                        InputStream inputStream = localSocketAccept.getInputStream();
                        try {
                            this.accepted = true;
                            byte[] bArr = new byte[128];
                            int i = inputStream.read(bArr);
                            if (i > 0) {
                                this.received = new String(bArr, 0, i, StandardCharsets.UTF_8);
                            }
                            localSocketAccept.getOutputStream().write("ARCHPHENE_WAYLAND_BRIDGE_ACK\n".getBytes(StandardCharsets.UTF_8));
                            localSocketAccept.getOutputStream().flush();
                            if (inputStream != null) {
                                inputStream.close();
                            }
                            if (localSocketAccept != null) {
                                localSocketAccept.close();
                            }
                            localServerSocket.close();
                        } finally {
                        }
                    } finally {
                    }
                } finally {
                }
            } catch (Throwable th) {
                this.error = th.toString();
                this.ready.countDown();
            }
        }
    }

    private static final class Result {
        final int exitCode;
        final String startError;
        final String stderr;
        final String stdout;
        final boolean timedOut;

        Result(int i, boolean z, String str, String str2, String str3) {
            this.exitCode = i;
            this.timedOut = z;
            this.stdout = str;
            this.stderr = str2;
            this.startError = str3;
        }
    }
}
