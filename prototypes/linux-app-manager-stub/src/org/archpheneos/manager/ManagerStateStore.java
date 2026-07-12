package org.archpheneos.manager;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

public final class ManagerStateStore {
    private static final String PREFS = "linux-app-manager-state";
    private static final String BACKGROUND = "background-checks";

    public static final class Snapshot {
        public final String availableVersion;
        public final String status;
        public final String error;
        public final long checkedAt;
        public final boolean updateAvailable;

        Snapshot(String availableVersion, String status, String error, long checkedAt,
                boolean updateAvailable) {
            this.availableVersion = availableVersion;
            this.status = status;
            this.error = error;
            this.checkedAt = checkedAt;
            this.updateAvailable = updateAvailable;
        }

        public static Snapshot idle() {
            return new Snapshot("", "idle", "", 0L, false);
        }
    }

    private ManagerStateStore() {}

    public static synchronized Snapshot read(Context context, String packageName) {
        String json = preferences(context).getString("app:" + packageName, null);
        if (json == null) return Snapshot.idle();
        try {
            JSONObject root = new JSONObject(json);
            return new Snapshot(
                    root.optString("availableVersion", ""),
                    root.optString("status", "idle"),
                    root.optString("error", ""),
                    root.optLong("checkedAt", 0L),
                    root.optBoolean("updateAvailable", false));
        } catch (Exception ignored) {
            return Snapshot.idle();
        }
    }

    public static synchronized Snapshot checking(Context context, String packageName) {
        Snapshot previous = read(context, packageName);
        Snapshot next = new Snapshot(previous.availableVersion, "checking", "",
                previous.checkedAt, previous.updateAvailable);
        write(context, packageName, next);
        return next;
    }

    public static synchronized Snapshot result(Context context, String packageName,
            ArchPackageUpdateChecker.Result result) {
        Snapshot next = new Snapshot(result.availableVersion,
                result.updateAvailable ? "update" : "current", "",
                System.currentTimeMillis(), result.updateAvailable);
        write(context, packageName, next);
        return next;
    }

    public static synchronized Snapshot error(Context context, String packageName, Exception error) {
        Snapshot previous = read(context, packageName);
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        Snapshot next = new Snapshot(previous.availableVersion, "error", message,
                System.currentTimeMillis(), previous.updateAvailable);
        write(context, packageName, next);
        return next;
    }

    public static synchronized Snapshot reconcileInstalledVersion(Context context,
            String packageName, String installedVersion) {
        Snapshot previous = read(context, packageName);
        if (!previous.availableVersion.isEmpty()
                && previous.availableVersion.equals(installedVersion)) {
            Snapshot current = new Snapshot(installedVersion, "current", "",
                    previous.checkedAt, false);
            write(context, packageName, current);
            return current;
        }
        if ("checking".equals(previous.status)) {
            Snapshot recovered = new Snapshot(previous.availableVersion,
                    previous.updateAvailable ? "update" : "idle", previous.error,
                    previous.checkedAt, previous.updateAvailable);
            write(context, packageName, recovered);
            return recovered;
        }
        return previous;
    }

    public static boolean backgroundChecksEnabled(Context context) {
        return preferences(context).getBoolean(BACKGROUND, false);
    }

    public static boolean checkOnLaunch(Context context) {
        return preferences(context).getBoolean("check-on-launch", false);
    }

    public static void setCheckOnLaunch(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("check-on-launch", enabled).apply();
    }

    public static boolean allowPrereleases(Context context) {
        return preferences(context).getBoolean("allow-prereleases", false);
    }

    public static void setAllowPrereleases(Context context, boolean enabled) {
        SharedPreferences prefs = preferences(context);
        SharedPreferences.Editor editor = prefs.edit().putBoolean("allow-prereleases", enabled);
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("app:")) editor.remove(key);
        }
        editor.apply();
    }

    public static boolean wifiOnly(Context context) {
        return preferences(context).getBoolean("wifi-only", true);
    }

    public static void setWifiOnly(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("wifi-only", enabled).apply();
    }

    public static boolean chargingOnly(Context context) {
        return preferences(context).getBoolean("charging-only", false);
    }

    public static void setChargingOnly(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("charging-only", enabled).apply();
    }

    public static int updateIntervalHours(Context context) {
        return preferences(context).getInt("update-interval-hours", 24);
    }

    public static void setUpdateIntervalHours(Context context, int hours) {
        preferences(context).edit().putInt("update-interval-hours", hours).apply();
    }

    public static String sortMode(Context context) {
        return preferences(context).getString("sort-mode", "name");
    }

    public static void setSortMode(Context context, String mode) {
        preferences(context).edit().putString("sort-mode", mode).apply();
    }

    public static boolean sortAscending(Context context) {
        return preferences(context).getBoolean("sort-ascending", true);
    }

    public static void setSortAscending(Context context, boolean ascending) {
        preferences(context).edit().putBoolean("sort-ascending", ascending).apply();
    }

    public static String pinnedVersion(Context context, String packageName) {
        return preferences(context).getString("pinned-version:" + packageName, "");
    }

    public static void setPinnedVersion(Context context, String packageName, String version) {
        preferences(context).edit().putString("pinned-version:" + packageName,
                version == null ? "" : version).apply();
    }

    public static boolean isPinned(Context context, String packageName) {
        return !pinnedVersion(context, packageName).isEmpty();
    }

    public static String listFilter(Context context) {
        return preferences(context).getString("list-filter", "all");
    }

    public static void setListFilter(Context context, String filter) {
        preferences(context).edit().putString("list-filter", filter).apply();
    }

    public static String themeMode(Context context) {
        return preferences(context).getString("theme-mode", "system");
    }

    public static void setThemeMode(Context context, String mode) {
        preferences(context).edit().putString("theme-mode", mode).apply();
    }

    public static boolean materialYou(Context context) {
        return preferences(context).getBoolean("material-you", false);
    }

    public static void setMaterialYou(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("material-you", enabled).apply();
    }

    public static boolean versionPrerelease(Context context, String packageName, String version) {
        return preferences(context).getBoolean(
                "version-prerelease:" + packageName + ":" + version,
                VersionPolicy.isPrerelease(version));
    }

    public static void setVersionPrerelease(Context context, String packageName, String version,
            boolean prerelease) {
        preferences(context).edit().putBoolean(
                "version-prerelease:" + packageName + ":" + version, prerelease).apply();
    }

    public static String versionHealth(Context context, String packageName, String version) {
        return preferences(context).getString("version-health:" + packageName + ":" + version,
                "unknown");
    }

    public static void setVersionHealth(Context context, String packageName, String version,
            String health) {
        preferences(context).edit().putString(
                "version-health:" + packageName + ":" + version, health).apply();
    }
    public static void setBackgroundChecksEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(BACKGROUND, enabled).apply();
    }

    private static void write(Context context, String packageName, Snapshot value) {
        try {
            JSONObject root = new JSONObject();
            root.put("availableVersion", value.availableVersion);
            root.put("status", value.status);
            root.put("error", value.error);
            root.put("checkedAt", value.checkedAt);
            root.put("updateAvailable", value.updateAvailable);
            preferences(context).edit().putString("app:" + packageName, root.toString()).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist manager app state", e);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}