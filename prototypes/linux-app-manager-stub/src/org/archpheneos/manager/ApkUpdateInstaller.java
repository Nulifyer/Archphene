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

public final class ApkUpdateInstaller {
    public interface Callback {
        void onStatus(String status, boolean terminal);
    }

    private ApkUpdateInstaller() {}

    public static void install(Activity activity, String url, String expectedSha256,
            String expectedPackage, Callback callback) {
        new Thread(() -> {
            try {
                URL parsed = new URL(url);
                boolean debuggable = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                File candidate;
                if (debuggable && "file".equals(parsed.getProtocol())) {
                    candidate = new File(parsed.toURI()).getCanonicalFile();
                    String cacheRoot = activity.getCacheDir().getCanonicalPath() + File.separator;
                    if (!candidate.getPath().startsWith(cacheRoot)) {
                        throw new SecurityException("Debug APK must be inside manager cache");
                    }
                } else {
                    if (!"https".equals(parsed.getProtocol())) {
                        throw new SecurityException("APK updates require HTTPS");
                    }
                    candidate = new File(activity.getCacheDir(), "archphene-update.apk");
                    download(parsed, candidate);
                }
                String actualHash = sha256(candidate);
                if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                    throw new SecurityException("APK SHA-256 mismatch");
                }
                verifyIdentity(activity, candidate, expectedPackage);
                activity.runOnUiThread(() -> callback.onStatus("Verified; opening Android installer", false));
                commit(activity, candidate, expectedPackage, callback);
            } catch (Exception e) {
                activity.runOnUiThread(() -> callback.onStatus("Install failed: " + e.getMessage(), true));
            }
        }, "archphene-apk-update").start();
    }

    private static void download(URL url, File destination) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("APK download HTTP " + connection.getResponseCode());
            }
            long declared = connection.getContentLengthLong();
            if (declared <= 0 || declared > 512L * 1024 * 1024) {
                throw new IllegalStateException("Invalid APK download size");
            }
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > 512L * 1024 * 1024) {
                        throw new IllegalStateException("APK exceeds 512 MiB");
                    }
                    out.write(buffer, 0, read);
                }
                if (total != declared) {
                    throw new IllegalStateException("Incomplete APK download");
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void verifyIdentity(Context context, File apk, String expectedPackage) throws Exception {
        PackageManager packages = context.getPackageManager();
        PackageInfo candidate = packages.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed = packages.getPackageInfo(expectedPackage, PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !expectedPackage.equals(candidate.packageName)) {
            throw new SecurityException("APK package identity mismatch");
        }
        byte[][] candidateCerts = certificateDigests(candidate);
        byte[][] installedCerts = certificateDigests(installed);
        if (!Arrays.deepEquals(candidateCerts, installedCerts)) {
            throw new SecurityException("APK signing identity mismatch");
        }
        if (candidate.getLongVersionCode() < installed.getLongVersionCode()) {
            throw new SecurityException("APK version downgrade rejected");
        }
    }

    private static byte[][] certificateDigests(PackageInfo info) throws Exception {
        android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
        byte[][] result = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) {
            result[i] = MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray());
        }
        Arrays.sort(result, (left, right) -> Arrays.compare(left, right));
        return result;
    }

    private static void commit(Activity activity, File apk, String packageName, Callback callback) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (InputStream in = new FileInputStream(apk);
                OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            session.fsync(out);
        }

        String action = activity.getPackageName() + ".INSTALL_RESULT." + sessionId;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirmation != null) {
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(confirmation);
                    }
                    callback.onStatus("Waiting for Android install confirmation", false);
                    return;
                }
                try {
                    activity.unregisterReceiver(this);
                } catch (IllegalArgumentException ignored) {
                }
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    callback.onStatus("Android package update installed", true);
                } else {
                    callback.onStatus("Android installer failed: "
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
        session.commit(pending.getIntentSender());
        session.close();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) {
            out.append(String.format("%02x", value & 0xff));
        }
        return out.toString();
    }
}