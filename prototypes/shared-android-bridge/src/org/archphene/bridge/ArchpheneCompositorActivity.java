package org.archphene.bridge;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Generic full-window host for an unmodified Linux Wayland application. */
public abstract class ArchpheneCompositorActivity extends Activity {
    private static final String META_PAYLOAD = "org.archphene.bridge.payload";
    private static final String META_TOOLKIT = "org.archphene.bridge.toolkit";
    private static final String META_TAG = "org.archphene.bridge.log_tag";
    private static final String META_DATA_ASSETS = "org.archphene.bridge.data_assets";

    private final AtomicBoolean launched = new AtomicBoolean();
    private ArchpheneInputView compositorView;
    private ArchpheneCompositorSession session;
    private AndroidDocumentSession documentSession;
    private Process linuxProcess;
    private String logTag = "ArchpheneLinuxApp";
    private String payload;
    private String toolkit;
    private String dataAssets;
    private String runtimeProbeUri;
    private String runtimeLoaderUri;
    private String[] runtimeLibraryUris;
    private String[] runtimeLibraryNames;
    private final Map<Integer, SecondaryWindow> secondaryWindows = new HashMap<>();
    private boolean independentWindows;
    private ArchpheneCompositorSession.WindowFrame primaryFrame;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        readMetadata();
        runtimeProbeUri = getIntent().getStringExtra("archphene_test_runtime_module_uri");
        runtimeLoaderUri = getIntent().getStringExtra("archphene_test_runtime_loader_uri");
        runtimeLibraryUris = getIntent().getStringArrayExtra(
                "archphene_test_runtime_library_uris");
        runtimeLibraryNames = getIntent().getStringArrayExtra(
                "archphene_test_runtime_library_names");
        String legacyLibc = getIntent().getStringExtra("archphene_test_runtime_libc_uri");
        if (runtimeLibraryUris == null && legacyLibc != null) {
            runtimeLibraryUris = new String[] {legacyLibc};
            runtimeLibraryNames = new String[] {"libc.so.6"};
        }
        independentWindows = shouldUseIndependentWindows();
        documentSession = new AndroidDocumentSession(this, logTag);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        final ArchpheneCompositorSession[] holder = new ArchpheneCompositorSession[1];
        compositorView = new ArchpheneInputView(
                this,
                new ArchpheneInputView.InputSink() {
                    @Override
                    public void preedit(String text, int begin, int end) {
                        if (holder[0] != null) holder[0].imePreedit(text, begin, end);
                    }

                    @Override
                    public void commit(String text) {
                        if (holder[0] != null) holder[0].imeCommit(text);
                    }

                    @Override
                    public void deleteSurrounding(int before, int after) {
                        if (holder[0] != null) holder[0].imeDelete(before, after);
                    }

                    @Override
                    public void editorAction(int action) {
                        if (holder[0] != null) holder[0].imeAction(action);
                    }

                    @Override
                    public boolean key(KeyEvent event) {
                        return holder[0] != null && holder[0].key(event);
                    }
                });
        compositorView.setBackgroundColor(Color.BLACK);
        compositorView.setScaleType(ImageView.ScaleType.FIT_XY);
        compositorView.setClickable(true);

