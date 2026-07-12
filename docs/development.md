# Development

The repository currently targets Windows and PowerShell for the established emulator and device workflows.

## Prerequisites

- PowerShell 7
- Git
- JDK 17
- Android SDK platform 36
- Android build-tools 36.0.0
- Android platform-tools and emulator
- ADB-enabled test device or configured AVD
- Additional native toolchains and package workspaces for wrapper/runtime builds

Build outputs, SDKs, downloaded packages, signing files, screenshots, and test artifacts are ignored.

## Build the manager

```powershell
./scripts/build-install-linux-manager-stub.ps1 -SkipInstall
```

The script uses `tooling/android-sdk`, `ANDROID_SDK_ROOT`, or `ANDROID_HOME`.

Install and launch on an emulator:

```powershell
./scripts/build-install-linux-manager-stub.ps1 -Serial emulator-5554
```

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