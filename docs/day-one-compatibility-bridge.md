# Day-one compatibility bridge

Date: 2026-07-09

Goal: make packaged Linux apps run immediately as APKs on GrapheneOS/Android, before the OS has full native Linux-app platform support.

This is the bridge layer between "we generated an APK" and "the Linux app actually works."

## Core idea

Each generated Linux APK should contain an Android wrapper plus a Linux-facing compatibility bridge:

```text
Android app process / package identity
  Activity + Surface + lifecycle + permissions
  native launcher
  bridge services
    Wayland server
    D-Bus/session bus shim
    portal service
    audio server shim
    clipboard/notification bridge
    file import/export bridge
  Linux app process
    glibc loader
    app binary
    app dependency closure
```

The Linux app should think it is running in a small Linux desktop session. Android should think it is running one normal APK under one app UID.

## Day-one rule

Prefer app-local compatibility over system mutation.

Do not require:

- root
- a VM
- host pacman
- modifying Android `/`
- mounting a real Arch root
- privileged device nodes
- systemd
- raw DRM/KMS
- raw camera/microphone access

Do require:

- generated APK per app
- stable package name/signing key
- app-local runtime and dependencies
- Android wrapper Activity
- Linux-facing bridge protocols
- explicit package-level Android permissions

## APK layout

Example generated APK:

```text
AndroidManifest.xml
classes.dex
res/mipmap*/icon.*
assets/descriptor.json
assets/desktop/firefox.desktop
assets/share/...
lib/arm64-v8a/libarchphene_launcher.so
lib/arm64-v8a/libarchphene_bridge.so
lib/arm64-v8a/libld-linux-aarch64.so
lib/arm64-v8a/libc.so.6
lib/arm64-v8a/libwayland-client.so
lib/arm64-v8a/libgtk-3.so
lib/arm64-v8a/libapp_exec.so
```

For day one, package executable ELF payloads in install-time executable locations. Avoid downloading mutable executable code into app data after install.

If the source package contains `/usr/bin/foo`, package ingestion can:

- copy it into the APK native library area as an executable payload.
- patch the ELF interpreter to the packaged glibc loader.
- patch rpath/runpath to the packaged runtime directory.
- rewrite `.desktop` metadata into Android manifest/launcher metadata.

This is not elegant, but it gives Android a normal install/update/uninstall unit.

## Runtime process model

Launch flow:

```text
Android launcher
  -> Wrapper Activity
    -> create Android Surface
    -> start bridge services
    -> start Linux app process under same APK UID
    -> Linux app connects to bridge sockets
```

The Linux app process inherits the Android app sandbox:

- same app UID
- same package permissions
- same app data ownership
- same SELinux app domain unless the OS later adds a dedicated Linux-app domain
- same force-stop/uninstall behavior

That is acceptable for a day-one APK model. The long-term OS-integrated version can split each Linux app into richer SELinux domains and mount namespaces.

## Bridge components

### 1. Wayland-to-Android Surface bridge

Linux GUI apps need a display server. Day one should target Wayland first.

The bridge provides:

- `WAYLAND_DISPLAY`
- a Wayland server socket owned by the app
- Android Surface-backed outputs
- Android touch/mouse/keyboard input translated to Wayland events
- clipboard integration through a portal
- resize/orientation/external-display handling

Avoid raw DRM/KMS. The bridge renders into Android-controlled surfaces.

Implementation choices:

- custom minimal Wayland compositor using Android native rendering primitives.
- wlroots-like approach only if it can be made Android-friendly without raw DRM/udev assumptions.
- software rendering fallback for the first prototype.

X11 support should come later through Xwayland-on-Wayland. Treat X11 compatibility as lower trust because X11 clients have weaker isolation expectations.

### 2. D-Bus/session bus shim

Many Linux desktop apps expect a session bus.

The bridge should provide a small session bus or bus-compatible shim for:

- `org.freedesktop.portal.Desktop`
- notifications
- settings/color-scheme hints
- open URI
- inhibit/session status
- limited app-private service names

Do not expose a broad host D-Bus. There is no real Linux desktop host to expose, and a broad bus would become a cross-app attack surface.

### 3. Portal bridge

The portal bridge is the main permission translation layer.

Linux-facing:

- xdg-desktop-portal style APIs
- file open/save
- open URI
- screenshot/screencast later
- notifications
- print later
- USB later

Android-facing:

- Storage Access Framework
- Android intent resolver
- NotificationManager
- MediaProjection only when explicitly granted
- Android USB permission APIs

Day-one file access can be import/export rather than true arbitrary filesystem access:

```text
Linux app asks portal to open file
  -> Android picker opens
  -> bridge receives URI
  -> bridge copies or streams file into app-private cache
  -> Linux app receives a path inside its synthetic home/cache
```

Long-term platform support can replace this with FUSE or a stronger document-provider mount.

### 4. Audio bridge

Provide Linux-facing audio compatibility:

- PulseAudio-compatible socket first, or
- PipeWire-compatible shim later.

