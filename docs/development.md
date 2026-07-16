# Development

Archphene uses Linux containers as the authoritative build environment. Windows is
the supported host adapter for Podman, Android Emulator control, ADB, USB devices,
screenshots, and input automation. Compilers, Arch package tools, glibc builds, APK
assembly, signing, and release publishing should not depend on Windows-native ports.

## Prerequisites

- PowerShell 7 and Podman Desktop
- Git
- Android platform-tools and emulator on Windows
- ADB-enabled test device or configured AVD
- A persistent signing key when producing release builds

Build outputs, SDKs, downloaded packages, signing files, screenshots, and test artifacts are ignored.

## Build in Linux

From Windows, use the thin Podman launcher:

```powershell
./scripts/build-manager-podman.ps1
```

It enters the Podman Linux VM for signed Arch package tooling and the patched glibc
build, then assembles and signs the APK in a Linux Android SDK container. No
Windows-native compiler, archive tool, shell port, or Android build tool is used.

For repeated manager-only changes, reuse the verified runtime artifact:

```powershell
./scripts/build-manager-podman.ps1 -SkipRuntime
```

Build the same ABI-specific artifacts published by GitHub Releases with:

```powershell
./scripts/build-manager-podman.ps1 -SkipRuntime -ArtifactAbi x86_64
./scripts/build-manager-podman.ps1 -SkipRuntime -ArtifactAbi arm64-v8a
```

Use the universal flavor only for local cross-device development. Release APKs are single-ABI so Android installs only the matching package runtime and wrapper templates.

The AArch64 bootstrap can be rebuilt independently on Linux with:

```bash
CONTAINER_CLI=podman JOBS=8 bash scripts/build-ci-package-runtime-arm64.sh
```

It uses a cacheable cross-toolchain image and a persistent package cache. Cache
entries are signature-verified on every build before extraction. The ignored
output is `tooling/build/ci-package-runtime-arm64/` with a complete `SHA256SUMS`
catalog, pinned keyring/package-signer/glibc provenance, and the AArch64 glibc
path broker. A normal non-`SkipRuntime` manager build refreshes both x86_64 and
AArch64 artifacts before APK assembly.

Local builds are signed with the development key and remain debuggable so the explicit `archphene_test_*` emulator hooks work. Those hooks are ignored unless Android marks the installed APK debuggable.

Use `-ReleaseBuild` only with the ignored production credentials created by
`setup-github-release-signing.ps1`. GitHub Actions invokes the underlying Linux
scripts directly on Ubuntu. Release builds force both APKs non-debuggable. The outputs are
`prototypes/linux-app-manager-stub/out-linux/archphene.apk` and the companion
`prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk`; the latter is also embedded in the manager.

### Qt platform theme

Rebuild the exact-ABI Qt appearance plugin and refresh its prebuilt checksums:

```powershell
./scripts/build-qt-platform-theme-podman.ps1 -RebuildImage
```

The script uses the pinned Arch Linux snapshot and fails if Qt does not match the runtime manifest.

### Native compositor probe

Build each ABI entirely in Podman:

```powershell
./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi x86_64
./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi arm64-v8a
```

Then use Windows only for ADB installation and result collection:

```powershell
./scripts/test-native-compositor-probe.ps1 -AndroidAbi x86_64 -Serial emulator-5554
./scripts/test-native-compositor-probe.ps1 -AndroidAbi arm64-v8a -Serial <adb-serial>
```

## Install from Windows

Windows only needs to transfer and exercise the resulting APK:

```powershell
./scripts/install-apk.ps1 -Serial emulator-5554
```

The adapter finds ADB under `tooling/android-sdk`, `ANDROID_SDK_ROOT`, or
`ANDROID_HOME`. It can install another generated wrapper without rebuilding it:

```powershell
./scripts/install-apk.ps1 -Apk path/to/wrapper.apk -Package org.example.wrapper -Serial <adb-serial>
```

The older `build-install-*.ps1` scripts remain prototype fixtures while their
build portions are migrated into the shared Linux toolchain. Do not extend them
with new production build logic.

## Run tests

Broad suites:

```powershell
./scripts/test-emulator-regression.ps1
./scripts/test-arm64-physical-regression.ps1 -Serial <adb-serial>
```

Focused scripts under `scripts/` cover manager workflows, package signatures, update transactions, KCalc input/clipboard/resize, Mousepad documents and IME, rotation, and file-descriptor lifecycle.

## Development rules

- Keep Android as the authority for identity, permissions, storage grants, lifecycle, and installation.
- Fix shared protocol/runtime behavior in shared layers rather than adding application-title or widget-coordinate special cases.
- Reject malformed protocol and package input deterministically.
- Preserve package signer continuity and never commit signing keys.
- Keep production builds non-debuggable.
- Add tests proportional to protocol, storage, package, or security impact.
- Record dated experiment evidence under `research/experiments/`, not in current product documentation.

## Release builds

See [Publishing releases](releases.md). Release signing files remain under ignored `tooling/signing/` and require an offline backup.
