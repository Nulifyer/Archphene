/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.app.Activity
 *  android.content.ClipData
 *  android.content.ClipboardManager
 *  android.content.ClipboardManager$OnPrimaryClipChangedListener
 *  android.content.Context
 *  android.content.res.Configuration
 *  android.graphics.Bitmap
 *  android.graphics.Bitmap$Config
 *  android.graphics.Canvas
 *  android.graphics.Insets
 *  android.graphics.Rect
 *  android.graphics.Region
 *  android.graphics.Region$Op
 *  android.net.LocalServerSocket
 *  android.net.LocalSocket
 *  android.os.Build$VERSION
 *  android.os.Bundle
 *  android.os.ParcelFileDescriptor
 *  android.os.ParcelFileDescriptor$AutoCloseInputStream
 *  android.os.Process
 *  android.os.SystemClock
 *  android.system.Os
 *  android.util.DisplayMetrics
 *  android.util.Log
 *  android.view.KeyEvent
 *  android.view.MotionEvent
 *  android.view.View
 *  android.view.ViewGroup$LayoutParams
 *  android.view.WindowInsets$Type
 *  android.view.inputmethod.BaseInputConnection
 *  android.view.inputmethod.EditorInfo
 *  android.view.inputmethod.InputConnection
 *  android.view.inputmethod.InputMethodManager
 *  android.widget.FrameLayout
 *  android.widget.FrameLayout$LayoutParams
 *  android.widget.ImageView
 *  android.widget.ImageView$ScaleType
 */
package org.archphene.linux.mousepad;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Region;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.archphene.bridge.AndroidDocumentSession;

