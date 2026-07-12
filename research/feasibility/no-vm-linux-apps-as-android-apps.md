# No-VM Linux apps as Android-managed apps

Date: 2026-07-09

Goal: generic Linux desktop apps should behave like Android apps on a GrapheneOS-derived system: launcher entries, recents, per-app permissions, storage isolation, lifecycle controls, hardening toggles, and update accountability through the OS.

Constraint: do not use a VM as the product architecture.

## Bottom line

This requires custom Android/GrapheneOS platform development. It is not something pacman, Termux, proot, Waydroid, or a chroot can provide cleanly.

The right no-VM model is a new host runtime:

```text
GrapheneOS-derived host
  PackageManager understands Linux app descriptors
  ActivityTaskManager sees Linux apps as launchable apps
  LinuxZygote / LinuxAppSpawner starts native ELF processes
  SELinux labels each Linux app like an Android app
  per-app UID / data directory / cgroup / seccomp profile
  Android portal brokers expose files, network, audio, camera, clipboard, notifications
  Wayland compositor bridge renders Linux windows into Android Surfaces
  pacman-compatible package layer feeds packages into this model
```

The Linux process would not be "just another shell process." It would be a new Android-recognized app process class.

## Why direct pacman install is not enough

Android's app sandbox is based on unique app UIDs, Linux process isolation, SELinux, app-specific storage, package identity, permissions, and framework mediation. The AOSP security documentation says Android assigns a unique UID to each app and uses that UID to set up a kernel-level application sandbox. It also states that native code is sandboxed the same way as interpreted code.

GrapheneOS adds more hardening and deliberately avoids increasing attack surface. A host-level pacman database installing daemons, libraries, systemd units, `/usr` files, D-Bus services, device rules, and desktop integration directly into the Android root would bypass most of that model.

So the package manager cannot own the host. The OS must own the package manager.

## Compatibility layer choices

### Choice A: Rebuild Linux apps for Android/Bionic

This is the cleanest security model:

- Apps are APKs.
- Native code is built with the Android NDK.
- Android permissions work naturally.
- The normal app sandbox applies.

But it does not meet the "generic Arch packages" goal. Most Arch binaries expect glibc, normal FHS paths, desktop services, and Linux graphics/audio stacks.

Use this for core apps where security matters more than package compatibility.

### Choice B: Add a glibc Linux ABI runtime to Android

This is the serious no-VM route.

Add a system-provided Linux runtime containing:

- glibc dynamic loader and libraries.
- A controlled FHS-like root skeleton.
- Linux desktop libraries.
- Wayland client stack.
- xdg-desktop-portal client stack.
- Fontconfig, icon themes, MIME data, certificates, locales.
- Optional compatibility shims for common distro assumptions.

Each Linux app gets:

- Android package identity.
- Android UID/appId.
- Android data directory.
- SELinux MCS category.
- Isolated mount namespace.
- Controlled root view containing runtime, app files, and app data.
- No direct device nodes except safe pseudo-devices.
- No direct access to Android framework private sockets.

This is basically "Flatpak-like app model implemented inside Android and backed by Arch packages."

### Choice C: proot/chroot

This is useful for prototyping only.

It can run some binaries but does not satisfy the product goal because the OS does not truly own each app's identity, lifecycle, permissions, or resource mediation.

## Required platform components

### 1. Linux package ingestion

Do not install Arch packages into `/`.

Instead, create a new package format or ingestion pipeline:

```text
Arch package / pacman repo
  -> metadata extractor
  -> dependency resolver
  -> Linux app descriptor
  -> immutable app payload store
  -> Android package registration
```

For example:

```text
org.archphene.firefox
  source package: extra/firefox
  executable: /usr/bin/firefox
  desktop file: firefox.desktop
  icon: firefox
  declared capabilities:
    network
    downloads
    camera optional
    microphone optional
    notifications
```

The Android-side install result must be a real package identity, not a hidden entry in one "Linux Runtime" app.

### 2. PackageManager integration

Android PackageManager needs a new kind of package:

- Normal APK package: Java/Kotlin/ART/native Android app.
- Linux app package: native ELF entrypoint with Linux runtime descriptor.

The Linux package must still produce:

- package name
- version
- signing/provenance metadata
- appId/UID assignment
- permission declarations
- launcher activity equivalent
- backup/uninstall behavior
- per-user install state
- update state

This can be implemented either by extending PackageManager directly or by generating minimal wrapper APKs plus a privileged system service. Direct PackageManager integration is cleaner long-term.

### 3. Linux process spawning

Android's normal Zygote path is ART-oriented. Linux apps need a native process spawn path with the same security posture.

Create a `LinuxZygote` or `LinuxAppSpawner` that:

- receives launch requests only from system_server.
- sets UID/GID and supplementary groups.
- enters the app's SELinux context.
- joins the app's cgroup.
- applies seccomp.
- creates a mount namespace.
- sets up `/proc`, `/dev`, `/tmp`, runtime paths, and app data paths.
- launches the correct ELF loader and executable.

For glibc binaries, either preserve the expected interpreter path such as `/lib/ld-linux-aarch64.so.1` inside the app mount namespace or patch ELF interpreters during package ingestion.

Avoid `setuid` helpers. Android/GrapheneOS hardening expects privileged transitions to be tightly owned by platform code and SELinux policy.

### 4. SELinux model

Create Linux app domains parallel to Android app domains:

```text
u:r:linux_app:s0:cNN,cMM
u:r:linux_app_exec:s0
u:object_r:linux_app_data_file:s0:cNN,cMM
u:object_r:linux_runtime_file:s0
u:r:linux_portal_service:s0
```

Rules:

- Linux apps cannot read other Linux app data.
- Linux apps cannot read Android app data.
- Linux apps cannot access system/vendor/product partitions except approved runtime files.
- Linux apps cannot talk to arbitrary Binder services.
- Linux apps cannot access raw camera, microphone, location, USB, Bluetooth, input, DRM, GPU, or sensors device nodes.
- Linux apps communicate with Android resources through portal/broker services.

The MCS category pattern should mirror Android app isolation.

### 5. Mount namespace and filesystem view

Each app gets a synthetic root:

```text
/
  app/               immutable app payload
  runtime/           immutable Linux runtime
  usr/               merged view from runtime + app dependencies
  etc/               generated app-specific config
  home/app/          app-private home
  tmp/               private tmpfs
  proc/              filtered proc
  dev/               minimal pseudo-devices
  run/archphene/     broker sockets
```

The app should not see the real Android `/`.

Use read-only bind mounts for runtime and app payload. Use per-app writable storage under Android's app data path. Use noexec/nodev/nosuid wherever possible.

### 6. Display and input

Linux GUI apps should talk Wayland, not X11 first.

Create:

- a host Android `LinuxWindowHost` app/service with a Surface.
- a Wayland compositor running as a trusted system component.
- per-app Wayland sockets with identity tracking.
- input translation from Android touch/mouse/keyboard to Wayland events.
- window-to-task mapping so each Linux app appears in recents and task switching.

X11 can be supported through Xwayland later, but X11 weakens isolation because clients can often inspect or interfere with other clients.

### 7. Permissions and portals

Do not grant Linux apps direct device access. Implement portal services.

Map Android permissions to Linux portal capabilities:

| Android-visible permission | Linux-side mechanism |
| --- | --- |
| Files and folders | Android Storage Access Framework, exposed through document portal or FUSE |
| Network | per-app UID policy, network namespace, firewall marks, or brokered socket API |
| Camera | Android Camera API broker, not `/dev/video*` |
| Microphone | Android AudioRecord broker |
| Speakers | Android AudioTrack / AAudio bridge |
| Notifications | Android NotificationManager bridge |
| Clipboard | Android clipboard bridge with foreground/lifecycle policy |
| Location | Android LocationManager broker |
| USB | Android USB permission broker passing approved file descriptors |
| Bluetooth | Android Bluetooth API broker |
| Sensors | Android SensorManager broker |

Flatpak's portal model is the closest existing Linux desktop pattern: apps have limited host access by default, and portals mediate things like file opening, notifications, screenshots, printing, and session interaction.

Android's Storage Access Framework is the closest existing Android storage pattern: the user picks documents/directories, and the app receives URI access instead of blanket filesystem permissions.

### 8. Network control

Network is one of the hardest parts without a VM.

Android normally applies per-UID network policy. If each Linux app has its own Android UID and runs as that UID, the kernel and Android network policy can enforce many controls naturally.

Needed additions:

- make Linux app processes visible to netd/NetworkPolicyManager under their own app UIDs.
- ensure helper processes do not run under a shared runtime UID when doing network I/O.
- prevent shared Linux daemons from becoming network proxies for denied apps.
- optionally create per-app network namespaces for stricter behavior.

Rule: no shared "Linux runtime" daemon should perform network I/O on behalf of every app unless it can preserve caller identity and enforce Android permissions.

### 9. Audio, camera, GPU, and hardware

For GrapheneOS-grade goals, raw device nodes should be unavailable.

GPU is the exception that needs careful design. Practical GUI performance needs hardware acceleration. Options:

- mediated EGL/Vulkan path through Android graphics stack.
- Wayland buffer passing and Android graphics buffers.
- software rendering as baseline fallback.

Camera/mic/location/sensors should always go through Android APIs because Android permissions and privacy indicators live there.

### 10. App lifecycle

Linux apps need Android lifecycle integration:

- launch from launcher.
- foreground/background state.
- recents task.
- background execution limits.
- force stop.
- battery restrictions.
- notification permission.
- per-app storage clearing.
- uninstall cleanup.
- crash reporting.