Android-facing:

- AudioTrack for playback.
- AudioRecord for microphone.
- Android microphone permission before capture.
- privacy indicators remain Android-controlled.

Do not expose `/dev/snd`.

### 5. Network bridge

Day one can use Android's package-level network permission.

If the generated APK declares network permission and the user grants/allows it, Linux app sockets work as normal under that app UID.

Long-term:

- per-app Android UID remains the enforcement point.
- optional per-app socket broker for domain-level policy.
- prevent a shared runtime process from becoming a network proxy for denied apps.

### 6. Clipboard and notifications

Clipboard:

- Linux app talks to Wayland/portal clipboard.
- bridge talks to Android clipboard APIs.
- enforce foreground/lifecycle rules.

Notifications:

- Linux app uses libnotify/portal.
- bridge posts Android notifications under the APK's package identity.
- notification permission is declared by the generated APK.

### 7. Filesystem compatibility

The Linux app needs familiar paths:

```text
$HOME
/tmp
/etc
/usr/share
/usr/lib
/run/user/<uid>
```

Without OS mount namespaces, a normal APK cannot create a perfect synthetic root. Day one can still fake enough with:

- patched interpreter and rpath.
- environment variables.
- app-private data directories.
- generated config files.
- wrapper scripts.
- `LD_PRELOAD` compatibility shims for selected path assumptions.

Environment example:

```text
HOME=/data/data/org.archphene.app/files/home
XDG_RUNTIME_DIR=/data/data/org.archphene.app/cache/run
XDG_CONFIG_HOME=/data/data/org.archphene.app/files/config
XDG_DATA_HOME=/data/data/org.archphene.app/files/data
TMPDIR=/data/data/org.archphene.app/cache/tmp
WAYLAND_DISPLAY=archphene-0
DBUS_SESSION_BUS_ADDRESS=unix:path=...
PULSE_SERVER=unix:...
SSL_CERT_FILE=...
FONTCONFIG_PATH=...
```

Long-term OS support should replace these shims with a real per-app mount namespace.

### 8. Linux syscall and path shims

Some apps will look for Linux files Android does not expose:

- `/etc/resolv.conf`
- `/etc/passwd`
- `/proc` details
- MIME database
- icon themes
- fontconfig
- locale files
- timezone files

The bridge package should provide generated replacements where possible.

Use `LD_PRELOAD` only as a compatibility aid, not the security boundary. It does not work reliably for every binary and can be bypassed by static binaries or direct syscalls.

## Security posture

Day-one bridge security is package-level:

- each generated APK gets its own Android UID.
- Android permissions apply to the wrapper/bridge/app as one unit.
- app data is per package.
- install/update/uninstall are Android-native.

This is weaker than full OS integration but still much better than one giant shared Linux environment for every app.

Rules:

- no shared privileged runtime holding broad permissions for every Linux app.
- no raw device nodes.
- no host filesystem mutation.
- no arbitrary package scripts on the phone.
- no executable downloads after install unless a deliberate developer mode permits it.
- generated APK permissions must be visible and minimal.

## What runs day one

Good candidates:

- terminal emulators.
- SDL apps.
- simple Wayland clients.
- simple GTK/Qt apps with limited portal needs.
- text editors.
- file viewers using file-open portal.

Hard candidates:

- browsers.
- Electron apps.
- office suites.
- IDEs.
- apps needing full D-Bus desktop services.
- apps needing systemd, udev, FUSE, scanners, printers, GPU device nodes, or unrestricted `$HOME`.

## Prototype target

First proof:

```text
one generated APK
  one simple Wayland or SDL app
  bundled glibc/runtime
  patched ELF interpreter
  Android Activity with Surface
  bridge-owned display
  app-private HOME
  no network
  no camera/mic
```

Success criteria:

- installs as a normal APK.
- launches from Android launcher.
- draws into an Android Surface.
- works on external display/desktop mode.
- has its own Android app settings page.
- force stop works.
- uninstall removes data.
- update installs over previous version with same signing key.

## Evolution path

### Bridge v0

- one app per APK.
- duplicated runtime per APK.
- software rendering.
- app-private files only.

### Bridge v1

- shared runtime library set through build-time deduplication.
- Wayland bridge.
- file open/save portal.
- audio output.
- clipboard.
- notifications.

### Bridge v2

- platform-provided runtime.
- per-app mount namespace.
- Linux app SELinux domain.
- OS-level Wayland bridge.
- Android Settings integration for Linux app permissions.

### Bridge v3

- package manager support for Linux app descriptors.
- generated APKs become thin identity/permission wrappers.
- runtime and bridge are updated with the OS.
- stronger per-resource portal enforcement.

## Why this matters

The store solves acquisition and updates. The bridge solves execution.

Without the bridge, the APK generator only creates packages that install but do not behave like useful desktop apps. With the bridge, GrapheneOS desktop mode can become a laptop-like environment incrementally: first curated apps, then broader Linux package compatibility, then OS-level hardening.
