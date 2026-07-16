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
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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

    private final Activity activity;
    private final Set<String> capabilities;
    private final AtomicBoolean running = new AtomicBoolean();
    private LocalServerSocket server;
    private Thread thread;
    private String socketName;

    AndroidCapabilityBroker(Activity activity, Set<String> capabilities) {
        this.activity = activity;
        this.capabilities = capabilities;
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Android capability broker already started");
        }
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
            try (LocalSocket client = current.accept()) {
                client.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                Credentials peer = client.getPeerCredentials();
                if (peer == null || peer.getUid() != Process.myUid()) {
                    Log.w(TAG, "Rejected capability broker peer uid="
                            + (peer == null ? -1 : peer.getUid()));
                    writeResponse(client, "ERROR\tUNAUTHORIZED");
                    continue;
                }
                String response;
                try {
                    response = handle(readRequest(client.getInputStream()));
                } catch (Exception error) {
                    Log.w(TAG, "Rejected Android capability request: "
                            + safeMessage(error));
                    response = "ERROR\tINVALID_REQUEST";
                }
                writeResponse(client, response);
            } catch (IOException error) {
                if (running.get()) Log.e(TAG, "Capability broker socket failed", error);
            }
        }
    }

    private String handle(String request) throws Exception {
        String[] fields = request.split("\\t", -1);
        if (fields.length < 2 || !PROTOCOL.equals(fields[0])) {
            throw new IllegalArgumentException("Unsupported capability protocol");
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
            default:
                throw new IllegalArgumentException("Unknown capability request");
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

    private String notifyLinuxApp(String id, String title, String body) throws Exception {
        validateText(id, "notification ID", false);
        validateText(title, "notification title", false);
        validateText(body, "notification body", true);
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            boolean requested = activity.getPreferences(Activity.MODE_PRIVATE)
                    .getBoolean("notification_permission_requested", false);
            if (!requested) {
                activity.getPreferences(Activity.MODE_PRIVATE).edit()
                        .putBoolean("notification_permission_requested", true).apply();
                runOnUiThread(() -> activity.requestPermissions(
                        new String[] {Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST));
                return "ERROR\tPERMISSION_REQUESTED";
            }
            return "ERROR\tPERMISSION_DENIED";
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
        runOnUiThread(() -> {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(id, 1);
        });
        Log.i(TAG, "Withdrew Android notification id=" + id);
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
        byte[] decoded;
        try {
            decoded = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Capability field is not base64url", error);
        }
        if (decoded.length == 0 || decoded.length > maxBytes) {
            throw new IllegalArgumentException("Capability field has invalid length");
        }
        String result = new String(decoded, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(decoded, result.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Capability field is not UTF-8");
        }
        return result;
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