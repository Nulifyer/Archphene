# KCalc Runtime: Ship vs Bridge Split

Date: 2026-07-09

## Direct Answer

No, we should not bring a full KDE Plasma, GNOME, or generic Arch desktop stack into the KCalc APK/runtime.

For a real KCalc GUI, we need three separate layers:

1. Linux ABI/runtime libraries that KCalc and Qt/KF6 load in-process.
2. Android bridge services that replace desktop session services.
3. Assets/config data that are not services but are still needed for the UI to look and behave correctly.

The full recursive Arch dependency closure is the wrong install unit. A naive resolver already started pulling broad rootfs packages such as `bash`, `coreutils`, `curl`, `dbus`, `filesystem`, `pam`, `systemd-libs`, `tar`, certificates, Kerberos/LDAP libraries, and many GCC sanitizer libraries. That is normal for an Arch system root, but it is not the right model for a generated Android app.

## Rule

```text
Ship libraries that are part of the app's in-process ABI.
Bridge services that represent OS/session/device behavior.
Omit tools and daemons until a real app behavior requires them.
```

## For KCalc: Ship These

These should be Linux runtime modules mounted read-only into the KCalc app namespace. They are not Android permissions and they are not privileged services.

### Base ABI

```text
glibc dynamic loader and libc
libstdc++ / libgcc runtime
libm
libdl/libpthread compatibility from glibc
locale/gconv data, minimally C.UTF-8 first
```

Reason: unmodified Arch binaries require the glibc ABI and loader. Android/Bionic cannot replace this.

### KCalc Direct App Libraries

From KCalc `.PKGINFO` and ELF sonames:

```text
gmp
mpfr
libmpc
qt6-base runtime libraries:
  libQt6Core.so.6
  libQt6Gui.so.6
  libQt6Widgets.so.6
  libQt6Xml.so.6
KDE Frameworks 6 libraries:
  kcolorscheme
  kconfig
  kconfigwidgets
  kcoreaddons
  kcrash
  kguiaddons
  ki18n
  kiconthemes
  knotifications
  kwidgetsaddons
  kxmlgui
```

Reason: these are linked libraries or close plugin/config dependencies for the binary. They run in the app process.

### Qt/KDE Support Libraries That Are Still Runtime, Not Desktop

```text
qt6-wayland client plugin, unless KCalc is forced through another Qt QPA plugin
wayland-client libraries
xkbcommon keymap library/data needed by Qt/Wayland input
fontconfig/freetype/harfbuzz/icu stack needed by Qt text rendering
glib2 if required by Qt/KF6 support libraries
pcre2/libffi/etc. only if needed by the loaded libraries
```

Reason: these are not the desktop environment. They are app toolkit runtime pieces.

### Assets/Data

```text
KCalc app data from usr/share
KDE translation files, at least current locale plus fallback
kconfig schema files used by KCalc/KF6
Breeze icon theme subset, especially accessories-calculator and action icons
fontconfig config and generated app/runtime font cache
minimal fonts if Android fonts are not directly exposed through a broker
Qt plugins actually loaded by the app:
  platforms/libqwayland-*.so
  imageformats as needed
  iconengines as needed
  styles as needed
```

Reason: missing assets often produce blank icons, broken text, missing menus, or plugin load errors even when ELF dependencies resolve.

## For KCalc: Bridge These

These should be Android-side services exposed to Linux apps through small protocol shims or compatibility daemons. They should not be imported as KDE/GNOME/system session daemons.

### Wayland Compositor

```text
Linux app -> app-private wayland-0 socket -> Archphene compositor -> Android Surface
```

We already proved the standard filesystem `XDG_RUNTIME_DIR/wayland-0` socket with JNI. Next is protocol/rendering:

```text
wl_display
wl_registry
wl_compositor
wl_shm
wl_seat
xdg_wm_base
optional wl_data_device for clipboard
```

Do not ship KWin, Weston, GNOME Shell, Mutter, or a full desktop session for KCalc.

