# Development

Archphene uses a Linux host and Linux containers as the authoritative build
environment. Podman, Android Emulator control, ADB, USB devices, screenshots,
and input automation all run directly on Linux.

## Prerequisites

- For the normal container build: Bash 5, Podman with a working rootless setup,
  Git, JDK 26.0.1, Python 3, jq, curl, tar, and standard GNU utilities
- For local emulator and direct Android SDK scripts: Android command-line tools,
  platform-tools, build-tools 36.0.0, platform 36, emulator, NDK
  29.0.14206865, and the `system-images;android-36;google_apis;x86_64`
  system image
- An ADB-enabled device or an AVD named `ArchpheneOS_x86_64_api36` for tests
- A persistent signing key only when producing release builds

The production base pins Gradle 9.6.1 through its wrapper and official
distribution checksum, AGP 9.3.0 with its built-in Kotlin 2.2.10, JDK 26.0.1,
SDK and Build Tools 36.0.0, NDK 29.0.14206865, and Rust/Cargo 1.88.0. Gradle
dependencies are SHA-256 verified, Cargo uses its committed lockfile, and the
native container starts from an immutable SDK image manifest. Run the
non-downloading local contract before building:

```bash
./scripts/check-production-toolchain.sh
```

The standalone `kotlin` executable is not part of the Android build. Java
source and bytecode remain targeted at Java 17 for Android compatibility even
though Gradle itself is intentionally run on the pinned JDK 26.0.1.

Build outputs, SDKs, downloaded packages, signing files, screenshots, and test artifacts are ignored.

## Device performance baseline

Run the state-preserving idle-manager gate against each maintained target:

```bash
./scripts/test-archphene-performance.sh --serial emulator-5554
./scripts/test-archphene-performance.sh --serial <adb-serial>
```

It measures three cold and three retained-process launches, switches to one
other manager section and restores the original selection, then records memory,
heap, frame, view, thread, descriptor, and idle-child metrics. Results are
written under `tooling/build/performance/` as JSON plus a full-device
screenshot. The interaction timing includes ADB and UIAutomator capture, so it
is an end-to-end automation-response ceiling rather than a claim about raw
touch latency. OEM builds that omit Android's native-allocation table report
that field as unavailable instead of zero.

Budgets have `ARCHPHENE_MAX_*` environment overrides for deliberate device
profiles. Defaults reject cold launch over 1.5 seconds, retained launch over
750 ms, PSS over 160 MiB, RSS over 300 MiB, Java or native heap PSS over 64 MiB,
more than 64 threads or 256 descriptors, any idle child process, frame p95 over
250 ms, or more than 50% jank in the short navigation sample.

## Main-thread I/O diagnostics

Debug APKs enable StrictMode after framework application initialization. Run
the state-preserving device gate with the matching exact-ABI APK:

```bash
./scripts/test-archphene-main-thread-io.sh \
  --serial emulator-5554 \
  --apk tooling/build/apk/app-debug-x86_64.apk
./scripts/test-archphene-main-thread-io.sh \
  --serial <adb-serial> \
  --apk tooling/build/apk/app-debug-arm64-v8a.apk
```

The gate cold-starts the manager, changes and restores the App scale preference,
proves the restored value after process death, runs a real pacman-backed search
and dependency resolution, and rejects every StrictMode stack that enters
Archphene code. It archives the raw scoped log and a full-device screenshot
under `tooling/build/main-thread-io/`. Samsung's framework performs
device-specific lifecycle preference operations under the app's thread policy;
the gate reports their count but does not misattribute those framework-only
stacks.

## Build in Linux

Use the Podman launcher:

```bash
./scripts/build-manager-podman.sh
```

It uses Linux containers for signed Arch package tooling and the patched glibc
build, then assembles and signs the APK in a Linux Android SDK container.

For repeated manager-only changes, reuse the verified runtime artifact:

```bash
./scripts/build-manager-podman.sh --skip-runtime
```

Build the same ABI-specific artifacts published by GitHub Releases with:

