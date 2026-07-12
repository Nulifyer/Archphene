# Shared Linux runtime dependencies

Date: 2026-07-09

Goal: define how ArchpheneOS should handle Linux dependencies such as `glibc`, GTK, Qt, FFmpeg, Mesa/Vulkan loaders, language runtimes, Electron, and common command-line tools so Linux apps do not each bundle a full duplicate runtime.

## Direct answer

Android/GrapheneOS already has a C library, but it is not glibc. Android uses its own libc ABI, commonly called Bionic. Normal Arch Linux packages are built against glibc and expect glibc's dynamic linker, libc ABI, symbol versions, filesystem conventions, NSS behavior, locale modules, loader paths, and Linux distribution userland assumptions.

Therefore:

- a normal Arch `glibc`-linked binary cannot simply use Android's libc.
- Android's libc cannot be treated as a drop-in replacement for Arch `glibc`.
- `glibc` should be provided by the Linux app runtime.
- shared Linux dependencies are a good idea, but they should not be ordinary Android "system apps" in the usual privileged sense.

The correct model is closer to:

```text
verified shared runtime/dependency module
  mounted read-only into each Linux app namespace
  reference-counted by Linux app manager
  updated through APK/APEX-like Android package UX
  never granted user-facing app permissions by itself
```

## Why Android's libc cannot replace glibc

Android's NDK documentation describes Android native APIs as including `libc`, `libm`, and `libdl`, and notes that Android differs from Linux by including pthread and realtime functionality directly in libc rather than separate `libpthread`/`librt` libraries.

The NDK C++ support docs also explicitly say `libc++` is not a system library and must be included in an app when used as `libc++_shared.so`.

Those are Android-native ABI contracts. Arch Linux binaries are not built for those contracts. They expect:

- ELF interpreter such as `/lib/ld-linux-aarch64.so.1` or `/lib64/ld-linux-x86-64.so.2`
- glibc symbol versions such as `GLIBC_2.xx`
- glibc dynamic loader behavior
- glibc NSS modules
- glibc locale/gconv data
- Linux distribution filesystem conventions
- GNU/POSIX behavior beyond Android's app-facing native API surface

Even if many syscalls are ultimately the same Linux kernel syscalls, the userspace ABI is different.

## Translation layer versus shipping glibc

It is worth reusing Android system libraries where the ABI matches, but a general `glibc` to Bionic translation layer is not the right default for unmodified Arch packages.

There are three different cases:

```text
Android-native bridge code
  build against Android NDK/Bionic
  use Android libc, libm, libdl, liblog, EGL, Vulkan, AAudio, MediaNDK where possible
  best for the wrapper, portals, brokers, lifecycle integration, and display/audio bridge

Linux apps rebuilt for Android
  port source code to Android/Bionic deliberately
  package as a normal Android-native component or as a special Archphene Android-native LAPK
  useful for selected apps, but not compatible with normal Arch binary packages

Unmodified Arch packages
  keep the Arch/glibc ABI
  provide glibc and Linux userland dependencies as shared runtime modules
  best for broad pacman/AUR compatibility
```

A real translation layer would need to emulate or forward a large part of glibc's ABI:

- dynamic loader behavior
- symbol-versioned exports such as `GLIBC_2.xx`
- `pthread`, `rt`, resolver, NSS, locale, gconv, and errno behavior
- process, signal, file, terminal, and `/proc` assumptions
- ELF interpreter paths
- GNU extensions expected by desktop packages
- interaction with C++ runtimes and libraries compiled against glibc

That layer would either become a partial glibc reimplementation or would need to carry glibc internally anyway. In that case, installing a verified shared glibc runtime module is simpler, more predictable, and easier to secure.

The bridge should still avoid redundant packages where Android already provides a stable native API. For example, bridge-side graphics/audio/media code can use Android EGL, Vulkan, AAudio, MediaCodec, logging, Binder, and permission APIs. But Linux app binaries should not link directly against private Android platform libraries, and Arch binaries should not be rewritten at load time to pretend Bionic is glibc.

Practical rule:

```text
Use Bionic for ArchpheneOS bridge components.
Use Android stable NDK/system APIs for Android-facing integration.
Use shared glibc runtime modules for broad Arch Linux app compatibility.
Port individual apps to Bionic only when the app is important enough to maintain as an Android-native build.
```

## Should glibc be a "system app"?

Not in the normal Android meaning.

Android "system app" usually implies an app bundled into the OS image or installed with special platform trust. That is not what `glibc` should be. `glibc` does not need camera, contacts, storage, notification, or network permissions. It should not be a privileged app and should not become a permission bypass.

Better names:

- runtime module
- dependency module
- Linux runtime package
- shared runtime component
- Archphene module

It can still be installed and updated through an Android-style package/update interface. The important distinction is that it is a mounted dependency payload, not a user-facing privileged app.