### Input

Bridge Android input events to Wayland seat events:

```text
pointer motion/click
keyboard keymap/key events
touch events
focus/enter/leave
```

Do not ship a desktop input daemon.

### Clipboard

Bridge foreground clipboard through Android clipboard policy:

```text
Wayland data-control/data-device or portal-shaped shim
  -> Android ClipboardManager only while foreground/allowed
```

Do not give Linux apps raw global clipboard behavior beyond Android policy.

### Notifications

KCalc probably does not need notifications. If KF6/KNotifications probes the session, bridge or no-op it.

```text
KNotifications request -> Android notification permission path
```

Do not run a full KDE notification daemon for KCalc.

### D-Bus Session Bus

Do not start a normal desktop `dbus-daemon` by default.

Use an Archphene broker that implements or fakes only required names/interfaces:

```text
org.freedesktop.portal.Desktop, if needed
org.kde.StatusNotifierWatcher, likely not needed for KCalc
org.freedesktop.Notifications, bridge/no-op
org.kde.kglobalaccel, deny/no-op for KCalc
```

If a small dbus-compatible message router is needed for library compatibility, keep it app-local and brokered. Do not expose host/system D-Bus.

### File Dialogs / Documents

KCalc should not need files initially. For apps that do:

```text
Qt/KDE file dialog request -> xdg-desktop-portal style shim -> Android SAF
```

Do not mount broad shared storage as a Linux home directory.

### Secrets / Keyring

Not needed for KCalc. For other apps:

```text
libsecret/KWallet/Secret Service request -> Android Keystore-backed broker, per app/user approval
```

Do not run KDE Wallet or GNOME Keyring blindly as shared desktop services.

### Audio / Media

Not needed for KCalc. For other apps:

```text
PulseAudio/PipeWire shaped shim -> AAudio/AudioTrack/MediaCodec broker
```

Do not ship a full system PipeWire/PulseAudio session just for KCalc.

## For KCalc: Omit Unless Proven Needed

These appeared or can appear in a naive Arch closure, but should not be part of the first KCalc runtime module:

```text
bash
coreutils
findutils
tar
file
curl
ca-certificates / openssl / krb5 / ldap stack
pam / pambase
audit
systemd-libs as a service dependency
filesystem package layout scripts
full dbus service package
KWin / Plasma / GNOME Shell / Mutter / portal desktop backends
CUPS / print stack
network managers
package manager tooling
GCC sanitizer runtimes unless an actual linked library requires them
```

Some of these may be needed by other app classes, but they should be separate capability modules, not part of KCalc by default.

## Common App Families

### Qt/KDE Apps

Ship:

```text
Qt libraries
Qt platform plugin for Wayland
needed KF6 libraries
icons/themes/translations/config schemas
font/text stack
```

Bridge:

```text
Wayland compositor
D-Bus/portal subset
notifications
clipboard
file dialogs
settings/global shortcuts
```

Do not ship:

```text
Plasma shell
KWin
full KDE session daemons
system bus
```

### GTK/GNOME Apps

Ship:

```text
GTK/GDK/Pango/Cairo/Harfbuzz/Freetype/fontconfig
glib/gio libraries
icons/themes/schemas
GTK Wayland backend
```

Bridge:

```text
Wayland compositor
xdg-desktop-portal subset
GSettings/dconf compatibility where needed
clipboard/file picker/notifications
```

Do not ship:

```text
GNOME Shell
Mutter
gnome-session
full dconf service unless app-local and constrained
```

### Electron/Chromium Apps

Ship:

```text
Electron/Chromium runtime bundled with app unless intentionally system-shared
NSS/NSPR and media libraries needed by Electron build
```

Bridge:

```text
Wayland/Ozone surface
file picker
notifications
clipboard
permissions/media capture
```

Do not try to run VS Code on the user's installed Android browser. Electron apps expect their own Chromium/V8 runtime and native integration.

