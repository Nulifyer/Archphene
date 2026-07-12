# Linux package to APK store

Date: 2026-07-09

Goal: an Obtanium-like Android app that points at Linux app sources such as pacman repositories, AUR packages, and Git repositories, builds or repackages the app as an APK, and installs/updates it on a GrapheneOS-derived system so Linux desktop apps behave like normal Android apps.

## Short answer

Yes, a limited version is possible as an Android app. A serious generic version needs OS support.

The limited version can:

- fetch package metadata from pacman/AUR/Git sources.
- build or download binaries.
- generate a wrapper APK.
- sign the APK.
- request installation through Android's PackageInstaller.
- update the app later if the same signing key and package name are used.

But a normal Android app cannot make arbitrary Linux desktop apps fully native Android citizens by itself. It cannot change Android's package manager model, SELinux policy, graphics stack, per-app portal enforcement, or install packages silently unless it has privileged/device-owner style authority.

The serious version should be a pair:

```text
LinuxAPK Store app
  source discovery, updates, user UI, signing, policy selection

GrapheneOS-derived platform services
  package/runtime integration, spawner, SELinux labels, portals, desktop mode, Wayland bridge
```

## What can work without OS changes

An Android app can act like a source-based app store:

```text
pacman/AUR/Git source
  -> build or download artifacts
  -> bundle runtime + executable + launcher wrapper
  -> generate APK
  -> sign APK
  -> install via Android PackageInstaller
```

The generated APK would contain:

- `AndroidManifest.xml`
- an Activity for launcher/desktop-mode integration
- icons/name/version metadata
- a native launcher binary or JNI library
- app payload under APK assets or native library paths
- a glibc or bionic-compatible runtime
- a minimal Wayland/X/VNC/display bridge
- permission declarations such as network, audio, camera, files, notifications

Android already supports apps containing native C/C++ code through the NDK model. Android's PackageInstaller can install and update APKs, but committing an install may require user intervention unless the installer has special authority such as device-owner/profile-owner status.

This means the concept is feasible for a prototype and for curated apps.

## What will not be generic without OS changes

### glibc vs bionic

Arch packages are built for glibc. Android apps normally use bionic and the Android NDK runtime model. A generated APK has three options:

- rebuild the app against bionic with the NDK.
- bundle glibc and enough Linux runtime inside the APK.
- use a compatibility/runtime layer similar to Termux/proot, but hidden behind the app wrapper.

Rebuilding against bionic is cleaner but not generic. Bundling glibc is more compatible but heavier and harder to secure.

### Executable placement

Android expects app code to be installed as APK contents and native libraries. Modern Android restricts execution from arbitrary writable app data locations. A packager must place executable payload in install-time executable locations or use a platform-supported runtime path.

This pushes the design toward:

- packaging actual executables into the APK at build time.
- avoiding post-install mutable code downloads.
- rebuilding the APK for every app update.

That is good for code integrity, but it means pacman-style live mutation inside the installed app is the wrong model.

### Display

Linux desktop apps need Wayland or X11. A plain APK can provide its own display stack, but then every app either:

- bundles its own renderer/server bits, wasting storage and memory, or
- depends on a separate shared runtime app, which weakens the "each app is independent" model and complicates updates.

For a laptop-like phone desktop mode, the OS should provide the Wayland-to-Android Surface integration once, not per app.

### Permissions

An APK can request Android permissions, but Linux code does not automatically use Android APIs.

Example: a Linux app opening `/dev/video0` is not the same as an Android app using Camera2. To preserve Android/GrapheneOS permissions, the Linux app must be patched or routed through brokers:

- files through Android Storage Access Framework
- camera through Android Camera APIs
- microphone through Android audio capture APIs
- notifications through Android NotificationManager
- location through Android LocationManager
- USB through Android USB permission APIs

Without OS support, a wrapper APK can only mediate what the app has been adapted to use.

### Updates and signing

Android updates require package-name/signature continuity. The store app can sign generated APKs itself, but then:

- losing the signing key breaks updates.
- rebuilding from source must be reproducible enough to trust.
- AUR PKGBUILDs are arbitrary shell code and should be treated as untrusted build scripts.
- each generated app needs clear provenance: source URL, commit/tag/pkgver, package hash, build log, patches, runtime version.


## Day-one bridge

The APK store solves acquisition and updates. A compatibility bridge solves execution.

See [Day-one compatibility bridge](day-one-compatibility-bridge.md) for the runtime bridge design: Wayland-to-Android Surface, D-Bus/session bus shim, portal bridge, audio bridge, clipboard/notification bridge, patched glibc executable launch, and app-private filesystem compatibility.

## Product architecture

### MVP: LinuxAPK Store as a normal Android app

Scope:

- curated sources only.
- build off-device or on a trusted local build machine first.
- generate APKs.
- install/update through PackageInstaller.
- each APK bundles enough runtime to launch one app.
- GUI through a bundled Wayland/X bridge or Android-native wrapper.

Best first apps:

- CLI tools with simple terminal UI.
- SDL apps.
- simple Wayland-native apps.
- apps that can be rebuilt against Android/bionic.

