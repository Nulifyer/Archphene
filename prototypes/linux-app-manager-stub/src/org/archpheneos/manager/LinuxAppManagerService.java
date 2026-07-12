package org.archpheneos.manager;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public final class LinuxAppManagerService extends Service {
    public static final String ACTION_START = "org.archpheneos.manager.START_LINUX_APP_MANAGER";
    private static final String TAG = "ArchpheneLinuxMgr";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "LinuxAppManagerService started");
        try {
            LinuxPackageManifest manifest = LinuxPackageManifest.loadSample(this);
            PayloadStager.Result staged = PayloadStager.stage(this, manifest);
            PayloadLauncher.Result launch = PayloadLauncher.runDirectElf(staged);
            Log.i(TAG, "Parsed sample LAPK " + manifest.packageName + " entrypoint=" + manifest.entrypoint);
            Log.i(TAG, "Staged payload path=" + staged.file.getAbsolutePath() + " sha256=" + staged.sha256);
            Log.i(TAG, "Direct ELF launch exit=" + launch.exitCode + " startError=" + launch.startError + " stdoutBytes=" + launch.stdout.length());
        } catch (Exception e) {
            Log.e(TAG, "Failed sample LAPK launch plumbing", e);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}