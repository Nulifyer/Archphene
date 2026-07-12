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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InstalledLinuxAppCatalog {
    public static final class Entry {
        public final String packageName;
        public final String label;
        public final String androidVersion;
        public final String sourceType;
        public final String sourceId;
        public final String sourceVersion;
        public final String runtimeAbi;
        public final String updateUrl;
        public final Intent launchIntent;

        Entry(String packageName, String label, String androidVersion, Bundle metadata, Intent launchIntent) {
            this.packageName = packageName;
            this.label = label;
            this.androidVersion = androidVersion;
            this.sourceType = metadata.getString("org.archphene.source.type", "unknown");
            this.sourceId = metadata.getString("org.archphene.source.id", "unknown");
            this.sourceVersion = metadata.getString("org.archphene.source.version", "unknown");
            this.runtimeAbi = metadata.getString("org.archphene.runtime.abi", "unknown");
            this.updateUrl = metadata.getString("org.archphene.source.update_url", "");
            this.launchIntent = launchIntent;
        }
    }

    private InstalledLinuxAppCatalog() {}

    public static List<Entry> query(Context context) throws Exception {
        PackageManager packages = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = packages.queryIntentActivities(launcher, PackageManager.GET_META_DATA);
        ArrayList<Entry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo resolved : activities) {
            ApplicationInfo app = resolved.activityInfo.applicationInfo;
            if (!seen.add(app.packageName)) {
                continue;
            }
            ApplicationInfo withMetadata = packages.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA);
            Bundle metadata = withMetadata.metaData;
            if (metadata == null || !metadata.getBoolean("org.archphene.linux_app", false)) {
                continue;
            }
            PackageInfo info = packages.getPackageInfo(app.packageName, 0);
            Intent launchIntent = packages.getLaunchIntentForPackage(app.packageName);
            if (launchIntent == null) {
                continue;
            }
            result.add(new Entry(app.packageName, packages.getApplicationLabel(withMetadata).toString(),
                    info.versionName == null ? "unknown" : info.versionName, metadata, launchIntent));
        }
        result.sort(Comparator.comparing(entry -> entry.label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
}