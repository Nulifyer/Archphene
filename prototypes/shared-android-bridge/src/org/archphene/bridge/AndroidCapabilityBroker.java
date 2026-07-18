package org.archphene.bridge;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.print.PrintManager;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded, same-UID IPC from a glibc process to explicit Android APIs. */
final class AndroidCapabilityBroker implements Closeable {
    private static final String TAG = "ArchpheneCapabilities";
    private static final String PROTOCOL = "ARCHPHENE/1";
    private static final String CHANNEL_ID = "linux-app";
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int MAX_URI_BYTES = 4096;
    private static final int MAX_ID_BYTES = 128;
    private static final int MAX_TITLE_BYTES = 256;
    private static final int MAX_BODY_BYTES = 8192;
    private static final int SOCKET_TIMEOUT_MILLIS = 5000;
    private static final int UI_TIMEOUT_SECONDS = 15;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 0x4150;
    private static final int MICROPHONE_PERMISSION_REQUEST = 0x4151;
    private static final int CAMERA_PERMISSION_REQUEST = 0x4152;
    private static final int MAX_PENDING_NOTIFICATIONS = 32;
    private static final int MAX_PENDING_PRINTS = 4;
    private static final long MAX_PRINT_BYTES = 256L * 1024 * 1024;

    private final Activity activity;
    private final AndroidCameraIntegration cameraIntegration;
    private final ArchpheneAccessibilityBridge accessibilityBridge;
    private final AndroidSecretStore secretStore;
    private final Set<String> capabilities;
    private final AtomicBoolean running = new AtomicBoolean();
    private LocalServerSocket server;
    private Thread thread;
    private String socketName;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(
            0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final Map<String, PendingNotification> pendingNotifications =
            new LinkedHashMap<>();
    private boolean notificationPermissionRequestInFlight;
    private boolean microphonePermissionRequestInFlight;
    private boolean cameraPermissionRequestInFlight;
    private static final Set<File> ACTIVE_PRINT_FILES = new java.util.HashSet<>();

    private static final class PendingNotification {
        final String id;
        final String title;
        final String body;

        PendingNotification(String id, String title, String body) {
            this.id = id;
            this.title = title;
            this.body = body;
        }
    }

    AndroidCapabilityBroker(Activity activity, Set<String> capabilities) {
        this(activity, capabilities, null);
    }

    AndroidCapabilityBroker(Activity activity, Set<String> capabilities,
            ArchpheneAccessibilityBridge accessibilityBridge) {
        this.activity = activity;
        this.capabilities = capabilities;
        this.accessibilityBridge = accessibilityBridge;
        cameraIntegration = new AndroidCameraIntegration(activity);
        secretStore = new AndroidSecretStore(activity.getFilesDir());
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Android capability broker already started");
        }
        cleanupStalePrintFiles();
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        socketName = "archphene-" + Process.myUid() + "-"
                + Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP
                        | Base64.NO_PADDING);
        server = new LocalServerSocket(socketName);
        thread = new Thread(this::acceptLoop, "archphene-capability-broker");
        thread.start();
        if ((activity.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.i(TAG, "Capability broker ready abstract=" + socketName);
        }
    }

    String socketName() {
        if (!running.get() || socketName == null) {
            throw new IllegalStateException("Android capability broker is not running");
        }
        return socketName;
    }

    private void acceptLoop() {
        while (running.get()) {
            LocalServerSocket current = server;
            if (current == null) break;
            LocalSocket client = null;
            try {
                client = current.accept();
                client.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                Credentials peer = client.getPeerCredentials();
                if (peer == null || peer.getUid() != Process.myUid()) {
                    Log.w(TAG, "Rejected capability broker peer uid="
                            + (peer == null ? -1 : peer.getUid()));
                    writeResponse(client, "ERROR\tUNAUTHORIZED");
                    client.close();
                    continue;
                }
                LocalSocket accepted = client;
                clients.execute(() -> handleClient(accepted));
                client = null;
            } catch (RejectedExecutionException error) {
                if (client != null) {
                    try {
                        writeResponse(client, "ERROR\tBUSY");
                        client.close();
                    } catch (IOException ignored) {
                        // The rejected client may already have disconnected.
                    }
                }
            } catch (IOException error) {
                if (client != null) {
                    try {
                        client.close();
                    } catch (IOException ignored) {}
                }
                if (running.get()) Log.e(TAG, "Capability broker socket failed", error);
            }
        }
    }

