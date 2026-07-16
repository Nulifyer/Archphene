package org.archpheneos.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable phase and error state for one package conversion transaction. */
final class PackageInstallJobStore {
    static final String IDLE = "idle";
    static final String QUEUED = "queued";
    static final String RUNNING = "running";
    static final String WAITING_INSTALL = "waiting_install";
    static final String COMPLETE = "complete";
    static final String ERROR = "error";
    static final String CANCELLED = "cancelled";

    static final class Snapshot {
        final String id;
        final String state;
        final ApkUpdateInstaller.Phase phase;
        final int percent;
        final String status;
        final String error;
        final String diagnostics;
        final String androidPackage;
        final String runtimePackId;
        final long artifactsAt;
        final long updatedAt;

        Snapshot(String id, String state, ApkUpdateInstaller.Phase phase, int percent,
                String status, String error, String diagnostics, String androidPackage,
                String runtimePackId, long artifactsAt, long updatedAt) {
            this.id = id;
            this.state = state;
            this.phase = phase;
            this.percent = percent;
            this.status = status;
            this.error = error;
            this.diagnostics = diagnostics;
            this.androidPackage = androidPackage;
            this.runtimePackId = runtimePackId;
            this.artifactsAt = artifactsAt;
            this.updatedAt = updatedAt;
        }

        boolean active() {
            return QUEUED.equals(state) || RUNNING.equals(state)
                    || WAITING_INSTALL.equals(state);
        }

        boolean retryable() {
            return ERROR.equals(state) || CANCELLED.equals(state);
        }
    }

    private static final String PREFS = "linux-package-install-jobs-v1";
    private static final String PREFIX = "job:";
    private static final AtomicBoolean RECOVERED = new AtomicBoolean();

    private PackageInstallJobStore() {}

    static String key(ArchPackageRepository.PackageResult source) {
        return key(source.repository, source.name, source.architecture);
    }

    static String key(String repository, String name, String architecture) {
        return "pacman|" + normalized(repository) + "|" + normalized(name)
                + "|" + normalized(architecture);
    }

    static String key(InstalledLinuxAppCatalog.Entry app) {
        if (app == null || !"pacman".equalsIgnoreCase(app.sourceType)) return "";
        int separator = app.sourceId.indexOf('/');
        if (separator <= 0 || separator == app.sourceId.length() - 1
                || !app.runtimeAbi.toLowerCase(Locale.ROOT).startsWith("glibc-")) {
            return "";
        }
        return key(app.sourceId.substring(0, separator),
                app.sourceId.substring(separator + 1), app.runtimeAbi.substring(6));
    }
    static synchronized Snapshot read(Context context, String id) {
        String raw = preferences(context).getString(PREFIX + id, "");
        if (raw == null || raw.isEmpty()) return idle(id);
        try {
            JSONObject value = new JSONObject(raw);
            return new Snapshot(id, value.getString("state"),
                    ApkUpdateInstaller.Phase.valueOf(value.getString("phase")),
                    Math.max(0, Math.min(100, value.getInt("percent"))),
                    value.optString("status", ""), value.optString("error", ""),
                    value.optJSONArray("diagnostics") == null ? "[]"
                            : value.optJSONArray("diagnostics").toString(),
                    value.optString("androidPackage", ""),
                    value.optString("runtimePackId", ""),
                    value.optLong("artifactsAt", 0), value.optLong("updatedAt", 0));
        } catch (Exception invalid) {
            return new Snapshot(id, ERROR, ApkUpdateInstaller.Phase.ERROR, 0,
                    "Saved install state is invalid", "Clear and retry this package",
                    "[]", "", "", 0, 0);
        }
    }

    static synchronized Snapshot begin(Context context, String id) {
        clear(context, id);
        return write(context, id, QUEUED, ApkUpdateInstaller.Phase.DOWNLOAD, 0,
                "Queued", "", "", "", 0);
    }

    static synchronized Snapshot update(Context context, String id, String state,
            ApkUpdateInstaller.Phase phase, int percent, String status, String error) {
        Snapshot previous = read(context, id);
        return write(context, id, state, phase, percent, status, error,
                previous.androidPackage, previous.runtimePackId, previous.artifactsAt);
    }