### CLI/TUI Apps

Ship:

```text
glibc/libstdc++/ncurses/readline as needed
```

Bridge:

```text
PTY and terminal UI
synthetic /proc and system stats if needed
file portal paths
```

Do not expose raw Android host `/proc` or `/sys` broadly.

## KCalc Runtime Module Target

First real KCalc GUI runtime should be a curated module set, not full recursion:

```text
runtime.glibc-x86_64
runtime.qt6-widgets-wayland-x86_64
runtime.kf6-kcalc-minimal-x86_64
runtime.fonts-icons-minimal-x86_64
bridge.wayland-shm-v1
bridge.clipboard-foreground-v1
bridge.notifications-noop-v1
bridge.dbus-minimal-v1
```

The package resolver should classify dependencies into:

```text
ship.required
ship.asset
bridge.required
bridge.optional
stub.noop
omit.initial
```

## Next Engineering Step

Change the resolver from "download recursive Arch closure" to "classify package closure".

For KCalc, the next test should:

1. Build a curated KCalc runtime package list from direct ELF sonames plus Qt Wayland plugin requirements.
2. Stage only those libraries/assets into `runtime-root`.
3. Run `ld.so --list` through `run-as` against the curated root.
4. Add missing libraries only when the loader proves they are required.
5. Keep all service-like dependencies as bridge/stub entries until KCalc actually calls them.

This keeps the milestone moving toward real KCalc GUI without accidentally building an Arch desktop VM inside an APK.
## 2026-07-09 Emulator Iteration: Curated Runtime Closure

KCalc does not need a full KDE Plasma, GNOME, Xorg, KWin, Mutter, systemd user session, or Arch base userspace for the first GUI milestone. The loader-visible requirement is a much narrower ELF closure plus bridge-owned services.

Current measured split:

- Native Android APK entrypoints: `libarchphene_kcalc.so`, `libarchphene_wayland_socket_probe.so`, `libarchphene_wayland_jni.so`, and `libarchphene_ld.so` execute from Android's extracted `nativeLibraryDir`.
- App-private Linux runtime: 86 files / 126 MiB extracted from the APK into `files/linux-runtime/lib` for versioned Linux sonames such as `libQt6Core.so.6`, `libKF6Notifications.so.6`, and `libc.so.6`.
- Host ELF resolver result: `VisitedObjects=82`, `ResolvedLibraries=81`, `MissingLibraries=0` for Arch `usr/bin/kcalc`.
- Android `run-as` loader result: `libarchphene_ld.so --library-path files/linux-runtime/lib:nativeLibraryDir --list libarchphene_kcalc.so` resolves the full KCalc dependency tree from app-private runtime storage.
- App-spawned loader result: `--verify` exits `0`, but `--list` exits `159`, matching the existing app-process glibc/seccomp blocker.

Important Android rule discovered: `lib/<abi>` inside an APK is not a general Linux filesystem. Android extracted the true `.so` entrypoints, but versioned sonames are not reliable as native-library install artifacts. The bridge should package Linux runtime files in the APK, then extract them to app-private storage. Only executable entrypoints that need `execve` should be named/placed so Android extracts them under `nativeLibraryDir`.

Ship vs bridge decision after this test:

- Ship as runtime deps: glibc, libstdc++/libgcc/libgomp, Qt/KF6 libraries, font/text/image/compression/XML helpers, libdbus client library, libglvnd dispatch libraries, and small X11/XCB compatibility libraries pulled by Qt/KF6.
- Bridge or stub as Android services: Wayland compositor, input, clipboard, notifications, D-Bus session behavior, global shortcuts, file dialogs/SAF, secrets, audio, and policy-gated permissions.
- Do not ship as first milestone: Plasma/KWin, GNOME/Mutter, systemd services, dbus-daemon as a desktop session service, Xorg, pacman/base shell tooling, CUPS, PAM/audit, or a generic Arch rootfs.