Avoid first:

- Firefox/Chromium.
- full office suites.
- apps requiring systemd, D-Bus services, udev, FUSE, raw GPU/DRM, printing, scanners, or arbitrary home-directory access.

### Better MVP: normal app plus shared runtime APK

Scope:

- one `org.archphene.runtime` APK contains glibc/runtime/display bridge.
- generated app APKs contain app payload and metadata.
- generated app APKs bind to runtime service.

Pros:

- less duplication.
- central runtime updates.
- easier display integration.

Cons:

- app isolation is weaker unless runtime service preserves caller identity perfectly.
- Android permissions are split between wrapper app and runtime app.
- if the runtime app holds broad permissions, every Linux app may indirectly gain them unless the runtime enforces policy.

### Serious version: platform-supported Linux APKs

Scope:

- LinuxAPK Store is the UI/source manager.
- OS provides Linux app runtime services.
- PackageManager recognizes Linux app descriptors or generated wrapper packages.
- each Linux app gets unique UID, SELinux label, cgroup, app data, lifecycle.
- OS-provided Wayland bridge.
- OS-provided portals.
- package ingestion blocks mutable host changes.

This is the route that can make GrapheneOS viable as a phone/laptop OS without a VM.

## Suggested package format

Working extension: `.lapk` for source/intermediate packages, emitted as normal `.apk` for installation.

Descriptor:

```json
{
  "id": "org.archphene.firefox",
  "name": "Firefox",
  "source": {
    "type": "pacman",
    "repo": "extra",
    "package": "firefox",
    "version": "x.y.z"
  },
  "entrypoint": "/usr/bin/firefox",
  "desktopFile": "firefox.desktop",
  "runtime": "org.archphene.runtime.glibc.aarch64/1",
  "permissions": [
    "network",
    "downloads",
    "notifications"
  ],
  "portals": [
    "file-open",
    "file-save",
    "clipboard",
    "audio-output"
  ]
}
```

Generated APK:

```text
AndroidManifest.xml
classes.dex                 wrapper Activity/service
lib/arm64-v8a/launcher.so   native launcher or JNI glue
assets/linux-app/           immutable app payload
assets/descriptor.json
res/mipmap*/icon.*
```

## Build pipeline

### Pacman repo

```text
sync database
  -> resolve package + deps
  -> verify signatures
  -> extract payload
  -> reject unsafe install scripts
  -> derive desktop metadata
  -> patch paths/interpreter if needed
  -> generate descriptor
  -> build APK
  -> sign APK
```

### AUR

```text
fetch PKGBUILD
  -> static risk scan
  -> build in disposable sandbox
  -> record source hashes and build log
  -> package result like pacman package
```

AUR should be opt-in and marked lower trust. PKGBUILDs are executable build recipes.

### Git

```text
fetch tag/commit
  -> detect build system
  -> use recipe template
  -> build against Android/bionic or Linux runtime
  -> package result
```

Git sources need per-project recipes. There is no universal "turn Git repo into APK" rule.

## Security model

The store must avoid becoming "curl | sh for Android."

Required controls:

- source allowlist for early builds.
- signature/hash verification.
- reproducible build support where possible.
- visible provenance per app.
- per-app signing key management.
- no arbitrary post-install scripts on the phone.
- no shared runtime permission bypass.
- no direct raw device access.
- clear distinction between bionic-native APKs and glibc/Linux-runtime APKs.

## How this makes GrapheneOS more laptop-like

This fits Android desktop mode well:

- each Linux app gets a launcher icon.
- each app appears in recents/taskbar.
- Android handles app install/update/uninstall.
- GrapheneOS controls permissions and sensors.
- desktop apps run on the phone CPU, especially ARM64.
- external display/keyboard/mouse become useful without a separate laptop.

The missing piece is high-quality OS-level desktop integration. A store app can prove the packaging model, but the laptop experience requires platform services for display, lifecycle, portals, and per-app security.

## Recommended next prototype

Build a proof-of-concept `LinuxAPK Store` with one curated app:

1. Choose an ARM64 Wayland-native terminal or SDL app.
2. Build a fixed runtime.
3. Generate an APK wrapper.
4. Sign it with a local test key.
5. Install through PackageInstaller.
6. Launch on GrapheneOS desktop mode.
7. Verify app identity, permissions, storage, updates, and uninstall.

Success criteria:

- Android sees it as its own app.
- it launches from the launcher.
- it works on external display.
- update installs over the previous version.
- uninstall removes app data.
- no host filesystem mutation.

## Source map

- Android PackageInstaller: https://developer.android.com/reference/android/content/pm/PackageInstaller
- Android `REQUEST_INSTALL_PACKAGES`: https://developer.android.com/reference/android/Manifest.permission#REQUEST_INSTALL_PACKAGES
- Android NDK/native code: https://developer.android.com/ndk/guides
- Android manifest/package identity: https://developer.android.com/guide/topics/manifest/manifest-intro
- Android app sandbox: https://source.android.com/docs/security/app-sandbox
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
