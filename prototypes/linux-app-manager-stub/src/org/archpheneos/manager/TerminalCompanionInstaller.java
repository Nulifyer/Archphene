package org.archpheneos.manager;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Installs the same-release-signed, isolated Terminal companion from the manager APK. */
final class TerminalCompanionInstaller {
    static final String PACKAGE = "org.archpheneos.terminal";
    private static final String ASSET = "package-runtime/archphene-terminal.apk";

    interface Callback {
        void onProgress(ApkUpdateInstaller.Phase phase, int percent,
                String status, boolean terminal);
    }

    private TerminalCompanionInstaller() {}

    static boolean isInstalled(Activity activity) {
        try {
            PackageInfo terminal = activity.getPackageManager().getPackageInfo(
                    PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo manager = activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            return activity.getPackageManager().checkSignatures(
                    manager.packageName, terminal.packageName) == PackageManager.SIGNATURE_MATCH
                    && terminal.getLongVersionCode() >= manager.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    static ApkUpdateInstaller.Operation ensureInstalled(Activity activity, Callback callback) {
        if (isInstalled(activity)) {
            callback.onProgress(ApkUpdateInstaller.Phase.COMPLETE, 100,
                    "Archphene Terminal ready", true);
            return null;
        }
        try {
            File candidate = new File(activity.getCacheDir(), "archphene-terminal.apk")
                    .getCanonicalFile();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = activity.getAssets().open(ASSET);
                    FileOutputStream output = new FileOutputStream(candidate)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > 64L * 1024 * 1024) {
                        throw new SecurityException("Terminal companion exceeds size limit");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (total == 0) throw new SecurityException("Terminal companion is empty");
            String managerSigner = ApkUpdateInstaller.installedSignerSha256(
                    activity, activity.getPackageName());
            return ApkUpdateInstaller.installWithProgress(activity,
                    candidate.toURI().toString(), hex(digest.digest()), PACKAGE,
                    managerSigner, callback::onProgress);
        } catch (Exception error) {
            callback.onProgress(ApkUpdateInstaller.Phase.ERROR, 0,
                    "Terminal install failed: " + error.getMessage(), true);
            return null;
        }
    }

    static void launch(Activity activity) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch == null) throw new IllegalStateException("Terminal launcher is unavailable");
        activity.startActivity(launch);
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(
                java.util.Locale.ROOT, "%02x", value & 0xff));
        return output.toString();
    }
}