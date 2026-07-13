package org.archpheneos.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class LinuxAppUpdateCoordinator {
    public interface Callback {
        void onState(InstalledLinuxAppCatalog.Entry app, ManagerStateStore.Snapshot state,
                int completed, int total);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LinuxAppUpdateCoordinator() {}

    public static void checkOne(Context context, InstalledLinuxAppCatalog.Entry app,
            Callback callback) {
        ManagerStateStore.Snapshot checking = ManagerStateStore.checking(context, app.packageName);
        callback.onState(app, checking, 0, 1);
        EXECUTOR.execute(() -> {
            ManagerStateStore.Snapshot state = checkBlocking(context, app);
            MAIN.post(() -> callback.onState(app, state, 1, 1));
        });
    }

    public static void checkAll(Context context, List<InstalledLinuxAppCatalog.Entry> apps,
            Callback callback) {
        if (apps.isEmpty()) return;
        AtomicInteger completed = new AtomicInteger();
        for (InstalledLinuxAppCatalog.Entry app : apps) {
            ManagerStateStore.Snapshot checking = ManagerStateStore.checking(context, app.packageName);
            callback.onState(app, checking, completed.get(), apps.size());
            EXECUTOR.execute(() -> {
                ManagerStateStore.Snapshot state = checkBlocking(context, app);
                int done = completed.incrementAndGet();
                MAIN.post(() -> callback.onState(app, state, done, apps.size()));
            });
        }
    }

    public static ManagerStateStore.Snapshot checkBlocking(Context context,
            InstalledLinuxAppCatalog.Entry app) {
        if (app.updateUrl.isEmpty()) {
            return ManagerStateStore.error(context, app.packageName,
                    new IllegalStateException("No update source configured"));
        }
        try {
            ArchPackageUpdateChecker.Result result;
            if (app.updateUrl.startsWith("archphene-github://")) {
                GitHubReleaseClient.Artifact latest = GitHubReleaseClient.latest(context);
                result = new ArchPackageUpdateChecker.Result(latest.version,
                        GitHubReleaseClient.compareVersions(
                                latest.version, app.sourceVersion) > 0);
            } else if (app.updateUrl.startsWith("archphene-wrapper://")) {
                WrapperRepositoryClient.Artifact latest = WrapperRepositoryClient.latest(
                        context, app.packageName);
                result = new ArchPackageUpdateChecker.Result(latest.version,
                        !latest.version.equals(app.sourceVersion));
                ManagerStateStore.setVersionHealth(context, app.packageName,
                        latest.version, latest.health);
            } else {
                result = ArchPackageUpdateChecker.check(app.updateUrl, app.sourceVersion);
                if (!VersionPolicy.allowed(context, result.availableVersion)) {
                    result = new ArchPackageUpdateChecker.Result(app.sourceVersion, false);
                }
            }
            return ManagerStateStore.result(context, app.packageName, result);
        } catch (Exception e) {
            return ManagerStateStore.error(context, app.packageName, e);
        }
    }
}