package org.archpheneos.manager;

import java.net.URL;

public interface PackageSourceAdapter {
    boolean supports(URL metadataUrl);
    ArchPackageUpdateChecker.Result check(URL metadataUrl, String installedVersion) throws Exception;
    String name();
}