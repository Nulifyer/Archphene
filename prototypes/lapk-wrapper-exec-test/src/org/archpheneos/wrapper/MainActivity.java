package org.archpheneos.wrapper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String STATIC_PAYLOAD = "libarchphene_hello.so";
    private static final String DYNAMIC_PAYLOAD = "libarchphene_dynamic_hello.so";
    private static final String GLIBC_LOADER = "libld.so.2";
    private static final String GLIBC_LIBC = "libc.so.6";
    private static final String SYSCALL_PROBE = "libarchphene_syscall_probe.so";
    private static final String MUSL_PAYLOAD = "libarchphene_dynamic_musl.so";
    private static final String MUSL_LOADER = "libld-musl-x86_64.so";
    private static final String MUSL_WITH_LIB_PAYLOAD = "libarchphene_musl_withlib.so";
    private static final String MUSL_GREET_LIB = "libarchphene_greet.so";
    private static final String PERMISSION_REQUEST_PAYLOAD = "libarchphene_permission_request.so";
    private static final String FILE_REQUEST_PAYLOAD = "libarchphene_file_request.so";
    private static final String CREATE_DOCUMENT_PAYLOAD = "libarchphene_create_document.so";
    private static final String TREE_BACKGROUND_PAYLOAD = "libarchphene_tree_background.so";
    private static final String PRIVATE_HOME_PAYLOAD = "libarchphene_private_home.so";
    private static final String SPLIT_STORAGE_PAYLOAD = "libarchphene_split_storage.so";
    private static final String BRIDGE_JSON_PREFIX = "ARCHPHENE_BRIDGE_JSON ";
    private static final int REQUEST_POST_NOTIFICATIONS = 7001;
    private static final int REQUEST_OPEN_DOCUMENT = 8001;
    private static final int REQUEST_CREATE_DOCUMENT = 8002;
    private static final int REQUEST_OPEN_TREE = 8003;

    private final Object bridgeLock = new Object();
    private TextView bodyView;
    private boolean permissionBridgeStarted;
    private boolean fileBridgeStarted;
    private boolean createDocumentBridgeStarted;
    private boolean treeBackgroundBridgeStarted;
    private boolean privateHomeBridgeStarted;
    private boolean splitStorageBridgeStarted;
    private OutputStream pendingBridgeInput;
    private String pendingBridgeRequestId = "";
    private String pendingCreateDocumentText = "";
    private String persistedTreeUri = "";
    private String permissionCallbackStatus = "No Android permission callback yet.";
    private String filePortalStatus = "No Android file portal callback yet.";
    private String createDocumentStatus = "No Android create document callback yet.";
    private String treeGrantStatus = "No Android tree grant callback yet.";
    private String privateHomeStatus = "No private HOME payload result yet.";
    private String splitStorageStatus = "No split-storage payload result yet.";
    private final StringBuilder bridgeLog = new StringBuilder("Bridge harness: not started.\n");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 56, 40, 40);

        TextView title = new TextView(this);
        title.setText("ArchpheneOS LAPK Wrapper");
        title.setTextSize(24);

        bodyView = new TextView(this);
        bodyView.setTextSize(16);
        bodyView.setPadding(0, 28, 0, 0);
        bodyView.setText(renderBody());

        root.addView(title);
        root.addView(bodyView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        startSplitStorageBridgeSession();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        permissionCallbackStatus = "Android permission callback: POST_NOTIFICATIONS "
                + (granted ? "granted" : "denied") + ".";
        appendBridgeLog(permissionCallbackStatus, false);
        sendBridgeLine(buildPermissionResponse(pendingBridgeRequestId, granted, "android_callback"));
        refreshBody();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_DOCUMENT) {
            handleOpenDocumentResult(resultCode, data);
            return;
        }
        if (requestCode == REQUEST_CREATE_DOCUMENT) {
            handleCreateDocumentResult(resultCode, data);
            return;
        }
        if (requestCode == REQUEST_OPEN_TREE) {
            handleOpenTreeResult(resultCode, data);
        }
    }

    private void handleOpenDocumentResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            filePortalStatus = "Android file portal callback: denied or cancelled.";
            appendBridgeLog(filePortalStatus, false);
            sendBridgeLine(buildFileResponse(pendingBridgeRequestId, false, "cancelled", "", ""));
            refreshBody();
            return;
        }

        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) {
                getContentResolver().takePersistableUriPermission(uri, flags);
            }
        } catch (Exception e) {
            appendBridgeLog("Persistable URI grant note: " + e, false);
        }

        try {
            String text = readContentUri(uri, 4096);
            filePortalStatus = "Android file portal callback: granted URI " + uri + ".";
            appendBridgeLog(filePortalStatus, false);
            sendBridgeLine(buildFileResponse(pendingBridgeRequestId, true, "android_saf", uri.toString(), text));
        } catch (Exception e) {
            filePortalStatus = "Android file portal callback: read failed.";
            appendBridgeLog(filePortalStatus + " " + e, false);
            sendBridgeLine(buildFileResponse(pendingBridgeRequestId, false, "read_failed", uri.toString(), e.toString()));
        }
        refreshBody();
    }

    private void handleCreateDocumentResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            createDocumentStatus = "Android create document callback: denied or cancelled.";
            appendBridgeLog(createDocumentStatus, false);
            sendBridgeLine(buildCreateDocumentResponse(pendingBridgeRequestId, false, "cancelled", "", ""));
            refreshBody();
            return;
        }

        Uri uri = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new IllegalStateException("ContentResolver returned null output stream");
            }
            output.write(pendingCreateDocumentText.getBytes(StandardCharsets.UTF_8));
            createDocumentStatus = "Android create document callback: granted URI " + uri + ".";
            appendBridgeLog(createDocumentStatus, false);
            sendBridgeLine(buildCreateDocumentResponse(pendingBridgeRequestId, true, "android_saf_create_document", uri.toString(), ""));
        } catch (Exception e) {
            createDocumentStatus = "Android create document callback: write failed.";
            appendBridgeLog(createDocumentStatus + " " + e, false);
            sendBridgeLine(buildCreateDocumentResponse(pendingBridgeRequestId, false, "write_failed", uri.toString(), e.toString()));
        }
        refreshBody();
    }
    private void handleOpenTreeResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            treeGrantStatus = "Android tree grant callback: denied or cancelled.";
            appendBridgeLog(treeGrantStatus, false);
            sendBridgeLine(buildTreeResponse(pendingBridgeRequestId, false, "cancelled", "", ""));
            refreshBody();
            return;
        }

        Uri uri = data.getData();
        try {
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
            persistedTreeUri = uri.toString();
            treeGrantStatus = "Android tree grant callback: persisted URI " + uri + ".";
            appendBridgeLog(treeGrantStatus, false);
            sendBridgeLine(buildTreeResponse(pendingBridgeRequestId, true, "android_saf_open_tree", uri.toString(), ""));
        } catch (Exception e) {
            treeGrantStatus = "Android tree grant callback: persist failed.";
            appendBridgeLog(treeGrantStatus + " " + e, false);
            sendBridgeLine(buildTreeResponse(pendingBridgeRequestId, false, "persist_failed", uri.toString(), e.toString()));
        }
        refreshBody();
    }

    private String renderBody() {
        File libDir = new File(getApplicationInfo().nativeLibraryDir);
        File staticPayload = new File(libDir, STATIC_PAYLOAD);
        File dynamicPayload = new File(libDir, DYNAMIC_PAYLOAD);
        File loader = new File(libDir, GLIBC_LOADER);
        File libc = new File(libDir, GLIBC_LIBC);
        File probe = new File(libDir, SYSCALL_PROBE);
        File muslPayload = new File(libDir, MUSL_PAYLOAD);
        File muslLoader = new File(libDir, MUSL_LOADER);
        File muslWithLibPayload = new File(libDir, MUSL_WITH_LIB_PAYLOAD);
        File muslGreetLib = new File(libDir, MUSL_GREET_LIB);
        File permissionRequestPayload = new File(libDir, PERMISSION_REQUEST_PAYLOAD);
        File fileRequestPayload = new File(libDir, FILE_REQUEST_PAYLOAD);
        File createDocumentPayload = new File(libDir, CREATE_DOCUMENT_PAYLOAD);
        File treeBackgroundPayload = new File(libDir, TREE_BACKGROUND_PAYLOAD);
        File privateHomePayload = new File(libDir, PRIVATE_HOME_PAYLOAD);
        File splitStoragePayload = new File(libDir, SPLIT_STORAGE_PAYLOAD);

        StringBuilder out = new StringBuilder();
        out.append("Generated APK execution test\n\n");
        out.append("Package: ").append(getPackageName()).append("\n");
        out.append("UID: ").append(android.os.Process.myUid()).append("\n");
        out.append("PID: ").append(android.os.Process.myPid()).append("\n");
        out.append("Android notification permission: ").append(getPostNotificationPermissionStatus()).append("\n");
        out.append(permissionCallbackStatus).append("\n");
        out.append(filePortalStatus).append("\n");
        out.append(createDocumentStatus).append("\n");
        out.append(treeGrantStatus).append("\n");
        out.append(privateHomeStatus).append("\n");
        out.append(splitStorageStatus).append("\n\n");
        out.append("Bridge status\n");
        synchronized (bridgeLock) {
            out.append(bridgeLog);
        }
        out.append("\n");
        out.append("nativeLibraryDir: ").append(libDir.getAbsolutePath()).append("\n\n");

        appendFileState(out, "Static payload", staticPayload);
        appendFileState(out, "Dynamic payload", dynamicPayload);
        appendFileState(out, "Packaged glibc loader", loader);
        appendFileState(out, "Packaged glibc libc", libc);
        appendFileState(out, "Static syscall probe", probe);
        appendFileState(out, "Dynamic musl payload", muslPayload);
        appendFileState(out, "Packaged musl loader", muslLoader);
        appendFileState(out, "Dynamic musl payload with shared lib", muslWithLibPayload);
        appendFileState(out, "Packaged musl shared library", muslGreetLib);
        appendFileState(out, "Linux permission request payload", permissionRequestPayload);
        appendFileState(out, "Linux file request payload", fileRequestPayload);
        appendFileState(out, "Linux create document payload", createDocumentPayload);
        appendFileState(out, "Linux tree background payload", treeBackgroundPayload);
        appendFileState(out, "Linux private HOME payload", privateHomePayload);
        appendFileState(out, "Linux split storage payload", splitStoragePayload);

        out.append(runLs(
                staticPayload,
                dynamicPayload,
                loader,
                libc,
                probe,
                muslPayload,
                muslLoader,
                muslWithLibPayload,
                muslGreetLib,
                permissionRequestPayload,
                fileRequestPayload,
                createDocumentPayload,
                treeBackgroundPayload,
                privateHomePayload,
                splitStoragePayload));
        out.append("\n");
        out.append(runNamed("Static direct ELF launch", new String[] {staticPayload.getAbsolutePath()}));
        out.append("\n");
        out.append(runNamed("Dynamic direct ELF launch", new String[] {dynamicPayload.getAbsolutePath()}));
        out.append("\n");
        out.append(runNamed("Dynamic musl direct ELF launch", new String[] {muslPayload.getAbsolutePath()}));
        out.append("\n");
        out.append(runNamed("Dynamic packaged musl loader launch", new String[] {muslLoader.getAbsolutePath(), muslPayload.getAbsolutePath()}));
        out.append("\n");
        out.append(runNamed("Dynamic musl with shared library launch", new String[] {muslLoader.getAbsolutePath(), muslWithLibPayload.getAbsolutePath()}));
        out.append("\n");
        out.append(runNamed("Packaged glibc loader help", new String[] {loader.getAbsolutePath(), "--help"}));
        out.append("\n");
        out.append(runNamed("Packaged glibc loader verify", new String[] {loader.getAbsolutePath(), "--verify", dynamicPayload.getAbsolutePath()}));
        out.append("\n");
        out.append(runNamed("Packaged glibc loader list", new String[] {loader.getAbsolutePath(), "--list", dynamicPayload.getAbsolutePath()}));
        out.append("\n");
        for (String probeName : new String[] {"rseq", "statx", "clone3", "pidfd_open", "openat2"}) {
            out.append(runNamed("Static syscall probe " + probeName, new String[] {probe.getAbsolutePath(), probeName}));
            out.append("\n");
        }
        out.append(runNamed(
                "Dynamic packaged glibc loader launch",
                new String[] {
                    loader.getAbsolutePath(),
                    "--library-path",
                    libDir.getAbsolutePath(),
                    dynamicPayload.getAbsolutePath()
                }));
        out.append("\n");
        out.append(runNamed(
                "Dynamic glibc via Android shell launch",
                new String[] {
                    "/system/bin/sh",
                    "-c",
                    loader.getAbsolutePath() + " --library-path " + libDir.getAbsolutePath() + " " + dynamicPayload.getAbsolutePath()
                }));
        return out.toString();
    }

    private void startPermissionBridgeSession() {
        if (permissionBridgeStarted) {
            return;
        }
        permissionBridgeStarted = true;
        File payload = new File(getApplicationInfo().nativeLibraryDir, PERMISSION_REQUEST_PAYLOAD);
        appendBridgeLog("Starting permission bridge session: " + payload.getAbsolutePath(), true);

        Thread thread = new Thread(() -> runBridgeProcess(payload, "permission"), "archphene-permission-bridge");
        thread.start();
    }

    private void startFileBridgeSession() {
        if (fileBridgeStarted) {
            return;
        }
        fileBridgeStarted = true;
        File payload = new File(getApplicationInfo().nativeLibraryDir, FILE_REQUEST_PAYLOAD);
        appendBridgeLog("Starting file portal bridge session: " + payload.getAbsolutePath(), true);

        Thread thread = new Thread(() -> runBridgeProcess(payload, "file"), "archphene-file-bridge");
        thread.start();
    }
    private void startCreateDocumentBridgeSession() {
        if (createDocumentBridgeStarted) {
            return;
        }
        createDocumentBridgeStarted = true;
        File payload = new File(getApplicationInfo().nativeLibraryDir, CREATE_DOCUMENT_PAYLOAD);
        appendBridgeLog("Starting create document bridge session: " + payload.getAbsolutePath(), true);

        Thread thread = new Thread(() -> runBridgeProcess(payload, "create-document"), "archphene-create-document-bridge");
        thread.start();
    }
    private void startTreeBackgroundBridgeSession() {
        if (treeBackgroundBridgeStarted) {
            return;
        }
        treeBackgroundBridgeStarted = true;
        File payload = new File(getApplicationInfo().nativeLibraryDir, TREE_BACKGROUND_PAYLOAD);
        appendBridgeLog("Starting tree background bridge session: " + payload.getAbsolutePath(), true);

        Thread thread = new Thread(() -> runBridgeProcess(payload, "tree-background"), "archphene-tree-background-bridge");
        thread.start();
    }

    private void startSplitStorageBridgeSession() {
        if (splitStorageBridgeStarted) {
            return;
        }
        splitStorageBridgeStarted = true;
        File payload = new File(getApplicationInfo().nativeLibraryDir, SPLIT_STORAGE_PAYLOAD);
        appendBridgeLog("Starting split storage bridge session: " + payload.getAbsolutePath(), true);

        Thread thread = new Thread(() -> runBridgeProcess(payload, "split-storage"), "archphene-split-storage-bridge");
        thread.start();
    }
    private void startPrivateHomeBridgeSession() {
        if (privateHomeBridgeStarted) {
            return;
        }
        privateHomeBridgeStarted = true;
        File payload = new File(getApplicationInfo().nativeLibraryDir, PRIVATE_HOME_PAYLOAD);
        appendBridgeLog("Starting private HOME bridge session: " + payload.getAbsolutePath(), true);

        Thread thread = new Thread(() -> runBridgeProcess(payload, "private-home"), "archphene-private-home-bridge");
        thread.start();
    }

    private void runBridgeProcess(File payload, String bridgeName) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(payload.getAbsolutePath());
            builder.environment().put("LD_LIBRARY_PATH", payload.getParent());
            configurePrivateLinuxEnvironment(builder);
            process = builder.start();
            synchronized (bridgeLock) {
                pendingBridgeInput = process.getOutputStream();
            }
            appendBridgeLog(bridgeName + " bridge process started.", false);

            try (BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stdout.readLine()) != null) {
                    appendBridgeLog(bridgeName + " linux stdout: " + line, false);
                    if (line.startsWith(BRIDGE_JSON_PREFIX)) {
                        handleBridgeRequest(line.substring(BRIDGE_JSON_PREFIX.length()));
                    }
                }
            }

            int exit = process.waitFor();
            String stderr = readFully(process.getErrorStream());
            appendBridgeLog(bridgeName + " bridge process exit code: " + exit, false);
            if (!stderr.isEmpty()) {
                appendBridgeLog(bridgeName + " linux stderr: " + stderr, false);
            }
        } catch (Exception e) {
            appendBridgeLog(bridgeName + " bridge process error: " + e, false);
        } finally {
            synchronized (bridgeLock) {
                pendingBridgeInput = null;
            }
            refreshBody();
            if ("split-storage".equals(bridgeName)) {
                splitStorageStatus = "Split-storage payload completed; see bridge log for private and project readback.";
            } else if ("permission".equals(bridgeName)) {
                startFileBridgeSession();
            } else if ("file".equals(bridgeName)) {
                startCreateDocumentBridgeSession();
            } else if ("create-document".equals(bridgeName)) {
                startTreeBackgroundBridgeSession();
            } else if ("tree-background".equals(bridgeName)) {
                startSplitStorageBridgeSession();
            } else if ("private-home".equals(bridgeName)) {
                privateHomeStatus = "Private HOME payload completed; see bridge log for readback.";
                if (!permissionBridgeStarted) {
                    startPermissionBridgeSession();
                }
            }
        }
    }

    private void configurePrivateLinuxEnvironment(ProcessBuilder builder) {
        File home = new File(getFilesDir(), "linux-home");
        File cache = new File(home, ".cache");
        File config = new File(home, ".config");
        File tmp = new File(getCacheDir(), "linux-tmp");
        home.mkdirs();
        cache.mkdirs();
        config.mkdirs();
        tmp.mkdirs();
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("XDG_CACHE_HOME", cache.getAbsolutePath());
        builder.environment().put("XDG_CONFIG_HOME", config.getAbsolutePath());
        builder.environment().put("TMPDIR", tmp.getAbsolutePath());
    }

    private void handleBridgeRequest(String requestJson) {
        try {
            JSONObject request = new JSONObject(requestJson);
            String requestId = request.optString("id", "");
            String type = request.optString("type", "");
            String permission = request.optString("permission", "");
            String mime = request.optString("mime", "");
            String displayName = request.optString("display_name", "");
            String text = request.optString("text", "");
            String relativePath = request.optString("relative_path", "");
            synchronized (bridgeLock) {
                pendingBridgeRequestId = requestId;
            }
            appendBridgeLog("Bridge parsed framed request id=" + requestId + " json=" + requestJson, false);
            if ("permission.request".equals(type)
                    && Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
                handleNotificationPermissionRequest(requestId);
                return;
            }
            if ("file.open_document".equals(type) && "text/plain".equals(mime)) {
                handleOpenDocumentRequest(requestId);
                return;
            }
            if ("file.create_document".equals(type) && "text/plain".equals(mime)) {
                handleCreateDocumentRequest(requestId, mime, displayName, text);
                return;
            }
            if ("file.open_tree".equals(type)) {
                handleOpenTreeRequest(requestId);
                return;
            }
            if ("tree.write_file".equals(type)) {
                handleTreeWriteRequest(requestId, relativePath, mime, text);
                return;
            }
            if ("tree.read_file".equals(type)) {
                handleTreeReadRequest(requestId, relativePath);
                return;
            }
            sendBridgeLine(buildErrorResponse(requestId, "unsupported_request"));
        } catch (Exception e) {
            appendBridgeLog("Bridge request JSON parse failed: " + e, false);
            sendBridgeLine(buildErrorResponse("", "invalid_json"));
        }
    }

    private void handleNotificationPermissionRequest(String requestId) {
        runOnUiThread(() -> {
            appendBridgeLog("Bridge parsed Android permission request: POST_NOTIFICATIONS", false);
            if (Build.VERSION.SDK_INT < 33) {
                sendBridgeLine(buildPermissionResponse(requestId, true, "not_runtime_gated"));
                refreshBody();
                return;
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                appendBridgeLog("Bridge response path: permission already granted.", false);
                sendBridgeLine(buildPermissionResponse(requestId, true, "already_granted"));
                refreshBody();
                return;
            }
            permissionCallbackStatus = "Android permission dialog launched for POST_NOTIFICATIONS.";
            appendBridgeLog(permissionCallbackStatus, false);
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            refreshBody();
        });
    }

    private void handleOpenDocumentRequest(String requestId) {
        runOnUiThread(() -> {
            synchronized (bridgeLock) {
                pendingBridgeRequestId = requestId;
            }
            appendBridgeLog("Bridge parsed Android file portal request: open_document text/plain", false);
            filePortalStatus = "Android file portal launched ACTION_OPEN_DOCUMENT.";
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                startActivityForResult(intent, REQUEST_OPEN_DOCUMENT);
            } catch (Exception e) {
                filePortalStatus = "Android file portal launch failed.";
                appendBridgeLog(filePortalStatus + " " + e, false);
                sendBridgeLine(buildFileResponse(requestId, false, "launch_failed", "", e.toString()));
            }
            refreshBody();
        });
    }
    private void handleCreateDocumentRequest(String requestId, String mime, String displayName, String text) {
        runOnUiThread(() -> {
            synchronized (bridgeLock) {
                pendingBridgeRequestId = requestId;
                pendingCreateDocumentText = text;
            }
            appendBridgeLog("Bridge parsed Android file portal request: create_document " + mime, false);
            createDocumentStatus = "Android create document portal launched ACTION_CREATE_DOCUMENT.";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_TITLE, displayName.isEmpty() ? "archphene-created-document.txt" : displayName);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                startActivityForResult(intent, REQUEST_CREATE_DOCUMENT);
            } catch (Exception e) {
                createDocumentStatus = "Android create document portal launch failed.";
                appendBridgeLog(createDocumentStatus + " " + e, false);
                sendBridgeLine(buildCreateDocumentResponse(requestId, false, "launch_failed", "", e.toString()));
            }
            refreshBody();
        });
    }
    private void handleOpenTreeRequest(String requestId) {
        runOnUiThread(() -> {
            synchronized (bridgeLock) {
                pendingBridgeRequestId = requestId;
            }
            appendBridgeLog("Bridge parsed Android file portal request: open_tree", false);
            treeGrantStatus = "Android tree grant portal launched ACTION_OPEN_DOCUMENT_TREE.";
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                startActivityForResult(intent, REQUEST_OPEN_TREE);
            } catch (Exception e) {
                treeGrantStatus = "Android tree grant portal launch failed.";
                appendBridgeLog(treeGrantStatus + " " + e, false);
                sendBridgeLine(buildTreeResponse(requestId, false, "launch_failed", "", e.toString()));
            }
            refreshBody();
        });
    }

    private void handleTreeWriteRequest(String requestId, String relativePath, String mime, String text) {
        runOnUiThread(() -> {
            appendBridgeLog("Bridge parsed background tree write request: " + relativePath, false);
            try {
                Uri uri = writeTreeFile(relativePath, mime.isEmpty() ? "text/plain" : mime, text);
                sendBridgeLine(buildTreeResponse(requestId, true, "persisted_tree_write", uri.toString(), ""));
            } catch (Exception e) {
                sendBridgeLine(buildTreeResponse(requestId, false, "tree_write_failed", "", e.toString()));
            }
            refreshBody();
        });
    }

    private void handleTreeReadRequest(String requestId, String relativePath) {
        runOnUiThread(() -> {
            appendBridgeLog("Bridge parsed background tree read request: " + relativePath, false);
            try {
                Uri uri = findRequiredTreeChild(relativePath);
                String text = readContentUri(uri, 4096);
                sendBridgeLine(buildTreeResponse(requestId, true, "persisted_tree_read", uri.toString(), text));
            } catch (Exception e) {
                sendBridgeLine(buildTreeResponse(requestId, false, "tree_read_failed", "", e.toString()));
            }
            refreshBody();
        });
    }

    private String buildPermissionResponse(String requestId, boolean granted, String reason) {
        try {
            JSONObject response = new JSONObject();
            response.put("id", requestId);
            response.put("type", "permission.result");
            response.put("permission", Manifest.permission.POST_NOTIFICATIONS);
            response.put("granted", granted);
            response.put("reason", reason);
            return response.toString();
        } catch (Exception e) {
            return buildErrorResponse(requestId, "json_build_failed");
        }
    }

    private String buildFileResponse(String requestId, boolean granted, String reason, String uri, String text) {
        return buildFileResponseWithPortal(requestId, "android.saf.open_document", granted, reason, uri, text);
    }

    private String buildCreateDocumentResponse(String requestId, boolean granted, String reason, String uri, String text) {
        return buildFileResponseWithPortal(requestId, "android.saf.create_document", granted, reason, uri, text);
    }

    private String buildTreeResponse(String requestId, boolean granted, String reason, String uri, String text) {
        return buildFileResponseWithPortal(requestId, "android.saf.tree", granted, reason, uri, text);
    }

    private String buildFileResponseWithPortal(String requestId, String portal, boolean granted, String reason, String uri, String text) {
        try {
            JSONObject response = new JSONObject();
            response.put("id", requestId);
            response.put("type", "file.result");
            response.put("portal", portal);
            response.put("granted", granted);
            response.put("reason", reason);
            response.put("uri", uri);
            response.put("text", text);
            return response.toString();
        } catch (Exception e) {
            return buildErrorResponse(requestId, "json_build_failed");
        }
    }

    private String buildErrorResponse(String requestId, String reason) {
        try {
            JSONObject response = new JSONObject();
            response.put("id", requestId);
            response.put("type", "error");
            response.put("granted", false);
            response.put("reason", reason);
            return response.toString();
        } catch (Exception e) {
            return "{\"id\":\"\",\"type\":\"error\",\"granted\":false,\"reason\":\"json_build_failed\"}";
        }
    }

    private void sendBridgeLine(String line) {
        OutputStream input;
        synchronized (bridgeLock) {
            input = pendingBridgeInput;
        }
        if (input == null) {
            appendBridgeLog("Bridge response write failed: no pending Linux stdin.", true);
            return;
        }
        try {
            input.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
            appendBridgeLog("Bridge wrote Linux response: " + line, false);
        } catch (Exception e) {
            appendBridgeLog("Bridge response write failed: " + e, false);
        }
    }

    private void appendBridgeLog(String line, boolean refresh) {
        android.util.Log.i("ArchpheneWrapper", line);
        synchronized (bridgeLock) {
            bridgeLog.append(line).append("\n");
        }
        if (refresh) {
            refreshBody();
        }
    }

    private void refreshBody() {
        runOnUiThread(() -> {
            if (bodyView != null) {
                bodyView.setText(renderBody());
            }
        });
    }

    private String getPostNotificationPermissionStatus() {
        if (Build.VERSION.SDK_INT < 33) {
            return "not runtime-gated on this Android version";
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ? "granted"
                : "not granted";
    }

    private Uri requirePersistedTreeUri() throws Exception {
        if (!persistedTreeUri.isEmpty()) {
            return Uri.parse(persistedTreeUri);
        }
        for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.isWritePermission()) {
                persistedTreeUri = permission.getUri().toString();
                return permission.getUri();
            }
        }
        throw new IllegalStateException("No persisted tree grant available");
    }

    private Uri writeTreeFile(String relativePath, String mime, String text) throws Exception {
        Uri treeUri = requirePersistedTreeUri();
        String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
        Uri child = findTreeChild(treeUri, rootDocumentId, relativePath);
        if (child == null) {
            child = DocumentsContract.createDocument(getContentResolver(), rootDocumentUri, mime, relativePath);
        }
        if (child == null) {
            throw new IllegalStateException("DocumentsContract.createDocument returned null");
        }
        try (OutputStream output = getContentResolver().openOutputStream(child, "wt")) {
            if (output == null) {
                throw new IllegalStateException("ContentResolver returned null output stream");
            }
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return child;
    }

    private Uri findRequiredTreeChild(String relativePath) throws Exception {
        Uri treeUri = requirePersistedTreeUri();
        String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri child = findTreeChild(treeUri, rootDocumentId, relativePath);
        if (child == null) {
            throw new IllegalStateException("Tree child not found: " + relativePath);
        }
        return child;
    }

    private Uri findTreeChild(Uri treeUri, String parentDocumentId, String displayName) throws Exception {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                String childDocumentId = cursor.getString(0);
                String childDisplayName = cursor.getString(1);
                if (displayName.equals(childDisplayName)) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId);
                }
            }
        }
        return null;
    }
    private String readContentUri(Uri uri, int maxBytes) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("ContentResolver returned null input stream");
            }
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
        }
    }

    private static void appendFileState(StringBuilder out, String label, File file) {
        out.append(label).append("\n");
        out.append("Path: ").append(file.getAbsolutePath()).append("\n");
        out.append("Exists: ").append(file.exists()).append("\n");
        out.append("Length: ").append(file.length()).append("\n");
        out.append("canExecute: ").append(file.canExecute()).append("\n\n");
    }

    private static String runLs(File... files) {
        String[] command = new String[files.length + 2];
        command[0] = "/system/bin/ls";
        command[1] = "-lZ";
        for (int i = 0; i < files.length; i++) {
            command[i + 2] = files[i].getAbsolutePath();
        }
        return runNamed("Install path labels", command);
    }

    private static String runNamed(String label, String[] command) {
        try {
            Result result = run(command);
            return label + "\n\n" + formatCommandResult(command, result);
        } catch (Exception e) {
            return label + " failed:\n" + e + "\n";
        }
    }

    private static String formatCommandResult(String[] command, Result result) {
        return "Command: " + Arrays.toString(command) + "\n"
                + "Exit code: " + result.exitCode + "\n"
                + "Timed out: " + result.timedOut + "\n"
                + "Stdout:\n" + result.stdout
                + "Stderr:\n" + result.stderr
                + "Start error: " + result.startError + "\n";
    }

    private static Result run(String[] command) throws Exception {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
            String parent = new File(command[0]).getParent();
            if (parent != null) {
                builder.environment().put("LD_LIBRARY_PATH", parent);
            }
            process = builder.start();
        } catch (Exception e) {
            return new Result(-127, false, "", "", e.toString());
        }

        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        String stdout = readFully(process.getInputStream());
        String stderr = readFully(process.getErrorStream());
        if (!finished) {
            process.destroyForcibly();
            return new Result(-1, true, stdout, stderr, "");
        }
        return new Result(process.exitValue(), false, stdout, stderr, "");
    }

    private static String readFully(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
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





