package org.archpheneos.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONObject;

public final class ManagerStateStore {
    private static final String PREFS = "linux-app-manager-state";
    private static final String BACKGROUND = "background-checks";
    private static final Uri APPEARANCE_URI = Uri.parse(
            "content://org.archpheneos.manager.runtime/appearance");

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

    public static boolean microphoneInputEnabled(Context context, String packageName) {
        return preferences(context).getBoolean("microphone-input:" + packageName, false);
    }

    public static void setMicrophoneInputEnabled(Context context, String packageName,
            boolean enabled) {
        preferences(context).edit().putBoolean("microphone-input:" + packageName, enabled)
                .apply();
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
        notifyAppearanceChanged(context);
    }

    public static String linuxThemeMode(Context context) {
        String value = preferences(context).getString("linux-theme-mode", "system");
        return "light".equals(value) || "dark".equals(value) ? value : "system";
    }

    public static void setLinuxThemeMode(Context context, String mode) {
        String value = "light".equals(mode) || "dark".equals(mode) ? mode : "system";
        preferences(context).edit().putString("linux-theme-mode", value).apply();
        notifyAppearanceChanged(context);
    }

    public static int linuxScalePercent(Context context) {
        int value = preferences(context).getInt("linux-scale-percent", 0);
        for (int allowed : new int[] {0, 100, 125, 150, 175, 200}) {
            if (value == allowed) return value;
        }
        return 0;
    }

    public static void setLinuxScalePercent(Context context, int percent) {
        int value = 0;
        for (int allowed : new int[] {0, 100, 125, 150, 175, 200}) {
            if (percent == allowed) value = allowed;
        }
        preferences(context).edit().putInt("linux-scale-percent", value).apply();
        notifyAppearanceChanged(context);
    }

    public static int linuxFontPercent(Context context) {
        int value = preferences(context).getInt("linux-font-percent", 100);
        for (int allowed : new int[] {100, 110, 120, 125, 150}) {
            if (value == allowed) return value;
        }
        return 100;
    }

    public static void setLinuxFontPercent(Context context, int percent) {
        int value = 100;
        for (int allowed : new int[] {100, 110, 120, 125, 150}) {
            if (percent == allowed) value = allowed;
        }
        preferences(context).edit().putInt("linux-font-percent", value).apply();
        notifyAppearanceChanged(context);
    }

    public static String linuxControlDensity(Context context) {
        String value = preferences(context).getString("linux-control-density", "compact");
        return "compact".equals(value) || "comfortable".equals(value)
                || "touch".equals(value) || "automatic".equals(value) ? value : "compact";
    }

    public static void setLinuxControlDensity(Context context, String density) {
        String value = "compact".equals(density) || "comfortable".equals(density)
                || "touch".equals(density) || "automatic".equals(density)
                ? density : "compact";
        preferences(context).edit().putString("linux-control-density", value).apply();
        notifyAppearanceChanged(context);
    }

    private static void notifyAppearanceChanged(Context context) {
        context.getContentResolver().notifyChange(APPEARANCE_URI, null);
    }
    static void verifyPendingReinstallForTest(Context context) {
        SharedPreferences state = preferences(context);
        String previous = state.getString("pending-reinstall", null);
        try {
            ArchPackageRepository.PackageResult source =
                    new ArchPackageRepository.PackageResult("test-cli", "extra", "x86_64",
                            "1.2.3-1", "test", false, "usr/bin/test-command",
                            "test-command");
            setPendingReinstall(context, source);
            ArchPackageRepository.PackageResult restored = takePendingReinstall(context);
            if (restored == null || !source.name.equals(restored.name)
                    || !source.repository.equals(restored.repository)
                    || !source.architecture.equals(restored.architecture)
                    || !source.version.equals(restored.version)
                    || !source.executable.equals(restored.executable)
                    || takePendingReinstall(context) != null) {
                throw new IllegalStateException("Pending wrapper migration did not round-trip");
            }
        } finally {
            SharedPreferences.Editor editor = state.edit();
            if (previous == null) editor.remove("pending-reinstall");
            else editor.putString("pending-reinstall", previous);
            editor.commit();
        }
    }
    public static void setPendingReinstall(Context context,
            ArchPackageRepository.PackageResult source) {
        if (source == null) {
            preferences(context).edit().remove("pending-reinstall").commit();
            return;
        }
        try {
            JSONObject value = new JSONObject();
            value.put("name", source.name);
            value.put("repository", source.repository);
            value.put("architecture", source.architecture);
            value.put("version", source.version);
            value.put("description", source.description);
            value.put("flaggedOutOfDate", source.flaggedOutOfDate);
            value.put("matchedFile", source.matchedFile);
            value.put("executable", source.executable);
            preferences(context).edit().putString("pending-reinstall", value.toString()).commit();
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist pending wrapper migration", error);
        }
    }

    public static ArchPackageRepository.PackageResult takePendingReinstall(Context context) {
        SharedPreferences state = preferences(context);
        String raw = state.getString("pending-reinstall", "");
        state.edit().remove("pending-reinstall").commit();
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject value = new JSONObject(raw);
            return new ArchPackageRepository.PackageResult(value.getString("name"),
                    value.getString("repository"), value.getString("architecture"),
                    value.getString("version"), value.optString("description", ""),
                    value.optBoolean("flaggedOutOfDate", false),
                    value.optString("matchedFile", ""), value.getString("executable"));
        } catch (Exception error) {
            throw new IllegalStateException("Pending wrapper migration is invalid", error);
        }
    }
    public static void setPendingUninstallPackage(Context context, String packageName) {
        String value = packageName != null
                && packageName.matches("[a-zA-Z0-9._]{3,255}") ? packageName : "";
        preferences(context).edit().putString("pending-uninstall-package", value).commit();
    }

    public static String takePendingUninstallPackage(Context context) {
        android.content.SharedPreferences state = preferences(context);
        String value = state.getString("pending-uninstall-package", "");
        state.edit().remove("pending-uninstall-package").commit();
        return value == null ? "" : value;
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
