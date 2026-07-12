package org.archphene.linux.kcalc;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.lang.ref.WeakReference;

public final class MainActivity extends Activity {
    private static final String TAG = "ArchpheneKCalc";
    private static volatile WeakReference<MainActivity> currentActivity = new WeakReference<>(null);
    private static final String KCALC_PAYLOAD = "libarchphene_kcalc.so";
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
    private static final String JNI_LOAD_ERROR = loadWaylandJni();
    private volatile RawWaylandShmServer activeInteractiveServer;
    private volatile Process activeLinuxProcess;
    private volatile BridgeRootView bridgeRootView;
    private volatile boolean waylandTextInputRequested;
    private boolean popupBackInProgress;
    private boolean suppressNextBackInvocation;
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private String pendingBridgeClipboardText;

    private static native FileDescriptor createFilesystemWaylandServer(String socketPath) throws java.io.IOException;

    static String loadWaylandJni() {
        try {
            System.loadLibrary("archphene_wayland_jni");
            return "";
        } catch (Throwable t) {
            return t.toString();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentActivity = new WeakReference<>(this);
        String seededClipboard = getIntent().getStringExtra("archphene_android_clipboard_text");
        if (seededClipboard != null) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Archphene test input", seededClipboard));
        }
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboardListener = () -> {
            if (pendingBridgeClipboardText != null && clipboardManager.hasPrimaryClip()
                    && clipboardManager.getPrimaryClip() != null
                    && clipboardManager.getPrimaryClip().getItemCount() > 0) {
                CharSequence current = clipboardManager.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (pendingBridgeClipboardText.contentEquals(current)) {
                    return;
                }
            }
            RawWaylandShmServer server = activeInteractiveServer;
            if (server != null) {
                server.publishAndroidClipboard();
            }
        };
        clipboardManager.addPrimaryClipChangedListener(clipboardListener);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        ImageView framePreview = new ImageView(this);
        framePreview.setAdjustViewBounds(false);
        framePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        framePreview.setBackgroundColor(Color.BLACK);

        String report = "KCalc Archphene interactive launch\n";
        writeReportArtifact(report);
        Log.i(TAG, "Starting interactive Wayland client");
        if (getIntent().getBooleanExtra("archphene_glibc_probe", false)) {
            startGlibcRuntimeProbe();
        }
        if (getIntent().getBooleanExtra("archphene_access_probe", false)) {
            startSyscallProbe("access");
        }
        String requestedSyscallProbe = getIntent().getStringExtra("archphene_syscall_probe");
        if (requestedSyscallProbe != null && !requestedSyscallProbe.isEmpty()) {
            startSyscallProbe(requestedSyscallProbe);
        }

        BridgeRootView root = new BridgeRootView(this);
        bridgeRootView = root;
        root.setBackgroundColor(Color.WHITE);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
        root.setOnTouchListener((view, event) -> {
            RawWaylandShmServer server = activeInteractiveServer;
            if (server != null) {
                float[] point = mapPointerCoordinates(framePreview, server, event.getX(), event.getY());
                server.handleAndroidMotionEvent(event.getActionMasked(), point[0], point[1], event.getEventTime());
                return true;
            }
            return true;
        });
        root.setOnGenericMotionListener((view, event) -> {
            RawWaylandShmServer server = activeInteractiveServer;
            int action = event.getActionMasked();
            if (server != null && action == MotionEvent.ACTION_SCROLL) {
                float[] point = mapPointerCoordinates(framePreview, server, event.getX(), event.getY());
                return server.handleAndroidScrollEvent(point[0], point[1],
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL), event.getEventTime());
            }
            if (server != null && (action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_MOVE
                    || action == MotionEvent.ACTION_MOVE)) {
                float[] point = mapPointerCoordinates(framePreview, server, event.getX(), event.getY());
                return server.handleAndroidMotionEvent(MotionEvent.ACTION_MOVE, point[0], point[1], event.getEventTime());
            }
            if (server != null && action == MotionEvent.ACTION_HOVER_EXIT) {
                return server.handleAndroidPointerExit();
            }
            return false;
        });
        root.setOnHoverListener((view, event) -> {
            RawWaylandShmServer server = activeInteractiveServer;
            int action = event.getActionMasked();
            if (server != null && (action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_MOVE)) {
                float[] point = mapPointerCoordinates(framePreview, server, event.getX(), event.getY());
                return server.handleAndroidMotionEvent(MotionEvent.ACTION_MOVE, point[0], point[1], event.getEventTime());
            }
            if (server != null && action == MotionEvent.ACTION_HOVER_EXIT) {
                return server.handleAndroidPointerExit();
            }
            return false;
        });
        root.addView(framePreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));        framePreview.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            if (width == oldRight - oldLeft && height == oldBottom - oldTop) {
                return;
            }
            RawWaylandShmServer server = activeInteractiveServer;
            if (server != null) {
                server.requestResize(width, height);
            }
        });

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (suppressNextBackInvocation) {
                            suppressNextBackInvocation = false;
                            return;
                        }
                        RawWaylandShmServer server = activeInteractiveServer;
                        if (server == null || !server.sendPopupEscape()) {
                            finish();
                        }
                    });
        }
        root.requestFocus();
        root.post(() -> startInteractivePointerProbe(framePreview, report));
    }

    private float[] mapPointerCoordinates(ImageView view, RawWaylandShmServer server, float rootX, float rootY) {
        float localX = rootX - view.getLeft();
        float localY = rootY - view.getTop();
        Bitmap committed = server.bitmap;
        int surfaceWidth = committed == null ? server.configureWidth : committed.getWidth();
        int surfaceHeight = committed == null ? server.configureHeight : committed.getHeight();
        float scale = Math.min(view.getWidth() / (float) surfaceWidth,
                view.getHeight() / (float) surfaceHeight);
        if (scale <= 0) {
            return new float[] {localX, localY};
        }
        float contentWidth = surfaceWidth * scale;
        float contentHeight = surfaceHeight * scale;
        return new float[] {
                (localX - (view.getWidth() - contentWidth) / 2f) / scale,
                (localY - (view.getHeight() - contentHeight) / 2f) / scale
        };
    }
    private void setBridgeClipboardText(String text) {
        pendingBridgeClipboardText = text;
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Archphene Linux app", text));
        bridgeRootView.postDelayed(() -> {
            if (text.equals(pendingBridgeClipboardText)) {
                pendingBridgeClipboardText = null;
            }
        }, 1000);
    }
    @Override
    protected void onDestroy() {
        if (clipboardManager != null && clipboardListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener);
        }
        Process process = activeLinuxProcess;
        if (process != null) {
            process.destroy();
        }
        MainActivity activity = currentActivity.get();
        if (activity == this) {
            currentActivity.clear();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        BridgeRootView root = bridgeRootView;
        if (root == null) {
            return;
        }
        root.post(() -> {
            RawWaylandShmServer server = activeInteractiveServer;
            if (server != null) {
                ImageView preview = (ImageView) root.getChildAt(0);
                server.requestResize(Math.max(320, preview.getWidth()), Math.max(240, preview.getHeight()));
            }
        });
    }
    @Override
    public void onBackPressed() {
        RawWaylandShmServer server = activeInteractiveServer;
        if (server != null && server.sendPopupEscape()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        RawWaylandShmServer server = activeInteractiveServer;
        if (server != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && server.hasVisiblePopups()) {
                popupBackInProgress = true;
                suppressNextBackInvocation = true;
                server.handleAndroidKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, event.getEventTime());
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && popupBackInProgress) {
                popupBackInProgress = false;
                server.handleAndroidKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, event.getEventTime());
                return true;
            }
        }
        if (server != null && server.handleAndroidKeyEvent(event.getAction(), event.getKeyCode(), event.getEventTime())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private final class BridgeRootView extends FrameLayout {
        BridgeRootView(Context context) {
            super(context);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return waylandTextInputRequested;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (!waylandTextInputRequested) {
                return null;
            }
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
            RawWaylandShmServer server = activeInteractiveServer;
            if (server != null) {
                server.noteAndroidInputConnectionCreated();
            }
            return new BaseInputConnection(this, true) {
                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    RawWaylandShmServer server = activeInteractiveServer;
                    if (server != null && server.handleAndroidImeCommitText(text)) {
                        return true;
                    }
                    return super.commitText(text, newCursorPosition);
                }

                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    RawWaylandShmServer server = activeInteractiveServer;
                    if (server != null && server.handleAndroidImeDelete()) {
                        return true;
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength);
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    RawWaylandShmServer server = activeInteractiveServer;
                    if (server != null && server.handleAndroidKeyEvent(event.getAction(), event.getKeyCode(), event.getEventTime())) {
                        return true;
                    }
                    return super.sendKeyEvent(event);
                }
            };
        }
    }
    private int[] displayPixelSize() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int width = Math.max(320, Math.min(4096, metrics.widthPixels));
        int height = Math.max(240, Math.min(4096, metrics.heightPixels));
        return new int[] {width, height};
    }
    private void putDisplaySizeEnv(Map<String, String> env) {
        int[] size = displayPixelSize();
        env.put("ARCHPHENE_WIDTH", Integer.toString(size[0]));
        env.put("ARCHPHENE_HEIGHT", Integer.toString(size[1]));
        putQtDensityEnv(env);
    }
    private void putQtDensityEnv(Map<String, String> env) {
        float androidDensity = getResources().getDisplayMetrics().density;
        int fontDpi = Math.max(96, Math.min(384, Math.round(96.0f * androidDensity)));
        env.put("QT_FONT_DPI", Integer.toString(fontDpi));
    }
    private void writeReportArtifact(String report) {
        File reportFile = new File(getFilesDir(), "kcalc-report.txt");
        try (FileOutputStream out = new FileOutputStream(reportFile)) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "Bridge report written to " + reportFile.getAbsolutePath());
        } catch (Exception ignored) {
            Log.e(TAG, "Could not write bridge report", ignored);
        }
    }
    private static void logReportSummary(String label, String report) {
        Log.i(TAG, label + "\n" + summarizeWindowReport(report));
    }
    private static String summarizeWindowReport(String report) {
        StringBuilder out = new StringBuilder();
        out.append("Archphene KCalc window bridge\n");
        appendSummaryLine(out, report, "Raw Wayland parsed messages:");
        appendSummaryLine(out, report, "Raw Wayland fd count:");
        appendSummaryLine(out, report, "Raw Wayland dimensions:");
        appendSummaryLine(out, report, "Raw Wayland committed:");
        appendSummaryLine(out, report, "Raw Wayland bitmap ready:");
        appendSummaryLine(out, report, "Evented Wayland parsed messages:");
        appendSummaryLine(out, report, "Evented Wayland registry globals:");
        appendSummaryLine(out, report, "Evented Wayland dimensions:");
        appendSummaryLine(out, report, "Evented Wayland committed:");
        appendSummaryLine(out, report, "Evented Wayland bitmap ready:");
        appendSummaryLine(out, report, "XDG Wayland parsed messages:");
        appendSummaryLine(out, report, "XDG Wayland configure sent:");
        appendSummaryLine(out, report, "XDG Wayland configure acked:");
        appendSummaryLine(out, report, "XDG Wayland frame callback done:");
        appendSummaryLine(out, report, "XDG Wayland buffer released:");
        appendSummaryLine(out, report, "XDG Wayland dimensions:");
        appendSummaryLine(out, report, "XDG Wayland committed:");
        appendSummaryLine(out, report, "XDG Wayland bitmap ready:");
        appendSummaryLine(out, report, "Wayland API client exit code:");
        appendSummaryLine(out, report, "Wayland API server accepted:");
        appendSummaryLine(out, report, "Wayland API server sync callbacks:");
        appendSummaryLine(out, report, "Wayland API server shm formats:");
        appendSummaryLine(out, report, "Android Wayland API client exit code:");
        appendSummaryLine(out, report, "Android Wayland API server accepted:");
        appendSummaryLine(out, report, "Android Wayland API server sync callbacks:");
        appendSummaryLine(out, report, "Android Wayland API server shm formats:");
        appendSummaryLine(out, report, "Android Wayland API render exit code:");
        appendSummaryLine(out, report, "Android Wayland API render committed:");
        appendSummaryLine(out, report, "Android Wayland API render bitmap ready:");
        appendSummaryLine(out, report, "Android Wayland API xdg exit code:");
        appendSummaryLine(out, report, "Android Wayland API xdg configure acked:");
        appendSummaryLine(out, report, "Android Wayland API xdg output done:");
        appendSummaryLine(out, report, "Android Wayland API xdg seat capabilities sent:");
        appendSummaryLine(out, report, "Android Wayland API xdg pointer requested:");
        appendSummaryLine(out, report, "Android Wayland API xdg pointer events sent:");
        appendSummaryLine(out, report, "Android Wayland API interactive pointer exit code:");
        appendSummaryLine(out, report, "Android Wayland API interactive pointer timed out:");
        appendSummaryLine(out, report, "Android Wayland API interactive pointer android events:");
        appendSummaryLine(out, report, "Android Wayland API interactive pointer native repaint:");
        appendSummaryLine(out, report, "Android Wayland API interactive keyboard android events:");
        appendSummaryLine(out, report, "Android Wayland API interactive keyboard native repaint:");
        appendSummaryLine(out, report, "Android Wayland API interactive keyboard modifier events:");
        appendSummaryLine(out, report, "Android Wayland API interactive keyboard repeat info sent:");
        appendSummaryLine(out, report, "Android Wayland API interactive IME input connections:");
        appendSummaryLine(out, report, "Android Wayland API interactive IME commit events:");
        appendSummaryLine(out, report, "Android Wayland API interactive IME synthesized key events:");
        appendSummaryLine(out, report, "Android Wayland API interactive keyboard last modifiers:");
        appendSummaryLine(out, report, "Android Wayland API interactive pointer bitmap ready:");
        appendSummaryLine(out, report, "Android Wayland API xdg frame callback done:");
        appendSummaryLine(out, report, "Android Wayland API xdg buffer released:");
        appendSummaryLine(out, report, "Android Wayland API xdg post-commit sync done:");
        appendSummaryLine(out, report, "Android Wayland API xdg cleanup sync done:");
        appendSummaryLine(out, report, "Android Wayland API xdg destroy requests:");
        appendSummaryLine(out, report, "Android Wayland API xdg bitmap ready:");
        appendSummaryLine(out, report, "glibc loader --list kcalc");
        return out.toString();
    }

    private static void appendSummaryLine(StringBuilder out, String report, String prefix) {
        int start = report.indexOf(prefix);
        if (start < 0) {
            return;
        }
        int end = report.indexOf('\n', start);
        if (end < 0) {
            end = report.length();
        }
        out.append(report, start, end).append('\n');
    }

    private String renderReport(ImageView framePreview) {
        File apkLibDir = new File(getApplicationInfo().nativeLibraryDir);
        File libDir = new File(getFilesDir(), "linux-runtime/lib");
        String runtimePrep = prepareLinuxRuntime(libDir);
        File kcalc = new File(apkLibDir, KCALC_PAYLOAD);
        File waylandProbe = new File(apkLibDir, WAYLAND_PROBE_PAYLOAD);
        File waylandJni = new File(apkLibDir, WAYLAND_JNI_PAYLOAD);
        File frameClient = new File(apkLibDir, FRAME_CLIENT_PAYLOAD);
        File shmFrameClient = new File(apkLibDir, SHM_FRAME_CLIENT_PAYLOAD);
        File waylandShmClient = new File(apkLibDir, WAYLAND_SHM_CLIENT_PAYLOAD);
        File waylandEventedClient = new File(apkLibDir, WAYLAND_EVENTED_CLIENT_PAYLOAD);
        File waylandXdgClient = new File(apkLibDir, WAYLAND_XDG_CLIENT_PAYLOAD);
        File waylandApiClient = new File(apkLibDir, WAYLAND_API_CLIENT_PAYLOAD);
        File waylandAndroidApiClient = new File(apkLibDir, WAYLAND_ANDROID_API_CLIENT_PAYLOAD);
        File waylandAndroidApiRenderClient = new File(apkLibDir, WAYLAND_ANDROID_API_RENDER_CLIENT_PAYLOAD);
        File waylandAndroidApiXdgClient = new File(apkLibDir, WAYLAND_ANDROID_API_XDG_CLIENT_PAYLOAD);
        File waylandAndroidClientLib = new File(apkLibDir, WAYLAND_ANDROID_CLIENT_LIB);
        File loader = new File(apkLibDir, GLIBC_LOADER);
        File syscallProbe = new File(apkLibDir, SYSCALL_PROBE);
        File libc = new File(libDir, GLIBC_LIBC);
        File libm = new File(libDir, "libm.so.6");
        File qtCore = new File(libDir, "libQt6Core.so.6");

        StringBuilder out = new StringBuilder();
        out.append("KCalc Archphene launcher proof\n\n");
        out.append("Android package: ").append(getPackageName()).append("\n");
        out.append("App label: KCalc\n");
        out.append("UID: ").append(android.os.Process.myUid()).append("\n");
        out.append("nativeLibraryDir: ").append(apkLibDir.getAbsolutePath()).append("\n");
        out.append("linuxRuntimeLibDir: ").append(libDir.getAbsolutePath()).append("\n");
        out.append(runtimePrep).append("\n\n");
        out.append("Arch package metadata (.PKGINFO)\n");
        out.append(readAsset("kcalc.PKGINFO", 4096)).append("\n");

        appendFileState(out, "Native-dir real Arch usr/bin/kcalc ELF entrypoint", kcalc);
        appendFileState(out, "Native-dir Wayland socket probe ELF entrypoint", waylandProbe);
        appendFileState(out, "APK-extracted Wayland JNI socket binder", waylandJni);
        appendFileState(out, "Native-dir Linux frame client", frameClient);
        appendFileState(out, "Native-dir Linux wl_shm-style frame client", shmFrameClient);
        appendFileState(out, "Native-dir raw Wayland wl_shm client", waylandShmClient);
        appendFileState(out, "Native-dir evented Wayland wl_shm client", waylandEventedClient);
        appendFileState(out, "Native-dir xdg-shell Wayland client", waylandXdgClient);
        appendFileState(out, "Native-dir libwayland-client API probe", waylandApiClient);
        appendFileState(out, "Native-dir Android Wayland API probe", waylandAndroidApiClient);
        appendFileState(out, "Native-dir Android Wayland API render probe", waylandAndroidApiRenderClient);
        appendFileState(out, "Native-dir Android Wayland API xdg probe", waylandAndroidApiXdgClient);
        appendFileState(out, "Native-dir Android Wayland client shim", waylandAndroidClientLib);
        out.append("Wayland JNI load error: ").append(JNI_LOAD_ERROR).append("\n\n");
        appendFileState(out, "Native-dir glibc loader entrypoint", loader);
        appendFileState(out, "Native-dir static syscall probe", syscallProbe);
        appendFileState(out, "App-private packaged glibc libc", libc);
        out.append(reportPatchBytes(loader, libc));
        out.append("\n");
        out.append(runStracedLoaderList(loader, libDir, apkLibDir, libc));
        out.append("\n");

        out.append(runRenderedFrameBridge(frameClient, framePreview));
        out.append("\n");
        out.append(runShmFrameBridge(shmFrameClient, framePreview));
        out.append("\n");
        out.append(runRawWaylandShmBridge(waylandShmClient, framePreview));
        out.append("\n");
        out.append(runEventedWaylandShmBridge(waylandEventedClient, framePreview));
        out.append("\n");
        out.append(runXdgWaylandShmBridge(waylandXdgClient, framePreview));
        out.append("\n");
        out.append(runWaylandApiClientProbe(waylandApiClient));
        out.append("\n");
        out.append(runAndroidWaylandApiClientProbe(waylandAndroidApiClient));
        out.append("\n");
        out.append(runAndroidWaylandApiRenderClientProbe(waylandAndroidApiRenderClient, framePreview));
        out.append("\n");
        out.append(runAndroidWaylandApiXdgClientProbe(waylandAndroidApiXdgClient, framePreview));
        out.append("\n");
        out.append(runFilesystemWaylandSocketProbe(waylandProbe));
        out.append("\n");
        out.append(runWaylandSocketProbe(waylandProbe));
        out.append("\n");
        out.append(runNamed("Direct kcalc process launch", new String[] {kcalc.getAbsolutePath()}));
        out.append("\n");
        out.append(runNamed("glibc loader --verify kcalc", new String[] {loader.getAbsolutePath(), "--verify", kcalc.getAbsolutePath()}));
        out.append("\n");
        out.append(runNamed(
                "glibc loader --list libc",
                new String[] {
                    loader.getAbsolutePath(),
                    "--library-path",
                    libDir.getAbsolutePath() + ":" + apkLibDir.getAbsolutePath(),
                    libc.getAbsolutePath()
                }));
        out.append("\n");
        out.append(runNamed(
                "glibc loader --list libm",
                new String[] {
                    loader.getAbsolutePath(),
                    "--library-path",
                    libDir.getAbsolutePath() + ":" + apkLibDir.getAbsolutePath(),
                    libm.getAbsolutePath()
                }));
        out.append("\n");
        out.append(runNamed(
                "glibc loader --list QtCore",
                new String[] {
                    loader.getAbsolutePath(),
                    "--library-path",
                    libDir.getAbsolutePath() + ":" + apkLibDir.getAbsolutePath(),
                    qtCore.getAbsolutePath()
                }));
        out.append("\n");
        out.append(runNamed(
                "glibc loader --list kcalc",
                new String[] {
                    loader.getAbsolutePath(),
                    "--library-path",
                    libDir.getAbsolutePath() + ":" + apkLibDir.getAbsolutePath(),
                    kcalc.getAbsolutePath()
                }));
        out.append("\n");
        out.append(runSyscallProbeMatrix(syscallProbe));
        out.append("\n");
        out.append("Current expected result: this APK proves KCalc can be installed and launched as a normal Android app identity, with the real Arch KCalc ELF in Android nativeLibraryDir and the Arch runtime closure extracted into app-private storage. The remaining pre-GUI blocker is glibc compatibility with the app-spawned Android syscall profile; after that the bridge needs a real Wayland compositor protocol implementation for Qt/KF6 windows.\n");
        return out.toString();
    }

    private String runStracedLoaderList(File loader, File libDir, File apkLibDir, File target) {
        File trace = new File(getCacheDir(), "ld-list-libc.strace");
        if (trace.exists()) {
            trace.delete();
        }
        String[] command = new String[] {
                "/system/bin/strace",
                "-f",
                "-s",
                "160",
                "-o",
                trace.getAbsolutePath(),
                loader.getAbsolutePath(),
                "--library-path",
                libDir.getAbsolutePath() + ":" + apkLibDir.getAbsolutePath(),
                target.getAbsolutePath()
        };
        StringBuilder out = new StringBuilder();
        out.append("App-spawned strace for glibc loader --list libc\n\n");
        out.append(runNamed("strace ld.so --list libc", command));
        out.append("\nTrace tail:\n");
        out.append(readTail(trace, 12000));
        out.append("\n");
        return out.toString();
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

    private String runRenderedFrameBridge(File frameClient, ImageView framePreview) {
        StringBuilder out = new StringBuilder();
        out.append("Linux-rendered frame bridge proof\n\n");
        appendFileState(out, "Frame client payload", frameClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-frame-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale frame socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        FrameBridgeServer server = new FrameBridgeServer(socket);
        Thread thread = new Thread(server, "archphene-frame-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Frame bridge server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for frame bridge server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Frame bridge server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-frame-0");
        out.append(runNamedWithEnv("Linux payload sends RGBA frame to Android UI bridge", new String[] {frameClient.getAbsolutePath()}, env));
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining frame bridge server: ").append(e).append("\n");
        }

        if (server.bitmap != null) {
            framePreview.setImageBitmap(server.bitmap);
            framePreview.setMinimumHeight(Math.max(240, server.height));
        }
        out.append("Frame bridge server accepted: ").append(server.accepted).append("\n");
        out.append("Frame bridge server header: ").append(server.header).append("\n");
        out.append("Frame bridge dimensions: ").append(server.width).append("x").append(server.height).append("\n");
        out.append("Frame bridge bytes: ").append(server.bytesRead).append("\n");
        out.append("Frame bridge bitmap ready: ").append(server.bitmap != null).append("\n");
        out.append("Frame bridge error: ").append(server.error).append("\n");
        return out.toString();
    }

    private String runShmFrameBridge(File shmFrameClient, ImageView framePreview) {
        StringBuilder out = new StringBuilder();
        out.append("Linux wl_shm-style memfd frame bridge proof\n\n");
        appendFileState(out, "wl_shm-style frame client payload", shmFrameClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-shm-frame-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale shm frame socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        ShmFrameBridgeServer server = new ShmFrameBridgeServer(socket);
        Thread thread = new Thread(server, "archphene-shm-frame-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Shm frame bridge server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for shm frame bridge server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Shm frame bridge server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-shm-frame-0");
        out.append(runNamedWithEnv("Linux payload sends memfd-backed wl_shm-style frame", new String[] {shmFrameClient.getAbsolutePath()}, env));
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining shm frame bridge server: ").append(e).append("\n");
        }

        if (server.bitmap != null) {
            framePreview.setImageBitmap(server.bitmap);
            framePreview.setMinimumHeight(Math.max(240, server.height));
        }
        out.append("Shm frame bridge server accepted: ").append(server.accepted).append("\n");
        out.append("Shm frame bridge header: ").append(server.header).append("\n");
        out.append("Shm frame bridge fd count: ").append(server.fdCount).append("\n");
        out.append("Shm frame bridge dimensions: ").append(server.width).append("x").append(server.height).append(" stride=").append(server.stride).append("\n");
        out.append("Shm frame bridge bytes: ").append(server.bytesRead).append("\n");
        out.append("Shm frame bridge bitmap ready: ").append(server.bitmap != null).append("\n");
        out.append("Shm frame bridge error: ").append(server.error).append("\n");
        return out.toString();
    }

    private String runRawWaylandShmBridge(File waylandShmClient, ImageView framePreview) {
        StringBuilder out = new StringBuilder();
        out.append("Raw Wayland wl_shm compositor bridge proof\n\n");
        appendFileState(out, "Raw Wayland wl_shm client payload", waylandShmClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-wayland-shm-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale raw Wayland socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        RawWaylandShmServer server = new RawWaylandShmServer(socket);
        Thread thread = new Thread(server, "archphene-raw-wayland-shm-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Raw Wayland server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for raw Wayland server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Raw Wayland server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-wayland-shm-0");
        out.append(runNamedWithEnv("Linux payload sends raw Wayland wl_shm commit", new String[] {waylandShmClient.getAbsolutePath()}, env));
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining raw Wayland server: ").append(e).append("\n");
        }

        if (server.bitmap != null) {
            framePreview.setImageBitmap(server.bitmap);
            framePreview.setMinimumHeight(Math.max(240, server.height));
        }
        out.append("Raw Wayland server accepted: ").append(server.accepted).append("\n");
        out.append("Raw Wayland parsed messages: ").append(server.messageCount).append("\n");
        out.append("Raw Wayland fd count: ").append(server.fdCount).append("\n");
        out.append("Raw Wayland dimensions: ").append(server.width).append("x").append(server.height).append(" stride=").append(server.stride).append("\n");
        out.append("Raw Wayland bytes: ").append(server.bytesRead).append("\n");
        out.append("Raw Wayland committed: ").append(server.committed).append("\n");
        out.append("Raw Wayland bitmap ready: ").append(server.bitmap != null).append("\n");
        out.append("Raw Wayland log:\n").append(server.log);
        out.append("Raw Wayland error: ").append(server.error).append("\n");
        return out.toString();
    }

    private String runEventedWaylandShmBridge(File waylandEventedClient, ImageView framePreview) {
        StringBuilder out = new StringBuilder();
        out.append("Evented Wayland registry/compositor bridge proof\n\n");
        appendFileState(out, "Evented Wayland wl_shm client payload", waylandEventedClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-wayland-evented-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale evented Wayland socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        RawWaylandShmServer server = new RawWaylandShmServer(socket, true);
        Thread thread = new Thread(server, "archphene-evented-wayland-shm-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Evented Wayland server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for evented Wayland server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Evented Wayland server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-wayland-evented-0");
        out.append(runNamedWithEnv("Linux payload performs Wayland registry roundtrip then wl_shm commit", new String[] {waylandEventedClient.getAbsolutePath()}, env));
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining evented Wayland server: ").append(e).append("\n");
        }

        if (server.bitmap != null) {
            framePreview.setImageBitmap(server.bitmap);
            framePreview.setMinimumHeight(Math.max(240, server.height));
        }
        out.append("Evented Wayland server accepted: ").append(server.accepted).append("\n");
        out.append("Evented Wayland parsed messages: ").append(server.messageCount).append("\n");
        out.append("Evented Wayland registry globals: ").append(server.registryGlobalCount).append("\n");
        out.append("Evented Wayland callback done sent: ").append(server.callbackDoneSent).append("\n");
        out.append("Evented Wayland shm formats sent: ").append(server.shmFormatCount).append("\n");
        out.append("Evented Wayland fd count: ").append(server.fdCount).append("\n");
        out.append("Evented Wayland dimensions: ").append(server.width).append("x").append(server.height).append(" stride=").append(server.stride).append("\n");
        out.append("Evented Wayland bytes: ").append(server.bytesRead).append("\n");
        out.append("Evented Wayland committed: ").append(server.committed).append("\n");
        out.append("Evented Wayland bitmap ready: ").append(server.bitmap != null).append("\n");
        out.append("Evented Wayland log:\n").append(server.log);
        out.append("Evented Wayland error: ").append(server.error).append("\n");
        return out.toString();
    }
    private String runXdgWaylandShmBridge(File waylandXdgClient, ImageView framePreview) {
        StringBuilder out = new StringBuilder();
        out.append("XDG Wayland toplevel configure bridge proof\n\n");
        appendFileState(out, "XDG Wayland client payload", waylandXdgClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-wayland-xdg-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale xdg Wayland socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        int[] displaySize = displayPixelSize();
        RawWaylandShmServer server = new RawWaylandShmServer(socket, true, displaySize[0], displaySize[1]);
        Thread thread = new Thread(server, "archphene-xdg-wayland-shm-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("XDG Wayland server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for xdg Wayland server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("XDG Wayland server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-wayland-xdg-0");
        out.append(runNamedWithEnv("Linux payload performs xdg-shell configure/ack then wl_shm commit", new String[] {waylandXdgClient.getAbsolutePath()}, env));
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining xdg Wayland server: ").append(e).append("\n");
        }

        if (server.bitmap != null) {
            framePreview.setImageBitmap(server.bitmap);
            framePreview.setMinimumHeight(Math.max(240, server.height));
        }
        out.append("XDG Wayland server accepted: ").append(server.accepted).append("\n");
        out.append("XDG Wayland parsed messages: ").append(server.messageCount).append("\n");
        out.append("XDG Wayland registry globals: ").append(server.registryGlobalCount).append("\n");
        out.append("XDG Wayland callback done sent: ").append(server.callbackDoneSent).append("\n");
        out.append("XDG Wayland shm formats sent: ").append(server.shmFormatCount).append("\n");
        out.append("XDG Wayland configure sent: ").append(server.xdgConfigureSent).append(" serial=").append(server.xdgConfigureSerial).append(" configured=").append(server.configureWidth).append("x").append(server.configureHeight).append("\n");
        out.append("XDG Wayland configure acked: ").append(server.xdgConfigureAcked).append("\n");
        out.append("XDG Wayland frame callback done: ").append(server.frameCallbackDoneSent).append("\n");
        out.append("XDG Wayland buffer released: ").append(server.bufferReleaseSent).append("\n");
        out.append("XDG Wayland fd count: ").append(server.fdCount).append("\n");
        out.append("XDG Wayland dimensions: ").append(server.width).append("x").append(server.height).append(" stride=").append(server.stride).append("\n");
        out.append("XDG Wayland bytes: ").append(server.bytesRead).append("\n");
        out.append("XDG Wayland committed: ").append(server.committed).append("\n");
        out.append("XDG Wayland bitmap ready: ").append(server.bitmap != null).append("\n");
        out.append("XDG Wayland log:\n").append(server.log);
        out.append("XDG Wayland error: ").append(server.error).append("\n");
        return out.toString();
    }
    private String runWaylandApiClientProbe(File waylandApiClient) {
        StringBuilder out = new StringBuilder();
        out.append("Real libwayland-client API bridge probe\n\n");
        appendFileState(out, "libwayland-client API client payload", waylandApiClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-wayland-api-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale API Wayland socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        RawWaylandShmServer server = new RawWaylandShmServer(socket, true, 2);
        Thread thread = new Thread(server, "archphene-wayland-api-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Wayland API server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for Wayland API server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Wayland API server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-wayland-api-0");
        Result result;
        try {
            result = run(new String[] {waylandApiClient.getAbsolutePath()}, env);
            out.append("libwayland-client API payload performs registry roundtrip and binds globals\n\n")
                    .append(formatCommandResult(new String[] {waylandApiClient.getAbsolutePath()}, result));
        } catch (Exception e) {
            result = new Result(-127, false, "", "", e.toString());
            out.append("libwayland-client API payload failed:\n").append(e).append("\n");
        }
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining Wayland API server: ").append(e).append("\n");
        }

        out.append("Wayland API client exit code: ").append(result.exitCode).append("\n");
        out.append("Wayland API server accepted: ").append(server.accepted).append("\n");
        out.append("Wayland API server parsed messages: ").append(server.messageCount).append("\n");
        out.append("Wayland API server registry globals: ").append(server.registryGlobalCount).append("\n");
        out.append("Wayland API server sync callbacks: ").append(server.syncCallbackCount).append("\n");
        out.append("Wayland API server shm formats: ").append(server.shmFormatCount).append("\n");
        out.append("Wayland API server completed: ").append(server.committed).append("\n");
        out.append("Wayland API server log:\n").append(server.log);
        out.append("Wayland API server error: ").append(server.error).append("\n");
        return out.toString();
    }
    private String runAndroidWaylandApiClientProbe(File waylandApiClient) {
        StringBuilder out = new StringBuilder();
        out.append("Android-built Wayland-client API bridge probe\n\n");
        appendFileState(out, "Android Wayland API client payload", waylandApiClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-wayland-android-api-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale Android API Wayland socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        RawWaylandShmServer server = new RawWaylandShmServer(socket, true, 2);
        Thread thread = new Thread(server, "archphene-wayland-android-api-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Android Wayland API server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for Android Wayland API server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Android Wayland API server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-0");
        Result result;
        try {
            result = run(new String[] {waylandApiClient.getAbsolutePath()}, env);
            out.append("Android Wayland API payload performs registry roundtrip and binds globals\n\n")
                    .append(formatCommandResult(new String[] {waylandApiClient.getAbsolutePath()}, result));
        } catch (Exception e) {
            result = new Result(-127, false, "", "", e.toString());
            out.append("Android Wayland API payload failed:\n").append(e).append("\n");
        }
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining Android Wayland API server: ").append(e).append("\n");
        }

        out.append("Android Wayland API client exit code: ").append(result.exitCode).append("\n");
        out.append("Android Wayland API server accepted: ").append(server.accepted).append("\n");
        out.append("Android Wayland API server parsed messages: ").append(server.messageCount).append("\n");
        out.append("Android Wayland API server registry globals: ").append(server.registryGlobalCount).append("\n");
        out.append("Android Wayland API server sync callbacks: ").append(server.syncCallbackCount).append("\n");
        out.append("Android Wayland API server shm formats: ").append(server.shmFormatCount).append("\n");
        out.append("Android Wayland API server completed: ").append(server.committed).append("\n");
        out.append("Android Wayland API server log:\n").append(server.log);
        out.append("Android Wayland API server error: ").append(server.error).append("\n");
        return out.toString();
    }
    private String runAndroidWaylandApiRenderClientProbe(File waylandApiRenderClient, ImageView framePreview) {
        StringBuilder out = new StringBuilder();
        out.append("Android-built Wayland-client API render bridge proof\n\n");
        appendFileState(out, "Android Wayland API render client payload", waylandApiRenderClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-wayland-android-api-render-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale Android API render Wayland socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        RawWaylandShmServer server = new RawWaylandShmServer(socket, true);
        Thread thread = new Thread(server, "archphene-wayland-android-api-render-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Android Wayland API render server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for Android Wayland API render server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Android Wayland API render server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-render-0");
        Result result;
        try {
            result = run(new String[] {waylandApiRenderClient.getAbsolutePath()}, env);
            out.append("Android Wayland API payload creates wl_shm buffer and commits wl_surface\n\n")
                    .append(formatCommandResult(new String[] {waylandApiRenderClient.getAbsolutePath()}, result));
        } catch (Exception e) {
            result = new Result(-127, false, "", "", e.toString());
            out.append("Android Wayland API render payload failed:\n").append(e).append("\n");
        }
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining Android Wayland API render server: ").append(e).append("\n");
        }

        if (server.bitmap != null) {
            framePreview.setImageBitmap(server.bitmap);
            framePreview.setMinimumHeight(Math.max(240, server.height));
        }
        out.append("Android Wayland API render exit code: ").append(result.exitCode).append("\n");
        out.append("Android Wayland API render accepted: ").append(server.accepted).append("\n");
        out.append("Android Wayland API render parsed messages: ").append(server.messageCount).append("\n");
        out.append("Android Wayland API render registry globals: ").append(server.registryGlobalCount).append("\n");
        out.append("Android Wayland API render sync callbacks: ").append(server.syncCallbackCount).append("\n");
        out.append("Android Wayland API render shm formats: ").append(server.shmFormatCount).append("\n");
        out.append("Android Wayland API render fd count: ").append(server.fdCount).append("\n");
        out.append("Android Wayland API render dimensions: ").append(server.width).append("x").append(server.height).append(" stride=").append(server.stride).append("\n");
        out.append("Android Wayland API render bytes: ").append(server.bytesRead).append("\n");
        out.append("Android Wayland API render committed: ").append(server.committed).append("\n");
        out.append("Android Wayland API render bitmap ready: ").append(server.bitmap != null).append("\n");
        out.append("Android Wayland API render log:\n").append(server.log);
        out.append("Android Wayland API render error: ").append(server.error).append("\n");
        return out.toString();
    }
    private String runAndroidWaylandApiXdgClientProbe(File waylandApiXdgClient, ImageView framePreview) {
        StringBuilder out = new StringBuilder();
        out.append("Android-built Wayland-client API xdg-shell bridge proof\n\n");
        appendFileState(out, "Android Wayland API xdg client payload", waylandApiXdgClient);
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "archphene-wayland-android-api-xdg-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale Android API xdg Wayland socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        int[] displaySize = displayPixelSize();
        RawWaylandShmServer server = new RawWaylandShmServer(socket, true, displaySize[0], displaySize[1], true);
        Thread thread = new Thread(server, "archphene-wayland-android-api-xdg-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Android Wayland API xdg server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for Android Wayland API xdg server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Android Wayland API xdg server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "archphene-wayland-android-api-xdg-0");
        Result result;
        try {
            result = run(new String[] {waylandApiXdgClient.getAbsolutePath()}, env);
            out.append("Android Wayland API payload performs xdg configure/ack and commits wl_shm buffer\n\n")
                    .append(formatCommandResult(new String[] {waylandApiXdgClient.getAbsolutePath()}, result));
        } catch (Exception e) {
            result = new Result(-127, false, "", "", e.toString());
            out.append("Android Wayland API xdg payload failed:\n").append(e).append("\n");
        }
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining Android Wayland API xdg server: ").append(e).append("\n");
        }

        if (server.bitmap != null) {
            framePreview.setImageBitmap(server.bitmap);
            framePreview.setMinimumHeight(Math.max(240, server.height));
        }
        out.append("Android Wayland API xdg exit code: ").append(result.exitCode).append("\n");
        out.append("Android Wayland API xdg accepted: ").append(server.accepted).append("\n");
        out.append("Android Wayland API xdg parsed messages: ").append(server.messageCount).append("\n");
        out.append("Android Wayland API xdg registry globals: ").append(server.registryGlobalCount).append("\n");
        out.append("Android Wayland API xdg sync callbacks: ").append(server.syncCallbackCount).append("\n");
        out.append("Android Wayland API xdg shm formats: ").append(server.shmFormatCount).append("\n");
        out.append("Android Wayland API xdg output done: ").append(server.outputDoneSent).append("\n");
        out.append("Android Wayland API xdg seat capabilities sent: ").append(server.seatCapabilitiesSent).append("\n");
        out.append("Android Wayland API xdg pointer requested: ").append(server.pointerRequested).append("\n");
        out.append("Android Wayland API xdg pointer events sent: ").append(server.pointerEventsSent).append("\n");
        out.append("Android Wayland API xdg configure sent: ").append(server.xdgConfigureSent).append(" serial=").append(server.xdgConfigureSerial).append(" configured=").append(server.configureWidth).append("x").append(server.configureHeight).append("\n");
        out.append("Android Wayland API xdg configure acked: ").append(server.xdgConfigureAcked).append("\n");
        out.append("Android Wayland API xdg frame callback done: ").append(server.frameCallbackDoneSent).append("\n");
        out.append("Android Wayland API xdg buffer released: ").append(server.bufferReleaseSent).append("\n");
        out.append("Android Wayland API xdg post-commit sync done: ").append(server.postCommitSyncDone).append("\n");
        out.append("Android Wayland API xdg cleanup sync done: ").append(server.cleanupSyncDone).append("\n");
        out.append("Android Wayland API xdg destroy requests: ").append(server.destroyRequestCount).append("\n");
        out.append("Android Wayland API xdg fd count: ").append(server.fdCount).append("\n");
        out.append("Android Wayland API xdg dimensions: ").append(server.width).append("x").append(server.height).append(" stride=").append(server.stride).append("\n");
        out.append("Android Wayland API xdg bytes: ").append(server.bytesRead).append("\n");
        out.append("Android Wayland API xdg committed: ").append(server.committed).append("\n");
        out.append("Android Wayland API xdg bitmap ready: ").append(server.bitmap != null).append("\n");
        out.append("Android Wayland API xdg log:\n").append(server.log);
        out.append("Android Wayland API xdg error: ").append(server.error).append("\n");
        return out.toString();
    }
    private void startGlibcRuntimeProbe() {
        Thread thread = new Thread(() -> {
            File apkLibDir = new File(getApplicationInfo().nativeLibraryDir);
            File runtimeLibDir = new File(getFilesDir(), "linux-runtime/lib");
            File loader = new File(apkLibDir, GLIBC_LOADER);
            File libc = new File(runtimeLibDir, GLIBC_LIBC);
            File kcalc = new File(apkLibDir, KCALC_PAYLOAD);

            StringBuilder report = new StringBuilder();
            report.append("Archphene source-built glibc Android app-domain probe\n\n");
            report.append(prepareLinuxRuntime(runtimeLibDir)).append("\n\n");

            Map<String, String> env = new HashMap<>();
            env.put("LD_LIBRARY_PATH", runtimeLibDir.getAbsolutePath()
                    + ":" + apkLibDir.getAbsolutePath());
            env.put("LD_DEBUG", "files,libs,reloc");

            report.append(runNamedWithEnv(
                    "Source-built loader --list libc",
                    new String[] {
                        loader.getAbsolutePath(),
                        "--library-path",
                        env.get("LD_LIBRARY_PATH"),
                        "--list",
                        libc.getAbsolutePath()
                    },
                    env));
            env.remove("LD_DEBUG");
            env.put("LD_DEBUG", "libs");
            env.put("LD_WARN", "1");
            report.append("\n");
            report.append(runNamedWithEnv(
                    "Source-built loader --list kcalc",
                    new String[] {
                        loader.getAbsolutePath(),
                        "--library-path",
                        env.get("LD_LIBRARY_PATH"),
                        "--list",
                        kcalc.getAbsolutePath()
                    },
                    env));

            env.remove("LD_DEBUG");
            env.remove("LD_WARN");
            env.put("QT_QPA_PLATFORM", "wayland");
            env.put("QT_QPA_PLATFORM_PLUGIN_PATH", runtimeLibDir.getAbsolutePath());
            report.append("\n");
            report.append(runNamedWithEnv(
                    "Source-built loader direct KCalc startup",
                    new String[] {
                        loader.getAbsolutePath(),
                        "--library-path",
                        env.get("LD_LIBRARY_PATH"),
                        kcalc.getAbsolutePath()
                    },
                    env));

            File kcalcTrace = new File(getCacheDir(), "kcalc-startup.strace");
            if (kcalcTrace.exists()) {
                kcalcTrace.delete();
            }
            report.append("\n");
            report.append(runNamedWithEnv(
                    "Straced source-built loader direct KCalc startup",
                    new String[] {
                        "/system/bin/strace", "-f", "-s", "160", "-o",
                        kcalcTrace.getAbsolutePath(),
                        loader.getAbsolutePath(), "--library-path",
                        env.get("LD_LIBRARY_PATH"), kcalc.getAbsolutePath()
                    },
                    env));
            report.append("\nKCalc startup trace tail:\n");
            report.append(readTail(kcalcTrace, 24000));

            File reportFile = new File(getFilesDir(), "glibc-runtime-probe.txt");
            try (FileOutputStream output = new FileOutputStream(reportFile)) {
                output.write(report.toString().getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "glibc runtime probe written to " + reportFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Could not write glibc runtime probe", e);
            }
        }, "archphene-glibc-runtime-probe");
        thread.start();
    }
    private void startInteractivePointerProbe(ImageView framePreview, String baselineReport) {
        int viewportWidth = Math.max(320, framePreview.getWidth());
        int viewportHeight = Math.max(240, framePreview.getHeight());
        Log.i(TAG, "Wayland viewport " + viewportWidth + "x" + viewportHeight);
        File apkLibDir = new File(getApplicationInfo().nativeLibraryDir);
        Thread thread = new Thread(() -> {
            Log.i(TAG, "Interactive real KCalc Wayland launch is ready");
            String interactiveReport = runAndroidWaylandApiXdgInteractivePointerProbe(framePreview, viewportWidth, viewportHeight);
            String combined = baselineReport + "\n\n" + interactiveReport;
            writeReportArtifact(combined);
            logReportSummary("Interactive bridge report", combined);
        }, "archphene-wayland-interactive-pointer-probe");
        thread.start();
    }

    private String runAndroidWaylandApiXdgInteractivePointerProbe(ImageView framePreview, int viewportWidth, int viewportHeight) {
        StringBuilder out = new StringBuilder();
        out.append("Real Arch KCalc through Android Wayland bridge\n\n");
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "i0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale Android API interactive Wayland socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        RawWaylandShmServer server = new RawWaylandShmServer(socket, true, viewportWidth, viewportHeight, true);
        server.frameCommittedCallback = () -> runOnUiThread(() -> {
            Bitmap latest = server.bitmap;
            if (latest != null) {
                framePreview.setImageBitmap(latest);
            }
        });
        server.interactivePointerMode = true;
        Thread thread = new Thread(server, "archphene-wayland-real-kcalc-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Real KCalc Wayland server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for real KCalc Wayland server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Real KCalc Wayland server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        File apkLibDir = new File(getApplicationInfo().nativeLibraryDir);
        File runtimeLibDir = new File(getFilesDir(), "linux-runtime/lib");
        File loader = new File(apkLibDir, GLIBC_LOADER);
        boolean clipboardProbe = getIntent().getBooleanExtra("archphene_qt_clipboard_probe", false);
        File kcalc = new File(apkLibDir, clipboardProbe ? CLIPBOARD_PROBE_PAYLOAD : KCALC_PAYLOAD);
        out.append(prepareLinuxRuntime(runtimeLibDir)).append("\n");
        appendFileState(out, "Source-built glibc loader", loader);
        appendFileState(out, clipboardProbe ? "Qt clipboard probe" : "Real Arch KCalc", kcalc);
        String libraryPath = runtimeLibDir.getAbsolutePath() + ":" + apkLibDir.getAbsolutePath();
        env.put("ARCHPHENE_WIDTH", Integer.toString(viewportWidth));
        env.put("ARCHPHENE_HEIGHT", Integer.toString(viewportHeight));
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "i0");
        env.put("LD_LIBRARY_PATH", libraryPath);
        env.put("QT_QPA_PLATFORM", "wayland");
        env.put("QT_QPA_PLATFORM_PLUGIN_PATH", runtimeLibDir.getAbsolutePath());
        env.put("QT_PLUGIN_PATH", runtimeLibDir.getAbsolutePath());
        env.put("QT_DEBUG_PLUGINS", "1");
        putQtDensityEnv(env);
        env.put("ARCHPHENE_INTERACTIVE_POINTER", "1");
        env.put("ARCHPHENE_INTERACTIVE_KEYBOARD", "1");
        Result result;
        activeInteractiveServer = server;
        try {
            String[] command = new String[] {
                    loader.getAbsolutePath(), "--library-path", libraryPath, kcalc.getAbsolutePath()
            };
            result = run(command, env, 0);
            out.append("Real KCalc process connected to the Android-owned Wayland compositor\n\n")
                    .append(formatCommandResult(command, result));
        } catch (Exception e) {
            result = new Result(-127, false, "", "", e.toString());
            out.append("Real KCalc Wayland launch failed:\n").append(e).append("\n");
        } finally {
            if (activeInteractiveServer == server) {
                activeInteractiveServer = null;
            }
        }
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining Android Wayland API interactive pointer server: ").append(e).append("\n");
        }

        if (server.bitmap != null) {
            runOnUiThread(() -> {
                framePreview.setImageBitmap(server.bitmap);
                framePreview.setMinimumHeight(Math.max(240, server.height));
            });
        }
        boolean nativeRepaint = result.stdout.contains("pointer_repainted=1") || result.stdout.contains("real_pointer_repainted=1");
        boolean nativeKeyboardRepaint = result.stdout.contains("real_keyboard_repainted=1");
        out.append("Android Wayland API interactive pointer exit code: ").append(result.exitCode).append("\n");
        out.append("Android Wayland API interactive pointer timed out: ").append(result.timedOut).append("\n");
        out.append("Android Wayland API interactive pointer accepted: ").append(server.accepted).append("\n");
        out.append("Android Wayland API interactive pointer android events: ").append(server.androidPointerEventsSent).append("\n");
        out.append("Android Wayland API interactive pointer bridge motion events: ").append(server.pointerMotionEventsSent).append("\n");
        out.append("Android Wayland API interactive pointer bridge button events: ").append(server.pointerButtonEventsSent).append("\n");
        out.append("Android Wayland API interactive pointer native repaint: ").append(nativeRepaint).append("\n");
        out.append("Android Wayland API interactive keyboard android events: ").append(server.androidKeyEventsSent).append("\n");
        out.append("Android Wayland API interactive keyboard bridge key events: ").append(server.keyboardKeyEventsSent).append("\n");
        out.append("Android Wayland API interactive keyboard modifier events: ").append(server.keyboardModifiersSent).append("\n");
        out.append("Android Wayland API interactive keyboard repeat info sent: ").append(server.keyboardRepeatInfoSent).append(" rate=").append(server.keyboardRepeatRate).append(" delay=").append(server.keyboardRepeatDelay).append("\n");
        out.append("Android Wayland API interactive IME input connections: ").append(server.androidInputConnectionsCreated).append("\n");
        out.append("Android Wayland API interactive IME commit events: ").append(server.androidImeCommitEventsSent).append(" chars=").append(server.androidImeCommitChars).append(" last=").append(server.androidImeLastText).append("\n");
        out.append("Android Wayland API interactive IME synthesized key events: ").append(server.androidImeSynthKeyEventsSent).append("\n");
        out.append("Android Wayland API interactive keyboard last modifiers: ").append(server.keyboardLastMods).append("\n");
        out.append("Android Wayland API interactive keyboard native repaint: ").append(nativeKeyboardRepaint).append("\n");
        out.append("Android Wayland API interactive keyboard last key: ").append(server.keyboardLastKey).append("\n");
        out.append("Android Wayland API interactive pointer commits: ").append(server.commitCount).append("\n");
        out.append("Android Wayland API interactive pointer last xy: ").append(server.pointerLastX).append("x").append(server.pointerLastY).append("\n");
        out.append("Android Wayland API interactive pointer bitmap ready: ").append(server.bitmap != null).append("\n");
        out.append("Android Wayland API interactive pointer log:\n").append(server.log);
        out.append("Android Wayland API interactive pointer error: ").append(server.error).append("\n");
        if (!result.timedOut && result.exitCode != 143 && result.exitCode != -127) {
            runOnUiThread(this::finish);
        }
        return out.toString();
    }
    private String runFilesystemWaylandSocketProbe(File waylandProbe) {
        StringBuilder out = new StringBuilder();
        out.append("Wayland filesystem socket JNI probe\n\n");
        if (!JNI_LOAD_ERROR.isEmpty()) {
            out.append("JNI library failed to load: ").append(JNI_LOAD_ERROR).append("\n");
            return out.toString();
        }

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "wayland-0");
        if (socket.exists() && !socket.delete()) {
            out.append("Could not remove stale socket path: ").append(socket.getAbsolutePath()).append("\n");
            return out.toString();
        }

        FilesystemBridgeServer server = new FilesystemBridgeServer(socket);
        Thread thread = new Thread(server, "archphene-wayland-filesystem-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Filesystem bridge server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for filesystem bridge server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Filesystem bridge server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", "wayland-0");
        out.append(runNamedWithEnv("Linux payload connects to JNI-owned filesystem wayland-0 socket", new String[] {waylandProbe.getAbsolutePath()}, env));
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining filesystem bridge server: ").append(e).append("\n");
        }
        out.append("Filesystem bridge server accepted: ").append(server.accepted).append("\n");
        out.append("Filesystem bridge server received: ").append(server.received).append("\n");
        out.append("Filesystem bridge server error: ").append(server.error).append("\n");
        return out.toString();
    }

    private String runSyscallProbeMatrix(File syscallProbe) {
        StringBuilder out = new StringBuilder();
        out.append("App-spawned Linux syscall probe matrix\n\n");
        appendFileState(out, "Probe payload", syscallProbe);
        String[] probes = new String[] {
                "open",
                "access",
                "openat",
                "openat2",
                "mkdir",
                "mkdirat",
                "unlinkat",
                "renameat",
                "readlinkat",
                "faccessat",
                "faccessat2",
                "newfstatat",
                "statx",
                "getrandom",
                "memfd_create",
                "membarrier",
                "rt_sigaction",
                "rt_sigprocmask",
                "setitimer",
                "execve_null",
                "uname",
                "futex",
                "sched_setaffinity",
                "sched_getaffinity",
                "getcpu",
                "arch_prctl",
                "set_tid_address",
                "prctl",
                "set_robust_list",
                "prlimit64",
                "rseq",
                "io_uring_setup",
                "clone3",
                "pidfd_open",
                "landlock_create_ruleset",
                "futex_waitv"
        };
        for (String probe : probes) {
            out.append(runNamed("Static syscall probe " + probe, new String[] {syscallProbe.getAbsolutePath(), probe}));
            out.append("\n");
        }
        return out.toString();
    }

    private void startSyscallProbe(String syscallName) {
        new Thread(() -> {
            File syscallProbe = new File(getApplicationInfo().nativeLibraryDir, SYSCALL_PROBE);
            String report = runNamed("Static syscall probe " + syscallName,
                    new String[] {syscallProbe.getAbsolutePath(), syscallName});
            File reportFile = new File(getFilesDir(), "syscall-" + syscallName + "-probe.txt");
            try (FileOutputStream stream = new FileOutputStream(reportFile)) {
                stream.write(report.getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "access syscall probe written to " + reportFile.getAbsolutePath());
            } catch (IOException error) {
                Log.e(TAG, "Unable to write access syscall probe", error);
            }
        }, "archphene-syscall-probe-" + syscallName).start();
    }

    private String runWaylandSocketProbe(File waylandProbe) {
        StringBuilder out = new StringBuilder();
        out.append("Wayland abstract socket fallback probe\n\n");
        appendFileState(out, "Probe payload", waylandProbe);

        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        String socketName = getPackageName() + ".wayland-0." + android.os.Process.myUid();

        BridgeServer server = new BridgeServer(socketName);
        Thread thread = new Thread(server, "archphene-wayland-probe-server");
        thread.start();
        try {
            if (!server.ready.await(2, TimeUnit.SECONDS)) {
                out.append("Bridge server did not become ready before timeout\n");
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while waiting for bridge server: ").append(e).append("\n");
            return out.toString();
        }
        if (!server.listening) {
            out.append("Bridge server failed before listen: ").append(server.error).append("\n");
            return out.toString();
        }

        Map<String, String> env = new HashMap<>();
        putDisplaySizeEnv(env);
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("WAYLAND_DISPLAY", socketName);
        env.put("ARCHPHENE_WAYLAND_ABSTRACT", "1");
        out.append(runNamedWithEnv("Linux payload connects to Android-owned abstract wayland socket", new String[] {waylandProbe.getAbsolutePath()}, env));
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("Interrupted while joining bridge server: ").append(e).append("\n");
        }
        out.append("Bridge server accepted: ").append(server.accepted).append("\n");
        out.append("Bridge server received: ").append(server.received).append("\n");
        out.append("Bridge server error: ").append(server.error).append("\n");
        return out.toString();
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
            File shellPluginDir = new File(runtimeLibDir, "wayland-shell-integration");
            shellPluginDir.mkdirs();
            copyFile(shellPluginSource, new File(shellPluginDir, "libxdg-shell.so"));
            return "Extracted Linux runtime from APK: " + count + " files, " + (bytes / (1024 * 1024)) + " MiB";
        } catch (Exception e) {
            return "Linux runtime extraction failed: " + e;
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        destination.setReadable(true, false);
        destination.setExecutable(true, false);
    }

    private static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleteContents(file);
            }
            file.delete();
        }
    }
    private String readAsset(String name, int maxBytes) {
        try (InputStream input = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int remaining = maxBytes;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read == -1) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "asset read failed: " + e + "\n";
        }
    }

    private static void appendFileState(StringBuilder out, String label, File file) {
        out.append(label).append("\n");
        out.append("Path: ").append(file.getAbsolutePath()).append("\n");
        out.append("Exists: ").append(file.exists()).append("\n");
        out.append("Length: ").append(file.length()).append("\n");
        out.append("canExecute: ").append(file.canExecute()).append("\n\n");
    }

    private static String reportPatchBytes(File loader, File libc) {
        StringBuilder out = new StringBuilder();
        out.append("On-device glibc patch byte report\n\n");
        appendBytes(out, "loader set_robust_list site", loader, 0x140d8, 8);
        appendBytes(out, "loader rseq site", loader, 0x1416d, 8);
        appendBytes(out, "libc startup rt_sigprocmask site", libc, 0x27765, 8);
        appendBytes(out, "libc pthread set_robust_list site", libc, 0x974cd, 8);
        appendBytes(out, "libc pthread rseq site", libc, 0x977b3, 8);
        appendBytes(out, "libc fork set_robust_list site", libc, 0xe56dc, 8);
        appendBytes(out, "libc faccessat2 syscall number site", libc, 0x10a008, 8);
        appendBytes(out, "libc faccessat2 syscall site", libc, 0x10a027, 8);
        appendBytes(out, "libc openat2 entry site", libc, 0x10faf0, 8);
        return out.toString();
    }

    private static void appendBytes(StringBuilder out, String label, File file, long offset, int length) {
        out.append(label).append(" @ 0x").append(Long.toHexString(offset)).append("\n");
        out.append("File: ").append(file.getAbsolutePath()).append("\n");
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[length];
            raf.seek(offset);
            int read = raf.read(bytes);
            out.append("Bytes: ");
            for (int i = 0; i < read; i++) {
                if (i > 0) {
                    out.append(" ");
                }
                int value = bytes[i] & 0xff;
                if (value < 0x10) {
                    out.append("0");
                }
                out.append(Integer.toHexString(value));
            }
            out.append("\n\n");
        } catch (Exception e) {
            out.append("Read failed: ").append(e).append("\n\n");
        }
    }

    private String runNamed(String label, String[] command) {
        return runNamedWithEnv(label, command, new HashMap<String, String>());
    }

    private String runNamedWithEnv(String label, String[] command, Map<String, String> extraEnv) {
        try {
            Result result = run(command, extraEnv);
            return label + "\n\n" + formatCommandResult(command, result);
        } catch (Exception e) {
            return label + " failed:\n" + e + "\n";
        }
    }

    private Result run(String[] command, Map<String, String> extraEnv) throws Exception {
        return run(command, extraEnv, 5);
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
                () -> stdoutResult[0] = readProcessStream(process.getInputStream()),
                "archphene-process-stdout");
        Thread stderrReader = new Thread(
                () -> stderrResult[0] = readProcessStream(process.getErrorStream()),
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
        File dir = new File(getFilesDir(), "linux-runtime/fontconfig");
        dir.mkdirs();
        File config = new File(dir, "fonts.conf");
        try (InputStream input = getAssets().open("fonts.conf");
             FileOutputStream output = new FileOutputStream(config)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return config;
    }

    private static String formatCommandResult(String[] command, Result result) {
        return "Command: " + Arrays.toString(command) + "\n"
                + "Exit code: " + result.exitCode + "\n"
                + "Timed out: " + result.timedOut + "\n"
                + "Stdout:\n" + result.stdout
                + "Stderr:\n" + result.stderr
                + "Start error: " + result.startError + "\n";
    }

    private static String readProcessStream(InputStream in) {
        final int maxCapturedBytes = 64 * 1024;
        boolean truncated = false;
        try (InputStream input = in; ByteArrayOutputStream tail = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (tail.size() + read > maxCapturedBytes) {
                    tail.reset();
                    truncated = true;
                }
                tail.write(buffer, 0, read);
            }
            String prefix = truncated ? "[earlier process output truncated]\n" : "";
            return prefix + tail.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "[stream unavailable after process exit: " + e + "]\n";
        }
    }
    private static final class RawWaylandShmServer implements Runnable {
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
        volatile Runnable frameCommittedCallback;

        private final Object writeLock = new Object();
        private final Object eventLock = new Object();
        private LocalSocket connectedClient;
        private StringBuilder eventLog;
        private boolean pointerInside;
        private int pointerFocusSurfaceId;
        private int pointerGrabSurfaceId;
        private int keyboardModsDepressed;
        private int pointerSerial = 200;
        private int lastInputSerial;
        private final ArrayDeque<Integer> recentInputSerials = new ArrayDeque<>();
        private int popupSequence;
        private int activePopupGrabId;
        private boolean repeatMenuTapOnRelease;
        private boolean replayingMenuTap;
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
        private final Map<Integer, ShmPoolState> shmPools = new HashMap<>();
        private final Map<Integer, ShmBufferState> shmBuffers = new HashMap<>();
        private final Map<Integer, Integer> auxiliarySurfaceBuffers = new HashMap<>();
        private final Map<Integer, Integer> xdgSurfaceToWlSurface = new HashMap<>();
        private final Map<Integer, PositionerState> positioners = new HashMap<>();
        private final Map<Integer, PopupState> popups = new HashMap<>();
        private final Map<Integer, PopupState> popupsByXdgSurface = new HashMap<>();
        private final Map<Integer, ClipboardSourceState> clipboardSources = new HashMap<>();
        private final ArrayDeque<FileDescriptor> pendingShmFds = new ArrayDeque<>();
        private int frameCallbackId;
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
        private int pointerId;
        private int keyboardId;
        private int dataDeviceManagerId;
        private int dataDeviceId;
        private int clipboardSourceId;
        private int nextServerObjectId = 0xff000000;
        private final Map<Integer, String> androidClipboardOffers = new HashMap<>();
        private boolean androidClipboardOfferPending;
        private String lastOfferedAndroidClipboardText;
        private int registryId = 2;
        private final boolean sendServerEvents;
        private volatile int configureWidth;
        private volatile int configureHeight;
        private final float coordinateScale;
        private final int stopAfterSyncCallbacks;
        private final boolean waitForPostCommitSync;

        RawWaylandShmServer(File socket) {
            this(socket, false, 420, 260, 0);
        }

        RawWaylandShmServer(File socket, boolean sendServerEvents) {
            this(socket, sendServerEvents, 420, 260, 0);
        }

        RawWaylandShmServer(File socket, boolean sendServerEvents, int stopAfterSyncCallbacks) {
            this(socket, sendServerEvents, 420, 260, stopAfterSyncCallbacks);
        }

        RawWaylandShmServer(File socket, boolean sendServerEvents, int configureWidth, int configureHeight) {
            this(socket, sendServerEvents, configureWidth, configureHeight, 0, false);
        }

        RawWaylandShmServer(File socket, boolean sendServerEvents, int configureWidth, int configureHeight, boolean waitForPostCommitSync) {
            this(socket, sendServerEvents, configureWidth, configureHeight, 0, waitForPostCommitSync);
        }

        private RawWaylandShmServer(File socket, boolean sendServerEvents, int configureWidth, int configureHeight, int stopAfterSyncCallbacks) {
            this(socket, sendServerEvents, configureWidth, configureHeight, stopAfterSyncCallbacks, false);
        }

        private RawWaylandShmServer(File socket, boolean sendServerEvents, int configureWidth, int configureHeight, int stopAfterSyncCallbacks, boolean waitForPostCommitSync) {
            this.socket = socket;
            this.sendServerEvents = sendServerEvents;
            this.configureWidth = Math.max(320, Math.min(4096, configureWidth));
            this.configureHeight = Math.max(240, Math.min(4096, configureHeight));
            this.coordinateScale = Math.max(1f, android.content.res.Resources.getSystem().getDisplayMetrics().density);
            this.stopAfterSyncCallbacks = Math.max(0, stopAfterSyncCallbacks);
            this.waitForPostCommitSync = waitForPostCommitSync;
        }

        @Override
        public void run() {
            StringBuilder events = new StringBuilder();
            synchronized (eventLock) {
                eventLog = events;
            }
            try {
                FileDescriptor serverFd = createFilesystemWaylandServer(socket.getAbsolutePath());
                try (LocalServerSocket server = new LocalServerSocket(serverFd)) {
                    listening = true;
                    ready.countDown();
                    try (LocalSocket client = server.accept(); InputStream in = client.getInputStream()) {
                        accepted = true;
                        connectedClient = client;
                        while (!committed) {
                            byte[] header = readExact(in, 8);
                            int object = u32(header, 0);
                            int sizeOpcode = u32(header, 4);
                            int opcode = sizeOpcode & 0xffff;
                            int size = (sizeOpcode >>> 16) & 0xffff;
                            if (size < 8 || size > 4096) {
                                throw new IllegalArgumentException("invalid Wayland message size " + size);
                            }
                            byte[] payload = readExact(in, size - 8);
                            messageCount++;
                            handleMessage(client, object, opcode, payload, events);
                        }
                    }
                }
            } catch (Throwable e) {
                error = e.toString();
                ready.countDown();
            } finally {
                connectedClient = null;
                synchronized (eventLock) {
                    log = events.toString();
                    eventLog = null;
                }
                if (socket.exists()) {
                    socket.delete();
                }
            }
        }

        private void handleMessage(LocalSocket client, int object, int opcode, byte[] payload, StringBuilder events) throws Exception {
            events.append("object=").append(object).append(" opcode=").append(opcode).append(" size=").append(payload.length + 8);
            if (object == 1 && opcode == 1) {
                registryId = u32(payload, 0);
                events.append(" wl_display.get_registry new_id=").append(registryId).append("\n");
                if (sendServerEvents) {
                    sendRegistryGlobal(client, registryId, 1, "wl_shm", 1, events);
                    sendRegistryGlobal(client, registryId, 2, "wl_compositor", 1, events);
                    sendRegistryGlobal(client, registryId, 3, "xdg_wm_base", 1, events);
                    sendRegistryGlobal(client, registryId, 4, "wl_output", 2, events);
                    sendRegistryGlobal(client, registryId, 5, "wl_seat", 7, events);
                    sendRegistryGlobal(client, registryId, 6, "wl_data_device_manager", 3, events);
                }
                return;
            }
            if (object == 1 && opcode == 0) {
                int callbackId = u32(payload, 0);
                events.append(" wl_display.sync callback_id=").append(callbackId).append("\n");
                sendCallbackDone(client, callbackId, 1, events);
                syncCallbackCount++;
                if (postCommitPending && waitForPostCommitSync && !postCommitSyncDone) {
                    postCommitSyncDone = true;
                    return;
                }
                if (cleanupPending && waitForPostCommitSync) {
                    cleanupSyncDone = true;
                    committed = true;
                    return;
                }
                if (stopAfterSyncCallbacks > 0 && syncCallbackCount >= stopAfterSyncCallbacks) {
                    committed = true;
                }
                return;
            }
            if (object == registryId && opcode == 0) {
                int name = u32(payload, 0);
                String iface = stringArg(payload, 4);
                int paddedEnd = 4 + stringPaddedLength(payload, 4);
                int version = u32(payload, paddedEnd);
                int newId = u32(payload, paddedEnd + 4);
                events.append(" wl_registry.bind name=").append(name).append(" interface=").append(iface).append(" version=").append(version).append(" new_id=").append(newId).append("\n");
                if ("wl_shm".equals(iface)) {
                    shmId = newId;
                    if (sendServerEvents) {
                        sendU32Event(client, newId, 0, 0, events, "wl_shm.format ARGB8888");
                        shmFormatCount++;
                    }
                } else if ("wl_compositor".equals(iface)) {
                    compositorId = newId;
                } else if ("xdg_wm_base".equals(iface)) {
                    xdgWmBaseId = newId;
                } else if ("wl_output".equals(iface)) {
                    outputId = newId;
                    sendOutputEvents(client, newId, events);
                } else if ("wl_seat".equals(iface)) {
                    seatId = newId;
                    sendSeatEvents(client, newId, events);
                } else if ("wl_data_device_manager".equals(iface)) {
                    dataDeviceManagerId = newId;
                }
                return;
            }
            if (object == dataDeviceManagerId && opcode == 0) {
                int sourceId = u32(payload, 0);
                clipboardSources.put(sourceId, new ClipboardSourceState(sourceId));
                events.append(" wl_data_device_manager.create_data_source source_id=").append(sourceId).append("\n");
                return;
            }
            if (object == dataDeviceManagerId && opcode == 1) {
                dataDeviceId = u32(payload, 0);
                int requestedSeat = u32(payload, 4);
                events.append(" wl_data_device_manager.get_data_device device_id=").append(dataDeviceId)
                        .append(" seat=").append(requestedSeat).append("\n");
                androidClipboardOfferPending = true;
                return;
            }
            String androidOfferText = androidClipboardOffers.get(object);
            if (androidOfferText != null) {
                if (opcode == 0) {
                    events.append(" wl_data_offer.accept mime=").append(stringArg(payload, 4)).append("\n");
                } else if (opcode == 1) {
                    String mime = stringArg(payload, 0);
                    FileDescriptor[] fds = client.getAncillaryFileDescriptors();
                    if (fds == null || fds.length == 0) {
                        throw new IllegalStateException("wl_data_offer.receive missing destination fd");
                    }
                    byte[] text = androidOfferText.getBytes(StandardCharsets.UTF_8);
                    OutputStream destination = new FileOutputStream(fds[0]);
                    try {
                        destination.write(text);
                        destination.flush();
                    } finally {
                        Os.close(fds[0]);
                    }
                    events.append(" android->wayland clipboard bytes=").append(text.length)
                            .append(" mime=").append(mime).append("\n");
                } else if (opcode == 2) {
                    androidClipboardOffers.remove(object);
                    events.append(" wl_data_offer.destroy\n");
                } else {
                    events.append(" wl_data_offer opcode=").append(opcode).append("\n");
                }
                return;
            }            ClipboardSourceState clipboardSource = clipboardSources.get(object);
            if (clipboardSource != null) {
                if (opcode == 0) {
                    String mime = stringArg(payload, 0);
                    clipboardSource.mimeTypes.add(mime);
                    events.append(" wl_data_source.offer mime=").append(mime).append("\n");
                } else if (opcode == 1) {
                    clipboardSources.remove(object);
                    if (clipboardSourceId == object) {
                        clipboardSourceId = 0;
                    }
                    events.append(" wl_data_source.destroy\n");
                } else if (opcode == 2) {
                    clipboardSource.actions = u32(payload, 0);
                    events.append(" wl_data_source.set_actions actions=").append(clipboardSource.actions).append("\n");
                }
                return;
            }
            if (object == dataDeviceId) {
                if (opcode == 1) {
                    int sourceId = u32(payload, 0);
                    int serial = u32(payload, 4);
                    clipboardSourceId = clipboardSources.containsKey(sourceId) ? sourceId : 0;
                    events.append(" wl_data_device.set_selection source=").append(sourceId)
                            .append(" serial=").append(serial)
                            .append(" valid_serial=").append(isKnownInputSerial(serial)).append("\n");
                    ClipboardSourceState source = clipboardSources.get(clipboardSourceId);
                    if (source != null) {
                        requestClipboardSourceData(client, source, events);
                    }
                } else if (opcode == 2) {
                    dataDeviceId = 0;
                    events.append(" wl_data_device.release\n");
                } else {
                    events.append(" wl_data_device.unhandled\n");
                }
                return;
            }
            if (object == seatId && opcode == 0) {
                pointerId = u32(payload, 0);
                pointerRequested = true;
                events.append(" wl_seat.get_pointer pointer_id=").append(pointerId).append("\n");
                return;
            }
            if (object == seatId && opcode == 1) {
                keyboardId = u32(payload, 0);
                keyboardRequested = true;
                events.append(" wl_seat.get_keyboard keyboard_id=").append(keyboardId).append("\n");
                sendKeyboardKeymap(client, events);
                if (surfaceId != 0 && !keyboardFocusSent) {
                    sendKeyboardFocus(client, events);
                }
                return;
            }
            if (object == xdgWmBaseId && opcode == 1) {
                int positionerId = u32(payload, 0);
                positioners.put(positionerId, new PositionerState());
                events.append(" xdg_wm_base.create_positioner positioner_id=").append(positionerId).append("\n");
                return;
            }
            PositionerState positioner = positioners.get(object);
            if (positioner != null) {
                if (opcode == 0) {
                    positioners.remove(object);
                    events.append(" xdg_positioner.destroy\n");
                } else if (opcode == 1) {
                    positioner.width = u32(payload, 0);
                    positioner.height = u32(payload, 4);
                    events.append(" xdg_positioner.set_size ").append(positioner.width).append("x").append(positioner.height).append("\n");
                } else if (opcode == 2) {
                    positioner.anchorX = u32(payload, 0);
                    positioner.anchorY = u32(payload, 4);
                    positioner.anchorWidth = u32(payload, 8);
                    positioner.anchorHeight = u32(payload, 12);
                    events.append(" xdg_positioner.set_anchor_rect x=").append(positioner.anchorX).append(" y=").append(positioner.anchorY).append(" w=").append(positioner.anchorWidth).append(" h=").append(positioner.anchorHeight).append("\n");
                } else if (opcode == 3) {
                    positioner.anchor = u32(payload, 0);
                    events.append(" xdg_positioner.set_anchor value=").append(positioner.anchor).append("\n");
                } else if (opcode == 4) {
                    positioner.gravity = u32(payload, 0);
                    events.append(" xdg_positioner.set_gravity value=").append(positioner.gravity).append("\n");
                } else if (opcode == 5) {
                    positioner.constraintAdjustment = u32(payload, 0);
                    events.append(" xdg_positioner.set_constraint_adjustment value=").append(positioner.constraintAdjustment).append("\n");
                } else if (opcode == 6) {
                    positioner.offsetX = u32(payload, 0);
                    positioner.offsetY = u32(payload, 4);
                    events.append(" xdg_positioner.set_offset x=").append(positioner.offsetX).append(" y=").append(positioner.offsetY).append("\n");
                } else {
                    events.append(" xdg_positioner.unknown\n");
                }
                return;
            }
            if (object == xdgWmBaseId && opcode == 2) {
                int newXdgSurfaceId = u32(payload, 0);
                int baseSurface = u32(payload, 4);
                xdgSurfaceToWlSurface.put(newXdgSurfaceId, baseSurface);
                if (xdgSurfaceId == 0) {
                    if (surfaceId != 0 && surfaceId != baseSurface) {
                        auxiliarySurfaceBuffers.putIfAbsent(surfaceId, 0);
                    }
                    surfaceId = baseSurface;
                    auxiliarySurfaceBuffers.remove(baseSurface);
                    xdgSurfaceId = newXdgSurfaceId;
                    events.append(" promoted-to-primary");
                }
                events.append(" xdg_wm_base.get_xdg_surface xdg_surface_id=").append(newXdgSurfaceId).append(" surface=").append(baseSurface).append("\n");
                return;
            }
            if (object == xdgToplevelId && opcode == 0) {
                destroyRequestCount++;
                cleanupPending = true;
                events.append(" xdg_toplevel.destroy\n");
                xdgToplevelId = 0;
                return;
            }
            Integer roleSurface = xdgSurfaceToWlSurface.get(object);
            if (roleSurface != null && opcode == 0) {
                destroyRequestCount++;
                xdgSurfaceToWlSurface.remove(object);
                popupsByXdgSurface.remove(object);
                if (object == xdgSurfaceId) {
                    cleanupPending = true;
                    xdgSurfaceId = 0;
                }
                events.append(" xdg_surface.destroy\n");
                return;
            }
            if (object == xdgSurfaceId && opcode == 1) {
                xdgToplevelId = u32(payload, 0);
                events.append(" xdg_surface.get_toplevel xdg_toplevel_id=").append(xdgToplevelId).append("\n");
                return;
            }
            if (roleSurface != null && opcode == 2) {
                int popupId = u32(payload, 0);
                int parentXdgSurfaceId = u32(payload, 4);
                int positionerId = u32(payload, 8);
                PositionerState popupPositioner = positioners.get(positionerId);
                if (popupPositioner == null) {
                    throw new IllegalStateException("xdg_surface.get_popup with unknown positioner " + positionerId);
                }
                PopupState parentPopup = popupsByXdgSurface.get(parentXdgSurfaceId);
                int parentX = parentPopup == null ? 0 : parentPopup.x;
                int parentY = parentPopup == null ? 0 : parentPopup.y;
                PopupState popup = new PopupState(popupId, object, roleSurface, parentXdgSurfaceId,
                        popupPositioner, parentX, parentY, coordinateScale, ++popupSequence);
                popups.put(popupId, popup);
                popupsByXdgSurface.put(object, popup);
                events.append(" xdg_surface.get_popup popup_id=").append(popupId).append(" parent=").append(parentXdgSurfaceId).append(" positioner=").append(positionerId).append("\n");
                return;
            }
            if (roleSurface != null && opcode == 4) {
                int serial = u32(payload, 0);
                PopupState popup = popupsByXdgSurface.get(object);
                if (popup != null) {
                    popup.configureAcked = serial == popup.configureSerial;
                } else {
                    xdgConfigureAcked = serial == xdgConfigureSerial;
                }
                events.append(" xdg_surface.ack_configure serial=").append(serial).append("\n");
                return;
            }
            PopupState popupRole = popups.get(object);
            if (popupRole != null) {
                if (opcode == 0) {
                    if (pointerFocusSurfaceId == popupRole.wlSurfaceId) {
                        pointerFocusSurfaceId = 0;
                        pointerInside = false;
                    }
                    if (pointerGrabSurfaceId == popupRole.wlSurfaceId) {
                        pointerGrabSurfaceId = 0;
                    }
                    popupRole.visible = false;                    if (pointerFocusSurfaceId == popupRole.wlSurfaceId) {
                        pointerFocusSurfaceId = 0;
                        pointerInside = false;
                    }
                    if (pointerGrabSurfaceId == popupRole.wlSurfaceId) {
                        pointerGrabSurfaceId = 0;
                    }
                    popupRole.visible = false;                    if (pointerFocusSurfaceId == popupRole.wlSurfaceId) {
                        pointerFocusSurfaceId = 0;
                        pointerInside = false;
                    }
                    if (pointerGrabSurfaceId == popupRole.wlSurfaceId) {
                        pointerGrabSurfaceId = 0;
                    }
                    popupRole.visible = false;                    if (pointerFocusSurfaceId == popupRole.wlSurfaceId) {
                        pointerFocusSurfaceId = 0;
                        pointerInside = false;
                    }
                    if (pointerGrabSurfaceId == popupRole.wlSurfaceId) {
                        pointerGrabSurfaceId = 0;
                    }
                    popupRole.visible = false;                    if (pointerFocusSurfaceId == popupRole.wlSurfaceId) {
                        pointerFocusSurfaceId = 0;
                        pointerInside = false;
                    }
                    if (pointerGrabSurfaceId == popupRole.wlSurfaceId) {
                        pointerGrabSurfaceId = 0;
                    }
                    popupRole.visible = false;                    popups.remove(object);
                    popupsByXdgSurface.remove(popupRole.xdgSurfaceId);
                    if (activePopupGrabId == object) {
                        PopupState parent = popupsByXdgSurface.get(popupRole.parentXdgSurfaceId);
                        activePopupGrabId = parent == null ? 0 : parent.popupId;
                    }
                    restoreMainBitmap();
                    events.append(" xdg_popup.destroy\n");
                } else if (opcode == 1) {
                    int grabSeat = u32(payload, 0);
                    int grabSerial = u32(payload, 4);
                    popupRole.grabbed = grabSeat == seatId && isKnownInputSerial(grabSerial);
                    popupRole.grabSerial = grabSerial;
                    if (grabSeat == seatId) {
                        activePopupGrabId = object;
                    }
                    events.append(" xdg_popup.grab seat=").append(grabSeat).append(" serial=").append(grabSerial)
                            .append(" valid=").append(popupRole.grabbed).append("\n");
                } else {
                    events.append(" xdg_popup.unknown\n");
                }
                return;
            }            if (object == shmId && opcode == 0) {
                int poolId = u32(payload, 0);
                shmPoolId = poolId;
                poolSize = u32(payload, 4);
                FileDescriptor[] fds = client.getAncillaryFileDescriptors();
                if (fds != null) {
                    for (FileDescriptor fd : fds) {
                        pendingShmFds.addLast(fd);
                    }
                    fdCount += fds.length;
                }
                if (pendingShmFds.isEmpty()) {
                    throw new IllegalStateException("wl_shm.create_pool did not include fd");
                }
                shmFd = pendingShmFds.removeFirst();
                shmPools.put(poolId, new ShmPoolState(shmFd, poolSize));
                events.append(" wl_shm.create_pool pool_id=").append(poolId).append(" size=").append(poolSize).append(" fds=").append(fdCount).append("\n");
                return;
            }
            ShmPoolState pool = shmPools.get(object);
            if (pool != null && opcode == 1) {
                destroyRequestCount++;
                if (!interactivePointerMode) {
                    cleanupPending = true;
                }
                shmPools.remove(object);
                events.append(" wl_shm_pool.destroy\n");
                return;
            }
            if (pool != null && opcode == 2) {
                pool.size = u32(payload, 0);
                events.append(" wl_shm_pool.resize size=").append(pool.size).append("\n");
                return;
            }
            if (pool != null && opcode == 0) {
                bufferId = u32(payload, 0);
                int offset = u32(payload, 4);
                width = u32(payload, 8);
                height = u32(payload, 12);
                stride = u32(payload, 16);
                int format = u32(payload, 20);
                shmBuffers.put(bufferId, new ShmBufferState(pool, offset, width, height, stride, format));
                events.append(" wl_shm_pool.create_buffer buffer_id=").append(bufferId).append(" offset=").append(offset).append(" width=").append(width).append(" height=").append(height).append(" stride=").append(stride).append(" format=").append(format).append("\n");
                return;
            }
            if (shmBuffers.containsKey(object) && opcode == 0) {
                destroyRequestCount++;
                if (!interactivePointerMode) {
                    cleanupPending = true;
                }
                shmBuffers.remove(object);
                events.append(" wl_buffer.destroy\n");
                return;
            }
            if (object == compositorId && opcode == 0) {
                int newSurfaceId = u32(payload, 0);
                if (surfaceId == 0) {
                    surfaceId = newSurfaceId;
                } else {
                    auxiliarySurfaceBuffers.put(newSurfaceId, 0);
                }
                events.append(" wl_compositor.create_surface surface_id=").append(newSurfaceId).append("\n");
                return;
            }
            if (object == surfaceId && opcode == 0) {
                destroyRequestCount++;
                cleanupPending = true;
                events.append(" wl_surface.destroy\n");
                surfaceId = 0;
                return;
            }
            if (object == surfaceId && opcode == 1) {
                attachedBufferId = u32(payload, 0);
                mainBufferAttachPending = true;
                events.append(" wl_surface.attach buffer=").append(attachedBufferId).append(" x=").append(u32(payload, 4)).append(" y=").append(u32(payload, 8)).append("\n");
                return;
            }
            if (auxiliarySurfaceBuffers.containsKey(object) && opcode == 1) {
                int auxiliaryBufferId = u32(payload, 0);
                auxiliarySurfaceBuffers.put(object, auxiliaryBufferId);
                events.append(" auxiliary wl_surface.attach buffer=").append(auxiliaryBufferId).append("\n");
                return;
            }
            if (auxiliarySurfaceBuffers.containsKey(object) && opcode == 2) {
                events.append(" auxiliary wl_surface.damage\n");
                return;
            }
            if (auxiliarySurfaceBuffers.containsKey(object) && opcode == 0) {
                auxiliarySurfaceBuffers.remove(object);
                PopupState popup = findPopupByWlSurface(object);
                if (popup != null) {
                    popups.remove(popup.popupId);
                    popupsByXdgSurface.remove(popup.xdgSurfaceId);
                    if (activePopupGrabId == popup.popupId) {
                        PopupState parent = popupsByXdgSurface.get(popup.parentXdgSurfaceId);
                        activePopupGrabId = parent == null ? 0 : parent.popupId;
                    }
                    restoreMainBitmap();
                }
                events.append(" auxiliary wl_surface.destroy\n");
                return;
            }
            if (auxiliarySurfaceBuffers.containsKey(object) && opcode == 6) {
                int auxiliaryBufferId = auxiliarySurfaceBuffers.get(object);
                PopupState popup = findPopupByWlSurface(object);
                events.append(" auxiliary wl_surface.commit\n");
                if (popup != null && !popup.configureSent) {
                    sendPopupConfigure(client, popup, events);
                    return;
                }
                if (popup != null && auxiliaryBufferId != 0) {
                    commitPopupBuffer(popup, auxiliaryBufferId);
                }
                if (auxiliaryBufferId != 0) {
                    writeMessage(client, auxiliaryBufferId, 0, new byte[0]);
                    events.append("server->client object=").append(auxiliaryBufferId).append(" opcode=0 wl_buffer.release auxiliary\n");
                }
                return;
            }
            if (object == surfaceId && opcode == 3) {
                frameCallbackId = u32(payload, 0);
                events.append(" wl_surface.frame callback_id=").append(frameCallbackId).append("\n");
                return;
            }
            if (object == surfaceId && (opcode == 2 || opcode == 9)) {
                int damageX = u32(payload, 0);
                int damageY = u32(payload, 4);
                int damageWidth = u32(payload, 8);
                int damageHeight = u32(payload, 12);
                addMainDamage(damageX, damageY, damageWidth, damageHeight);
                events.append(opcode == 2 ? " wl_surface.damage" : " wl_surface.damage_buffer")
                        .append(" x=").append(damageX).append(" y=").append(damageY)
                        .append(" w=").append(damageWidth).append(" h=").append(damageHeight).append("\n");
                return;
            }
            if (object == xdgWmBaseId && opcode == 0) {
                destroyRequestCount++;
                cleanupPending = true;
                events.append(" xdg_wm_base.destroy\n");
                xdgWmBaseId = 0;
                return;
            }
            if (object == surfaceId && opcode == 6) {
                events.append(" wl_surface.commit\n");
                if (sendServerEvents && xdgSurfaceId != 0 && !xdgConfigureSent) {
                    sendXdgConfigure(client, events);
                    return;
                }
                if (sendServerEvents && xdgSurfaceId != 0 && !xdgConfigureAcked) {
                    throw new IllegalStateException("xdg surface committed buffer before ack_configure");
                }
                boolean releaseAttachedBuffer = mainBufferAttachPending && attachedBufferId != 0;
                commitBuffer();
                mainBufferAttachPending = false;
                if (androidClipboardOfferPending && dataDeviceId != 0) {
                    androidClipboardOfferPending = false;
                    sendAndroidClipboardOffer(client, events);
                }
                if (frameCallbackId != 0) {
                    sendU32Event(client, frameCallbackId, 0, 2, events, "wl_callback.done frame");
                    frameCallbackDoneSent = true;
                    frameCallbackId = 0;
                }
                if (releaseAttachedBuffer) {
                    writeMessage(client, attachedBufferId, 0, new byte[0]);
                    bufferReleaseSent = true;
                    events.append("server->client object=").append(attachedBufferId).append(" opcode=0 wl_buffer.release\n");
                }
                if (pointerId != 0 && !pointerEventsSent && !interactivePointerMode) {
                    sendPointerEvents(client, events);
                }
                if (keyboardId != 0 && !keyboardFocusSent) {
                    sendKeyboardFocus(client, events);
                }
                if (waitForPostCommitSync) {
                    postCommitPending = true;
                } else {
                    committed = true;
                }
                return;
            }
            events.append(" unknown\n");
        }

        void noteAndroidInputConnectionCreated() {
            androidInputConnectionsCreated++;
            appendAsyncEvent("android->bridge input_connection created");
        }

        boolean handleAndroidImeCommitText(CharSequence text) {
            LocalSocket client = connectedClient;
            if (client == null || keyboardId == 0 || !keyboardFocusSent || text == null) {
                return false;
            }
            String value = text.toString();
            androidImeCommitEventsSent++;
            androidImeCommitChars += value.length();
            androidImeLastText = value;
            appendAsyncEvent("android->bridge ime.commitText chars=" + value.length());
            long now = android.os.SystemClock.uptimeMillis();
            boolean sentAny = false;
            try {
                for (int i = 0; i < value.length(); i++) {
                    int evdevKey = evdevKeyCodeForCharacter(value.charAt(i));
                    if (evdevKey == 0) {
                        continue;
                    }
                    keyboardLastKey = evdevKey;
                    sendKeyboardKey(client, evdevKey, now, true);
                    sendKeyboardKey(client, evdevKey, now, false);
                    androidImeSynthKeyEventsSent += 2;
                    sentAny = true;
                }
                return sentAny || value.isEmpty();
            } catch (Exception e) {
                appendAsyncEvent("android ime forwarding failed: " + e);
                error = e.toString();
                return false;
            }
        }

        boolean handleAndroidImeDelete() {
            LocalSocket client = connectedClient;
            if (client == null || keyboardId == 0 || !keyboardFocusSent) {
                return false;
            }
            long now = android.os.SystemClock.uptimeMillis();
            try {
                keyboardLastKey = 14;
                sendKeyboardKey(client, 14, now, true);
                sendKeyboardKey(client, 14, now, false);
                androidImeSynthKeyEventsSent += 2;
                appendAsyncEvent("android->bridge ime.delete");
                return true;
            } catch (Exception e) {
                appendAsyncEvent("android ime delete failed: " + e);
                error = e.toString();
                return false;
            }
        }

        private static int evdevKeyCodeForCharacter(char value) {
            if (value >= 'a' && value <= 'z') {
                return evdevKeyCode(KeyEvent.KEYCODE_A + (value - 'a'));
            }
            if (value >= 'A' && value <= 'Z') {
                return evdevKeyCode(KeyEvent.KEYCODE_A + (value - 'A'));
            }
            if (value >= '1' && value <= '9') {
                return evdevKeyCode(KeyEvent.KEYCODE_1 + (value - '1'));
            }
            if (value == '0') return evdevKeyCode(KeyEvent.KEYCODE_0);
            if (value == ' ') return evdevKeyCode(KeyEvent.KEYCODE_SPACE);
            if (value == '\n') return evdevKeyCode(KeyEvent.KEYCODE_ENTER);
            return 0;
        }
        boolean handleAndroidKeyEvent(int action, int androidKeyCode, long eventTime) {
            LocalSocket client = connectedClient;
            if (client == null || keyboardId == 0 || !keyboardFocusSent) {
                return false;
            }
            int evdevKey = evdevKeyCode(androidKeyCode);
            if (evdevKey == 0) {
                return false;
            }
            androidKeyEventsSent++;
            keyboardLastKey = evdevKey;
            int modifierMask = modifierMaskForAndroidKey(androidKeyCode);
            try {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (modifierMask != 0) {
                        keyboardModsDepressed |= modifierMask;
                    }
                    sendKeyboardKey(client, evdevKey, eventTime, true);
                    if (modifierMask != 0) {
                        sendKeyboardModifiers(client);
                    }
                    return true;
                }
                if (action == KeyEvent.ACTION_UP) {
                    sendKeyboardKey(client, evdevKey, eventTime, false);
                    if (modifierMask != 0) {
                        keyboardModsDepressed &= ~modifierMask;
                        sendKeyboardModifiers(client);
                    }
                    return true;
                }
            } catch (Exception e) {
                appendAsyncEvent("android key forwarding failed: " + e);
                error = e.toString();
            }
            return false;
        }

        boolean hasVisiblePopups() {
            for (PopupState popup : popups.values()) {
                if (popup.visible) {
                    return true;
                }
            }
            return false;
        }

        boolean sendPopupEscape() {
            if (!hasVisiblePopups()) {
                return false;
            }
            long time = android.os.SystemClock.uptimeMillis();
            return handleAndroidKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, time)
                    && handleAndroidKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, time);
        }

        private static int modifierMaskForAndroidKey(int androidKeyCode) {
            if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT || androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) return 1;
            if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT || androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) return 4;
            if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT || androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) return 8;
            return 0;
        }

        private static int evdevKeyCode(int androidKeyCode) {
            if (androidKeyCode >= KeyEvent.KEYCODE_A && androidKeyCode <= KeyEvent.KEYCODE_Z) {
                int[] letters = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
                return letters[androidKeyCode - KeyEvent.KEYCODE_A];
            }
            if (androidKeyCode >= KeyEvent.KEYCODE_1 && androidKeyCode <= KeyEvent.KEYCODE_9) {
                return androidKeyCode - KeyEvent.KEYCODE_1 + 2;
            }
            if (androidKeyCode == KeyEvent.KEYCODE_0) return 11;
            if (androidKeyCode == KeyEvent.KEYCODE_ENTER) return 28;
            if (androidKeyCode == KeyEvent.KEYCODE_SPACE) return 57;
            if (androidKeyCode == KeyEvent.KEYCODE_DEL) return 14;
            if (androidKeyCode == KeyEvent.KEYCODE_TAB) return 15;
            if (androidKeyCode == KeyEvent.KEYCODE_DPAD_UP) return 103;
            if (androidKeyCode == KeyEvent.KEYCODE_DPAD_LEFT) return 105;
            if (androidKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return 106;
            if (androidKeyCode == KeyEvent.KEYCODE_DPAD_DOWN) return 108;
            if (androidKeyCode == KeyEvent.KEYCODE_MOVE_HOME) return 102;
            if (androidKeyCode == KeyEvent.KEYCODE_MOVE_END) return 107;
            if (androidKeyCode == KeyEvent.KEYCODE_PAGE_UP) return 104;
            if (androidKeyCode == KeyEvent.KEYCODE_PAGE_DOWN) return 109;
            if (androidKeyCode == KeyEvent.KEYCODE_INSERT) return 110;
            if (androidKeyCode == KeyEvent.KEYCODE_FORWARD_DEL) return 111;
            if (androidKeyCode >= KeyEvent.KEYCODE_F1 && androidKeyCode <= KeyEvent.KEYCODE_F10) {
                return 59 + androidKeyCode - KeyEvent.KEYCODE_F1;
            }
            if (androidKeyCode == KeyEvent.KEYCODE_F11) return 87;
            if (androidKeyCode == KeyEvent.KEYCODE_F12) return 88;
            if (androidKeyCode == KeyEvent.KEYCODE_ESCAPE) return 1;
            if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT) return 42;
            if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) return 54;
            if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT) return 29;
            if (androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) return 97;
            if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT) return 56;
            if (androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) return 100;
            return 0;
        }

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

        private void sendKeyboardRepeatInfo(LocalSocket client, StringBuilder events) throws Exception {
            keyboardRepeatRate = 25;
            keyboardRepeatDelay = 400;
            byte[] repeat = new byte[8];
            putU32(repeat, 0, keyboardRepeatRate);
            putU32(repeat, 4, keyboardRepeatDelay);
            writeMessage(client, keyboardId, 5, repeat);
            keyboardRepeatInfoSent = true;
            events.append("server->client object=").append(keyboardId).append(" opcode=5 wl_keyboard.repeat_info rate=").append(keyboardRepeatRate).append(" delay=").append(keyboardRepeatDelay).append("\n");
        }
        private void sendKeyboardFocus(LocalSocket client, StringBuilder events) throws Exception {
            byte[] enter = new byte[12];
            putU32(enter, 0, pointerSerial++);
            putU32(enter, 4, surfaceId);
            putU32(enter, 8, 0);
            writeMessage(client, keyboardId, 1, enter);
            byte[] modifiers = new byte[20];
            putU32(modifiers, 0, pointerSerial++);
            writeMessage(client, keyboardId, 4, modifiers);
            keyboardFocusSent = true;
            events.append("server->client object=").append(keyboardId).append(" opcode=1 wl_keyboard.enter surface=").append(surfaceId).append("\n");
            events.append("server->client object=").append(keyboardId).append(" opcode=4 wl_keyboard.modifiers zero\n");
        }

        private void sendKeyboardModifiers(LocalSocket client) throws Exception {
            byte[] modifiers = new byte[20];
            int serial = pointerSerial++;
            putU32(modifiers, 0, serial);
            rememberInputSerial(serial);
            putU32(modifiers, 4, keyboardModsDepressed);
            writeMessage(client, keyboardId, 4, modifiers);
            keyboardModifiersSent++;
            keyboardLastMods = keyboardModsDepressed;
            appendAsyncEvent("android->wayland object=" + keyboardId + " opcode=4 wl_keyboard.modifiers depressed=" + keyboardModsDepressed);
        }
        private void sendKeyboardKey(LocalSocket client, int evdevKey, long eventTime, boolean pressed) throws Exception {
            byte[] key = new byte[16];
            int serial = pointerSerial++;
            putU32(key, 0, serial);
            putU32(key, 4, (int) eventTime);
            putU32(key, 8, evdevKey);
            putU32(key, 12, pressed ? 1 : 0);
            writeMessage(client, keyboardId, 3, key);
            keyboardKeyEventsSent++;
            rememberInputSerial(serial);
            appendAsyncEvent("android->wayland object=" + keyboardId + " opcode=3 wl_keyboard.key key=" + evdevKey + " " + (pressed ? "pressed" : "released"));
        }
        synchronized void requestResize(int width, int height) {
            int nextWidth = Math.max(320, Math.min(4096, width));
            int nextHeight = Math.max(240, Math.min(4096, height));
            if (nextWidth == configureWidth && nextHeight == configureHeight) {
                return;
            }
            configureWidth = nextWidth;
            configureHeight = nextHeight;
            LocalSocket client = connectedClient;
            if (client == null || xdgSurfaceId == 0 || xdgToplevelId == 0) {
                return;
            }
            try {
                xdgConfigureAcked = false;
                StringBuilder resize = new StringBuilder();
                sendXdgConfigure(client, resize);
                appendAsyncEvent(resize.toString().trim());
            } catch (Exception e) {
                appendAsyncEvent("android resize forwarding failed: " + e);
                error = e.toString();
            }
        }
        boolean handleAndroidScrollEvent(float x, float y, float verticalScroll, long eventTime) {
            if (verticalScroll == 0 || !handleAndroidMotionEvent(MotionEvent.ACTION_MOVE, x, y, eventTime)) {
                return false;
            }
            LocalSocket client = connectedClient;
            if (client == null || pointerId == 0) {
                return false;
            }
            try {
                byte[] source = new byte[4];
                putU32(source, 0, 0); // WL_POINTER_AXIS_SOURCE_WHEEL
                writeMessage(client, pointerId, 6, source);
                byte[] discrete = new byte[8];
                putU32(discrete, 0, 0); // WL_POINTER_AXIS_VERTICAL_SCROLL
                putU32(discrete, 4, verticalScroll > 0 ? -1 : 1);
                writeMessage(client, pointerId, 8, discrete);
                byte[] axis = new byte[12];
                putU32(axis, 0, (int) eventTime);
                putU32(axis, 4, 0);
                putU32(axis, 8, Math.round(-verticalScroll * 15f * 256f));
                writeMessage(client, pointerId, 4, axis);
                sendPointerFrame(client);
                appendAsyncEvent("android->wayland wl_pointer.axis vertical=" + verticalScroll);
                return true;
            } catch (Exception e) {
                appendAsyncEvent("android scroll forwarding failed: " + e);
                error = e.toString();
                return false;
            }
        }

        boolean handleAndroidPointerExit() {
            LocalSocket client = connectedClient;
            if (client == null || pointerId == 0 || !pointerInside || pointerGrabSurfaceId != 0) {
                return false;
            }
            try {
                sendPointerLeave(client, pointerFocusSurfaceId);
                sendPointerFrame(client);
                pointerInside = false;
                pointerFocusSurfaceId = 0;
                return true;
            } catch (Exception e) {
                appendAsyncEvent("android hover exit forwarding failed: " + e);
                error = e.toString();
                return false;
            }
        }
        boolean handleAndroidMotionEvent(int action, float x, float y, long eventTime) {
            LocalSocket client = connectedClient;
            if (client == null || pointerId == 0 || surfaceId == 0) {
                return false;
            }
            int px = Math.max(0, Math.min(configureWidth - 1, Math.round(x)));
            int py = Math.max(0, Math.min(configureHeight - 1, Math.round(y)));
            pointerLastX = px;
            pointerLastY = py;
            androidPointerEventsSent++;
            PopupState popup = findVisiblePopupAt(px, py);
            int targetSurfaceId = popup == null ? surfaceId : popup.wlSurfaceId;
            if (action == MotionEvent.ACTION_DOWN) {
                PopupState activePopup = popups.get(activePopupGrabId);
                repeatMenuTapOnRelease = !replayingMenuTap && popup == null && activePopup != null
                        && activePopup.visible && py < activePopup.y && !activePopup.containsAnchor(px, py);
                pointerGrabSurfaceId = targetSurfaceId;
            } else if (pointerGrabSurfaceId != 0) {
                targetSurfaceId = pointerGrabSurfaceId;
                popup = findPopupByWlSurface(targetSurfaceId);
            }
            int targetX = popup == null ? px : px - popup.x;
            int targetY = popup == null ? py : py - popup.y;
            try {
                if (!pointerInside || pointerFocusSurfaceId != targetSurfaceId) {
                    if (pointerInside && pointerFocusSurfaceId != 0) {
                        sendPointerLeave(client, pointerFocusSurfaceId);
                    }
                    sendPointerEnter(client, targetSurfaceId, targetX, targetY);
                    pointerInside = true;
                    pointerFocusSurfaceId = targetSurfaceId;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    sendPointerMotion(client, targetX, targetY, eventTime);
                    sendPointerButton(client, eventTime, true);
                    sendPointerFrame(client);
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    sendPointerMotion(client, targetX, targetY, eventTime);
                    sendPointerFrame(client);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    sendPointerMotion(client, targetX, targetY, eventTime);
                    sendPointerButton(client, eventTime, false);
                    sendPointerFrame(client);
                    pointerGrabSurfaceId = 0;
                    if (repeatMenuTapOnRelease && action == MotionEvent.ACTION_UP) {
                        repeatMenuTapOnRelease = false;
                        scheduleMenuTapReplay(px, py, eventTime);
                    } else {
                        repeatMenuTapOnRelease = false;
                    }
                    return true;
                }
            } catch (Exception e) {
                appendAsyncEvent("android pointer forwarding failed: " + e);
                error = e.toString();
            }
            return false;
        }

        private void scheduleMenuTapReplay(int x, int y, long eventTime) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                replayingMenuTap = true;
                try {
                    handleAndroidMotionEvent(MotionEvent.ACTION_DOWN, x, y, eventTime + 100);
                    handleAndroidMotionEvent(MotionEvent.ACTION_UP, x, y, eventTime + 101);
                } finally {
                    replayingMenuTap = false;
                }
            }, 100);
        }

        private PopupState findVisiblePopupAt(int x, int y) {
            PopupState match = null;
            for (PopupState popup : popups.values()) {
                if (popup.visible && x >= popup.x && y >= popup.y
                        && x < popup.x + popup.width && y < popup.y + popup.height
                        && (match == null || popup.sequence > match.sequence)) {
                    match = popup;
                }
            }
            return match;
        }

        private void sendPointerEnter(LocalSocket client, int targetSurfaceId, int x, int y) throws Exception {
            byte[] enter = new byte[16];
            putU32(enter, 0, pointerSerial++);
            putU32(enter, 4, targetSurfaceId);
            putU32(enter, 8, x * 256);
            putU32(enter, 12, y * 256);
            writeMessage(client, pointerId, 0, enter);
            pointerEventsSent = true;
            appendAsyncEvent("android->wayland object=" + pointerId + " opcode=0 wl_pointer.enter surface=" + targetSurfaceId + " x=" + x + " y=" + y);
        }

        private void sendPointerLeave(LocalSocket client, int targetSurfaceId) throws Exception {
            byte[] leave = new byte[8];
            putU32(leave, 0, pointerSerial++);
            putU32(leave, 4, targetSurfaceId);
            writeMessage(client, pointerId, 1, leave);
            appendAsyncEvent("android->wayland object=" + pointerId + " opcode=1 wl_pointer.leave surface=" + targetSurfaceId);
        }
        private void sendPointerMotion(LocalSocket client, int x, int y, long eventTime) throws Exception {
            byte[] motion = new byte[12];
            putU32(motion, 0, (int) eventTime);
            putU32(motion, 4, x * 256);
            putU32(motion, 8, y * 256);
            writeMessage(client, pointerId, 2, motion);
            pointerEventsSent = true;
            pointerMotionEventsSent++;
            appendAsyncEvent("android->wayland object=" + pointerId + " opcode=2 wl_pointer.motion x=" + x + " y=" + y);
        }

        private void sendPointerButton(LocalSocket client, long eventTime, boolean pressed) throws Exception {
            byte[] button = new byte[16];
            int serial = pointerSerial++;
            putU32(button, 0, serial);
            putU32(button, 4, (int) eventTime);
            putU32(button, 8, 0x110);
            putU32(button, 12, pressed ? 1 : 0);
            writeMessage(client, pointerId, 3, button);
            pointerEventsSent = true;
            pointerButtonEventsSent++;
            if (pressed) {
                rememberInputSerial(serial);
            }
            appendAsyncEvent("android->wayland object=" + pointerId + " opcode=3 wl_pointer.button left " + (pressed ? "pressed" : "released"));
        }

        private void sendPointerFrame(LocalSocket client) throws Exception {
            writeMessage(client, pointerId, 5, new byte[0]);
            appendAsyncEvent("android->wayland object=" + pointerId + " opcode=5 wl_pointer.frame");
        }

        private void appendAsyncEvent(String line) {
            synchronized (eventLock) {
                if (eventLog != null) {
                    eventLog.append(line).append("\n");
                }
            }
        }
        private synchronized void rememberInputSerial(int serial) {
            lastInputSerial = serial;
            recentInputSerials.addLast(serial);
            while (recentInputSerials.size() > 32) {
                recentInputSerials.removeFirst();
            }
        }

        private synchronized boolean isKnownInputSerial(int serial) {
            return recentInputSerials.contains(serial);
        }

        synchronized void publishAndroidClipboard() {
            LocalSocket client = connectedClient;
            if (client == null || dataDeviceId == 0) {
                return;
            }
            StringBuilder update = new StringBuilder();
            try {
                sendAndroidClipboardOffer(client, update);
                String message = update.toString().trim();
                if (!message.isEmpty()) {
                    appendAsyncEvent(message);
                }
            } catch (Exception e) {
                appendAsyncEvent("android clipboard offer failed: " + e);
                error = e.toString();
            }
        }
        private void sendAndroidClipboardOffer(LocalSocket client, StringBuilder events) throws Exception {
            MainActivity activity = currentActivity.get();
            if (activity == null || dataDeviceId == 0) {
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
                    || clipboard.getPrimaryClip().getItemCount() == 0) {
                return;
            }
            CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity);
            if (value == null) {
                return;
            }
            String text = value.toString();
            if (text.equals(lastOfferedAndroidClipboardText)) {
                return;
            }
            lastOfferedAndroidClipboardText = text;
            int offerId = nextServerObjectId++;
            androidClipboardOffers.put(offerId, text);
            byte[] offer = new byte[4];
            putU32(offer, 0, offerId);
            writeMessage(client, dataDeviceId, 0, offer);
            writeMessage(client, offerId, 0, stringPayload("text/plain"));
            writeMessage(client, offerId, 0, stringPayload("text/plain;charset=utf-8"));
            byte[] selection = new byte[4];
            putU32(selection, 0, offerId);
            writeMessage(client, dataDeviceId, 5, selection);
            events.append("server->client wl_data_device.data_offer id=").append(offerId).append("\n")
                    .append("server->client wl_data_offer.offer text MIME types\n")
                    .append("server->client wl_data_device.selection offer=").append(offerId).append("\n");
        }
        private void requestClipboardSourceData(LocalSocket client, ClipboardSourceState source, StringBuilder events) throws Exception {
            String mime = source.preferredTextMime();
            if (mime == null) {
                events.append("clipboard source has no supported text MIME\n");
                return;
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try {
                writeMessageWithFd(client, source.id, 1, stringPayload(mime), pipe[1].getFileDescriptor());
            } finally {
                pipe[1].close();
            }
            events.append("server->client object=").append(source.id)
                    .append(" opcode=1 wl_data_source.send mime=").append(mime).append("\n");
            ParcelFileDescriptor readSide = pipe[0];
            new Thread(() -> readClipboardPipe(readSide), "archphene-clipboard-read").start();
        }

        private void readClipboardPipe(ParcelFileDescriptor readSide) {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readSide);
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                int total = 0;
                int read;
                while ((read = in.read(chunk)) != -1) {
                    total += read;
                    if (total > 1024 * 1024) {
                        throw new IOException("clipboard payload exceeds 1 MiB");
                    }
                    out.write(chunk, 0, read);
                }
                if (total == 0) {
                    appendAsyncEvent("wayland->android clipboard empty payload ignored");
                    return;
                }
                String text = out.toString(StandardCharsets.UTF_8.name());
                MainActivity activity = currentActivity.get();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        activity.setBridgeClipboardText(text);
                    });
                }
                appendAsyncEvent("wayland->android clipboard bytes=" + total);
            } catch (Exception e) {
                appendAsyncEvent("wayland->android clipboard failed: " + e);
            }
        }

        private void sendPointerEvents(LocalSocket client, StringBuilder events) throws Exception {
            int sx = Math.max(1, configureWidth / 2) * 256;
            int sy = Math.max(1, configureHeight / 2) * 256;
            byte[] enter = new byte[16];
            putU32(enter, 0, 100);
            putU32(enter, 4, surfaceId);
            putU32(enter, 8, sx);
            putU32(enter, 12, sy);
            writeMessage(client, pointerId, 0, enter);
            byte[] motion = new byte[12];
            putU32(motion, 0, 16);
            putU32(motion, 4, sx);
            putU32(motion, 8, sy);
            writeMessage(client, pointerId, 2, motion);
            byte[] button = new byte[16];
            putU32(button, 0, 101);
            putU32(button, 4, 17);
            putU32(button, 8, 0x110);
            putU32(button, 12, 1);
            writeMessage(client, pointerId, 3, button);
            pointerEventsSent = true;
            events.append("server->client object=").append(pointerId).append(" opcode=0 wl_pointer.enter surface=").append(surfaceId).append(" x=").append(configureWidth / 2).append(" y=").append(configureHeight / 2).append("\n");
            events.append("server->client object=").append(pointerId).append(" opcode=2 wl_pointer.motion x=").append(configureWidth / 2).append(" y=").append(configureHeight / 2).append("\n");
            events.append("server->client object=").append(pointerId).append(" opcode=3 wl_pointer.button left pressed\n");
        }

        private void sendXdgConfigure(LocalSocket client, StringBuilder events) throws Exception {
            xdgConfigureSerial = Math.max(42, xdgConfigureSerial + 1);
            if (xdgToplevelId != 0) {
                byte[] toplevel = new byte[12];
                putU32(toplevel, 0, configureWidth);
                putU32(toplevel, 4, configureHeight);
                putU32(toplevel, 8, 0);
                writeMessage(client, xdgToplevelId, 0, toplevel);
                events.append("server->client object=").append(xdgToplevelId).append(" opcode=0 xdg_toplevel.configure width=").append(configureWidth).append(" height=").append(configureHeight).append(" states=0\n");
            }
            byte[] surface = new byte[4];
            putU32(surface, 0, xdgConfigureSerial);
            writeMessage(client, xdgSurfaceId, 0, surface);
            xdgConfigureSent = true;
            events.append("server->client object=").append(xdgSurfaceId).append(" opcode=0 xdg_surface.configure serial=").append(xdgConfigureSerial).append("\n");
        }
        private void sendOutputEvents(LocalSocket client, int output, StringBuilder events) throws Exception {
            byte[] make = stringPayload("Archphene");
            byte[] model = stringPayload("Android Display");
            byte[] geometry = new byte[20 + make.length + model.length + 4];
            putU32(geometry, 0, 0);
            putU32(geometry, 4, 0);
            putU32(geometry, 8, 68);
            putU32(geometry, 12, 151);
            putU32(geometry, 16, 0);
            System.arraycopy(make, 0, geometry, 20, make.length);
            System.arraycopy(model, 0, geometry, 20 + make.length, model.length);
            putU32(geometry, 20 + make.length + model.length, 0);
            writeMessage(client, output, 0, geometry);
            byte[] mode = new byte[16];
            putU32(mode, 0, 1);
            putU32(mode, 4, configureWidth);
            putU32(mode, 8, configureHeight);
            putU32(mode, 12, 60000);
            writeMessage(client, output, 1, mode);
            sendU32Event(client, output, 3, 1, events, "wl_output.scale");
            writeMessage(client, output, 2, new byte[0]);
            outputDoneSent = true;
            events.append("server->client object=").append(output).append(" opcode=0 wl_output.geometry make=Archphene model=Android Display\n");
            events.append("server->client object=").append(output).append(" opcode=1 wl_output.mode current width=").append(configureWidth).append(" height=").append(configureHeight).append(" refresh=60000\n");
            events.append("server->client object=").append(output).append(" opcode=2 wl_output.done\n");
        }

        private void sendSeatEvents(LocalSocket client, int seat, StringBuilder events) throws Exception {
            writeMessage(client, seat, 1, stringPayload("default"));
            sendU32Event(client, seat, 0, 3, events, "wl_seat.capabilities pointer keyboard");
            seatCapabilitiesSent = true;
            events.append("server->client object=").append(seat).append(" opcode=1 wl_seat.name default\n");
        }

        private void sendRegistryGlobal(LocalSocket client, int registry, int name, String iface, int version, StringBuilder events) throws Exception {
            byte[] ifaceBytes = (iface + "\0").getBytes(StandardCharsets.UTF_8);
            int padded = (ifaceBytes.length + 3) & ~3;
            byte[] payload = new byte[4 + 4 + padded + 4];
            putU32(payload, 0, name);
            putU32(payload, 4, ifaceBytes.length);
            System.arraycopy(ifaceBytes, 0, payload, 8, ifaceBytes.length);
            putU32(payload, 8 + padded, version);
            writeMessage(client, registry, 0, payload);
            registryGlobalCount++;
            events.append("server->client object=").append(registry).append(" opcode=0 wl_registry.global name=").append(name).append(" interface=").append(iface).append(" version=").append(version).append("\n");
        }

        private void sendCallbackDone(LocalSocket client, int callbackId, int serial, StringBuilder events) throws Exception {
            sendU32Event(client, callbackId, 0, serial, events, "wl_callback.done");
            callbackDoneSent = true;
        }

        private void sendU32Event(LocalSocket client, int object, int opcode, int value, StringBuilder events, String label) throws Exception {
            byte[] payload = new byte[4];
            putU32(payload, 0, value);
            writeMessage(client, object, opcode, payload);
            events.append("server->client object=").append(object).append(" opcode=").append(opcode).append(" ").append(label).append(" value=").append(value).append("\n");
        }

        private static byte[] stringPayload(String value) {
            byte[] bytes = (value + "\0").getBytes(StandardCharsets.UTF_8);
            int padded = (bytes.length + 3) & ~3;
            byte[] payload = new byte[4 + padded];
            putU32(payload, 0, bytes.length);
            System.arraycopy(bytes, 0, payload, 4, bytes.length);
            return payload;
        }

        private void writeMessageWithFd(LocalSocket client, int object, int opcode, byte[] payload, FileDescriptor fd) throws Exception {
            byte[] message = new byte[8 + payload.length];
            putU32(message, 0, object);
            putU32(message, 4, (message.length << 16) | (opcode & 0xffff));
            System.arraycopy(payload, 0, message, 8, payload.length);
            synchronized (writeLock) {
                client.setFileDescriptorsForSend(new FileDescriptor[] {fd});
                try {
                    OutputStream out = client.getOutputStream();
                    out.write(message);
                    out.flush();
                } finally {
                    client.setFileDescriptorsForSend(null);
                }
            }
        }
        private void writeMessage(LocalSocket client, int object, int opcode, byte[] payload) throws Exception {
            byte[] message = new byte[8 + payload.length];
            putU32(message, 0, object);
            putU32(message, 4, (message.length << 16) | (opcode & 0xffff));
            System.arraycopy(payload, 0, message, 8, payload.length);
            synchronized (writeLock) {
                OutputStream out = client.getOutputStream();
                out.write(message);
                out.flush();
            }
        }

        private PopupState findPopupByWlSurface(int wlSurfaceId) {
            for (PopupState popup : popups.values()) {
                if (popup.wlSurfaceId == wlSurfaceId) {
                    return popup;
                }
            }
            return null;
        }

        private void sendPopupConfigure(LocalSocket client, PopupState popup, StringBuilder events) throws Exception {
            popup.configureSerial = ++xdgConfigureSerial;
            byte[] configure = new byte[16];
            putU32(configure, 0, popup.x);
            putU32(configure, 4, popup.y);
            putU32(configure, 8, popup.width);
            putU32(configure, 12, popup.height);
            writeMessage(client, popup.popupId, 0, configure);
            byte[] surfaceConfigure = new byte[4];
            putU32(surfaceConfigure, 0, popup.configureSerial);
            writeMessage(client, popup.xdgSurfaceId, 0, surfaceConfigure);
            popup.configureSent = true;
            events.append("server->client object=").append(popup.popupId)
                    .append(" opcode=0 xdg_popup.configure x=").append(popup.x)
                    .append(" y=").append(popup.y).append(" w=").append(popup.width)
                    .append(" h=").append(popup.height).append("\n");
            events.append("server->client object=").append(popup.xdgSurfaceId)
                    .append(" opcode=0 xdg_surface.configure serial=").append(popup.configureSerial).append("\n");
        }

        private void commitPopupBuffer(PopupState popup, int popupBufferId) throws Exception {
            ShmBufferState buffer = shmBuffers.get(popupBufferId);
            if (buffer == null) {
                throw new IllegalStateException("popup commit with unknown wl_buffer " + popupBufferId);
            }
            long required = (long) buffer.offset + (long) buffer.stride * buffer.height;
            if (buffer.width <= 0 || buffer.height <= 0 || buffer.stride < buffer.width * 4 || required > buffer.pool.size) {
                throw new IllegalStateException("invalid popup buffer state before commit");
            }
            byte[] rgba = readExactFromFd(buffer.pool.fd, buffer.offset, buffer.stride * buffer.height);
            int[] argb = new int[buffer.width * buffer.height];
            for (int y = 0; y < buffer.height; y++) {
                int row = y * buffer.stride;
                for (int x = 0; x < buffer.width; x++) {
                    int p = row + x * 4;
                    int b = rgba[p] & 0xff;
                    int g = rgba[p + 1] & 0xff;
                    int r = rgba[p + 2] & 0xff;
                    int a = buffer.format == 0 ? rgba[p + 3] & 0xff : 0xff;
                    argb[y * buffer.width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            popup.bitmap = Bitmap.createBitmap(argb, buffer.width, buffer.height, Bitmap.Config.ARGB_8888);
            popup.visible = true;
            composeSurfaceTree();
        }

        private void composeSurfaceTree() {
            Bitmap base = mainBitmap;
            if (base == null) {
                return;
            }
            Bitmap composed = base.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(composed);
            ArrayList<PopupState> stack = new ArrayList<>(popups.values());
            stack.sort((left, right) -> Integer.compare(left.sequence, right.sequence));
            for (PopupState popup : stack) {
                if (popup.visible && popup.bitmap != null) {
                    canvas.drawBitmap(popup.bitmap, popup.x, popup.y, null);
                }
            }
            bitmap = composed;
            Runnable callback = frameCommittedCallback;
            if (callback != null) {
                callback.run();
            }
        }

        private void restoreMainBitmap() {
            composeSurfaceTree();
        }

        private void addMainDamage(int x, int y, int damageWidth, int damageHeight) {
            long rightLong = (long) x + Math.max(0, damageWidth);
            long bottomLong = (long) y + Math.max(0, damageHeight);
            int left = Math.max(0, x);
            int top = Math.max(0, y);
            int right = (int) Math.max(left, Math.min(Integer.MAX_VALUE, rightLong));
            int bottom = (int) Math.max(top, Math.min(Integer.MAX_VALUE, bottomLong));
            if (!mainDamagePending) {
                mainDamageLeft = left;
                mainDamageTop = top;
                mainDamageRight = right;
                mainDamageBottom = bottom;
                mainDamagePending = true;
            } else {
                mainDamageLeft = Math.min(mainDamageLeft, left);
                mainDamageTop = Math.min(mainDamageTop, top);
                mainDamageRight = Math.max(mainDamageRight, right);
                mainDamageBottom = Math.max(mainDamageBottom, bottom);
            }
        }
        private void commitBuffer() throws Exception {
            if (!mainBufferAttachPending) {
                return;
            }
            ShmBufferState buffer = shmBuffers.get(attachedBufferId);
            if (buffer == null) {
                if (attachedBufferId == 0) {
                    return;
                }
                throw new IllegalStateException("commit with unknown wl_buffer " + attachedBufferId);
            }
            if (buffer.pool.fd == null) {
                throw new IllegalStateException("commit before shm fd");
            }
            width = buffer.width;
            height = buffer.height;
            stride = buffer.stride;
            long required = (long) buffer.offset + (long) stride * height;
            if (width <= 0 || height <= 0 || stride < width * 4 || required > buffer.pool.size) {
                throw new IllegalStateException("invalid buffer state before commit");
            }
            byte[] rgba = readExactFromFd(buffer.pool.fd, buffer.offset, stride * height);
            commitCount++;
            bytesRead = rgba.length;
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                int row = y * stride;
                for (int x = 0; x < width; x++) {
                    int p = row + x * 4;
                    int b = rgba[p] & 0xff;
                    int g = rgba[p + 1] & 0xff;
                    int r = rgba[p + 2] & 0xff;
                    int a = buffer.format == 0 ? rgba[p + 3] & 0xff : 0xff;
                    argb[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
Bitmap incoming = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            if (mainBitmap != null && mainBitmap.getWidth() == width && mainBitmap.getHeight() == height
                    && mainDamagePending) {
                int left = Math.max(0, Math.min(width, mainDamageLeft));
                int top = Math.max(0, Math.min(height, mainDamageTop));
                int right = Math.max(left, Math.min(width, mainDamageRight));
                int bottom = Math.max(top, Math.min(height, mainDamageBottom));
                Bitmap retained = mainBitmap.copy(Bitmap.Config.ARGB_8888, true);
                if (right > left && bottom > top) {
                    Rect region = new Rect(left, top, right, bottom);
                    new Canvas(retained).drawBitmap(incoming, region, region, null);
                }
                mainBitmap = retained;
            } else {
                mainBitmap = incoming;
            }
            mainDamagePending = false;
            composeSurfaceTree();
        }
        private static byte[] readExact(InputStream in, int length) throws Exception {
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = in.read(bytes, offset, length - offset);
                if (read == -1) {
                    throw new java.io.EOFException("EOF after " + offset + " of " + length + " bytes");
                }
                offset += read;
            }
            return bytes;
        }

        private static byte[] readExactFromFd(FileDescriptor fd, int position, int length) throws Exception {
            byte[] bytes = new byte[length];
            FileInputStream in = new FileInputStream(fd);
            in.getChannel().position(position);
            int offset = 0;
            while (offset < length) {
                int read = in.read(bytes, offset, length - offset);
                if (read == -1) {
                    throw new java.io.EOFException("EOF after " + offset + " of " + length + " raw Wayland shm bytes");
                }
                offset += read;
            }
            return bytes;
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
        }

        private static final class PopupState {
            final int popupId;
            final int xdgSurfaceId;
            final int wlSurfaceId;
            final int parentXdgSurfaceId;
            final int x;
            final int y;
            final int width;
            final int height;
            final int sequence;
            final int anchorX;
            final int anchorY;
            final int anchorWidth;
            final int anchorHeight;
            int configureSerial;
            int grabSerial;
            boolean configureSent;
            boolean configureAcked;
            boolean grabbed;
            boolean visible;
            Bitmap bitmap;

            PopupState(int popupId, int xdgSurfaceId, int wlSurfaceId, int parentXdgSurfaceId,
                    PositionerState positioner, int parentX, int parentY, float coordinateScale, int sequence) {
                this.popupId = popupId;
                this.xdgSurfaceId = xdgSurfaceId;
                this.wlSurfaceId = wlSurfaceId;
                this.parentXdgSurfaceId = parentXdgSurfaceId;
                this.anchorX = parentX + Math.round(positioner.anchorX * coordinateScale);
                this.anchorY = parentY + Math.round(positioner.anchorY * coordinateScale);
                this.anchorWidth = Math.max(1, Math.round(positioner.anchorWidth * coordinateScale));
                this.anchorHeight = Math.max(1, Math.round(positioner.anchorHeight * coordinateScale));
                this.x = parentX + Math.round((positioner.anchorX + positioner.offsetX) * coordinateScale);
                this.y = parentY + Math.round((positioner.anchorY + positioner.anchorHeight + positioner.offsetY) * coordinateScale);
                this.width = Math.max(1, positioner.width);
                this.height = Math.max(1, positioner.height);
                this.sequence = sequence;
            }

            boolean containsAnchor(int px, int py) {
                return px >= anchorX && py >= anchorY
                        && px < anchorX + anchorWidth && py < anchorY + anchorHeight;
            }
        }

        private static final class ClipboardSourceState {
            final int id;
            final ArrayList<String> mimeTypes = new ArrayList<>();
            int actions;

            ClipboardSourceState(int id) {
                this.id = id;
            }

            String preferredTextMime() {
                if (mimeTypes.contains("text/plain")) {
                    return "text/plain";
                }
                return mimeTypes.contains("text/plain;charset=utf-8") ? "text/plain;charset=utf-8" : null;
            }
        }

        private static final class ShmPoolState {
            final FileDescriptor fd;
            int size;

            ShmPoolState(FileDescriptor fd, int size) {
                this.fd = fd;
                this.size = size;
            }
        }

        private static final class ShmBufferState {
            final ShmPoolState pool;
            final int offset;
            final int width;
            final int height;
            final int stride;
            final int format;

            ShmBufferState(ShmPoolState pool, int offset, int width, int height, int stride, int format) {
                this.pool = pool;
                this.offset = offset;
                this.width = width;
                this.height = height;
                this.stride = stride;
                this.format = format;
            }
        }

        private static int u32(byte[] bytes, int offset) {
            return (bytes[offset] & 0xff)
                    | ((bytes[offset + 1] & 0xff) << 8)
                    | ((bytes[offset + 2] & 0xff) << 16)
                    | ((bytes[offset + 3] & 0xff) << 24);
        }

        private static void putU32(byte[] bytes, int offset, int value) {
            bytes[offset] = (byte) (value & 0xff);
            bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
            bytes[offset + 2] = (byte) ((value >>> 16) & 0xff);
            bytes[offset + 3] = (byte) ((value >>> 24) & 0xff);
        }

        private static String stringArg(byte[] bytes, int offset) throws Exception {
            int length = u32(bytes, offset);
            if (length <= 0 || offset + 4 + length > bytes.length) {
                throw new IllegalArgumentException("invalid Wayland string length " + length);
            }
            return new String(bytes, offset + 4, length - 1, StandardCharsets.UTF_8);
        }

        private static int stringPaddedLength(byte[] bytes, int offset) {
            int length = u32(bytes, offset);
            return 4 + ((length + 3) & ~3);
        }
    }
    private static final class ShmFrameBridgeServer implements Runnable {
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

        ShmFrameBridgeServer(File socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                FileDescriptor serverFd = createFilesystemWaylandServer(socket.getAbsolutePath());
                try (LocalServerSocket server = new LocalServerSocket(serverFd)) {
                    listening = true;
                    ready.countDown();
                    try (LocalSocket client = server.accept(); InputStream in = client.getInputStream()) {
                        accepted = true;
                        header = readHeader(in);
                        FileDescriptor[] fds = client.getAncillaryFileDescriptors();
                        fdCount = fds == null ? 0 : fds.length;
                        if (fds == null || fds.length == 0) {
                            throw new IllegalStateException("no memfd received with shm frame");
                        }
                        String[] parts = header.trim().split(" ");
                        if (parts.length != 5 || !"ARCHPHENE_SHM_FRAME_V1".equals(parts[0])) {
                            throw new IllegalArgumentException("unexpected shm frame header: " + header);
                        }
                        width = Integer.parseInt(parts[1]);
                        height = Integer.parseInt(parts[2]);
                        stride = Integer.parseInt(parts[3]);
                        int length = Integer.parseInt(parts[4]);
                        if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || stride < width * 4 || length < stride * height) {
                            throw new IllegalArgumentException("invalid shm frame metadata: " + header);
                        }
                        byte[] rgba = readExactFromFd(fds[0], length);
                        bytesRead = rgba.length;
                        int[] argb = new int[width * height];
                        for (int y = 0; y < height; y++) {
                            int row = y * stride;
                            for (int x = 0; x < width; x++) {
                                int p = row + x * 4;
                                int r = rgba[p] & 0xff;
                                int g = rgba[p + 1] & 0xff;
                                int b = rgba[p + 2] & 0xff;
                                int a = rgba[p + 3] & 0xff;
                                argb[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                            }
                        }
                        bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
                    }
                }
            } catch (Throwable e) {
                error = e.toString();
                ready.countDown();
            } finally {
                if (socket.exists()) {
                    socket.delete();
                }
            }
        }

        private static String readHeader(InputStream in) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (out.size() < 128) {
                int b = in.read();
                if (b == -1) {
                    throw new java.io.EOFException("EOF before shm frame header");
                }
                out.write(b);
                if (b == '\n') {
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }
            throw new IllegalArgumentException("shm frame header too long");
        }

        private static byte[] readExactFromFd(FileDescriptor fd, int length) throws Exception {
            byte[] bytes = new byte[length];
            try (FileInputStream in = new FileInputStream(fd)) {
                int offset = 0;
                while (offset < length) {
                    int read = in.read(bytes, offset, length - offset);
                    if (read == -1) {
                        throw new java.io.EOFException("EOF after " + offset + " of " + length + " shm bytes");
                    }
                    offset += read;
                }
            }
            return bytes;
        }
    }

    private static final class FrameBridgeServer implements Runnable {
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

        FrameBridgeServer(File socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                FileDescriptor fd = createFilesystemWaylandServer(socket.getAbsolutePath());
                try (LocalServerSocket server = new LocalServerSocket(fd)) {
                    listening = true;
                    ready.countDown();
                    try (LocalSocket client = server.accept(); InputStream in = client.getInputStream()) {
                        accepted = true;
                        header = readFrameHeader(in);
                        String[] parts = header.trim().split(" ");
                        if (parts.length != 3 || !"ARCHPHENE_FRAME_V1".equals(parts[0])) {
                            throw new IllegalArgumentException("unexpected frame header: " + header);
                        }
                        width = Integer.parseInt(parts[1]);
                        height = Integer.parseInt(parts[2]);
                        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                            throw new IllegalArgumentException("invalid frame dimensions: " + width + "x" + height);
                        }
                        int expected = width * height * 4;
                        byte[] rgba = readExact(in, expected);
                        bytesRead = rgba.length;
                        int[] argb = new int[width * height];
                        for (int i = 0, p = 0; i < argb.length; i++, p += 4) {
                            int r = rgba[p] & 0xff;
                            int g = rgba[p + 1] & 0xff;
                            int b = rgba[p + 2] & 0xff;
                            int a = rgba[p + 3] & 0xff;
                            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
                        }
                        bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
                    }
                }
            } catch (Throwable e) {
                error = e.toString();
                ready.countDown();
            } finally {
                if (socket.exists()) {
                    socket.delete();
                }
            }
        }

        private static String readFrameHeader(InputStream in) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (out.size() < 128) {
                int b = in.read();
                if (b == -1) {
                    throw new java.io.EOFException("EOF before frame header");
                }
                out.write(b);
                if (b == '\n') {
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }
            throw new IllegalArgumentException("frame header too long");
        }

        private static byte[] readExact(InputStream in, int length) throws Exception {
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = in.read(bytes, offset, length - offset);
                if (read == -1) {
                    throw new java.io.EOFException("EOF after " + offset + " of " + length + " frame bytes");
                }
                offset += read;
            }
            return bytes;
        }
    }

    private static final class FilesystemBridgeServer implements Runnable {
        final File socket;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean listening;
        volatile boolean accepted;
        volatile String received = "";
        volatile String error = "";

        FilesystemBridgeServer(File socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                FileDescriptor fd = createFilesystemWaylandServer(socket.getAbsolutePath());
                try (LocalServerSocket server = new LocalServerSocket(fd)) {
                    listening = true;
                    ready.countDown();
                    try (LocalSocket client = server.accept();
                            InputStream in = client.getInputStream()) {
                        accepted = true;
                        byte[] buffer = new byte[128];
                        int read = in.read(buffer);
                        if (read > 0) {
                            received = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        }
                        byte[] reply = "ARCHPHENE_WAYLAND_FILESYSTEM_ACK\n".getBytes(StandardCharsets.UTF_8);
                        client.getOutputStream().write(reply);
                        client.getOutputStream().flush();
                    }
                }
            } catch (Throwable e) {
                error = e.toString();
                ready.countDown();
            } finally {
                if (socket.exists()) {
                    socket.delete();
                }
            }
        }
    }

    private static final class BridgeServer implements Runnable {
        final String socketName;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean listening;
        volatile boolean accepted;
        volatile String received = "";
        volatile String error = "";

        BridgeServer(String socketName) {
            this.socketName = socketName;
        }

        @Override
        public void run() {
            try (LocalServerSocket server = new LocalServerSocket(socketName)) {
                listening = true;
                ready.countDown();
                try (LocalSocket client = server.accept();
                        InputStream in = client.getInputStream()) {
                    accepted = true;
                    byte[] buffer = new byte[128];
                    int read = in.read(buffer);
                    if (read > 0) {
                        received = new String(buffer, 0, read, StandardCharsets.UTF_8);
                    }
                    byte[] reply = "ARCHPHENE_WAYLAND_BRIDGE_ACK\n".getBytes(StandardCharsets.UTF_8);
                    client.getOutputStream().write(reply);
                    client.getOutputStream().flush();
                }
            } catch (Throwable e) {
                error = e.toString();
                ready.countDown();
            }
        }
    }

    private static final class Result {
        final int exitCode;
        final boolean timedOut;
        final String stdout;
        final String stderr;
        final String startError;

        Result(int exitCode, boolean timedOut, String stdout, String stderr, String startError) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.stdout = stdout;
            this.stderr = stderr;
            this.startError = startError;
        }
    }
}


































