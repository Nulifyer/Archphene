package org.archpheneos.manager;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ArchPackageUpdateChecker {
    public static final class Result {
        public final String availableVersion;
        public final boolean updateAvailable;

        Result(String availableVersion, boolean updateAvailable) {
            this.availableVersion = availableVersion;
            this.updateAvailable = updateAvailable;
        }
    }

    private static final List<PackageSourceAdapter> SOURCES = Collections.singletonList(
            new ArchLinuxSourceAdapter());

    private ArchPackageUpdateChecker() {}

    public static Result check(String updateUrl, String installedVersion) throws Exception {
        URL parsed = new URL(updateUrl);
        for (PackageSourceAdapter source : SOURCES) {
            if (source.supports(parsed)) return source.check(parsed, installedVersion);
        }
        throw new SecurityException("No trusted package source adapter accepts this metadata URL");
    }

    public static List<String> sourceNames() {
        ArrayList<String> names = new ArrayList<>();
        for (PackageSourceAdapter source : SOURCES) names.add(source.name());
        return Collections.unmodifiableList(names);
    }
}