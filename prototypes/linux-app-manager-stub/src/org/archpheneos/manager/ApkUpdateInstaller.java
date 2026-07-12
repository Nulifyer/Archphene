package org.archpheneos.manager;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApkUpdateInstaller {
    public enum Phase { DOWNLOAD, INSTALL, COMPLETE, ERROR, CANCELLED }

    public interface Callback {
        void onStatus(String status, boolean terminal);
    }

    public interface ProgressCallback {
        void onProgress(Phase phase, int percent, String status, boolean terminal);
    }

    public static final class Operation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpURLConnection connection;
        private volatile PackageInstaller installer;
        private volatile int sessionId = -1;
        private volatile boolean committed;

        public boolean canCancel() {
            return !committed && !cancelled.get();
        }

        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            HttpURLConnection activeConnection = connection;
            if (activeConnection != null) activeConnection.disconnect();
            PackageInstaller activeInstaller = installer;
            if (activeInstaller != null && sessionId >= 0 && !committed) {
                try { activeInstaller.abandonSession(sessionId); }
                catch (Exception ignored) {}
            }
        }

        private void checkCancelled() throws CancelledException {
            if (cancelled.get()) throw new CancelledException();
        }
    }

    private static final class CancelledException extends Exception {}

    private ApkUpdateInstaller() {}

    public static void install(Activity activity, String url, String expectedSha256,
            String expectedPackage, Callback callback) {
        installWithProgress(activity, url, expectedSha256, expectedPackage,
                (phase, percent, status, terminal) -> callback.onStatus(status, terminal));
    }

    public static Operation installWithProgress(Activity activity, String url,
            String expectedSha256, String expectedPackage, ProgressCallback callback) {
        return installWithProgress(activity, url, expectedSha256, expectedPackage, "", callback);
    }

    public static Operation installWithProgress(Activity activity, String url,
            String expectedSha256, String expectedPackage, String expectedSignerSha256,
            ProgressCallback callback) {
        Operation operation = new Operation();
        new Thread(() -> runInstall(activity, url, expectedSha256, expectedPackage,
                expectedSignerSha256, callback, operation), "archphene-apk-update").start();
        return operation;
    }

    private static void runInstall(Activity activity, String url, String expectedSha256,
            String expectedPackage, String expectedSignerSha256,
            ProgressCallback callback, Operation operation) {
        try {
            post(activity, callback, Phase.DOWNLOAD, 0, "Preparing download", false);
            URL parsed = new URL(url);
            boolean debuggable = (activity.getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            File candidate;
            if (debuggable && "file".equals(parsed.getProtocol())) {
                candidate = new File(parsed.toURI()).getCanonicalFile();
                String cacheRoot = activity.getCacheDir().getCanonicalPath() + File.separator;
                if (!candidate.getPath().startsWith(cacheRoot)) {
                    throw new SecurityException("Debug APK must be inside manager cache");
                }
                post(activity, callback, Phase.DOWNLOAD, 100, "Download ready", false);
            } else {
                if (!"https".equals(parsed.getProtocol())) {
                    throw new SecurityException("APK updates require HTTPS");
                }
                candidate = new File(activity.getCacheDir(),
                        "archphene-update-" + expectedPackage.hashCode() + ".apk");
                download(activity, parsed, candidate, callback, operation);
            }
            operation.checkCancelled();
            post(activity, callback, Phase.INSTALL, 5, "Verifying package", false);
            String actualHash = sha256(candidate);
            if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                throw new SecurityException("APK SHA-256 mismatch");
            }
            verifyIdentity(activity, candidate, expectedPackage, expectedSignerSha256);
            operation.checkCancelled();
            post(activity, callback, Phase.INSTALL, 20, "Package verified", false);
            commit(activity, candidate, expectedPackage, callback, operation);
        } catch (CancelledException e) {
            post(activity, callback, Phase.CANCELLED, 0, "Download cancelled", true);
        } catch (Exception e) {
            if (operation.cancelled.get()) {
                post(activity, callback, Phase.CANCELLED, 0, "Download cancelled", true);
            } else {
                post(activity, callback, Phase.ERROR, 0, "Install failed: " + e.getMessage(), true);
            }
        }
    }

    private static void download(Activity activity, URL url, File destination,
            ProgressCallback callback, Operation operation) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        operation.connection = connection;
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("APK download HTTP " + connection.getResponseCode());
            }
            long declared = connection.getContentLengthLong();
            if (declared <= 0 || declared > 512L * 1024 * 1024) {
                throw new IllegalStateException("Invalid APK download size");
            }
            try (InputStream in = connection.getInputStream();
                    OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                int lastPercent = -1;
                while ((read = in.read(buffer)) != -1) {
                    operation.checkCancelled();
                    total += read;
                    if (total > 512L * 1024 * 1024) {
                        throw new IllegalStateException("APK exceeds 512 MiB");
                    }
                    out.write(buffer, 0, read);
                    int percent = (int) Math.min(100, total * 100 / declared);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        post(activity, callback, Phase.DOWNLOAD, percent,
                                "Downloading " + percent + "%", false);
                    }
                }
                if (total != declared) throw new IllegalStateException("Incomplete APK download");
            }
        } finally {
            operation.connection = null;
            connection.disconnect();
        }
    }

    private static void verifyIdentity(Context context, File apk, String expectedPackage,
            String expectedSignerSha256) throws Exception {
        PackageManager packages = context.getPackageManager();
        PackageInfo candidate = packages.getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !expectedPackage.equals(candidate.packageName)) {
            throw new SecurityException("APK package identity mismatch");
        }
        byte[][] candidateCerts = certificateDigests(candidate);
        try {
            PackageInfo installed = packages.getPackageInfo(expectedPackage,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (!Arrays.deepEquals(candidateCerts, certificateDigests(installed))) {
                throw new SecurityException("APK signing identity mismatch");
            }
            if (candidate.getLongVersionCode() < installed.getLongVersionCode()) {
                throw new SecurityException("APK version downgrade rejected");
            }
        } catch (PackageManager.NameNotFoundException notInstalled) {
            if (expectedSignerSha256 == null || expectedSignerSha256.isEmpty()) {
                throw new SecurityException("Initial install requires a trusted signing certificate");
            }
            if (candidateCerts.length != 1
                    || !hex(candidateCerts[0]).equalsIgnoreCase(expectedSignerSha256)) {
                throw new SecurityException("APK signer does not match wrapper repository");
            }
        }
    }
    private static byte[][] certificateDigests(PackageInfo info) throws Exception {
        android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
        byte[][] result = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) {
            result[i] = MessageDigest.getInstance("SHA-256")
                    .digest(signatures[i].toByteArray());
        }
        Arrays.sort(result, (left, right) -> Arrays.compare(left, right));
        return result;
    }

    private static void commit(Activity activity, File apk, String packageName,
            ProgressCallback callback, Operation operation) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        operation.installer = installer;
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        int sessionId = installer.createSession(params);
        operation.sessionId = sessionId;
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (InputStream in = new FileInputStream(apk);
                OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int lastPercent = -1;
            int read;
            while ((read = in.read(buffer)) != -1) {
                operation.checkCancelled();
                out.write(buffer, 0, read);
                total += read;
                int percent = 20 + (int) Math.min(50, total * 50 / apk.length());
                if (percent != lastPercent) {
                    lastPercent = percent;
                    post(activity, callback, Phase.INSTALL, percent, "Preparing install", false);
                }
            }
            session.fsync(out);
        }
        operation.checkCancelled();

        String action = activity.getPackageName() + ".INSTALL_RESULT." + sessionId;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirmation != null) {
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(confirmation);
                    }
                    callback.onProgress(Phase.INSTALL, 80,
                            "Waiting for install confirmation", false);
                    return;
                }
                try { activity.unregisterReceiver(this); }
                catch (IllegalArgumentException ignored) {}
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    callback.onProgress(Phase.COMPLETE, 100,
                            "Android package update installed", true);
                } else {
                    callback.onProgress(Phase.ERROR, 0, "Android installer failed: "
                            + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), true);
                }
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(receiver, filter);
        }
        Intent result = new Intent(action).setPackage(activity.getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(activity, sessionId, result,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        operation.committed = true;
        post(activity, callback, Phase.INSTALL, 75, "Opening Android installer", false);
        session.commit(pending.getIntentSender());
        session.close();
    }

    private static void post(Activity activity, ProgressCallback callback, Phase phase,
            int percent, String status, boolean terminal) {
        activity.runOnUiThread(() -> callback.onProgress(phase, percent, status, terminal));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
}