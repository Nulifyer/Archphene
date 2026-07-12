package org.archpheneos.manager;

import java.util.Locale;
import java.util.regex.Pattern;

public final class VersionPolicy {
    private static final Pattern PRERELEASE = Pattern.compile(
            "(?:^|[._+~-]|\\d)(?:alpha|beta|preview|pre|rc|dev|snapshot|nightly)(?:[._+~-]?\\d*)?(?:$|[._+~-])",
            Pattern.CASE_INSENSITIVE);

    private VersionPolicy() {}

    public static boolean isPrerelease(String version) {
        if (version == null || version.isEmpty()) return false;
        String normalized = version.toLowerCase(Locale.ROOT);
        return PRERELEASE.matcher(normalized).find();
    }

    public static boolean allowed(android.content.Context context, String version) {
        return ManagerStateStore.allowPrereleases(context) || !isPrerelease(version);
    }
}