```bash
./scripts/build-manager-podman.sh --skip-runtime --artifact-abi x86_64
./scripts/build-manager-podman.sh --skip-runtime --artifact-abi arm64-v8a
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
path broker. A normal build without `--skip-runtime` refreshes both x86_64 and
AArch64 artifacts before APK assembly.

Local builds are signed with the development key and remain debuggable so the explicit `archphene_test_*` emulator hooks work. Those hooks are ignored unless Android marks the installed APK debuggable.

Use `--release-build` only with the ignored production credentials created by
`setup-github-release-signing.sh`. GitHub Actions invokes the underlying Linux
scripts directly on Ubuntu. Release builds force both APKs non-debuggable. The outputs are
`prototypes/linux-app-manager-stub/out-linux/archphene.apk` and the companion
`prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk`; the latter is also embedded in the manager.

The greenfield launcher-template release intentionally omits AGP VCS metadata:
its authenticated identity depends only on launcher inputs, not the parent
repository commit or dirty state. Verify both the omitted metadata and
byte-identical forced rebuilds with:

```bash
./scripts/test-launcher-template-reproducibility.sh
```

### Qt platform theme

Rebuild the exact-ABI Qt appearance plugin and refresh its prebuilt checksums:

```bash
./scripts/build-qt-platform-theme-podman.sh --rebuild-image
```

The script uses the pinned Arch Linux snapshot for host tools, a checksum-pinned official Arch Linux ARM Qt package for target headers and libraries, and the existing AArch64 cross-toolchain. It fails if Qt does not match the runtime manifest or either output has the wrong ELF architecture.

### Native compositor probe

Build each ABI entirely in Podman:

```bash
./scripts/build-native-compositor-probe-podman.sh --android-abi x86_64
./scripts/build-native-compositor-probe-podman.sh --android-abi arm64-v8a
```

Then use ADB for installation and result collection:

```bash
./scripts/test-native-compositor-probe.sh --android-abi x86_64 --serial emulator-5554
./scripts/test-native-compositor-probe.sh --android-abi arm64-v8a --serial <adb-serial>
```

The probe includes real Wayland keymap, clipboard, drag-and-drop, and SHM
descriptor transfer. Those operations pass through the compositor's reviewed
Unix syscall boundary, where new descriptors are wrapped in Rust ownership
immediately; client adoption takes an `OwnedFd` after JNI receives the sole
descriptor returned by `ParcelFileDescriptor.detachFd()`.

Android presentation also crosses one reviewed graphics boundary:
`ANativeWindow` references are retained and released through RAII, and native
window/Bitmap storage is exposed to the compositor only as a bounded RGBA byte
slice after format, dimensions, stride, nullness, and total size are checked.

The same probe packages a tiny executable for the selected ABI and invokes it
through the production descriptor launcher. It requires exact output and exit
status, then checks invalid-descriptor rejection; this covers the reviewed
fork/exec, inherited-FD, wait, and bounded child-output boundary on Android.

### Secrets desktop-client fixture

Rebuild the KWallet compatibility daemon and official Arch desktop-client
closure before assembling the full x86_64 secrets probe:

```bash
./scripts/build-kwallet-compat-runtime-podman.sh
./scripts/build-libsecret-probe-runtime-podman.sh
./scripts/build-secrets-capability-probe-podman.sh --android-abi x86_64
./scripts/test-android-secrets-bridge.sh \
  --android-abi x86_64 --serial emulator-5554