        session = new ArchpheneCompositorSession(
                this,
                compositorView,
                new ArchpheneCompositorSession.Listener() {
                    @Override
                    public void onClientConnected() {
                        Log.i(logTag, "Linux Wayland client connected to shared native compositor");
                    }

                    @Override
                    public void onFrame(Bitmap frame) {
                        if (!independentWindows) compositorView.setImageBitmap(frame);
                    }

                    @Override
                    public void onWindows(List<ArchpheneCompositorSession.WindowFrame> windows) {
                        updateWindows(windows);
                    }

                    @Override
                    public void onError(String detail) {
                        Log.e(logTag, "Shared compositor failed: " + detail);
                    }
                });
        holder[0] = session;
        session.setIndependentWindows(independentWindows);
        installInputRouting();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(compositorView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        setContentView(root);
        root.requestApplyInsets();
        compositorView.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            if (session != null) session.configure(width, height, 1);
            if (width > 1 && height > 1 && launched.compareAndSet(false, true)) {
                if (runtimeProbeUri == null) launch(width, height);
                else if (runtimeLoaderUri == null) runRuntimeFdProbe(runtimeProbeUri);
                else runRuntimeGlibcProbe(runtimeProbeUri, runtimeLoaderUri,
                        runtimeLibraryUris, runtimeLibraryNames);
            }
        });
    }

    private void installInputRouting() {
        compositorView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) view.requestFocus();
            boolean mouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
                    || event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE;
            if (mouse) {
                session.pointerMotion(event.getX(), event.getY(), event.getEventTime());
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    session.pointerButton(true, event.getEventTime());
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    session.pointerButton(false, event.getEventTime());
                }
            } else {
                session.touch(event);
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
            return true;
        });
        compositorView.setOnGenericMotionListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_SCROLL) {
                session.pointerMotion(event.getX(), event.getY(), event.getEventTime());
                session.pointerAxis(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                        event.getEventTime());
                return true;
            }
            if (action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_MOVE) {
                session.pointerMotion(event.getX(), event.getY(), event.getEventTime());
                return true;
            }
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                session.pointerLeave();
                return true;
            }
            return false;
        });
    }

    private boolean shouldUseIndependentWindows() {
        return isInMultiWindowMode()
                || getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private void applyWindowMode() {
        boolean next = shouldUseIndependentWindows();
        if (next == independentWindows) return;
        independentWindows = next;
        if (session != null) session.setIndependentWindows(next);
        if (!next) {
            for (SecondaryWindow window : secondaryWindows.values()) {
                window.dismissFromRegistry();
            }
            secondaryWindows.clear();
            primaryFrame = null;
        }
    }

    private void updateWindows(List<ArchpheneCompositorSession.WindowFrame> windows) {
        if (!independentWindows) return;
        ArchpheneCompositorSession.WindowFrame nextPrimary = null;
        for (ArchpheneCompositorSession.WindowFrame frame : windows) {
            if (frame.window.primary) {
                nextPrimary = frame;
                break;
            }
        }
        if (nextPrimary == null) {
            for (ArchpheneCompositorSession.WindowFrame frame : windows) {
                if (frame.window.parentId == 0) {
                    nextPrimary = frame;
                    break;
                }
            }
        }
        if (nextPrimary != null) {
            primaryFrame = nextPrimary;
            long bitmapArea = (long) nextPrimary.bitmap.getWidth() * nextPrimary.bitmap.getHeight();
            long viewportArea = (long) compositorView.getWidth() * compositorView.getHeight();
            boolean compact = viewportArea > 0 && bitmapArea * 2 < viewportArea;
            compositorView.setScaleType(compact
                    ? ImageView.ScaleType.FIT_CENTER
                    : ImageView.ScaleType.FIT_XY);
            compositorView.setImageBitmap(nextPrimary.bitmap);
        }

        Set<Integer> mapped = new HashSet<>();
        for (ArchpheneCompositorSession.WindowFrame frame : windows) {
            if (nextPrimary != null && frame.window.id == nextPrimary.window.id) continue;
            mapped.add(frame.window.id);
            SecondaryWindow secondary = secondaryWindows.get(frame.window.id);
            if (secondary == null) {
                secondary = new SecondaryWindow(frame);
                secondaryWindows.put(frame.window.id, secondary);
            } else {
                secondary.update(frame);
            }
        }
        Set<Integer> removed = new HashSet<>(secondaryWindows.keySet());
        removed.removeAll(mapped);
        for (int id : removed) {
            SecondaryWindow window = secondaryWindows.remove(id);
            if (window != null) window.dismissFromRegistry();
        }
    }

    private ArchpheneInputView createSecondaryInputView() {
        ArchpheneInputView view = new ArchpheneInputView(
                this,
                new ArchpheneInputView.InputSink() {
                    @Override
                    public void preedit(String text, int begin, int end) {
                        if (session != null) session.imePreedit(text, begin, end);
                    }

                    @Override
                    public void commit(String text) {
                        if (session != null) session.imeCommit(text);
                    }

                    @Override
                    public void deleteSurrounding(int before, int after) {
                        if (session != null) session.imeDelete(before, after);
                    }

                    @Override
                    public void editorAction(int action) {
                        if (session != null) session.imeAction(action);
                    }

                    @Override
                    public boolean key(KeyEvent event) {
                        return session != null && session.key(event);
                    }
                });
        view.setBackgroundColor(Color.BLACK);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setClickable(true);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        return view;
    }

    private final class SecondaryWindow {
        private final int id;
        private final Dialog dialog;
        private final ArchpheneInputView view;
        private int frameWidth;
        private int frameHeight;
        private boolean registryDismiss;
        private int layoutWidth = -1;
        private int layoutHeight = -1;

        SecondaryWindow(ArchpheneCompositorSession.WindowFrame frame) {
            id = frame.window.id;
            view = createSecondaryInputView();
            dialog = new Dialog(ArchpheneCompositorActivity.this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(view);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(ignored -> {
                if (!registryDismiss && session != null) session.closeWindow(id);
            });
            dialog.setOnKeyListener((ignored, keyCode, event) ->
                    session != null && session.key(event));
            installInputRouting();
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setGravity(Gravity.CENTER);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            update(frame);
            view.requestFocus();
            if (session != null) session.activateWindow(id, view);
        }

        void update(ArchpheneCompositorSession.WindowFrame frame) {
            frameWidth = Math.max(1, frame.bitmap.getWidth());
            frameHeight = Math.max(1, frame.bitmap.getHeight());
            view.setImageBitmap(frame.bitmap);
            Window window = dialog.getWindow();
            if (window == null) return;
            float scale = 1f;
            ArchpheneCompositorSession.WindowFrame primary = primaryFrame;
            if (primary != null && compositorView.getWidth() > 0 && compositorView.getHeight() > 0) {
                scale = Math.min(
                        compositorView.getWidth() / (float) Math.max(1, primary.bitmap.getWidth()),
                        compositorView.getHeight() / (float) Math.max(1, primary.bitmap.getHeight()));
            }
            int maxWidth = Math.max(1, (int) (getResources().getDisplayMetrics().widthPixels * 0.95f));
            int maxHeight = Math.max(1, (int) (getResources().getDisplayMetrics().heightPixels * 0.90f));
            int width = Math.min(maxWidth, Math.max(1, Math.round(frameWidth * scale)));
            int height = Math.min(maxHeight, Math.max(1, Math.round(frameHeight * scale)));
            if (width != layoutWidth || height != layoutHeight) {
                layoutWidth = width;
                layoutHeight = height;
                window.setLayout(width, height);
            }
        }

        void dismissFromRegistry() {
            registryDismiss = true;
            dialog.dismiss();
        }

        private void installInputRouting() {
            view.setOnFocusChangeListener((ignored, focused) -> {
                if (focused && session != null) session.activateWindow(id, view);
            });
            view.setOnTouchListener((ignored, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) view.requestFocus();
                boolean mouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
                        || event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE;
                if (mouse) {
                    session.pointerMotion(id, view, frameWidth, frameHeight,
                            event.getX(), event.getY(), event.getEventTime());
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        session.pointerButton(true, event.getEventTime());
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        session.pointerButton(false, event.getEventTime());
                    }
                } else {
                    session.touch(id, view, frameWidth, frameHeight, event);
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                return true;
            });
            view.setOnGenericMotionListener((ignored, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_SCROLL) {
                    session.pointerMotion(id, view, frameWidth, frameHeight,
                            event.getX(), event.getY(), event.getEventTime());
                    session.pointerAxis(
                            event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                            event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                            event.getEventTime());
                    return true;
                }
                if (action == MotionEvent.ACTION_HOVER_ENTER
                        || action == MotionEvent.ACTION_HOVER_MOVE) {
                    session.pointerMotion(id, view, frameWidth, frameHeight,
                            event.getX(), event.getY(), event.getEventTime());
                    return true;
                }
                if (action == MotionEvent.ACTION_HOVER_EXIT) {
                    session.pointerLeave();
                    return true;
                }
                return false;
            });
        }
    }
    private void runRuntimeFdProbe(String uriText) {
        new Thread(() -> {
            try {
                RuntimeFdLauncher.Result result = RuntimeFdLauncher.run(
                        getContentResolver(), android.net.Uri.parse(uriText));
                Log.i("ArchpheneRuntime", "Runtime FD probe exit=" + result.exitCode
                        + " output=" + result.output.replace('\n', ' '));
            } catch (Exception error) {
                Log.e("ArchpheneRuntime", "Runtime FD probe failed", error);
            }
        }, "archphene-runtime-fd-probe").start();
    }

    private void runRuntimeGlibcProbe(String program, String loader,
            String[] libraryUris, String[] libraryNames) {
        new Thread(() -> {
            try {
                if (libraryUris == null || libraryNames == null
                        || libraryUris.length != libraryNames.length) {
                    throw new IllegalArgumentException("Runtime library intent is malformed");
                }
                android.net.Uri[] libraries = new android.net.Uri[libraryUris.length];
                for (int index = 0; index < libraryUris.length; index++) {
                    libraries[index] = android.net.Uri.parse(libraryUris[index]);
                }
                RuntimeFdLauncher.Result result = RuntimeFdLauncher.runGlibc(
                        getContentResolver(), android.net.Uri.parse(program),
                        android.net.Uri.parse(loader), libraries, libraryNames, getCacheDir());
                Log.i("ArchpheneRuntime", "Runtime glibc probe exit=" + result.exitCode
                        + " output=" + result.output.replace('\n', ' '));
            } catch (Exception error) {
                Log.e("ArchpheneRuntime", "Runtime glibc probe failed", error);
            }
        }, "archphene-runtime-glibc-probe").start();
    }
    private void launch(int width, int height) {
        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "wayland-0");
        session.start(socket, width, height);
        new Thread(() -> launchLinuxProcess(runtimeDir), "archphene-linux-launch").start();
    }

    private void launchLinuxProcess(File waylandRuntime) {
        try {
            File runtimeLib = new File(getFilesDir(), "linux-runtime/lib");
            prepareRuntime(runtimeLib);
            File apkLib = new File(getApplicationInfo().nativeLibraryDir);
            File loader = new File(apkLib, "libarchphene_ld.so");
            File executable = new File(apkLib, payload);
            if (!loader.isFile() || !executable.isFile()) {
                throw new IOException("Linux loader or payload is missing");
            }
            File home = new File(getFilesDir(), "linux-home");
            File cache = new File(home, ".cache");
            File config = new File(home, ".config");
            File tmp = new File(getCacheDir(), "linux-tmp");
            home.mkdirs();
            cache.mkdirs();
            config.mkdirs();
            tmp.mkdirs();
            File imported = documentSession.importDocument(getIntent());

            ProcessBuilder builder = new ProcessBuilder(
                    loader.getAbsolutePath(),
                    "--library-path",
                    runtimeLib.getAbsolutePath() + ":" + apkLib.getAbsolutePath(),
                    executable.getAbsolutePath());
            if (imported != null) builder.command().add(imported.getAbsolutePath());
            Map<String, String> env = builder.environment();
            env.put("LD_LIBRARY_PATH", runtimeLib.getAbsolutePath() + ":" + apkLib.getAbsolutePath());
            env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
            env.put("HOME", home.getAbsolutePath());
            env.put("XDG_CACHE_HOME", cache.getAbsolutePath());
            env.put("XDG_CONFIG_HOME", config.getAbsolutePath());
            env.put("XDG_RUNTIME_DIR", waylandRuntime.getAbsolutePath());
            env.put("WAYLAND_DISPLAY", "wayland-0");
            env.put("TMPDIR", tmp.getAbsolutePath());
            File fontconfig = copyAsset("fonts.conf", new File(getFilesDir(), "fontconfig/fonts.conf"));
            env.put("FONTCONFIG_FILE", fontconfig.getAbsolutePath());
            env.put("FONTCONFIG_PATH", fontconfig.getParentFile().getAbsolutePath());
            applyToolkitEnvironment(env, runtimeLib, config);
            builder.redirectErrorStream(true);
            linuxProcess = builder.start();
            Log.i(logTag, "Started Linux payload executable=" + executable.getName());
            try (BufferedReader output = new BufferedReader(new InputStreamReader(
                    linuxProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null) {
                    if (shouldLogLinuxLine(line)) Log.i(logTag, "linux: " + line);
                }
            }
            int exit = linuxProcess.waitFor();
            Log.i(logTag, "Linux payload exited code=" + exit);
        } catch (Throwable error) {
            Log.e(logTag, "Could not launch Linux payload", error);
        }
    }

    private void applyToolkitEnvironment(
            Map<String, String> env, File runtimeLib, File configDir) throws IOException {
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int dpi = Math.max(96, Math.min(384,
                Math.round(96f * getResources().getDisplayMetrics().density)));
        env.put("QT_FONT_DPI", Integer.toString(dpi));
        env.put("ARCHPHENE_COLOR_SCHEME", dark ? "dark" : "light");
        if ("gtk3".equals(toolkit)) {
            File root = new File(getFilesDir(), "linux-runtime/root");
            env.put("GDK_BACKEND", "wayland");
            env.put("GTK_IM_MODULE", "wayland");
            env.put("GTK_IM_MODULE_FILE", new File(
                    runtimeLib, "gtk-3.0/3.0.0/immodules.cache").getAbsolutePath());

            env.put("GTK_USE_PORTAL", "0");
            env.put("GTK_THEME", dark ? "Adwaita:dark" : "Adwaita");
            env.put("GTK_DATA_PREFIX", new File(root, "usr").getAbsolutePath());
            env.put("XDG_DATA_DIRS", new File(root, "usr/share").getAbsolutePath());
            env.put("GIO_USE_VFS", "local");
            env.put("GDK_PIXBUF_MODULE_FILE", new File(
                    runtimeLib, "gdk-pixbuf-2.0/2.10.0/loaders.cache").getAbsolutePath());
            env.put("GSETTINGS_SCHEMA_DIR", new File(
                    root, "usr/share/glib-2.0/schemas").getAbsolutePath());
            env.put("XKB_CONFIG_ROOT", new File(
                    root, "usr/share/xkeyboard-config-2").getAbsolutePath());
            env.put("MOUSEPAD_PLUGIN_PATH", new File(runtimeLib, "mousepad/plugins").getAbsolutePath());
            env.put("GCONV_PATH", new File(root, "usr/lib/gconv").getAbsolutePath());
        } else {
            env.put("QT_QPA_PLATFORM", "wayland");
            env.put("QT_QPA_PLATFORM_PLUGIN_PATH", runtimeLib.getAbsolutePath());
            env.put("QT_PLUGIN_PATH", runtimeLib.getAbsolutePath());
            env.put("QT_WAYLAND_SHELL_INTEGRATION", "xdg-shell");
            writeKdeTheme(configDir, dark);
        }
    }

    private void writeKdeTheme(File configDir, boolean dark) throws IOException {
        String foreground = dark ? "239,240,241" : "35,38,41";
        String window = dark ? "35,38,41" : "239,240,241";
        String view = dark ? "27,30,32" : "255,255,255";
        String button = dark ? "49,54,59" : "239,240,241";
        String palette = "[General]\nColorScheme=" + (dark ? "BreezeDark" : "BreezeLight")
                + "\n\n[Colors:Window]\nBackgroundNormal=" + window
                + "\nForegroundNormal=" + foreground
                + "\n\n[Colors:View]\nBackgroundNormal=" + view
                + "\nForegroundNormal=" + foreground
                + "\nDecorationFocus=61,174,233\n"
                + "\n[Colors:Button]\nBackgroundNormal=" + button
                + "\nForegroundNormal=" + foreground
                + "\nDecorationFocus=61,174,233\n"
                + "\n[Colors:Selection]\nBackgroundNormal=61,174,233"
                + "\nForegroundNormal=255,255,255\n";
        writeText(new File(configDir, "kdeglobals"), palette);
    }

    private void prepareRuntime(File runtimeLib) throws IOException {
        runtimeLib.mkdirs();
        deleteContents(runtimeLib);
        String prefix = "lib/" + Build.SUPPORTED_ABIS[0] + "/";
        try (ZipFile zip = new ZipFile(getApplicationInfo().sourceDir)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) continue;
                String leaf = name.substring(prefix.length());
                if (leaf.isEmpty() || leaf.contains("/")) continue;
                File target = new File(runtimeLib, leaf);
                try (InputStream input = zip.getInputStream(entry);
                        OutputStream output = new FileOutputStream(target)) {
                    copy(input, output);
                }
                target.setReadable(true, false);
                target.setExecutable(true, false);
            }
        }
        File shell = new File(runtimeLib, "libarchphene_xdg_shell.so");
        if (shell.isFile()) {
            File directory = new File(runtimeLib, "wayland-shell-integration");
            directory.mkdirs();
            copyFile(shell, new File(directory, "libxdg-shell.so"));
        }
        if ("gtk3".equals(toolkit)) prepareGtkRuntime(runtimeLib);
        if (dataAssets != null && !dataAssets.isBlank()) {
            File root = new File(getFilesDir(), "linux-runtime/root");
            deleteContents(root);
            root.mkdirs();
            for (String asset : dataAssets.split(",")) {
                extractZipAsset(asset.trim(), root);
            }
        }
    }

    private void prepareGtkRuntime(File runtimeLib) throws IOException {
        File plugins = new File(runtimeLib, "mousepad/plugins");
        plugins.mkdirs();
        for (String name : new String[] {
                "libmousepad-plugin-gspell.so", "libmousepad-plugin-shortcuts.so"}) {
            File source = new File(runtimeLib, name);
            if (source.isFile()) copyFile(source, new File(plugins, name));
        }
        File source = new File(runtimeLib, "libarchphene_im_wayland.so");
        File directory = new File(runtimeLib, "gtk-3.0/3.0.0/immodules");
        directory.mkdirs();
        if (source.isFile()) {
            File module = new File(directory, "im-wayland.so");
            copyFile(source, module);
            writeText(new File(directory.getParentFile(), "immodules.cache"),
                    "\"" + module.getAbsolutePath() + "\"\n"
                            + "\"wayland\" \"Wayland\" \"gtk30\" \"\" \"\"\n");
        }
        File svgSource = new File(runtimeLib, "libarchphene_pixbufloader_svg.so");
        if (svgSource.isFile()) {
            File loaderDirectory = new File(
                    runtimeLib, "gdk-pixbuf-2.0/2.10.0/loaders");
            loaderDirectory.mkdirs();
            File svgLoader = new File(loaderDirectory, "libpixbufloader_svg.so");
            copyFile(svgSource, svgLoader);
            String q = Character.toString((char) 34);
            writeText(new File(loaderDirectory.getParentFile(), "loaders.cache"),
                    "# GdkPixbuf Image Loader Modules file\n"
                            + q + svgLoader.getAbsolutePath() + q + "\n"
                            + q + "svg" + q + " 6 "
                            + q + "gdk-pixbuf" + q + " "
                            + q + "Scalable Vector Graphics" + q + " "
                            + q + "LGPL" + q + "\n"
                            + q + "image/svg+xml" + q + " "
                            + q + "image/svg" + q + " "
                            + q + "image/svg-xml" + q + " "
                            + q + "image/vnd.adobe.svg+xml" + q + " "
                            + q + "text/xml-svg" + q + " "
                            + q + "image/svg+xml-compressed" + q + " " + q + q + "\n"
                            + q + "svg" + q + " " + q + "svgz" + q + " "
                            + q + "svg.gz" + q + " " + q + q + "\n"
                            + q + " <svg" + q + " " + q + "*    " + q + " 100\n"
                            + q + " <!DOCTYPE svg" + q + " "
                            + q + "*             " + q + " 100\n");
        }
    }

    private void readMetadata() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(
                    getPackageName(), PackageManager.GET_META_DATA);
            Bundle metadata = info.metaData;
            payload = metadata.getString(META_PAYLOAD);
            toolkit = metadata.getString(META_TOOLKIT, "qt6");
            logTag = metadata.getString(META_TAG, logTag);
            dataAssets = metadata.getString(META_DATA_ASSETS, "");
            if (payload == null || payload.isBlank()) {
                throw new IllegalStateException("Linux payload metadata is missing");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not read bridge metadata", error);
        }
    }

    private File copyAsset(String name, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream input = getAssets().open(name);
                OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
        return target;
    }

    private void extractZipAsset(String name, File root) throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(getAssets().open(name))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(root, entry.getName()).getCanonicalFile();
                if (!target.getPath().startsWith(rootPath)) {
                    throw new IOException("Archive path escapes runtime root");
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream output = new FileOutputStream(target)) {
                    copy(zip, output);
                }
            }
        }
    }

    private static boolean shouldLogLinuxLine(String line) {
        if (!line.contains("ARCHPHENE")) return true;
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error") || lower.contains("fail") || lower.contains("fatal");
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source);
                OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
        target.setReadable(true, false);
        target.setExecutable(true, false);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteContents(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) deleteContents(file);
            file.delete();
        }
    }

    @Override
    public void onMultiWindowModeChanged(
            boolean inMultiWindowMode, Configuration newConfiguration) {
        super.onMultiWindowModeChanged(inMultiWindowMode, newConfiguration);
        applyWindowMode();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfiguration) {
        super.onConfigurationChanged(newConfiguration);
        applyWindowMode();
    }
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (session != null && session.key(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (session != null && session.hasPopups()) {
            session.dismissPopups();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (documentSession != null) documentSession.syncAsyncIfDirty();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        for (SecondaryWindow window : secondaryWindows.values()) {
            window.dismissFromRegistry();
        }
        secondaryWindows.clear();
        if (documentSession != null) documentSession.close();
        if (linuxProcess != null) linuxProcess.destroy();
        if (session != null) session.close();
        super.onDestroy();
    }
}
