package org.archphene.bridge;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProviderClient;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
import java.util.ArrayList;
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
    private static final Uri RUNTIME_PROVIDER = Uri.parse(
            "content://org.archpheneos.manager.runtime");
    private static final String ACTIVE_PACK_METHOD =
            "org.archphene.runtime.ACTIVE_PACK_V1";
    private static final String RELEASE_LEASE_METHOD =
            "org.archphene.runtime.RELEASE_LEASE_V1";
    private static final String APPEARANCE_METHOD =
            "org.archphene.runtime.APPEARANCE_V1";

    private final AtomicBoolean launched = new AtomicBoolean();
    private final AtomicBoolean packagedRuntimeActive = new AtomicBoolean();
    private ArchpheneInputView compositorView;
    private ArchpheneCompositorSession session;
    private AndroidDocumentSession documentSession;
    private AndroidCapabilityBroker capabilityBroker;
    private ArchpheneAccessibilityBridge accessibilityBridge;
    private Dialog documentRestartDialog;
    private Set<String> capabilities;
    private final AndroidGpuBridge gpuBridge = new AndroidGpuBridge();
    private final AndroidDesktopIntegration desktopIntegration =
            new AndroidDesktopIntegration();
    private final AndroidAudioIntegration audioIntegration =
            new AndroidAudioIntegration();
    private Process linuxProcess;
    private String logTag = "ArchpheneLinuxApp";
    private String payload;
    private String toolkit;
    private String dataAssets;
    private String runtimeProbeUri;
    private String runtimeProgramName = "program";
    private String runtimeLoaderUri;
    private String runtimeDataUri;
    private String[] runtimeLibraryUris;
    private String[] runtimeLibraryNames;
    private boolean runtimeGui;
    private boolean processTreeProbe;
    private final Binder runtimeLeaseToken = new Binder();
    private ContentProviderClient runtimeProviderClient;
    private String runtimePackId;
    private boolean runtimeExecutionStarted;
    private RuntimeFdLauncher.Execution managedRuntimeExecution;
    private boolean activityDestroyed;
    private String appearanceTheme = "system";
    private int appearanceScalePercent;
    private int appearanceFontPercent = 100;
    private boolean appearanceMaterialYou;
    private final Map<Integer, SecondaryWindow> secondaryWindows = new HashMap<>();
    private boolean independentWindows;
    private ArchpheneCompositorSession.WindowFrame primaryFrame;
    private final Object linuxDragToken = new Object();
    private boolean androidDragDropped;
    private final List<DragAndDropPermissions> retainedDragPermissions =
            new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        readMetadata();
        runBrokerPermissionProbe();
        runtimeProbeUri = getIntent().getStringExtra("archphene_test_runtime_module_uri");
        runtimeLoaderUri = getIntent().getStringExtra("archphene_test_runtime_loader_uri");
        runtimeLibraryUris = getIntent().getStringArrayExtra(
                "archphene_test_runtime_library_uris");
        runtimeLibraryNames = getIntent().getStringArrayExtra(
                "archphene_test_runtime_library_names");
        runtimeGui = getIntent().getBooleanExtra("archphene_runtime_gui", false);
        processTreeProbe = (getApplicationInfo().flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra(
                        "archphene_test_process_tree_cleanup", false);
        String legacyLibc = getIntent().getStringExtra("archphene_test_runtime_libc_uri");
        if (runtimeLibraryUris == null && legacyLibc != null) {
            runtimeLibraryUris = new String[] {legacyLibc};
            runtimeLibraryNames = new String[] {"libc.so.6"};
        }
        loadManagerAppearance();
        if (runtimeProbeUri == null && !processTreeProbe) loadManagerRuntimePack();
        independentWindows = shouldUseIndependentWindows();
        documentSession = capabilities.contains(BridgeCapabilities.DOCUMENTS)
                ? new AndroidDocumentSession(this, logTag) : null;
        accessibilityBridge = capabilities.contains(BridgeCapabilities.ACCESSIBILITY)
                ? new ArchpheneAccessibilityBridge() : null;
        capabilityBroker = new AndroidCapabilityBroker(
                this, capabilities, accessibilityBridge);
        try {
            capabilityBroker.start();
        } catch (IOException error) {
            throw new IllegalStateException("Could not start Android capability broker", error);
        }
        int systemChrome = resolvedDarkAppearance() ? Color.rgb(35, 38, 41)
                : Color.rgb(239, 240, 241);
        getWindow().setStatusBarColor(systemChrome);
        getWindow().setNavigationBarColor(systemChrome);
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
        compositorView.setBackgroundColor(systemChrome);
        compositorView.setScaleType(ImageView.ScaleType.FIT_XY);
        compositorView.setClickable(true);
        compositorView.setAccessibilityBridge(accessibilityBridge);

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
                    public void onLinuxDragText(String text) {
                        startLinuxTextDrag(text);
                    }

                    @Override
                    public void onLinuxDragUriList(String uriList) {
                        startLinuxUriDrag(uriList);
                    }

                    @Override
                    public void onError(String detail) {
                        Log.e(logTag, "Shared compositor failed: " + detail);
                    }
                });
        holder[0] = session;
        session.setIndependentWindows(independentWindows);
        installInputRouting();
        installDragRouting(compositorView, 0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(systemChrome);
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
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int lightBars = resolvedDarkAppearance() ? 0
                    : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(lightBars,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        root.requestApplyInsets();
        compositorView.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            if (session != null) {
                session.configure(width, height, 1);
                if (independentWindows && primaryFrame != null) {
                    session.configureWindow(primaryFrame.window.id, width, height);
                }
            }
            if (width > 1 && height > 1 && launched.compareAndSet(false, true)) {
                if (processTreeProbe) runProcessTreeCleanupProbe();
                else if (runtimeProbeUri == null) launch(width, height);
                else if (runtimeLoaderUri == null) runRuntimeFdProbe(runtimeProbeUri);
                else if (runtimeGui) launchRuntimeGlibc(width, height);
                else runRuntimeGlibcProbe(runtimeProbeUri, runtimeLoaderUri,
                        runtimeLibraryUris, runtimeLibraryNames);
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!AndroidDocumentSession.isDocumentIntent(intent)) {
            setIntent(intent);
            return;
        }
        if (documentSession == null) {
            Log.w(logTag, "Ignoring new document intent without declared bridge capability");
            return;
        }
        Intent pending = new Intent(intent);
        boolean debuggable = (getApplicationInfo().flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        boolean autoConfirm = debuggable && pending.getBooleanExtra(
                "archphene_test_confirm_document_restart", false);
        boolean requireActive = debuggable && pending.getBooleanExtra(
                "archphene_test_require_active_document_restart", false);
        boolean active = hasActiveLinuxRuntime();
        if (requireActive && !active) {
            throw new IllegalStateException(
                    "Running document restart probe found no active Linux runtime");
        }
        if (!active || autoConfirm) {
            restartForDocumentIntent(pending);
            return;
        }
        showDocumentRestartDialog(pending);
    }

    private synchronized boolean hasActiveLinuxRuntime() {
        return managedRuntimeExecution != null
                || packagedRuntimeActive.get()
                || (linuxProcess != null && linuxProcess.isAlive());
    }

    private void showDocumentRestartDialog(Intent pending) {
        if (documentRestartDialog != null) documentRestartDialog.dismiss();
        documentRestartDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Open document?")
                .setMessage("This Linux app must restart to open the document. "
                        + "Unsaved changes may be lost.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Restart and open", (dialog, which) ->
                        restartForDocumentIntent(pending))
                .create();
        documentRestartDialog.setOnDismissListener(dialog -> documentRestartDialog = null);
        documentRestartDialog.show();
    }

    private void restartForDocumentIntent(Intent pending) {
        if (documentRestartDialog != null) {
            documentRestartDialog.setOnDismissListener(null);
            documentRestartDialog.dismiss();
            documentRestartDialog = null;
        }
        if (documentSession != null) {
            documentSession.close();
            documentSession = null;
        }
        Log.i(logTag, "Restarting running Linux app for document intent");
        setIntent(pending);
        recreate();
    }

    private List<File> importDocumentsIfAllowed() {
        if (documentSession != null) {
            List<File> imported = documentSession.importDocuments(getIntent());
            runDocumentSessionProbe(imported);
            return imported;
        }
        if (AndroidDocumentSession.isDocumentIntent(getIntent())) {
            Log.w(logTag, "Ignoring document intent without declared bridge capability");
        }
        return java.util.Collections.emptyList();
    }

    private void runBrokerPermissionProbe() {
        String authority = getIntent().getStringExtra(
                "archphene_test_private_broker_authority");
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
                || authority == null) {
            return;
        }
        Uri uri = new Uri.Builder().scheme("content").authority(authority)
                .appendPath("document").appendPath("home").build();
        try (android.database.Cursor result = getContentResolver().query(
                uri, new String[] {"document_id"}, null, null, null)) {
            if (result == null) {
                Log.i(logTag,
                        "Private GUI home provider unavailable to unauthorized caller");
                return;
            }
            throw new SecurityException(
                    "Private GUI home provider accepted unauthorized caller");
        } catch (SecurityException expected) {
            if (expected.getMessage() != null && expected.getMessage().startsWith(
                    "Private GUI home provider accepted")) {
                throw expected;
            }
            Log.i(logTag, "Private GUI home provider denied unauthorized caller");
        }
    }
    private void runDocumentSessionProbe(List<File> imported) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
                || !getIntent().getBooleanExtra(
                        "archphene_test_document_conflict", false)) {
            return;
        }
        try {
            documentSession.runConflictProbe(imported);
            if (getIntent().getBooleanExtra(
                    "archphene_test_confirm_document_restart", false)) {
                Log.i(logTag, "Running document restart probe passed documents="
                        + imported.size());
            }
        } catch (Exception error) {
            Log.e(logTag, "Document conflict probe failed", error);
            throw new IllegalStateException("Document conflict probe failed", error);
        }
    }
    private void loadManagerRuntimePack() {
        ContentProviderClient client = null;
        String leasedPack = null;
        try {
            client = getContentResolver().acquireContentProviderClient(RUNTIME_PROVIDER);
            if (client == null) return;
            Bundle request = new Bundle();
            request.putBinder("lease_token", runtimeLeaseToken);
            Bundle runtime = client.call(ACTIVE_PACK_METHOD, null, request);
            if (runtime == null) return;
            leasedPack = runtime.getString("pack_id");
            String program = runtime.getString("program_uri");
            String loader = runtime.getString("loader_uri");
            String[] libraries = runtime.getStringArray("library_uris");
            String[] names = runtime.getStringArray("library_names");
            if (leasedPack == null || !leasedPack.matches("[a-f0-9]{64}")
                    || program == null || loader == null || libraries == null || names == null
                    || libraries.length == 0 || libraries.length != names.length) {
                throw new SecurityException("Manager returned an invalid runtime pack");
            }
            runtimeProbeUri = program;
            runtimeProgramName = runtime.getString("program_name", "program");
            runtimeLoaderUri = loader;
            runtimeLibraryUris = libraries;
            runtimeLibraryNames = names;
            runtimeDataUri = runtime.getString("data_uri");
            String runtimeToolkit = runtime.getString("toolkit");
            if ("qt6".equals(runtimeToolkit) || "gtk3".equals(runtimeToolkit)
                    || "gtk4".equals(runtimeToolkit)
                    || "wayland".equals(runtimeToolkit)) toolkit = runtimeToolkit;
            runtimeGui = true;
            synchronized (this) {
                runtimePackId = leasedPack;
                runtimeProviderClient = client;
                client = null;
            }
            Log.i(logTag, "Loaded manager-owned runtime pack " + runtimePackId);
        } catch (Exception unavailable) {
            Log.d(logTag, "No manager-owned runtime pack; using packaged payload");
        } finally {
            if (client != null) {
                if (leasedPack != null) releaseLease(client, leasedPack);
                client.release();
            }
        }
    }

    private void loadManagerAppearance() {
        try {
            Bundle appearance = getContentResolver().call(
                    RUNTIME_PROVIDER, APPEARANCE_METHOD, null, null);
            if (appearance == null) return;
            String theme = appearance.getString("theme_mode", "system");
            appearanceTheme = "dark".equals(theme) || "light".equals(theme)
                    ? theme : "system";
            int scale = appearance.getInt("scale_percent", 0);
            appearanceScalePercent = scale == 100 || scale == 125 || scale == 150
                    || scale == 175 || scale == 200 ? scale : 0;
            int font = appearance.getInt("font_percent", 100);
            appearanceFontPercent = font == 110 || font == 120 || font == 125 || font == 150
                    ? font : 100;
            appearanceMaterialYou = appearance.getBoolean("material_you", false);
        } catch (Exception unavailable) {
            Log.d(logTag, "No manager appearance policy; using Android defaults");
        }
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

    private void installDragRouting(ArchpheneInputView target, int windowId) {
        if (!capabilities.contains(BridgeCapabilities.DRAG_DROP)) return;
        target.setOnDragListener((view, event) -> {
            if (event.getLocalState() == linuxDragToken) {
                if (event.getAction() == DragEvent.ACTION_DRAG_ENDED && session != null) {
                    session.finishLinuxDrag(event.getResult());
                }
                return true;
            }
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    androidDragDropped = false;
                    return event.getClipDescription() != null;
                case DragEvent.ACTION_DRAG_ENTERED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    routeAndroidDragMotion(target, windowId, event);
                    return true;
                case DragEvent.ACTION_DROP: {
                    routeAndroidDragMotion(target, windowId, event);
                    ClipData clip = event.getClipData();
                    if (containsOnlyUris(clip)) {
                        return importAndroidDragDocuments(event, clip);
                    }
                    if (clip == null || clip.getItemCount() != 1
                            || clip.getItemAt(0).getUri() != null) {
                        if (session != null) session.cancelAndroidDrag();
                        return false;
                    }
                    CharSequence value = clip.getItemAt(0).coerceToText(this);
                    String text = value == null ? "" : value.toString();
                    if (text.getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024) {
                        if (session != null) session.cancelAndroidDrag();
                        return false;
                    }
                    androidDragDropped = true;
                    if (session != null) session.androidDropText(text);
                    return true;
                }
                case DragEvent.ACTION_DRAG_EXITED:
                    if (session != null) session.cancelAndroidDrag();
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    if (!androidDragDropped && session != null) {
                        session.cancelAndroidDrag();
                    }
                    androidDragDropped = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    private boolean containsOnlyUris(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0 || clip.getItemCount() > 32) return false;
        for (int index = 0; index < clip.getItemCount(); index++) {
            if (clip.getItemAt(index).getUri() == null) return false;
        }
        return true;
    }

    private boolean importAndroidDragDocuments(DragEvent event, ClipData clip) {
        if (session == null || documentSession == null) {
            if (session != null) session.cancelAndroidDrag();
            return false;
        }
        DragAndDropPermissions permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? requestDragAndDropPermissions(event) : null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && permission == null) {
            session.cancelAndroidDrag();
            return false;
        }
        AndroidDocumentSession documents = documentSession;
        androidDragDropped = true;
        if (permission != null) {
            synchronized (retainedDragPermissions) {
                retainedDragPermissions.add(permission);
            }
        }
        new Thread(() -> {
            List<File> imported = documents.importDragDocuments(clip);
            if (imported.isEmpty() || isFinishing() || isDestroyed()) {
                releaseDragPermission(permission);
                if (session != null) session.cancelAndroidDrag();
                return;
            }
            StringBuilder uriList = new StringBuilder();
            for (File file : imported) {
                uriList.append(file.toURI().toASCIIString()).append("\r\n");
            }
            if (session != null) session.androidDropUriList(uriList.toString());
        }, "archphene-drag-document-import").start();
        return true;
    }

    private void releaseDragPermission(DragAndDropPermissions permission) {
        if (permission == null) return;
        boolean retained;
        synchronized (retainedDragPermissions) {
            retained = retainedDragPermissions.remove(permission);
        }
        if (retained) permission.release();
    }

    private void releaseDragPermissions() {
        List<DragAndDropPermissions> permissions;
        synchronized (retainedDragPermissions) {
            permissions = new ArrayList<>(retainedDragPermissions);
            retainedDragPermissions.clear();
        }
        for (DragAndDropPermissions permission : permissions) permission.release();
    }

    private void routeAndroidDragMotion(
            ArchpheneInputView target, int windowId, DragEvent event) {
        if (session == null) return;
        int width = target.getDrawable() == null
                ? target.getWidth() : target.getDrawable().getIntrinsicWidth();
        int height = target.getDrawable() == null
                ? target.getHeight() : target.getDrawable().getIntrinsicHeight();
        session.androidDragMotion(windowId, target, Math.max(1, width), Math.max(1, height),
                event.getX(), event.getY(), android.os.SystemClock.uptimeMillis());
    }

    private void startLinuxUriDrag(String uriList) {
        if (session == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                || uriList == null
                || uriList.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            if (session != null) session.finishLinuxDrag(false);
            return;
        }
        try {
            File home = new File(getFilesDir(), "linux-home").getCanonicalFile();
            ArrayList<Uri> exported = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String rawLine : uriList.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Uri source = Uri.parse(line);
                if (!"file".equals(source.getScheme())
                        || (source.getAuthority() != null
                                && !source.getAuthority().isEmpty())) {
                    throw new SecurityException("Linux drag URI is not a local file");
                }
                File file = new File(source.getPath()).getCanonicalFile();
                if (!file.isFile()
                        || !file.getPath().startsWith(home.getPath() + File.separator)) {
                    throw new SecurityException("Linux drag file is outside visible home");
                }
                String relative = file.getPath().substring(home.getPath().length() + 1)
                        .replace(File.separatorChar, '/');
                for (String segment : relative.split("/")) {
                    if (segment.isEmpty() || segment.startsWith(".")) {
                        throw new SecurityException("Linux drag file is private");
                    }
                }
                if (!seen.add(relative)) continue;
                exported.add(new Uri.Builder().scheme("content")
                        .authority(getPackageName() + ".documents")
                        .appendPath("document").appendPath("home/" + relative).build());
                if (exported.size() > 32) {
                    throw new SecurityException("Linux drag has too many files");
                }
            }
            if (exported.isEmpty()) throw new SecurityException("Linux drag has no files");
            ClipData data = new ClipData("Archphene Linux files",
                    new String[] {ClipDescription.MIMETYPE_TEXT_URILIST,
                            "application/octet-stream"},
                    new ClipData.Item(exported.get(0)));
            for (int index = 1; index < exported.size(); index++) {
                data.addItem(new ClipData.Item(exported.get(index)));
            }
            ArchpheneInputView source = focusedDragSource();
            ImageView shadowView = new ImageView(this);
            shadowView.setImageResource(android.R.drawable.ic_menu_save);
            int shadowSize = Math.round(48 * getResources().getDisplayMetrics().density);
            shadowView.layout(0, 0, shadowSize, shadowSize);
            boolean started = source.startDragAndDrop(data,
                    new View.DragShadowBuilder(shadowView), linuxDragToken,
                    View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
            if (!started) session.finishLinuxDrag(false);
        } catch (Exception error) {
            Log.w(logTag, "Rejected Linux file drag", error);
            session.finishLinuxDrag(false);
        }
    }

    private ArchpheneInputView focusedDragSource() {
        ArchpheneInputView source = compositorView;
        for (SecondaryWindow window : secondaryWindows.values()) {
            if (window.view.hasFocus()) return window.view;
        }
        return source;
    }

    private void startLinuxTextDrag(String text) {
        if (session == null || text == null || text.isEmpty()
                || !capabilities.contains(BridgeCapabilities.DRAG_DROP)) {
            if (session != null) session.finishLinuxDrag(false);
            return;
        }
        ArchpheneInputView source = focusedDragSource();
        ClipData data = ClipData.newPlainText("Archphene Linux drag", text);
        ImageView shadowView = new ImageView(this);
        shadowView.setImageResource(android.R.drawable.ic_menu_edit);
        int shadowSize = Math.round(48 * getResources().getDisplayMetrics().density);
        shadowView.layout(0, 0, shadowSize, shadowSize);
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(shadowView);
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            started = source.startDragAndDrop(
                    data, shadow, linuxDragToken, View.DRAG_FLAG_GLOBAL);
        } else {
            started = source.startDrag(data, shadow, linuxDragToken, 0);
        }
        if (!started) session.finishLinuxDrag(false);
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
            int viewportWidth = compositorView.getWidth();
            int viewportHeight = compositorView.getHeight();
            compositorView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            compositorView.setImageBitmap(nextPrimary.bitmap);
            if (session != null && viewportWidth > 1 && viewportHeight > 1
                    && (nextPrimary.bitmap.getWidth() != viewportWidth
                            || nextPrimary.bitmap.getHeight() != viewportHeight)) {
                session.configureWindow(
                        nextPrimary.window.id, viewportWidth, viewportHeight);
            }
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
            installDragRouting(view, id);
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
            float fittedScale = Math.min(scale, Math.min(
                    maxWidth / (float) frameWidth,
                    maxHeight / (float) frameHeight));
            int width = Math.max(1, Math.round(frameWidth * fittedScale));
            int height = Math.max(1, Math.round(frameHeight * fittedScale));
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
    private void runProcessTreeCleanupProbe() {
        new Thread(() -> {
            Process shell = null;
            try {
                shell = new ProcessBuilder(
                        "/system/bin/sh", "-c", "sleep 300 & wait").start();
                Thread.sleep(500);
                int terminated = RuntimeFdLauncher.terminateUidProcesses();
                if (terminated < 2 || !shell.waitFor(
                        3, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "UID cleanup did not terminate the process tree");
                }
                Log.i("ArchpheneRuntime", "Process-tree cleanup probe passed; terminated="
                        + terminated + " exit=" + shell.exitValue());
            } catch (Throwable error) {
                Log.e("ArchpheneRuntime", "Process-tree cleanup probe failed", error);
            } finally {
                if (shell != null) shell.destroyForcibly();
                runOnUiThread(this::finish);
            }
        }, "archphene-process-tree-probe").start();
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
    private void launchRuntimeGlibc(int width, int height) {
        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "wayland-0");
        session.start(socket, width, height);
        new Thread(() -> {
            RuntimeFdLauncher.Execution execution = RuntimeFdLauncher.newExecution();
            try {
                if (!registerManagedRuntimeExecution(execution)) {
                    execution.cancel();
                    throw new java.util.concurrent.CancellationException(
                            "Activity was destroyed before runtime launch");
                }
                if (runtimePackId != null && !beginRuntimeExecution()) {
                    throw new SecurityException("Runtime pack lease is unavailable");
                }
                if (runtimeLibraryUris == null || runtimeLibraryNames == null
                        || runtimeLibraryUris.length != runtimeLibraryNames.length) {
                    throw new IllegalArgumentException("Runtime library intent is malformed");
                }
                android.net.Uri[] libraries = new android.net.Uri[runtimeLibraryUris.length];
                for (int index = 0; index < runtimeLibraryUris.length; index++) {
                    if (runtimeLibraryUris[index] == null) {
                        throw new IllegalArgumentException("Runtime library URI is missing");
                    }
                    libraries[index] = android.net.Uri.parse(runtimeLibraryUris[index]);
                }
                File runtimeLib = new File(getFilesDir(), "linux-runtime/lib");
                prepareRuntime(runtimeLib);
                File home = new File(getFilesDir(), "linux-home");
                File cache = new File(home, ".cache");
                File config = new File(home, ".config");
                File tmp = new File(getCacheDir(), "linux-tmp");
                home.mkdirs();
                cache.mkdirs();
                config.mkdirs();
                tmp.mkdirs();
                startDesktopIntegration(runtimeDir);
                startAudioIntegration();
                if (isActivityDestroyed()) return;
                Map<String, String> environment = new HashMap<>();
                environment.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
                environment.put("HOME", home.getAbsolutePath());
                environment.put("XDG_CACHE_HOME", cache.getAbsolutePath());
                environment.put("XDG_CONFIG_HOME", config.getAbsolutePath());
                environment.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
                environment.put("WAYLAND_DISPLAY", "wayland-0");
                applyCapabilityEnvironment(environment);
                environment.put("TMPDIR", tmp.getAbsolutePath());
                File runtimeRoot = new File(getFilesDir(), "linux-runtime/root");
                environment.put("ARCHPHENE_RUNTIME_ROOT", runtimeRoot.getAbsolutePath());
                environment.put("XDG_DATA_DIRS",
                        new File(runtimeRoot, "usr/share").getAbsolutePath());
                environment.put("__EGL_VENDOR_LIBRARY_DIRS", new File(runtimeRoot,
                        "usr/share/glvnd/egl_vendor.d").getAbsolutePath());
                File fontconfig = copyAsset("fonts.conf",
                        new File(getFilesDir(), "fontconfig/fonts.conf"));
                environment.put("FONTCONFIG_FILE", fontconfig.getAbsolutePath());
                environment.put("FONTCONFIG_PATH", fontconfig.getParentFile().getAbsolutePath());
                File gpuSocket = startGpuBridge();
                applyToolkitEnvironment(environment, runtimeLib, config, gpuSocket);
                if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        && getIntent().getBooleanExtra(
                                "archphene_test_media_debug", false)) {
                    File pipeWireLog = new File(getCacheDir(), "pipewire-debug.log");
                    File gstreamerLog = new File(getCacheDir(), "gstreamer-debug.log");
                    pipeWireLog.delete();
                    gstreamerLog.delete();
                    environment.put("ARCHPHENE_PIPEWIRE_DEBUG_LOG",
                            pipeWireLog.getAbsolutePath());
                    environment.put("GST_DEBUG",
                            "2,pipewire*:6,camerabin*:6,GST_STATES:5");
                    environment.put("GST_DEBUG_NO_COLOR", "1");
                    environment.put("GST_DEBUG_FILE", gstreamerLog.getAbsolutePath());
                }
                List<File> imported = importDocumentsIfAllowed();
                List<String> arguments = new java.util.ArrayList<>();
                for (File file : imported) arguments.add(file.getAbsolutePath());
                RuntimeFdLauncher.Result result = RuntimeFdLauncher.runGlibc(
                        getContentResolver(), android.net.Uri.parse(runtimeProbeUri),
                        android.net.Uri.parse(runtimeLoaderUri), libraries,
                        runtimeLibraryNames, getCacheDir(), environment,
                        runtimeProgramName, arguments, execution);
                Log.i("ArchpheneRuntime", "Runtime GUI exit=" + result.exitCode);
                logRuntimeOutput(result.output);
            } catch (Throwable error) {
                Log.e("ArchpheneRuntime", "Could not launch runtime GUI", error);
            } finally {
                clearManagedRuntimeExecution(execution);
                int terminated = RuntimeFdLauncher.terminateUidProcesses();
                if (terminated > 0) {
                    Log.i("ArchpheneRuntime", "Cleaned up " + terminated
                            + " remaining Linux processes");
                }
                gpuBridge.stop();
                audioIntegration.stop();
                desktopIntegration.stop();
                releaseRuntimeLease();
            }
        }, "archphene-runtime-gui").start();
    }

    private static void logRuntimeOutput(String output) {
        if (output == null || output.isEmpty()) return;
        int chunk = 3000;
        for (int offset = 0; offset < output.length(); offset += chunk) {
            int end = Math.min(output.length(), offset + chunk);
            Log.i("ArchpheneRuntime", "Runtime output "
                    + (offset / chunk + 1) + ": " + output.substring(offset, end));
        }
    }

    private void launch(int width, int height) {
        File runtimeDir = new File(getFilesDir(), "wayland-runtime");
        runtimeDir.mkdirs();
        File socket = new File(runtimeDir, "wayland-0");
        session.start(socket, width, height);
        packagedRuntimeActive.set(true);
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
            startDesktopIntegration(waylandRuntime);
            startAudioIntegration();
            if (isActivityDestroyed()) return;
            List<File> imported = importDocumentsIfAllowed();

            ProcessBuilder builder = new ProcessBuilder(
                    loader.getAbsolutePath(),
                    "--library-path",
                    runtimeLib.getAbsolutePath() + ":" + apkLib.getAbsolutePath(),
                    executable.getAbsolutePath());
            for (File file : imported) builder.command().add(file.getAbsolutePath());
            Map<String, String> env = builder.environment();
            env.put("LD_LIBRARY_PATH", runtimeLib.getAbsolutePath() + ":" + apkLib.getAbsolutePath());
            env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
            env.put("HOME", home.getAbsolutePath());
            env.put("XDG_CACHE_HOME", cache.getAbsolutePath());
            env.put("XDG_CONFIG_HOME", config.getAbsolutePath());
            env.put("XDG_RUNTIME_DIR", waylandRuntime.getAbsolutePath());
            env.put("WAYLAND_DISPLAY", "wayland-0");
            applyCapabilityEnvironment(env);
            if (getIntent().getBooleanExtra("archphene.wayland_debug", false)) {
                env.put("WAYLAND_DEBUG", "client");
                Log.i(logTag, "Wayland client protocol trace enabled");
            }
            env.put("TMPDIR", tmp.getAbsolutePath());
            File fontconfig = copyAsset("fonts.conf", new File(getFilesDir(), "fontconfig/fonts.conf"));
            env.put("FONTCONFIG_FILE", fontconfig.getAbsolutePath());
            env.put("FONTCONFIG_PATH", fontconfig.getParentFile().getAbsolutePath());
            File gpuSocket = startGpuBridge();
            applyToolkitEnvironment(env, runtimeLib, config, gpuSocket);
            if (isActivityDestroyed()) return;
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
        } finally {
            packagedRuntimeActive.set(false);
            audioIntegration.stop();
            desktopIntegration.stop();
            gpuBridge.stop();
        }
    }

    private void applyCapabilityEnvironment(Map<String, String> environment) {
        environment.put("ARCHPHENE_ANDROID_BROKER", "@" + capabilityBroker.socketName());
        environment.put("ARCHPHENE_ANDROID_PROTOCOL", "1");
        desktopIntegration.applyEnvironment(environment);
        audioIntegration.applyEnvironment(environment);
    }

    private void startDesktopIntegration(File runtimeDirectory) throws IOException {
        boolean camera = capabilities.contains(BridgeCapabilities.CAMERA);
        desktopIntegration.start(new File(getApplicationInfo().nativeLibraryDir),
                getCacheDir(), "@" + capabilityBroker.socketName(),
                getApplicationInfo().loadLabel(getPackageManager()).toString(),
                capabilities.contains(BridgeCapabilities.SECRETS), false, camera,
                camera ? new File(runtimeDirectory, "pipewire-0").getAbsolutePath() : null);
    }

    private void startAudioIntegration() throws IOException {
        if (!capabilities.contains(BridgeCapabilities.AUDIO_OUTPUT)) return;
        audioIntegration.start(new File(getApplicationInfo().nativeLibraryDir), getCacheDir(),
                capabilities.contains(BridgeCapabilities.AUDIO_INPUT),
                "@" + capabilityBroker.socketName());
    }

    private File startGpuBridge() {
        if (!("wayland".equals(toolkit) || "gtk4".equals(toolkit))) return null;
        return gpuBridge.start(new File(getApplicationInfo().nativeLibraryDir), getCacheDir());
    }

    private boolean resolvedDarkAppearance() {
        boolean systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return "dark".equals(appearanceTheme)
                || (!"light".equals(appearanceTheme) && systemDark);
    }
    private void applyToolkitEnvironment(Map<String, String> env, File runtimeLib,
            File configDir, File gpuSocket) throws IOException {
        Configuration configuration = getResources().getConfiguration();
        boolean dark = resolvedDarkAppearance();
        int scalePercent = appearanceScalePercent;
        if (scalePercent == 0) {
            int smallestWidth = configuration.smallestScreenWidthDp;
            scalePercent = smallestWidth >= 840 ? 100 : smallestWidth >= 600 ? 125 : 150;
        }
        float appScale = scalePercent / 100f;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float requestedBodyPixels = 16f * scaledDensity
                * appearanceFontPercent / 100f;
        int fontPointSize = Math.max(9, Math.min(30,
                Math.round(requestedBodyPixels * 72f / 96f / appScale)));
        env.put("QT_SCALE_FACTOR", String.format(Locale.US, "%.2f", appScale));
        env.put("QT_SCALE_FACTOR_ROUNDING_POLICY", "PassThrough");
        env.put("QT_FONT_DPI", "96");
        env.put("QT_QPA_PLATFORMTHEME", "archphene");
        env.put("QT_STYLE_OVERRIDE", "archphene");
        env.put("ARCHPHENE_FONT_POINT_SIZE", Integer.toString(fontPointSize));
        env.put("ARCHPHENE_COLOR_SCHEME", dark ? "dark" : "light");
        File dataHome = new File(configDir.getParentFile(), ".local/share");
        dataHome.mkdirs();
        env.put("XDG_DATA_HOME", dataHome.getAbsolutePath());
        File root = new File(getFilesDir(), "linux-runtime/root");
        File xkbRoot = new File(root, "usr/share/X11/xkb");
        if (!xkbRoot.isDirectory()) {
            xkbRoot = new File(root, "usr/share/xkeyboard-config-2");
        }
        if (xkbRoot.isDirectory()) env.put("XKB_CONFIG_ROOT", xkbRoot.getAbsolutePath());
        Log.i(logTag, "Appearance theme=" + appearanceTheme + " resolved="
                + (dark ? "dark" : "light") + " scale=" + scalePercent
                + " font=" + appearanceFontPercent + " pointSize=" + fontPointSize
                + " materialYou="
                + appearanceMaterialYou);
        if ("wayland".equals(toolkit) || "gtk4".equals(toolkit)) {
            env.put("EGL_PLATFORM", "wayland");
            env.put("LIBGL_ALWAYS_SOFTWARE", "true");
            if (gpuSocket != null) {
                env.put("GALLIUM_DRIVER", "virpipe");
                env.put("VTEST_SOCKET_NAME", gpuSocket.getAbsolutePath());
                Log.i(logTag, "Graphics renderer=virpipe Android EGL/GLES bridge");
            } else {
                env.put("GALLIUM_DRIVER", "llvmpipe");
                Log.i(logTag, "Graphics renderer=llvmpipe fallback");
            }
        }
        if ("gtk3".equals(toolkit) || "gtk4".equals(toolkit)) {
            env.put("GDK_BACKEND", "wayland");
            env.put("GDK_SCALE", "1");
            env.put("GDK_DPI_SCALE", "1.0");
            writeGtkTheme(configDir, dark, fontPointSize, appScale);
            if ("gtk3".equals(toolkit)) {
                env.put("GTK_IM_MODULE", "wayland");
                env.put("GTK_IM_MODULE_FILE", new File(
                        runtimeLib, "gtk-3.0/3.0.0/immodules.cache").getAbsolutePath());
            }

            env.put("GTK_USE_PORTAL", "0");
            env.put("GTK_THEME", dark ? "Adwaita:dark" : "Adwaita");
            env.put("GTK_DATA_PREFIX", new File(root, "usr").getAbsolutePath());
            env.put("XDG_DATA_DIRS", new File(root, "usr/share").getAbsolutePath());
            env.put("GIO_USE_VFS", "local");


            env.put("GSETTINGS_SCHEMA_DIR", new File(
                    root, "usr/share/glib-2.0/schemas").getAbsolutePath());
            if ("gtk3".equals(toolkit)) {
                env.put("MOUSEPAD_PLUGIN_PATH",
                        new File(runtimeLib, "mousepad/plugins").getAbsolutePath());
            }
            env.put("GCONV_PATH", new File(root, "usr/lib/gconv").getAbsolutePath());
        } else if (!"wayland".equals(toolkit)) {
            env.put("QT_QPA_PLATFORM", "wayland");
            env.put("QT_QPA_PLATFORM_PLUGIN_PATH", runtimeLib.getAbsolutePath());
            env.put("QT_PLUGIN_PATH", runtimeLib.getAbsolutePath());
            env.put("QT_WAYLAND_SHELL_INTEGRATION", "xdg-shell");
            writeKdeTheme(configDir, dark);
        }
    }

    private void writeGtkTheme(File configDir, boolean dark,
            int fontPointSize, float appScale) throws IOException {
        File gtkConfig = new File(configDir, "gtk-3.0");
        gtkConfig.mkdirs();
        File gtk4Config = new File(configDir, "gtk-4.0");
        gtk4Config.mkdirs();
        float density = getResources().getDisplayMetrics().density;
        int uiFontSize = Math.max(fontPointSize,
                Math.round(fontPointSize * 4f / 3f * appScale));
        int controlHeight = Math.max(Math.round(48f * density), uiFontSize + 20);
        int titleButtonSize = Math.max(Math.round(48f * density), uiFontSize + 20);
        int horizontalPadding = Math.max(14, Math.round(12f * density));
        int scrollbarSize = Math.max(18, Math.round(12f * density));
        int menuBorder = Math.max(2, Math.round(1f * density));
        String outline = dark ? "rgba(255,255,255,0.28)" : "rgba(0,0,0,0.24)";
        String shadow = dark ? "rgba(0,0,0,0.72)" : "rgba(0,0,0,0.38)";
        String baseSettings = "[Settings]\n"
                + "gtk-theme-name=" + (dark ? "Adwaita-dark" : "Adwaita") + "\n"
                + "gtk-icon-theme-name=Adwaita\n"
                + "gtk-font-name=Noto Sans " + fontPointSize + "\n"
                + "gtk-application-prefer-dark-theme=" + dark + "\n";
        String settings = baseSettings
                + "gtk-menu-images=false\n"
                + "gtk-button-images=false\n";
        writeText(new File(gtkConfig, "settings.ini"), settings);
        String css = "* {\n"
                + "  font-size: " + uiFontSize + "px;\n"
                + "}\n"
                + "button, entry, combobox, menuitem {\n"
                + "  min-height: " + controlHeight + "px;\n"
                + "}\n"
                + "menubar > menuitem {\n"
                + "  min-height: " + controlHeight + "px;\n"
                + "  padding: 0 " + horizontalPadding + "px;\n"
                + "}\n"
                + ".csd menu, menu, .menu, .context-menu {\n"
                + "  border: " + menuBorder + "px solid " + outline + ";\n"
                + "  box-shadow: inset 0 0 0 1px " + outline
                + ", 0 8px 24px " + shadow + ";\n"
                + "  padding: 4px;\n"
                + "}\n"
                + ".csd.popup decoration {\n"
                + "  box-shadow: 0 8px 24px " + shadow
                + ", 0 0 0 " + menuBorder + "px " + outline + ";\n"
                + "}\n"
                + "menu menuitem {\n"
                + "  min-height: " + controlHeight + "px;\n"
                + "  padding: 0 " + horizontalPadding + "px;\n"
                + "}\n"
                + "headerbar {\n"
                + "  min-height: " + titleButtonSize + "px;\n"
                + "}\n"
                + "headerbar button.titlebutton, headerbar .titlebutton {\n"
                + "  min-width: " + titleButtonSize + "px;\n"
                + "  min-height: " + titleButtonSize + "px;\n"
                + "  padding: 4px;\n"
                + "}\n"
                + "headerbar button.titlebutton image, headerbar .titlebutton image,\n"
                + ".titlebar button.titlebutton image, windowcontrols image {\n"
                + "  -gtk-icon-transform: scale(3);\n"
                + "}\n"
                + "scrollbar slider {\n"
                + "  min-width: " + scrollbarSize + "px;\n"
                + "  min-height: " + scrollbarSize + "px;\n"
                + "}\n";
        writeText(new File(gtkConfig, "gtk.css"), css);
        writeText(new File(gtk4Config, "settings.ini"), baseSettings);
        writeText(new File(gtk4Config, "gtk.css"), css);
    }
    private void writeKdeTheme(File configDir, boolean dark) throws IOException {
        String foreground = dark ? "239,240,241" : "35,38,41";
        String inactive = dark ? "174,181,185" : "91,99,104";
        String window = dark ? "35,38,41" : "239,240,241";
        String alternate = dark ? "42,46,50" : "246,247,248";
        String view = dark ? "27,30,32" : "255,255,255";
        String button = dark ? "49,54,59" : "239,240,241";
        String accent = dark ? "86,188,236" : "23,147,209";
        String selectionForeground = dark ? "17,20,23" : "255,255,255";
        if (appearanceMaterialYou && Build.VERSION.SDK_INT >= 31) {
            foreground = rgb(getColor(dark ? android.R.color.system_neutral1_10
                    : android.R.color.system_neutral1_900));
            inactive = rgb(getColor(dark ? android.R.color.system_neutral1_200
                    : android.R.color.system_neutral1_700));
            window = rgb(getColor(dark ? android.R.color.system_neutral1_900
                    : android.R.color.system_neutral1_10));
            alternate = rgb(getColor(dark ? android.R.color.system_neutral1_800
                    : android.R.color.system_neutral1_50));
            view = window;
            button = alternate;
            accent = rgb(getColor(dark ? android.R.color.system_accent1_200
                    : android.R.color.system_accent1_600));
            selectionForeground = window;
        }
        String schemeName = dark ? "ArchpheneDark" : "ArchpheneLight";
        StringBuilder palette = new StringBuilder();
        palette.append("[General]\nName=Archphene ")
                .append(dark ? "Dark" : "Light")
                .append("\nColorScheme=").append(schemeName).append("\n\n");
        appendColorSet(palette, "Window", window, alternate, foreground, inactive,
                accent, foreground);
        appendColorSet(palette, "View", view, alternate, foreground, inactive,
                accent, foreground);
        appendColorSet(palette, "Button", button, alternate, foreground, inactive,
                accent, foreground);
        appendColorSet(palette, "Selection", accent, accent, selectionForeground,
                selectionForeground, accent, selectionForeground);
        appendColorSet(palette, "Tooltip", button, alternate, foreground, inactive,
                accent, foreground);
        palette.append("[ColorEffects:Disabled]\n")
                .append("Color=56,56,56\nColorAmount=0\nColorEffect=0\n")
                .append("ContrastAmount=0.65\nContrastEffect=1\n")
                .append("IntensityAmount=0.1\nIntensityEffect=2\n\n")
                .append("[ColorEffects:Inactive]\n")
                .append("ChangeSelectionColor=true\nColor=112,111,110\n")
                .append("ColorAmount=0.025\nColorEffect=2\n")
                .append("ContrastAmount=0.1\nContrastEffect=2\n")
                .append("Enable=false\nIntensityAmount=0\nIntensityEffect=0\n");
        writeText(new File(configDir, "kdeglobals"), palette.toString());
        File schemes = new File(configDir.getParentFile(), ".local/share/color-schemes");
        schemes.mkdirs();
        writeText(new File(schemes, schemeName + ".colors"), palette.toString());
    }
    private static void appendColorSet(StringBuilder output, String name,
            String background, String alternate, String foreground, String inactive,
            String accent, String active) {
        output.append("[Colors:").append(name).append("]\n")
                .append("BackgroundNormal=").append(background).append('\n')
                .append("BackgroundAlternate=").append(alternate).append('\n')
                .append("ForegroundNormal=").append(foreground).append('\n')
                .append("ForegroundInactive=").append(inactive).append('\n')
                .append("ForegroundActive=").append(active).append('\n')
                .append("ForegroundLink=").append(accent).append('\n')
                .append("ForegroundVisited=").append(accent).append('\n')
                .append("ForegroundNegative=218,68,83\n")
                .append("ForegroundNeutral=246,116,0\n")
                .append("ForegroundPositive=39,174,96\n")
                .append("DecorationFocus=").append(accent).append('\n')
                .append("DecorationHover=").append(accent).append("\n\n");
    }

    private static String rgb(int color) {
        return Color.red(color) + "," + Color.green(color) + "," + Color.blue(color);
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
        File platformTheme = new File(runtimeLib, "libarchphene_qt_platform_theme.so");
        if (platformTheme.isFile()) {
            File directory = new File(runtimeLib, "platformthemes");
            directory.mkdirs();
            copyFile(platformTheme,
                    new File(directory, "libarchphene_qt_platform_theme.so"));
        }
        File style = new File(runtimeLib, "libarchphene_qt_style.so");
        if (style.isFile()) {
            File directory = new File(runtimeLib, "styles");
            directory.mkdirs();
            copyFile(style, new File(directory, "libarchphene_qt_style.so"));
        }
        File shell = new File(runtimeLib, "libarchphene_xdg_shell.so");
        if (shell.isFile()) {
            File directory = new File(runtimeLib, "wayland-shell-integration");
            directory.mkdirs();
            copyFile(shell, new File(directory, "libxdg-shell.so"));
        }
        if ("gtk3".equals(toolkit)) prepareGtkRuntime(runtimeLib);
        if (runtimeDataUri != null || dataAssets != null && !dataAssets.isBlank()) {
            File root = new File(getFilesDir(), "linux-runtime/root");
            deleteContents(root);
            root.mkdirs();
            if (dataAssets != null && !dataAssets.isBlank()) {
                for (String asset : dataAssets.split(",")) {
                    extractZipAsset(asset.trim(), root);
                }
            }
            if (runtimeDataUri != null) extractZipUri(runtimeDataUri, root);
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
            capabilities = BridgeCapabilities.read(this);
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
        try (InputStream input = getAssets().open(name)) {
            extractZip(input, root);
        }
    }

    private void extractZipUri(String value, File root) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(Uri.parse(value))) {
            if (input == null) throw new IOException("Runtime provider returned no data stream");
            extractZip(input, root);
        }
    }

    private static void extractZip(InputStream input, File root) throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        int entries = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 100000) throw new IOException("Runtime data has too many entries");
                File target = new File(root, entry.getName()).getCanonicalFile();
                if (!target.getPath().startsWith(rootPath)) {
                    throw new IOException("Archive path escapes runtime root");
                }
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("Could not create runtime data directory");
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create runtime data parent");
                }
                try (OutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        total += count;
                        if (total > 2L * 1024 * 1024 * 1024) {
                            throw new IOException("Runtime data exceeds extraction bounds");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                target.setReadable(true, false);
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (capabilityBroker != null) {
            capabilityBroker.onRequestPermissionsResult(requestCode, grantResults);
        }
    }

    @Override
    protected void onDestroy() {
        if (documentRestartDialog != null) {
            documentRestartDialog.dismiss();
            documentRestartDialog = null;
        }
        for (SecondaryWindow window : secondaryWindows.values()) {
            window.dismissFromRegistry();
        }
        secondaryWindows.clear();
        if (documentSession != null) documentSession.close();
        releaseDragPermissions();
        audioIntegration.stop();
        desktopIntegration.stop();
        if (capabilityBroker != null) capabilityBroker.close();
        if (accessibilityBridge != null && compositorView != null) {
            accessibilityBridge.detach(compositorView);
        }
        cancelManagedRuntimeExecution();
        if (linuxProcess != null) linuxProcess.destroy();
        RuntimeFdLauncher.terminateUidProcesses();
        gpuBridge.stop();
        if (session != null) session.close();
        releaseUnstartedRuntimeLease();
        super.onDestroy();
    }

    private synchronized boolean registerManagedRuntimeExecution(
            RuntimeFdLauncher.Execution execution) {
        if (activityDestroyed || managedRuntimeExecution != null) return false;
        managedRuntimeExecution = execution;
        return true;
    }

    private synchronized boolean isActivityDestroyed() {
        return activityDestroyed;
    }

    private synchronized void clearManagedRuntimeExecution(
            RuntimeFdLauncher.Execution execution) {
        if (managedRuntimeExecution == execution) managedRuntimeExecution = null;
    }

    private void cancelManagedRuntimeExecution() {
        RuntimeFdLauncher.Execution execution;
        synchronized (this) {
            activityDestroyed = true;
            execution = managedRuntimeExecution;
        }
        if (execution != null) execution.cancel();
    }

    private synchronized boolean beginRuntimeExecution() {
        if (runtimeProviderClient == null || runtimeExecutionStarted) return false;
        runtimeExecutionStarted = true;
        return true;
    }

    private synchronized void releaseUnstartedRuntimeLease() {
        if (!runtimeExecutionStarted) releaseRuntimeLease();
    }

    private synchronized void releaseRuntimeLease() {
        if (runtimeProviderClient == null) return;
        releaseLease(runtimeProviderClient, runtimePackId);
        runtimeProviderClient.release();
        runtimeProviderClient = null;
        runtimePackId = null;
    }

    private void releaseLease(ContentProviderClient client, String packId) {
        try {
            Bundle request = new Bundle();
            request.putBinder("lease_token", runtimeLeaseToken);
            client.call(RELEASE_LEASE_METHOD, packId, request);
        } catch (Exception error) {
            Log.w(logTag, "Could not explicitly release runtime pack lease", error);
        }
    }
}
