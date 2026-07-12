# Archphene

[![Publish Archphene APK](https://github.com/Nulifyer/Archphene/actions/workflows/publish-release-apk.yml/badge.svg)](https://github.com/Nulifyer/Archphene/actions/workflows/publish-release-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/Nulifyer/Archphene?display_name=tag&sort=semver)](https://github.com/Nulifyer/Archphene/releases/latest)
[![Android](https://img.shields.io/badge/Android-15%20%7C%2016-3DDC84?logo=android&logoColor=white)](#tested-systems)

Archphene is a research project for running unmodified Arch Linux desktop applications as isolated Android applications, without a VM, root access, chroot, or OS modification.

Each wrapped Linux application receives a normal Android package identity, UID, private data directory, lifecycle, and permission boundary. An Android-owned Wayland bridge renders the Linux interface and brokers Android features such as input, clipboard, documents, themes, rotation, and freeform resizing.

> [!WARNING]
> Archphene is an active prototype, not a production application store. KCalc and Mousepad are working proofs. The manager does **not yet download an arbitrary pacman package and build its wrapper entirely on-device**. GrapheneOS-specific behavior has not been validated on a supported Pixel.

<p align="center">
  <img src="docs/images/archphene-manager.png" width="360" alt="Archphene app manager showing Archphene, KCalc, and Mousepad as Android applications">
</p>

## What works today

- Runs real, unmodified Arch Linux and Arch Linux ARM ELF application payloads as child processes of ordinary Android apps.
- Gives each Linux app a distinct Android package, UID, SELinux app domain, storage sandbox, launcher entry, and system install/uninstall flow.
- Renders Qt 6/KDE and GTK 3 applications through an app-local Wayland compositor.
- Supports touch and mouse input, hardware keyboard input, Android IME input, clipboard synchronization, popup menus, secondary dialogs, rotation, and live/freeform resizing.
- Maps Android light/dark mode into GTK and Qt/KDE applications.
- Brokers user-visible files through Android's Storage Access Framework while keeping background application state private.
- Exposes a Linux Home document provider for Android file managers and sharing workflows.
- Provides an Archphene manager UI with package search, update checks, version history, pinning, prerelease policy, repository settings, and Android-confirmed APK installation.
- Verifies package names, hashes, signer continuity, version ordering, HTTPS sources, and download limits before opening an Android `PackageInstaller` session.

## Tested applications

| Application | Toolkit | Architecture | Tested environment | Status |
|---|---|---:|---|---|
| KCalc | Qt 6 / KDE Frameworks | x86_64 | Android 16 emulator | GUI, menus, keyboard, clipboard, theme, resize |
| KCalc | Qt 6 / KDE Frameworks | AArch64 | Samsung Galaxy S22 Ultra, Android 15 | GUI, menus, keyboard, clipboard, freeform resize |
| Mousepad | GTK 3 | x86_64 | Android 16 emulator | Editing, dialogs, IME, document open/save/reopen |
| Archphene manager | Android | x86_64 and AArch64 | Emulator and Samsung device | Catalog, versions, updates, settings, APK replacement plumbing |

These results prove the bridge on the listed targets only. They do not establish compatibility with every Android device, Linux application, GPU driver, or GrapheneOS release.

## How it works

```text
signed Arch package and dependency closure
                    |
                    v
        generated Android wrapper APK
        - Linux executable and runtime
        - Android Activity and permissions
        - Wayland and document bridge
                    |
                    v
          Android PackageInstaller
                    |
                    v
       separate Android UID and sandbox
                    |
                    v
 Linux process <-> Wayland bridge <-> Android UI
```

Android remains the installer and sandbox authority. The glibc compatibility patches only replace optional or blocked syscall forms needed during process startup. They do not grant permissions, bypass SELinux, or alter the Android kernel.

Official Arch Linux packages are used for x86_64. AArch64 testing uses the separate Arch Linux ARM repositories and signing keys.

## Install Archphene

Download the APK and checksum from [GitHub Releases](https://github.com/Nulifyer/Archphene/releases).

1. Download `Archphene-<version>.apk`.
2. Optionally verify it against `Archphene-<version>.apk.sha256`.
3. Allow your browser or file manager to install unknown applications when Android prompts.
4. Install the APK through Android's normal package installer.

Release APKs are signed with a dedicated persistent Archphene release key and are built with `android:debuggable="false"`.

The current release installs the manager. Until on-device package conversion is complete, Linux application wrappers still come from the repository's development build pipeline rather than being generated automatically for every package by the installed manager.

## Build from source

### Manager APK

Requirements:

- PowerShell 7
- JDK 17
- Android SDK platform 36
- Android build-tools 36.0.0

The build script uses `tooling/android-sdk` when present, then falls back to `ANDROID_SDK_ROOT` or `ANDROID_HOME`.

```powershell
./scripts/build-install-linux-manager-stub.ps1 -SkipInstall
```

Output:

```text
prototypes/linux-app-manager-stub/out/archpheneos-manager.apk
```

Install on a connected device or emulator:

```powershell
./scripts/build-install-linux-manager-stub.ps1 -Serial emulator-5554
```

Development builds use a persistent ignored debug key and remain debuggable for automated `run-as` tests. GitHub Releases use the separate non-debuggable release profile documented in [Publishing releases](docs/releases.md).

### Regression tests

The repository contains focused emulator and physical-device tests under `scripts/`. The broad entry points are:

```powershell
./scripts/test-emulator-regression.ps1
./scripts/test-arm64-physical-regression.ps1 -Serial <adb-serial>
```

The physical suite expects the curated ARM64 package/runtime workspace and a compatible attached device. Individual scripts cover manager workflows, package signatures, KCalc interactions, Mousepad documents, IME behavior, rotation, and update transactions.

## Current limitations

- The manager cannot yet resolve, verify, wrap, sign, and install arbitrary pacman packages entirely on-device.
- Manager APK replacement is tested, but GitHub Releases discovery and download are not yet a complete production self-update flow.
- KCalc and Mousepad still contain duplicated prototype Java Wayland compositor implementations. A shared native compositor is required before broad application support.
- The current wrappers duplicate large runtime closures per Android UID.
- GPU acceleration, audio, printing, camera, drag-and-drop, accessibility, keyrings, and many desktop portals are incomplete or absent.
- Android permissions require explicit bridge APIs; a Linux syscall cannot directly trigger an Android runtime permission prompt.
- Secondary Linux windows are currently mapped with prototype phone/freeform policies rather than a complete general window registry.
- GrapheneOS-on-Pixel, Android 16 KB page-size devices, and generic laptop hardware remain unvalidated.
- Archphene does not provide GrapheneOS firmware, verified boot, kernel hardening, or security updates on unsupported hardware.

See the [roadmap](docs/roadmap.md) for the detailed engineering and security backlog.

## Roadmap

1. Move the duplicated Java compositor into one shared native Wayland core with generated protocol bindings.
2. Implement atomic runtime packaging, process-group lifecycle management, and 16 KB page-size validation.
3. Complete the multi-document Android storage broker and manager-owned shared user-document provider.
4. Add on-device Arch/Arch Linux ARM repository synchronization, dependency resolution, signature verification, package extraction, wrapper generation, and persistent per-device signing.
5. Generate Android manifests and permission brokers from package capabilities.
6. Expand compatibility to GPU-accelerated editors, browsers, creative applications, audio, and desktop/freeform multi-window use.
7. Validate supported GrapheneOS Pixels without claiming GrapheneOS-equivalent security on other devices.

## Repository layout

| Path | Purpose |
|---|---|
| `prototypes/linux-app-manager-stub/` | Archphene Android manager |
| `prototypes/kcalc-android-app/` | Qt/KDE wrapper and Wayland proof |
| `prototypes/mousepad-android-app/` | GTK wrapper and document workflow proof |
| `prototypes/shared-android-bridge/` | Shared bridge extraction work |
| `patches/glibc/` | Android app-seccomp compatibility patches |
| `scripts/` | Build, package, emulator, physical-device, and regression automation |
| `docs/` | Current product, architecture, security, development, and release documentation |
| `research/` | Historical feasibility studies, experiments, source reviews, audits, and recovery evidence |

## Documentation

- [Documentation index](docs/README.md)
- [Architecture](docs/architecture.md)
- [Security model](docs/security.md)
- [Storage and documents](docs/storage.md)
- [Development](docs/development.md)
- [Roadmap](docs/roadmap.md)
- [Publishing APK releases](docs/releases.md)
- [Research archive](research/README.md)

## Contributing

The highest-value contributions are shared bridge improvements, protocol correctness, package verification, reproducible wrapper generation, Android permission/storage brokers, and automated compatibility tests.

Before adding application-specific workarounds, check whether the behavior belongs in the shared Wayland, runtime, storage, or permission layer. Include the target Android version, CPU ABI, package version, reproduction steps, and relevant logs in bug reports.

Read [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and [SUPPORT.md](SUPPORT.md) before opening a pull request or issue.

Use [GitHub Issues](https://github.com/Nulifyer/Archphene/issues) for reproducible bugs and focused design proposals.

## License

A project-wide license has not been selected yet. Third-party packages, source trees, and generated artifacts retain their respective upstream licenses. Until a project license is added, the repository should be treated as source-available research rather than a redistributable software release.