```

The default full-client build fails closed if either fixture is absent. The
official Arch binaries require a 4 KB-page x86_64 emulator. Use
`--without-client-fixtures` only for the separately identified core encrypted
store and private Secret Service wire-contract lane, such as the fixture-free
AArch64 probe; it does not establish desktop-client compatibility.

## Install from Linux

Transfer and exercise the resulting APK:

```bash
./scripts/install-apk.sh --serial emulator-5554
```

The adapter finds ADB under `tooling/android-sdk`, `ANDROID_SDK_ROOT`, or
`ANDROID_HOME`. It can install another generated wrapper without rebuilding it:

```bash
./scripts/install-apk.sh --apk path/to/wrapper.apk --package org.example.wrapper --serial <adb-serial>
```

The `build-install-*.sh` scripts remain prototype fixtures. Production builds
use the shared Linux toolchain.

## Run tests

Broad suites:

```bash
./scripts/test-emulator-regression.sh
./scripts/test-arm64-physical-regression.sh --serial <adb-serial>
```

The physical suite re-verifies every AArch64 runtime package against the
staged keyring before touching the device. Prepare both the staged runtime and
the read-only package cache volume with:

```bash
CONTAINER_CLI=podman SKIP_CHOWN=1 bash scripts/build-ci-package-runtime-arm64.sh
```

`--skip-signature-gate` exists for focused device iteration only; it is not a
release-validation result.

For a clean debuggable emulator manager, provision the maintained KCalc and
Mousepad fixtures through the real on-device package transaction before running
the suite:

```bash
./scripts/test-emulator-regression.sh --serial emulator-5554 --provision
```

Provisioning is intentionally restricted to emulator serials. It grants the
manager's `REQUEST_INSTALL_PACKAGES` app-op on that disposable target and still
confirms every generated wrapper through Android's Package Installer. Physical
device packages and app data are never cleared or replaced by this option.

Focused scripts under `scripts/` cover manager workflows, package signatures,
update transactions, KCalc input/clipboard/resize, Mousepad documents and IME,
rotation, file-descriptor lifecycle, and toolkit appearance. Run the live
system-mode regressions with:

```bash
./scripts/test-kcalc-live-theme.sh --serial emulator-5554
./scripts/test-mousepad-live-theme.sh --serial emulator-5554
./scripts/test-gnome-text-editor-live-theme.sh --serial emulator-5554
./scripts/test-gnome-text-editor-input.sh --serial emulator-5554
./scripts/test-kate-live-theme.sh --serial emulator-5554
```

The AArch64 native readiness gate uses the configured host NDK when available
and otherwise runs in the pinned Android-native Podman image, so it does not
require a second host-side SDK installation:

```bash
./scripts/test-arm64-bridge-readiness.sh
```

The generic policy test verifies that an explicit manager choice overrides the
opposite Android mode and that Material You changes both generated toolkit
configuration and rendered Linux-app pixels:

```bash
./scripts/test-linux-app-appearance-policy.sh \
  --serial emulator-5554 \
  --package org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2 \
  --toolkit qt6 --label KCalc
```

These tests restore the prior Android night mode and manager Linux-appearance
preferences on exit.

Theme propagation is not a visual-quality pass. Run the source contract and the
focused artifact-producing geometry/render checks with:

```bash
./scripts/test-linux-appearance-source-contract.sh
./scripts/test-kcalc-menu-switch.sh --serial emulator-5554
./scripts/test-mousepad-secondary-window.sh --serial emulator-5554
./scripts/test-mousepad-material-you-visual.sh --serial emulator-5554
./scripts/test-foot-visual-quality.sh --serial emulator-5554
./scripts/test-foot-live-theme.sh --serial emulator-5554
./scripts/test-linux-control-density-matrix.sh \
  --serial emulator-5554 --package PACKAGE --label NAME --toolkit qt6
./scripts/test-gpu-visual-quality.sh --serial emulator-5554 --package PACKAGE
./scripts/test-real-app-accessibility.sh \
  --serial emulator-5554 \
  --target-package org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2 \
  --profile KCalc
./scripts/test-real-app-accessibility.sh \
  --serial emulator-5554 \
  --target-package org.archphene.linux.pb1623042aeee4267eb8c86dead4b2dd7 \
  --profile Kate
./scripts/test-kate-workflows.sh --serial emulator-5554
```

The device checks do not install missing fixtures. The real-app accessibility
test fails closed unless the current test-only AccessibilityService is already
installed; pass `--probe-apk` only after installation has been explicitly
approved. KCalc, Kate, and Mousepad retain PNG/raw frames, scoped logs,
generated theme files, and exported semantic trees under
`tooling/artifacts/visual-audit/`. The Kate workflow snapshots and restores its
existing `katerc`, session directory, and feedback state even when an assertion
fails.
Review the artifacts using [the visual-quality gate](linux-visual-quality.md).

Run the release display profiles independently with:

```bash
./scripts/test-release-display-matrix.sh --serial emulator-5554
```

Run Kate through a tablet resize and a temporary 1920x1080 emulator display
with display-targeted input using:

```bash
./scripts/test-kate-large-display.sh --serial emulator-5554
```

The focused test refuses to reuse an existing secondary display and restores
the emulator's prior size/density overrides after removing the display it
created.

Run the generic Android document gate against Kate with:

```bash
./scripts/test-mousepad-android-document-workflow.sh \
  --serial emulator-5554 \
  --package org.archphene.linux.pb1623042aeee4267eb8c86dead4b2dd7 \
  --label Kate
```

Despite its historical filename, the gate is wrapper-generic: it creates
unique self-cleaning fixtures and proves SAF selection, private import, editor
writeback, destructive cold reopen, and DocumentsUI provider browse.

The script restores display size, density, Android font scale, and night mode on exit.

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