    static synchronized Snapshot setArtifacts(Context context, String id,
            String androidPackage, String runtimePackId) {
        Snapshot previous = read(context, id);
        if (androidPackage == null || !androidPackage.matches("[a-zA-Z0-9._]{3,255}")
                || runtimePackId == null || !runtimePackId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid package job artifacts");
        }
        return write(context, id, previous.state, previous.phase, previous.percent,
                previous.status, previous.error, androidPackage, runtimePackId,
                System.currentTimeMillis());
    }

    static void recoverInterruptedOnce(Context context) {
        if (!RECOVERED.compareAndSet(false, true)) return;
        SharedPreferences preferences = preferences(context);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX) || !(entry.getValue() instanceof String)) {
                continue;
            }
            String id = entry.getKey().substring(PREFIX.length());
            Snapshot previous = read(context, id);
            if (previous.active()) {
                if (reconcileCompletedInstall(context, previous)) continue;
                Snapshot interrupted = update(context, id, ERROR, ApkUpdateInstaller.Phase.ERROR,
                        previous.percent, "Install interrupted",
                        "The manager process stopped before this phase completed. Retry the package.");
                TerminalCommandReporter.reportJob(context, interrupted, true);
            }
        }
    }

    private static boolean reconcileCompletedInstall(Context context, Snapshot job) {
        if (job.androidPackage.isEmpty() || job.runtimePackId.isEmpty()
                || job.artifactsAt <= 0) return false;
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(
                    job.androidPackage, 0);
            if (installed.lastUpdateTime < job.artifactsAt) return false;
            RuntimePackStore.activate(context, job.androidPackage, job.runtimePackId);
            RuntimePackStore.grantActive(context, job.androidPackage);
            String[] identity = job.id.split("\\|", -1);
            if (identity.length == 4 && "pacman".equals(identity[0])) {
                TrackedPackageStore.remove(context, identity[1], identity[2], identity[3]);
            }
            Snapshot recovered = update(context, job.id, COMPLETE,
                    ApkUpdateInstaller.Phase.COMPLETE, 100,
                    "Recovered completed Android install", "");
            TerminalCommandReporter.reportJob(context, recovered, true);
            return true;
        } catch (Exception error) {
            android.util.Log.w("ArchpheneManager",
                    "Could not reconcile interrupted package job " + job.id, error);
            return false;
        }
    }
    static synchronized void clear(Context context, String id) {
        preferences(context).edit().remove(PREFIX + id).commit();
    }

    static void verifyForTest(Context context) throws Exception {
        String id = "test|job-store";
        clear(context, id);
        Snapshot queued = begin(context, id);
        if (!queued.active() || queued.percent != 0) {
            throw new IllegalStateException("Package job queue state was not durable");
        }
        update(context, id, RUNNING, ApkUpdateInstaller.Phase.DOWNLOAD, 47,
                "Verifying", "");
        Snapshot running = read(context, id);
        if (!running.active() || running.percent != 47
                || !"Verifying".equals(running.status)) {
            throw new IllegalStateException("Package job progress did not round-trip");
        }
        setArtifacts(context, id, "org.archphene.linux.test",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        update(context, id, ERROR, ApkUpdateInstaller.Phase.ERROR, 47,
                "Verification failed", "Synthetic isolated failure");
        Snapshot failed = read(context, id);
        if (!failed.retryable() || !failed.error.contains("isolated")
                || !"org.archphene.linux.test".equals(failed.androidPackage)
                || failed.artifactsAt <= 0 || diagnosticCount(failed) != 3
                || !diagnosticSummary(failed).contains("Verification failed")) {
            throw new IllegalStateException("Package job failure state did not round-trip");
        }
        for (int index = 0; index < 30; index++) {
            update(context, id, RUNNING, ApkUpdateInstaller.Phase.DOWNLOAD,
                    index, "Synthetic phase " + index, "");
        }
        Snapshot bounded = read(context, id);
        if (diagnosticCount(bounded) != 24
                || !diagnosticSummary(bounded).contains("Synthetic phase 29")) {
            throw new IllegalStateException("Package diagnostics were not bounded");
        }
        clear(context, id);
        JSONObject legacy = new JSONObject();
        legacy.put("state", ERROR);
        legacy.put("phase", ApkUpdateInstaller.Phase.ERROR.name());
        legacy.put("percent", 0);
        legacy.put("status", "Legacy failure");
        legacy.put("error", "legacy");
        legacy.put("androidPackage", "");
        legacy.put("runtimePackId", "");
        legacy.put("artifactsAt", 0);
        legacy.put("updatedAt", 1);
        if (!preferences(context).edit().putString(PREFIX + id, legacy.toString()).commit()
                || diagnosticCount(read(context, id)) != 0) {
            throw new IllegalStateException("Legacy package job migration failed");
        }
        clear(context, id);
    }

    private static Snapshot write(Context context, String id, String state,
            ApkUpdateInstaller.Phase phase, int percent, String status, String error,
            String androidPackage, String runtimePackId, long artifactsAt) {
        if (id == null || id.length() > 512 || state == null || phase == null) {
            throw new IllegalArgumentException("Invalid package job state");
        }
        String safeStatus = bounded(status, 1024);
        String safeError = bounded(error, 4096);
        int safePercent = Math.max(0, Math.min(100, percent));
        long now = System.currentTimeMillis();
        try {
            JSONObject value = new JSONObject();
            value.put("state", state);
            value.put("phase", phase.name());
            value.put("percent", safePercent);
            value.put("status", safeStatus);
            value.put("error", safeError);
            value.put("diagnostics", appendDiagnostic(context, id, state, phase,
                    safePercent, safeStatus, safeError));
            value.put("androidPackage", bounded(androidPackage, 255));
            value.put("runtimePackId", bounded(runtimePackId, 64));
            value.put("artifactsAt", artifactsAt);
            value.put("updatedAt", now);
            if (!preferences(context).edit().putString(PREFIX + id, value.toString()).commit()) {
                throw new IllegalStateException("Could not persist package job state");
            }
            return new Snapshot(id, state, phase, safePercent, safeStatus, safeError,
                    value.getJSONArray("diagnostics").toString(),
                    bounded(androidPackage, 255), bounded(runtimePackId, 64),
                    artifactsAt, now);
        } catch (Exception errorValue) {
            throw new IllegalStateException("Could not encode package job state", errorValue);
        }
    }

    private static Snapshot idle(String id) {
        return new Snapshot(id, IDLE, ApkUpdateInstaller.Phase.DOWNLOAD,
                0, "", "", "[]", "", "", 0, 0);
    }

    static String diagnosticSummary(Snapshot snapshot) {
        try {
            JSONArray values = new JSONArray(snapshot.diagnostics);
            StringBuilder result = new StringBuilder();
            int first = Math.max(0, values.length() - 6);
            for (int index = first; index < values.length(); index++) {
                JSONObject value = values.getJSONObject(index);
                if (result.length() > 0) result.append((char) 10);
                result.append(value.optString("phase", "").toLowerCase(Locale.ROOT))
                        .append(" ").append(value.optInt("percent", 0)).append("%: ")
                        .append(value.optString("status", ""));
                String error = value.optString("error", "");
                if (!error.isEmpty()) result.append(" - ").append(error);
            }
            return result.toString();
        } catch (Exception invalid) {
            return "";
        }
    }

    private static int diagnosticCount(Snapshot snapshot) {
        try {
            return new JSONArray(snapshot.diagnostics).length();
        } catch (Exception invalid) {
            return 0;
        }
    }

    private static JSONArray appendDiagnostic(Context context, String id, String state,
            ApkUpdateInstaller.Phase phase, int percent, String status, String error) {
        JSONArray values = new JSONArray();
        try {
            String raw = preferences(context).getString(PREFIX + id, "");
            if (raw != null && !raw.isEmpty()) {
                JSONArray saved = new JSONObject(raw).optJSONArray("diagnostics");
                if (saved != null) values = new JSONArray(saved.toString());
            }
        } catch (Exception ignored) {
            values = new JSONArray();
        }
        JSONObject next = new JSONObject();
        try {
            next.put("at", System.currentTimeMillis());
            next.put("state", state);
            next.put("phase", phase.name());
            next.put("percent", percent);
            next.put("status", bounded(status, 256));
            next.put("error", bounded(error, 512));
            if (values.length() > 0) {
                JSONObject previous = values.getJSONObject(values.length() - 1);
                if (state.equals(previous.optString("state"))
                        && phase.name().equals(previous.optString("phase"))
                        && percent == previous.optInt("percent")
                        && status.equals(previous.optString("status"))
                        && error.equals(previous.optString("error"))) {
                    return values;
                }
            }
            values.put(next);
            while (values.length() > 24) values.remove(0);
            return values;
        } catch (Exception invalid) {
            return values;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
