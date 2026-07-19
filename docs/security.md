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

- The manager performs on-device resolution, verification, generic classification, runtime-pack publication, wrapper generation, signing, and Android-confirmed installation for supported x86_64 and AArch64 packages. KCalc launches from a generated wrapper on both architectures.
- Package-derived dependency closures execute from manager-owned, immutable content-addressed packs through caller-authenticated, read-only descriptors under the separate wrapper UID. Stable provider clients and Binder death tokens protect live packs; external uninstalls are reconciled per package; ELF page layouts fail closed. Dedicated process groups, parent-death signals, cancellable executions, and wrapper-UID cleanup terminate Linux descendants when their Activity exits.
- Per-package jobs persist phase, percent, bounded diagnostics, generated package identity, runtime-pack identity, and artifact time before installer handoff. Recovery activates a completed install only when Android reports an update newer than the recorded artifacts; otherwise it fails closed into an explicit retry state.
- The shared Rust Wayland compositor enforces the currently implemented object, role, configure, buffer, popup, subsurface, input, and teardown contracts on x86_64 and AArch64. It still needs broader protocol coverage, independent security review, and sustained parser fuzzing before it should be treated as a hardened general compositor boundary.
- GrapheneOS-specific hardening has not been validated on a supported Pixel.
- Running on stock Android does not provide GrapheneOS firmware, verified boot policy, exploit mitigations, or security updates.
- No generic Android or laptop target can honestly claim GrapheneOS-equivalent security without device-specific platform work.

Report vulnerabilities through the private process in [SECURITY.md](../SECURITY.md). Detailed historical analysis is preserved in the [implementation gap audit](../research/audits/implementation-gap-audit.md).