    private void handleClient(LocalSocket accepted) {
        try (LocalSocket client = accepted) {
            String response;
            FileDescriptor[] descriptors = null;
            try {
                String request = readRequest(client.getInputStream());
                descriptors = client.getAncillaryFileDescriptors();
                response = handle(request, descriptors);
            } catch (Exception error) {
                Log.w(TAG, "Rejected Android capability request: "
                        + safeMessage(error));
                response = "ERROR\tINVALID_REQUEST";
            } finally {
                closeDescriptors(descriptors);
            }
            writeResponse(client, response);
        } catch (IOException error) {
            if (running.get()) Log.d(TAG, "Capability client disconnected");
        }
    }
    private String handle(String request, FileDescriptor[] descriptors) throws Exception {
        String[] fields = request.split("\\t", -1);
        if (fields.length < 2 || !PROTOCOL.equals(fields[0])) {
            throw new IllegalArgumentException("Unsupported capability protocol");
        }
        if (!"PRINT_PDF".equals(fields[1]) && !"CAPTURE_CAMERA_JPEG".equals(fields[1])
                && !"STREAM_CAMERA_I420".equals(fields[1])
                && !"PUBLISH_ACCESSIBILITY_TREE".equals(fields[1])
                && !"STORE_SECRET".equals(fields[1])
                && !"READ_SECRET".equals(fields[1])
                && !"LIST_SECRETS".equals(fields[1])
                && !"CATALOG_SECRETS".equals(fields[1])
                && descriptors != null && descriptors.length != 0) {
            throw new IllegalArgumentException("Unexpected capability descriptors");
        }
        switch (fields[1]) {
            case "OPEN_URI":
                requireFields(fields, 3);
                requireCapability(BridgeCapabilities.OPEN_URI);
                openUri(decode(fields[2], MAX_URI_BYTES));
                return "OK";
            case "NOTIFY":
                requireFields(fields, 5);
                requireCapability(BridgeCapabilities.NOTIFICATIONS);
                return notifyLinuxApp(
                        decode(fields[2], MAX_ID_BYTES),
                        decode(fields[3], MAX_TITLE_BYTES),
                        decode(fields[4], MAX_BODY_BYTES));
            case "WITHDRAW_NOTIFICATION":
                requireFields(fields, 3);
                requireCapability(BridgeCapabilities.NOTIFICATIONS);
                withdrawNotification(decode(fields[2], MAX_ID_BYTES));
                return "OK";
            case "PRINT_PDF":
                requireFields(fields, 3);
                requireCapability(BridgeCapabilities.PRINTING);
                printPdf(decode(fields[2], MAX_TITLE_BYTES), descriptors);
                return "OK";
            case "REQUEST_AUDIO_INPUT":
                requireFields(fields, 2);
                requireCapability(BridgeCapabilities.AUDIO_INPUT);
                return requestAudioInput();
            case "CHECK_AUDIO_INPUT":
                requireFields(fields, 2);
                requireCapability(BridgeCapabilities.AUDIO_INPUT);
                return audioInputPermissionState();
            case "REQUEST_CAMERA":
                requireFields(fields, 2);
                requireCapability(BridgeCapabilities.CAMERA);
                return requestCamera();
            case "CHECK_CAMERA":
                requireFields(fields, 2);
                requireCapability(BridgeCapabilities.CAMERA);
                return cameraPermissionState();
            case "CAPTURE_CAMERA_JPEG":
                requireFields(fields, 5);
                requireCapability(BridgeCapabilities.CAMERA);
                return captureCameraJpeg(fields[2], fields[3], fields[4], descriptors);
            case "STREAM_CAMERA_I420":
                requireFields(fields, 5);
                requireCapability(BridgeCapabilities.CAMERA);
                return streamCameraI420(fields[2], fields[3], fields[4], descriptors);
            case "PUBLISH_ACCESSIBILITY_TREE":
                requireFields(fields, 2);
                requireCapability(BridgeCapabilities.ACCESSIBILITY);
                requireAccessibilityBridge().publish(requireRegularDescriptor(
                        descriptors, "Accessibility tree"));
                return "OK";
            case "ACCESSIBILITY_EVENT":
                requireFields(fields, 4);
                requireCapability(BridgeCapabilities.ACCESSIBILITY);
                requireAccessibilityBridge().sendNamedEvent(
                        parseBoundedInt(fields[2], 0, 1_000_000, "accessibility node"),
                        fields[3]);
                return "OK";
            case "TAKE_ACCESSIBILITY_ACTION":
                requireFields(fields, 3);
                requireCapability(BridgeCapabilities.ACCESSIBILITY);
                return requireAccessibilityBridge().takeAction(
                        parseBoundedInt(fields[2], 0, 250, "accessibility timeout"));
            case "ACCESSIBILITY_MENU_FALLBACK":
                requireFields(fields, 3);
                requireCapability(BridgeCapabilities.ACCESSIBILITY);
                requireAccessibilityBridge().activateMenuFallback(
                        parseBoundedInt(fields[2], 1, 1_000_000, "accessibility node"), false);
                return "OK";
            case "ACCESSIBILITY_MENU_ACTION":
                requireFields(fields, 4);
                requireCapability(BridgeCapabilities.ACCESSIBILITY);
                requireAccessibilityBridge().activateMenuFallback(
                        parseBoundedInt(fields[2], 1, 1_000_000, "accessibility node"),
                        parseBoundedInt(fields[3], 0, 1, "menu transition") == 1);
                return "OK";
            case "STORE_SECRET":
                if (fields.length != 5 && fields.length != 6) {
                    throw new IllegalArgumentException("STORE_SECRET field count is invalid");
                }
                requireCapability(BridgeCapabilities.SECRETS);
                secretStore.store(
                        decode(fields[2], MAX_ID_BYTES),
                        decodeAllowEmpty(fields[3], MAX_TITLE_BYTES),
                        decode(fields[4], 8 * 1024),
                        fields.length == 6 ? decode(fields[5], 512) : "text/plain",
                        requireRegularDescriptor(descriptors, "Secret input"));
                return "OK";
            case "READ_SECRET": {
                requireFields(fields, 3);
                requireCapability(BridgeCapabilities.SECRETS);
                AndroidSecretStore.ReadResult result = secretStore.read(
                        decode(fields[2], MAX_ID_BYTES),
                        requireRegularDescriptor(descriptors, "Secret output"));
                if (result == null) return "ERROR\tNOT_FOUND";
                return "OK\t" + encode(result.label) + "\t"
                        + encode(result.attributes) + "\t" + result.secretBytes;
            }
            case "DELETE_SECRET":
                requireFields(fields, 3);
                requireCapability(BridgeCapabilities.SECRETS);
                if (!secretStore.delete(decode(fields[2], MAX_ID_BYTES))) {
                    throw new IOException("Could not delete secret record");
                }
                return "OK";
            case "LIST_SECRETS":
                requireFields(fields, 2);
                requireCapability(BridgeCapabilities.SECRETS);
                return "OK\t" + secretStore.list(
                        requireRegularDescriptor(descriptors, "Secret index output"));
            case "CATALOG_SECRETS":
                requireFields(fields, 2);
                requireCapability(BridgeCapabilities.SECRETS);
                return "OK\t" + secretStore.catalog(
                        requireRegularDescriptor(descriptors, "Secret catalog output"));
            default:
                throw new IllegalArgumentException("Unknown capability request");
        }
    }

