package org.archpheneos.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Collections;
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
        public final String managedKind;
        public final List<String> commands;

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
                    normalizedUpdateUrl(metadata),
                    launchIntent, false, "", Collections.emptyList());
        }

        Entry(String packageName, String label, String androidVersion, String sourceType,
                String sourceId, String sourceVersion, String executable, String runtimeAbi,
                String updateUrl, Intent launchIntent, boolean runtimeBound,
                String managedKind, List<String> commands) {
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
            this.managedKind = managedKind;
            this.commands = Collections.unmodifiableList(new ArrayList<>(commands));
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
        for (ManagedPackageStore.Entry managed : ManagedPackageStore.list(context)) {
            String key = pacmanSourceKey(managed.repository, managed.name,
                    managed.architecture);
            if (!bySource.containsKey(key)) result.add(fromManaged(packages, managed));
        }
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

    private static Entry fromManaged(PackageManager packages, ManagedPackageStore.Entry entry) {
        String update = "https://archlinux.org/packages/" + entry.repository + "/"
                + entry.architecture + "/" + entry.name + "/json/";
        Intent terminal = new Intent(Intent.ACTION_MAIN)
                .setClassName("org.archpheneos.terminal",
                        "org.archpheneos.terminal.TerminalActivity")
                .addCategory(Intent.CATEGORY_LAUNCHER);
        return new Entry(entry.stateKey(), entry.name, entry.version, "pacman",
                entry.repository + "/" + entry.name, entry.version, entry.executable,
                "glibc-" + entry.architecture, update, terminal, true,
                entry.kind, entry.commands);
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
                        launchIntent, true, "", Collections.emptyList());
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

    static void verifyPacmanMetadataForTest() {
        Bundle metadata = new Bundle();
        metadata.putString("org.archphene.source.type", "pacman");
        metadata.putString("org.archphene.source.id", "extra/mousepad");
        metadata.putString("org.archphene.runtime.abi", "glibc-x86_64");
        metadata.putString("org.archphene.source.update_url",
                "https://archlinux.org/packages/extra/x86_64/kcalc/json/");
        String expected = "https://archlinux.org/packages/extra/x86_64/mousepad/json/";
        if (!expected.equals(normalizedUpdateUrl(metadata))) {
            throw new SecurityException("Pacman update metadata normalization mismatch");
        }
    }

    private static String normalizedUpdateUrl(Bundle metadata) {
        String configured = metadata.getString("org.archphene.source.update_url", "");
        if (!"pacman".equalsIgnoreCase(metadata.getString("org.archphene.source.type", ""))) {
            return configured;
        }
        String sourceId = metadata.getString("org.archphene.source.id", "");
        String runtimeAbi = metadata.getString("org.archphene.runtime.abi", "");
        if (!sourceId.matches("[a-z0-9-]{1,32}/[a-zA-Z0-9@._+:-]{1,128}")
                || !"glibc-x86_64".equals(runtimeAbi)) {
            return configured;
        }
        int separator = sourceId.indexOf('/');
        return "https://archlinux.org/packages/" + sourceId.substring(0, separator)
                + "/x86_64/" + sourceId.substring(separator + 1) + "/json/";
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