## Why ordinary APK library sharing is not enough

Android has a `<uses-library>` manifest element, but this is for adding shared library code to the package class loader. It is aimed at Android framework/class libraries, not arbitrary glibc ELF dependency graphs for separately sandboxed Linux apps.

For Linux app sharing, the runtime must solve problems Android APK libraries do not solve:

- mount the same read-only files into many app namespaces
- expose ELF interpreters and `.so` files at Linux paths
- keep app UIDs isolated even when sharing code
- keep runtime files immutable and verifiable
- handle ABI/version constraints
- keep dependency modules from obtaining user app permissions
- prevent one app from writing into another app's shared runtime
- support uninstall garbage collection

## Recommended package model

Use three package types:

```text
LAPK: Linux user app package
  launchable Android app identity
  owns permissions, data, lifecycle, activities
  examples: org.archphene.gimp, org.archphene.zed

LRPK: Linux runtime package
  non-launchable shared runtime module
  no Android dangerous permissions
  mounted read-only into Linux app namespaces
  examples: org.archphene.runtime.glibc, org.archphene.runtime.gtk3

LTK: Linux toolchain/tool package
  optional shared developer/runtime tools
  no direct Android permissions
  exposed only to apps that declare capability use
  examples: org.archphene.tools.git, org.archphene.tools.nodejs
```

These can be delivered as APK-like packages for familiar installation/update UX, but their payload should be handled by the ArchpheneOS Linux app manager.

## Runtime module examples

### Base glibc runtime

```text
org.archphene.runtime.glibc-aarch64
  /runtime/glibc/aarch64/2.41/
    lib/ld-linux-aarch64.so.1
    lib/libc.so.6
    lib/libm.so.6
    lib/libdl.so.2
    lib/libpthread.so.0 compatibility stubs as needed
    lib/libresolv.so.2
    lib/librt.so.1
    lib/libnss_*.so.2
    lib/gconv/*
    share/locale/*
    etc/nsswitch.conf template
```

The mounted app namespace can expose this as:

```text
/lib/ld-linux-aarch64.so.1
/usr/lib/libc.so.6
/usr/lib/libm.so.6
```

or the packager can patch ELF interpreters/RPATHs to:

```text
/run/archphene/runtime/glibc/lib/ld-linux-aarch64.so.1
```

The first approach maximizes compatibility. The second approach reduces path conflicts but requires more patching.

### GTK runtime

```text
org.archphene.runtime.gtk3-aarch64
  depends: glibc, fontconfig, freetype, cairo, pango, harfbuzz, glib2, gdk-pixbuf
  provides: GTK3 runtime for GIMP, Firefox UI, many desktop apps
```

### Qt/KDE runtime

```text
org.archphene.runtime.qt6-kde-aarch64
  depends: glibc, qt6-base, qt6-declarative, kcoreaddons, kio, knotifications
  provides: Qt/KDE stack for Kdenlive and KDE apps
```

### Media runtime

```text
org.archphene.runtime.media-ffmpeg-aarch64
  depends: glibc, codec libs, audio bridge libs
  provides: FFmpeg/MLT media decode/encode userspace
```

### Developer tools

```text
org.archphene.tools.git-aarch64
org.archphene.tools.nodejs-aarch64
org.archphene.tools.python-aarch64
org.archphene.tools.shell-aarch64
```

These should not appear in an app's PATH unless the user app declares and receives the relevant capability.

## Dependency resolution

LinuxAPK Store should resolve dependencies before installation:

```text
requested app: org.archphene.gimp
  requires:
    runtime.glibc-aarch64 >= 2.41
    runtime.gtk3-aarch64 >= 3.24
    runtime.image-codecs-aarch64
    bridge.wayland-v1

resolver:
  chooses compatible runtime modules
  verifies signatures/hashes
  installs missing modules
  records reverse dependencies
  installs user app
```

Every LAPK should include a manifest:

```json
{
  "package": "org.archphene.gimp",
  "arch": "aarch64",
  "linuxAbi": "glibc",
  "requires": [
    "org.archphene.runtime.glibc-aarch64 >= 2.41 < 2.42",
    "org.archphene.runtime.gtk3-aarch64 >= 3.24",
    "org.archphene.bridge.wayland >= 1"
  ],
  "capabilities": [
    "gui.wayland",
    "files.documentPortal",
    "clipboard",
    "dynamicCode.plugins.optional"
  ]
}
```

## Mount model

At launch:

```text
PackageManager starts org.archphene.gimp
  -> LinuxAppManager reads dependency graph
  -> creates app mount namespace
  -> mounts app payload read-only
  -> mounts shared runtime modules read-only
  -> creates writable app-private HOME/XDG dirs
  -> exposes granted document trees
  -> starts bridge services
  -> execs glibc dynamic linker + app binary
```