    private ArchpheneAccessibilityBridge requireAccessibilityBridge() {
        if (accessibilityBridge == null) {
            throw new IllegalStateException("Android accessibility bridge is unavailable");
        }
        return accessibilityBridge;
    }

    private static FileDescriptor requireRegularDescriptor(
            FileDescriptor[] descriptors, String label) throws Exception {
        if (descriptors == null || descriptors.length != 1 || !descriptors[0].valid()) {
            throw new IllegalArgumentException(label + " requires one descriptor");
        }
        android.system.StructStat stat = Os.fstat(descriptors[0]);
        if ((stat.st_mode & OsConstants.S_IFMT) != OsConstants.S_IFREG) {
            throw new IllegalArgumentException(label + " requires a regular file");
        }
        return descriptors[0];
    }

    private static int parseBoundedInt(String value, int minimum, int maximum, String label) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " is invalid", error);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(label + " is out of range");
        }
        return parsed;
    }

    private void printPdf(String title, FileDescriptor[] descriptors) throws Exception {
        validateText(title, "print title", false);
        if (descriptors == null || descriptors.length != 1 || !descriptors[0].valid()) {
            throw new IllegalArgumentException("Printing requires exactly one PDF descriptor");
        }
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PRINTING)) {
            throw new UnsupportedOperationException("Android printing is unavailable");
        }
        android.system.StructStat stat = Os.fstat(descriptors[0]);
        if ((stat.st_mode & OsConstants.S_IFMT) != OsConstants.S_IFREG) {
            throw new IllegalArgumentException("Printing requires a regular PDF file");
        }
        if (stat.st_size < 5 || stat.st_size > MAX_PRINT_BYTES) {
            throw new IllegalArgumentException("Print PDF size is invalid");
        }
        File directory = new File(activity.getCacheDir(), "print");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create private print directory");
        }
        File document = new File(directory, UUID.randomUUID() + ".pdf").getCanonicalFile();
        if (!directory.getCanonicalFile().equals(document.getParentFile())) {
            throw new SecurityException("Invalid private print path");
        }
        boolean accepted = false;
        try {
            synchronized (ACTIVE_PRINT_FILES) {
                if (ACTIVE_PRINT_FILES.size() >= MAX_PENDING_PRINTS) {
                    throw new IllegalStateException("Too many pending print jobs");
                }
                ACTIVE_PRINT_FILES.add(document);
            }
            copyPdf(descriptors[0], document);
            runOnUiThread(() -> {
                PrintManager manager = activity.getSystemService(PrintManager.class);
                if (manager == null || manager.print(title,
                        new AndroidPdfPrintAdapter(document, title,
                                () -> finishPrint(document)), null) == null) {
                    throw new IllegalStateException("Android print service unavailable");
                }
            });
            accepted = true;
            Log.i(TAG, "Opened Android print UI bytes=" + document.length());
        } finally {
            if (!accepted) finishPrint(document);
        }
    }

    private static void copyPdf(FileDescriptor descriptor, File target) throws IOException {
        byte[] header = new byte[5];
        long total = 0;
        try (FileInputStream input = new FileInputStream(descriptor);
                FileOutputStream output = new FileOutputStream(target, false)) {
            int headerRead = input.read(header);
            if (headerRead != header.length
                    || header[0] != '%' || header[1] != 'P' || header[2] != 'D'
                    || header[3] != 'F' || header[4] != '-') {
                throw new IOException("Print document is not a PDF");
            }
            output.write(header);
            total = header.length;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                try {
                    total = Math.addExact(total, read);
                } catch (ArithmeticException overflow) {
                    throw new IOException("Print PDF size overflow", overflow);
                }
                if (total > MAX_PRINT_BYTES) throw new IOException("Print PDF exceeds size limit");
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static void finishPrint(File document) {
        synchronized (ACTIVE_PRINT_FILES) {
            ACTIVE_PRINT_FILES.remove(document);
        }
        if (document.exists() && !document.delete()) {
            Log.w(TAG, "Could not delete private print document");
        }
    }

    private static void closeDescriptors(FileDescriptor[] descriptors) {
        if (descriptors == null) return;
        for (FileDescriptor descriptor : descriptors) {
            if (descriptor == null || !descriptor.valid()) continue;
            try {
                Os.close(descriptor);
            } catch (Exception ignored) {}
        }
    }

    private void cleanupStalePrintFiles() throws IOException {
        File directory = new File(activity.getCacheDir(), "print").getCanonicalFile();
        File[] files = directory.listFiles();
        if (files == null) return;
        synchronized (ACTIVE_PRINT_FILES) {
            for (File file : files) {
                File canonical = file.getCanonicalFile();
                if (!directory.equals(canonical.getParentFile())
                        || !canonical.isFile() || !canonical.getName().endsWith(".pdf")
                        || ACTIVE_PRINT_FILES.contains(canonical)) {
                    continue;
                }
                if (!canonical.delete()) {
                    Log.w(TAG, "Could not delete stale private print document");
                }
            }
        }
    }

    private void requireCapability(String capability) {
        if (!capabilities.contains(capability)) {
            throw new SecurityException("Undeclared Android capability: " + capability);
        }
    }

    private void openUri(String value) throws Exception {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("URI contains control characters");
        }
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getUserInfo() != null) {
            throw new SecurityException("Only ordinary HTTP(S) URIs are supported");
        }
        runOnUiThread(() -> activity.startActivity(
                new Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE)));
        Log.i(TAG, "Opened Android URI scheme=" + scheme.toLowerCase(java.util.Locale.ROOT));
    }

    private String requestAudioInput() throws Exception {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return "OK";
        }
        synchronized (this) {
            if (microphonePermissionRequestInFlight) {
                return "ERROR\tPERMISSION_REQUESTED";
            }
            if (activity.getPreferences(Activity.MODE_PRIVATE)
                    .getBoolean("microphone_permission_requested", false)) {
                return "ERROR\tPERMISSION_DENIED";
            }
            microphonePermissionRequestInFlight = true;
        }
        activity.getPreferences(Activity.MODE_PRIVATE).edit()
                .putBoolean("microphone_permission_requested", true).apply();
        try {
            runOnUiThread(() -> activity.requestPermissions(
                    new String[] {Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST));
        } catch (Exception error) {
            synchronized (this) {
                microphonePermissionRequestInFlight = false;
            }
            activity.getPreferences(Activity.MODE_PRIVATE).edit()
                    .putBoolean("microphone_permission_requested", false).apply();
            throw error;
        }
        Log.i(TAG, "Requested Android microphone permission for Linux audio input");
        return "ERROR\tPERMISSION_REQUESTED";
    }

    private String audioInputPermissionState() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return "OK";
        }
        synchronized (this) {
            if (microphonePermissionRequestInFlight) {
                return "ERROR\tPERMISSION_REQUESTED";
            }
        }
        return activity.getPreferences(Activity.MODE_PRIVATE)
                .getBoolean("microphone_permission_requested", false)
                ? "ERROR\tPERMISSION_DENIED" : "ERROR\tPERMISSION_NOT_REQUESTED";
    }
    private String requestCamera() throws Exception {
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return "ERROR\tUNAVAILABLE";
        }
        if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            return "OK";
        }
        synchronized (this) {
            if (cameraPermissionRequestInFlight) return "ERROR\tPERMISSION_REQUESTED";
            if (activity.getPreferences(Activity.MODE_PRIVATE)
                    .getBoolean("camera_permission_requested", false)) {
                return "ERROR\tPERMISSION_DENIED";
            }
            cameraPermissionRequestInFlight = true;
        }
        activity.getPreferences(Activity.MODE_PRIVATE).edit()
                .putBoolean("camera_permission_requested", true).apply();
        try {
            runOnUiThread(() -> activity.requestPermissions(
                    new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST));
        } catch (Exception error) {
            synchronized (this) {
                cameraPermissionRequestInFlight = false;
            }
            activity.getPreferences(Activity.MODE_PRIVATE).edit()
                    .putBoolean("camera_permission_requested", false).apply();
            throw error;
        }
        Log.i(TAG, "Requested Android camera permission for Linux camera access");
        return "ERROR\tPERMISSION_REQUESTED";
    }

    private String cameraPermissionState() {
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return "ERROR\tUNAVAILABLE";
        }
        if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            return "OK";
        }
        synchronized (this) {
            if (cameraPermissionRequestInFlight) return "ERROR\tPERMISSION_REQUESTED";
        }
        return activity.getPreferences(Activity.MODE_PRIVATE)
                .getBoolean("camera_permission_requested", false)
                ? "ERROR\tPERMISSION_DENIED" : "ERROR\tPERMISSION_NOT_REQUESTED";
    }

    private String captureCameraJpeg(String widthField, String heightField, String facing,
            FileDescriptor[] descriptors) throws Exception {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return cameraPermissionState();
        }
        if (descriptors == null || descriptors.length != 1 || !descriptors[0].valid()) {
            throw new IllegalArgumentException("Camera capture requires one output descriptor");
        }
        android.system.StructStat stat = Os.fstat(descriptors[0]);
        if ((stat.st_mode & OsConstants.S_IFMT) != OsConstants.S_IFREG) {
            throw new IllegalArgumentException("Camera capture requires a regular output file");
        }
        int width;
        int height;
        try {
            width = Integer.parseInt(widthField);
            height = Integer.parseInt(heightField);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Camera dimensions are invalid", error);
        }
        boolean front;
        if ("front".equals(facing)) front = true;
        else if ("back".equals(facing)) front = false;
        else throw new IllegalArgumentException("Camera facing must be front or back");
        AndroidCameraIntegration.CaptureResult result = cameraIntegration.captureJpeg(
                descriptors[0], width, height, front);
        Log.i(TAG, "Captured Android camera JPEG " + result.width + "x" + result.height
                + " bytes=" + result.bytes + " facing=" + facing);
        return "OK\t" + result.width + "\t" + result.height + "\t" + result.bytes;
    }

    private String streamCameraI420(String widthField, String heightField, String facing,
            FileDescriptor[] descriptors) throws Exception {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return cameraPermissionState();
        }
        if (descriptors == null || descriptors.length != 1 || !descriptors[0].valid()) {
            throw new IllegalArgumentException("Camera stream requires one output descriptor");
        }
        android.system.StructStat stat = Os.fstat(descriptors[0]);
        int type = stat.st_mode & OsConstants.S_IFMT;
        if (type != OsConstants.S_IFSOCK && type != OsConstants.S_IFIFO) {
            throw new IllegalArgumentException("Camera stream requires a socket or pipe");
        }
        int width;
        int height;
        try {
            width = Integer.parseInt(widthField);
            height = Integer.parseInt(heightField);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Camera stream dimensions are invalid", error);
        }
        boolean front;
        if ("front".equals(facing)) front = true;
        else if ("back".equals(facing)) front = false;
        else throw new IllegalArgumentException("Camera facing must be front or back");
        Log.i(TAG, "Starting Android camera I420 stream "
                + width + "x" + height + " facing=" + facing);
        cameraIntegration.streamI420(descriptors[0], width, height, front);
        Log.i(TAG, "Android camera I420 stream stopped");
        return "OK";
    }

    private String notifyLinuxApp(String id, String title, String body) throws Exception {
        validateText(id, "notification ID", false);
        validateText(title, "notification title", false);
        validateText(body, "notification body", true);
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            boolean requestPermission = false;
            synchronized (this) {
                boolean requested = activity.getPreferences(Activity.MODE_PRIVATE)
                        .getBoolean("notification_permission_requested", false);
                if (requested && !notificationPermissionRequestInFlight) {
                    return "ERROR\tPERMISSION_DENIED";
                }
                if (!pendingNotifications.containsKey(id)
                        && pendingNotifications.size() >= MAX_PENDING_NOTIFICATIONS) {
                    return "ERROR\tNOTIFICATION_QUEUE_FULL";
                }
                pendingNotifications.put(id, new PendingNotification(id, title, body));
                if (!notificationPermissionRequestInFlight) {
                    notificationPermissionRequestInFlight = true;
                    requestPermission = true;
                }
            }
            if (requestPermission) {
                activity.getPreferences(Activity.MODE_PRIVATE).edit()
                        .putBoolean("notification_permission_requested", true).apply();
                try {
                    runOnUiThread(() -> activity.requestPermissions(
                            new String[] {Manifest.permission.POST_NOTIFICATIONS},
                            NOTIFICATION_PERMISSION_REQUEST));
                } catch (Exception error) {
                    synchronized (this) {
                        pendingNotifications.remove(id);
                        notificationPermissionRequestInFlight = false;
                    }
                    throw error;
                }
            }
            return "ERROR\tPERMISSION_REQUESTED";
        }
        runOnUiThread(() -> postNotification(id, title, body));
        Log.i(TAG, "Posted Android notification id=" + id);
        return "OK";
    }

    private void postNotification(String id, String title, String body) {
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("Notification service unavailable");
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Linux application notifications",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        int icon = activity.getApplicationInfo().icon;
        if (icon == 0) icon = android.R.drawable.sym_def_app_icon;
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(activity, CHANNEL_ID)
                : new android.app.Notification.Builder(activity);
        builder.setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true);
        Intent launch = activity.getPackageManager()
                .getLaunchIntentForPackage(activity.getPackageName());
        if (launch != null) {
            builder.setContentIntent(PendingIntent.getActivity(activity, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        manager.notify(id, 1, builder.build());
    }

    private void withdrawNotification(String id) throws Exception {
        validateText(id, "notification ID", false);
        synchronized (this) {
            pendingNotifications.remove(id);
        }
        runOnUiThread(() -> {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(id, 1);
        });
        Log.i(TAG, "Withdrew Android notification id=" + id);
    }

    boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
            synchronized (this) {
                microphonePermissionRequestInFlight = false;
            }
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "Android microphone permission "
                    + (granted ? "granted" : "denied"));
            return true;
        }
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            synchronized (this) {
                cameraPermissionRequestInFlight = false;
            }
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "Android camera permission " + (granted ? "granted" : "denied"));
            return true;
        }
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return false;
        List<PendingNotification> pending;
        synchronized (this) {
            pending = new ArrayList<>(pendingNotifications.values());
            pendingNotifications.clear();
            notificationPermissionRequestInFlight = false;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            for (PendingNotification notification : pending) {
                try {
                    postNotification(notification.id, notification.title, notification.body);
                    Log.i(TAG, "Posted queued Android notification id=" + notification.id);
                } catch (RuntimeException error) {
                    Log.e(TAG, "Could not post queued Android notification", error);
                }
            }
        } else {
            Log.i(TAG, "Android notification permission denied; discarded "
                    + pending.size() + " queued notification(s)");
        }
        return true;
    }

    private void runOnUiThread(Runnable action) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                finished.countDown();
            }
        });
        if (!finished.await(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("Timed out waiting for Android UI");
        }
        Throwable error = failure.get();
        if (error != null) throw new IOException("Android capability failed", error);
    }

    private static String readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        while (value.size() <= MAX_REQUEST_BYTES) {
            int next = input.read();
            if (next < 0 || next == '\n') break;
            if (next != '\r') value.write(next);
        }
        if (value.size() == 0 || value.size() > MAX_REQUEST_BYTES) {
            throw new IOException("Capability request is empty or too large");
        }
        return value.toString(StandardCharsets.UTF_8);
    }

    private static void writeResponse(LocalSocket socket, String response) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write((response + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String decode(String value, int maxBytes) {
        return decode(value, maxBytes, false);
    }

    private static String decodeAllowEmpty(String value, int maxBytes) {
        return decode(value, maxBytes, true);
    }

    private static String decode(String value, int maxBytes, boolean allowEmpty) {
        byte[] decoded;
        try {
            decoded = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Capability field is not base64url", error);
        }
        if ((!allowEmpty && decoded.length == 0) || decoded.length > maxBytes) {
            throw new IllegalArgumentException("Capability field has invalid length");
        }
        String result = new String(decoded, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(decoded, result.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Capability field is not UTF-8");
        }
        return result;
    }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static void validateText(String value, String label, boolean multiline) {
        if (value.isBlank()) throw new IllegalArgumentException(label + " is empty");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current)
                    && !(multiline && (current == '\n' || current == '\t'))) {
                throw new IllegalArgumentException(label + " contains control characters");
            }
        }
    }

    private static void requireFields(String[] fields, int count) {
        if (fields.length != count) throw new IllegalArgumentException("Wrong field count");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        running.set(false);
        synchronized (this) {
            pendingNotifications.clear();
            notificationPermissionRequestInFlight = false;
            microphonePermissionRequestInFlight = false;
            cameraPermissionRequestInFlight = false;
        }
        cameraIntegration.close();
        clients.shutdownNow();
        LocalServerSocket current = server;
        server = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {}
        }
        Thread currentThread = thread;
        thread = null;
        if (currentThread != null) currentThread.interrupt();
        socketName = null;
    }
}
