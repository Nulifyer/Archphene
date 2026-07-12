package org.archpheneos.manager;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.util.List;

public final class LinuxAppManagerService extends JobService {
    private static final String TAG = "ArchpheneLinuxMgr";
    private static final int JOB_ID = 0x41524348;
    private static final String CHANNEL_ID = "linux-app-updates";
    private volatile boolean cancelled;

    public static boolean schedule(Context context, boolean enabled) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return false;
        if (!enabled) {
            scheduler.cancel(JOB_ID);
            return true;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, LinuxAppManagerService.class))
                .setRequiredNetworkType(ManagerStateStore.wifiOnly(context)
                        ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(ManagerStateStore.chargingOnly(context))
                .setPeriodic(ManagerStateStore.updateIntervalHours(context)
                        * 60L * 60L * 1000L)
                .setPersisted(true)
                .build();
        try {
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not schedule background package checks", e);
            return false;
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        cancelled = false;
        new Thread(() -> {
            int updates = 0;
            try {
                List<InstalledLinuxAppCatalog.Entry> apps = InstalledLinuxAppCatalog.query(this);
                for (InstalledLinuxAppCatalog.Entry app : apps) {
                    if (cancelled) break;
                    ManagerStateStore.Snapshot result =
                            LinuxAppUpdateCoordinator.checkBlocking(this, app);
                    if (result.updateAvailable) updates++;
                }
                if (!cancelled && updates > 0) notifyUpdates(updates);
            } catch (Exception e) {
                Log.e(TAG, "Background package check failed", e);
            } finally {
                jobFinished(params, false);
            }
        }, "archphene-background-update-check").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        cancelled = true;
        return true;
    }

    private void notifyUpdates(int count) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "Linux app updates", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CHANNEL_ID)
                : new android.app.Notification.Builder(this);
        android.app.Notification notification = builder
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(count == 1 ? "Linux app update available"
                        : count + " Linux app updates available")
                .setContentText("Open Archphene to review package versions")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        notifications.notify(1001, notification);
    }
}