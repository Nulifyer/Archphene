package org.archpheneos.manager;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import java.net.URL;

/** Selects one fail-closed repository and trust policy for the process ABI. */
final class ArchRuntimePolicy {
    static final String X86_64 = "x86_64";
    static final String AARCH64 = "aarch64";
    static final String ARCHLINUX_ARM_BUILD_KEY =
            "68B3537F39A313B3E574D06777193F152BDBE6A6";

    final String architecture;
    final String mirrorHost;
    final String mirrorPrefix;
    final String trustName;
    final String requiredSigner;

    private ArchRuntimePolicy(String architecture, String mirrorHost, String mirrorPrefix,
            String trustName, String requiredSigner) {
        this.architecture = architecture;
        this.mirrorHost = mirrorHost;
        this.mirrorPrefix = mirrorPrefix;
        this.trustName = trustName;
        this.requiredSigner = requiredSigner;
    }

    static ArchRuntimePolicy current() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return new ArchRuntimePolicy(AARCH64, "ca.us.mirror.archlinuxarm.org",
                        "/aarch64", "archlinuxarm-aarch64", ARCHLINUX_ARM_BUILD_KEY);
            }
            if (X86_64.equals(abi)) {
                return new ArchRuntimePolicy(X86_64, "geo.mirror.pkgbuild.com",
                        "", "archlinux-x86_64", "");
            }
        }
        throw new UnsupportedOperationException("Archphene has no runtime for this device ABI");
    }

    static String packageTransactionIssue() {
        long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        if (X86_64.equals(current().architecture) && pageSize >= 16384) {
            return "Package installs unavailable: upstream Arch x86_64 runtime is 4 KB-only "
                    + "on this 16 KB Android device";
        }
        return "";
    }
    static void verifyForTest() throws Exception {
        ArchRuntimePolicy x86 = new ArchRuntimePolicy(X86_64,
                "geo.mirror.pkgbuild.com", "", "archlinux-x86_64", "");
        ArchRuntimePolicy arm = new ArchRuntimePolicy(AARCH64,
                "ca.us.mirror.archlinuxarm.org", "/aarch64", "archlinuxarm-aarch64",
                ARCHLINUX_ARM_BUILD_KEY);
        if (!"https://geo.mirror.pkgbuild.com/core/os/x86_64/core.db".equals(
                x86.databaseUrl("core", false))
                || !"https://ca.us.mirror.archlinuxarm.org/aarch64/extra/extra.files".equals(
                        arm.databaseUrl("extra", true))) {
            throw new SecurityException("Repository path policy mismatch");
        }
        x86.validatePackageUrl(
                "https://geo.mirror.pkgbuild.com/extra/os/x86_64/kcalc.pkg.tar.zst",
                "extra");
        arm.validatePackageUrl(
                "https://ca.us.mirror.archlinuxarm.org/aarch64/extra/kcalc.pkg.tar.xz",
                "extra");
        boolean rejected = false;
        try {
            arm.validatePackageUrl(
                    "https://geo.mirror.pkgbuild.com/extra/os/x86_64/kcalc.pkg.tar.zst",
                    "extra");
        } catch (SecurityException expected) {
            rejected = true;
        }
        if (!rejected) {
            throw new SecurityException("Cross-architecture repository URL was accepted");
        }
    }
    static boolean supports(String architecture) {
        try {
            return current().architecture.equals(architecture);
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    String databaseUrl(String repository, boolean files) {
        requireRepository(repository);
        String suffix = files ? ".files" : ".db";
        return baseUrl(repository) + "/" + repository + suffix;
    }

    String baseUrl(String repository) {
        requireRepository(repository);
        return "https://" + mirrorHost + repositoryPath(repository);
    }

    void validatePackageUrl(String endpoint, String repository) throws Exception {
        requireRepository(repository);
        URL url = new URL(endpoint);
        validateHost(url);
        String expected = repositoryPath(repository) + "/";
        if (!url.getPath().startsWith(expected)) {
            throw new SecurityException("Package URL does not match its Arch repository");
        }
    }

    void validateHost(URL url) {
        if (!"https".equals(url.getProtocol()) || !mirrorHost.equals(url.getHost())
                || url.getUserInfo() != null || url.getPort() != -1) {
            throw new SecurityException("Unsupported Arch package endpoint");
        }
    }

    private String repositoryPath(String repository) {
        requireRepository(repository);
        if (X86_64.equals(architecture)) {
            return "/" + repository + "/os/x86_64";
        }
        return mirrorPrefix + "/" + repository;
    }

    String catalogAsset() {
        return "package-runtime/runtime-modules-" + architecture + ".tsv";
    }

    private static void requireRepository(String repository) {
        if (!"core".equals(repository) && !"extra".equals(repository)) {
            throw new SecurityException("Unsupported Arch repository");
        }
    }
}