public final class MainActivity
extends Activity {
    private static final String TAG = "ArchpheneMousepad";
    private static volatile WeakReference<MainActivity> currentActivity = new WeakReference<MainActivity>(null);
    private static final String KCALC_PAYLOAD = "libarchphene_mousepad.so";
    private static final String GTK3_CONFORMANCE_PAYLOAD = "libarchphene_gtk3_conformance.so";
    private static final String CLIPBOARD_PROBE_PAYLOAD = "libarchphene_qt_clipboard_probe.so";
    private static final String WAYLAND_PROBE_PAYLOAD = "libarchphene_wayland_socket_probe.so";
    private static final String WAYLAND_JNI_PAYLOAD = "libarchphene_wayland_jni.so";
    private static final String FRAME_CLIENT_PAYLOAD = "libarchphene_frame_client.so";
    private static final String SHM_FRAME_CLIENT_PAYLOAD = "libarchphene_shm_frame_client.so";
    private static final String WAYLAND_SHM_CLIENT_PAYLOAD = "libarchphene_wayland_shm_client.so";
    private static final String WAYLAND_EVENTED_CLIENT_PAYLOAD = "libarchphene_wayland_evented_client.so";
    private static final String WAYLAND_XDG_CLIENT_PAYLOAD = "libarchphene_wayland_xdg_client.so";
    private static final String WAYLAND_API_CLIENT_PAYLOAD = "libarchphene_wayland_api_client.so";
    private static final String WAYLAND_ANDROID_API_CLIENT_PAYLOAD = "libarchphene_wayland_android_api_client.so";
    private static final String WAYLAND_ANDROID_API_RENDER_CLIENT_PAYLOAD = "libarchphene_wayland_android_api_render_client.so";
    private static final String WAYLAND_ANDROID_API_XDG_CLIENT_PAYLOAD = "libarchphene_wayland_android_api_xdg_client.so";
    private static final String WAYLAND_ANDROID_CLIENT_LIB = "libarchphene_wayland_client_android.so";
    private static final String GLIBC_LOADER = "libarchphene_ld.so";
    private static final String SYSCALL_PROBE = "libarchphene_syscall_probe.so";
    private static final String GLIBC_LIBC = "libc.so.6";
    private static final String JNI_LOAD_ERROR = MainActivity.loadWaylandJni();
    private volatile RawWaylandShmServer activeInteractiveServer;
    private volatile Process activeLinuxProcess;
    private volatile BridgeRootView bridgeRootView;
    private volatile boolean waylandTextInputRequested;
    private boolean waylandTextInputDesired;
    private int waylandTextInputGeneration;
    private Runnable pendingWaylandTextInputApply;
    private long lastAndroidImeCommitUptime;
    private boolean retainAndroidImeForKeyboardNavigation;
    private boolean popupBackInProgress;
    private boolean suppressNextBackInvocation;
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private String pendingBridgeClipboardText;
    private float touchDownX;
    private float touchDownY;
    private float touchLastY;
    private boolean touchScrolling;
    private boolean touchPointerPressed;
    private Runnable pendingViewportResize;
    private AndroidDocumentSession documentSession;

    private static native FileDescriptor createFilesystemWaylandServer(String var0) throws IOException;

    static String loadWaylandJni() {
        try {
            System.loadLibrary("archphene_wayland_jni");
            return "";
        }
        catch (Throwable throwable) {
            return throwable.toString();
        }
    }

    protected void onCreate(Bundle bundle) {
        ImageView framePreview;
        BridgeRootView bridgeRootView;
        String string;
        ClipboardManager clipboardManager;
        super.onCreate(bundle);
        this.documentSession = new AndroidDocumentSession(this, TAG);
        currentActivity = new WeakReference<MainActivity>(this);
        String string2 = this.getIntent().getStringExtra("archphene_android_clipboard_text");
        if (string2 != null) {
            clipboardManager = (ClipboardManager)this.getSystemService("clipboard");
            clipboardManager.setPrimaryClip(ClipData.newPlainText((CharSequence)"Archphene test input", (CharSequence)string2));
        }
        this.clipboardManager = (ClipboardManager)this.getSystemService("clipboard");
        this.clipboardListener = () -> {};
        this.clipboardManager.addPrimaryClipChangedListener(this.clipboardListener);
        this.getWindow().setStatusBarColor(-1);
        this.getWindow().setNavigationBarColor(-16777216);
        this.getWindow().setSoftInputMode(19);
        framePreview = new ImageView((Context)this);
        framePreview.setAdjustViewBounds(false);
        // Keep the last committed Wayland frame stable during viewport animation.
        framePreview.setScaleType(ImageView.ScaleType.MATRIX);
        framePreview.setBackgroundColor(0);
        String string3 = "Mousepad Archphene interactive launch\n";
        this.writeReportArtifact(string3);
        Log.i((String)TAG, (String)"Starting interactive Wayland client");
        if (this.getIntent().getBooleanExtra("archphene_glibc_probe", false)) {
            this.startGlibcRuntimeProbe();
        }
        if (this.getIntent().getBooleanExtra("archphene_access_probe", false)) {
            this.startSyscallProbe("access");
        }
        if ((string = this.getIntent().getStringExtra("archphene_syscall_probe")) != null && !string.isEmpty()) {
            this.startSyscallProbe(string);
        }
        this.bridgeRootView = bridgeRootView = new BridgeRootView((Context)this);
        bridgeRootView.setBackgroundColor(-1);
        bridgeRootView.setFocusable(true);
        bridgeRootView.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 30) {
            bridgeRootView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets system = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(system.left, system.top, system.right, system.bottom);
                return windowInsets;
            });
            bridgeRootView.requestApplyInsets();
        }
        bridgeRootView.setOnTouchListener((arg_0, arg_1) -> this.handleRootTouch(framePreview, bridgeRootView, arg_0, arg_1));
        bridgeRootView.setOnGenericMotionListener((arg_0, arg_1) -> this.lambda$onCreate$5(framePreview, arg_0, arg_1));
        bridgeRootView.setOnHoverListener((arg_0, arg_1) -> this.lambda$onCreate$6(framePreview, arg_0, arg_1));
        bridgeRootView.addView((View)framePreview, (ViewGroup.LayoutParams)new FrameLayout.LayoutParams(-1, -1));
        framePreview.addOnLayoutChangeListener((view, n, n2, n3, n4, n5, n6, n7, n8) -> {
            int n9 = n3 - n;
            int n10 = n4 - n2;
            if (n9 == n7 - n5 && n10 == n8 - n6) {
                return;
            }
            if (this.pendingViewportResize != null) {
                view.removeCallbacks(this.pendingViewportResize);
            }
            this.pendingViewportResize = () -> {
                this.pendingViewportResize = null;
                RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
                if (rawWaylandShmServer != null) {
                    rawWaylandShmServer.requestResize(n9, n10);
                }
            };
            view.postDelayed(this.pendingViewportResize, 120L);
        });
        this.setContentView((View)bridgeRootView);
        if (Build.VERSION.SDK_INT >= 33) {
            this.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () -> {
                if (this.suppressNextBackInvocation) {
                    this.suppressNextBackInvocation = false;
                    return;
                }
                RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
                if (rawWaylandShmServer == null || !rawWaylandShmServer.sendPopupEscape()) {
                    this.finish();
                }
            });
        }
        bridgeRootView.requestFocus();
        bridgeRootView.post(() -> this.lambda$onCreate$10(framePreview, string3));
    }

    private void setWaylandTextInputEnabled(boolean enabled) {
        this.setWaylandTextInputEnabled(enabled, true);
    }

    private void setWaylandTextInputEnabled(boolean enabled, boolean allowKeyboardNavigationRetention) {
        BridgeRootView root = this.bridgeRootView;
        if (root == null) {
            return;
        }
        if (!enabled && allowKeyboardNavigationRetention
                && this.waylandTextInputRequested
                && SystemClock.uptimeMillis() - this.lastAndroidImeCommitUptime < 750L) {
            this.retainAndroidImeForKeyboardNavigation = true;
            this.waylandTextInputDesired = true;
            if (this.pendingWaylandTextInputApply != null) {
                root.removeCallbacks(this.pendingWaylandTextInputApply);
                this.pendingWaylandTextInputApply = null;
            }
            Log.i(TAG, "Wayland text input retained for keyboard navigation");
            return;
        }
        if (enabled) {
            this.retainAndroidImeForKeyboardNavigation = false;
        }
        this.waylandTextInputDesired = enabled;
        int generation = ++this.waylandTextInputGeneration;
        if (this.pendingWaylandTextInputApply != null) {
            root.removeCallbacks(this.pendingWaylandTextInputApply);
            this.pendingWaylandTextInputApply = null;
        }
        if (this.waylandTextInputRequested == enabled) {
            Log.i(TAG, "Wayland text input unchanged enabled=" + enabled
                    + " generation=" + generation);
            return;
        }
        this.pendingWaylandTextInputApply = () -> {
            if (this.waylandTextInputGeneration != generation) {
                return;
            }
            this.pendingWaylandTextInputApply = null;
            boolean requested = this.waylandTextInputDesired;
            if (this.waylandTextInputRequested == requested) {
                return;
            }
            this.waylandTextInputRequested = requested;
            InputMethodManager input = (InputMethodManager)this.getSystemService("input_method");
            if (this.waylandTextInputRequested) {
                root.requestFocus();
                input.restartInput(root);
                input.showSoftInput(root, InputMethodManager.SHOW_IMPLICIT);
            } else {
                input.hideSoftInputFromWindow(root.getWindowToken(), 0);
            }
            Log.i(TAG, "Wayland text input applied enabled="
                    + this.waylandTextInputRequested + " generation=" + generation);
        };
        long delayMillis = enabled ? 0L : 300L;
        Log.i(TAG, "Wayland text input scheduled enabled=" + enabled
                + " delayMs=" + delayMillis + " generation=" + generation);
        root.postDelayed(this.pendingWaylandTextInputApply, delayMillis);
    }
    private void noteAndroidImeCommit() {
        this.lastAndroidImeCommitUptime = SystemClock.uptimeMillis();
    }

    private void releaseRetainedAndroidIme() {
        if (!this.retainAndroidImeForKeyboardNavigation) {
            return;
        }
        this.retainAndroidImeForKeyboardNavigation = false;
        this.lastAndroidImeCommitUptime = 0L;
        this.setWaylandTextInputEnabled(false, false);
    }

    private float[] mapPointerCoordinates(ImageView imageView, RawWaylandShmServer rawWaylandShmServer, float f, float f2) {
        float f3 = f - (float)imageView.getLeft();
        float f4 = f2 - (float)imageView.getTop();
        float f5 = (float)rawWaylandShmServer.outputWidth / (float)Math.max(1, imageView.getWidth());
        float f6 = (float)rawWaylandShmServer.outputHeight / (float)Math.max(1, imageView.getHeight());
        return new float[]{f3 * f5, f4 * f6};
    }

    private void setBridgeClipboardText(String string) {
        this.pendingBridgeClipboardText = string;
        this.clipboardManager.setPrimaryClip(ClipData.newPlainText((CharSequence)"Archphene Linux app", (CharSequence)string));
        this.bridgeRootView.postDelayed(() -> {
            if (string.equals(this.pendingBridgeClipboardText)) {
                this.pendingBridgeClipboardText = null;
            }
        }, 1000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.documentSession.syncAsyncIfDirty();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!AndroidDocumentSession.isDocumentIntent(intent)) {
            return;
        }
        this.documentSession.sync();
        this.setIntent(intent);
        Process process = this.activeLinuxProcess;
        if (process != null) {
            process.destroy();
        }
        this.recreate();
    }

    protected void onDestroy() {
        this.documentSession.close();
        MainActivity mainActivity;
        Process process;
        if (this.clipboardManager != null && this.clipboardListener != null) {
            this.clipboardManager.removePrimaryClipChangedListener(this.clipboardListener);
        }
        if ((process = this.activeLinuxProcess) != null) {
            process.destroy();
        }
        if ((mainActivity = (MainActivity)((Object)currentActivity.get())) == this) {
            currentActivity.clear();
        }
        super.onDestroy();
    }

    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        BridgeRootView bridgeRootView = this.bridgeRootView;
        if (bridgeRootView == null) {
            return;
        }
        bridgeRootView.post(() -> {
            RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
            if (rawWaylandShmServer != null) {
                ImageView imageView = (ImageView)bridgeRootView.getChildAt(0);
                rawWaylandShmServer.requestResize(Math.max(320, imageView.getWidth()), Math.max(240, imageView.getHeight()));
            }
        });
    }

    public void onBackPressed() {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        if (rawWaylandShmServer != null && rawWaylandShmServer.sendPopupEscape()) {
            return;
        }
        super.onBackPressed();
    }

    public boolean dispatchKeyEvent(KeyEvent keyEvent) {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        if (rawWaylandShmServer != null && keyEvent.getKeyCode() == 4) {
            if (keyEvent.getAction() == 0 && rawWaylandShmServer.hasVisiblePopups()) {
                this.popupBackInProgress = true;
                this.suppressNextBackInvocation = true;
                rawWaylandShmServer.handleAndroidKeyEvent(0, 111, keyEvent.getEventTime());
                return true;
            }
            if (keyEvent.getAction() == 1 && this.popupBackInProgress) {
                this.popupBackInProgress = false;
                rawWaylandShmServer.handleAndroidKeyEvent(1, 111, keyEvent.getEventTime());
                return true;
            }
        }
        if (rawWaylandShmServer != null && rawWaylandShmServer.handleAndroidKeyEvent(keyEvent.getAction(), keyEvent.getKeyCode(), keyEvent.getEventTime())) {
            return true;
        }
        return super.dispatchKeyEvent(keyEvent);
    }

    private int[] displayPixelSize() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        this.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        int n = Math.max(320, Math.min(4096, displayMetrics.widthPixels));
        int n2 = Math.max(240, Math.min(4096, displayMetrics.heightPixels));
        return new int[]{n, n2};
    }

    private void putDisplaySizeEnv(Map<String, String> map) {
        int[] nArray = this.displayPixelSize();
        map.put("ARCHPHENE_WIDTH", Integer.toString(nArray[0]));
        map.put("ARCHPHENE_HEIGHT", Integer.toString(nArray[1]));
        this.putQtDensityEnv(map);
    }

    private void putQtDensityEnv(Map<String, String> map) {
        float f = this.getResources().getDisplayMetrics().density;
        int n = Math.max(96, Math.min(384, Math.round(96.0f * f)));
        map.put("QT_FONT_DPI", Integer.toString(n));
    }

    private void writeReportArtifact(String string) {
        File file = new File(this.getFilesDir(), "mousepad-report.txt");
        try (FileOutputStream fileOutputStream = new FileOutputStream(file);){
            fileOutputStream.write(string.getBytes(StandardCharsets.UTF_8));
            Log.i((String)TAG, (String)("Bridge report written to " + file.getAbsolutePath()));
        }
        catch (Exception exception) {
            Log.e((String)TAG, (String)"Could not write bridge report", (Throwable)exception);
        }
    }

    private static void logReportSummary(String string, String string2) {
        Log.i((String)TAG, (String)(string + "\n" + MainActivity.summarizeWindowReport(string2)));
    }

    private static String summarizeWindowReport(String string) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Archphene Mousepad window bridge\n");
        MainActivity.appendSummaryLine(stringBuilder, string, "Raw Wayland parsed messages:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Raw Wayland fd count:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Raw Wayland dimensions:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Raw Wayland committed:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Raw Wayland bitmap ready:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Evented Wayland parsed messages:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Evented Wayland registry globals:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Evented Wayland dimensions:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Evented Wayland committed:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Evented Wayland bitmap ready:");
        MainActivity.appendSummaryLine(stringBuilder, string, "XDG Wayland parsed messages:");
        MainActivity.appendSummaryLine(stringBuilder, string, "XDG Wayland configure sent:");
        MainActivity.appendSummaryLine(stringBuilder, string, "XDG Wayland configure acked:");
        MainActivity.appendSummaryLine(stringBuilder, string, "XDG Wayland frame callback done:");
        MainActivity.appendSummaryLine(stringBuilder, string, "XDG Wayland buffer released:");
        MainActivity.appendSummaryLine(stringBuilder, string, "XDG Wayland dimensions:");
        MainActivity.appendSummaryLine(stringBuilder, string, "XDG Wayland committed:");
        MainActivity.appendSummaryLine(stringBuilder, string, "XDG Wayland bitmap ready:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Wayland API client exit code:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Wayland API server accepted:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Wayland API server sync callbacks:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Wayland API server shm formats:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API client exit code:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API server accepted:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API server sync callbacks:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API server shm formats:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API render exit code:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API render committed:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API render bitmap ready:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg exit code:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg configure acked:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg output done:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg seat capabilities sent:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg pointer requested:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg pointer events sent:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive pointer exit code:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive pointer timed out:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive pointer android events:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive pointer native repaint:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive keyboard android events:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive keyboard native repaint:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive keyboard modifier events:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive keyboard repeat info sent:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive IME input connections:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive IME commit events:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive IME synthesized key events:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive keyboard last modifiers:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API interactive pointer bitmap ready:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg frame callback done:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg buffer released:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg post-commit sync done:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg cleanup sync done:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg destroy requests:");
        MainActivity.appendSummaryLine(stringBuilder, string, "Android Wayland API xdg bitmap ready:");
        MainActivity.appendSummaryLine(stringBuilder, string, "glibc loader --list mousepad");
        return stringBuilder.toString();
    }

    private static void appendSummaryLine(StringBuilder stringBuilder, String string, String string2) {
        int n = string.indexOf(string2);
        if (n < 0) {
            return;
        }
        int n2 = string.indexOf(10, n);
        if (n2 < 0) {
            n2 = string.length();
        }
        stringBuilder.append(string, n, n2).append('\n');
    }

    private String renderReport(ImageView imageView) {
        File file = new File(this.getApplicationInfo().nativeLibraryDir);
        File file2 = new File(this.getFilesDir(), "linux-runtime/lib");
        String string = this.prepareLinuxRuntime(file2);
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
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Mousepad Archphene launcher proof\n\n");
        stringBuilder.append("Android package: ").append(this.getPackageName()).append("\n");
        stringBuilder.append("App label: Mousepad\n");
        stringBuilder.append("UID: ").append(android.os.Process.myUid()).append("\n");
        stringBuilder.append("nativeLibraryDir: ").append(file.getAbsolutePath()).append("\n");
        stringBuilder.append("linuxRuntimeLibDir: ").append(file2.getAbsolutePath()).append("\n");
        stringBuilder.append(string).append("\n\n");
        stringBuilder.append("Arch package metadata (.PKGINFO)\n");
        stringBuilder.append(this.readAsset("mousepad.PKGINFO", 4096)).append("\n");
        MainActivity.appendFileState(stringBuilder, "Native-dir real Arch usr/bin/mousepad ELF entrypoint", file3);
        MainActivity.appendFileState(stringBuilder, "Native-dir Wayland socket probe ELF entrypoint", file4);
        MainActivity.appendFileState(stringBuilder, "APK-extracted Wayland JNI socket binder", file5);
        MainActivity.appendFileState(stringBuilder, "Native-dir Linux frame client", file6);
        MainActivity.appendFileState(stringBuilder, "Native-dir Linux wl_shm-style frame client", file7);
        MainActivity.appendFileState(stringBuilder, "Native-dir raw Wayland wl_shm client", file8);
        MainActivity.appendFileState(stringBuilder, "Native-dir evented Wayland wl_shm client", file9);
        MainActivity.appendFileState(stringBuilder, "Native-dir xdg-shell Wayland client", file10);
        MainActivity.appendFileState(stringBuilder, "Native-dir libwayland-client API probe", file11);
        MainActivity.appendFileState(stringBuilder, "Native-dir Android Wayland API probe", file12);
        MainActivity.appendFileState(stringBuilder, "Native-dir Android Wayland API render probe", file13);
        MainActivity.appendFileState(stringBuilder, "Native-dir Android Wayland API xdg probe", file14);
        MainActivity.appendFileState(stringBuilder, "Native-dir Android Wayland client shim", file15);
        stringBuilder.append("Wayland JNI load error: ").append(JNI_LOAD_ERROR).append("\n\n");
        MainActivity.appendFileState(stringBuilder, "Native-dir glibc loader entrypoint", file16);
        MainActivity.appendFileState(stringBuilder, "Native-dir static syscall probe", file17);
        MainActivity.appendFileState(stringBuilder, "App-private packaged glibc libc", file18);
        stringBuilder.append(MainActivity.reportPatchBytes(file16, file18));
        stringBuilder.append("\n");
        stringBuilder.append(this.runStracedLoaderList(file16, file2, file, file18));
        stringBuilder.append("\n");
        stringBuilder.append(this.runRenderedFrameBridge(file6, imageView));
        stringBuilder.append("\n");
        stringBuilder.append(this.runShmFrameBridge(file7, imageView));
        stringBuilder.append("\n");
        stringBuilder.append(this.runRawWaylandShmBridge(file8, imageView));
        stringBuilder.append("\n");
        stringBuilder.append(this.runEventedWaylandShmBridge(file9, imageView));
        stringBuilder.append("\n");
        stringBuilder.append(this.runXdgWaylandShmBridge(file10, imageView));
        stringBuilder.append("\n");
        stringBuilder.append(this.runWaylandApiClientProbe(file11));
        stringBuilder.append("\n");
        stringBuilder.append(this.runAndroidWaylandApiClientProbe(file12));
        stringBuilder.append("\n");
        stringBuilder.append(this.runAndroidWaylandApiRenderClientProbe(file13, imageView));
        stringBuilder.append("\n");
        stringBuilder.append(this.runAndroidWaylandApiXdgClientProbe(file14, imageView));
        stringBuilder.append("\n");
        stringBuilder.append(this.runFilesystemWaylandSocketProbe(file4));
        stringBuilder.append("\n");
        stringBuilder.append(this.runWaylandSocketProbe(file4));
        stringBuilder.append("\n");
        stringBuilder.append(this.runNamed("Direct mousepad process launch", new String[]{file3.getAbsolutePath()}));
        stringBuilder.append("\n");
        stringBuilder.append(this.runNamed("glibc loader --verify mousepad", new String[]{file16.getAbsolutePath(), "--verify", file3.getAbsolutePath()}));
        stringBuilder.append("\n");
        stringBuilder.append(this.runNamed("glibc loader --list libc", new String[]{file16.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file.getAbsolutePath(), file18.getAbsolutePath()}));
        stringBuilder.append("\n");
        stringBuilder.append(this.runNamed("glibc loader --list libm", new String[]{file16.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file.getAbsolutePath(), file19.getAbsolutePath()}));
        stringBuilder.append("\n");
        stringBuilder.append(this.runNamed("glibc loader --list QtCore", new String[]{file16.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file.getAbsolutePath(), file20.getAbsolutePath()}));
        stringBuilder.append("\n");
        stringBuilder.append(this.runNamed("glibc loader --list mousepad", new String[]{file16.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file.getAbsolutePath(), file3.getAbsolutePath()}));
        stringBuilder.append("\n");
        stringBuilder.append(this.runSyscallProbeMatrix(file17));
        stringBuilder.append("\n");
        stringBuilder.append("Current expected result: this APK proves Mousepad can be installed and launched as a normal Android app identity, with the real Arch Mousepad ELF in Android nativeLibraryDir and the Arch runtime closure extracted into app-private storage. The remaining pre-GUI blocker is glibc compatibility with the app-spawned Android syscall profile; after that the bridge needs a real Wayland compositor protocol implementation for Qt/KF6 windows.\n");
        return stringBuilder.toString();
    }

    private String runStracedLoaderList(File file, File file2, File file3, File file4) {
        File file5 = new File(this.getCacheDir(), "ld-list-libc.strace");
        if (file5.exists()) {
            file5.delete();
        }
        String[] stringArray = new String[]{"/system/bin/strace", "-f", "-s", "160", "-o", file5.getAbsolutePath(), file.getAbsolutePath(), "--library-path", file2.getAbsolutePath() + ":" + file3.getAbsolutePath(), file4.getAbsolutePath()};
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("App-spawned strace for glibc loader --list libc\n\n");
        stringBuilder.append(this.runNamed("strace ld.so --list libc", stringArray));
        stringBuilder.append("\nTrace tail:\n");
        stringBuilder.append(MainActivity.readTail(file5, 12000));
        stringBuilder.append("\n");
        return stringBuilder.toString();
    }

    private static String readTail(File file, int maxBytes) {
        if (!file.exists()) {
            return "trace file does not exist: " + file.getAbsolutePath() + "\n";
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            raf.seek(start);
            byte[] bytes = new byte[(int) (length - start)];
            int read = raf.read(bytes);
            String prefix = start > 0 ? "... trace truncated to last " + maxBytes + " bytes ...\n" : "";
            return prefix + new String(bytes, 0, Math.max(0, read), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "trace read failed: " + e + "\n";
        }
    }

    private String runRenderedFrameBridge(File file, ImageView imageView) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Linux-rendered frame bridge proof\n\n");
        MainActivity.appendFileState(stringBuilder, "Frame client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-frame-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale frame socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        FrameBridgeServer frameBridgeServer = new FrameBridgeServer(file3);
        Thread thread = new Thread(frameBridgeServer, "archphene-frame-server");
        thread.start();
        try {
            if (!frameBridgeServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Frame bridge server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for frame bridge server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!frameBridgeServer.listening) {
            stringBuilder.append("Frame bridge server failed before listen: ").append(frameBridgeServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-frame-0");
        stringBuilder.append(this.runNamedWithEnv("Linux payload sends RGBA frame to Android UI bridge", new String[]{file.getAbsolutePath()}, hashMap));
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining frame bridge server: ").append(interruptedException).append("\n");
        }
        if (frameBridgeServer.bitmap != null) {
            imageView.setImageBitmap(frameBridgeServer.bitmap);
        }
        stringBuilder.append("Frame bridge server accepted: ").append(frameBridgeServer.accepted).append("\n");
        stringBuilder.append("Frame bridge server header: ").append(frameBridgeServer.header).append("\n");
        stringBuilder.append("Frame bridge dimensions: ").append(frameBridgeServer.width).append("x").append(frameBridgeServer.height).append("\n");
        stringBuilder.append("Frame bridge bytes: ").append(frameBridgeServer.bytesRead).append("\n");
        stringBuilder.append("Frame bridge bitmap ready: ").append(frameBridgeServer.bitmap != null).append("\n");
        stringBuilder.append("Frame bridge error: ").append(frameBridgeServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runShmFrameBridge(File file, ImageView imageView) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Linux wl_shm-style memfd frame bridge proof\n\n");
        MainActivity.appendFileState(stringBuilder, "wl_shm-style frame client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-shm-frame-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale shm frame socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        ShmFrameBridgeServer shmFrameBridgeServer = new ShmFrameBridgeServer(file3);
        Thread thread = new Thread(shmFrameBridgeServer, "archphene-shm-frame-server");
        thread.start();
        try {
            if (!shmFrameBridgeServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Shm frame bridge server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for shm frame bridge server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!shmFrameBridgeServer.listening) {
            stringBuilder.append("Shm frame bridge server failed before listen: ").append(shmFrameBridgeServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-shm-frame-0");
        stringBuilder.append(this.runNamedWithEnv("Linux payload sends memfd-backed wl_shm-style frame", new String[]{file.getAbsolutePath()}, hashMap));
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining shm frame bridge server: ").append(interruptedException).append("\n");
        }
        if (shmFrameBridgeServer.bitmap != null) {
            imageView.setImageBitmap(shmFrameBridgeServer.bitmap);
        }
        stringBuilder.append("Shm frame bridge server accepted: ").append(shmFrameBridgeServer.accepted).append("\n");
        stringBuilder.append("Shm frame bridge header: ").append(shmFrameBridgeServer.header).append("\n");
        stringBuilder.append("Shm frame bridge fd count: ").append(shmFrameBridgeServer.fdCount).append("\n");
        stringBuilder.append("Shm frame bridge dimensions: ").append(shmFrameBridgeServer.width).append("x").append(shmFrameBridgeServer.height).append(" stride=").append(shmFrameBridgeServer.stride).append("\n");
        stringBuilder.append("Shm frame bridge bytes: ").append(shmFrameBridgeServer.bytesRead).append("\n");
        stringBuilder.append("Shm frame bridge bitmap ready: ").append(shmFrameBridgeServer.bitmap != null).append("\n");
        stringBuilder.append("Shm frame bridge error: ").append(shmFrameBridgeServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runRawWaylandShmBridge(File file, ImageView imageView) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Raw Wayland wl_shm compositor bridge proof\n\n");
        MainActivity.appendFileState(stringBuilder, "Raw Wayland wl_shm client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-shm-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale raw Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-raw-wayland-shm-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Raw Wayland server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for raw Wayland server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!rawWaylandShmServer.listening) {
            stringBuilder.append("Raw Wayland server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-wayland-shm-0");
        stringBuilder.append(this.runNamedWithEnv("Linux payload sends raw Wayland wl_shm commit", new String[]{file.getAbsolutePath()}, hashMap));
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining raw Wayland server: ").append(interruptedException).append("\n");
        }
        if (rawWaylandShmServer.bitmap != null) {
            imageView.setImageBitmap(rawWaylandShmServer.bitmap);
        }
        stringBuilder.append("Raw Wayland server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
        stringBuilder.append("Raw Wayland parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
        stringBuilder.append("Raw Wayland fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
        stringBuilder.append("Raw Wayland dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
        stringBuilder.append("Raw Wayland bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
        stringBuilder.append("Raw Wayland committed: ").append(rawWaylandShmServer.committed).append("\n");
        stringBuilder.append("Raw Wayland bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
        stringBuilder.append("Raw Wayland log:\n").append(rawWaylandShmServer.log);
        stringBuilder.append("Raw Wayland error: ").append(rawWaylandShmServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runEventedWaylandShmBridge(File file, ImageView imageView) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Evented Wayland registry/compositor bridge proof\n\n");
        MainActivity.appendFileState(stringBuilder, "Evented Wayland wl_shm client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-evented-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale evented Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-evented-wayland-shm-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Evented Wayland server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for evented Wayland server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!rawWaylandShmServer.listening) {
            stringBuilder.append("Evented Wayland server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-wayland-evented-0");
        stringBuilder.append(this.runNamedWithEnv("Linux payload performs Wayland registry roundtrip then wl_shm commit", new String[]{file.getAbsolutePath()}, hashMap));
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining evented Wayland server: ").append(interruptedException).append("\n");
        }
        if (rawWaylandShmServer.bitmap != null) {
            imageView.setImageBitmap(rawWaylandShmServer.bitmap);
        }
        stringBuilder.append("Evented Wayland server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
        stringBuilder.append("Evented Wayland parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
        stringBuilder.append("Evented Wayland registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
        stringBuilder.append("Evented Wayland callback done sent: ").append(rawWaylandShmServer.callbackDoneSent).append("\n");
        stringBuilder.append("Evented Wayland shm formats sent: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
        stringBuilder.append("Evented Wayland fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
        stringBuilder.append("Evented Wayland dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
        stringBuilder.append("Evented Wayland bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
        stringBuilder.append("Evented Wayland committed: ").append(rawWaylandShmServer.committed).append("\n");
        stringBuilder.append("Evented Wayland bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
        stringBuilder.append("Evented Wayland log:\n").append(rawWaylandShmServer.log);
        stringBuilder.append("Evented Wayland error: ").append(rawWaylandShmServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runXdgWaylandShmBridge(File file, ImageView imageView) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("XDG Wayland toplevel configure bridge proof\n\n");
        MainActivity.appendFileState(stringBuilder, "XDG Wayland client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-xdg-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale xdg Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        int[] nArray = this.displayPixelSize();
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true, nArray[0], nArray[1]);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-xdg-wayland-shm-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("XDG Wayland server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for xdg Wayland server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!rawWaylandShmServer.listening) {
            stringBuilder.append("XDG Wayland server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-wayland-xdg-0");
        stringBuilder.append(this.runNamedWithEnv("Linux payload performs xdg-shell configure/ack then wl_shm commit", new String[]{file.getAbsolutePath()}, hashMap));
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining xdg Wayland server: ").append(interruptedException).append("\n");
        }
        if (rawWaylandShmServer.bitmap != null) {
            imageView.setImageBitmap(rawWaylandShmServer.bitmap);
        }
        stringBuilder.append("XDG Wayland server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
        stringBuilder.append("XDG Wayland parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
        stringBuilder.append("XDG Wayland registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
        stringBuilder.append("XDG Wayland callback done sent: ").append(rawWaylandShmServer.callbackDoneSent).append("\n");
        stringBuilder.append("XDG Wayland shm formats sent: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
        stringBuilder.append("XDG Wayland configure sent: ").append(rawWaylandShmServer.xdgConfigureSent).append(" serial=").append(rawWaylandShmServer.xdgConfigureSerial).append(" configured=").append(rawWaylandShmServer.configureWidth).append("x").append(rawWaylandShmServer.configureHeight).append("\n");
        stringBuilder.append("XDG Wayland configure acked: ").append(rawWaylandShmServer.xdgConfigureAcked).append("\n");
        stringBuilder.append("XDG Wayland frame callback done: ").append(rawWaylandShmServer.frameCallbackDoneSent).append("\n");
        stringBuilder.append("XDG Wayland buffer released: ").append(rawWaylandShmServer.bufferReleaseSent).append("\n");
        stringBuilder.append("XDG Wayland fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
        stringBuilder.append("XDG Wayland dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
        stringBuilder.append("XDG Wayland bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
        stringBuilder.append("XDG Wayland committed: ").append(rawWaylandShmServer.committed).append("\n");
        stringBuilder.append("XDG Wayland bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
        stringBuilder.append("XDG Wayland log:\n").append(rawWaylandShmServer.log);
        stringBuilder.append("XDG Wayland error: ").append(rawWaylandShmServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runWaylandApiClientProbe(File file) {
        Result result;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Real libwayland-client API bridge probe\n\n");
        MainActivity.appendFileState(stringBuilder, "libwayland-client API client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-api-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale API Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true, 2);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-api-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Wayland API server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for Wayland API server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!rawWaylandShmServer.listening) {
            stringBuilder.append("Wayland API server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-wayland-api-0");
        try {
            result = this.run(new String[]{file.getAbsolutePath()}, hashMap);
            stringBuilder.append("libwayland-client API payload performs registry roundtrip and binds globals\n\n").append(MainActivity.formatCommandResult(new String[]{file.getAbsolutePath()}, result));
        }
        catch (Exception exception) {
            result = new Result(-127, false, "", "", exception.toString());
            stringBuilder.append("libwayland-client API payload failed:\n").append(exception).append("\n");
        }
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining Wayland API server: ").append(interruptedException).append("\n");
        }
        stringBuilder.append("Wayland API client exit code: ").append(result.exitCode).append("\n");
        stringBuilder.append("Wayland API server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
        stringBuilder.append("Wayland API server parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
        stringBuilder.append("Wayland API server registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
        stringBuilder.append("Wayland API server sync callbacks: ").append(rawWaylandShmServer.syncCallbackCount).append("\n");
        stringBuilder.append("Wayland API server shm formats: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
        stringBuilder.append("Wayland API server completed: ").append(rawWaylandShmServer.committed).append("\n");
        stringBuilder.append("Wayland API server log:\n").append(rawWaylandShmServer.log);
        stringBuilder.append("Wayland API server error: ").append(rawWaylandShmServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runAndroidWaylandApiClientProbe(File file) {
        Result result;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Android-built Wayland-client API bridge probe\n\n");
        MainActivity.appendFileState(stringBuilder, "Android Wayland API client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-android-api-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale Android API Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true, 2);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-android-api-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Android Wayland API server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for Android Wayland API server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!rawWaylandShmServer.listening) {
            stringBuilder.append("Android Wayland API server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-0");
        try {
            result = this.run(new String[]{file.getAbsolutePath()}, hashMap);
            stringBuilder.append("Android Wayland API payload performs registry roundtrip and binds globals\n\n").append(MainActivity.formatCommandResult(new String[]{file.getAbsolutePath()}, result));
        }
        catch (Exception exception) {
            result = new Result(-127, false, "", "", exception.toString());
            stringBuilder.append("Android Wayland API payload failed:\n").append(exception).append("\n");
        }
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining Android Wayland API server: ").append(interruptedException).append("\n");
        }
        stringBuilder.append("Android Wayland API client exit code: ").append(result.exitCode).append("\n");
        stringBuilder.append("Android Wayland API server accepted: ").append(rawWaylandShmServer.accepted).append("\n");
        stringBuilder.append("Android Wayland API server parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
        stringBuilder.append("Android Wayland API server registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
        stringBuilder.append("Android Wayland API server sync callbacks: ").append(rawWaylandShmServer.syncCallbackCount).append("\n");
        stringBuilder.append("Android Wayland API server shm formats: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
        stringBuilder.append("Android Wayland API server completed: ").append(rawWaylandShmServer.committed).append("\n");
        stringBuilder.append("Android Wayland API server log:\n").append(rawWaylandShmServer.log);
        stringBuilder.append("Android Wayland API server error: ").append(rawWaylandShmServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runAndroidWaylandApiRenderClientProbe(File file, ImageView imageView) {
        Result result;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Android-built Wayland-client API render bridge proof\n\n");
        MainActivity.appendFileState(stringBuilder, "Android Wayland API render client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-android-api-render-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale Android API render Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-android-api-render-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Android Wayland API render server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for Android Wayland API render server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!rawWaylandShmServer.listening) {
            stringBuilder.append("Android Wayland API render server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-render-0");
        try {
            result = this.run(new String[]{file.getAbsolutePath()}, hashMap);
            stringBuilder.append("Android Wayland API payload creates wl_shm buffer and commits wl_surface\n\n").append(MainActivity.formatCommandResult(new String[]{file.getAbsolutePath()}, result));
        }
        catch (Exception exception) {
            result = new Result(-127, false, "", "", exception.toString());
            stringBuilder.append("Android Wayland API render payload failed:\n").append(exception).append("\n");
        }
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining Android Wayland API render server: ").append(interruptedException).append("\n");
        }
        if (rawWaylandShmServer.bitmap != null) {
            imageView.setImageBitmap(rawWaylandShmServer.bitmap);
        }
        stringBuilder.append("Android Wayland API render exit code: ").append(result.exitCode).append("\n");
        stringBuilder.append("Android Wayland API render accepted: ").append(rawWaylandShmServer.accepted).append("\n");
        stringBuilder.append("Android Wayland API render parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
        stringBuilder.append("Android Wayland API render registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
        stringBuilder.append("Android Wayland API render sync callbacks: ").append(rawWaylandShmServer.syncCallbackCount).append("\n");
        stringBuilder.append("Android Wayland API render shm formats: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
        stringBuilder.append("Android Wayland API render fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
        stringBuilder.append("Android Wayland API render dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
        stringBuilder.append("Android Wayland API render bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
        stringBuilder.append("Android Wayland API render committed: ").append(rawWaylandShmServer.committed).append("\n");
        stringBuilder.append("Android Wayland API render bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
        stringBuilder.append("Android Wayland API render log:\n").append(rawWaylandShmServer.log);
        stringBuilder.append("Android Wayland API render error: ").append(rawWaylandShmServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runAndroidWaylandApiXdgClientProbe(File file, ImageView imageView) {
        Result result;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Android-built Wayland-client API xdg-shell bridge proof\n\n");
        MainActivity.appendFileState(stringBuilder, "Android Wayland API xdg client payload", file);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "archphene-wayland-android-api-xdg-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale Android API xdg Wayland socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        int[] nArray = this.displayPixelSize();
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file3, true, nArray[0], nArray[1], true);
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-android-api-xdg-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Android Wayland API xdg server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for Android Wayland API xdg server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!rawWaylandShmServer.listening) {
            stringBuilder.append("Android Wayland API xdg server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-xdg-0");
        try {
            result = this.run(new String[]{file.getAbsolutePath()}, hashMap);
            stringBuilder.append("Android Wayland API payload performs xdg configure/ack and commits wl_shm buffer\n\n").append(MainActivity.formatCommandResult(new String[]{file.getAbsolutePath()}, result));
        }
        catch (Exception exception) {
            result = new Result(-127, false, "", "", exception.toString());
            stringBuilder.append("Android Wayland API xdg payload failed:\n").append(exception).append("\n");
        }
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining Android Wayland API xdg server: ").append(interruptedException).append("\n");
        }
        if (rawWaylandShmServer.bitmap != null) {
            imageView.setImageBitmap(rawWaylandShmServer.bitmap);
        }
        stringBuilder.append("Android Wayland API xdg exit code: ").append(result.exitCode).append("\n");
        stringBuilder.append("Android Wayland API xdg accepted: ").append(rawWaylandShmServer.accepted).append("\n");
        stringBuilder.append("Android Wayland API xdg parsed messages: ").append(rawWaylandShmServer.messageCount).append("\n");
        stringBuilder.append("Android Wayland API xdg registry globals: ").append(rawWaylandShmServer.registryGlobalCount).append("\n");
        stringBuilder.append("Android Wayland API xdg sync callbacks: ").append(rawWaylandShmServer.syncCallbackCount).append("\n");
        stringBuilder.append("Android Wayland API xdg shm formats: ").append(rawWaylandShmServer.shmFormatCount).append("\n");
        stringBuilder.append("Android Wayland API xdg output done: ").append(rawWaylandShmServer.outputDoneSent).append("\n");
        stringBuilder.append("Android Wayland API xdg seat capabilities sent: ").append(rawWaylandShmServer.seatCapabilitiesSent).append("\n");
        stringBuilder.append("Android Wayland API xdg pointer requested: ").append(rawWaylandShmServer.pointerRequested).append("\n");
        stringBuilder.append("Android Wayland API xdg pointer events sent: ").append(rawWaylandShmServer.pointerEventsSent).append("\n");
        stringBuilder.append("Android Wayland API xdg configure sent: ").append(rawWaylandShmServer.xdgConfigureSent).append(" serial=").append(rawWaylandShmServer.xdgConfigureSerial).append(" configured=").append(rawWaylandShmServer.configureWidth).append("x").append(rawWaylandShmServer.configureHeight).append("\n");
        stringBuilder.append("Android Wayland API xdg configure acked: ").append(rawWaylandShmServer.xdgConfigureAcked).append("\n");
        stringBuilder.append("Android Wayland API xdg frame callback done: ").append(rawWaylandShmServer.frameCallbackDoneSent).append("\n");
        stringBuilder.append("Android Wayland API xdg buffer released: ").append(rawWaylandShmServer.bufferReleaseSent).append("\n");
        stringBuilder.append("Android Wayland API xdg post-commit sync done: ").append(rawWaylandShmServer.postCommitSyncDone).append("\n");
        stringBuilder.append("Android Wayland API xdg cleanup sync done: ").append(rawWaylandShmServer.cleanupSyncDone).append("\n");
        stringBuilder.append("Android Wayland API xdg destroy requests: ").append(rawWaylandShmServer.destroyRequestCount).append("\n");
        stringBuilder.append("Android Wayland API xdg fd count: ").append(rawWaylandShmServer.fdCount).append("\n");
        stringBuilder.append("Android Wayland API xdg dimensions: ").append(rawWaylandShmServer.width).append("x").append(rawWaylandShmServer.height).append(" stride=").append(rawWaylandShmServer.stride).append("\n");
        stringBuilder.append("Android Wayland API xdg bytes: ").append(rawWaylandShmServer.bytesRead).append("\n");
        stringBuilder.append("Android Wayland API xdg committed: ").append(rawWaylandShmServer.committed).append("\n");
        stringBuilder.append("Android Wayland API xdg bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
        stringBuilder.append("Android Wayland API xdg log:\n").append(rawWaylandShmServer.log);
        stringBuilder.append("Android Wayland API xdg error: ").append(rawWaylandShmServer.error).append("\n");
        return stringBuilder.toString();
    }

    private void startGlibcRuntimeProbe() {
        Thread thread = new Thread(() -> {
            File file = new File(this.getApplicationInfo().nativeLibraryDir);
            File file2 = new File(this.getFilesDir(), "linux-runtime/lib");
            File file3 = new File(file, GLIBC_LOADER);
            File file4 = new File(file2, GLIBC_LIBC);
            File file5 = new File(file, KCALC_PAYLOAD);
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("Archphene source-built glibc Android app-domain probe\n\n");
            stringBuilder.append(this.prepareLinuxRuntime(file2)).append("\n\n");
            HashMap<String, String> hashMap = new HashMap<String, String>();
            hashMap.put("LD_LIBRARY_PATH", file2.getAbsolutePath() + ":" + file.getAbsolutePath());
            hashMap.put("LD_DEBUG", "files,libs,reloc");
            stringBuilder.append(this.runNamedWithEnv("Source-built loader --list libc", new String[]{file3.getAbsolutePath(), "--library-path", (String)hashMap.get("LD_LIBRARY_PATH"), "--list", file4.getAbsolutePath()}, hashMap));
            hashMap.remove("LD_DEBUG");
            hashMap.put("LD_DEBUG", "libs");
            hashMap.put("LD_WARN", "1");
            stringBuilder.append("\n");
            stringBuilder.append(this.runNamedWithEnv("Source-built loader --list mousepad", new String[]{file3.getAbsolutePath(), "--library-path", (String)hashMap.get("LD_LIBRARY_PATH"), "--list", file5.getAbsolutePath()}, hashMap));
            hashMap.remove("LD_DEBUG");
            hashMap.remove("LD_WARN");
            hashMap.put("QT_QPA_PLATFORM", "wayland");
            hashMap.put("QT_QPA_PLATFORM_PLUGIN_PATH", file2.getAbsolutePath());
            stringBuilder.append("\n");
            stringBuilder.append(this.runNamedWithEnv("Source-built loader direct Mousepad startup", new String[]{file3.getAbsolutePath(), "--library-path", (String)hashMap.get("LD_LIBRARY_PATH"), file5.getAbsolutePath()}, hashMap));
            File file6 = new File(this.getCacheDir(), "mousepad-startup.strace");
            if (file6.exists()) {
                file6.delete();
            }
            stringBuilder.append("\n");
            stringBuilder.append(this.runNamedWithEnv("Straced source-built loader direct Mousepad startup", new String[]{"/system/bin/strace", "-f", "-s", "160", "-o", file6.getAbsolutePath(), file3.getAbsolutePath(), "--library-path", (String)hashMap.get("LD_LIBRARY_PATH"), file5.getAbsolutePath()}, hashMap));
            stringBuilder.append("\nMousepad startup trace tail:\n");
            stringBuilder.append(MainActivity.readTail(file6, 24000));
            File file7 = new File(this.getFilesDir(), "glibc-runtime-probe.txt");
            try (FileOutputStream fileOutputStream = new FileOutputStream(file7);){
                fileOutputStream.write(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
                Log.i((String)TAG, (String)("glibc runtime probe written to " + file7.getAbsolutePath()));
            }
            catch (Exception exception) {
                Log.e((String)TAG, (String)"Could not write glibc runtime probe", (Throwable)exception);
            }
        }, "archphene-glibc-runtime-probe");
        thread.start();
    }

    private void startInteractivePointerProbe(ImageView imageView, String string) {
        int n = Math.max(320, imageView.getWidth());
        int n2 = Math.max(240, imageView.getHeight());
        Log.i((String)TAG, (String)("Wayland viewport " + n + "x" + n2));
        File file = new File(this.getApplicationInfo().nativeLibraryDir);
        Thread thread = new Thread(() -> {
            Log.i((String)TAG, (String)"Interactive real Mousepad Wayland launch is ready");
            String string2 = this.runAndroidWaylandApiXdgInteractivePointerProbe(imageView, n, n2);
            String string3 = string + "\n\n" + string2;
            this.writeReportArtifact(string3);
            MainActivity.logReportSummary("Interactive bridge report", string3);
        }, "archphene-wayland-interactive-pointer-probe");
        thread.start();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private String runAndroidWaylandApiXdgInteractivePointerProbe(ImageView imageView, int n, int n2) {
        Result result;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Real Arch Mousepad through Android Wayland bridge\n\n");
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file = new File(this.getFilesDir(), "wayland-runtime");
        file.mkdirs();
        File file2 = new File(file, "i0");
        if (file2.exists() && !file2.delete()) {
            stringBuilder.append("Could not remove stale Android API interactive Wayland socket path: ").append(file2.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        RawWaylandShmServer rawWaylandShmServer = new RawWaylandShmServer(file2, true, n, n2, true);
        rawWaylandShmServer.frameCommittedCallback = () -> this.runOnUiThread(() -> {
            Bitmap bitmap = rawWaylandShmServer.bitmap;
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
        });
        rawWaylandShmServer.interactivePointerMode = true;
        Thread thread = new Thread(rawWaylandShmServer, "archphene-wayland-real-mousepad-server");
        thread.start();
        try {
            if (!rawWaylandShmServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Real Mousepad Wayland server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for real Mousepad Wayland server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!rawWaylandShmServer.listening) {
            stringBuilder.append("Real Mousepad Wayland server failed before listen: ").append(rawWaylandShmServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        File file3 = new File(this.getApplicationInfo().nativeLibraryDir);
        File file4 = new File(this.getFilesDir(), "linux-runtime/lib");
        File file5 = new File(file3, GLIBC_LOADER);
        boolean bl = this.getIntent().getBooleanExtra("archphene_qt_clipboard_probe", false);
        boolean bl2 = "gtk3-conformance".equals(this.getIntent().getStringExtra("archphene_wayland_client"));
        File file6 = new File(file3, bl2 ? GTK3_CONFORMANCE_PAYLOAD : (bl ? CLIPBOARD_PROBE_PAYLOAD : KCALC_PAYLOAD));
        stringBuilder.append(this.prepareLinuxRuntime(file4)).append("\n");
        MainActivity.appendFileState(stringBuilder, "Source-built glibc loader", file5);
        MainActivity.appendFileState(stringBuilder, bl2 ? "GTK3 conformance client" : (bl ? "Qt clipboard probe" : "Real Arch Mousepad"), file6);
        String string = file4.getAbsolutePath() + ":" + file3.getAbsolutePath();
        hashMap.put("ARCHPHENE_WIDTH", Integer.toString(n));
        hashMap.put("ARCHPHENE_HEIGHT", Integer.toString(n2));
        hashMap.put("XDG_RUNTIME_DIR", file.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "i0");
        hashMap.put("LD_LIBRARY_PATH", string);
        File file7 = new File(this.getFilesDir(), "linux-runtime/root");
        hashMap.put("GDK_BACKEND", "wayland");
        hashMap.put("GCONV_PATH", new File(file7, "usr/lib/gconv").getAbsolutePath());
        hashMap.put("GTK_IM_MODULE", "wayland");
        hashMap.put("GTK_IM_MODULE_FILE", new File(file4, "gtk-3.0/3.0.0/immodules.cache").getAbsolutePath());
        hashMap.put("GIO_USE_VFS", "local");
        hashMap.put("GTK_USE_PORTAL", "0");
        hashMap.put("GDK_PIXBUF_MODULE_FILE", new File(file4, "gdk-pixbuf-2.0/2.10.0/loaders.cache").getAbsolutePath());
        hashMap.put("XDG_DATA_DIRS", new File(this.getFilesDir(), "linux-runtime/glycin-share").getAbsolutePath() + ":" + new File(file7, "usr/share").getAbsolutePath());
        hashMap.put("GSETTINGS_SCHEMA_DIR", new File(file7, "usr/share/glib-2.0/schemas").getAbsolutePath());
        hashMap.put("XKB_CONFIG_ROOT", new File(file7, "usr/share/xkeyboard-config-2").getAbsolutePath());
        hashMap.put("GTK_DATA_PREFIX", new File(file7, "usr").getAbsolutePath());
        hashMap.put("GTK_THEME", "Adwaita");
        hashMap.put("MOUSEPAD_PLUGIN_PATH", new File(file4, "mousepad/plugins").getAbsolutePath());
        hashMap.put("QT_QPA_PLATFORM", "wayland");
        hashMap.put("QT_QPA_PLATFORM_PLUGIN_PATH", file4.getAbsolutePath());
        hashMap.put("QT_PLUGIN_PATH", file4.getAbsolutePath());
        hashMap.put("QT_DEBUG_PLUGINS", "1");
        this.putQtDensityEnv(hashMap);
        hashMap.put("ARCHPHENE_INTERACTIVE_POINTER", "1");
        hashMap.put("ARCHPHENE_INTERACTIVE_KEYBOARD", "1");
        this.activeInteractiveServer = rawWaylandShmServer;
        try {
            ArrayList<String> command = new ArrayList<String>();
            command.add(file5.getAbsolutePath());
            command.add("--library-path");
            command.add(string);
            command.add(file6.getAbsolutePath());
            if (!bl && !bl2) {
                File incomingDocument = this.documentSession.importDocument(this.getIntent());
                if (incomingDocument != null) {
                    command.add(incomingDocument.getAbsolutePath());
                }
            }
            String[] stringArray = command.toArray(new String[0]);
            result = this.run(stringArray, hashMap, 0);
            this.documentSession.sync();
            stringBuilder.append("Real Mousepad process connected to the Android-owned Wayland compositor\n\n").append(MainActivity.formatCommandResult(stringArray, result));
        }
        catch (Exception exception) {
            result = new Result(-127, false, "", "", exception.toString());
            stringBuilder.append("Real Mousepad Wayland launch failed:\n").append(exception).append("\n");
        }
        finally {
            if (this.activeInteractiveServer == rawWaylandShmServer) {
                this.activeInteractiveServer = null;
            }
        }
        try {
            thread.join(3000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining Android Wayland API interactive pointer server: ").append(interruptedException).append("\n");
        }
        if (rawWaylandShmServer.bitmap != null) {
            this.runOnUiThread(() -> {
                imageView.setImageBitmap(rawWaylandShmServer.bitmap);
            });
        }
        boolean bl3 = result.stdout.contains("pointer_repainted=1") || result.stdout.contains("real_pointer_repainted=1");
        boolean bl4 = result.stdout.contains("real_keyboard_repainted=1");
        stringBuilder.append("Android Wayland API interactive pointer exit code: ").append(result.exitCode).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer timed out: ").append(result.timedOut).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer accepted: ").append(rawWaylandShmServer.accepted).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer android events: ").append(rawWaylandShmServer.androidPointerEventsSent).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer bridge motion events: ").append(rawWaylandShmServer.pointerMotionEventsSent).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer bridge button events: ").append(rawWaylandShmServer.pointerButtonEventsSent).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer native repaint: ").append(bl3).append("\n");
        stringBuilder.append("Android Wayland API interactive keyboard android events: ").append(rawWaylandShmServer.androidKeyEventsSent).append("\n");
        stringBuilder.append("Android Wayland API interactive keyboard bridge key events: ").append(rawWaylandShmServer.keyboardKeyEventsSent).append("\n");
        stringBuilder.append("Android Wayland API interactive keyboard modifier events: ").append(rawWaylandShmServer.keyboardModifiersSent).append("\n");
        stringBuilder.append("Android Wayland API interactive keyboard repeat info sent: ").append(rawWaylandShmServer.keyboardRepeatInfoSent).append(" rate=").append(rawWaylandShmServer.keyboardRepeatRate).append(" delay=").append(rawWaylandShmServer.keyboardRepeatDelay).append("\n");
        stringBuilder.append("Android Wayland API interactive IME input connections: ").append(rawWaylandShmServer.androidInputConnectionsCreated).append("\n");
        stringBuilder.append("Android Wayland API interactive IME commit events: ").append(rawWaylandShmServer.androidImeCommitEventsSent).append(" chars=").append(rawWaylandShmServer.androidImeCommitChars).append(" last=").append(rawWaylandShmServer.androidImeLastText).append("\n");
        stringBuilder.append("Android Wayland API interactive IME synthesized key events: ").append(rawWaylandShmServer.androidImeSynthKeyEventsSent).append("\n");
        stringBuilder.append("Android Wayland API interactive keyboard last modifiers: ").append(rawWaylandShmServer.keyboardLastMods).append("\n");
        stringBuilder.append("Android Wayland API interactive keyboard native repaint: ").append(bl4).append("\n");
        stringBuilder.append("Android Wayland API interactive keyboard last key: ").append(rawWaylandShmServer.keyboardLastKey).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer commits: ").append(rawWaylandShmServer.commitCount).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer last xy: ").append(rawWaylandShmServer.pointerLastX).append("x").append(rawWaylandShmServer.pointerLastY).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer bitmap ready: ").append(rawWaylandShmServer.bitmap != null).append("\n");
        stringBuilder.append("Android Wayland API interactive pointer log:\n").append(rawWaylandShmServer.log);
        stringBuilder.append("Android Wayland API interactive pointer error: ").append(rawWaylandShmServer.error).append("\n");
        if (!result.timedOut && result.exitCode != 143 && result.exitCode != -127) {
            this.runOnUiThread(() -> ((MainActivity)this).finish());
        }
        return stringBuilder.toString();
    }

    private String runFilesystemWaylandSocketProbe(File file) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Wayland filesystem socket JNI probe\n\n");
        if (!JNI_LOAD_ERROR.isEmpty()) {
            stringBuilder.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return stringBuilder.toString();
        }
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        File file3 = new File(file2, "wayland-0");
        if (file3.exists() && !file3.delete()) {
            stringBuilder.append("Could not remove stale socket path: ").append(file3.getAbsolutePath()).append("\n");
            return stringBuilder.toString();
        }
        FilesystemBridgeServer filesystemBridgeServer = new FilesystemBridgeServer(file3);
        Thread thread = new Thread(filesystemBridgeServer, "archphene-wayland-filesystem-server");
        thread.start();
        try {
            if (!filesystemBridgeServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Filesystem bridge server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for filesystem bridge server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!filesystemBridgeServer.listening) {
            stringBuilder.append("Filesystem bridge server failed before listen: ").append(filesystemBridgeServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", "wayland-0");
        stringBuilder.append(this.runNamedWithEnv("Linux payload connects to JNI-owned filesystem wayland-0 socket", new String[]{file.getAbsolutePath()}, hashMap));
        try {
            thread.join(2000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining filesystem bridge server: ").append(interruptedException).append("\n");
        }
        stringBuilder.append("Filesystem bridge server accepted: ").append(filesystemBridgeServer.accepted).append("\n");
        stringBuilder.append("Filesystem bridge server received: ").append(filesystemBridgeServer.received).append("\n");
        stringBuilder.append("Filesystem bridge server error: ").append(filesystemBridgeServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String runSyscallProbeMatrix(File file) {
        String[] stringArray;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("App-spawned Linux syscall probe matrix\n\n");
        MainActivity.appendFileState(stringBuilder, "Probe payload", file);
        for (String string : stringArray = new String[]{"open", "access", "openat", "openat2", "mkdir", "mkdirat", "unlinkat", "renameat", "readlinkat", "faccessat", "faccessat2", "newfstatat", "statx", "getrandom", "memfd_create", "membarrier", "rt_sigaction", "rt_sigprocmask", "setitimer", "execve_null", "uname", "futex", "sched_setaffinity", "sched_getaffinity", "getcpu", "arch_prctl", "set_tid_address", "prctl", "set_robust_list", "prlimit64", "rseq", "io_uring_setup", "clone3", "pidfd_open", "landlock_create_ruleset", "futex_waitv"}) {
            stringBuilder.append(this.runNamed("Static syscall probe " + string, new String[]{file.getAbsolutePath(), string}));
            stringBuilder.append("\n");
        }
        return stringBuilder.toString();
    }

    private void startSyscallProbe(String string) {
        new Thread(() -> {
            File file = new File(this.getApplicationInfo().nativeLibraryDir, SYSCALL_PROBE);
            String string2 = this.runNamed("Static syscall probe " + string, new String[]{file.getAbsolutePath(), string});
            File file2 = new File(this.getFilesDir(), "syscall-" + string + "-probe.txt");
            try (FileOutputStream fileOutputStream = new FileOutputStream(file2);){
                fileOutputStream.write(string2.getBytes(StandardCharsets.UTF_8));
                Log.i((String)TAG, (String)("access syscall probe written to " + file2.getAbsolutePath()));
            }
            catch (IOException iOException) {
                Log.e((String)TAG, (String)"Unable to write access syscall probe", (Throwable)iOException);
            }
        }, "archphene-syscall-probe-" + string).start();
    }

    private String runWaylandSocketProbe(File file) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Wayland abstract socket fallback probe\n\n");
        MainActivity.appendFileState(stringBuilder, "Probe payload", file);
        File file2 = new File(this.getFilesDir(), "wayland-runtime");
        file2.mkdirs();
        String string = this.getPackageName() + ".wayland-0." + android.os.Process.myUid();
        BridgeServer bridgeServer = new BridgeServer(string);
        Thread thread = new Thread(bridgeServer, "archphene-wayland-probe-server");
        thread.start();
        try {
            if (!bridgeServer.ready.await(2L, TimeUnit.SECONDS)) {
                stringBuilder.append("Bridge server did not become ready before timeout\n");
                return stringBuilder.toString();
            }
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while waiting for bridge server: ").append(interruptedException).append("\n");
            return stringBuilder.toString();
        }
        if (!bridgeServer.listening) {
            stringBuilder.append("Bridge server failed before listen: ").append(bridgeServer.error).append("\n");
            return stringBuilder.toString();
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        this.putDisplaySizeEnv(hashMap);
        hashMap.put("XDG_RUNTIME_DIR", file2.getAbsolutePath());
        hashMap.put("WAYLAND_DISPLAY", string);
        hashMap.put("ARCHPHENE_WAYLAND_ABSTRACT", "1");
        stringBuilder.append(this.runNamedWithEnv("Linux payload connects to Android-owned abstract wayland socket", new String[]{file.getAbsolutePath()}, hashMap));
        try {
            thread.join(2000L);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stringBuilder.append("Interrupted while joining bridge server: ").append(interruptedException).append("\n");
        }
        stringBuilder.append("Bridge server accepted: ").append(bridgeServer.accepted).append("\n");
        stringBuilder.append("Bridge server received: ").append(bridgeServer.received).append("\n");
        stringBuilder.append("Bridge server error: ").append(bridgeServer.error).append("\n");
        return stringBuilder.toString();
    }

    private String prepareLinuxRuntime(File runtimeLibDir) {
        String prefix = "lib/x86_64/";
        int count = 0;
        long bytes = 0;
        try {
            runtimeLibDir.mkdirs();
            deleteContents(runtimeLibDir);
            try (ZipFile zip = new ZipFile(getApplicationInfo().sourceDir)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith(prefix)) {
                        continue;
                    }
                    String leaf = name.substring(prefix.length());
                    if (leaf.isEmpty() || leaf.contains("/")) {
                        continue;
                    }
                    File out = new File(runtimeLibDir, leaf);
                    try (InputStream in = zip.getInputStream(entry); FileOutputStream file = new FileOutputStream(out)) {
                        byte[] buffer = new byte[1024 * 64];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            file.write(buffer, 0, read);
                            bytes += read;
                        }
                    }
                    out.setReadable(true, false);
                    out.setExecutable(true, false);
                    count++;
                }
            }
            File shellPluginSource = new File(runtimeLibDir, "libarchphene_xdg_shell.so");
            if (shellPluginSource.isFile()) {
                File shellPluginDir = new File(runtimeLibDir, "wayland-shell-integration");
                shellPluginDir.mkdirs();
                copyFile(shellPluginSource, new File(shellPluginDir, "libxdg-shell.so"));
            }
            File mousepadPluginDir = new File(runtimeLibDir, "mousepad/plugins");
            mousepadPluginDir.mkdirs();
            for (String plugin : new String[] {
                    "libmousepad-plugin-gspell.so", "libmousepad-plugin-shortcuts.so"}) {
                File source = new File(runtimeLibDir, plugin);
                if (source.isFile()) {
                    copyFile(source, new File(mousepadPluginDir, plugin));
                }
            }
            File gtkImModuleSource = new File(runtimeLibDir, "libarchphene_im_wayland.so");
            File gtkImModuleDir = new File(runtimeLibDir, "gtk-3.0/3.0.0/immodules");
            gtkImModuleDir.mkdirs();
            File gtkImModule = new File(gtkImModuleDir, "im-wayland.so");
            if (gtkImModuleSource.isFile()) {
                copyFile(gtkImModuleSource, gtkImModule);
                File cache = new File(gtkImModuleDir.getParentFile(), "immodules.cache");
                String cacheEntry = "\"" + gtkImModule.getAbsolutePath() + "\"\n"
                        + "\"wayland\" \"Wayland\" \"gtk30\" \"\" \"\"\n";
                try (FileOutputStream output = new FileOutputStream(cache)) {
                    output.write(cacheEntry.getBytes(StandardCharsets.UTF_8));
                }
            }
            File dataRoot = new File(getFilesDir(), "linux-runtime/root");
            deleteContents(dataRoot);
            dataRoot.mkdirs();
            extractZipAsset("mousepad-data.zip", dataRoot);
            extractZipAsset("glibc-gconv.zip", dataRoot);
            return "Extracted Linux runtime from APK: " + count + " files, " + (bytes / (1024 * 1024)) + " MiB";
        } catch (Exception e) {
            return "Linux runtime extraction failed: " + e;
        }
    }

    private void extractZipAsset(String string, File file) throws IOException {
        String string2 = file.getCanonicalPath() + File.separator;
        try (InputStream inputStream = this.getAssets().open(string);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream);){
            ZipEntry zipEntry;
            byte[] byArray = new byte[65536];
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                File file2 = new File(file, zipEntry.getName()).getCanonicalFile();
                if (!file2.getPath().startsWith(string2)) {
                    throw new IOException("data archive path escapes runtime root");
                }
                if (zipEntry.isDirectory()) {
                    file2.mkdirs();
                    continue;
                }
                File file3 = file2.getParentFile();
                if (file3 != null) {
                    file3.mkdirs();
                }
                try (FileOutputStream fileOutputStream = new FileOutputStream(file2);){
                    int n;
                    while ((n = zipInputStream.read(byArray)) != -1) {
                        ((OutputStream)fileOutputStream).write(byArray, 0, n);
                    }
                }
            }
        }
    }

    private static void copyFile(File file, File file2) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(file);
             FileOutputStream fileOutputStream = new FileOutputStream(file2);){
            int n;
            byte[] byArray = new byte[65536];
            while ((n = fileInputStream.read(byArray)) != -1) {
                fileOutputStream.write(byArray, 0, n);
            }
        }
        file2.setReadable(true, false);
        file2.setExecutable(true, false);
    }

    private static void deleteContents(File file) {
        File[] fileArray = file.listFiles();
        if (fileArray == null) {
            return;
        }
        for (File file2 : fileArray) {
            if (file2.isDirectory()) {
                MainActivity.deleteContents(file2);
            }
            file2.delete();
        }
    }

    /*
     * Enabled aggressive exception aggregation
     */
    private String readAsset(String string, int n) {
        try (InputStream inputStream = this.getAssets().open(string);){
            String string2;
            try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();){
                int n2;
                byte[] byArray = new byte[1024];
                for (int i = n; i > 0 && (n2 = inputStream.read(byArray, 0, Math.min(byArray.length, i))) != -1; i -= n2) {
                    byteArrayOutputStream.write(byArray, 0, n2);
                }
                string2 = byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
            }
            return string2;
        }
        catch (Exception exception) {
            return "asset read failed: " + String.valueOf(exception) + "\n";
        }
    }

    private static void appendFileState(StringBuilder stringBuilder, String string, File file) {
        stringBuilder.append(string).append("\n");
        stringBuilder.append("Path: ").append(file.getAbsolutePath()).append("\n");
        stringBuilder.append("Exists: ").append(file.exists()).append("\n");
        stringBuilder.append("Length: ").append(file.length()).append("\n");
        stringBuilder.append("canExecute: ").append(file.canExecute()).append("\n\n");
    }

    private static String reportPatchBytes(File file, File file2) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("On-device glibc patch byte report\n\n");
        MainActivity.appendBytes(stringBuilder, "loader set_robust_list site", file, 82136L, 8);
        MainActivity.appendBytes(stringBuilder, "loader rseq site", file, 82285L, 8);
        MainActivity.appendBytes(stringBuilder, "libc startup rt_sigprocmask site", file2, 161637L, 8);
        MainActivity.appendBytes(stringBuilder, "libc pthread set_robust_list site", file2, 619725L, 8);
        MainActivity.appendBytes(stringBuilder, "libc pthread rseq site", file2, 620467L, 8);
        MainActivity.appendBytes(stringBuilder, "libc fork set_robust_list site", file2, 939740L, 8);
        MainActivity.appendBytes(stringBuilder, "libc faccessat2 syscall number site", file2, 1089544L, 8);
        MainActivity.appendBytes(stringBuilder, "libc faccessat2 syscall site", file2, 1089575L, 8);
        MainActivity.appendBytes(stringBuilder, "libc openat2 entry site", file2, 1112816L, 8);
        return stringBuilder.toString();
    }

    private static void appendBytes(StringBuilder stringBuilder, String string, File file, long l, int n) {
        stringBuilder.append(string).append(" @ 0x").append(Long.toHexString(l)).append("\n");
        stringBuilder.append("File: ").append(file.getAbsolutePath()).append("\n");
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");){
            byte[] byArray = new byte[n];
            randomAccessFile.seek(l);
            int n2 = randomAccessFile.read(byArray);
            stringBuilder.append("Bytes: ");
            for (int i = 0; i < n2; ++i) {
                int n3;
                if (i > 0) {
                    stringBuilder.append(" ");
                }
                if ((n3 = byArray[i] & 0xFF) < 16) {
                    stringBuilder.append("0");
                }
                stringBuilder.append(Integer.toHexString(n3));
            }
            stringBuilder.append("\n\n");
        }
        catch (Exception exception) {
            stringBuilder.append("Read failed: ").append(exception).append("\n\n");
        }
    }

    private String runNamed(String string, String[] stringArray) {
        return this.runNamedWithEnv(string, stringArray, new HashMap<String, String>());
    }

    private String runNamedWithEnv(String string, String[] stringArray, Map<String, String> map) {
        try {
            Result result = this.run(stringArray, map);
            return string + "\n\n" + MainActivity.formatCommandResult(stringArray, result);
        }
        catch (Exception exception) {
            return string + " failed:\n" + String.valueOf(exception) + "\n";
        }
    }

    private Result run(String[] stringArray, Map<String, String> map) throws Exception {
        return this.run(stringArray, map, 5);
    }

    private Result run(String[] command, Map<String, String> extraEnv, int timeoutSeconds) throws Exception {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            File fontconfig = prepareFontconfig();
            File home = new File(getFilesDir(), "linux-home");
            File cache = new File(home, ".cache");
            File config = new File(home, ".config");
            File tmp = new File(getCacheDir(), "linux-tmp");
            File runtime = new File(getFilesDir(), "wayland-runtime");
            home.mkdirs();
            cache.mkdirs();
            config.mkdirs();
            tmp.mkdirs();
            runtime.mkdirs();
            String libDir = new File(command[0]).getParent();
            builder.environment().put("LD_LIBRARY_PATH", libDir);
            builder.environment().put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
            builder.environment().put("HOME", home.getAbsolutePath());
            builder.environment().put("XDG_CACHE_HOME", cache.getAbsolutePath());
            builder.environment().put("XDG_CONFIG_HOME", config.getAbsolutePath());
            builder.environment().put("XDG_RUNTIME_DIR", runtime.getAbsolutePath());
            builder.environment().put("TMPDIR", tmp.getAbsolutePath());
            builder.environment().put("FONTCONFIG_FILE", fontconfig.getAbsolutePath());
            builder.environment().put("FONTCONFIG_PATH", fontconfig.getParentFile().getAbsolutePath());
            builder.environment().put("QT_QPA_PLATFORM", "wayland");
            putQtDensityEnv(builder.environment());
            builder.environment().put("QT_QPA_PLATFORM_PLUGIN_PATH",
                    new File(getFilesDir(), "linux-runtime/lib").getAbsolutePath());
            builder.environment().put("WAYLAND_DISPLAY", "wayland-0");
            builder.environment().putAll(extraEnv);
            process = builder.start();
        } catch (Exception e) {
            return new Result(-127, false, "", "", e.toString());
        }

        String[] stdoutResult = new String[] {""};
        String[] stderrResult = new String[] {""};
        Thread stdoutReader = new Thread(
                () -> stdoutResult[0] = readProcessStream(process.getInputStream(), "stdout"),
                "archphene-process-stdout");
        Thread stderrReader = new Thread(
                () -> stderrResult[0] = readProcessStream(process.getErrorStream(), "stderr"),
                "archphene-process-stderr");
        stdoutReader.start();
        stderrReader.start();

        boolean activityOwned = timeoutSeconds <= 0;
        if (activityOwned) {
            activeLinuxProcess = process;
        }
        boolean finished;
        if (activityOwned) {
            process.waitFor();
            finished = true;
        } else {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        }
        stdoutReader.join(2000);
        stderrReader.join(2000);
        String stdout = stdoutResult[0];
        String stderr = stderrResult[0];
        if (activityOwned && activeLinuxProcess == process) {
            activeLinuxProcess = null;
        }
        if (!finished) {
            return new Result(-1, true, stdout, stderr, "");
        }
        return new Result(process.exitValue(), false, stdout, stderr, "");
    }

    private File prepareFontconfig() throws Exception {
        File file = new File(this.getFilesDir(), "linux-runtime/fontconfig");
        file.mkdirs();
        File file2 = new File(file, "fonts.conf");
        try (InputStream inputStream = this.getAssets().open("fonts.conf");
             FileOutputStream fileOutputStream = new FileOutputStream(file2);){
            int n;
            byte[] byArray = new byte[8192];
            while ((n = inputStream.read(byArray)) != -1) {
                fileOutputStream.write(byArray, 0, n);
            }
        }
        return file2;
    }

    private static String formatCommandResult(String[] stringArray, Result result) {
        return "Command: " + Arrays.toString(stringArray) + "\nExit code: " + result.exitCode + "\nTimed out: " + result.timedOut + "\nStdout:\n" + result.stdout + "Stderr:\n" + result.stderr + "Start error: " + result.startError + "\n";
    }

    /*
     * Enabled aggressive exception aggregation
     */
    private static String readProcessStream(InputStream in, String streamName) {
        final int maxCapturedBytes = 64 * 1024;
        boolean truncated = false;
        try (InputStream input = in; ByteArrayOutputStream tail = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
                if (!chunk.isEmpty()) {
                    Log.i(TAG, "Linux " + streamName + ": " + chunk);
                }
                if (tail.size() + read > maxCapturedBytes) {
                    tail.reset();
                    truncated = true;
                }
                tail.write(buffer, 0, read);
            }
            String prefix = truncated ? "[earlier process output truncated]" + (char) 10 : "";
            return prefix + tail.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "[stream unavailable after process exit: " + e + "]" + (char) 10;
        }
    }
    private /* synthetic */ void lambda$onCreate$10(ImageView imageView, String string) {
        this.startInteractivePointerProbe(imageView, string);
    }

    private /* synthetic */ boolean lambda$onCreate$6(ImageView imageView, View view, MotionEvent motionEvent) {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        int n = motionEvent.getActionMasked();
        if (rawWaylandShmServer != null && (n == 9 || n == 7)) {
            float[] fArray = this.mapPointerCoordinates(imageView, rawWaylandShmServer, motionEvent.getX(), motionEvent.getY());
            return rawWaylandShmServer.handleAndroidMotionEvent(2, fArray[0], fArray[1], motionEvent.getEventTime());
        }
        if (rawWaylandShmServer != null && n == 10) {
            return rawWaylandShmServer.handleAndroidPointerExit();
        }
        return false;
    }

    private /* synthetic */ boolean lambda$onCreate$5(ImageView imageView, View view, MotionEvent motionEvent) {
        RawWaylandShmServer rawWaylandShmServer = this.activeInteractiveServer;
        int n = motionEvent.getActionMasked();
        if (rawWaylandShmServer != null && n == 8) {
            float[] fArray = this.mapPointerCoordinates(imageView, rawWaylandShmServer, motionEvent.getX(), motionEvent.getY());
            return rawWaylandShmServer.handleAndroidScrollEvent(fArray[0], fArray[1], motionEvent.getAxisValue(9), motionEvent.getEventTime());
        }
        if (rawWaylandShmServer != null && (n == 9 || n == 7 || n == 2)) {
            float[] fArray = this.mapPointerCoordinates(imageView, rawWaylandShmServer, motionEvent.getX(), motionEvent.getY());
            return rawWaylandShmServer.handleAndroidMotionEvent(2, fArray[0], fArray[1], motionEvent.getEventTime());
        }
        if (rawWaylandShmServer != null && n == 10) {
            return rawWaylandShmServer.handleAndroidPointerExit();
        }
        return false;
    }

    private /* synthetic */ boolean handleRootTouch(ImageView imageView, BridgeRootView bridgeRootView, View view, MotionEvent motionEvent) {
        RawWaylandShmServer server = this.activeInteractiveServer;
        if (server == null) {
            return true;
        }
        float[] point = this.mapPointerCoordinates(
                imageView, server, motionEvent.getX(), motionEvent.getY());
        int action = motionEvent.getActionMasked();
        boolean mouseSource = (motionEvent.getSource() & 0x2002) == 0x2002;
        if (mouseSource) {
            return server.handleAndroidMotionEvent(
                    action, point[0], point[1], motionEvent.getEventTime());
        }
        if (action == MotionEvent.ACTION_DOWN) {
            this.releaseRetainedAndroidIme();
            return server.prepareAndroidTouchTarget(point[0], point[1], motionEvent.getEventTime())
                    && server.handleAndroidTouchEvent(action, motionEvent.getEventTime());
        }
        if (action == MotionEvent.ACTION_MOVE) {
            server.prepareAndroidTouchTarget(point[0], point[1], motionEvent.getEventTime());
            return server.handleAndroidTouchEvent(action, motionEvent.getEventTime());
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean handled = server.handleAndroidTouchEvent(action, motionEvent.getEventTime());
            server.logRecentProtocol();
            return handled;
        }
        return true;
    }
    private final class BridgeRootView
    extends FrameLayout {
        BridgeRootView(Context context) {
            super(context);
        }

        public boolean onCheckIsTextEditor() {
            return MainActivity.this.waylandTextInputRequested;
        }

        public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
            if (!MainActivity.this.waylandTextInputRequested) {
                return null;
            }
            editorInfo.inputType = 524433;
            editorInfo.imeOptions = 0x2000001;
            RawWaylandShmServer rawWaylandShmServer = MainActivity.this.activeInteractiveServer;
            if (rawWaylandShmServer != null) {
                rawWaylandShmServer.noteAndroidInputConnectionCreated();
            }
            return new BaseInputConnection((View)this, true){

                public boolean commitText(CharSequence charSequence, int n) {
                    MainActivity.this.noteAndroidImeCommit();
                    RawWaylandShmServer rawWaylandShmServer = MainActivity.this.activeInteractiveServer;
                    if (rawWaylandShmServer != null && rawWaylandShmServer.handleAndroidImeCommitText(charSequence)) {
                        return true;
                    }
                    return super.commitText(charSequence, n);
                }

                public boolean deleteSurroundingText(int n, int n2) {
                    RawWaylandShmServer rawWaylandShmServer = MainActivity.this.activeInteractiveServer;
                    if (rawWaylandShmServer != null && rawWaylandShmServer.handleAndroidImeDelete()) {
                        return true;
                    }
                    return super.deleteSurroundingText(n, n2);
                }

                public boolean sendKeyEvent(KeyEvent keyEvent) {
                    RawWaylandShmServer rawWaylandShmServer = MainActivity.this.activeInteractiveServer;
                    if (rawWaylandShmServer != null && rawWaylandShmServer.handleAndroidKeyEvent(keyEvent.getAction(), keyEvent.getKeyCode(), keyEvent.getEventTime())) {
                        return true;
                    }
                    return super.sendKeyEvent(keyEvent);
                }
            };
        }
    }

    private static final class RawWaylandShmServer
    implements Runnable {
        final File socket;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean listening;
        volatile boolean accepted;
        volatile String error = "";
        volatile String log = "";
        volatile int messageCount;
        volatile int fdCount;
        volatile int width;
        volatile int height;
        volatile int stride;
        volatile int bytesRead;
        volatile int registryGlobalCount;
        volatile int shmFormatCount;
        volatile int syncCallbackCount;
        volatile boolean callbackDoneSent;
        volatile boolean xdgConfigureSent;
        volatile boolean xdgConfigureAcked;
        volatile int xdgConfigureSerial;
        volatile boolean frameCallbackDoneSent;
        volatile boolean bufferReleaseSent;
        volatile boolean postCommitSyncDone;
        volatile boolean cleanupSyncDone;
        volatile int destroyRequestCount;
        volatile boolean outputDoneSent;
        volatile boolean seatCapabilitiesSent;
        volatile boolean pointerRequested;
        volatile boolean pointerEventsSent;
        volatile boolean interactivePointerMode;
        volatile int androidPointerEventsSent;
        volatile int pointerMotionEventsSent;
        volatile int pointerButtonEventsSent;
        volatile boolean keyboardRequested;
        volatile boolean keyboardKeymapSent;
        volatile boolean keyboardFocusSent;
        volatile boolean keyboardRepeatInfoSent;
        volatile int keyboardRepeatRate;
        volatile int keyboardRepeatDelay;
        volatile int androidKeyEventsSent;
        volatile int androidInputConnectionsCreated;
        volatile int androidImeCommitEventsSent;
        volatile int androidImeCommitChars;
        volatile int androidImeSynthKeyEventsSent;
        volatile String androidImeLastText = "";
        volatile int keyboardKeyEventsSent;
        volatile int keyboardModifiersSent;
        volatile int keyboardLastMods;
        volatile int keyboardLastKey;
        volatile int pointerLastX;
        volatile int pointerLastY;
        volatile int commitCount;
        volatile boolean committed;
        volatile Bitmap bitmap;
        volatile Bitmap mainBitmap;
        private boolean compactMainPresentation;
        private int mainDisplayX;
        private int mainDisplayY;
        private int mainDisplayWidth;
        private int mainDisplayHeight;
        private int mainSourceWidth;
        private int mainSourceHeight;
        volatile Runnable frameCommittedCallback;
        private final Object writeLock = new Object();
        private final Object eventLock = new Object();
        private volatile LocalSocket connectedClient;
        private StringBuilder eventLog;
        private volatile boolean pointerInside;
        private volatile int pointerFocusSurfaceId;
        private volatile int keyboardFocusSurfaceId;
        private volatile int pointerGrabSurfaceId;
        private volatile int touchFocusSurfaceId;
        private volatile int preparedTouchSurfaceId;
        private volatile int pointerSurfaceX;
        private volatile int pointerSurfaceY;
        private volatile int keyboardModsDepressed;
        private int pointerSerial = 200;
        private int lastInputSerial;
        private final ArrayDeque<Integer> recentInputSerials = new ArrayDeque();
        private int popupSequence;
        private int activePopupGrabId;
        private FileDescriptor shmFd;
        private int poolSize;
        private int bufferId;
        private int attachedBufferId;
        private boolean mainBufferAttachPending;
        private boolean mainDamagePending;
        private int mainDamageLeft;
        private int mainDamageTop;
        private int mainDamageRight;
        private int mainDamageBottom;
        private final Map<Integer, ShmPoolState> shmPools = new HashMap<Integer, ShmPoolState>();
        private final Map<Integer, ShmBufferState> shmBuffers = new HashMap<Integer, ShmBufferState>();
        private final Map<Integer, Integer> auxiliarySurfaceBuffers = new HashMap<Integer, Integer>();
        private final Map<Integer, ArrayDeque<Integer>> auxiliaryFrameCallbacks = new HashMap<Integer, ArrayDeque<Integer>>();
        private final Map<Integer, Boolean> auxiliarySurfaceAttachPending = new HashMap<Integer, Boolean>();
        private final Map<Integer, Rect> auxiliarySurfaceDamage = new HashMap<Integer, Rect>();
        private final Map<Integer, Region> regions = new HashMap<Integer, Region>();
        private final Map<Integer, Region> surfaceInputRegions = new HashMap<Integer, Region>();
        private final Map<Integer, Region> pendingSurfaceInputRegions = new HashMap<Integer, Region>();
        private final Map<Integer, Boolean> pendingSurfaceInputInfinite = new HashMap<Integer, Boolean>();
        private final Map<Integer, Integer> surfaceBufferScales = new HashMap<Integer, Integer>();
        private final Map<Integer, SubsurfaceState> subsurfaces = new HashMap<Integer, SubsurfaceState>();
        private final Map<Integer, SubsurfaceState> subsurfacesBySurface = new HashMap<Integer, SubsurfaceState>();
        private final Map<Integer, Integer> xdgSurfaceToWlSurface = new HashMap<Integer, Integer>();
        private final Map<Integer, WindowGeometry> windowGeometries = new HashMap<Integer, WindowGeometry>();
        private final Map<Integer, PositionerState> positioners = new HashMap<Integer, PositionerState>();
        private final Map<Integer, PopupState> popups = new HashMap<Integer, PopupState>();
        private final Map<Integer, PopupState> popupsByXdgSurface = new HashMap<Integer, PopupState>();
        private final Map<Integer, ChildToplevelState> childToplevelsByXdg = new HashMap<Integer, ChildToplevelState>();
        private final Map<Integer, ChildToplevelState> childToplevelsByRole = new HashMap<Integer, ChildToplevelState>();
        private final Map<Integer, ChildToplevelState> childToplevelsBySurface = new HashMap<Integer, ChildToplevelState>();
        private final Map<Integer, ClipboardSourceState> clipboardSources = new HashMap<Integer, ClipboardSourceState>();
        private final ArrayDeque<FileDescriptor> pendingShmFds = new ArrayDeque();
        private int frameCallbackId;
        private final ArrayDeque<Integer> mainFrameCallbacks = new ArrayDeque<Integer>();
        private int surfaceId;
        private int xdgSurfaceId;
        private int xdgToplevelId;
        private boolean postCommitPending;
        private boolean cleanupPending;
        private int shmId = 3;
        private int shmPoolId = 4;
        private int compositorId = 6;
        private int xdgWmBaseId;
        private int outputId;
        private int seatId;
        private volatile int pointerId;
        private volatile int keyboardId;
        private volatile int touchId;
        private int dataDeviceManagerId;
        private int subcompositorId;
        private int textInputManagerId;
        private volatile int textInputId;
        private volatile boolean textInputPendingEnabled;
        private volatile boolean textInputEnabled;
        private int textInputCommitSerial;
        private int textInputContentHints;
        private int textInputContentPurpose;
        private String textInputSurrounding = "";
        private int dataDeviceId;
        private int clipboardSourceId;
        private int nextServerObjectId = -16777216;
        private final Map<Integer, String> androidClipboardOffers = new HashMap<Integer, String>();
        private boolean androidClipboardOfferPending;
        private String lastOfferedAndroidClipboardText;
        private int registryId = 2;
        private final boolean sendServerEvents;
        private volatile int configureWidth;
        private volatile int configureHeight;
        private volatile int outputWidth;
        private volatile int outputHeight;
        private final int outputScale;
        private final float coordinateScale;
        private final int stopAfterSyncCallbacks;
        private final boolean waitForPostCommitSync;

        RawWaylandShmServer(File file) {
            this(file, false, 420, 260, 0);
        }

        RawWaylandShmServer(File file, boolean bl) {
            this(file, bl, 420, 260, 0);
        }

        RawWaylandShmServer(File file, boolean bl, int n) {
            this(file, bl, 420, 260, n);
        }

        RawWaylandShmServer(File file, boolean bl, int n, int n2) {
            this(file, bl, n, n2, 0, false);
        }

        RawWaylandShmServer(File file, boolean bl, int n, int n2, boolean bl2) {
            this(file, bl, n, n2, 0, bl2);
        }

        private RawWaylandShmServer(File file, boolean bl, int n, int n2, int n3) {
            this(file, bl, n, n2, n3, false);
        }

        private RawWaylandShmServer(File file, boolean bl, int n, int n2, int n3, boolean bl2) {
            this.socket = file;
            this.sendServerEvents = bl;
            this.outputScale = 2;
            this.outputWidth = Math.max(320, Math.min(4096, n));
            this.outputHeight = Math.max(240, Math.min(4096, n2));
            this.configureWidth = Math.max(160, this.outputWidth / this.outputScale);
            this.configureHeight = Math.max(120, this.outputHeight / this.outputScale);
            this.coordinateScale = this.outputScale;
            this.stopAfterSyncCallbacks = Math.max(0, n3);
            this.waitForPostCommitSync = bl2;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public void run() {
            StringBuilder stringBuilder = new StringBuilder();
            Object object = this.eventLock;
            synchronized (object) {
                this.eventLog = stringBuilder;
            }
            try {
                object = MainActivity.createFilesystemWaylandServer(this.socket.getAbsolutePath());
                try (LocalServerSocket localServerSocket = new LocalServerSocket((FileDescriptor)object);){
                    this.listening = true;
                    this.ready.countDown();
                    try (LocalSocket localSocket = localServerSocket.accept();
                         InputStream inputStream = localSocket.getInputStream();){
                        this.accepted = true;
                        this.connectedClient = localSocket;
                        while (!this.committed) {
                            byte[] byArray = RawWaylandShmServer.readExact(inputStream, 8);
                            int n = RawWaylandShmServer.u32(byArray, 0);
                            int n2 = RawWaylandShmServer.u32(byArray, 4);
                            int n3 = n2 & 0xFFFF;
                            int n4 = n2 >>> 16 & 0xFFFF;
                            if (n4 < 8 || n4 > 4096) {
                                throw new IllegalArgumentException("invalid Wayland message size " + n4);
                            }
                            byte[] byArray2 = RawWaylandShmServer.readExact(inputStream, n4 - 8);
                            ++this.messageCount;
                            this.handleMessage(localSocket, n, n3, byArray2, stringBuilder);
                        }
                    }
                }
            }
            catch (Throwable throwable) {
                this.error = throwable.toString();
                this.ready.countDown();
            }
            finally {
                this.closeAllShmPools();
                this.connectedClient = null;
                object = this.eventLock;
                synchronized (object) {
                    this.log = stringBuilder.toString();
                    this.eventLog = null;
                }
                if (this.socket.exists()) {
                    this.socket.delete();
                }
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        private void handleMessage(LocalSocket localSocket, int n, int n2, byte[] byArray, StringBuilder stringBuilder) throws Exception {
            stringBuilder.append("object=").append(n).append(" opcode=").append(n2).append(" size=").append(byArray.length + 8);
            if (n == 1 && n2 == 1) {
                this.registryId = RawWaylandShmServer.u32(byArray, 0);
                stringBuilder.append(" wl_display.get_registry new_id=").append(this.registryId).append("\n");
                if (this.sendServerEvents) {
                    this.sendRegistryGlobal(localSocket, this.registryId, 1, "wl_shm", 1, stringBuilder);
                    this.sendRegistryGlobal(localSocket, this.registryId, 2, "wl_compositor", 4, stringBuilder);
                    this.sendRegistryGlobal(localSocket, this.registryId, 3, "xdg_wm_base", 1, stringBuilder);
                    this.sendRegistryGlobal(localSocket, this.registryId, 4, "wl_output", 2, stringBuilder);
                    this.sendRegistryGlobal(localSocket, this.registryId, 5, "wl_seat", 7, stringBuilder);
                    this.sendRegistryGlobal(localSocket, this.registryId, 6, "wl_data_device_manager", 3, stringBuilder);
                    this.sendRegistryGlobal(localSocket, this.registryId, 7, "wl_subcompositor", 1, stringBuilder);
                    this.sendRegistryGlobal(localSocket, this.registryId, 8, "zwp_text_input_manager_v3", 1, stringBuilder);
                }
                return;
            }
            if (n == 1 && n2 == 0) {
                int n3 = RawWaylandShmServer.u32(byArray, 0);
                stringBuilder.append(" wl_display.sync callback_id=").append(n3).append("\n");
                this.sendCallbackDone(localSocket, n3, 1, stringBuilder);
                ++this.syncCallbackCount;
                if (this.postCommitPending && this.waitForPostCommitSync && !this.postCommitSyncDone) {
                    this.postCommitSyncDone = true;
                    return;
                }
                if (this.cleanupPending && this.waitForPostCommitSync) {
                    this.cleanupSyncDone = true;
                    this.committed = true;
                    return;
                }
                if (this.stopAfterSyncCallbacks > 0 && this.syncCallbackCount >= this.stopAfterSyncCallbacks) {
                    this.committed = true;
                }
                return;
            }
            if (n == this.registryId && n2 == 0) {
                int n4 = RawWaylandShmServer.u32(byArray, 0);
                String string = RawWaylandShmServer.stringArg(byArray, 4);
                int n5 = 4 + RawWaylandShmServer.stringPaddedLength(byArray, 4);
                int n6 = RawWaylandShmServer.u32(byArray, n5);
                int n7 = RawWaylandShmServer.u32(byArray, n5 + 4);
                stringBuilder.append(" wl_registry.bind name=").append(n4).append(" interface=").append(string).append(" version=").append(n6).append(" new_id=").append(n7).append("\n");
                if ("wl_shm".equals(string)) {
                    this.shmId = n7;
                    if (this.sendServerEvents) {
                        this.sendU32Event(localSocket, n7, 0, 0, stringBuilder, "wl_shm.format ARGB8888");
                        ++this.shmFormatCount;
                    }
                } else if ("wl_compositor".equals(string)) {
                    this.compositorId = n7;
                } else if ("xdg_wm_base".equals(string)) {
                    this.xdgWmBaseId = n7;
                } else if ("wl_output".equals(string)) {
                    this.outputId = n7;
                    this.sendOutputEvents(localSocket, n7, stringBuilder);
                } else if ("wl_seat".equals(string)) {
                    this.seatId = n7;
                    this.sendSeatEvents(localSocket, n7, stringBuilder);
                } else if ("wl_subcompositor".equals(string)) {
                    this.subcompositorId = n7;
                } else if ("wl_data_device_manager".equals(string)) {
                    this.dataDeviceManagerId = n7;
                } else if ("zwp_text_input_manager_v3".equals(string)) {
                    this.textInputManagerId = n7;
                }
                return;
            }
            if (n == this.textInputManagerId) {
                if (n2 == 0) {
                    this.textInputManagerId = 0;
                    stringBuilder.append(" zwp_text_input_manager_v3.destroy" + (char) 10);
                } else if (n2 == 1) {
                    this.textInputId = RawWaylandShmServer.u32(byArray, 0);
                    int requestedSeat = RawWaylandShmServer.u32(byArray, 4);
                    this.textInputPendingEnabled = false;
                    this.textInputEnabled = false;
                    stringBuilder.append(" zwp_text_input_manager_v3.get_text_input id=")
                            .append(this.textInputId).append(" seat=").append(requestedSeat).append("" + (char) 10);
                    if (this.keyboardFocusSurfaceId != 0) {
                        this.sendTextInputEnter(localSocket, this.keyboardFocusSurfaceId);
                    }
                }
                return;
            }
            if (n == this.textInputId && this.textInputId != 0) {
                if (n2 == 0) {
                    this.textInputId = 0;
                    this.textInputEnabled = false;
                    this.textInputPendingEnabled = false;
                    this.notifyAndroidTextInput(false, false);
                    stringBuilder.append(" zwp_text_input_v3.destroy" + (char) 10);
                } else if (n2 == 1) {
                    this.textInputPendingEnabled = true;
                    stringBuilder.append(" zwp_text_input_v3.enable" + (char) 10);
                } else if (n2 == 2) {
                    this.textInputPendingEnabled = false;
                    stringBuilder.append(" zwp_text_input_v3.disable" + (char) 10);
                } else if (n2 == 3) {
                    this.textInputSurrounding = RawWaylandShmServer.stringArg(byArray, 0);
                    stringBuilder.append(" zwp_text_input_v3.set_surrounding_text chars=")
                            .append(this.textInputSurrounding.length()).append("" + (char) 10);
                } else if (n2 == 4) {
                    stringBuilder.append(" zwp_text_input_v3.set_text_change_cause" + (char) 10);
                } else if (n2 == 5) {
                    this.textInputContentHints = RawWaylandShmServer.u32(byArray, 0);
                    this.textInputContentPurpose = RawWaylandShmServer.u32(byArray, 4);
                    stringBuilder.append(" zwp_text_input_v3.set_content_type hints=")
                            .append(this.textInputContentHints).append(" purpose=")
                            .append(this.textInputContentPurpose).append("" + (char) 10);
                } else if (n2 == 6) {
                    stringBuilder.append(" zwp_text_input_v3.set_cursor_rectangle" + (char) 10);
                } else if (n2 == 7) {
                    this.textInputEnabled = this.textInputPendingEnabled;
                    this.textInputCommitSerial++;
                    this.notifyAndroidTextInput(this.textInputEnabled);
                    stringBuilder.append(" zwp_text_input_v3.commit serial=")
                            .append(this.textInputCommitSerial).append(" enabled=")
                            .append(this.textInputEnabled).append("" + (char) 10);
                }
                return;
            }            if (n == this.subcompositorId) {
                if (n2 == 0) {
                    stringBuilder.append(" wl_subcompositor.destroy\n");
                } else if (n2 == 1) {
                    int n8 = RawWaylandShmServer.u32(byArray, 0);
                    int n9 = RawWaylandShmServer.u32(byArray, 4);
                    int n10 = RawWaylandShmServer.u32(byArray, 8);
                    SubsurfaceState subsurfaceState = new SubsurfaceState(n8, n9, n10, ++this.popupSequence);
                    this.subsurfaces.put(n8, subsurfaceState);
                    this.subsurfacesBySurface.put(n9, subsurfaceState);
                    Log.i((String)MainActivity.TAG, (String)("Wayland subsurface role=" + n8 + " surface=" + n9 + " parentSurface=" + n10));
                    stringBuilder.append(" wl_subcompositor.get_subsurface subsurface_id=").append(n8).append(" surface=").append(n9).append(" parent=").append(n10).append("\n");
                } else {
                    stringBuilder.append(" wl_subcompositor.unknown\n");
                }
                return;
            }
            SubsurfaceState subsurfaceState = this.subsurfaces.get(n);
            if (subsurfaceState != null) {
                if (n2 == 0) {
                    this.subsurfaces.remove(n);
                    this.subsurfacesBySurface.remove(subsurfaceState.wlSurfaceId);
                    this.composeSurfaceTree();
                    stringBuilder.append(" wl_subsurface.destroy\n");
                } else if (n2 == 1) {
                    subsurfaceState.x = RawWaylandShmServer.u32(byArray, 0);
                    subsurfaceState.y = RawWaylandShmServer.u32(byArray, 4);
                    stringBuilder.append(" wl_subsurface.set_position x=").append(subsurfaceState.x).append(" y=").append(subsurfaceState.y).append("\n");
                } else if (n2 == 2 || n2 == 3) {
                    subsurfaceState.aboveParent = n2 == 2;
                    stringBuilder.append(n2 == 2 ? " wl_subsurface.place_above\n" : " wl_subsurface.place_below\n");
                } else if (n2 == 4 || n2 == 5) {
                    subsurfaceState.synchronizedCommit = n2 == 4;
                    stringBuilder.append(n2 == 4 ? " wl_subsurface.set_sync\n" : " wl_subsurface.set_desync\n");
                } else {
                    stringBuilder.append(" wl_subsurface.unknown\n");
                }
                return;
            }
            if (n == this.dataDeviceManagerId && n2 == 0) {
                int n11 = RawWaylandShmServer.u32(byArray, 0);
                this.clipboardSources.put(n11, new ClipboardSourceState(n11));
                stringBuilder.append(" wl_data_device_manager.create_data_source source_id=").append(n11).append("\n");
                return;
            }
            if (n == this.dataDeviceManagerId && n2 == 1) {
                this.dataDeviceId = RawWaylandShmServer.u32(byArray, 0);
                int n12 = RawWaylandShmServer.u32(byArray, 4);
                stringBuilder.append(" wl_data_device_manager.get_data_device device_id=").append(this.dataDeviceId).append(" seat=").append(n12).append("\n");
                return;
            }
            String string = this.androidClipboardOffers.get(n);
            if (string != null) {
                if (n2 == 0) {
                    stringBuilder.append(" wl_data_offer.accept mime=").append(RawWaylandShmServer.stringArg(byArray, 4)).append("\n");
                } else if (n2 == 1) {
                    String string2 = RawWaylandShmServer.stringArg(byArray, 0);
                    FileDescriptor[] fileDescriptorArray = localSocket.getAncillaryFileDescriptors();
                    if (fileDescriptorArray == null || fileDescriptorArray.length == 0) {
                        throw new IllegalStateException("wl_data_offer.receive missing destination fd");
                    }
                    byte[] byArray2 = string.getBytes(StandardCharsets.UTF_8);
                    FileOutputStream fileOutputStream = new FileOutputStream(fileDescriptorArray[0]);
                    try {
                        ((OutputStream)fileOutputStream).write(byArray2);
                        fileOutputStream.flush();
                    }
                    finally {
                        Os.close((FileDescriptor)fileDescriptorArray[0]);
                    }
                    stringBuilder.append(" android->wayland clipboard bytes=").append(byArray2.length).append(" mime=").append(string2).append("\n");
                } else if (n2 == 2) {
                    this.androidClipboardOffers.remove(n);
                    stringBuilder.append(" wl_data_offer.destroy\n");
                } else {
                    stringBuilder.append(" wl_data_offer opcode=").append(n2).append("\n");
                }
                return;
            }
            ClipboardSourceState clipboardSourceState = this.clipboardSources.get(n);
            if (clipboardSourceState != null) {
                if (n2 == 0) {
                    String string3 = RawWaylandShmServer.stringArg(byArray, 0);
                    clipboardSourceState.mimeTypes.add(string3);
                    stringBuilder.append(" wl_data_source.offer mime=").append(string3).append("\n");
                } else if (n2 == 1) {
                    this.clipboardSources.remove(n);
                    if (this.clipboardSourceId == n) {
                        this.clipboardSourceId = 0;
                    }
                    stringBuilder.append(" wl_data_source.destroy\n");
                } else if (n2 == 2) {
                    clipboardSourceState.actions = RawWaylandShmServer.u32(byArray, 0);
                    stringBuilder.append(" wl_data_source.set_actions actions=").append(clipboardSourceState.actions).append("\n");
                }
                return;
            }
            if (n == this.dataDeviceId) {
                if (n2 == 1) {
                    int n13 = RawWaylandShmServer.u32(byArray, 0);
                    int n14 = RawWaylandShmServer.u32(byArray, 4);
                    this.clipboardSourceId = this.clipboardSources.containsKey(n13) ? n13 : 0;
                    stringBuilder.append(" wl_data_device.set_selection source=").append(n13).append(" serial=").append(n14).append(" valid_serial=").append(this.isKnownInputSerial(n14)).append("\n");
                    ClipboardSourceState clipboardSourceState2 = this.clipboardSources.get(this.clipboardSourceId);
                    if (clipboardSourceState2 != null) {
                        this.requestClipboardSourceData(localSocket, clipboardSourceState2, stringBuilder);
                    }
                } else if (n2 == 2) {
                    this.dataDeviceId = 0;
                    stringBuilder.append(" wl_data_device.release\n");
                } else {
                    stringBuilder.append(" wl_data_device.unhandled\n");
                }
                return;
            }
            if (n == this.seatId && n2 == 0) {
                this.pointerId = RawWaylandShmServer.u32(byArray, 0);
                this.pointerRequested = true;
                stringBuilder.append(" wl_seat.get_pointer pointer_id=").append(this.pointerId).append("\n");
                return;
            }
            if (n == this.seatId && n2 == 2) {
                this.touchId = RawWaylandShmServer.u32(byArray, 0);
                stringBuilder.append(" wl_seat.get_touch touch_id=").append(this.touchId).append("\n");
                return;
            }
            if (n == this.seatId && n2 == 1) {
                this.keyboardId = RawWaylandShmServer.u32(byArray, 0);
                this.keyboardRequested = true;
                stringBuilder.append(" wl_seat.get_keyboard keyboard_id=").append(this.keyboardId).append("\n");
                this.sendKeyboardKeymap(localSocket, stringBuilder);
                if (this.surfaceId != 0 && !this.keyboardFocusSent) {
                    this.sendKeyboardFocus(localSocket, stringBuilder);
                }
                return;
            }
            if (n == this.xdgWmBaseId && n2 == 1) {
                int n15 = RawWaylandShmServer.u32(byArray, 0);
                this.positioners.put(n15, new PositionerState());
                stringBuilder.append(" xdg_wm_base.create_positioner positioner_id=").append(n15).append("\n");
                return;
            }
            PositionerState positionerState = this.positioners.get(n);
            if (positionerState != null) {
                if (n2 == 0) {
                    this.positioners.remove(n);
                    stringBuilder.append(" xdg_positioner.destroy\n");
                } else if (n2 == 1) {
                    positionerState.width = RawWaylandShmServer.u32(byArray, 0);
                    positionerState.height = RawWaylandShmServer.u32(byArray, 4);
                    stringBuilder.append(" xdg_positioner.set_size ").append(positionerState.width).append("x").append(positionerState.height).append("\n");
                } else if (n2 == 2) {
                    positionerState.anchorX = RawWaylandShmServer.u32(byArray, 0);
                    positionerState.anchorY = RawWaylandShmServer.u32(byArray, 4);
                    positionerState.anchorWidth = RawWaylandShmServer.u32(byArray, 8);
                    positionerState.anchorHeight = RawWaylandShmServer.u32(byArray, 12);
                    stringBuilder.append(" xdg_positioner.set_anchor_rect x=").append(positionerState.anchorX).append(" y=").append(positionerState.anchorY).append(" w=").append(positionerState.anchorWidth).append(" h=").append(positionerState.anchorHeight).append("\n");
                } else if (n2 == 3) {
                    positionerState.anchor = RawWaylandShmServer.u32(byArray, 0);
                    stringBuilder.append(" xdg_positioner.set_anchor value=").append(positionerState.anchor).append("\n");
                } else if (n2 == 4) {
                    positionerState.gravity = RawWaylandShmServer.u32(byArray, 0);
                    stringBuilder.append(" xdg_positioner.set_gravity value=").append(positionerState.gravity).append("\n");
                } else if (n2 == 5) {
                    positionerState.constraintAdjustment = RawWaylandShmServer.u32(byArray, 0);
                    stringBuilder.append(" xdg_positioner.set_constraint_adjustment value=").append(positionerState.constraintAdjustment).append("\n");
                } else if (n2 == 6) {
                    positionerState.offsetX = RawWaylandShmServer.u32(byArray, 0);
                    positionerState.offsetY = RawWaylandShmServer.u32(byArray, 4);
                    stringBuilder.append(" xdg_positioner.set_offset x=").append(positionerState.offsetX).append(" y=").append(positionerState.offsetY).append("\n");
                } else if (n2 == 7) {
                    positionerState.reactive = true;
                    stringBuilder.append(" xdg_positioner.set_reactive\n");
                } else if (n2 == 8) {
                    positionerState.parentWidth = RawWaylandShmServer.u32(byArray, 0);
                    positionerState.parentHeight = RawWaylandShmServer.u32(byArray, 4);
                    stringBuilder.append(" xdg_positioner.set_parent_size ").append(positionerState.parentWidth).append("x").append(positionerState.parentHeight).append("\n");
                } else if (n2 == 9) {
                    positionerState.parentConfigureSerial = RawWaylandShmServer.u32(byArray, 0);
                    stringBuilder.append(" xdg_positioner.set_parent_configure serial=").append(positionerState.parentConfigureSerial).append("\n");
                } else {
                    stringBuilder.append(" xdg_positioner.unknown\n");
                }
                return;
            }
            if (n == this.xdgWmBaseId && n2 == 2) {
                int n16 = RawWaylandShmServer.u32(byArray, 0);
                int n17 = RawWaylandShmServer.u32(byArray, 4);
                this.xdgSurfaceToWlSurface.put(n16, n17);
                if (this.xdgSurfaceId == 0) {
                    if (this.surfaceId != 0 && this.surfaceId != n17) {
                        this.auxiliarySurfaceBuffers.putIfAbsent(this.surfaceId, 0);
                    }
                    this.surfaceId = n17;
                    this.auxiliarySurfaceBuffers.remove(n17);
                    this.xdgSurfaceId = n16;
                    this.xdgToplevelId = 0;
                    this.xdgConfigureSent = false;
                    this.xdgConfigureAcked = false;
                    this.cleanupPending = false;
                    this.attachedBufferId = 0;
                    this.mainBufferAttachPending = false;
                    this.mainDamagePending = false;
                    this.mainBitmap = null;
                    this.bitmap = null;
                    this.committed = false;
                    this.frameCallbackId = 0;
                    this.mainFrameCallbacks.clear();
                    this.pointerInside = false;
                    this.pointerFocusSurfaceId = 0;
                    this.pointerGrabSurfaceId = 0;
                    this.keyboardFocusSurfaceId = 0;
                    this.keyboardFocusSent = false;
                    stringBuilder.append(" promoted-to-primary");
                }
                stringBuilder.append(" xdg_wm_base.get_xdg_surface xdg_surface_id=").append(n16).append(" surface=").append(n17).append("\n");
                return;
            }
            ChildToplevelState childToplevelState = this.childToplevelsByRole.get(n);
            if (childToplevelState != null) {
                if (n2 == 0) {
                    if (this.pointerFocusSurfaceId == childToplevelState.wlSurfaceId) {
                        this.sendPointerLeave(localSocket, childToplevelState.wlSurfaceId);
                        this.sendPointerFrame(localSocket);
                        this.pointerInside = false;
                        this.pointerFocusSurfaceId = 0;
                    }
                    if (this.pointerGrabSurfaceId == childToplevelState.wlSurfaceId) {
                        this.pointerGrabSurfaceId = 0;
                    }
                    if (this.keyboardId != 0 && this.keyboardFocusSurfaceId == childToplevelState.wlSurfaceId) {
                        this.sendKeyboardFocus(localSocket, this.surfaceId, stringBuilder);
                    }
                    this.childToplevelsByRole.remove(n);
                    this.childToplevelsByXdg.remove(childToplevelState.xdgSurfaceId);
                    this.childToplevelsBySurface.remove(childToplevelState.wlSurfaceId);
                    if (this.childToplevelsByRole.isEmpty()) {
                        this.sendMainToplevelActivation(localSocket, true, stringBuilder);
                    }
                    this.composeSurfaceTree();
                    stringBuilder.append(" child xdg_toplevel.destroy\n");
                } else if (n2 == 1) {
                    childToplevelState.parentToplevelId = RawWaylandShmServer.u32(byArray, 0);
                    stringBuilder.append(" child xdg_toplevel.set_parent parent=").append(childToplevelState.parentToplevelId).append("\n");
                } else if (n2 == 2) {
                    childToplevelState.title = RawWaylandShmServer.stringArg(byArray, 0);
                    stringBuilder.append(" child xdg_toplevel.set_title ").append(childToplevelState.title).append("\n");
                } else if (n2 == 3) {
                    childToplevelState.appId = RawWaylandShmServer.stringArg(byArray, 0);
                    stringBuilder.append(" child xdg_toplevel.set_app_id ").append(childToplevelState.appId).append("\n");
                } else if (n2 == 7) {
                    childToplevelState.maxWidth = RawWaylandShmServer.u32(byArray, 0);
                    childToplevelState.maxHeight = RawWaylandShmServer.u32(byArray, 4);
                    stringBuilder.append(" child xdg_toplevel.set_max_size ").append(childToplevelState.maxWidth).append("x").append(childToplevelState.maxHeight).append("\n");
                } else if (n2 == 8) {
                    childToplevelState.minWidth = RawWaylandShmServer.u32(byArray, 0);
                    childToplevelState.minHeight = RawWaylandShmServer.u32(byArray, 4);
                    stringBuilder.append(" child xdg_toplevel.set_min_size ").append(childToplevelState.minWidth).append("x").append(childToplevelState.minHeight).append("\n");
                } else {
                    stringBuilder.append(" child xdg_toplevel opcode=").append(n2).append("\n");
                }
                return;
            }
            if (n == this.xdgToplevelId && n2 == 0) {
                ++this.destroyRequestCount;
                this.cleanupPending = true;
                stringBuilder.append(" xdg_toplevel.destroy\n");
                this.xdgToplevelId = 0;
                return;
            }
            Integer n18 = this.xdgSurfaceToWlSurface.get(n);
            if (n18 != null && n2 == 0) {
                ++this.destroyRequestCount;
                this.xdgSurfaceToWlSurface.remove(n);
                this.windowGeometries.remove(n);
                this.popupsByXdgSurface.remove(n);
                if (n == this.xdgSurfaceId) {
                    this.cleanupPending = true;
                    this.xdgSurfaceId = 0;
                }
                stringBuilder.append(" xdg_surface.destroy\n");
                return;
            }
            if (n18 != null && n2 == 3) {
                WindowGeometry windowGeometry = new WindowGeometry(RawWaylandShmServer.u32(byArray, 0), RawWaylandShmServer.u32(byArray, 4), RawWaylandShmServer.u32(byArray, 8), RawWaylandShmServer.u32(byArray, 12));
                this.windowGeometries.put(n, windowGeometry);
                stringBuilder.append(" xdg_surface.set_window_geometry x=").append(windowGeometry.x).append(" y=").append(windowGeometry.y).append(" w=").append(windowGeometry.width).append(" h=").append(windowGeometry.height).append("\n");
                return;
            }
            if (n18 != null && n2 == 1) {
                int n19 = RawWaylandShmServer.u32(byArray, 0);
                if (n == this.xdgSurfaceId) {
                    this.xdgToplevelId = n19;
                    stringBuilder.append(" xdg_surface.get_toplevel xdg_toplevel_id=").append(this.xdgToplevelId).append("\n");
                } else {
                    ChildToplevelState childToplevelState2 = new ChildToplevelState(n19, n, n18, ++this.popupSequence);
                    this.childToplevelsByRole.put(n19, childToplevelState2);
                    this.childToplevelsByXdg.put(n, childToplevelState2);
                    this.childToplevelsBySurface.put(n18, childToplevelState2);
                    Log.i((String)MainActivity.TAG, (String)("Wayland child toplevel=" + n19 + " xdgSurface=" + n + " wlSurface=" + n18));
                    stringBuilder.append(" child xdg_surface.get_toplevel id=").append(n19).append("\n");
                }
                return;
            }
            if (n18 != null && n2 == 2) {
                int n20 = RawWaylandShmServer.u32(byArray, 0);
                int n21 = RawWaylandShmServer.u32(byArray, 4);
                int n22 = RawWaylandShmServer.u32(byArray, 8);
                PositionerState positionerState2 = this.positioners.get(n22);
                if (positionerState2 == null) {
                    throw new IllegalStateException("xdg_surface.get_popup with unknown positioner " + n22);
                }
                PopupState popupState = new PopupState(n20, n, n18, n21, positionerState2, ++this.popupSequence);
                this.popups.put(n20, popupState);
                this.constrainPopup(popupState);
                this.popupsByXdgSurface.put(n, popupState);
                Log.i((String)MainActivity.TAG, (String)("Wayland popup=" + n20 + " xdgSurface=" + n + " wlSurface=" + n18 + " parentXdg=" + n21 + " configure=" + popupState.configureX + "," + popupState.configureY + " size=" + popupState.width + "x" + popupState.height));
                stringBuilder.append(" xdg_surface.get_popup popup_id=").append(n20).append(" parent=").append(n21).append(" positioner=").append(n22).append("\n");
                return;
            }
            if (n18 != null && n2 == 4) {
                int n23 = RawWaylandShmServer.u32(byArray, 0);
                PopupState popupState = this.popupsByXdgSurface.get(n);
                if (popupState != null) {
                    popupState.configureAcked = n23 == popupState.configureSerial;
                } else if (this.childToplevelsByXdg.containsKey(n)) {
                    ChildToplevelState childToplevelState3 = this.childToplevelsByXdg.get(n);
                    childToplevelState3.configureAcked = n23 == childToplevelState3.configureSerial;
                } else {
                    this.xdgConfigureAcked = n23 == this.xdgConfigureSerial;
                }
                stringBuilder.append(" xdg_surface.ack_configure serial=").append(n23).append("\n");
                return;
            }
            PopupState popupState = this.popups.get(n);
            if (popupState != null) {
                if (n2 == 0) {
                    if (this.pointerFocusSurfaceId == popupState.wlSurfaceId) {
                        this.pointerFocusSurfaceId = 0;
                        this.pointerInside = false;
                    }
                    if (this.pointerGrabSurfaceId == popupState.wlSurfaceId) {
                        this.pointerGrabSurfaceId = 0;
                    }
                    popupState.visible = false;
                    this.popups.remove(n);
                    this.popupsByXdgSurface.remove(popupState.xdgSurfaceId);
                    if (this.activePopupGrabId == n) {
                        PopupState popupState2 = this.popupsByXdgSurface.get(popupState.parentXdgSurfaceId);
                        this.activePopupGrabId = popupState2 == null ? 0 : popupState2.popupId;
                    }
                    this.restoreMainBitmap();
                    stringBuilder.append(" xdg_popup.destroy\n");
                } else if (n2 == 1) {
                    int n24 = RawWaylandShmServer.u32(byArray, 0);
                    int n25 = RawWaylandShmServer.u32(byArray, 4);
                    PopupState popupState3 = this.popupsByXdgSurface.get(popupState.parentXdgSurfaceId);
                    boolean bl = popupState3 == null || popupState3.grabbed;
                    popupState.grabbed = n24 == this.seatId && this.isKnownInputSerial(n25) && bl && !popupState.visible;
                    popupState.grabSerial = n25;
                    if (popupState.grabbed) {
                        this.activePopupGrabId = n;
                    } else {
                        this.sendPopupDone(localSocket, popupState, false);
                    }
                    stringBuilder.append(" xdg_popup.grab seat=").append(n24).append(" serial=").append(n25).append(" valid=").append(popupState.grabbed).append("\n");
                } else if (n2 == 2) {
                    int n26 = RawWaylandShmServer.u32(byArray, 0);
                    int n27 = RawWaylandShmServer.u32(byArray, 4);
                    PositionerState positionerState3 = this.positioners.get(n26);
                    if (positionerState3 == null) {
                        throw new IllegalStateException("xdg_popup.reposition with unknown positioner " + n26);
                    }
                    popupState.applyPositioner(positionerState3);
                    this.constrainPopup(popupState);
                    popupState.configureAcked = false;
                    byte[] byArray3 = new byte[4];
                    RawWaylandShmServer.putU32(byArray3, 0, n27);
                    this.writeMessage(localSocket, popupState.popupId, 2, byArray3);
                    this.sendPopupConfigure(localSocket, popupState, stringBuilder);
                    stringBuilder.append(" xdg_popup.reposition token=").append(n27).append("\n");
                } else {
                    stringBuilder.append(" xdg_popup.unknown\n");
                }
                return;
            }
            if (n == this.shmId && n2 == 0) {
                int n28;
                this.shmPoolId = n28 = RawWaylandShmServer.u32(byArray, 0);
                this.poolSize = RawWaylandShmServer.u32(byArray, 4);
                FileDescriptor[] fileDescriptorArray = localSocket.getAncillaryFileDescriptors();
                if (fileDescriptorArray != null) {
                    for (FileDescriptor fileDescriptor : fileDescriptorArray) {
                        this.pendingShmFds.addLast(fileDescriptor);
                    }
                    this.fdCount += fileDescriptorArray.length;
                }
                if (this.pendingShmFds.isEmpty()) {
                    throw new IllegalStateException("wl_shm.create_pool did not include fd");
                }
                this.shmFd = this.pendingShmFds.removeFirst();
                this.shmPools.put(n28, new ShmPoolState(this.shmFd, this.poolSize));
                stringBuilder.append(" wl_shm.create_pool pool_id=").append(n28).append(" size=").append(this.poolSize).append(" fds=").append(this.fdCount).append("\n");
                return;
            }
            ShmPoolState shmPoolState = this.shmPools.get(n);
            if (shmPoolState != null && n2 == 1) {
                ++this.destroyRequestCount;
                if (!this.interactivePointerMode) {
                    this.cleanupPending = true;
                }
                this.shmPools.remove(n);
                shmPoolState.destroyed = true;
                shmPoolState.closeIfUnused();
                stringBuilder.append(" wl_shm_pool.destroy\n");
                return;
            }
            if (shmPoolState != null && n2 == 2) {
                shmPoolState.size = RawWaylandShmServer.u32(byArray, 0);
                stringBuilder.append(" wl_shm_pool.resize size=").append(shmPoolState.size).append("\n");
                return;
            }
            if (shmPoolState != null && n2 == 0) {
                this.bufferId = RawWaylandShmServer.u32(byArray, 0);
                int n29 = RawWaylandShmServer.u32(byArray, 4);
                this.width = RawWaylandShmServer.u32(byArray, 8);
                this.height = RawWaylandShmServer.u32(byArray, 12);
                this.stride = RawWaylandShmServer.u32(byArray, 16);
                int n30 = RawWaylandShmServer.u32(byArray, 20);
                this.shmBuffers.put(this.bufferId, new ShmBufferState(shmPoolState, n29, this.width, this.height, this.stride, n30));
                ++shmPoolState.bufferRefs;
                stringBuilder.append(" wl_shm_pool.create_buffer buffer_id=").append(this.bufferId).append(" offset=").append(n29).append(" width=").append(this.width).append(" height=").append(this.height).append(" stride=").append(this.stride).append(" format=").append(n30).append("\n");
                return;
            }
            if (this.shmBuffers.containsKey(n) && n2 == 0) {
                ++this.destroyRequestCount;
                if (!this.interactivePointerMode) {
                    this.cleanupPending = true;
                }
                ShmBufferState removed = this.shmBuffers.remove(n);
                if (removed != null) {
                    removed.pool.bufferRefs = Math.max(0, removed.pool.bufferRefs - 1);
                    removed.pool.closeIfUnused();
                }
                stringBuilder.append(" wl_buffer.destroy\n");
                return;
            }
            if (this.regions.containsKey(n)) {
                Region region = this.regions.get(n);
                if (n2 == 0) {
                    this.regions.remove(n);
                    stringBuilder.append(" wl_region.destroy\n");
                } else if (n2 == 1 || n2 == 2) {
                    int n31 = RawWaylandShmServer.u32(byArray, 0);
                    int n32 = RawWaylandShmServer.u32(byArray, 4);
                    int n33 = RawWaylandShmServer.u32(byArray, 8);
                    int n34 = RawWaylandShmServer.u32(byArray, 12);
                    Rect rect = new Rect(n31, n32, n31 + n33, n32 + n34);
                    region.op(rect, n2 == 1 ? Region.Op.UNION : Region.Op.DIFFERENCE);
                    stringBuilder.append(n2 == 1 ? " wl_region.add" : " wl_region.subtract").append(" x=").append(n31).append(" y=").append(n32).append(" width=").append(n33).append(" height=").append(n34).append("\n");
                }
                return;
            }
            if (n == this.compositorId && n2 == 1) {
                int n35 = RawWaylandShmServer.u32(byArray, 0);
                this.regions.put(n35, new Region());
                stringBuilder.append(" wl_compositor.create_region region_id=").append(n35).append("\n");
                return;
            }
            if (n == this.compositorId && n2 == 0) {
                int n36 = RawWaylandShmServer.u32(byArray, 0);
                this.surfaceBufferScales.put(n36, 1);
                if (this.surfaceId == 0) {
                    this.surfaceId = n36;
                } else {
                    this.auxiliarySurfaceBuffers.put(n36, 0);
                }
                stringBuilder.append(" wl_compositor.create_surface surface_id=").append(n36).append("\n");
                return;
            }
            if (n == this.surfaceId && n2 == 0) {
                ++this.destroyRequestCount;
                this.cleanupPending = true;
                stringBuilder.append(" wl_surface.destroy\n");
                this.surfaceBufferScales.remove(n);
                this.surfaceId = 0;
                return;
            }
            if ((n == this.surfaceId || this.auxiliarySurfaceBuffers.containsKey(n)) && n2 == 5) {
                int n37 = RawWaylandShmServer.u32(byArray, 0);
                this.pendingSurfaceInputInfinite.put(n, n37 == 0);
                if (n37 == 0) {
                    this.pendingSurfaceInputRegions.remove(n);
                } else {
                    Region region = this.regions.get(n37);
                    this.pendingSurfaceInputRegions.put(n, region == null ? new Region() : new Region(region));
                }
                stringBuilder.append(" wl_surface.set_input_region region=").append(n37).append("\n");
                return;
            }
            if ((n == this.surfaceId || this.auxiliarySurfaceBuffers.containsKey(n)) && n2 == 8) {
                int n38 = RawWaylandShmServer.u32(byArray, 0);
                if (n38 <= 0) {
                    throw new IllegalStateException("wl_surface.set_buffer_scale must be positive");
                }
                this.surfaceBufferScales.put(n, n38);
                stringBuilder.append(" wl_surface.set_buffer_scale scale=").append(n38).append("\n");
                return;
            }
            if (n == this.surfaceId && n2 == 1) {
                this.attachedBufferId = RawWaylandShmServer.u32(byArray, 0);
                this.mainBufferAttachPending = true;
                stringBuilder.append(" wl_surface.attach buffer=").append(this.attachedBufferId).append(" x=").append(RawWaylandShmServer.u32(byArray, 4)).append(" y=").append(RawWaylandShmServer.u32(byArray, 8)).append("\n");
                return;
            }
            if (this.auxiliarySurfaceBuffers.containsKey(n) && n2 == 3) {
                int callbackId = RawWaylandShmServer.u32(byArray, 0);
                this.auxiliaryFrameCallbacks
                        .computeIfAbsent(n, ignored -> new ArrayDeque<Integer>())
                        .addLast(callbackId);
                stringBuilder.append(" auxiliary wl_surface.frame callback_id=").append(callbackId).append("\n");
                return;
            }
            if (this.auxiliarySurfaceBuffers.containsKey(n) && n2 == 1) {
                int n39 = RawWaylandShmServer.u32(byArray, 0);
                this.auxiliarySurfaceBuffers.put(n, n39);
                this.auxiliarySurfaceAttachPending.put(n, true);
                stringBuilder.append(" auxiliary wl_surface.attach buffer=").append(n39).append("\n");
                return;
            }
            if (this.auxiliarySurfaceBuffers.containsKey(n) && (n2 == 2 || n2 == 9)) {
                int n40 = n2 == 2 ? Math.max(1, this.surfaceBufferScales.getOrDefault(n, 1)) : 1;
                int n41 = RawWaylandShmServer.saturatedScale(RawWaylandShmServer.u32(byArray, 0), n40);
                int n42 = RawWaylandShmServer.saturatedScale(RawWaylandShmServer.u32(byArray, 4), n40);
                int n43 = RawWaylandShmServer.saturatedScale(RawWaylandShmServer.u32(byArray, 8), n40);
                int n44 = RawWaylandShmServer.saturatedScale(RawWaylandShmServer.u32(byArray, 12), n40);
                Rect rect = new Rect(n41, n42, Math.max(n41, RawWaylandShmServer.saturatedAdd(n41, n43)), Math.max(n42, RawWaylandShmServer.saturatedAdd(n42, n44)));
                Rect rect2 = this.auxiliarySurfaceDamage.get(n);
                if (rect2 == null) {
                    this.auxiliarySurfaceDamage.put(n, rect);
                } else {
                    rect2.union(rect);
                }
                stringBuilder.append(" auxiliary wl_surface.damage x=").append(n41).append(" y=").append(n42).append(" width=").append(n43).append(" height=").append(n44).append("\n");
                return;
            }
            if (this.auxiliarySurfaceBuffers.containsKey(n) && n2 == 0) {
                PopupState popupState4;
                this.auxiliarySurfaceBuffers.remove(n);
                this.auxiliarySurfaceAttachPending.remove(n);
                this.auxiliarySurfaceDamage.remove(n);
                this.auxiliaryFrameCallbacks.remove(n);
                this.surfaceInputRegions.remove(n);
                this.pendingSurfaceInputRegions.remove(n);
                this.pendingSurfaceInputInfinite.remove(n);
                this.surfaceBufferScales.remove(n);
                SubsurfaceState subsurfaceState2 = this.subsurfacesBySurface.remove(n);
                if (subsurfaceState2 != null) {
                    this.subsurfaces.remove(subsurfaceState2.subsurfaceId);
                    this.composeSurfaceTree();
                }
                if ((popupState4 = this.findPopupByWlSurface(n)) != null) {
                    this.popups.remove(popupState4.popupId);
                    this.popupsByXdgSurface.remove(popupState4.xdgSurfaceId);
                    if (this.activePopupGrabId == popupState4.popupId) {
                        PopupState popupState5 = this.popupsByXdgSurface.get(popupState4.parentXdgSurfaceId);
                        this.activePopupGrabId = popupState5 == null ? 0 : popupState5.popupId;
                    }
                    this.restoreMainBitmap();
                }
                stringBuilder.append(" auxiliary wl_surface.destroy\n");
                return;
            }
            if (this.auxiliarySurfaceBuffers.containsKey(n) && n2 == 6) {
                SubsurfaceState subsurfaceState3;
                ChildToplevelState childToplevelState4;
                this.applyPendingInputRegion(n);
                int n45 = this.auxiliarySurfaceBuffers.get(n);
                boolean bl = this.auxiliarySurfaceAttachPending.getOrDefault(n, false);
                boolean bl2 = bl || this.auxiliarySurfaceDamage.containsKey(n);
                PopupState popupState6 = this.findPopupByWlSurface(n);
                stringBuilder.append(" auxiliary wl_surface.commit\n");
                if (popupState6 != null && !popupState6.configureSent) {
                    this.sendPopupConfigure(localSocket, popupState6, stringBuilder);
                    return;
                }
                if (popupState6 != null && bl && n45 == 0) {
                    popupState6.visible = false;
                    popupState6.grabbed = false;
                    if (this.activePopupGrabId == popupState6.popupId) {
                        PopupState parentPopup = this.popupsByXdgSurface.get(popupState6.parentXdgSurfaceId);
                        this.activePopupGrabId = parentPopup != null && parentPopup.grabbed
                                ? parentPopup.popupId : 0;
                    }
                    if (this.pointerFocusSurfaceId == popupState6.wlSurfaceId) {
                        this.pointerFocusSurfaceId = 0;
                        this.pointerInside = false;
                    }
                    this.composeSurfaceTree();
                    stringBuilder.append(" xdg_popup.unmap null_buffer\n");
                } else if (popupState6 != null && bl2 && n45 != 0) {
                    this.commitPopupBuffer(popupState6, n45);
                }
                childToplevelState4 = this.childToplevelsBySurface.get(n);
                if (childToplevelState4 != null && bl && n45 == 0) {
                    childToplevelState4.visible = false;
                    childToplevelState4.bitmap = null;
                    if (this.pointerFocusSurfaceId == childToplevelState4.wlSurfaceId) {
                        this.pointerFocusSurfaceId = 0;
                        this.pointerInside = false;
                    }
                    ChildToplevelState remainingChild = this.topVisibleChildToplevel();
                    int restoredFocusSurface = remainingChild == null
                            ? this.surfaceId : remainingChild.wlSurfaceId;
                    if (this.keyboardId != 0 && restoredFocusSurface != 0
                            && this.keyboardFocusSurfaceId != restoredFocusSurface) {
                        try {
                            this.sendKeyboardFocus(localSocket, restoredFocusSurface, null);
                        } catch (Exception focusError) {
                            this.error = focusError.toString();
                            Log.e((String)MainActivity.TAG,
                                    (String)"Child unmap focus restore failed",
                                    (Throwable)focusError);
                        }
                    }
                    if (remainingChild == null) {
                        this.sendMainToplevelActivation(localSocket, true, stringBuilder);
                    }
                    this.composeSurfaceTree();
                    stringBuilder.append(" child_toplevel.unmap null_buffer\n");
                } else if (childToplevelState4 != null && !childToplevelState4.configureSent) {
                    this.sendMainToplevelActivation(localSocket, false, stringBuilder);
                    this.sendChildToplevelConfigure(localSocket, childToplevelState4, stringBuilder);
                    return;
                }
                if (childToplevelState4 != null && bl2 && n45 != 0) {
                    Bitmap incomingChildBitmap = this.readShmBitmap(n45);
                    Rect rect = this.auxiliarySurfaceDamage.remove(n);
                    Log.i((String)MainActivity.TAG, (String)("Child buffer commit surface=" + n + " buffer=" + n45 + " size=" + incomingChildBitmap.getWidth() + "x" + incomingChildBitmap.getHeight() + " damage=" + String.valueOf(rect) + " coverage=" + RawWaylandShmServer.sampledOpaqueCoverage((Bitmap)incomingChildBitmap)));
                    childToplevelState4.bitmap = incomingChildBitmap;
                    childToplevelState4.visible = true;
                    this.composeSurfaceTree();
                    Log.i((String)MainActivity.TAG, (String)("Child map focus keyboard=" + this.keyboardId
                            + " current=" + this.keyboardFocusSurfaceId
                            + " target=" + childToplevelState4.wlSurfaceId));
                    if (this.keyboardId != 0 && this.keyboardFocusSurfaceId != childToplevelState4.wlSurfaceId) {
                        try {
                            this.sendKeyboardFocus(localSocket, childToplevelState4.wlSurfaceId, null);
                            Log.i((String)MainActivity.TAG, (String)("Child map focus complete target="
                                    + childToplevelState4.wlSurfaceId));
                        } catch (Exception focusError) {
                            this.error = focusError.toString();
                            Log.e((String)MainActivity.TAG, (String)"Child map focus failed", (Throwable)focusError);
                        }
                    }
                }
                if ((subsurfaceState3 = this.subsurfacesBySurface.get(n)) != null && bl2 && n45 != 0) {
                    this.commitSubsurfaceBuffer(subsurfaceState3, n45);
                }
                if (bl2) {
                    this.auxiliarySurfaceDamage.remove(n);
                }
                if (bl && n45 != 0) {
                    this.writeMessage(localSocket, n45, 0, new byte[0]);
                    stringBuilder.append("server->client object=").append(n45).append(" opcode=0 wl_buffer.release auxiliary\n");
                    this.auxiliarySurfaceAttachPending.remove(n);
                }
                ArrayDeque<Integer> callbacks = this.auxiliaryFrameCallbacks.remove(n);
                if (callbacks != null) {
                    while (!callbacks.isEmpty()) {
                        int callbackId = callbacks.removeFirst();
                        this.sendU32Event(localSocket, callbackId, 0, 2, stringBuilder,
                                "wl_callback.done auxiliary frame");
                    }
                }
                return;
            }
            if (n == this.surfaceId && n2 == 3) {
                this.frameCallbackId = RawWaylandShmServer.u32(byArray, 0);
                this.mainFrameCallbacks.addLast(this.frameCallbackId);
                stringBuilder.append(" wl_surface.frame callback_id=").append(this.frameCallbackId).append("\n");
                return;
            }
            if (n == this.surfaceId && (n2 == 2 || n2 == 9)) {
                int n51 = RawWaylandShmServer.u32(byArray, 0);
                int n52 = RawWaylandShmServer.u32(byArray, 4);
                int n53 = RawWaylandShmServer.u32(byArray, 8);
                int n54 = RawWaylandShmServer.u32(byArray, 12);
                if (n2 == 2) {
                    int n55 = Math.max(1, this.surfaceBufferScales.getOrDefault(this.surfaceId, 1));
                    this.addMainDamage(n51 * n55, n52 * n55, n53 * n55, n54 * n55);
                } else {
                    this.addMainDamage(n51, n52, n53, n54);
                }
                stringBuilder.append(n2 == 2 ? " wl_surface.damage" : " wl_surface.damage_buffer").append(" x=").append(n51).append(" y=").append(n52).append(" w=").append(n53).append(" h=").append(n54).append("\n");
                return;
            }
            if (n == this.xdgWmBaseId && n2 == 0) {
                ++this.destroyRequestCount;
                this.cleanupPending = true;
                stringBuilder.append(" xdg_wm_base.destroy\n");
                this.xdgWmBaseId = 0;
                return;
            }
            if (n == this.surfaceId && n2 == 6) {
                this.applyPendingInputRegion(n);
                stringBuilder.append(" wl_surface.commit\n");
                if (this.sendServerEvents && this.xdgSurfaceId != 0 && !this.xdgConfigureSent) {
                    this.sendXdgConfigure(localSocket, stringBuilder);
                    return;
                }
                if (this.sendServerEvents && this.xdgSurfaceId != 0 && !this.xdgConfigureAcked) {
                    stringBuilder.append(" accepting commit for prior configure while resize ack is pending\n");
                }
                boolean bl = this.mainBufferAttachPending && this.attachedBufferId != 0;
                this.commitBuffer();
                this.mainBufferAttachPending = false;
                if (this.androidClipboardOfferPending && this.dataDeviceId != 0) {
                    this.androidClipboardOfferPending = false;
                    this.sendAndroidClipboardOffer(localSocket, stringBuilder);
                }
                while (!this.mainFrameCallbacks.isEmpty()) {
                    int callbackId = this.mainFrameCallbacks.removeFirst();
                    this.sendU32Event(localSocket, callbackId, 0, 2, stringBuilder, "wl_callback.done frame");
                    this.frameCallbackDoneSent = true;
                }
                this.frameCallbackId = 0;
                if (bl) {
                    this.writeMessage(localSocket, this.attachedBufferId, 0, new byte[0]);
                    this.bufferReleaseSent = true;
                    stringBuilder.append("server->client object=").append(this.attachedBufferId).append(" opcode=0 wl_buffer.release\n");
                }
                if (this.pointerId != 0 && !this.pointerEventsSent && !this.interactivePointerMode) {
                    this.sendPointerEvents(localSocket, stringBuilder);
                }
                if (this.keyboardId != 0 && !this.keyboardFocusSent) {
                    this.sendKeyboardFocus(localSocket, stringBuilder);
                }
                if (this.waitForPostCommitSync) {
                    this.postCommitPending = true;
                } else {
                    this.committed = true;
                }
                return;
            }
            stringBuilder.append(" unknown\n");
        }

        void noteAndroidInputConnectionCreated() {
            ++this.androidInputConnectionsCreated;
            this.appendAsyncEvent("android->bridge input_connection created");
        }

        boolean handleAndroidImeCommitText(CharSequence text) {
            LocalSocket client = this.connectedClient;
            if (client == null || text == null) {
                return false;
            }
            String value = text.toString();
            this.androidImeCommitEventsSent++;
            this.androidImeCommitChars += value.length();
            this.androidImeLastText = value;
            this.appendAsyncEvent("android->bridge ime.commitText chars=" + value.length());
            try {
                if (this.textInputId != 0 && this.textInputEnabled) {
                    this.writeMessage(client, this.textInputId, 3,
                            RawWaylandShmServer.stringPayload(value));
                    this.sendTextInputDone(client);
                    this.appendAsyncEvent("android->wayland zwp_text_input_v3.commit_string chars="
                            + value.length());
                    return true;
                }
                if (this.keyboardId == 0 || !this.keyboardFocusSent) {
                    return false;
                }
                long now = SystemClock.uptimeMillis();
                boolean sentAny = false;
                for (int i = 0; i < value.length(); i++) {
                    int evdevKey = RawWaylandShmServer.evdevKeyCodeForCharacter(value.charAt(i));
                    if (evdevKey == 0) {
                        continue;
                    }
                    this.keyboardLastKey = evdevKey;
                    this.sendKeyboardKey(client, evdevKey, now, true);
                    this.sendKeyboardKey(client, evdevKey, now, false);
                    this.androidImeSynthKeyEventsSent += 2;
                    sentAny = true;
                }
                return sentAny || value.isEmpty();
            } catch (Exception e) {
                this.appendAsyncEvent("android ime forwarding failed: " + e);
                this.error = e.toString();
                return false;
            }
        }

        boolean handleAndroidImeDelete() {
            LocalSocket client = this.connectedClient;
            if (client == null) {
                return false;
            }
            try {
                if (this.textInputId != 0 && this.textInputEnabled) {
                    byte[] delete = new byte[8];
                    RawWaylandShmServer.putU32(delete, 0, 1);
                    RawWaylandShmServer.putU32(delete, 4, 0);
                    this.writeMessage(client, this.textInputId, 4, delete);
                    this.sendTextInputDone(client);
                    this.appendAsyncEvent("android->wayland zwp_text_input_v3.delete_surrounding_text");
                    return true;
                }
                if (this.keyboardId == 0 || !this.keyboardFocusSent) {
                    return false;
                }
                long now = SystemClock.uptimeMillis();
                this.keyboardLastKey = 14;
                this.sendKeyboardKey(client, 14, now, true);
                this.sendKeyboardKey(client, 14, now, false);
                this.androidImeSynthKeyEventsSent += 2;
                this.appendAsyncEvent("android->bridge ime.delete");
                return true;
            } catch (Exception e) {
                this.appendAsyncEvent("android ime delete failed: " + e);
                this.error = e.toString();
                return false;
            }
        }
        private static int evdevKeyCodeForCharacter(char c) {
            if (c >= 'a' && c <= 'z') {
                return RawWaylandShmServer.evdevKeyCode(29 + (c - 97));
            }
            if (c >= 'A' && c <= 'Z') {
                return RawWaylandShmServer.evdevKeyCode(29 + (c - 65));
            }
            if (c >= '1' && c <= '9') {
                return RawWaylandShmServer.evdevKeyCode(8 + (c - 49));
            }
            if (c == '0') {
                return RawWaylandShmServer.evdevKeyCode(7);
            }
            if (c == ' ') {
                return RawWaylandShmServer.evdevKeyCode(62);
            }
            if (c == '-') {
                return 12;
            }
            if (c == '.') {
                return 52;
            }
            if (c == '\n') {
                return RawWaylandShmServer.evdevKeyCode(66);
            }
            return 0;
        }

        boolean handleAndroidKeyEvent(int n, int n2, long l) {
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.keyboardId == 0 || !this.keyboardFocusSent) {
                return false;
            }
            int n3 = RawWaylandShmServer.evdevKeyCode(n2);
            if (n == 0) {
                Log.i((String)MainActivity.TAG, (String)("Wayland key android=" + n2 + " evdev=" + n3 + " keyboardFocus=" + this.keyboardFocusSurfaceId));
            }
            if (n3 == 0) {
                return false;
            }
            ++this.androidKeyEventsSent;
            this.keyboardLastKey = n3;
            int n4 = RawWaylandShmServer.modifierMaskForAndroidKey(n2);
            try {
                if (n == 0) {
                    if (n2 == 50 && (this.keyboardModsDepressed & 4) != 0) {
                        this.publishAndroidClipboard();
                    }
                    if (n4 != 0) {
                        this.keyboardModsDepressed |= n4;
                    }
                    this.sendKeyboardKey(localSocket, n3, l, true);
                    if (n4 != 0) {
                        this.sendKeyboardModifiers(localSocket);
                    }
                    return true;
                }
                if (n == 1) {
                    this.sendKeyboardKey(localSocket, n3, l, false);
                    if (n4 != 0) {
                        this.keyboardModsDepressed &= ~n4;
                        this.sendKeyboardModifiers(localSocket);
                    }
                    return true;
                }
            }
            catch (Exception exception) {
                this.appendAsyncEvent("android key forwarding failed: " + String.valueOf(exception));
                this.error = exception.toString();
            }
            return false;
        }

        private boolean isWithinActivePopupGrab(PopupState popupState) {
            if (popupState == null) {
                return false;
            }
            PopupState popupState2 = this.popups.get(this.activePopupGrabId);
            while (popupState2 != null) {
                if (popupState2.popupId == popupState.popupId) {
                    return true;
                }
                popupState2 = this.popupsByXdgSurface.get(popupState2.parentXdgSurfaceId);
            }
            return false;
        }

        private void sendPopupDone(LocalSocket localSocket, PopupState popupState3, boolean bl) throws Exception {
            Object object;
            if (bl) {
                object = new ArrayList();
                block0: for (PopupState popupState4 : this.popups.values()) {
                    PopupState popupState5 = this.popupsByXdgSurface.get(popupState4.parentXdgSurfaceId);
                    while (popupState5 != null) {
                        if (popupState5.popupId == popupState3.popupId) {
                            ((ArrayList)object).add(popupState4);
                            continue block0;
                        }
                        popupState5 = this.popupsByXdgSurface.get(popupState5.parentXdgSurfaceId);
                    }
                }
                ((ArrayList<PopupState>)object).sort((PopupState popupState, PopupState popupState2) -> Integer.compare(popupState2.sequence, popupState.sequence));
                Iterator<PopupState> iterator = ((ArrayList)object).iterator();
                while (iterator.hasNext()) {
                    PopupState popupState4;
                    popupState4 = iterator.next();
                    if (!popupState4.visible) continue;
                    this.writeMessage(localSocket, popupState4.popupId, 1, new byte[0]);
                    popupState4.visible = false;
                    popupState4.grabbed = false;
                }
            }
            if (popupState3.visible || !popupState3.configureSent) {
                this.writeMessage(localSocket, popupState3.popupId, 1, new byte[0]);
            }
            popupState3.visible = false;
            popupState3.grabbed = false;
            object = this.popupsByXdgSurface.get(popupState3.parentXdgSurfaceId);
            this.activePopupGrabId = object != null && ((PopupState)object).grabbed ? ((PopupState)object).popupId : 0;
            this.appendAsyncEvent("server->client xdg_popup.popup_done popup=" + popupState3.popupId);
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        void logRecentProtocol() {
            String string;
            Object object = this.eventLock;
            synchronized (object) {
                string = this.eventLog == null ? this.log : this.eventLog.toString();
            }
            int n = Math.max(0, string.length() - 6000);
            String string2 = string.substring(n);
            for (int i = 0; i < string2.length(); i += 3000) {
                Log.i((String)MainActivity.TAG, (String)("Wayland trace:\n" + string2.substring(i, Math.min(string2.length(), i + 3000))));
            }
        }

        boolean hasActivePopupGrab() {
            PopupState popupState = this.popups.get(this.activePopupGrabId);
            return popupState != null && popupState.visible && popupState.grabbed;
        }

        boolean hasVisiblePopups() {
            for (PopupState popupState : this.popups.values()) {
                if (!popupState.visible) continue;
                return true;
            }
            return false;
        }

        boolean sendPopupEscape() {
            long l;
            ChildToplevelState childToplevelState = this.topVisibleChildToplevel();
            if (!this.hasVisiblePopups() && childToplevelState == null) {
                return false;
            }
            if (childToplevelState != null && this.keyboardId != 0 && this.keyboardFocusSurfaceId != childToplevelState.wlSurfaceId) {
                try {
                    this.sendKeyboardFocus(this.connectedClient, childToplevelState.wlSurfaceId, null);
                }
                catch (Exception exception) {
                    this.error = exception.toString();
                    return false;
                }
            }
            return this.handleAndroidKeyEvent(0, 111, l = SystemClock.uptimeMillis()) && this.handleAndroidKeyEvent(1, 111, l);
        }

        private static int modifierMaskForAndroidKey(int n) {
            if (n == 59 || n == 60) {
                return 1;
            }
            if (n == 113 || n == 114) {
                return 4;
            }
            if (n == 57 || n == 58) {
                return 8;
            }
            return 0;
        }

        private static int evdevKeyCode(int n) {
            if (n >= 29 && n <= 54) {
                int[] nArray = new int[]{30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
                return nArray[n - 29];
            }
            if (n >= 8 && n <= 16) {
                return n - 8 + 2;
            }
            if (n == 7) {
                return 11;
            }
            if (n == 66) {
                return 28;
            }
            if (n == 62) {
                return 57;
            }
            if (n == 69) {
                return 12;
            }
            if (n == 56) {
                return 52;
            }
            if (n == 67) {
                return 14;
            }
            if (n == 61) {
                return 15;
            }
            if (n == 19) {
                return 103;
            }
            if (n == 21) {
                return 105;
            }
            if (n == 22) {
                return 106;
            }
            if (n == 20) {
                return 108;
            }
            if (n == 122) {
                return 102;
            }
            if (n == 123) {
                return 107;
            }
            if (n == 92) {
                return 104;
            }
            if (n == 93) {
                return 109;
            }
            if (n == 124) {
                return 110;
            }
            if (n == 112) {
                return 111;
            }
            if (n >= 131 && n <= 140) {
                return 59 + n - 131;
            }
            if (n == 141) {
                return 87;
            }
            if (n == 142) {
                return 88;
            }
            if (n == 111) {
                return 1;
            }
            if (n == 59) {
                return 42;
            }
            if (n == 60) {
                return 54;
            }
            if (n == 113) {
                return 29;
            }
            if (n == 114) {
                return 97;
            }
            if (n == 57) {
                return 56;
            }
            if (n == 58) {
                return 100;
            }
            return 0;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        private void sendKeyboardKeymap(LocalSocket client, StringBuilder events) throws Exception {
            byte[] keymapBytes = (minimalXkbKeymap() + "\0").getBytes(StandardCharsets.UTF_8);
            File keymapFile = File.createTempFile("akm", ".xkb", socket.getParentFile());
            try (FileOutputStream out = new FileOutputStream(keymapFile)) {
                out.write(keymapBytes);
            }
            byte[] payload = new byte[8];
            putU32(payload, 0, 1);
            putU32(payload, 4, keymapBytes.length);
            try (FileInputStream in = new FileInputStream(keymapFile)) {
                writeMessageWithFd(client, keyboardId, 0, payload, in.getFD());
            } finally {
                keymapFile.delete();
            }
            keyboardKeymapSent = true;
            events.append("server->client object=").append(keyboardId).append(" opcode=0 wl_keyboard.keymap xkb_v1 fd size=").append(keymapBytes.length).append("\n");
            sendKeyboardRepeatInfo(client, events);
        }

        private static String minimalXkbKeymap() {
            return "xkb_keymap {\n"
                    + "xkb_keycodes \"archphene\" { minimum = 8; maximum = 255; <ESC> = 9; <AE01> = 10; <AE02> = 11; <AE03> = 12; <AE04> = 13; <AE05> = 14; <AE06> = 15; <AE07> = 16; <AE08> = 17; <AE09> = 18; <AE10> = 19; <TAB> = 23; <AD01> = 24; <AD02> = 25; <AD03> = 26; <AD04> = 27; <AD05> = 28; <AD06> = 29; <AD07> = 30; <AD08> = 31; <AD09> = 32; <AD10> = 33; <AC01> = 38; <AC02> = 39; <AC03> = 40; <AC04> = 41; <AC05> = 42; <AC06> = 43; <AC07> = 44; <AC08> = 45; <AC09> = 46; <AB01> = 52; <AB02> = 53; <AB03> = 54; <AB04> = 55; <AB05> = 56; <AB06> = 57; <AB07> = 58; <LFSH> = 50; <RTSH> = 62; <LCTL> = 37; <RCTL> = 105; <LALT> = 64; <RALT> = 108; <SPCE> = 65; <RTRN> = 36; <BKSP> = 22; <UP> = 111; <LEFT> = 113; <RGHT> = 114; <DOWN> = 116; <HOME> = 110; <END> = 115; <PGUP> = 112; <PGDN> = 117; <INS> = 118; <DELE> = 119; <FK01> = 67; <FK02> = 68; <FK03> = 69; <FK04> = 70; <FK05> = 71; <FK06> = 72; <FK07> = 73; <FK08> = 74; <FK09> = 75; <FK10> = 76; <FK11> = 95; <FK12> = 96; };\n"
                    + "xkb_types \"archphene\" { virtual_modifiers NumLock,Alt,LevelThree; type \"ONE_LEVEL\" { modifiers = none; map[None] = Level1; level_name[Level1] = \"Any\"; }; type \"TWO_LEVEL\" { modifiers = Shift; map[None] = Level1; map[Shift] = Level2; level_name[Level1] = \"Base\"; level_name[Level2] = \"Shift\"; }; };\n"
                    + "xkb_compatibility \"archphene\" { };\n"
                    + "xkb_symbols \"archphene\" { key <ESC> { [ Escape ] }; modifier_map Shift { <LFSH>, <RTSH> }; modifier_map Control { <LCTL>, <RCTL> }; modifier_map Mod1 { <LALT>, <RALT> }; key <LFSH> { [ Shift_L ] }; key <RTSH> { [ Shift_R ] }; key <LCTL> { [ Control_L ] }; key <RCTL> { [ Control_R ] }; key <LALT> { [ Alt_L ] }; key <RALT> { [ Alt_R ] }; key <AE01> { [ 1 ] }; key <AE02> { [ 2 ] }; key <AE03> { [ 3 ] }; key <AE04> { [ 4 ] }; key <AE05> { [ 5 ] }; key <AE06> { [ 6 ] }; key <AE07> { [ 7 ] }; key <AE08> { [ 8 ] }; key <AE09> { [ 9 ] }; key <AE10> { [ 0 ] }; key <AD01> { type=\"TWO_LEVEL\", [ q, Q ] }; key <AD02> { type=\"TWO_LEVEL\", [ w, W ] }; key <AD03> { type=\"TWO_LEVEL\", [ e, E ] }; key <AD04> { type=\"TWO_LEVEL\", [ r, R ] }; key <AD05> { type=\"TWO_LEVEL\", [ t, T ] }; key <AD06> { type=\"TWO_LEVEL\", [ y, Y ] }; key <AD07> { type=\"TWO_LEVEL\", [ u, U ] }; key <AD08> { type=\"TWO_LEVEL\", [ i, I ] }; key <AD09> { type=\"TWO_LEVEL\", [ o, O ] }; key <AD10> { type=\"TWO_LEVEL\", [ p, P ] }; key <AC01> { type=\"TWO_LEVEL\", [ a, A ] }; key <AC02> { type=\"TWO_LEVEL\", [ s, S ] }; key <AC03> { type=\"TWO_LEVEL\", [ d, D ] }; key <AC04> { type=\"TWO_LEVEL\", [ f, F ] }; key <AC05> { type=\"TWO_LEVEL\", [ g, G ] }; key <AC06> { type=\"TWO_LEVEL\", [ h, H ] }; key <AC07> { type=\"TWO_LEVEL\", [ j, J ] }; key <AC08> { type=\"TWO_LEVEL\", [ k, K ] }; key <AC09> { type=\"TWO_LEVEL\", [ l, L ] }; key <AB01> { type=\"TWO_LEVEL\", [ z, Z ] }; key <AB02> { type=\"TWO_LEVEL\", [ x, X ] }; key <AB03> { type=\"TWO_LEVEL\", [ c, C ] }; key <AB04> { type=\"TWO_LEVEL\", [ v, V ] }; key <AB05> { type=\"TWO_LEVEL\", [ b, B ] }; key <AB06> { type=\"TWO_LEVEL\", [ n, N ] }; key <AB07> { type=\"TWO_LEVEL\", [ m, M ] }; key <SPCE> { [ space ] }; key <RTRN> { [ Return ] }; key <BKSP> { [ BackSpace ] }; key <UP> { [ Up ] }; key <LEFT> { [ Left ] }; key <RGHT> { [ Right ] }; key <DOWN> { [ Down ] }; key <HOME> { [ Home ] }; key <END> { [ End ] }; key <PGUP> { [ Prior ] }; key <PGDN> { [ Next ] }; key <INS> { [ Insert ] }; key <DELE> { [ Delete ] }; key <FK01> { [ F1 ] }; key <FK02> { [ F2 ] }; key <FK03> { [ F3 ] }; key <FK04> { [ F4 ] }; key <FK05> { [ F5 ] }; key <FK06> { [ F6 ] }; key <FK07> { [ F7 ] }; key <FK08> { [ F8 ] }; key <FK09> { [ F9 ] }; key <FK10> { [ F10 ] }; key <FK11> { [ F11 ] }; key <FK12> { [ F12 ] }; };\n"
                    + "xkb_geometry \"archphene\" { };\n"
                    + "};\n";
        }

        private void sendKeyboardRepeatInfo(LocalSocket localSocket, StringBuilder stringBuilder) throws Exception {
            this.keyboardRepeatRate = 25;
            this.keyboardRepeatDelay = 400;
            byte[] byArray = new byte[8];
            RawWaylandShmServer.putU32(byArray, 0, this.keyboardRepeatRate);
            RawWaylandShmServer.putU32(byArray, 4, this.keyboardRepeatDelay);
            this.writeMessage(localSocket, this.keyboardId, 5, byArray);
            this.keyboardRepeatInfoSent = true;
            stringBuilder.append("server->client object=").append(this.keyboardId).append(" opcode=5 wl_keyboard.repeat_info rate=").append(this.keyboardRepeatRate).append(" delay=").append(this.keyboardRepeatDelay).append("\n");
        }

        private void notifyAndroidTextInput(boolean enabled) {
            this.notifyAndroidTextInput(enabled, true);
        }

        private void notifyAndroidTextInput(boolean enabled, boolean allowKeyboardNavigationRetention) {
            MainActivity activity = MainActivity.currentActivity.get();
            if (activity != null) {
                activity.runOnUiThread(() -> activity.setWaylandTextInputEnabled(
                        enabled, allowKeyboardNavigationRetention));
            }
            this.appendAsyncEvent("wayland->android text-input enabled=" + enabled
                    + " retainable=" + allowKeyboardNavigationRetention);
        }

        private void sendTextInputEnter(LocalSocket client, int targetSurfaceId) throws Exception {
            if (this.textInputId == 0) {
                return;
            }
            byte[] payload = new byte[4];
            RawWaylandShmServer.putU32(payload, 0, targetSurfaceId);
            this.writeMessage(client, this.textInputId, 0, payload);
            this.appendAsyncEvent("server->client zwp_text_input_v3.enter surface=" + targetSurfaceId);
        }

        private void sendTextInputLeave(LocalSocket client, int targetSurfaceId) throws Exception {
            if (this.textInputId == 0) {
                return;
            }
            byte[] payload = new byte[4];
            RawWaylandShmServer.putU32(payload, 0, targetSurfaceId);
            this.writeMessage(client, this.textInputId, 1, payload);
            this.textInputEnabled = false;
            this.textInputPendingEnabled = false;
            this.notifyAndroidTextInput(false, false);
            this.appendAsyncEvent("server->client zwp_text_input_v3.leave surface=" + targetSurfaceId);
        }

        private void sendTextInputDone(LocalSocket client) throws Exception {
            byte[] payload = new byte[4];
            RawWaylandShmServer.putU32(payload, 0, this.textInputCommitSerial);
            this.writeMessage(client, this.textInputId, 5, payload);
        }
        private void sendKeyboardFocus(LocalSocket localSocket, StringBuilder stringBuilder) throws Exception {
            this.sendKeyboardFocus(localSocket, this.surfaceId, stringBuilder);
        }

        private void sendKeyboardFocus(LocalSocket localSocket, int n, StringBuilder stringBuilder) throws Exception {
            byte[] byArray;
            int previousFocusSurfaceId = this.keyboardFocusSurfaceId;
            if (previousFocusSurfaceId != 0 && previousFocusSurfaceId != n) {
                this.sendTextInputLeave(localSocket, previousFocusSurfaceId);
                byArray = new byte[8];
                RawWaylandShmServer.putU32(byArray, 0, this.pointerSerial++);
                RawWaylandShmServer.putU32(byArray, 4, previousFocusSurfaceId);
                this.writeMessage(localSocket, this.keyboardId, 2, byArray);
            }
            byArray = new byte[12];
            RawWaylandShmServer.putU32(byArray, 0, this.pointerSerial++);
            RawWaylandShmServer.putU32(byArray, 4, n);
            RawWaylandShmServer.putU32(byArray, 8, 0);
            this.writeMessage(localSocket, this.keyboardId, 1, byArray);
            byte[] byArray2 = new byte[20];
            RawWaylandShmServer.putU32(byArray2, 0, this.pointerSerial++);
            this.writeMessage(localSocket, this.keyboardId, 4, byArray2);
            this.keyboardFocusSent = true;
            this.keyboardFocusSurfaceId = n;
            if (previousFocusSurfaceId != n) {
                this.sendTextInputEnter(localSocket, n);
            }
            if (stringBuilder != null) {
                stringBuilder.append("server->client object=").append(this.keyboardId).append(" opcode=1 wl_keyboard.enter surface=").append(n).append("\n");
                stringBuilder.append("server->client object=").append(this.keyboardId).append(" opcode=4 wl_keyboard.modifiers zero\n");
            } else {
                this.appendAsyncEvent("android->wayland wl_keyboard.enter surface=" + n);
            }
        }

        private void sendKeyboardModifiers(LocalSocket localSocket) throws Exception {
            byte[] byArray = new byte[20];
            int n = this.pointerSerial++;
            RawWaylandShmServer.putU32(byArray, 0, n);
            this.rememberInputSerial(n);
            RawWaylandShmServer.putU32(byArray, 4, this.keyboardModsDepressed);
            this.writeMessage(localSocket, this.keyboardId, 4, byArray);
            ++this.keyboardModifiersSent;
            this.keyboardLastMods = this.keyboardModsDepressed;
            this.appendAsyncEvent("android->wayland object=" + this.keyboardId + " opcode=4 wl_keyboard.modifiers depressed=" + this.keyboardModsDepressed);
        }

        private void sendKeyboardKey(LocalSocket localSocket, int n, long l, boolean bl) throws Exception {
            byte[] byArray = new byte[16];
            int n2 = this.pointerSerial++;
            RawWaylandShmServer.putU32(byArray, 0, n2);
            RawWaylandShmServer.putU32(byArray, 4, (int)l);
            RawWaylandShmServer.putU32(byArray, 8, n);
            RawWaylandShmServer.putU32(byArray, 12, bl ? 1 : 0);
            this.writeMessage(localSocket, this.keyboardId, 3, byArray);
            ++this.keyboardKeyEventsSent;
            this.rememberInputSerial(n2);
            this.appendAsyncEvent("android->wayland object=" + this.keyboardId + " opcode=3 wl_keyboard.key key=" + n + " " + (bl ? "pressed" : "released"));
        }

        synchronized void requestResize(int n, int n2) {
            int nextOutputWidth = Math.max(320, Math.min(4096, n));
            int nextOutputHeight = Math.max(240, Math.min(4096, n2));
            int nextConfigureWidth = Math.max(160, nextOutputWidth / this.outputScale);
            int nextConfigureHeight = Math.max(120, nextOutputHeight / this.outputScale);
            if (nextOutputWidth == this.outputWidth && nextOutputHeight == this.outputHeight) return;
            boolean orientationChanged = (this.outputWidth > this.outputHeight)
                    != (nextOutputWidth > nextOutputHeight);
            this.outputWidth = nextOutputWidth;
            this.outputHeight = nextOutputHeight;
            this.configureWidth = nextConfigureWidth;
            this.configureHeight = nextConfigureHeight;
            LocalSocket client = this.connectedClient;
            if (client == null) return;
            try {
                StringBuilder events = new StringBuilder();
                if (orientationChanged) this.dismissPopupsForViewportChange(client, events);
                if (this.outputId != 0) this.sendOutputMode(client, this.outputId, events);
                if (this.xdgSurfaceId != 0 && this.xdgToplevelId != 0) {
                    this.xdgConfigureAcked = false;
                    this.sendXdgConfigure(client, events);
                    for (ChildToplevelState child : this.childToplevelsByXdg.values()) {
                        if (!child.visible) continue;
                        child.configureAcked = false;
                        this.sendChildToplevelConfigure(client, child, events);
                    }
                }
                this.appendAsyncEvent(events.toString().trim());
            } catch (Exception exception) {
                this.appendAsyncEvent("android resize forwarding failed: " + exception);
                this.error = exception.toString();
            }
        }

        private void sendOutputMode(LocalSocket client, int output, StringBuilder events) throws Exception {
            byte[] mode = new byte[16];
            RawWaylandShmServer.putU32(mode, 0, 1);
            RawWaylandShmServer.putU32(mode, 4, this.outputWidth);
            RawWaylandShmServer.putU32(mode, 8, this.outputHeight);
            RawWaylandShmServer.putU32(mode, 12, 60000);
            this.writeMessage(client, output, 1, mode);
            this.writeMessage(client, output, 2, new byte[0]);
            this.outputDoneSent = true;
            events.append("server->client object=").append(output)
                    .append(" opcode=1 wl_output.mode current width=").append(this.outputWidth)
                    .append(" height=").append(this.outputHeight).append(" refresh=60000\n");
            events.append("server->client object=").append(output).append(" opcode=2 wl_output.done\n");
        }

        private void dismissPopupsForViewportChange(LocalSocket client, StringBuilder events) throws Exception {
            ArrayList<PopupState> stack = new ArrayList<>(this.popups.values());
            stack.sort((left, right) -> Integer.compare(right.sequence, left.sequence));
            boolean changed = false;
            for (PopupState popup : stack) {
                if (!popup.visible) continue;
                this.writeMessage(client, popup.popupId, 1, new byte[0]);
                popup.visible = false;
                popup.grabbed = false;
                changed = true;
                events.append("server->client object=").append(popup.popupId)
                        .append(" opcode=1 xdg_popup.popup_done reason=viewport-change\n");
            }
            if (changed) {
                this.activePopupGrabId = 0;
                this.pointerGrabSurfaceId = 0;
                this.pointerFocusSurfaceId = this.surfaceId;
                this.restoreMainBitmap();
            }
        }

        private void applyPendingInputRegion(int n) {
            Boolean bl = this.pendingSurfaceInputInfinite.remove(n);
            if (bl == null) {
                return;
            }
            if (bl.booleanValue()) {
                this.surfaceInputRegions.remove(n);
            } else {
                Region region = this.pendingSurfaceInputRegions.remove(n);
                this.surfaceInputRegions.put(n, region == null ? new Region() : new Region(region));
            }
        }

        private boolean surfaceAcceptsInput(int n, int n2, int n3) {
            Region region = this.surfaceInputRegions.get(n);
            return region == null || region.contains(n2, n3);
        }

        boolean handleAndroidScrollEvent(float f, float f2, float f3, long l) {
            if (f3 == 0.0f || !this.handleAndroidMotionEvent(2, f, f2, l)) {
                return false;
            }
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.pointerId == 0) {
                return false;
            }
            try {
                byte[] byArray = new byte[4];
                RawWaylandShmServer.putU32(byArray, 0, 0);
                this.writeMessage(localSocket, this.pointerId, 6, byArray);
                byte[] byArray2 = new byte[8];
                RawWaylandShmServer.putU32(byArray2, 0, 0);
                RawWaylandShmServer.putU32(byArray2, 4, f3 > 0.0f ? -1 : 1);
                this.writeMessage(localSocket, this.pointerId, 8, byArray2);
                byte[] byArray3 = new byte[12];
                RawWaylandShmServer.putU32(byArray3, 0, (int)l);
                RawWaylandShmServer.putU32(byArray3, 4, 0);
                RawWaylandShmServer.putU32(byArray3, 8, Math.round(-f3 * 15.0f * 256.0f));
                this.writeMessage(localSocket, this.pointerId, 4, byArray3);
                this.sendPointerFrame(localSocket);
                this.appendAsyncEvent("android->wayland wl_pointer.axis vertical=" + f3);
                return true;
            }
            catch (Exception exception) {
                this.appendAsyncEvent("android scroll forwarding failed: " + String.valueOf(exception));
                this.error = exception.toString();
                return false;
            }
        }

        boolean handleAndroidPointerExit() {
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.pointerId == 0 || !this.pointerInside || this.pointerGrabSurfaceId != 0) {
                return false;
            }
            try {
                this.sendPointerLeave(localSocket, this.pointerFocusSurfaceId);
                this.sendPointerFrame(localSocket);
                this.pointerInside = false;
                this.pointerFocusSurfaceId = 0;
                return true;
            }
            catch (Exception exception) {
                this.appendAsyncEvent("android hover exit forwarding failed: " + String.valueOf(exception));
                this.error = exception.toString();
                return false;
            }
        }


        boolean handleAndroidMotionEvent(int n, float f, float f2, long l) {
            return this.handleAndroidInputEvent(n, f, f2, l, true);
        }

        boolean prepareAndroidTouchTarget(float f, float f2, long l) {
            return this.handleAndroidInputEvent(2, f, f2, l, false);
        }

        private boolean handleAndroidInputEvent(int n, float f, float f2, long l, boolean bl) {
            int n2;
            int n3;
            int n4;
            ChildToplevelState childToplevelState;
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.pointerId == 0 || this.surfaceId == 0) {
                return false;
            }
            int previousSurfaceX = this.pointerSurfaceX;
            int previousSurfaceY = this.pointerSurfaceY;
            int n5 = Math.max(0, Math.min(this.outputWidth - 1, Math.round(f)));
            int n6 = Math.max(0, Math.min(this.outputHeight - 1, Math.round(f2)));
            int n7 = n5 + this.presentationOriginX();
            int n8 = n6 + this.presentationOriginY();
            this.pointerLastX = n5;
            this.pointerLastY = n6;
            ++this.androidPointerEventsSent;
            PopupState popupState = this.findVisiblePopupAt(n5, n6);
            if (n == 0 && this.activePopupGrabId != 0 && !this.isWithinActivePopupGrab(popupState)) {
                PopupState popupState2 = this.popups.get(this.activePopupGrabId);
                if (popupState2 != null) {
                    try {
                        this.sendPopupDone(localSocket, popupState2, true);
                        this.composeSurfaceTree();
                    }
                    catch (Exception exception) {
                        this.appendAsyncEvent("popup dismissal failed: " + String.valueOf(exception));
                        this.error = exception.toString();
                    }
                }
                return true;
            }
            SubsurfaceState subsurfaceState = popupState == null ? this.findVisibleSubsurfaceAt(n5, n6) : null;
            ChildToplevelState childToplevelState2 = childToplevelState = popupState == null && subsurfaceState == null ? this.findVisibleChildToplevelAt(n5, n6) : null;
            if (popupState == null && subsurfaceState == null && childToplevelState == null && this.topVisibleChildToplevel() != null) {
                return true;
            }
            if (popupState == null && subsurfaceState == null && childToplevelState == null && this.compactMainPresentation && (n5 < this.mainDisplayX || n6 < this.mainDisplayY || n5 >= this.mainDisplayX + this.mainDisplayWidth || n6 >= this.mainDisplayY + this.mainDisplayHeight)) {
                return true;
            }
            n4 = popupState != null ? popupState.wlSurfaceId : (subsurfaceState != null ? subsurfaceState.wlSurfaceId : (childToplevelState != null ? childToplevelState.wlSurfaceId : this.surfaceId));
            if (n == 0) {
                this.pointerGrabSurfaceId = n4;
            } else if (this.pointerGrabSurfaceId != 0) {
                n4 = this.pointerGrabSurfaceId;
                popupState = this.findPopupByWlSurface(n4);
                subsurfaceState = popupState == null ? this.subsurfacesBySurface.get(n4) : null;
                childToplevelState = popupState == null && subsurfaceState == null ? this.childToplevelsBySurface.get(n4) : null;
            }
            int n10 = Math.max(1, this.surfaceBufferScales.getOrDefault(n4, 1));
            if (popupState != null) {
                n3 = Math.round((float)(n5 - popupState.displayX) / popupState.pixelsPerLogical);
                n2 = Math.round((float)(n6 - popupState.displayY) / popupState.pixelsPerLogical);
            } else if (subsurfaceState != null) {
                n3 = Math.round((float)(n5 - subsurfaceState.displayX) / subsurfaceState.pixelsPerLogical);
                n2 = Math.round((float)(n6 - subsurfaceState.displayY) / subsurfaceState.pixelsPerLogical);
            } else if (childToplevelState != null) {
                n3 = (Math.round((float)((n5 - childToplevelState.displayX) * childToplevelState.sourceWidth) / (float)childToplevelState.displayWidth) + childToplevelState.bufferOriginX) / n10;
                n2 = (Math.round((float)((n6 - childToplevelState.displayY) * childToplevelState.sourceHeight) / (float)childToplevelState.displayHeight) + childToplevelState.bufferOriginY) / n10;
            } else if (this.compactMainPresentation) {
                n3 = (Math.round((float)((n5 - this.mainDisplayX) * this.mainSourceWidth) / (float)Math.max(1, this.mainDisplayWidth)) + this.presentationOriginX()) / n10;
                n2 = (Math.round((float)((n6 - this.mainDisplayY) * this.mainSourceHeight) / (float)Math.max(1, this.mainDisplayHeight)) + this.presentationOriginY()) / n10;
            } else {
                n3 = n7 / n10;
                n2 = n8 / n10;
            }
            if ((n == 1 || n == 3) && this.pointerGrabSurfaceId != 0) {
                n3 = previousSurfaceX;
                n2 = previousSurfaceY;
            }
            this.pointerSurfaceX = n3;
            this.pointerSurfaceY = n2;
            if (!bl) {
                this.preparedTouchSurfaceId = n4;
                return true;
            }
            if (n == 0) {
                Log.i((String)MainActivity.TAG, (String)("Wayland pointer px=" + n5 + " py=" + n6 + " target=" + n4 + " local=" + n3 + "," + n2 + " child=" + (childToplevelState != null) + (String)(childToplevelState == null ? "" : " display=" + childToplevelState.displayX + "," + childToplevelState.displayY + " " + childToplevelState.displayWidth + "x" + childToplevelState.displayHeight + " source=" + childToplevelState.sourceWidth + "x" + childToplevelState.sourceHeight + " origin=" + childToplevelState.bufferOriginX + "," + childToplevelState.bufferOriginY + " scale=" + n10)));
            }
            try {
                if (!this.pointerInside || this.pointerFocusSurfaceId != n4) {
                    if (this.pointerInside && this.pointerFocusSurfaceId != 0) {
                        this.sendPointerLeave(localSocket, this.pointerFocusSurfaceId);
                    }
                    this.sendPointerEnter(localSocket, n4, n3, n2);
                    this.pointerInside = true;
                    this.pointerFocusSurfaceId = n4;
                }
                if (n == 0) {
                    this.sendPointerMotion(localSocket, n3, n2, l);
                    this.sendPointerFrame(localSocket);
                    if (this.keyboardId != 0 && this.keyboardFocusSurfaceId != n4) {
                        this.sendKeyboardFocus(localSocket, n4, null);
                    }
                    this.sendPointerButton(localSocket, l, true);
                    this.sendPointerFrame(localSocket);
                    return true;
                }
                if (n == 2) {
                    this.sendPointerMotion(localSocket, n3, n2, l);
                    this.sendPointerFrame(localSocket);
                    return true;
                }
                if (n == 1 || n == 3) {
                    this.sendPointerButton(localSocket, l, false);
                    this.sendPointerFrame(localSocket);
                    this.pointerGrabSurfaceId = 0;
                    return true;
                }
            }
            catch (Exception exception) {
                this.appendAsyncEvent("android pointer forwarding failed: " + String.valueOf(exception));
                this.error = exception.toString();
            }
            return false;
        }

        private ChildToplevelState topVisibleChildToplevel() {
            ChildToplevelState childToplevelState = null;
            for (ChildToplevelState childToplevelState2 : this.childToplevelsByXdg.values()) {
                if (!childToplevelState2.visible || childToplevelState != null
                        && childToplevelState2.sequence <= childToplevelState.sequence) continue;
                childToplevelState = childToplevelState2;
            }
            return childToplevelState;
        }

        private ChildToplevelState findVisibleChildToplevelAt(int n, int n2) {
            ChildToplevelState childToplevelState = null;
            for (ChildToplevelState childToplevelState2 : this.childToplevelsByXdg.values()) {
                int n3;
                if (!childToplevelState2.visible || n < childToplevelState2.displayX || n2 < childToplevelState2.displayY || n >= childToplevelState2.displayX + childToplevelState2.displayWidth || n2 >= childToplevelState2.displayY + childToplevelState2.displayHeight || childToplevelState != null && childToplevelState2.sequence <= childToplevelState.sequence) continue;
                int n4 = Math.max(1, this.surfaceBufferScales.getOrDefault(childToplevelState2.wlSurfaceId, 1));
                int n5 = (Math.round((float)((n - childToplevelState2.displayX) * childToplevelState2.sourceWidth) / (float)childToplevelState2.displayWidth) + childToplevelState2.bufferOriginX) / n4;
                if (!this.surfaceAcceptsInput(childToplevelState2.wlSurfaceId, n5, n3 = (Math.round((float)((n2 - childToplevelState2.displayY) * childToplevelState2.sourceHeight) / (float)childToplevelState2.displayHeight) + childToplevelState2.bufferOriginY) / n4)) continue;
                childToplevelState = childToplevelState2;
            }
            return childToplevelState;
        }

        private SubsurfaceState findVisibleSubsurfaceAt(int n, int n2) {
            SubsurfaceState subsurfaceState = null;
            for (SubsurfaceState subsurfaceState2 : this.subsurfaces.values()) {
                int n3;
                int n4;
                if (!subsurfaceState2.visible || !subsurfaceState2.aboveParent || subsurfaceState2.bitmap == null || n < subsurfaceState2.displayX || n2 < subsurfaceState2.displayY || n >= subsurfaceState2.displayX + subsurfaceState2.displayWidth || n2 >= subsurfaceState2.displayY + subsurfaceState2.displayHeight || !this.surfaceAcceptsInput(subsurfaceState2.wlSurfaceId, n4 = Math.round((float)(n - subsurfaceState2.displayX) / subsurfaceState2.pixelsPerLogical), n3 = Math.round((float)(n2 - subsurfaceState2.displayY) / subsurfaceState2.pixelsPerLogical)) || subsurfaceState != null && subsurfaceState2.sequence <= subsurfaceState.sequence) continue;
                subsurfaceState = subsurfaceState2;
            }
            return subsurfaceState;
        }

        private PopupState findVisiblePopupAt(int n, int n2) {
            PopupState popupState = null;
            for (PopupState popupState2 : this.popups.values()) {
                int n3;
                int n4;
                if (!popupState2.visible || !popupState2.grabbed || n < popupState2.displayX || n2 < popupState2.displayY || n >= popupState2.displayX + popupState2.displayWidth || n2 >= popupState2.displayY + popupState2.displayHeight || !this.surfaceAcceptsInput(popupState2.wlSurfaceId, n4 = Math.round((float)(n - popupState2.displayX) / popupState2.pixelsPerLogical), n3 = Math.round((float)(n2 - popupState2.displayY) / popupState2.pixelsPerLogical)) || popupState != null && popupState2.sequence <= popupState.sequence) continue;
                popupState = popupState2;
            }
            return popupState;
        }

        boolean handleAndroidTouchEvent(int n, long l) {
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.touchId == 0) {
                return false;
            }
            try {
                if (n == 0) {
                    int n2 = this.preparedTouchSurfaceId;
                    if (n2 == 0) {
                        return false;
                    }
                    if (this.keyboardId != 0 && this.keyboardFocusSurfaceId != n2) {
                        this.sendKeyboardFocus(localSocket, n2, null);
                    }
                    int n3 = this.pointerSerial++;
                    byte[] byArray = new byte[24];
                    RawWaylandShmServer.putU32(byArray, 0, n3);
                    RawWaylandShmServer.putU32(byArray, 4, (int)l);
                    RawWaylandShmServer.putU32(byArray, 8, n2);
                    RawWaylandShmServer.putU32(byArray, 12, 0);
                    RawWaylandShmServer.putU32(byArray, 16, this.pointerSurfaceX * 256);
                    RawWaylandShmServer.putU32(byArray, 20, this.pointerSurfaceY * 256);
                    this.writeMessage(localSocket, this.touchId, 0, byArray);
                    this.writeMessage(localSocket, this.touchId, 3, new byte[0]);
                    this.rememberInputSerial(n3);
                    this.touchFocusSurfaceId = n2;
                    this.appendAsyncEvent("android->wayland wl_touch.down surface=" + n2 + " x=" + this.pointerSurfaceX + " y=" + this.pointerSurfaceY);
                    return true;
                }
                if (n == 2 && this.touchFocusSurfaceId != 0) {
                    byte[] byArray = new byte[16];
                    RawWaylandShmServer.putU32(byArray, 0, (int)l);
                    RawWaylandShmServer.putU32(byArray, 4, 0);
                    RawWaylandShmServer.putU32(byArray, 8, this.pointerSurfaceX * 256);
                    RawWaylandShmServer.putU32(byArray, 12, this.pointerSurfaceY * 256);
                    this.writeMessage(localSocket, this.touchId, 2, byArray);
                    this.writeMessage(localSocket, this.touchId, 3, new byte[0]);
                    this.appendAsyncEvent("android->wayland wl_touch.motion x="
                            + this.pointerSurfaceX + " y=" + this.pointerSurfaceY);
                    return true;
                }
                if (n == 1 && this.touchFocusSurfaceId != 0) {
                    byte[] byArray = new byte[12];
                    RawWaylandShmServer.putU32(byArray, 0, this.pointerSerial++);
                    RawWaylandShmServer.putU32(byArray, 4, (int)l);
                    RawWaylandShmServer.putU32(byArray, 8, 0);
                    this.writeMessage(localSocket, this.touchId, 1, byArray);
                    this.writeMessage(localSocket, this.touchId, 3, new byte[0]);
                    this.touchFocusSurfaceId = 0;
                    this.appendAsyncEvent("android->wayland wl_touch.up");
                    return true;
                }
                if (n == 3 && this.touchFocusSurfaceId != 0) {
                    this.writeMessage(localSocket, this.touchId, 4, new byte[0]);
                    this.touchFocusSurfaceId = 0;
                    this.appendAsyncEvent("android->wayland wl_touch.cancel");
                    return true;
                }
            }
            catch (Exception exception) {
                this.appendAsyncEvent("android touch forwarding failed: " + String.valueOf(exception));
                this.error = exception.toString();
            }
            return false;
        }

        private void sendPointerEnter(LocalSocket localSocket, int n, int n2, int n3) throws Exception {
            byte[] byArray = new byte[16];
            RawWaylandShmServer.putU32(byArray, 0, this.pointerSerial++);
            RawWaylandShmServer.putU32(byArray, 4, n);
            RawWaylandShmServer.putU32(byArray, 8, n2 * 256);
            RawWaylandShmServer.putU32(byArray, 12, n3 * 256);
            this.writeMessage(localSocket, this.pointerId, 0, byArray);
            this.pointerEventsSent = true;
            this.appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=0 wl_pointer.enter surface=" + n + " x=" + n2 + " y=" + n3);
        }

        private void sendPointerLeave(LocalSocket localSocket, int n) throws Exception {
            byte[] byArray = new byte[8];
            RawWaylandShmServer.putU32(byArray, 0, this.pointerSerial++);
            RawWaylandShmServer.putU32(byArray, 4, n);
            this.writeMessage(localSocket, this.pointerId, 1, byArray);
            this.appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=1 wl_pointer.leave surface=" + n);
        }

        private void sendPointerMotion(LocalSocket localSocket, int n, int n2, long l) throws Exception {
            byte[] byArray = new byte[12];
            RawWaylandShmServer.putU32(byArray, 0, (int)l);
            RawWaylandShmServer.putU32(byArray, 4, n * 256);
            RawWaylandShmServer.putU32(byArray, 8, n2 * 256);
            this.writeMessage(localSocket, this.pointerId, 2, byArray);
            this.pointerEventsSent = true;
            ++this.pointerMotionEventsSent;
            this.appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=2 wl_pointer.motion x=" + n + " y=" + n2);
        }

        private void sendPointerButton(LocalSocket localSocket, long l, boolean bl) throws Exception {
            byte[] byArray = new byte[16];
            int n = this.pointerSerial++;
            RawWaylandShmServer.putU32(byArray, 0, n);
            RawWaylandShmServer.putU32(byArray, 4, (int)l);
            RawWaylandShmServer.putU32(byArray, 8, 272);
            RawWaylandShmServer.putU32(byArray, 12, bl ? 1 : 0);
            this.writeMessage(localSocket, this.pointerId, 3, byArray);
            this.pointerEventsSent = true;
            ++this.pointerButtonEventsSent;
            if (bl) {
                this.rememberInputSerial(n);
            }
            this.appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=3 wl_pointer.button left " + (bl ? "pressed" : "released"));
        }

        private void sendPointerFrame(LocalSocket localSocket) throws Exception {
            this.writeMessage(localSocket, this.pointerId, 5, new byte[0]);
            this.appendAsyncEvent("android->wayland object=" + this.pointerId + " opcode=5 wl_pointer.frame");
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        private void appendAsyncEvent(String string) {
            Object object = this.eventLock;
            synchronized (object) {
                if (this.eventLog != null) {
                    this.eventLog.append(string).append("\n");
                }
            }
        }

        private synchronized void rememberInputSerial(int n) {
            this.lastInputSerial = n;
            this.recentInputSerials.addLast(n);
            while (this.recentInputSerials.size() > 32) {
                this.recentInputSerials.removeFirst();
            }
        }

        private synchronized boolean isKnownInputSerial(int n) {
            return this.recentInputSerials.contains(n);
        }

        synchronized void publishAndroidClipboard() {
            LocalSocket localSocket = this.connectedClient;
            if (localSocket == null || this.dataDeviceId == 0) {
                return;
            }
            StringBuilder stringBuilder = new StringBuilder();
            try {
                this.sendAndroidClipboardOffer(localSocket, stringBuilder);
                String string = stringBuilder.toString().trim();
                if (!string.isEmpty()) {
                    this.appendAsyncEvent(string);
                }
            }
            catch (Exception exception) {
                this.appendAsyncEvent("android clipboard offer failed: " + String.valueOf(exception));
                this.error = exception.toString();
            }
        }

        private void sendAndroidClipboardOffer(LocalSocket localSocket, StringBuilder stringBuilder) throws Exception {
            MainActivity mainActivity = (MainActivity)((Object)currentActivity.get());
            if (mainActivity == null || this.dataDeviceId == 0) {
                return;
            }
            ClipboardManager clipboardManager = (ClipboardManager)mainActivity.getSystemService("clipboard");
            if (!clipboardManager.hasPrimaryClip() || clipboardManager.getPrimaryClip() == null || clipboardManager.getPrimaryClip().getItemCount() == 0) {
                return;
            }
            CharSequence charSequence = clipboardManager.getPrimaryClip().getItemAt(0).coerceToText((Context)mainActivity);
            if (charSequence == null) {
                return;
            }
            String string = charSequence.toString();
            if (string.equals(this.lastOfferedAndroidClipboardText)) {
                return;
            }
            this.lastOfferedAndroidClipboardText = string;
            int n = this.nextServerObjectId++;
            this.androidClipboardOffers.put(n, string);
            byte[] byArray = new byte[4];
            RawWaylandShmServer.putU32(byArray, 0, n);
            this.writeMessage(localSocket, this.dataDeviceId, 0, byArray);
            this.writeMessage(localSocket, n, 0, RawWaylandShmServer.stringPayload("text/plain"));
            this.writeMessage(localSocket, n, 0, RawWaylandShmServer.stringPayload("text/plain;charset=utf-8"));
            byte[] byArray2 = new byte[4];
            RawWaylandShmServer.putU32(byArray2, 0, n);
            this.writeMessage(localSocket, this.dataDeviceId, 5, byArray2);
            stringBuilder.append("server->client wl_data_device.data_offer id=").append(n).append("\n").append("server->client wl_data_offer.offer text MIME types\n").append("server->client wl_data_device.selection offer=").append(n).append("\n");
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        private void requestClipboardSourceData(LocalSocket localSocket, ClipboardSourceState clipboardSourceState, StringBuilder stringBuilder) throws Exception {
            String string = clipboardSourceState.preferredTextMime();
            if (string == null) {
                stringBuilder.append("clipboard source has no supported text MIME\n");
                return;
            }
            ParcelFileDescriptor[] parcelFileDescriptorArray = ParcelFileDescriptor.createPipe();
            try {
                this.writeMessageWithFd(localSocket, clipboardSourceState.id, 1, RawWaylandShmServer.stringPayload(string), parcelFileDescriptorArray[1].getFileDescriptor());
            }
            finally {
                parcelFileDescriptorArray[1].close();
            }
            stringBuilder.append("server->client object=").append(clipboardSourceState.id).append(" opcode=1 wl_data_source.send mime=").append(string).append("\n");
            ParcelFileDescriptor parcelFileDescriptor = parcelFileDescriptorArray[0];
            new Thread(() -> this.readClipboardPipe(parcelFileDescriptor), "archphene-clipboard-read").start();
        }

        private void readClipboardPipe(ParcelFileDescriptor parcelFileDescriptor) {
            try (ParcelFileDescriptor.AutoCloseInputStream autoCloseInputStream = new ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptor);
                 ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();){
                int n;
                byte[] byArray = new byte[4096];
                int n2 = 0;
                while ((n = autoCloseInputStream.read(byArray)) != -1) {
                    if ((n2 += n) > 0x100000) {
                        throw new IOException("clipboard payload exceeds 1 MiB");
                    }
                    byteArrayOutputStream.write(byArray, 0, n);
                }
                if (n2 == 0) {
                    this.appendAsyncEvent("wayland->android clipboard empty payload ignored");
                    return;
                }
                String string = byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
                MainActivity mainActivity = (MainActivity)((Object)currentActivity.get());
                if (mainActivity != null) {
                    mainActivity.runOnUiThread(() -> mainActivity.setBridgeClipboardText(string));
                }
                this.appendAsyncEvent("wayland->android clipboard bytes=" + n2);
            }
            catch (Exception exception) {
                this.appendAsyncEvent("wayland->android clipboard failed: " + String.valueOf(exception));
            }
        }

        private void sendPointerEvents(LocalSocket localSocket, StringBuilder stringBuilder) throws Exception {
            int n = Math.max(1, this.configureWidth / 2) * 256;
            int n2 = Math.max(1, this.configureHeight / 2) * 256;
            byte[] byArray = new byte[16];
            RawWaylandShmServer.putU32(byArray, 0, 100);
            RawWaylandShmServer.putU32(byArray, 4, this.surfaceId);
            RawWaylandShmServer.putU32(byArray, 8, n);
            RawWaylandShmServer.putU32(byArray, 12, n2);
            this.writeMessage(localSocket, this.pointerId, 0, byArray);
            byte[] byArray2 = new byte[12];
            RawWaylandShmServer.putU32(byArray2, 0, 16);
            RawWaylandShmServer.putU32(byArray2, 4, n);
            RawWaylandShmServer.putU32(byArray2, 8, n2);
            this.writeMessage(localSocket, this.pointerId, 2, byArray2);
            byte[] byArray3 = new byte[16];
            RawWaylandShmServer.putU32(byArray3, 0, 101);
            RawWaylandShmServer.putU32(byArray3, 4, 17);
            RawWaylandShmServer.putU32(byArray3, 8, 272);
            RawWaylandShmServer.putU32(byArray3, 12, 1);
            this.writeMessage(localSocket, this.pointerId, 3, byArray3);
            this.pointerEventsSent = true;
            stringBuilder.append("server->client object=").append(this.pointerId).append(" opcode=0 wl_pointer.enter surface=").append(this.surfaceId).append(" x=").append(this.configureWidth / 2).append(" y=").append(this.configureHeight / 2).append("\n");
            stringBuilder.append("server->client object=").append(this.pointerId).append(" opcode=2 wl_pointer.motion x=").append(this.configureWidth / 2).append(" y=").append(this.configureHeight / 2).append("\n");
            stringBuilder.append("server->client object=").append(this.pointerId).append(" opcode=3 wl_pointer.button left pressed\n");
        }

        private void sendXdgConfigure(LocalSocket localSocket, StringBuilder stringBuilder) throws Exception {
            byte[] byArray;
            this.xdgConfigureSerial = Math.max(42, this.xdgConfigureSerial + 1);
            if (this.xdgToplevelId != 0) {
                byArray = new byte[16];
                RawWaylandShmServer.putU32(byArray, 0, this.configureWidth);
                RawWaylandShmServer.putU32(byArray, 4, this.configureHeight);
                RawWaylandShmServer.putU32(byArray, 8, 4);
                RawWaylandShmServer.putU32(byArray, 12, 4);
                this.writeMessage(localSocket, this.xdgToplevelId, 0, byArray);
                stringBuilder.append("server->client object=").append(this.xdgToplevelId).append(" opcode=0 xdg_toplevel.configure width=").append(this.configureWidth).append(" height=").append(this.configureHeight).append(" states=activated\n");
            }
            byArray = new byte[4];
            RawWaylandShmServer.putU32(byArray, 0, this.xdgConfigureSerial);
            this.writeMessage(localSocket, this.xdgSurfaceId, 0, byArray);
            this.xdgConfigureSent = true;
            stringBuilder.append("server->client object=").append(this.xdgSurfaceId).append(" opcode=0 xdg_surface.configure serial=").append(this.xdgConfigureSerial).append("\n");
        }

        private void sendOutputEvents(LocalSocket localSocket, int n, StringBuilder stringBuilder) throws Exception {
            byte[] byArray = RawWaylandShmServer.stringPayload("Archphene");
            byte[] byArray2 = RawWaylandShmServer.stringPayload("Android Display");
            byte[] byArray3 = new byte[20 + byArray.length + byArray2.length + 4];
            RawWaylandShmServer.putU32(byArray3, 0, 0);
            RawWaylandShmServer.putU32(byArray3, 4, 0);
            RawWaylandShmServer.putU32(byArray3, 8, 68);
            RawWaylandShmServer.putU32(byArray3, 12, 151);
            RawWaylandShmServer.putU32(byArray3, 16, 0);
            System.arraycopy(byArray, 0, byArray3, 20, byArray.length);
            System.arraycopy(byArray2, 0, byArray3, 20 + byArray.length, byArray2.length);
            RawWaylandShmServer.putU32(byArray3, 20 + byArray.length + byArray2.length, 0);
            this.writeMessage(localSocket, n, 0, byArray3);
            byte[] byArray4 = new byte[16];
            RawWaylandShmServer.putU32(byArray4, 0, 1);
            RawWaylandShmServer.putU32(byArray4, 4, this.outputWidth);
            RawWaylandShmServer.putU32(byArray4, 8, this.outputHeight);
            RawWaylandShmServer.putU32(byArray4, 12, 60000);
            this.writeMessage(localSocket, n, 1, byArray4);
            this.sendU32Event(localSocket, n, 3, this.outputScale, stringBuilder, "wl_output.scale");
            this.writeMessage(localSocket, n, 2, new byte[0]);
            this.outputDoneSent = true;
            stringBuilder.append("server->client object=").append(n).append(" opcode=0 wl_output.geometry make=Archphene model=Android Display\n");
            stringBuilder.append("server->client object=").append(n).append(" opcode=1 wl_output.mode current width=").append(this.outputWidth).append(" height=").append(this.outputHeight).append(" refresh=60000\n");
            stringBuilder.append("server->client object=").append(n).append(" opcode=2 wl_output.done\n");
        }

        private void sendSeatEvents(LocalSocket localSocket, int n, StringBuilder stringBuilder) throws Exception {
            this.writeMessage(localSocket, n, 1, RawWaylandShmServer.stringPayload("default"));
            this.sendU32Event(localSocket, n, 0, 7, stringBuilder, "wl_seat.capabilities pointer keyboard touch");
            this.seatCapabilitiesSent = true;
            stringBuilder.append("server->client object=").append(n).append(" opcode=1 wl_seat.name default\n");
        }

        private void sendRegistryGlobal(LocalSocket localSocket, int n, int n2, String string, int n3, StringBuilder stringBuilder) throws Exception {
            byte[] byArray = (string + "\u0000").getBytes(StandardCharsets.UTF_8);
            int n4 = byArray.length + 3 & 0xFFFFFFFC;
            byte[] byArray2 = new byte[8 + n4 + 4];
            RawWaylandShmServer.putU32(byArray2, 0, n2);
            RawWaylandShmServer.putU32(byArray2, 4, byArray.length);
            System.arraycopy(byArray, 0, byArray2, 8, byArray.length);
            RawWaylandShmServer.putU32(byArray2, 8 + n4, n3);
            this.writeMessage(localSocket, n, 0, byArray2);
            ++this.registryGlobalCount;
            stringBuilder.append("server->client object=").append(n).append(" opcode=0 wl_registry.global name=").append(n2).append(" interface=").append(string).append(" version=").append(n3).append("\n");
        }

        private void sendCallbackDone(LocalSocket localSocket, int n, int n2, StringBuilder stringBuilder) throws Exception {
            this.sendU32Event(localSocket, n, 0, n2, stringBuilder, "wl_callback.done");
            this.callbackDoneSent = true;
        }

        private void sendU32Event(LocalSocket localSocket, int n, int n2, int n3, StringBuilder stringBuilder, String string) throws Exception {
            byte[] byArray = new byte[4];
            RawWaylandShmServer.putU32(byArray, 0, n3);
            this.writeMessage(localSocket, n, n2, byArray);
            stringBuilder.append("server->client object=").append(n).append(" opcode=").append(n2).append(" ").append(string).append(" value=").append(n3).append("\n");
        }

        private static byte[] stringPayload(String string) {
            byte[] byArray = (string + "\u0000").getBytes(StandardCharsets.UTF_8);
            int n = byArray.length + 3 & 0xFFFFFFFC;
            byte[] byArray2 = new byte[4 + n];
            RawWaylandShmServer.putU32(byArray2, 0, byArray.length);
            System.arraycopy(byArray, 0, byArray2, 4, byArray.length);
            return byArray2;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        private void writeMessageWithFd(LocalSocket localSocket, int n, int n2, byte[] byArray, FileDescriptor fileDescriptor) throws Exception {
            byte[] byArray2 = new byte[8 + byArray.length];
            RawWaylandShmServer.putU32(byArray2, 0, n);
            RawWaylandShmServer.putU32(byArray2, 4, byArray2.length << 16 | n2 & 0xFFFF);
            System.arraycopy(byArray, 0, byArray2, 8, byArray.length);
            Object object = this.writeLock;
            synchronized (object) {
                localSocket.setFileDescriptorsForSend(new FileDescriptor[]{fileDescriptor});
                try {
                    OutputStream outputStream = localSocket.getOutputStream();
                    outputStream.write(byArray2);
                    outputStream.flush();
                }
                finally {
                    localSocket.setFileDescriptorsForSend(null);
                }
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        private void writeMessage(LocalSocket localSocket, int n, int n2, byte[] byArray) throws Exception {
            byte[] byArray2 = new byte[8 + byArray.length];
            RawWaylandShmServer.putU32(byArray2, 0, n);
            RawWaylandShmServer.putU32(byArray2, 4, byArray2.length << 16 | n2 & 0xFFFF);
            System.arraycopy(byArray, 0, byArray2, 8, byArray.length);
            Object object = this.writeLock;
            synchronized (object) {
                OutputStream outputStream = localSocket.getOutputStream();
                outputStream.write(byArray2);
                outputStream.flush();
            }
        }

        private PopupState findPopupByWlSurface(int n) {
            for (PopupState popupState : this.popups.values()) {
                if (popupState.wlSurfaceId != n) continue;
                return popupState;
            }
            return null;
        }

        private void sendMainToplevelActivation(LocalSocket localSocket, boolean bl, StringBuilder stringBuilder) throws Exception {
            if (this.xdgToplevelId == 0 || this.xdgSurfaceId == 0) {
                return;
            }
            byte[] byArray = new byte[bl ? 16 : 12];
            RawWaylandShmServer.putU32(byArray, 0, this.configureWidth);
            RawWaylandShmServer.putU32(byArray, 4, this.configureHeight);
            RawWaylandShmServer.putU32(byArray, 8, bl ? 4 : 0);
            if (bl) {
                RawWaylandShmServer.putU32(byArray, 12, 4);
            }
            this.writeMessage(localSocket, this.xdgToplevelId, 0, byArray);
            byte[] byArray2 = new byte[4];
            int n = ++this.xdgConfigureSerial;
            RawWaylandShmServer.putU32(byArray2, 0, n);
            this.writeMessage(localSocket, this.xdgSurfaceId, 0, byArray2);
            stringBuilder.append("server->client main xdg_toplevel.configure activated=").append(bl).append(" serial=").append(n).append("\n");
        }

        private void sendChildToplevelConfigure(LocalSocket localSocket, ChildToplevelState childToplevelState, StringBuilder stringBuilder) throws Exception {
            int n;
            childToplevelState.configureSerial = ++this.xdgConfigureSerial;
            byte[] byArray = new byte[16];
            int n2 = childToplevelState.isFileDialog() ? this.configureWidth : 0;
            int n3 = n = childToplevelState.isFileDialog() ? this.configureHeight : 0;
            if (n2 > 0) {
                n2 = Math.max(n2, childToplevelState.minWidth);
                if (childToplevelState.maxWidth > 0) {
                    n2 = Math.min(n2, childToplevelState.maxWidth);
                }
            }
            if (n > 0) {
                n = Math.max(n, childToplevelState.minHeight);
                if (childToplevelState.maxHeight > 0) {
                    n = Math.min(n, childToplevelState.maxHeight);
                }
            }
            RawWaylandShmServer.putU32(byArray, 0, n2);
            RawWaylandShmServer.putU32(byArray, 4, n);
            RawWaylandShmServer.putU32(byArray, 8, 4);
            RawWaylandShmServer.putU32(byArray, 12, 4);
            this.writeMessage(localSocket, childToplevelState.toplevelId, 0, byArray);
            byte[] byArray2 = new byte[4];
            RawWaylandShmServer.putU32(byArray2, 0, childToplevelState.configureSerial);
            this.writeMessage(localSocket, childToplevelState.xdgSurfaceId, 0, byArray2);
            childToplevelState.configureSent = true;
            stringBuilder.append("server->client child xdg_toplevel.configure size=").append(n2).append("x").append(n).append(" serial=").append(childToplevelState.configureSerial).append("\n");
        }

        private static String sampledOpaqueCoverage(Bitmap bitmap) {
            int n = 0;
            int n2 = 0;
            int n3 = Math.max(1, bitmap.getWidth() / 32);
            int n4 = Math.max(1, bitmap.getHeight() / 32);
            for (int i = 0; i < bitmap.getHeight(); i += n4) {
                for (int j = 0; j < bitmap.getWidth(); j += n3) {
                    int n5 = bitmap.getPixel(j, i);
                    ++n;
                    if ((n5 & 0xFF000000) == 0 || (n5 & 0xFFFFFF) == 0) continue;
                    ++n2;
                }
            }
            return n2 + "/" + n;
        }

        private Bitmap readShmBitmap(int n) throws Exception {
            ShmBufferState shmBufferState = this.shmBuffers.get(n);
            if (shmBufferState == null) {
                throw new IllegalStateException("child toplevel commit with unknown wl_buffer " + n);
            }
            byte[] byArray = RawWaylandShmServer.readExactFromFd(shmBufferState.pool.fd, shmBufferState.offset, shmBufferState.stride * shmBufferState.height);
            int[] nArray = new int[shmBufferState.width * shmBufferState.height];
            for (int i = 0; i < shmBufferState.height; ++i) {
                int n2 = i * shmBufferState.stride;
                for (int j = 0; j < shmBufferState.width; ++j) {
                    int n3;
                    int n4 = n2 + j * 4;
                    int n5 = byArray[n4] & 0xFF;
                    int n6 = byArray[n4 + 1] & 0xFF;
                    int n7 = byArray[n4 + 2] & 0xFF;
                    int n8 = n3 = shmBufferState.format == 0 ? byArray[n4 + 3] & 0xFF : 255;
                    if (n3 < 255) {
                        n7 = (n7 * n3 + 255 * (255 - n3)) / 255;
                        n6 = (n6 * n3 + 255 * (255 - n3)) / 255;
                        n5 = (n5 * n3 + 255 * (255 - n3)) / 255;
                    }
                    nArray[i * shmBufferState.width + j] = 0xFF000000 | n7 << 16 | n6 << 8 | n5;
                }
            }
            return Bitmap.createBitmap((int[])nArray, (int)shmBufferState.width, (int)shmBufferState.height, (Bitmap.Config)Bitmap.Config.ARGB_8888);
        }

        private void constrainPopup(PopupState popupState) {
            int n;
            PositionerState positionerState;
            int n2;
            PositionerState positionerState2 = popupState.positioner;
            popupState.applyPositioner(positionerState2);
            int n3 = 0;
            int n4 = 0;
            int n5 = popupState.parentXdgSurfaceId;
            PopupState popupState2 = this.popupsByXdgSurface.get(n5);
            while (popupState2 != null) {
                n3 += popupState2.configureX;
                n4 += popupState2.configureY;
                n5 = popupState2.parentXdgSurfaceId;
                popupState2 = this.popupsByXdgSurface.get(n5);
            }
            int n6 = this.configureWidth;
            int n7 = this.configureHeight;
            ChildToplevelState childToplevelState = this.childToplevelsByXdg.get(n5);
            if (childToplevelState != null && childToplevelState.displayWidth > 1 && childToplevelState.displayHeight > 1) {
                n2 = Math.max(1, this.surfaceBufferScales.getOrDefault(childToplevelState.wlSurfaceId, 1));
                float f = (float)childToplevelState.displayWidth / (float)Math.max(1, childToplevelState.sourceWidth);
                float f2 = Math.max(0.01f, (float)n2 * f);
                n6 = Math.max(1, Math.round((float)childToplevelState.displayWidth / f2));
                n7 = Math.max(1, Math.round((float)childToplevelState.displayHeight / f2));
            }
            n2 = -n3;
            int n8 = -n4;
            int n9 = n2 + n6;
            int n10 = n8 + n7;
            int n11 = positionerState2.constraintAdjustment;
            if ((popupState.configureX < n2 || popupState.configureX + popupState.width > n9) && (n11 & 4) != 0) {
                positionerState = new PositionerState(positionerState2);
                positionerState.anchor = RawWaylandShmServer.flipHorizontal(positionerState.anchor);
                positionerState.gravity = RawWaylandShmServer.flipHorizontal(positionerState.gravity);
                n = RawWaylandShmServer.positionerX(positionerState, popupState.width);
                if (RawWaylandShmServer.overflow(n, popupState.width, n2, n9) < RawWaylandShmServer.overflow(popupState.configureX, popupState.width, n2, n9)) {
                    popupState.configureX = n;
                }
            }
            if ((popupState.configureY < n8 || popupState.configureY + popupState.height > n10) && (n11 & 8) != 0) {
                positionerState = new PositionerState(positionerState2);
                positionerState.anchor = RawWaylandShmServer.flipVertical(positionerState.anchor);
                positionerState.gravity = RawWaylandShmServer.flipVertical(positionerState.gravity);
                n = RawWaylandShmServer.positionerY(positionerState, popupState.height);
                if (RawWaylandShmServer.overflow(n, popupState.height, n8, n10) < RawWaylandShmServer.overflow(popupState.configureY, popupState.height, n8, n10)) {
                    popupState.configureY = n;
                }
            }
            if ((n11 & 1) != 0) {
                popupState.configureX = RawWaylandShmServer.clampPosition(popupState.configureX, popupState.width, n2, n9);
            }
            if ((n11 & 2) != 0) {
                popupState.configureY = RawWaylandShmServer.clampPosition(popupState.configureY, popupState.height, n8, n10);
            }
            if ((n11 & 0x10) != 0 && popupState.width > n6) {
                popupState.width = n6;
                popupState.configureX = n2;
            }
            if ((n11 & 0x20) != 0 && popupState.height > n7) {
                popupState.height = n7;
                popupState.configureY = n8;
            }
        }

        private static int positionerX(PositionerState positionerState, int n) {
            return PopupState.gravityX(PopupState.anchorPointX(positionerState), n, positionerState.gravity) + positionerState.offsetX;
        }

        private static int positionerY(PositionerState positionerState, int n) {
            return PopupState.gravityY(PopupState.anchorPointY(positionerState), n, positionerState.gravity) + positionerState.offsetY;
        }

        private static int overflow(int n, int n2, int n3, int n4) {
            return Math.max(0, n3 - n) + Math.max(0, n + n2 - n4);
        }

        private static int clampPosition(int n, int n2, int n3, int n4) {
            if (n2 >= n4 - n3) {
                return n3;
            }
            return Math.max(n3, Math.min(n4 - n2, n));
        }

        private static int flipHorizontal(int n) {
            switch (n) {
                case 3: {
                    return 4;
                }
                case 4: {
                    return 3;
                }
                case 5: {
                    return 7;
                }
                case 7: {
                    return 5;
                }
                case 6: {
                    return 8;
                }
                case 8: {
                    return 6;
                }
            }
            return n;
        }

        private static int flipVertical(int n) {
            switch (n) {
                case 1: {
                    return 2;
                }
                case 2: {
                    return 1;
                }
                case 5: {
                    return 6;
                }
                case 6: {
                    return 5;
                }
                case 7: {
                    return 8;
                }
                case 8: {
                    return 7;
                }
            }
            return n;
        }

        private void sendPopupConfigure(LocalSocket localSocket, PopupState popupState, StringBuilder stringBuilder) throws Exception {
            popupState.configureSerial = ++this.xdgConfigureSerial;
            byte[] byArray = new byte[16];
            RawWaylandShmServer.putU32(byArray, 0, popupState.configureX);
            RawWaylandShmServer.putU32(byArray, 4, popupState.configureY);
            RawWaylandShmServer.putU32(byArray, 8, popupState.width);
            RawWaylandShmServer.putU32(byArray, 12, popupState.height);
            this.writeMessage(localSocket, popupState.popupId, 0, byArray);
            byte[] byArray2 = new byte[4];
            RawWaylandShmServer.putU32(byArray2, 0, popupState.configureSerial);
            this.writeMessage(localSocket, popupState.xdgSurfaceId, 0, byArray2);
            popupState.configureSent = true;
            stringBuilder.append("server->client object=").append(popupState.popupId).append(" opcode=0 xdg_popup.configure x=").append(popupState.configureX).append(" y=").append(popupState.configureY).append(" w=").append(popupState.width).append(" h=").append(popupState.height).append("\n");
            stringBuilder.append("server->client object=").append(popupState.xdgSurfaceId).append(" opcode=0 xdg_surface.configure serial=").append(popupState.configureSerial).append("\n");
        }

        private void commitPopupBuffer(PopupState popupState, int n) throws Exception {
            ShmBufferState shmBufferState = this.shmBuffers.get(n);
            if (shmBufferState == null) {
                throw new IllegalStateException("popup commit with unknown wl_buffer " + n);
            }
            long l = (long)shmBufferState.offset + (long)shmBufferState.stride * (long)shmBufferState.height;
            if (shmBufferState.width <= 0 || shmBufferState.height <= 0 || shmBufferState.stride < shmBufferState.width * 4 || l > (long)shmBufferState.pool.size) {
                throw new IllegalStateException("invalid popup buffer state before commit");
            }
            byte[] byArray = RawWaylandShmServer.readExactFromFd(shmBufferState.pool.fd, shmBufferState.offset, shmBufferState.stride * shmBufferState.height);
            int[] nArray = new int[shmBufferState.width * shmBufferState.height];
            for (int i = 0; i < shmBufferState.height; ++i) {
                int n2 = i * shmBufferState.stride;
                for (int j = 0; j < shmBufferState.width; ++j) {
                    int n3 = n2 + j * 4;
                    int n4 = byArray[n3] & 0xFF;
                    int n5 = byArray[n3 + 1] & 0xFF;
                    int n6 = byArray[n3 + 2] & 0xFF;
                    int n7 = shmBufferState.format == 0 ? byArray[n3 + 3] & 0xFF : 255;
                    nArray[i * shmBufferState.width + j] = n7 << 24 | n6 << 16 | n5 << 8 | n4;
                }
            }
            popupState.bitmap = Bitmap.createBitmap((int[])nArray, (int)shmBufferState.width, (int)shmBufferState.height, (Bitmap.Config)Bitmap.Config.ARGB_8888);
            Log.i((String)MainActivity.TAG, (String)("Popup buffer commit surface=" + popupState.wlSurfaceId + " buffer=" + n + " size=" + shmBufferState.width + "x" + shmBufferState.height + " configure=" + popupState.configureX + "," + popupState.configureY + " " + popupState.width + "x" + popupState.height));
            popupState.visible = true;
            this.composeSurfaceTree();
            if (popupState.grabbed && this.keyboardId != 0 && this.keyboardFocusSurfaceId != popupState.wlSurfaceId) {
                this.sendKeyboardFocus(this.connectedClient, popupState.wlSurfaceId, null);
            }
        }

        private void commitSubsurfaceBuffer(SubsurfaceState subsurfaceState, int n) throws Exception {
            ShmBufferState shmBufferState = this.shmBuffers.get(n);
            if (shmBufferState == null) {
                throw new IllegalStateException("subsurface commit with unknown wl_buffer " + n);
            }
            long l = (long)shmBufferState.offset + (long)shmBufferState.stride * (long)shmBufferState.height;
            if (shmBufferState.width <= 0 || shmBufferState.height <= 0 || shmBufferState.stride < shmBufferState.width * 4 || l > (long)shmBufferState.pool.size) {
                throw new IllegalStateException("invalid subsurface buffer state before commit");
            }
            byte[] byArray = RawWaylandShmServer.readExactFromFd(shmBufferState.pool.fd, shmBufferState.offset, shmBufferState.stride * shmBufferState.height);
            int[] nArray = new int[shmBufferState.width * shmBufferState.height];
            for (int i = 0; i < shmBufferState.height; ++i) {
                int n2 = i * shmBufferState.stride;
                for (int j = 0; j < shmBufferState.width; ++j) {
                    int n3 = n2 + j * 4;
                    int n4 = byArray[n3] & 0xFF;
                    int n5 = byArray[n3 + 1] & 0xFF;
                    int n6 = byArray[n3 + 2] & 0xFF;
                    int n7 = shmBufferState.format == 0 ? byArray[n3 + 3] & 0xFF : 255;
                    nArray[i * shmBufferState.width + j] = n7 << 24 | n6 << 16 | n5 << 8 | n4;
                }
            }
            subsurfaceState.bitmap = Bitmap.createBitmap((int[])nArray, (int)shmBufferState.width, (int)shmBufferState.height, (Bitmap.Config)Bitmap.Config.ARGB_8888);
            Log.i((String)MainActivity.TAG, (String)("Subsurface buffer commit surface=" + subsurfaceState.wlSurfaceId + " parent=" + subsurfaceState.parentSurfaceId + " buffer=" + n + " size=" + shmBufferState.width + "x" + shmBufferState.height + " at=" + subsurfaceState.x + "," + subsurfaceState.y));
            subsurfaceState.visible = true;
            this.composeSurfaceTree();
        }

        private void composeSurfaceTree() {
            Object object;
            Object object3;
            Canvas canvas;
            Bitmap bitmap;
            Bitmap bitmap2 = this.mainBitmap;
            if (bitmap2 == null) {
                return;
            }
            int n = this.presentationOriginX();
            int n2 = this.presentationOriginY();
            WindowGeometry windowGeometry = this.windowGeometries.get(this.xdgSurfaceId);
            int n3 = Math.max(1, this.surfaceBufferScales.getOrDefault(this.surfaceId, 1));
            int n4 = windowGeometry == null ? bitmap2.getWidth() : Math.min(bitmap2.getWidth() - n, windowGeometry.width * n3);
            int n5 = windowGeometry == null ? bitmap2.getHeight() : Math.min(bitmap2.getHeight() - n2, windowGeometry.height * n3);
            Bitmap bitmap3 = Bitmap.createBitmap((Bitmap)bitmap2, (int)n, (int)n2, (int)Math.max(1, n4), (int)Math.max(1, n5));
            this.mainSourceWidth = bitmap3.getWidth();
            this.mainSourceHeight = bitmap3.getHeight();
            boolean bl = this.compactMainPresentation = (float)this.mainSourceWidth < (float)this.outputWidth * 0.85f || (float)this.mainSourceHeight < (float)this.outputHeight * 0.85f;
            if (this.compactMainPresentation) {
                bitmap = Bitmap.createBitmap((int)this.outputWidth, (int)this.outputHeight, (Bitmap.Config)Bitmap.Config.ARGB_8888);
                canvas = new Canvas(bitmap);
                canvas.drawColor(-1);
                float f = Math.min(1.0f, Math.min((float)this.outputWidth / (float)this.mainSourceWidth, (float)this.outputHeight / (float)this.mainSourceHeight));
                this.mainDisplayWidth = Math.max(1, Math.round((float)this.mainSourceWidth * f));
                this.mainDisplayHeight = Math.max(1, Math.round((float)this.mainSourceHeight * f));
                this.mainDisplayX = (this.outputWidth - this.mainDisplayWidth) / 2;
                this.mainDisplayY = (this.outputHeight - this.mainDisplayHeight) / 2;
                object3 = new Rect(this.mainDisplayX, this.mainDisplayY, this.mainDisplayX + this.mainDisplayWidth, this.mainDisplayY + this.mainDisplayHeight);
                canvas.drawBitmap(bitmap3, null, (Rect)object3, null);
                this.drawSubsurfacesForParent(canvas, this.surfaceId, (float)this.mainDisplayX - (float)n * f, (float)this.mainDisplayY - (float)n2 * f, (float)n3 * f);
            } else {
                bitmap = bitmap3.copy(Bitmap.Config.ARGB_8888, true);
                canvas = new Canvas(bitmap);
                this.mainDisplayX = 0;
                this.mainDisplayY = 0;
                this.mainDisplayWidth = bitmap.getWidth();
                this.mainDisplayHeight = bitmap.getHeight();
                this.drawSubsurfacesForParent(canvas, this.surfaceId, -n, -n2, this.outputScale);
            }
            ArrayList<ChildToplevelState> arrayList = new ArrayList<ChildToplevelState>(this.childToplevelsByXdg.values());
            arrayList.sort((first, second) -> Integer.compare(first.sequence, second.sequence));
            for (ChildToplevelState object22 : arrayList) {
                if (!object22.visible || object22.bitmap == null) continue;
                canvas.drawColor(0x66000000);
                object = this.windowGeometries.get(object22.xdgSurfaceId);
                int n6 = Math.max(1, this.surfaceBufferScales.getOrDefault(object22.wlSurfaceId, 1));
                int n7 = object == null ? 0 : Math.max(0, ((WindowGeometry)object).x * n6);
                int n8 = object == null ? 0 : Math.max(0, ((WindowGeometry)object).y * n6);
                int n9 = object == null ? object22.bitmap.getWidth() : Math.min(object22.bitmap.getWidth() - n7, ((WindowGeometry)object).width * n6);
                int n10 = object == null ? object22.bitmap.getHeight() : Math.min(object22.bitmap.getHeight() - n8, ((WindowGeometry)object).height * n6);
                Bitmap bitmap4 = Bitmap.createBitmap((Bitmap)object22.bitmap, (int)n7, (int)n8, (int)Math.max(1, n9), (int)Math.max(1, n10));
                object22.bufferOriginX = n7;
                object22.bufferOriginY = n8;
                object22.sourceWidth = bitmap4.getWidth();
                object22.sourceHeight = bitmap4.getHeight();
                float f = Math.min(1.0f, Math.min((float)bitmap.getWidth() / (float)object22.sourceWidth, (float)bitmap.getHeight() / (float)object22.sourceHeight));
                object22.displayWidth = Math.max(1, Math.round((float)object22.sourceWidth * f));
                object22.displayHeight = Math.max(1, Math.round((float)object22.sourceHeight * f));
                object22.displayX = Math.max(0, (bitmap.getWidth() - object22.displayWidth) / 2);
                object22.displayY = Math.max(0, (bitmap.getHeight() - object22.displayHeight) / 2);
                Rect rect = new Rect(object22.displayX, object22.displayY, object22.displayX + object22.displayWidth, object22.displayY + object22.displayHeight);
                canvas.drawBitmap(bitmap4, null, rect, null);
                float f2 = (float)object22.displayWidth / (float)object22.sourceWidth;
                float f3 = (float)n6 * f2;
                this.drawSubsurfacesForParent(canvas, object22.wlSurfaceId, (float)object22.displayX - (float)object22.bufferOriginX * f2, (float)object22.displayY - (float)object22.bufferOriginY * f2, f3);
            }
            object3 = new ArrayList<PopupState>(this.popups.values());
            ((ArrayList<PopupState>)object3).sort((PopupState popupState, PopupState popupState2) -> Integer.compare(popupState.sequence, popupState2.sequence));
            Iterator iterator = ((ArrayList)object3).iterator();
            while (iterator.hasNext()) {
                object = (PopupState)iterator.next();
                if (!((PopupState)object).visible || ((PopupState)object).bitmap == null) continue;
                this.layoutPopup((PopupState)object);
                Rect rect = new Rect(((PopupState)object).displayX, ((PopupState)object).displayY, ((PopupState)object).displayX + ((PopupState)object).displayWidth, ((PopupState)object).displayY + ((PopupState)object).displayHeight);
                canvas.drawBitmap(((PopupState)object).bitmap, null, rect, null);
                this.drawSubsurfacesForParent(canvas, ((PopupState)object).wlSurfaceId, ((PopupState)object).displayX, ((PopupState)object).displayY, ((PopupState)object).pixelsPerLogical);
            }
            this.bitmap = bitmap;
            Runnable runnable = this.frameCommittedCallback;
            if (runnable != null) {
                runnable.run();
            }
        }

        private void drawSubsurfacesForParent(Canvas canvas, int n, float f, float f2, float f3) {
            ArrayList<SubsurfaceState> arrayList = new ArrayList<SubsurfaceState>();
            for (SubsurfaceState subsurfaceState3 : this.subsurfaces.values()) {
                if (subsurfaceState3.parentSurfaceId != n || !subsurfaceState3.visible || !subsurfaceState3.aboveParent || subsurfaceState3.bitmap == null) continue;
                arrayList.add(subsurfaceState3);
            }
            arrayList.sort((subsurfaceState, subsurfaceState2) -> Integer.compare(subsurfaceState.sequence, subsurfaceState2.sequence));
            for (SubsurfaceState subsurfaceState3 : arrayList) {
                int n2 = Math.max(1, this.surfaceBufferScales.getOrDefault(subsurfaceState3.wlSurfaceId, 1));
                float f4 = f3 / (float)n2;
                subsurfaceState3.pixelsPerLogical = Math.max(0.01f, f3);
                subsurfaceState3.displayX = Math.round(f + (float)subsurfaceState3.x * f3);
                subsurfaceState3.displayY = Math.round(f2 + (float)subsurfaceState3.y * f3);
                subsurfaceState3.displayWidth = Math.max(1, Math.round((float)subsurfaceState3.bitmap.getWidth() * f4));
                subsurfaceState3.displayHeight = Math.max(1, Math.round((float)subsurfaceState3.bitmap.getHeight() * f4));
                Rect rect = new Rect(subsurfaceState3.displayX, subsurfaceState3.displayY, subsurfaceState3.displayX + subsurfaceState3.displayWidth, subsurfaceState3.displayY + subsurfaceState3.displayHeight);
                canvas.drawBitmap(subsurfaceState3.bitmap, null, rect, null);
                this.drawSubsurfacesForParent(canvas, subsurfaceState3.wlSurfaceId, subsurfaceState3.displayX, subsurfaceState3.displayY, f3);
            }
        }

        private void layoutPopup(PopupState popupState) {
            int n;
            Object object;
            float f = this.outputScale;
            int n2 = 0;
            int n3 = 0;
            int n4 = 0;
            int n5 = 0;
            Object object2 = popupState;
            while (object2 != null) {
                n4 += ((PopupState)object2).configureX;
                n5 += ((PopupState)object2).configureY;
                object = this.popupsByXdgSurface.get(((PopupState)object2).parentXdgSurfaceId);
                if (object != null) {
                    object2 = object;
                    continue;
                }
                ChildToplevelState childToplevelState = this.childToplevelsByXdg.get(((PopupState)object2).parentXdgSurfaceId);
                if (childToplevelState == null) break;
                n = Math.max(1, this.surfaceBufferScales.getOrDefault(childToplevelState.wlSurfaceId, 1));
                f = (float)childToplevelState.displayWidth / (float)Math.max(1, childToplevelState.sourceWidth) * (float)n;
                n2 = childToplevelState.displayX;
                n3 = childToplevelState.displayY;
                break;
            }
            int n6 = (object = this.windowGeometries.get(popupState.xdgSurfaceId)) == null ? 0 : ((WindowGeometry)object).x;
            n = object == null ? 0 : ((WindowGeometry)object).y;
            int n7 = Math.max(1, this.surfaceBufferScales.getOrDefault(popupState.wlSurfaceId, 1));
            float f2 = f / (float)n7;
            popupState.pixelsPerLogical = Math.max(0.01f, f);
            popupState.displayX = n2 + Math.round((float)(n4 - n6) * f);
            popupState.displayY = n3 + Math.round((float)(n5 - n) * f);
            popupState.displayWidth = Math.max(1, Math.round((float)popupState.bitmap.getWidth() * f2));
            popupState.displayHeight = Math.max(1, Math.round((float)popupState.bitmap.getHeight() * f2));
        }

        private int presentationOriginX() {
            WindowGeometry windowGeometry = this.windowGeometries.get(this.xdgSurfaceId);
            int n = Math.max(1, this.surfaceBufferScales.getOrDefault(this.surfaceId, 1));
            return windowGeometry == null ? 0 : Math.max(0, windowGeometry.x * n);
        }

        private int presentationOriginY() {
            WindowGeometry windowGeometry = this.windowGeometries.get(this.xdgSurfaceId);
            int n = Math.max(1, this.surfaceBufferScales.getOrDefault(this.surfaceId, 1));
            return windowGeometry == null ? 0 : Math.max(0, windowGeometry.y * n);
        }

        private void restoreMainBitmap() {
            this.composeSurfaceTree();
        }

        private void addMainDamage(int n, int n2, int n3, int n4) {
            long l = (long)n + (long)Math.max(0, n3);
            long l2 = (long)n2 + (long)Math.max(0, n4);
            int n5 = Math.max(0, n);
            int n6 = Math.max(0, n2);
            int n7 = (int)Math.max((long)n5, Math.min(Integer.MAX_VALUE, l));
            int n8 = (int)Math.max((long)n6, Math.min(Integer.MAX_VALUE, l2));
            if (!this.mainDamagePending) {
                this.mainDamageLeft = n5;
                this.mainDamageTop = n6;
                this.mainDamageRight = n7;
                this.mainDamageBottom = n8;
                this.mainDamagePending = true;
            } else {
                this.mainDamageLeft = Math.min(this.mainDamageLeft, n5);
                this.mainDamageTop = Math.min(this.mainDamageTop, n6);
                this.mainDamageRight = Math.max(this.mainDamageRight, n7);
                this.mainDamageBottom = Math.max(this.mainDamageBottom, n8);
            }
        }

        private void commitBuffer() throws Exception {
            int n;
            int n2;
            int n3;
            int n4;
            if (!this.mainBufferAttachPending) {
                return;
            }
            ShmBufferState shmBufferState = this.shmBuffers.get(this.attachedBufferId);
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
            long l = (long)shmBufferState.offset + (long)this.stride * (long)this.height;
            if (this.width <= 0 || this.height <= 0 || this.stride < this.width * 4 || l > (long)shmBufferState.pool.size) {
                throw new IllegalStateException("invalid buffer state before commit");
            }
            byte[] byArray = RawWaylandShmServer.readExactFromFd(shmBufferState.pool.fd, shmBufferState.offset, this.stride * this.height);
            ++this.commitCount;
            this.bytesRead = byArray.length;
            int[] nArray = new int[this.width * this.height];
            for (int i = 0; i < this.height; ++i) {
                n4 = i * this.stride;
                for (n3 = 0; n3 < this.width; ++n3) {
                    n2 = n4 + n3 * 4;
                    n = byArray[n2] & 0xFF;
                    int n5 = byArray[n2 + 1] & 0xFF;
                    int n6 = byArray[n2 + 2] & 0xFF;
                    int n7 = shmBufferState.format == 0 ? byArray[n2 + 3] & 0xFF : 255;
                    if (n7 < 255) {
                        n6 = (n6 * n7 + 255 * (255 - n7)) / 255;
                        n5 = (n5 * n7 + 255 * (255 - n7)) / 255;
                        n = (n * n7 + 255 * (255 - n7)) / 255;
                    }
                    nArray[i * this.width + n3] = 0xFF000000 | n6 << 16 | n5 << 8 | n;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap((int[])nArray, (int)this.width, (int)this.height, (Bitmap.Config)Bitmap.Config.ARGB_8888);
            this.mainBitmap = bitmap;
            this.mainDamagePending = false;
            this.composeSurfaceTree();
        }

        private static byte[] readExact(InputStream inputStream, int n) throws Exception {
            int n2;
            byte[] byArray = new byte[n];
            for (int i = 0; i < n; i += n2) {
                n2 = inputStream.read(byArray, i, n - i);
                if (n2 != -1) continue;
                throw new EOFException("EOF after " + i + " of " + n + " bytes");
            }
            return byArray;
        }

        private static byte[] readExactFromFd(FileDescriptor fileDescriptor, int n, int n2) throws Exception {
            int n3;
            byte[] byArray = new byte[n2];
            FileInputStream fileInputStream = new FileInputStream(fileDescriptor);
            fileInputStream.getChannel().position(n);
            for (int i = 0; i < n2; i += n3) {
                n3 = fileInputStream.read(byArray, i, n2 - i);
                if (n3 != -1) continue;
                throw new EOFException("EOF after " + i + " of " + n2 + " raw Wayland shm bytes");
            }
            return byArray;
        }

        private static int saturatedAdd(int n, int n2) {
            long l = (long)n + (long)n2;
            return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, l));
        }

        private static int saturatedScale(int n, int n2) {
            long l = (long)n * (long)n2;
            return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, l));
        }

        private static int u32(byte[] byArray, int n) {
            return byArray[n] & 0xFF | (byArray[n + 1] & 0xFF) << 8 | (byArray[n + 2] & 0xFF) << 16 | (byArray[n + 3] & 0xFF) << 24;
        }

        private static void putU32(byte[] byArray, int n, int n2) {
            byArray[n] = (byte)(n2 & 0xFF);
            byArray[n + 1] = (byte)(n2 >>> 8 & 0xFF);
            byArray[n + 2] = (byte)(n2 >>> 16 & 0xFF);
            byArray[n + 3] = (byte)(n2 >>> 24 & 0xFF);
        }

        private static String stringArg(byte[] byArray, int n) throws Exception {
            int n2 = RawWaylandShmServer.u32(byArray, n);
            if (n2 <= 0 || n + 4 + n2 > byArray.length) {
                throw new IllegalArgumentException("invalid Wayland string length " + n2);
            }
            return new String(byArray, n + 4, n2 - 1, StandardCharsets.UTF_8);
        }

        private static int stringPaddedLength(byte[] byArray, int n) {
            int n2 = RawWaylandShmServer.u32(byArray, n);
            return 4 + (n2 + 3 & 0xFFFFFFFC);
        }

        private static final class SubsurfaceState {
            final int subsurfaceId;
            final int wlSurfaceId;
            final int parentSurfaceId;
            final int sequence;
            int x;
            int y;
            int displayX;
            int displayY;
            int displayWidth = 1;
            int displayHeight = 1;
            float pixelsPerLogical = 1.0f;
            boolean aboveParent = true;
            boolean synchronizedCommit = true;
            boolean visible;
            Bitmap bitmap;

            SubsurfaceState(int n, int n2, int n3, int n4) {
                this.subsurfaceId = n;
                this.wlSurfaceId = n2;
                this.parentSurfaceId = n3;
                this.sequence = n4;
            }
        }

        private static final class ClipboardSourceState {
            final int id;
            final ArrayList<String> mimeTypes = new ArrayList();
            int actions;

            ClipboardSourceState(int n) {
                this.id = n;
            }

            String preferredTextMime() {
                if (this.mimeTypes.contains("text/plain")) {
                    return "text/plain";
                }
                return this.mimeTypes.contains("text/plain;charset=utf-8") ? "text/plain;charset=utf-8" : null;
            }
        }

        private static final class PositionerState {
            int width = 1;
            int height = 1;
            int anchorX;
            int anchorY;
            int anchorWidth;
            int anchorHeight;
            int anchor;
            int gravity;
            int constraintAdjustment;
            int offsetX;
            int offsetY;
            int parentWidth;
            int parentHeight;
            int parentConfigureSerial;
            boolean reactive;

            PositionerState() {
            }

            PositionerState(PositionerState positionerState) {
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

        private static final class ChildToplevelState {
            final int toplevelId;
            final int xdgSurfaceId;
            final int wlSurfaceId;
            final int sequence;
            int parentToplevelId;
            int configureSerial;
            int displayX;
            int displayY;
            int displayWidth;
            int displayHeight;
            int sourceWidth = 1;
            int sourceHeight = 1;
            int bufferOriginX;
            int bufferOriginY;
            int minWidth;
            int minHeight;
            int maxWidth;
            int maxHeight;
            String title = "";
            String appId = "";
            boolean configureSent;
            boolean configureAcked;
            boolean visible;
            Bitmap bitmap;

            ChildToplevelState(int n, int n2, int n3, int n4) {
                this.toplevelId = n;
                this.xdgSurfaceId = n2;
                this.wlSurfaceId = n3;
                this.sequence = n4;
            }

            boolean isSaveDialog() {
                return this.title.toLowerCase(Locale.ROOT).contains("save");
            }

            boolean isFileDialog() {
                String string = this.title.toLowerCase(Locale.ROOT);
                return string.contains("save") || string.contains("open") || string.contains("file chooser") || string.contains("select a file");
            }
        }

        private static final class WindowGeometry {
            final int x;
            final int y;
            final int width;
            final int height;

            WindowGeometry(int n, int n2, int n3, int n4) {
                this.x = n;
                this.y = n2;
                this.width = n3;
                this.height = n4;
            }
        }

        private static final class PopupState {
            final int popupId;
            final int xdgSurfaceId;
            final int wlSurfaceId;
            final int parentXdgSurfaceId;
            final int sequence;
            PositionerState positioner;
            int configureX;
            int configureY;
            int width;
            int height;
            int configureSerial;
            int grabSerial;
            int displayX;
            int displayY;
            int displayWidth = 1;
            int displayHeight = 1;
            float pixelsPerLogical = 1.0f;
            boolean configureSent;
            boolean configureAcked;
            boolean grabbed;
            boolean visible;
            Bitmap bitmap;

            PopupState(int n, int n2, int n3, int n4, PositionerState positionerState, int n5) {
                this.popupId = n;
                this.xdgSurfaceId = n2;
                this.wlSurfaceId = n3;
                this.parentXdgSurfaceId = n4;
                this.sequence = n5;
                this.applyPositioner(positionerState);
            }

            void applyPositioner(PositionerState positionerState) {
                this.positioner = new PositionerState(positionerState);
                this.width = Math.max(1, this.positioner.width);
                this.height = Math.max(1, this.positioner.height);
                int n = PopupState.anchorPointX(this.positioner);
                int n2 = PopupState.anchorPointY(this.positioner);
                this.configureX = PopupState.gravityX(n, this.width, this.positioner.gravity) + this.positioner.offsetX;
                this.configureY = PopupState.gravityY(n2, this.height, this.positioner.gravity) + this.positioner.offsetY;
            }

            private static int anchorPointX(PositionerState positionerState) {
                switch (positionerState.anchor) {
                    case 3: 
                    case 5: 
                    case 6: {
                        return positionerState.anchorX;
                    }
                    case 4: 
                    case 7: 
                    case 8: {
                        return positionerState.anchorX + positionerState.anchorWidth;
                    }
                }
                return positionerState.anchorX + positionerState.anchorWidth / 2;
            }

            private static int anchorPointY(PositionerState positionerState) {
                switch (positionerState.anchor) {
                    case 1: 
                    case 5: 
                    case 7: {
                        return positionerState.anchorY;
                    }
                    case 2: 
                    case 6: 
                    case 8: {
                        return positionerState.anchorY + positionerState.anchorHeight;
                    }
                }
                return positionerState.anchorY + positionerState.anchorHeight / 2;
            }

            private static int gravityX(int n, int n2, int n3) {
                switch (n3) {
                    case 3: 
                    case 5: 
                    case 6: {
                        return n - n2;
                    }
                    case 4: 
                    case 7: 
                    case 8: {
                        return n;
                    }
                }
                return n - n2 / 2;
            }

            private static int gravityY(int n, int n2, int n3) {
                switch (n3) {
                    case 1: 
                    case 5: 
                    case 7: {
                        return n - n2;
                    }
                    case 2: 
                    case 6: 
                    case 8: {
                        return n;
                    }
                }
                return n - n2 / 2;
            }
        }

        private void closeAllShmPools() {
            HashSet<ShmPoolState> pools = new HashSet<>(this.shmPools.values());
            for (ShmBufferState buffer : this.shmBuffers.values()) pools.add(buffer.pool);
            for (ShmPoolState pool : pools) pool.closeNow();
            while (!this.pendingShmFds.isEmpty()) {
                FileDescriptor fd = this.pendingShmFds.removeFirst();
                try {
                    if (fd.valid()) Os.close(fd);
                } catch (Exception ignored) {
                }
            }
            this.shmPools.clear();
            this.shmBuffers.clear();
        }

        private static final class ShmPoolState {
            final FileDescriptor fd;
            int size;
            int bufferRefs;
            boolean destroyed;
            boolean closed;

            ShmPoolState(FileDescriptor fileDescriptor, int n) {
                this.fd = fileDescriptor;
                this.size = n;
            }

            void closeIfUnused() {
                if (this.destroyed && this.bufferRefs == 0) this.closeNow();
            }

            void closeNow() {
                if (this.closed) return;
                this.closed = true;
                try {
                    if (this.fd.valid()) Os.close(this.fd);
                } catch (Exception ignored) {
                }
            }
        }

        private static final class ShmBufferState {
            final ShmPoolState pool;
            final int offset;
            final int width;
            final int height;
            final int stride;
            final int format;

            ShmBufferState(ShmPoolState shmPoolState, int n, int n2, int n3, int n4, int n5) {
                this.pool = shmPoolState;
                this.offset = n;
                this.width = n2;
                this.height = n3;
                this.stride = n4;
                this.format = n5;
            }
        }
    }

    private static final class FrameBridgeServer
    implements Runnable {
        final File socket;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean listening;
        volatile boolean accepted;
        volatile String header = "";
        volatile String error = "";
        volatile int width;
        volatile int height;
        volatile int bytesRead;
        volatile Bitmap bitmap;

        FrameBridgeServer(File file) {
            this.socket = file;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public void run() {
            try {
                FileDescriptor fileDescriptor = MainActivity.createFilesystemWaylandServer(this.socket.getAbsolutePath());
                try (LocalServerSocket localServerSocket = new LocalServerSocket(fileDescriptor);){
                    this.listening = true;
                    this.ready.countDown();
                    try (LocalSocket localSocket = localServerSocket.accept();
                         InputStream inputStream = localSocket.getInputStream();){
                        this.accepted = true;
                        this.header = FrameBridgeServer.readFrameHeader(inputStream);
                        String[] stringArray = this.header.trim().split(" ");
                        if (stringArray.length != 3 || !"ARCHPHENE_FRAME_V1".equals(stringArray[0])) {
                            throw new IllegalArgumentException("unexpected frame header: " + this.header);
                        }
                        this.width = Integer.parseInt(stringArray[1]);
                        this.height = Integer.parseInt(stringArray[2]);
                        if (this.width <= 0 || this.height <= 0 || this.width > 4096 || this.height > 4096) {
                            throw new IllegalArgumentException("invalid frame dimensions: " + this.width + "x" + this.height);
                        }
                        int n = this.width * this.height * 4;
                        byte[] byArray = FrameBridgeServer.readExact(inputStream, n);
                        this.bytesRead = byArray.length;
                        int[] nArray = new int[this.width * this.height];
                        int n2 = 0;
                        int n3 = 0;
                        while (n2 < nArray.length) {
                            int n4 = byArray[n3] & 0xFF;
                            int n5 = byArray[n3 + 1] & 0xFF;
                            int n6 = byArray[n3 + 2] & 0xFF;
                            int n7 = byArray[n3 + 3] & 0xFF;
                            nArray[n2] = n7 << 24 | n4 << 16 | n5 << 8 | n6;
                            ++n2;
                            n3 += 4;
                        }
                        this.bitmap = Bitmap.createBitmap((int[])nArray, (int)this.width, (int)this.height, (Bitmap.Config)Bitmap.Config.ARGB_8888);
                    }
                }
            }
            catch (Throwable throwable) {
                this.error = throwable.toString();
                this.ready.countDown();
            }
            finally {
                if (this.socket.exists()) {
                    this.socket.delete();
                }
            }
        }

        private static String readFrameHeader(InputStream inputStream) throws Exception {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            while (byteArrayOutputStream.size() < 128) {
                int n = inputStream.read();
                if (n == -1) {
                    throw new EOFException("EOF before frame header");
                }
                byteArrayOutputStream.write(n);
                if (n != 10) continue;
                return byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
            }
            throw new IllegalArgumentException("frame header too long");
        }

        private static byte[] readExact(InputStream inputStream, int n) throws Exception {
            int n2;
            byte[] byArray = new byte[n];
            for (int i = 0; i < n; i += n2) {
                n2 = inputStream.read(byArray, i, n - i);
                if (n2 != -1) continue;
                throw new EOFException("EOF after " + i + " of " + n + " frame bytes");
            }
            return byArray;
        }
    }

    private static final class ShmFrameBridgeServer
    implements Runnable {
        final File socket;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean listening;
        volatile boolean accepted;
        volatile String header = "";
        volatile String error = "";
        volatile int fdCount;
        volatile int width;
        volatile int height;
        volatile int stride;
        volatile int bytesRead;
        volatile Bitmap bitmap;

        ShmFrameBridgeServer(File file) {
            this.socket = file;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public void run() {
            try {
                FileDescriptor fileDescriptor = MainActivity.createFilesystemWaylandServer(this.socket.getAbsolutePath());
                try (LocalServerSocket localServerSocket = new LocalServerSocket(fileDescriptor);){
                    this.listening = true;
                    this.ready.countDown();
                    try (LocalSocket localSocket = localServerSocket.accept();
                         InputStream inputStream = localSocket.getInputStream();){
                        this.accepted = true;
                        this.header = ShmFrameBridgeServer.readHeader(inputStream);
                        FileDescriptor[] fileDescriptorArray = localSocket.getAncillaryFileDescriptors();
                        int n = this.fdCount = fileDescriptorArray == null ? 0 : fileDescriptorArray.length;
                        if (fileDescriptorArray == null || fileDescriptorArray.length == 0) {
                            throw new IllegalStateException("no memfd received with shm frame");
                        }
                        String[] stringArray = this.header.trim().split(" ");
                        if (stringArray.length != 5 || !"ARCHPHENE_SHM_FRAME_V1".equals(stringArray[0])) {
                            throw new IllegalArgumentException("unexpected shm frame header: " + this.header);
                        }
                        this.width = Integer.parseInt(stringArray[1]);
                        this.height = Integer.parseInt(stringArray[2]);
                        this.stride = Integer.parseInt(stringArray[3]);
                        int n2 = Integer.parseInt(stringArray[4]);
                        if (this.width <= 0 || this.height <= 0 || this.width > 4096 || this.height > 4096 || this.stride < this.width * 4 || n2 < this.stride * this.height) {
                            throw new IllegalArgumentException("invalid shm frame metadata: " + this.header);
                        }
                        byte[] byArray = ShmFrameBridgeServer.readExactFromFd(fileDescriptorArray[0], n2);
                        this.bytesRead = byArray.length;
                        int[] nArray = new int[this.width * this.height];
                        for (int i = 0; i < this.height; ++i) {
                            int n3 = i * this.stride;
                            for (int j = 0; j < this.width; ++j) {
                                int n4 = n3 + j * 4;
                                int n5 = byArray[n4] & 0xFF;
                                int n6 = byArray[n4 + 1] & 0xFF;
                                int n7 = byArray[n4 + 2] & 0xFF;
                                int n8 = byArray[n4 + 3] & 0xFF;
                                nArray[i * this.width + j] = n8 << 24 | n5 << 16 | n6 << 8 | n7;
                            }
                        }
                        this.bitmap = Bitmap.createBitmap((int[])nArray, (int)this.width, (int)this.height, (Bitmap.Config)Bitmap.Config.ARGB_8888);
                    }
                }
            }
            catch (Throwable throwable) {
                this.error = throwable.toString();
                this.ready.countDown();
            }
            finally {
                if (this.socket.exists()) {
                    this.socket.delete();
                }
            }
        }

        private static String readHeader(InputStream inputStream) throws Exception {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            while (byteArrayOutputStream.size() < 128) {
                int n = inputStream.read();
                if (n == -1) {
                    throw new EOFException("EOF before shm frame header");
                }
                byteArrayOutputStream.write(n);
                if (n != 10) continue;
                return byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
            }
            throw new IllegalArgumentException("shm frame header too long");
        }

        private static byte[] readExactFromFd(FileDescriptor fileDescriptor, int n) throws Exception {
            byte[] byArray = new byte[n];
            try (FileInputStream fileInputStream = new FileInputStream(fileDescriptor);){
                int n2;
                for (int i = 0; i < n; i += n2) {
                    n2 = fileInputStream.read(byArray, i, n - i);
                    if (n2 != -1) continue;
                    throw new EOFException("EOF after " + i + " of " + n + " shm bytes");
                }
            }
            return byArray;
        }
    }

    private static final class Result {
        final int exitCode;
        final boolean timedOut;
        final String stdout;
        final String stderr;
        final String startError;

        Result(int n, boolean bl, String string, String string2, String string3) {
            this.exitCode = n;
            this.timedOut = bl;
            this.stdout = string;
            this.stderr = string2;
            this.startError = string3;
        }
    }

    private static final class FilesystemBridgeServer
    implements Runnable {
        final File socket;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean listening;
        volatile boolean accepted;
        volatile String received = "";
        volatile String error = "";

        FilesystemBridgeServer(File file) {
            this.socket = file;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public void run() {
            try {
                FileDescriptor fileDescriptor = MainActivity.createFilesystemWaylandServer(this.socket.getAbsolutePath());
                try (LocalServerSocket localServerSocket = new LocalServerSocket(fileDescriptor);){
                    this.listening = true;
                    this.ready.countDown();
                    try (LocalSocket localSocket = localServerSocket.accept();
                         InputStream inputStream = localSocket.getInputStream();){
                        this.accepted = true;
                        byte[] byArray = new byte[128];
                        int n = inputStream.read(byArray);
                        if (n > 0) {
                            this.received = new String(byArray, 0, n, StandardCharsets.UTF_8);
                        }
                        byte[] byArray2 = "ARCHPHENE_WAYLAND_FILESYSTEM_ACK\n".getBytes(StandardCharsets.UTF_8);
                        localSocket.getOutputStream().write(byArray2);
                        localSocket.getOutputStream().flush();
                    }
                }
            }
            catch (Throwable throwable) {
                this.error = throwable.toString();
                this.ready.countDown();
            }
            finally {
                if (this.socket.exists()) {
                    this.socket.delete();
                }
            }
        }
    }

    private static final class BridgeServer
    implements Runnable {
        final String socketName;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean listening;
        volatile boolean accepted;
        volatile String received = "";
        volatile String error = "";

        BridgeServer(String string) {
            this.socketName = string;
        }

        @Override
        public void run() {
            try (LocalServerSocket localServerSocket = new LocalServerSocket(this.socketName);){
                this.listening = true;
                this.ready.countDown();
                try (LocalSocket localSocket = localServerSocket.accept();
                     InputStream inputStream = localSocket.getInputStream();){
                    this.accepted = true;
                    byte[] byArray = new byte[128];
                    int n = inputStream.read(byArray);
                    if (n > 0) {
                        this.received = new String(byArray, 0, n, StandardCharsets.UTF_8);
                    }
                    byte[] byArray2 = "ARCHPHENE_WAYLAND_BRIDGE_ACK\n".getBytes(StandardCharsets.UTF_8);
                    localSocket.getOutputStream().write(byArray2);
                    localSocket.getOutputStream().flush();
                }
            }
            catch (Throwable throwable) {
                this.error = throwable.toString();
                this.ready.countDown();
            }
        }
    }
}
