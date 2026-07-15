package org.archpheneos.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class InstalledLinuxAppCatalog {
    public static final class Entry {
        public final String packageName;
        public final String label;
        public final String androidVersion;
        public final String sourceType;
        public final String sourceId;
        public final String sourceVersion;
        public final String executable;
        public final String runtimeAbi;
        public final String updateUrl;
        public final Intent launchIntent;
        public final boolean runtimeBound;

        Entry(String packageName, String label, String androidVersion, Bundle metadata,
                Intent launchIntent) {
            this(packageName, label, androidVersion,
                    metadata.getString("org.archphene.source.type", "unknown"),
                    metadata.getString("org.archphene.source.id", "unknown"),
                    metadata.getString("org.archphene.source.version", "unknown"),
                    metadata.getString("org.archphene.source.executable",
                            metadata.getString("org.archphene.source.id", "unknown")
                                    .replaceFirst("^.*/", "")),
                    metadata.getString("org.archphene.runtime.abi", "unknown"),
                    metadata.getString("org.archphene.source.update_url", ""),
                    launchIntent, false);
        }

        Entry(String packageName, String label, String androidVersion, String sourceType,
                String sourceId, String sourceVersion, String executable, String runtimeAbi,
                String updateUrl, Intent launchIntent, boolean runtimeBound) {
            this.packageName = packageName;
            this.label = label;
            this.androidVersion = androidVersion;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.sourceVersion = sourceVersion;
            this.executable = executable;
            this.runtimeAbi = runtimeAbi;
            this.updateUrl = updateUrl;
            this.launchIntent = launchIntent;
            this.runtimeBound = runtimeBound;
        }
    }

    private InstalledLinuxAppCatalog() {}

    public static List<Entry> query(Context context) throws Exception {
        PackageManager packages = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = packages.queryIntentActivities(
                launcher, PackageManager.GET_META_DATA);
        ArrayList<Entry> result = new ArrayList<>();
        PackageInfo managerInfo = packages.getPackageInfo(context.getPackageName(), 0);
        Bundle managerMetadata = new Bundle();
        managerMetadata.putString("org.archphene.source.type", "archphene");
        managerMetadata.putString("org.archphene.source.id", "manager");
        managerMetadata.putString("org.archphene.source.version",
                managerInfo.versionName == null ? "unknown" : managerInfo.versionName);
        managerMetadata.putString("org.archphene.runtime.abi", "Android");
        managerMetadata.putString("org.archphene.source.update_url",
                "archphene-github://Nulifyer/Archphene");
        Intent managerLaunch = packages.getLaunchIntentForPackage(context.getPackageName());
        if (managerLaunch != null) {
            result.add(new Entry(context.getPackageName(), "Archphene",
                    managerInfo.versionName == null ? "unknown" : managerInfo.versionName,
                    managerMetadata, managerLaunch));
        }

        Map<String, Entry> byPackage = new LinkedHashMap<>();
        for (ResolveInfo resolved : activities) {
            ApplicationInfo app = resolved.activityInfo.applicationInfo;
            if (byPackage.containsKey(app.packageName)) continue;
            ApplicationInfo withMetadata = packages.getApplicationInfo(
                    app.packageName, PackageManager.GET_META_DATA);
            Bundle metadata = withMetadata.metaData;
            if (metadata == null || !metadata.getBoolean("org.archphene.linux_app", false)) {
                continue;
            }
            PackageInfo info = packages.getPackageInfo(app.packageName, 0);
            Intent launchIntent = packages.getLaunchIntentForPackage(app.packageName);
            if (launchIntent == null) continue;
            Entry entry = fromInstalled(context, app.packageName,
                    packages.getApplicationLabel(withMetadata).toString(),
                    info.versionName == null ? "unknown" : info.versionName,
                    metadata, launchIntent);
            byPackage.put(app.packageName, entry);
        }

        Map<String, Entry> bySource = new LinkedHashMap<>();
        for (Entry entry : byPackage.values()) {
            String key = sourceKey(entry.sourceType, entry.sourceId, entry.runtimeAbi);
            Entry current = bySource.get(key);
            if (current == null || preferred(entry, current)) bySource.put(key, entry);
        }
        result.addAll(bySource.values());
        result.sort(Comparator.comparing(entry -> entry.label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static String sourceKey(String sourceType, String sourceId, String runtimeAbi) {
        return normalize(sourceType) + "|" + normalize(sourceId) + "|" + normalize(runtimeAbi);
    }

    static String pacmanSourceKey(String repository, String name, String architecture) {
        String abi = "glibc-" + archLinuxArchitecture(architecture);
        return sourceKey("pacman", repository + "/" + name, abi);
    }

    static Entry findBySource(Context context, String repository, String name,
            String architecture) throws Exception {
        String wanted = pacmanSourceKey(repository, name, architecture);
        for (Entry entry : query(context)) {
            if (wanted.equals(sourceKey(entry.sourceType, entry.sourceId, entry.runtimeAbi))) {
                return entry;
            }
        }
        return null;
    }

    private static Entry fromInstalled(Context context, String packageName, String label,
            String androidVersion, Bundle metadata, Intent launchIntent) {
        try {
            RuntimePackStore.Pack pack = RuntimePackStore.active(context, packageName);
            ArchPackageRuntime.ResolvedPackage source = null;
            for (ArchPackageRuntime.ResolvedPackage value : pack.packages) {
                if (pack.sourcePackage.equals(value.name)) {
                    source = value;
                    break;
                }
            }
            if (source != null) {
                String architecture = archLinuxArchitecture(
                        android.os.Build.SUPPORTED_ABIS[0]);
                return new Entry(packageName, label, androidVersion, "pacman",
                        source.repository + "/" + source.name, source.version,
                        pack.executableName, "glibc-" + architecture,
                        "https://archlinux.org/packages/" + source.repository + "/"
                                + architecture + "/" + source.name + "/json/",
                        launchIntent, true);
            }
        } catch (Exception ignored) {
            // Legacy wrappers have no manager-owned runtime binding.
        }
        return new Entry(packageName, label, androidVersion, metadata, launchIntent);
    }

    private static boolean preferred(Entry candidate, Entry current) {
        if (candidate.runtimeBound != current.runtimeBound) return candidate.runtimeBound;
        boolean candidatePlaceholder = candidate.packageName.contains(
                ".p00000000000000000000000000000000");
        boolean currentPlaceholder = current.packageName.contains(
                ".p00000000000000000000000000000000");
        if (candidatePlaceholder != currentPlaceholder) return !candidatePlaceholder;
        return candidate.packageName.compareTo(current.packageName) < 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String archLinuxArchitecture(String architecture) {
        if ("arm64-v8a".equals(architecture)) return "aarch64";
        if ("armeabi-v7a".equals(architecture)) return "armv7h";
        return architecture;
    }
}
