# Security model

Archphene aims to preserve Android application isolation while making Linux desktop software usable through Android-managed applications. It does not attempt to turn pacman into a privileged Android system package manager.

## Preserved Android boundaries

Each wrapped application is intended to have its own:

- package name and signing identity;
- Linux/Android UID and SELinux app domain;
- private data directory;
- process lifecycle;
- Android permissions and URI grants;
- install, update, and uninstall transaction.

The bridge does not bypass runtime permissions. Files, camera, microphone, location, notifications, USB, networking policy, and similar capabilities require Android-side brokers.

## Package and update trust

Before an APK installation, the manager verifies:

- HTTPS transport;
- expected SHA-256;
- expected package name;
- signer continuity for updates;
- trusted signer identity for initial installs;
- non-decreasing Android version code;
- bounded download size.

Android still presents its system installation confirmation where required.

Production manager releases are built non-debuggable and signed with a dedicated release key. Development builds remain debuggable for emulator automation and must not be distributed as production builds.

## Important limitations

- The manager performs complete on-device resolution, verification, wrapper generation, and signing for the validated x86_64 KCalc template, but not yet for arbitrary packages, toolkits, or ABIs.
- Static and patched-glibc programs now execute from manager-owned, hash-verified, read-only descriptors under a separate wrapper UID, including a separately granted `DT_NEEDED` library. Complete application dependency graphs still use mutable app-private extraction and need package-derived catalogs, atomic runtime packs, and durable brokered grants.
- The prototype Java Wayland parsers are not yet a hardened protocol boundary.
- GrapheneOS-specific hardening has not been validated on a supported Pixel.
- Running on stock Android does not provide GrapheneOS firmware, verified boot policy, exploit mitigations, or security updates.
- No generic Android or laptop target can honestly claim GrapheneOS-equivalent security without device-specific platform work.

Report vulnerabilities through the private process in [SECURITY.md](../SECURITY.md). Detailed historical analysis is preserved in the [implementation gap audit](../research/audits/implementation-gap-audit.md).