Example namespace:

```text
/app
  current app payload

/run/archphene/runtime/glibc
  mounted from org.archphene.runtime.glibc-aarch64

/run/archphene/runtime/gtk3
  mounted from org.archphene.runtime.gtk3-aarch64

/home/app
  writable app-private data

/mnt/documents/<grant>
  user-granted document tree
```

This avoids giving one app direct read/write access to another app's private data while still sharing immutable dependency bytes.

## Update behavior

The UX should feel like Obtanium/Play Store:

- show available updates
- show app updates and runtime updates
- explain which apps are affected by a runtime update
- support unattended updates for safe-compatible runtime patch updates
- require user confirmation for major ABI/runtime changes
- rollback if post-update validation fails

Update classes:

```text
security patch
  same ABI promise, auto-eligible

minor runtime update
  compatible ABI, validate dependent apps, auto-eligible if policy allows

major runtime update
  ABI may change, install side-by-side, migrate apps individually

app update
  same as Android app update semantics, same package/signing identity
```

Important rule: runtime modules must support side-by-side versions.

Example:

```text
glibc 2.41 installed for GIMP and LibreOffice
glibc 2.42 installed for new Zed build
both mounted separately
old glibc removed only when no installed app references it
```

Do not force one global rolling Arch userspace the way a normal Arch root filesystem does. Mobile app updates need stronger rollback and per-app compatibility.

## Uninstall behavior

When a user uninstalls an app:

```text
uninstall org.archphene.gimp
  -> remove app payload
  -> remove app-private data if user confirms/Android semantics require
  -> decrement reference counts:
       runtime.gtk3
       runtime.glibc
       runtime.image-codecs
  -> if a runtime has zero dependents:
       mark unused
       optionally remove immediately or after retention window
```

Recommended:

- keep unused runtimes for a short retention window, such as 7-30 days.
- show "unused Linux runtimes use X MB" in storage settings.
- allow manual cleanup.
- never remove a runtime still referenced by an installed app.

## Security rules

Runtime/dependency modules:

- are read-only at runtime
- are signed and verified
- do not receive dangerous Android permissions
- are not launchable by default
- are mounted only into dependent apps
- cannot access dependent app data by themselves
- should be content-addressed or fs-verity/dm-verity protected
- are subject to rollback protection/downgrade rules

Linux user apps:

- own Android permissions
- own user-visible Settings controls
- own data directories
- declare capabilities
- can only see mounted runtimes they depend on

Bridge services:

- enforce caller identity
- mediate network/files/camera/mic/clipboard/notifications
- must not let shared runtimes become a permission side channel

## APEX-like versus APK-like

Android's APEX format exists for lower-level system modules that do not fit the standard app model. AOSP documentation says APEX was introduced for lower-level system modules such as native services/libraries, HALs, ART, and class libraries, and that it uses mounted filesystem payloads with verification.

For a GrapheneOS fork, ArchpheneOS should borrow the APEX design principles:

- mounted filesystem image
- verified payload
- package/version metadata
- rollback-aware updates
- mount at stable paths

But Linux app runtimes should not necessarily be Android platform APEXes unless the OS integrates them that way. A custom `LinuxRuntimeManager` can provide an APEX-like model for third-party Linux app runtimes without making each dependency a privileged platform component.

## Why not just use pacman on-device?

Normal pacman assumes:

- one mutable root filesystem
- global dependency graph
- global library upgrades
- package scripts with broad system assumptions
- no Android PackageManager identity
- no Android per-app permission model
- no user-facing per-app rollback boundary

ArchpheneOS should use Arch package metadata and payloads, but not expose the phone's Linux-app runtime as one mutable Arch root.

Better:

```text
server/builder/device resolver uses pacman metadata
  -> builds signed runtime/app modules
  -> device installs verified modules through Android-style package flow
  -> per-app namespace composes the required modules at launch
```

This keeps Arch package freshness while preserving Android/GrapheneOS app isolation.

## Practical conclusion

Yes, shared dependencies like glibc should be installable and reusable.

No, Android/GrapheneOS does not already provide glibc for Arch binaries.

No, ordinary privileged "system app" is not the right abstraction.

The right abstraction is a non-launchable, verified, read-only Linux runtime module managed by the bridge/Linux app store. It should update like an app-store component, be mounted into dependent Linux apps, support side-by-side versions, and be garbage-collected when no installed Linux apps need it.

## Source map

- Android NDK native APIs: https://developer.android.com/ndk/guides/stable_apis
- Android NDK C++ runtime support: https://developer.android.com/ndk/guides/cpp-support
- Android APEX file format: https://source.android.com/docs/core/ota/apex
- Android `<uses-library>` manifest element: https://developer.android.com/guide/topics/manifest/uses-library-element