Linux processes do not naturally understand Android lifecycle. The wrapper/host must translate lifecycle events into signals, portal state, freeze/thaw, or graceful shutdown.

### 11. Package updates and trust

Arch repositories are rolling and optimized for Arch systems, not GrapheneOS' verified host model.

For a secure product:

- mirror and snapshot Arch packages.
- verify Arch package signatures.
- add OS-level transparency metadata.
- run package ingestion in a sandboxed build/index pipeline.
- block maintainer scripts from mutating the host.
- convert package install scripts into declarative app metadata where possible.
- expose updates through Android's package/update UI.

Do not run arbitrary `post_install` scripts as root on the phone.

## What must be changed in GrapheneOS/AOSP

Minimum platform changes:

- PackageManager: Linux package identity, permissions, install/uninstall/update.
- system_server: launch/lifecycle integration.
- SELinux policy: new domains and file labels.
- init: start Linux runtime services.
- zygote/process: native Linux app spawner.
- storage: app data, document bridge, optional FUSE.
- graphics: Wayland compositor and Android Surface bridge.
- audio/camera/location/sensors: broker services.
- network: per-UID Linux process policy integration.
- settings UI: show Linux apps alongside Android apps.
- launcher/recents: Linux task representation.
- OTA: keep runtime and package store compatible across OS updates.

## Major risks

- Attack surface: glibc, desktop libraries, Wayland/Xwayland, font/media parsers, D-Bus-like services, and package ingestion add local attack surface.
- Permissions mismatch: Linux apps often expect ambient authority while Android expects explicit brokered authority.
- Generic package scripts: Arch install hooks cannot be allowed to mutate the Android host.
- Shared services: every shared broker must preserve app identity and enforce policy.
- X11: prefer Wayland-native apps and treat Xwayland apps as lower-trust compatibility mode.

## Recommended implementation path

### Phase 1: single hardcoded Linux app

Target a Wayland-native app with few permissions.

Build:

- fixed glibc runtime image.
- fixed app payload.
- Android wrapper Activity.
- LinuxAppSpawner prototype.
- Wayland-to-Surface display.
- app-private storage.
- no network, no camera, no microphone.

Success: a Linux GUI app launches from Android launcher and appears in recents as its own app.

### Phase 2: per-app UID and SELinux

Add:

- real package identity.
- unique UID.
- Linux SELinux app domain with MCS category.
- separate data directories.
- force-stop/uninstall behavior.

Success: two Linux apps cannot read each other's data or processes.

### Phase 3: portals

Add:

- file open/save through Android picker.
- network permission.
- notifications.
- clipboard.
- audio output.

Success: Linux apps request resources through Android-visible controls.

### Phase 4: package ingestion

Add:

- Arch package extraction.
- dependency closure resolution.
- app descriptor generation from `.desktop` files.
- immutable package store.
- wrapper package generation or direct PackageManager registration.

Success: install one Arch package and have it appear as an Android-managed Linux app.

### Phase 5: hardening

Add:

- seccomp profiles.
- Landlock where available.
- stricter mount namespace.
- no-new-privileges.
- read-only runtime/app payloads.
- syscall audit mode.
- crash and denial telemetry.

Landlock is relevant because the upstream kernel documents it as an unprivileged way for processes to restrict ambient rights such as filesystem and network access, stacked with system-wide access controls.

### Phase 6: compatibility expansion

Add:

- Xwayland compatibility mode.
- font/media portal coverage.
- print support.
- USB portal.
- GPU acceleration hardening.
- more desktop toolkits.

## Best first prototype target

Do not begin with Firefox or a full desktop environment.

Start with:

- `weston-terminal`, `foot`, or another simple Wayland terminal.
- then a simple GTK/Qt Wayland app.
- then a networked app.
- then Firefox/Chromium only after the sandbox, portals, and graphics path are stable.

Firefox/Chromium already have complex sandboxes. Running them inside a new Android-hosted Linux sandbox will expose many policy and namespace assumptions at once.

## Design name

Working name: **Archphene Linux App Runtime**.

Package type: **LAPK** or **Linux APK descriptor**.

Runtime service names:

- `LinuxAppManager`
- `LinuxAppSpawner`
- `LinuxWindowHost`
- `LinuxPortalService`
- `LinuxPackageIngestor`

## Source map

- Android app sandbox: https://source.android.com/docs/security/app-sandbox
- Android SELinux: https://source.android.com/docs/security/features/selinux
- Android NDK/native code: https://developer.android.com/ndk/guides
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- GrapheneOS features and attack-surface posture: https://grapheneos.org/features
- Flatpak sandbox/portal model: https://docs.flatpak.org/en/latest/sandbox-permissions.html
- Bubblewrap namespace sandbox model: https://github.com/containers/bubblewrap
- Landlock: https://docs.kernel.org/userspace-api/landlock.html
