# Changelog

Notable user-facing changes will be recorded here.

## Unreleased

### Added

- Archphene Android manager with package discovery, version checks, pinning, prerelease policy, repository management, and verified APK installation.
- GitHub Release workflow for signed, non-debuggable manager APKs and SHA-256 checksums.
- Qt/KDE KCalc and GTK Mousepad Android bridge prototypes.
- x86_64 emulator and AArch64 Samsung physical-device regression coverage.
- Current documentation and categorized research archive.

### Known limitations

- The complete on-device resolve, verify, wrap, sign, and install path is validated for KCalc on x86_64 and AArch64; broad arbitrary-package and toolkit compatibility remains incomplete.
- Generated Qt and GTK wrappers use the shared Rust compositor and Android bridge, but protocol, portal, toolkit, and device coverage remains incomplete.
- AT-SPI2 toolkit integration, AArch64 KWallet compatibility, GrapheneOS-on-Pixel validation, and production security hardening remain incomplete.
