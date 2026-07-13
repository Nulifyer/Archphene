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

Use `-ReleaseBuild` only with the ignored production credentials created by
`setup-github-release-signing.ps1`. GitHub Actions invokes the underlying Linux
scripts directly on Ubuntu. The output is
`prototypes/linux-app-manager-stub/out-linux/archphene.apk`.

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