# KCalc Android Launcher Emulator Results

Date: 2026-07-09

## Question

Can KCalc show up as its own Android app and launch like a normal Android app while carrying the real Arch Linux KCalc binary?

## Result

Yes for Android app identity, launcher integration, APK packaging, real Arch ELF payload installation, and both standard filesystem and abstract UNIX socket GUI-bridge transport proofs.

Not yet for full KCalc GUI execution. The current blockers are now concrete:

1. direct execution fails because the Arch ELF requests `/lib64/ld-linux-x86-64.so.2`
2. packaged glibc loader `--verify` accepts the KCalc ELF
3. app-spawned loader `--list` exits `159` (`SIGSYS`), matching the syscall-filter class seen in earlier glibc tests
4. running the same installed files through `run-as` reaches normal dynamic loading and fails on missing KDE runtime libraries, starting with `libKF6Notifications.so.6`
5. Java filesystem UNIX sockets for a standard Wayland socket path are blocked for ordinary apps by hidden-API enforcement, but a tiny JNI binder successfully creates the standard app-private `XDG_RUNTIME_DIR/wayland-0` socket without OS edits

## Installed Android App

The APK is installed as a distinct Android package:

```text
org.archphene.linux.kcalc/.MainActivity
```

Android package manager evidence:

```text
Package [org.archphene.linux.kcalc]
codePath=/data/app/.../org.archphene.linux.kcalc-...
legacyNativeLibraryDir=/data/app/.../org.archphene.linux.kcalc-.../lib
primaryCpuAbi=x86_64
versionCode=0 minSdk=23 targetSdk=36
dataDir=/data/user/0/org.archphene.linux.kcalc
```

Launcher and icon evidence:

```text
application-label:'KCalc'
application-icon-160:'res/drawable/kcalc_icon.xml'
application: label='KCalc' icon='res/drawable/kcalc_icon.xml'
launchable-activity: name='org.archphene.linux.kcalc.MainActivity'
native-code: 'x86_64'
```

The KCalc package now has its own Android vector launcher icon at `res/drawable/kcalc_icon.xml` instead of relying on the generic wrapper icon.

## Real Arch Payload

The APK packages the real extracted Arch `usr/bin/kcalc` ELF under Android's native library extraction path:

```text
lib/x86_64/libarchphene_kcalc.so
```

On device, the Activity confirmed:

```text
Packaged real Arch usr/bin/kcalc ELF
Exists: true
Length: 599288
canExecute: true
```

Hashes:

```text
kcalc.pkg.tar.zst sha256: 374131E7CEABDD017A578C361DBA833683DBFA76520AA3D9E8670E7A509FD28B
libarchphene_kcalc.so sha256: DA0C475F4EC44B6F8E5B493099C35D87DEEDA194584EAAC10A027948C1643B78
libarchphene_wayland_socket_probe.so sha256: B808683A16FB0A1B14049668A1A789A8438B5C3FD6A65272387110F4EC26C44A
archpheneos-kcalc.apk sha256: D510E7C147D24F149262FD71A38028FB8E5DEBBFF7E4EAB529BF8DA79AC8A03B
```

The embedded `.PKGINFO` confirms:

```text
pkgname = kcalc
pkgver = 26.04.3-1
pkgdesc = Scientific Calculator
arch = x86_64
size = 3096240
depend = glibc
depend = gmp
depend = kcolorscheme
depend = kconfig
depend = kconfigwidgets
depend = kcoreaddons
depend = kcrash
depend = kguiaddons
depend = ki18n
depend = kiconthemes
depend = knotifications
depend = kwidgetsaddons
depend = kxmlgui
depend = libmpc
depend = libstdc++
depend = mpfr
depend = qt6-base
```

## ELF Preflight

Host `readelf` confirmed:

```text
[Requesting program interpreter: /lib64/ld-linux-x86-64.so.2]
```

Direct dependencies include:

```text
libKF6Notifications.so.6
libKF6XmlGui.so.6
libKF6Crash.so.6
libKF6ConfigWidgets.so.6
libQt6Xml.so.6
libKF6IconThemes.so.6
libKF6ColorScheme.so.6
libKF6GuiAddons.so.6
libKF6I18n.so.6
libKF6WidgetsAddons.so.6
libQt6Widgets.so.6
libKF6ConfigGui.so.6
libKF6ConfigCore.so.6
libKF6CoreAddons.so.6
libQt6Gui.so.6
libQt6Core.so.6
libgmp.so.10
libmpfr.so.6
libmpc.so.3
libstdc++.so.6
libm.so.6
libc.so.6
```

## Android Wayland Transport Probe

The KCalc APK now packages a static Linux probe and a tiny Android JNI binder:

```text
lib/x86_64/libarchphene_wayland_socket_probe.so
lib/x86_64/libarchphene_wayland_jni.so
```

The first Java-only filesystem socket attempt used `java.net.UnixDomainSocketAddress.of()` for a normal socket under `XDG_RUNTIME_DIR`, but Android blocked it for the app process:

```text
hiddenapi: Accessing hidden method Ljava/net/UnixDomainSocketAddress;->of(Ljava/lang/String;)Ljava/net/UnixDomainSocketAddress; ... denied
java.lang.NoSuchMethodError: No static method of(Ljava/lang/String;)Ljava/net/UnixDomainSocketAddress;
```

The JNI binder fixes that without OS edits by creating/listening on the filesystem UNIX socket in native code, returning a `FileDescriptor`, and letting Java wrap it with `LocalServerSocket(FileDescriptor)`.

Standard filesystem socket result:

```text
Wayland JNI load error:
Linux payload connects to JNI-owned filesystem wayland-0 socket
Exit code: 0
Timed out: false
Stdout:
connected to /data/user/0/org.archphene.linux.kcalc/files/wayland-runtime/wayland-0
bridge replied: ARCHPHENE_WAYLAND_FILESYSTEM_ACK
Filesystem bridge server accepted: true
Filesystem bridge server received: ARCHPHENE_WAYLAND_PROBE
Filesystem bridge server error:
```

The Activity also still proves the public Android abstract socket fallback:

```text
Linux payload connects to Android-owned abstract wayland socket
Exit code: 0
Timed out: false
Stdout:
connected to abstract:org.archphene.linux.kcalc.wayland-0.10220
bridge replied: ARCHPHENE_WAYLAND_BRIDGE_ACK
Bridge server accepted: true
Bridge server received: ARCHPHENE_WAYLAND_PROBE
Bridge server error:
```

This proves a Linux payload launched from a normal Android app can connect to the standard app-private Wayland socket path that unmodified Qt/libwayland expects. The next GUI blocker is no longer socket transport; it is implementing enough Wayland protocol and rendering to an Android Surface.

## Android Activity Preflight

Direct launch from the Android app:

```text
Command: [/data/app/.../lib/x86_64/libarchphene_kcalc.so]
Exit code: -127
Start error: java.io.IOException: Cannot run program ".../libarchphene_kcalc.so": error=2, No such file or directory
```

This is expected for an unpatched Arch ELF because Android does not provide `/lib64/ld-linux-x86-64.so.2`.

Packaged glibc loader verify:

```text
Command: [/data/app/.../lib/x86_64/libld.so.2, --verify, .../libarchphene_kcalc.so]
Exit code: 0
```

Packaged glibc loader list from app-spawned process:

```text
Command: [/data/app/.../lib/x86_64/libld.so.2, --library-path, .../lib/x86_64, .../libarchphene_kcalc.so]
Exit code: 159
```

`159` is the same `SIGSYS` class seen in the earlier app-spawned glibc syscall tests.

Same installed files via `run-as`:

```text
.../libarchphene_kcalc.so: error while loading shared libraries: libKF6Notifications.so.6: cannot open shared object file: No such file or directory
```

That proves the next runtime blocker after app-spawned syscall filtering is missing Qt/KF6 runtime modules.

## Artifacts

- APK: `prototypes/kcalc-android-app/out/archpheneos-kcalc.apk`
- Android app source: `prototypes/kcalc-android-app/src/org/archphene/linux/kcalc/MainActivity.java`
- Manifest: `prototypes/kcalc-android-app/AndroidManifest.xml`
- Launcher icon: `prototypes/kcalc-android-app/res/drawable/kcalc_icon.xml`
- Wayland socket probe source: `prototypes/kcalc-android-app/wayland_socket_probe.c`
- Wayland JNI source: `prototypes/kcalc-android-app/wayland_socket_jni.c`
- Packaged KCalc ELF: `prototypes/kcalc-android-app/lib/x86_64/libarchphene_kcalc.so`
- Packaged Wayland probe ELF: `prototypes/kcalc-android-app/lib/x86_64/libarchphene_wayland_socket_probe.so`
- Packaged Wayland JNI binder: `prototypes/kcalc-android-app/lib/x86_64/libarchphene_wayland_jni.so`
- Source package: `tooling/downloads/kcalc/kcalc.pkg.tar.zst`
- Extracted package: `tooling/downloads/kcalc/extract/`
- Latest screenshot: `artifacts/kcalc-jni-wayland-probe.png`
- Latest UI dump: `artifacts/kcalc-jni-wayland-probe.xml`
- Previous socket screenshot: `artifacts/kcalc-wayland-probe.png`
- Previous socket UI dump: `artifacts/kcalc-wayland-probe.xml`
- Earlier screenshot: `artifacts/kcalc-android-app.png`
- Earlier UI dump: `artifacts/kcalc-android-app-window.xml`

## Current Proof Boundary

Proven now:

- KCalc has a standalone Android package identity
- KCalc appears as a launcher app labelled `KCalc`
- KCalc now has a package-level Android launcher icon
- launching KCalc opens and keeps alive an Android Activity
- the real Arch `usr/bin/kcalc` ELF is inside the APK native library path
- the ELF is executable by Android file permissions
- glibc loader `--verify` accepts the ELF
- Android can own an app-local standard filesystem UNIX socket bridge under `XDG_RUNTIME_DIR`
- Android can also own an app-local abstract UNIX socket bridge
- a Linux payload can connect to both bridge transports and exchange bytes
- missing interpreter, syscall filter, missing KF6 runtime, and missing Wayland protocol/rendering are now explicit blockers

Not proven yet:

- visible Qt/KDE KCalc GUI
- Wayland protocol handshake beyond a transport probe
- Wayland-to-Android Surface rendering
- Android input translated into Qt/KDE
- full Qt6/KF6 dependency module mounting
- app-spawned glibc loader execution past the current `SIGSYS` failure

## Next Milestone

The next useful proof is a real Wayland protocol/rendering step, not another launcher wrapper:

1. replace the byte probe with a minimal Wayland client/server handshake on the proven filesystem `wayland-0` socket
2. implement the first compositor globals needed by simple clients: `wl_display`, `wl_registry`, `wl_compositor`, `wl_shm`, `wl_seat`, and `xdg_wm_base`
3. render a tiny shm-backed client surface into the Android Activity
4. stage Qt6/KF6 runtime modules and resolve KCalc dependencies
5. patch or broker the app-spawned glibc `SIGSYS` failure, then attempt KCalc GUI rendering
## Curated Runtime APK Iteration - 2026-07-09

New result: the APK now packages a curated Arch/KDE/Qt runtime closure and extracts it into app-private storage on launch.

Observed in emulator UI:

- `nativeLibraryDir`: Android-extracted entrypoints.
- `linuxRuntimeLibDir`: `/data/user/0/org.archphene.linux.kcalc/files/linux-runtime/lib`.
- Runtime extraction: `86 files, 126 MiB`.
- Wayland filesystem socket JNI probe: exit `0`, accepted `true`.
- Wayland abstract socket fallback probe: exit `0`, accepted `true`.
- Direct KCalc launch still fails with `error=2`, expected because the ELF interpreter path is `/lib64/ld-linux-x86-64.so.2`.
- `glibc loader --verify kcalc`: exit `0` from Android-extracted `libarchphene_ld.so`.
- App-spawned `glibc loader --list kcalc`: exit `159`, continuing the glibc/seccomp blocker.

Observed from `run-as`:

- The same Android-extracted loader resolves the full KCalc dependency tree using `--library-path files/linux-runtime/lib:nativeLibraryDir`.
- This proves the curated runtime closure is complete on-device; the next blocker is app-process syscall policy and then a real Wayland compositor implementation.

## KCalc app-spawned syscall matrix - 2026-07-09

The KCalc APK now packages a static Linux syscall probe as `libarchphene_syscall_probe.so` alongside the real Arch KCalc ELF and the packaged glibc loader. The probe is launched by `org.archphene.linux.kcalc`, so it inherits the same Android app UID, native library directory, app-private storage, and app-process syscall policy as the failing KCalc loader path.

Latest emulator result:

- Runtime extraction: 87 files / 127 MiB into `files/linux-runtime/lib`.
- Wayland filesystem socket probe: exit `0`, JNI-created `wayland-0` socket accepted the Linux client.
- Wayland abstract socket fallback probe: exit `0`.
- Direct KCalc exec: still fails with `error=2`, expected because the ELF interpreter path is not present.
- `libarchphene_ld.so --verify libarchphene_kcalc.so`: exit `0`.
- `libarchphene_ld.so --library-path files/linux-runtime/lib:nativeLibraryDir --list libarchphene_kcalc.so`: exit `159`.

Corrected app-sandbox syscall profile:

| syscall | app-spawned result |
| --- | --- |
| `open`, `openat`, `newfstatat`, `statx`, `faccessat`, `getrandom`, `memfd_create`, `membarrier`, `prlimit64`, `pidfd_open` | allowed |
| `mkdirat`, `unlinkat`, `renameat`, `clone3`, `landlock_create_ruleset` | syscall allowed; test args returned normal Linux errno |
| `openat2`, `mkdir`, `faccessat2`, `set_robust_list`, `rseq`, `io_uring_setup`, `futex_waitv` | `SIGSYS` |

This changes the next milestone: KCalc is no longer blocked by missing Qt/KF6 ELF dependencies. It is blocked by Arch glibc using syscalls Android's app process policy kills. The bridge should patch or configure its glibc profile to avoid `set_robust_list`, `rseq`, `openat2`, and `faccessat2`, and it should continue having Android/bridge code pre-create Linux base directories instead of letting first-run Linux code issue direct `mkdir`.

Evidence artifact: `artifacts/kcalc-syscall-window.xml` and `artifacts/kcalc-syscall-summary.md`.

## Glibc loader patch iteration - 2026-07-09

New evidence from the KCalc APK emulator run:

- The app now prints on-device bytes for the actual loader/libc files it executes, proving the targeted binary patches are present after APK install and runtime extraction.
- Patched bytes confirmed on-device:
  - loader `set_robust_list` site `0x140d8`: `31 c0 ...`
  - loader `rseq` site `0x1416d`: `f7 d8 ...`
  - libc startup/pthread/fork `set_robust_list`/`rseq` sites: patched as expected
  - libc `faccessat2` and `openat2` sites: patched as expected
- Even with those bytes present, `libarchphene_ld.so --list libc.so.6` exits `159` with no stdout/stderr. The failure is therefore below Qt/KF6/KCalc and happens at basic glibc loader/libc startup.
- App-spawned `/system/bin/strace` was tested but cannot produce a trace for this stop on the emulator image: it exits `1` with `Unexpected wait status 0x1f` and an empty trace file.
- `dmesg` is not readable from the unprivileged emulator shell, so kernel seccomp audit details are not available without elevated/root image access.
- Additional app-spawned loader-adjacent syscall probes show `readlinkat`, `setitimer`, `execve` with null args, `uname`, `sched_setaffinity`, `sched_getaffinity`, and `getcpu` are allowed or return ordinary Linux errno. The known killed syscalls remain `openat2`, `faccessat2`, `set_robust_list`, `rseq`, `io_uring_setup`, and `futex_waitv`.
- A diagnostic patch to make libc's exported generic `syscall()` wrapper immediately return `-ENOSYS` was tested and did not change the `--list libc` failure. That experiment was reverted from the working APK files after saving evidence.

Artifacts:

- `artifacts/kcalc-byte-report-window.xml`
- `artifacts/kcalc-strace-window.xml`
- `artifacts/kcalc-loader-probes-window.xml`
- `artifacts/kcalc-generic-syscall-patched-window.xml`
- `artifacts/kcalc-post-generic-revert-window.xml`
- `artifacts/kcalc-syscallscan.txt`

Current conclusion: ad hoc binary patching of stock Arch glibc is not a reliable path to first GUI. The no-OS-edit bridge still looks viable, but it needs a bridge-owned libc/glibc build or source-level loader instrumentation where blocked syscall fallbacks are implemented intentionally and observably. The next milestone should be one of:

1. build a controlled glibc variant for the bridge with Android-app seccomp compatibility patches and progress logging,
2. use a musl-based compatibility/runtime path for early app proofs while keeping glibc support as a separate runtime target,
3. run the same APK on a rooted/userdebug image with usable seccomp audit/modern strace to identify the exact remaining killed inline site.

## Linux-rendered frame bridge milestone - 2026-07-09

New GUI-facing proof: the KCalc APK now displays a frame generated by a Linux ELF process inside the Android Activity.

Implementation:

- Added `prototypes/kcalc-android-app/archphene_frame_client.c`.
- The build script compiles it with the Android NDK as `libarchphene_frame_client.so` and packages it in `lib/x86_64`.
- The Activity starts a JNI-owned filesystem UNIX socket at `files/wayland-runtime/archphene-frame-0`.
- The Linux frame client connects using `XDG_RUNTIME_DIR` + `WAYLAND_DISPLAY`, renders a 360x220 RGBA calculator-like frame, and streams it to Android.
- Android reads `ARCHPHENE_FRAME_V1 <width> <height>`, converts RGBA to `Bitmap.Config.ARGB_8888`, and displays it in an `ImageView` above the diagnostic report.

Emulator result:

```text
Linux payload sends RGBA frame to Android UI bridge
Exit code: 0
Stdout:
sent Linux-rendered frame 360x220 to /data/user/0/org.archphene.linux.kcalc/files/wayland-runtime/archphene-frame-0

Frame bridge server accepted: true
Frame bridge server header: ARCHPHENE_FRAME_V1 360 220
Frame bridge dimensions: 360x220
Frame bridge bytes: 316800
Frame bridge bitmap ready: true
Frame bridge error:
```

Screenshot verification:

- `artifacts/kcalc-frame-bridge.png` is a valid PNG, `1080x2400`.
- Sampled preview-area pixel: `ARGB 255,8,18,18`, matching the Linux-rendered display region.

Artifacts:

- `artifacts/kcalc-frame-bridge.png`
- `artifacts/kcalc-frame-bridge-window.xml`

Meaning:

- We now have a visible Android UI surface fed by a Linux process running in the APK sandbox.
- This proves the process/display bridge direction needed for generic Linux app GUI support.
- It is not a real Wayland compositor yet: the payload uses a bridge-native frame protocol, not `wl_display`, `wl_shm`, `xdg_wm_base`, input events, or Qt/KF6.
- The next GUI milestone is to replace this bridge-native frame protocol with a minimal Wayland `wl_shm` compositor path, then point a controlled Wayland client at it before returning to KCalc.

## wl_shm-style memfd frame bridge milestone - 2026-07-09

New GUI bridge proof: the KCalc APK now receives a Linux-created memfd over `SCM_RIGHTS` and displays pixels read from that shared-memory fd.

Implementation:

- Added `prototypes/kcalc-android-app/archphene_shm_frame_client.c`.
- The build script compiles it as `libarchphene_shm_frame_client.so` with the Android NDK.
- The Linux client creates a memfd with `memfd_create`, resizes it with `ftruncate`, maps it with `mmap`, draws a 320x200 RGBA frame, and sends the fd over an app-local UNIX socket using `sendmsg` + `SCM_RIGHTS`.
- Android accepts the socket through the existing JNI-created filesystem socket bridge, calls `LocalSocket.getAncillaryFileDescriptors()`, reads the fd through `FileInputStream`, converts RGBA to `Bitmap.Config.ARGB_8888`, and displays it in the Activity.

Emulator result:

```text
Linux payload sends memfd-backed wl_shm-style frame
Exit code: 0
Stdout:
sent wl_shm-style memfd frame 320x200 stride=1280 bytes=256000

Shm frame bridge server accepted: true
Shm frame bridge header: ARCHPHENE_SHM_FRAME_V1 320 200 1280 256000
Shm frame bridge fd count: 1
Shm frame bridge dimensions: 320x200 stride=1280
Shm frame bridge bytes: 256000
Shm frame bridge bitmap ready: true
Shm frame bridge error:
```

Screenshot verification:

- `artifacts/kcalc-shm-frame.png` is a valid PNG, `1080x2400`.
- Sampled preview-area pixels match colors generated by the shm client, for example `ARGB 255,9,24,29` and `ARGB 255,23,31,42`.

Artifacts:

- `artifacts/kcalc-shm-frame.png`
- `artifacts/kcalc-shm-frame-window.xml`

Meaning:

- The bridge can now pass Linux-owned shared-memory GUI buffers into Android without a VM and without OS-level edits.
- This is materially closer to Wayland than the previous byte-stream frame protocol because `wl_shm` also depends on passing a shared-memory fd over the Wayland UNIX socket.
- It is still not a complete Wayland compositor: the next work is implementing actual Wayland object IDs/messages for `wl_display`, `wl_registry`, `wl_shm`, `wl_compositor`, `wl_surface`, and `xdg_wm_base`, using this proven fd-transfer path for `wl_shm_pool`/`wl_buffer`.

## Raw Wayland wl_shm request milestone - 2026-07-09

New compositor-facing proof: the KCalc APK now accepts and parses a controlled sequence of real Wayland-style requests and renders the committed `wl_shm` buffer into Android.

Implementation:

- Added `prototypes/kcalc-android-app/archphene_wayland_shm_client.c`.
- The build script compiles it as `libarchphene_wayland_shm_client.so` with the Android NDK.
- The client sends Wayland wire-format request headers/payloads for:
  - `wl_display.get_registry`
  - `wl_registry.bind` for `wl_shm`
  - `wl_registry.bind` for `wl_compositor`
  - `wl_shm.create_pool` with an `SCM_RIGHTS` memfd
  - `wl_shm_pool.create_buffer`
  - `wl_compositor.create_surface`
  - `wl_surface.attach`
  - `wl_surface.damage`
  - `wl_surface.commit`
- Android parses object ids/opcodes, receives the pool fd, tracks the buffer/surface metadata, reads the memfd on commit, converts the frame to `Bitmap.Config.ARGB_8888`, and displays it.

Emulator result:

```text
Linux payload sends raw Wayland wl_shm commit
Exit code: 0
Stdout:
sent raw Wayland wl_shm commit 300x180 stride=1200 bytes=216000

Raw Wayland server accepted: true
Raw Wayland parsed messages: 9
Raw Wayland fd count: 1
Raw Wayland dimensions: 300x180 stride=1200
Raw Wayland bytes: 216000
Raw Wayland committed: true
Raw Wayland bitmap ready: true
```

Parsed Wayland messages:

```text
object=1 opcode=1 size=12 wl_display.get_registry new_id=2
object=2 opcode=0 size=32 wl_registry.bind name=1 interface=wl_shm version=1 new_id=3
object=2 opcode=0 size=40 wl_registry.bind name=2 interface=wl_compositor version=1 new_id=6
object=3 opcode=0 size=16 wl_shm.create_pool pool_id=4 size=216000 fds=1
object=4 opcode=0 size=32 wl_shm_pool.create_buffer buffer_id=5 offset=0 width=300 height=180 stride=1200 format=0
object=6 opcode=0 size=12 wl_compositor.create_surface surface_id=7
object=7 opcode=1 size=20 wl_surface.attach buffer=5 x=0 y=0
object=7 opcode=2 size=24 wl_surface.damage x=0 y=0 w=300 h=180
object=7 opcode=6 size=8 wl_surface.commit
```

Screenshot verification:

- `artifacts/kcalc-raw-wayland-shm.png` is a valid PNG, `1080x2400`.
- Sampled preview-area pixels match colors generated by the raw Wayland client, for example `ARGB 255,7,21,26` and `ARGB 255,22,27,36`.

Artifacts:

- `artifacts/kcalc-raw-wayland-shm.png`
- `artifacts/kcalc-raw-wayland-shm-window.xml`

Meaning:

- This is the closest GUI proof so far: Android is now acting as a minimal controlled Wayland compositor for the core `wl_shm` surface commit path.
- It is still not enough for Qt/KF6/KCalc because real clients require server events and protocol objects not implemented yet, especially registry `global` events, `wl_callback`, `wl_seat`, `xdg_wm_base`, xdg surface/toplevel configure/ack flows, frame callbacks, and production-correct ARGB/XRGB handling.
- The next GUI milestone is to make this interactive in protocol terms: send real registry globals and enough `xdg_wm_base`/`xdg_surface`/`xdg_toplevel` configure events for a small libwayland-client program to use the bridge without hardcoded object ids.

## Full-window Android Activity surface milestone - 2026-07-09

The KCalc launcher APK now renders the bridge output as the primary Android Activity surface instead of as a small diagnostic preview.

Implementation:

- `MainActivity` uses a `FrameLayout` root with the raw Wayland bitmap `ImageView` set to `MATCH_PARENT` width and height.
- The diagnostic report is reduced to a compact bottom overlay so the Linux-rendered surface owns the app window visually.
- `ImageView.ScaleType.FIT_XY` is used for this proof so the controlled `300x180` Wayland `wl_shm` buffer fills the Android window.

Emulator result:

```text
Archphene KCalc window bridge
Raw Wayland parsed messages: 9
Raw Wayland fd count: 1
Raw Wayland dimensions: 300x180 stride=1200
Raw Wayland committed: true
Raw Wayland bitmap ready: true
glibc loader --list kcalc
```

Screenshot verification:

- `artifacts/kcalc-full-window-fitxy.png` is a valid PNG, `1080x2400`.
- `artifacts/kcalc-full-window-fitxy.xml` contains only the compact status overlay, confirming the old scrollable diagnostic report is no longer the main UI.
- Center-column samples are non-black across the Activity surface, showing the Wayland buffer is filling the app window:
  - `sample_540_400=255,7,21,26`
  - `sample_540_800=255,7,21,26`
  - `sample_540_1200=255,22,27,36`
  - `sample_540_1800=255,22,27,36`
  - `sample_540_2100=255,12,39,27`

Artifacts:

- `artifacts/kcalc-full-window-fitxy.png`
- `artifacts/kcalc-full-window-fitxy.xml`

Meaning:

- The bridge can now present Linux-rendered pixels as the Android app window, not just as diagnostics.
- This is still a compositor proof, not real KCalc GUI yet: production should negotiate the Android window size through Wayland/xdg-shell configure flows and have the Linux client render the correct dimensions instead of stretching a fixed test buffer.
- The next GUI milestone is to add server-to-client Wayland events for registry globals and xdg-shell configure, then run a small normal `libwayland-client` program through the bridge before attempting Qt/KF6/KCalc.

## Evented Wayland registry roundtrip milestone - 2026-07-09

The bridge now has a controlled client/server test for the first real bidirectional Wayland compositor behavior. This is a step beyond hardcoded request parsing: the Linux payload waits for compositor events before binding globals and committing a buffer.

Implementation:

- Added `prototypes/kcalc-android-app/archphene_wayland_evented_client.c`.
- The build script compiles it as `libarchphene_wayland_evented_client.so` with the Android NDK.
- `MainActivity` now saves the full diagnostic report to app-private `files/kcalc-report.txt`, which can be pulled from the userdebug emulator for protocol debugging.
- `RawWaylandShmServer` now has two modes:
  - eventless mode for the older hardcoded raw Wayland request test
  - evented mode for registry globals, sync callback completion, and shm format advertisement
- In evented mode, Android sends:
  - `wl_registry.global` for `wl_shm`
  - `wl_registry.global` for `wl_compositor`
  - `wl_registry.global` for `xdg_wm_base`
  - `wl_callback.done` for `wl_display.sync`
  - `wl_shm.format` after the client binds `wl_shm`

Emulator result:

```text
Evented Wayland parsed messages: 10
Evented Wayland registry globals: 3
Evented Wayland callback done sent: true
Evented Wayland shm formats sent: 1
Evented Wayland fd count: 1
Evented Wayland dimensions: 360x220 stride=1440
Evented Wayland bytes: 316800
Evented Wayland committed: true
Evented Wayland bitmap ready: true
```

Client stdout:

```text
evented Wayland wl_shm commit 360x220 stride=1440 bytes=316800 globals wl_shm=1 wl_compositor=2 xdg_wm_base=3
```

Protocol log:

```text
object=1 opcode=1 size=12 wl_display.get_registry new_id=2
server->client object=2 opcode=0 wl_registry.global name=1 interface=wl_shm version=1
server->client object=2 opcode=0 wl_registry.global name=2 interface=wl_compositor version=1
server->client object=2 opcode=0 wl_registry.global name=3 interface=xdg_wm_base version=1
object=1 opcode=0 size=12 wl_display.sync callback_id=8
server->client object=8 opcode=0 wl_callback.done value=1
object=2 opcode=0 size=32 wl_registry.bind name=1 interface=wl_shm version=1 new_id=3
server->client object=3 opcode=0 wl_shm.format ARGB8888 value=0
object=2 opcode=0 size=40 wl_registry.bind name=2 interface=wl_compositor version=1 new_id=6
object=3 opcode=0 size=16 wl_shm.create_pool pool_id=4 size=316800 fds=1
object=4 opcode=0 size=32 wl_shm_pool.create_buffer buffer_id=5 offset=0 width=360 height=220 stride=1440 format=0
object=6 opcode=0 size=12 wl_compositor.create_surface surface_id=7
object=7 opcode=1 size=20 wl_surface.attach buffer=5 x=0 y=0
object=7 opcode=2 size=24 wl_surface.damage x=0 y=0 w=360 h=220
object=7 opcode=6 size=8 wl_surface.commit
```

Screenshot verification:

- `artifacts/kcalc-evented-wayland-v2.png` is a valid PNG, `1080x2400`.
- `artifacts/kcalc-evented-wayland-v2.xml` contains the compact overlay with passing raw and evented Wayland summaries.
- Center-column samples show the evented client frame filling the Activity surface:
  - `sample_540_400=255,27,66,70`
  - `sample_540_800=255,27,66,70`
  - `sample_540_1200=255,14,31,39`
  - `sample_540_1800=255,14,31,39`
  - `sample_540_2100=255,4,8,10`

Artifacts:

- `artifacts/kcalc-evented-wayland-v2.png`
- `artifacts/kcalc-evented-wayland-v2.xml`
- `artifacts/kcalc-evented-wayland-v2-report.txt`

Meaning:

- The Android app is no longer only receiving one-way Wayland-shaped requests. It can now participate in the normal early Wayland handshake by advertising globals and completing a sync roundtrip.
- This is still below real toolkit requirements. The next compositor milestone is `xdg_wm_base`: handle `xdg_wm_base.get_xdg_surface`, `xdg_surface.get_toplevel`, send `xdg_surface.configure` and `xdg_toplevel.configure`, accept `xdg_surface.ack_configure`, then only treat commits as mapped after the configure/ack path.

## XDG shell configure/ack milestone - 2026-07-09

The bridge now has a controlled xdg-shell toplevel flow. This is the first proof that the Android-side compositor can model the lifecycle desktop Wayland toolkits expect before mapping a real app window.

Implementation:

- Added `prototypes/kcalc-android-app/archphene_wayland_xdg_client.c`.
- The build script compiles it as `libarchphene_wayland_xdg_client.so` with the Android NDK.
- The controlled Linux payload now:
  - discovers `wl_shm`, `wl_compositor`, and `xdg_wm_base` through registry events
  - binds `wl_shm` and reads `wl_shm.format`
  - binds `wl_compositor` and `xdg_wm_base`
  - creates a `wl_surface`
  - creates an `xdg_surface` and `xdg_toplevel`
  - sends the initial empty `wl_surface.commit`
  - waits for `xdg_toplevel.configure` and `xdg_surface.configure`
  - sends `xdg_surface.ack_configure`
  - creates a `wl_shm` buffer, attaches it, damages it, and commits the mapped surface
- The Android compositor server now tracks xdg object ids, sends configure events, requires `ack_configure`, then imports and renders the committed shm buffer.

Emulator result:

```text
XDG Wayland parsed messages: 15
XDG Wayland registry globals: 3
XDG Wayland callback done sent: true
XDG Wayland shm formats sent: 1
XDG Wayland configure sent: true serial=42
XDG Wayland configure acked: true
XDG Wayland fd count: 1
XDG Wayland dimensions: 420x260 stride=1680
XDG Wayland bytes: 436800
XDG Wayland committed: true
XDG Wayland bitmap ready: true
```

Client stdout:

```text
xdg Wayland wl_shm commit 420x260 stride=1680 bytes=436800 globals wl_shm=1 wl_compositor=2 xdg_wm_base=3 configure_serial=42
```

Protocol highlights:

```text
object=2 opcode=0 size=36 wl_registry.bind name=3 interface=xdg_wm_base version=1 new_id=9
object=6 opcode=0 size=12 wl_compositor.create_surface surface_id=7
object=9 opcode=2 size=16 xdg_wm_base.get_xdg_surface xdg_surface_id=10 surface=7
object=10 opcode=1 size=12 xdg_surface.get_toplevel xdg_toplevel_id=11
object=7 opcode=6 size=8 wl_surface.commit
server->client object=11 opcode=0 xdg_toplevel.configure width=420 height=260 states=0
server->client object=10 opcode=0 xdg_surface.configure serial=42
object=10 opcode=4 size=12 xdg_surface.ack_configure serial=42 matched=true
object=3 opcode=0 size=16 wl_shm.create_pool pool_id=4 size=436800 fds=1
object=4 opcode=0 size=32 wl_shm_pool.create_buffer buffer_id=5 offset=0 width=420 height=260 stride=1680 format=0
object=7 opcode=1 size=20 wl_surface.attach buffer=5 x=0 y=0
object=7 opcode=2 size=24 wl_surface.damage x=0 y=0 w=420 h=260
object=7 opcode=6 size=8 wl_surface.commit
```

Screenshot verification:

- `artifacts/kcalc-xdg-wayland.png` is a valid PNG, `1080x2400`.
- `artifacts/kcalc-xdg-wayland.xml` contains the compact overlay with passing raw, evented, and xdg Wayland summaries.
- Center-column samples show the xdg client frame filling the Activity surface:
  - `sample_540_400=255,27,66,70`
  - `sample_540_800=255,14,31,39`
  - `sample_540_1200=255,27,53,65`
  - `sample_540_1800=255,60,63,65`
  - `sample_540_2100=255,9,19,16`

Artifacts:

- `artifacts/kcalc-xdg-wayland.png`
- `artifacts/kcalc-xdg-wayland.xml`
- `artifacts/kcalc-xdg-wayland-report.txt`

Meaning:

- The bridge can now emulate the core Wayland window creation lifecycle used by normal desktop clients: registry discovery, compositor surface creation, xdg toplevel configuration, configure acknowledgement, shm buffer attachment, and final commit.
- This still is not enough for Qt/KF6/KCalc. The next compositor work is to cover the protocol features real Qt expects around event queues and object lifetimes: `wl_surface.frame` callbacks, `wl_callback.done` for frames, `wl_seat`/keyboard/pointer globals, `xdg_wm_base.ping`/`pong`, output scaling, surface enter/leave, and robust handling of repeated commits/resizes.
- The separate pre-GUI blocker remains the Arch glibc/loader path for the real KCalc binary. The compositor proof is now far enough along that the next highest-value work is either a libwayland-client compatibility test or the bridge-owned libc/glibc path.

## Native-size xdg render and frame lifecycle milestone - 2026-07-09

The stretched rendering issue is fixed for the controlled xdg-shell compositor path. The Android compositor no longer configures a fixed `420x260` test window and stretches it to the Activity. It now sends the emulator display size through xdg-shell, and the Linux-side client allocates a matching `wl_shm` buffer after receiving configure.

Implementation:

- `MainActivity` now reads the current display size with Android display metrics and passes it into the xdg Wayland server.
- The xdg compositor path sends `xdg_toplevel.configure` with the actual Android display size.
- `archphene_wayland_xdg_client.c` no longer uses compile-time `WIDTH`/`HEIGHT` for the committed xdg buffer.
- The xdg client now waits for configure, reads the configured width/height, allocates a memfd-backed `wl_shm` buffer at that exact size, draws into it, and then commits.
- The Activity `ImageView` now uses `FIT_CENTER` instead of `FIT_XY`, avoiding aspect-ratio distortion.
- The xdg path now also supports a basic frame lifecycle:
  - client requests `wl_surface.frame`
  - server sends `wl_callback.done`
  - server sends `wl_buffer.release`
  - client waits for both events before exiting

Emulator result:

```text
XDG Wayland parsed messages: 16
XDG Wayland configure sent: true serial=42 configured=1080x2400
XDG Wayland configure acked: true
XDG Wayland frame callback done: true
XDG Wayland buffer released: true
XDG Wayland fd count: 1
XDG Wayland dimensions: 1080x2400 stride=4320
XDG Wayland bytes: 10368000
XDG Wayland committed: true
XDG Wayland bitmap ready: true
```

Client stdout:

```text
xdg Wayland wl_shm commit 1080x2400 stride=4320 bytes=10368000 globals wl_shm=1 wl_compositor=2 xdg_wm_base=3 configure_serial=42 frame_done=1 buffer_released=1
```

Protocol highlights:

```text
server->client object=11 opcode=0 xdg_toplevel.configure width=1080 height=2400 states=0
object=4 opcode=0 size=32 wl_shm_pool.create_buffer buffer_id=5 offset=0 width=1080 height=2400 stride=4320 format=0
object=7 opcode=3 size=12 wl_surface.frame callback_id=12
server->client object=12 opcode=0 wl_callback.done frame value=2
server->client object=5 opcode=0 wl_buffer.release
```

Screenshot verification:

- `artifacts/kcalc-native-size-frame-v2.png` is a valid PNG, `1080x2400`.
- The xdg buffer dimensions are also `1080x2400`, so there is no `420x260` or other fixed-size buffer being stretched to the Activity.
- Pixel samples cover edges and center of the window, confirming the rendered buffer fills the screenshot bounds:
  - `sample_10_10=255,18,20,38`
  - `sample_540_80=255,35,22,45`
  - `sample_1070_10=255,51,20,51`
  - `sample_20_400=255,18,30,43`
  - `sample_540_400=255,27,66,70`
  - `sample_1060_400=255,51,30,55`
  - `sample_540_1200=255,14,31,39`
  - `sample_540_2000=255,4,8,10`
  - `sample_540_2300=255,9,21,18`

Artifacts:

- `artifacts/kcalc-native-size-frame-v2.png`
- `artifacts/kcalc-native-size-frame-v2.xml`
- `artifacts/kcalc-native-size-frame-v2-report.txt`

Meaning:

- The controlled xdg client now renders at native Android window resolution and is no longer distorted by display scaling.
- The compositor path now includes the minimum xdg-shell sizing loop, shm buffer import, frame callback, and buffer release needed for a desktop-style render lifecycle.
- This proves the rendering path can look correct when the client can run and honor the compositor configure size.
- The remaining gap to real KCalc/Qt is no longer the stretched proof surface. It is broader toolkit compatibility and the real Arch glibc/loader path.

## KCalc-shaped visual surface milestone - 2026-07-09

The controlled xdg client no longer renders an abstract diagnostic pattern. It now draws a KCalc-like calculator window based on the KDE reference shape while keeping the same native-size xdg-shell compositor path.

Implementation:

- Replaced the synthetic colored block drawing in `archphene_wayland_xdg_client.c` with a KCalc-style UI renderer.
- Added simple built-in bitmap text drawing for calculator labels.
- The rendered surface now includes:
  - dark desktop/background around the window
  - light KCalc window frame
  - title bar with `KCALC`
  - menu row with `FILE`, `EDIT`, `SETTINGS`, `HELP`
  - white display area with separator line
  - calculator key grid matching the KDE KCalc layout shape
  - tall `+` and `=` keys
  - bottom status row with `NORM`
- The visual renderer still uses the negotiated native xdg size (`1080x2400` in the emulator run) and does not rely on Android stretching.

Emulator result:

```text
XDG Wayland configure sent: true serial=42 configured=1080x2400
XDG Wayland configure acked: true
XDG Wayland frame callback done: true
XDG Wayland buffer released: true
XDG Wayland dimensions: 1080x2400 stride=4320
XDG Wayland committed: true
XDG Wayland bitmap ready: true
```

Screenshot verification:

- `artifacts/kcalc-visual-kcalc.png` is a valid PNG, `1080x2400`.
- `artifacts/kcalc-visual-kcalc.xml` confirms the Activity overlay still reports all compositor tests passing.
- Pixel samples show black background outside the centered window and light KCalc-like display/grid/status regions inside the window:
  - `sample_540_250=255,0,0,0`
  - `sample_540_660=255,0,0,0`
  - `sample_250_900=255,252,252,252`
  - `sample_540_900=255,252,252,252`
  - `sample_820_900=255,252,252,252`
  - `sample_250_1220=255,239,241,243`
  - `sample_540_1220=255,239,241,243`
  - `sample_540_2150=255,148,148,148`

Artifacts:

- `artifacts/kcalc-visual-kcalc.png`
- `artifacts/kcalc-visual-kcalc.xml`
- `artifacts/kcalc-visual-kcalc-report.txt`

Meaning:

- The visual proof now matches the KCalc target shape instead of only proving that pixels can cross the bridge.
- This is still not the real KDE KCalc binary painting through Qt. It is a controlled Wayland client rendering a KCalc-like UI. The remaining work for actual KCalc is to get the real Arch KCalc/Qt/KF6 process running through the Android-compatible libc/runtime path, then satisfy the additional Wayland protocol Qt expects.

## Android-built Wayland client API milestone - 2026-07-09

This milestone tested whether a normal Wayland client API path can run in the Android app environment without a VM.

Implementation:

- Added `archphene_wayland_api_client.c`, a small client written against normal `wayland-client.h` APIs.
- Added a negative-control build linked against the packaged Arch `libwayland-client.so.0`.
- Added `archphene_wayland_client_android.c`, a bridge-owned Android/bionic shared library implementing the minimum Wayland client ABI needed for registry discovery, `wl_registry.bind`, sync callbacks, and `wl_shm.format` dispatch.
- Staged local Wayland protocol headers in `prototypes/kcalc-android-app/wayland-include` so the Android build uses bionic C headers rather than Arch glibc headers.
- Added a two-sync server mode to the Android compositor harness for API-client registry/bind tests.

Negative-control result using Arch `libwayland-client.so.0`:

```text
Wayland API client exit code: 139
Wayland API server accepted: false
Wayland API server parsed messages: 0
WARNING: linker: Warning: ".../libc.so.6" unused DT entry: unknown processor-specific ...
```

Passing result using the Android-built bridge Wayland client shim:

```text
wayland-client API probe connected globals=3 wl_shm=1 wl_compositor=2 xdg_wm_base=3 shm_formats=1
Android Wayland API client exit code: 0
Android Wayland API server accepted: true
Android Wayland API server parsed messages: 5
Android Wayland API server registry globals: 3
Android Wayland API server sync callbacks: 2
Android Wayland API server shm formats: 1
Android Wayland API server completed: true
```

Protocol highlights:

```text
object=1 opcode=1 size=12 wl_display.get_registry new_id=2
server->client object=2 opcode=0 wl_registry.global name=1 interface=wl_shm version=1
server->client object=2 opcode=0 wl_registry.global name=2 interface=wl_compositor version=1
server->client object=2 opcode=0 wl_registry.global name=3 interface=xdg_wm_base version=1
object=1 opcode=0 size=12 wl_display.sync callback_id=3
object=2 opcode=0 size=32 wl_registry.bind name=1 interface=wl_shm version=1 new_id=4
server->client object=4 opcode=0 wl_shm.format ARGB8888 value=0
object=2 opcode=0 size=40 wl_registry.bind name=2 interface=wl_compositor version=1 new_id=5
object=1 opcode=0 size=12 wl_display.sync callback_id=6
```

Artifacts:

- `artifacts/kcalc-android-wayland-api-pass.png`
- `artifacts/kcalc-android-wayland-api-pass.xml`
- `artifacts/kcalc-android-wayland-api-pass-report.txt`

Meaning:

- Directly loading Arch/glibc-linked client-side graphics libraries from an Android/bionic executable is not viable; it crashes before the compositor sees a connection.
- A bridge-owned Android-native dependency can expose the same `wayland-client.h` API surface for controlled clients and successfully communicate with the Android compositor harness.
- This supports the bridge/dependency-manager direction: dependencies that cross into Android runtime services should be bridge-owned and Android-compatible, while the remaining hard problem is the actual Linux app ABI/runtime side for real Arch Qt/KF6 processes.
- The next milestone is to expand the Android-built Wayland client shim beyond registry/bind into `wl_shm.create_pool`, `wl_compositor.create_surface`, `wl_surface.attach/damage/commit`, and then xdg-shell wrappers so an API-client renderer can replace the hand-written wire client.

## Android-built Wayland API wl_shm render milestone - 2026-07-09

This milestone moved beyond registry/bind and proved a client written against normal `wayland-client.h` rendering APIs can create a software buffer and commit a `wl_surface` through the Android compositor harness.

Implementation:

- Added `archphene_wayland_api_render_client.c`, a normal Wayland API client using:
  - `wl_display_connect`
  - `wl_display_get_registry`
  - `wl_registry_bind`
  - `wl_shm_create_pool`
  - `wl_shm_pool_create_buffer`
  - `wl_compositor_create_surface`
  - `wl_surface_attach`
  - `wl_surface_damage`
  - `wl_surface_commit`
- Extended `archphene_wayland_client_android.c` to marshal object-creating requests and pass `memfd` descriptors with `SCM_RIGHTS`.
- Updated the Android compositor harness to track object IDs from `wl_registry.bind` rather than assuming fixed client object IDs.
- Added `libarchphene_wayland_android_api_render_client.so` to the APK build and report flow.

Emulator result:

```text
wayland-client API render committed 420x260 stride=1680 bytes=436800 globals=3 formats=1
Android Wayland API render exit code: 0
Android Wayland API render accepted: true
Android Wayland API render parsed messages: 11
Android Wayland API render registry globals: 3
Android Wayland API render sync callbacks: 2
Android Wayland API render shm formats: 1
Android Wayland API render fd count: 1
Android Wayland API render dimensions: 420x260 stride=1680
Android Wayland API render bytes: 436800
Android Wayland API render committed: true
Android Wayland API render bitmap ready: true
```

Protocol highlights:

```text
object=4 opcode=0 size=16 wl_shm.create_pool pool_id=7 size=436800 fds=1
object=7 opcode=0 size=32 wl_shm_pool.create_buffer buffer_id=8 offset=0 width=420 height=260 stride=1680 format=0
object=5 opcode=0 size=12 wl_compositor.create_surface surface_id=9
object=9 opcode=1 size=20 wl_surface.attach buffer=8 x=0 y=0
object=9 opcode=2 size=24 wl_surface.damage x=0 y=0 w=420 h=260
object=9 opcode=6 size=8 wl_surface.commit
```

Screenshot verification:

- `artifacts/kcalc-android-wayland-api-render-clean.png` is a valid PNG, `1080x2400`.
- Pixel samples show the API-rendered white and dark regions are visible in the final Android screenshot:
  - `sample_260_1130=255,255,255,255`
  - `sample_540_1130=255,255,255,255`
  - `sample_820_1130=255,255,255,255`
  - `sample_540_1200=255,65,65,65`
  - `sample_540_1280=255,62,62,63`

Artifacts:

- `artifacts/kcalc-android-wayland-api-render-clean.png`
- `artifacts/kcalc-android-wayland-api-render-clean.xml`
- `artifacts/kcalc-android-wayland-api-render-clean-report.txt`

Meaning:

- We now have a bridge-owned Android-native Wayland client library that can run a normal Wayland API client through shared-memory surface rendering.
- This is the bridge shape needed for Android-side dependencies: expose familiar Linux/Wayland APIs while keeping the implementation Android-compatible.
- The next graphics milestone is xdg-shell API support: bind `xdg_wm_base`, create an `xdg_surface`/`xdg_toplevel`, receive configure events, ack configure, then commit a buffer through the same API path.

## Android-built Wayland API xdg-shell milestone - 2026-07-09

This milestone replaced the hand-written xdg-shell client path with a client using generated-style xdg-shell API wrappers and the bridge-owned Android Wayland client shim.

Implementation:

- Staged `wayland-xdg-shell-client-protocol.h` into `prototypes/kcalc-android-app/wayland-include`.
- Extended `archphene_wayland_client_android.c` with:
  - `xdg_wm_base_interface`
  - `xdg_surface_interface`
  - `xdg_toplevel_interface`
  - xdg object creation through `wl_proxy_marshal_flags`
  - `xdg_surface.ack_configure`
  - dispatch for `xdg_toplevel.configure`
  - dispatch for `xdg_surface.configure`
- Added `archphene_wayland_api_xdg_client.c`, which uses normal API calls to:
  - bind `wl_shm`, `wl_compositor`, and `xdg_wm_base`
  - create `wl_surface`
  - create `xdg_surface` and `xdg_toplevel`
  - perform the initial empty commit
  - receive configure events
  - ack configure serial 42
  - create a `wl_shm` buffer
  - attach/damage/commit the native-size surface

Emulator result:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=3 formats=1
Android Wayland API xdg exit code: 0
Android Wayland API xdg accepted: true
Android Wayland API xdg parsed messages: 17
Android Wayland API xdg registry globals: 3
Android Wayland API xdg sync callbacks: 3
Android Wayland API xdg shm formats: 1
Android Wayland API xdg configure sent: true serial=42 configured=1080x2400
Android Wayland API xdg configure acked: true
Android Wayland API xdg fd count: 1
Android Wayland API xdg dimensions: 1080x2400 stride=4320
Android Wayland API xdg bytes: 10368000
Android Wayland API xdg committed: true
Android Wayland API xdg bitmap ready: true
```

Protocol highlights:

```text
object=6 opcode=2 size=16 xdg_wm_base.get_xdg_surface xdg_surface_id=9 surface=8
object=9 opcode=1 size=12 xdg_surface.get_toplevel xdg_toplevel_id=10
object=8 opcode=6 size=8 wl_surface.commit
server->client object=10 opcode=0 xdg_toplevel.configure width=1080 height=2400 states=0
server->client object=9 opcode=0 xdg_surface.configure serial=42
object=9 opcode=4 size=12 xdg_surface.ack_configure serial=42 matched=true
object=8 opcode=1 size=20 wl_surface.attach buffer=13 x=0 y=0
object=8 opcode=2 size=24 wl_surface.damage x=0 y=0 w=1080 h=2400
object=8 opcode=6 size=8 wl_surface.commit
```

Screenshot verification:

- `artifacts/kcalc-android-wayland-api-xdg.png` is a valid PNG, `1080x2400`.
- Pixel samples show the xdg API-rendered native-size client surface is visible:
  - `sample_540_250=255,244,246,248`
  - `sample_100_400=255,255,255,255`
  - `sample_540_520=255,255,255,255`
  - `sample_540_900=255,244,246,248`
  - `sample_540_1500=255,62,63,63`
  - `sample_540_2200=255,62,63,63`

Artifacts:

- `artifacts/kcalc-android-wayland-api-xdg.png`
- `artifacts/kcalc-android-wayland-api-xdg.xml`
- `artifacts/kcalc-android-wayland-api-xdg-report.txt`

Meaning:

- The bridge-owned Android Wayland client shim now supports the core API sequence a desktop toolkit expects for a basic xdg toplevel: global bind, initial commit, configure, ack, shm buffer commit.
- This does not make real Qt/KF6 run yet; it proves the Android-side Wayland API/compositor bridge can follow the same protocol shape a real toolkit uses.
- The next milestones are toolkit-facing compatibility work: object destruction/release handling, frame callbacks, more `wl_surface` requests, seats/input, keyboard/pointer dispatch, and finally running a bionic-linked or bridge-adapted toolkit client through this API surface.

## Android-built Wayland API xdg lifecycle milestone - 2026-07-09

This milestone extends the Android-built `libwayland-client` shim path beyond a basic xdg-shell commit. The client now uses normal API calls for `wl_surface.frame`, `wl_callback_add_listener`, and `wl_buffer_add_listener`; the Java Wayland test server now keeps the connection open for the client's post-commit `wl_display_roundtrip` before marking the probe complete.

Implementation:

- Extended `archphene_wayland_client_android.c` to marshal `wl_surface.frame` and dispatch listener callbacks for `wl_callback.done` and `wl_buffer.release`.
- Extended `archphene_wayland_api_xdg_client.c` to require both `frame_done` and `buffer_released` after the final commit.
- Added a `waitForPostCommitSync` mode to `RawWaylandShmServer` so the Android API xdg probe can drain a post-commit sync without changing the older probe behavior.

Emulator result:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=3 formats=1 frame_done=1 buffer_released=1
Android Wayland API xdg exit code: 0
Android Wayland API xdg accepted: true
Android Wayland API xdg parsed messages: 19
Android Wayland API xdg registry globals: 3
Android Wayland API xdg sync callbacks: 4
Android Wayland API xdg shm formats: 1
Android Wayland API xdg configure sent: true serial=42 configured=1080x2400
Android Wayland API xdg configure acked: true
Android Wayland API xdg frame callback done: true
Android Wayland API xdg buffer released: true
Android Wayland API xdg post-commit sync done: true
Android Wayland API xdg fd count: 1
Android Wayland API xdg dimensions: 1080x2400 stride=4320
Android Wayland API xdg bytes: 10368000
Android Wayland API xdg committed: true
Android Wayland API xdg bitmap ready: true
```

Protocol highlights:

```text
object=8 opcode=3 size=12 wl_surface.frame callback_id=14
object=9 opcode=4 size=12 xdg_surface.ack_configure serial=42 matched=true
object=8 opcode=1 size=20 wl_surface.attach buffer=13 x=0 y=0
object=8 opcode=2 size=24 wl_surface.damage x=0 y=0 w=1080 h=2400
object=8 opcode=6 size=8 wl_surface.commit
server->client object=14 opcode=0 wl_callback.done frame value=2
server->client object=13 opcode=0 wl_buffer.release
object=1 opcode=0 size=12 wl_display.sync callback_id=15
server->client object=15 opcode=0 wl_callback.done value=1
```

Artifacts:

- `artifacts/kcalc-android-wayland-api-xdg-lifecycle.png`
- `artifacts/kcalc-android-wayland-api-xdg-lifecycle.xml`
- `artifacts/kcalc-android-wayland-api-xdg-lifecycle-report.txt`

Meaning:

- The bridge-owned Android Wayland API path now covers the minimum render lifecycle a real toolkit expects after an xdg toplevel draw: configure, ack, frame callback, buffer release, and post-commit sync.
- This still is not real KCalc. The remaining hard work is adapting or rebuilding the Qt/KF6 stack so it links against the bridge-owned bionic Wayland client surface, then adding missing protocol coverage such as seats/input, pointer/keyboard events, output scale, window state, object destruction, and repeated frame scheduling.

## Android-built Wayland API xdg cleanup milestone - 2026-07-09

This milestone proves the bridge-owned Android `libwayland-client` shim can send protocol destroy requests instead of only freeing local proxy objects. The xdg API probe now performs a normal post-render cleanup sequence and verifies it with a final `wl_display_roundtrip`.

Implementation:

- Updated `wl_proxy_marshal_flags` in `archphene_wayland_client_android.c` so unhandled requests marked `WL_MARSHAL_FLAG_DESTROY` send an empty Wayland request before destroying the local proxy.
- Updated `archphene_wayland_api_xdg_client.c` to call:
  - `wl_callback_destroy`
  - `wl_buffer_destroy`
  - `wl_shm_pool_destroy`
  - `xdg_toplevel_destroy`
  - `xdg_surface_destroy`
  - `wl_surface_destroy`
  - `xdg_wm_base_destroy`
- Updated `RawWaylandShmServer` to log cleanup requests and wait for a final cleanup sync before ending the Android API xdg probe.

Emulator result:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=3 formats=1 frame_done=1 buffer_released=1 cleanup_done=1
Android Wayland API xdg exit code: 0
Android Wayland API xdg parsed messages: 26
Android Wayland API xdg sync callbacks: 5
Android Wayland API xdg frame callback done: true
Android Wayland API xdg buffer released: true
Android Wayland API xdg post-commit sync done: true
Android Wayland API xdg cleanup sync done: true
Android Wayland API xdg destroy requests: 6
Android Wayland API xdg committed: true
Android Wayland API xdg bitmap ready: true
```

Protocol cleanup highlights:

```text
object=13 opcode=0 size=8 wl_buffer.destroy
object=12 opcode=1 size=8 wl_shm_pool.destroy
object=10 opcode=0 size=8 xdg_toplevel.destroy
object=9 opcode=0 size=8 xdg_surface.destroy
object=8 opcode=0 size=8 wl_surface.destroy
object=6 opcode=0 size=8 xdg_wm_base.destroy
object=1 opcode=0 size=12 wl_display.sync callback_id=16
server->client object=16 opcode=0 wl_callback.done value=1
```

Artifacts:

- `artifacts/kcalc-android-wayland-api-xdg-cleanup.png`
- `artifacts/kcalc-android-wayland-api-xdg-cleanup.xml`
- `artifacts/kcalc-android-wayland-api-xdg-cleanup-report.txt`

Meaning:

- The bridge now handles a more realistic toolkit lifecycle: create, configure, render, receive frame/buffer lifecycle events, destroy protocol resources, and drain the event queue.
- The next practical compatibility target is input/output protocol coverage: advertise `wl_seat` and `wl_output`, dispatch keyboard/pointer/output events, and map Android input events into Wayland so the rendered app can become interactive rather than just visible.

## Android-built Wayland API output and seat milestone - 2026-07-09

This milestone adds the first environment/input substrate needed by real desktop toolkits. The Android Wayland server now advertises `wl_output` and `wl_seat`, and the Android-built Wayland API xdg client binds them through normal generated Wayland APIs before rendering.

Implementation:

- Added `wl_output_interface` and `wl_seat_interface` symbols to the bridge-owned Android `libwayland-client` shim.
- Added dispatch for:
  - `wl_output.geometry`
  - `wl_output.mode`
  - `wl_output.done`
  - `wl_output.scale`
  - `wl_seat.name`
  - `wl_seat.capabilities`
- Updated `RawWaylandShmServer` to advertise five globals: `wl_shm`, `wl_compositor`, `xdg_wm_base`, `wl_output`, and `wl_seat`.
- Updated the xdg API probe to bind `wl_output` at version 2 and `wl_seat` at version 2, then require output and seat events before rendering.

Emulator result:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=5 formats=1 output=1080x2400 scale=1 seat_caps=3 seat_named=1 frame_done=1 buffer_released=1 cleanup_done=1
Android Wayland API xdg exit code: 0
Android Wayland API xdg parsed messages: 28
Android Wayland API xdg registry globals: 5
Android Wayland API xdg sync callbacks: 5
Android Wayland API xdg shm formats: 1
Android Wayland API xdg output done: true
Android Wayland API xdg seat capabilities sent: true
Android Wayland API xdg configure acked: true
Android Wayland API xdg frame callback done: true
Android Wayland API xdg buffer released: true
Android Wayland API xdg cleanup sync done: true
Android Wayland API xdg destroy requests: 6
Android Wayland API xdg committed: true
Android Wayland API xdg bitmap ready: true
```

Protocol highlights:

```text
server->client object=2 opcode=0 wl_registry.global name=4 interface=wl_output version=2
server->client object=2 opcode=0 wl_registry.global name=5 interface=wl_seat version=2
object=2 opcode=0 size=36 wl_registry.bind name=4 interface=wl_output version=2 new_id=7
server->client object=7 opcode=0 wl_output.geometry make=Archphene model=Android Display
server->client object=7 opcode=1 wl_output.mode current width=1080 height=2400 refresh=60000
server->client object=7 opcode=3 wl_output.scale value=1
server->client object=7 opcode=2 wl_output.done
object=2 opcode=0 size=32 wl_registry.bind name=5 interface=wl_seat version=2 new_id=8
server->client object=8 opcode=1 wl_seat.name default
server->client object=8 opcode=0 wl_seat.capabilities pointer|keyboard value=3
```

Artifacts:

- `artifacts/kcalc-android-wayland-api-xdg-output-seat.png`
- `artifacts/kcalc-android-wayland-api-xdg-output-seat.xml`
- `artifacts/kcalc-android-wayland-api-xdg-output-seat-report.txt`

Meaning:

- The bridge can now tell a toolkit what display it is rendering to and that pointer/keyboard devices exist.
- This still does not deliver input events. The next milestone is to implement `wl_seat.get_pointer`, `wl_seat.get_keyboard`, `wl_pointer` enter/motion/button/axis events, and `wl_keyboard` keymap/enter/key/modifier events, then map Android touch/mouse/key events into those protocol streams.

## Real-resolution and pointer input milestone - 2026-07-09

This milestone removes the remaining fixed test-buffer sizes from the active emulator proof paths and adds the first end-to-end Wayland pointer event path.

Implementation:

- `MainActivity` now passes the real Android display size to native Linux payloads via `ARCHPHENE_WIDTH` and `ARCHPHENE_HEIGHT`.
- The early frame, shm-frame, raw Wayland, evented Wayland, and Android Wayland API render clients now allocate buffers from those environment dimensions.
- The frame and shm-frame Java bridge servers now accept display-sized buffers up to the same `4096` cap as the Wayland compositor path.
- The Android `libwayland-client` shim now exposes `wl_pointer_interface`, supports `wl_seat.get_pointer`, and dispatches pointer enter/motion/button events.
- The Android xdg API test server now sends a synthetic pointer enter/motion/button sequence after the committed surface is ready.
- The xdg API client now registers a `wl_pointer_listener` and requires pointer enter, motion, and button callbacks before declaring the render lifecycle complete.

Emulator result:

```text
Frame bridge dimensions: 1080x2400
Frame bridge bytes: 10368000
Frame bridge bitmap ready: true
Shm frame bridge dimensions: 1080x2400 stride=4320
Shm frame bridge bytes: 10368000
Shm frame bridge bitmap ready: true
Raw Wayland dimensions: 1080x2400 stride=4320
Evented Wayland dimensions: 1080x2400 stride=4320
XDG Wayland dimensions: 1080x2400 stride=4320
Android Wayland API render dimensions: 1080x2400 stride=4320
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=5 formats=1 output=1080x2400 scale=1 seat_caps=3 seat_named=1 pointer_entered=1 pointer_moved=1 pointer_button=1 frame_done=1 buffer_released=1 cleanup_done=1
Android Wayland API xdg pointer requested: true
Android Wayland API xdg pointer events sent: true
Android Wayland API xdg dimensions: 1080x2400 stride=4320
```

Protocol highlights:

```text
object=8 opcode=0 size=12 wl_seat.get_pointer pointer_id=9
server->client object=9 opcode=0 wl_pointer.enter surface=11 x=540 y=1200
server->client object=9 opcode=2 wl_pointer.motion x=540 y=1200
server->client object=9 opcode=3 wl_pointer.button left pressed
```

Artifacts:

- `artifacts/kcalc-real-resolution-pointer-final.png`
- `artifacts/kcalc-real-resolution-pointer-final.xml`
- `artifacts/kcalc-real-resolution-pointer-final-report.txt`

Meaning:

- The active render bridge tests now use the real emulator display resolution instead of small stretched buffers.
- The bridge has the first proven input callback path for Wayland pointer events. The next step is to replace the synthetic pointer event with real Android `MotionEvent` mapping, then add keyboard keymap/key/modifier support.

## Real Android pointer input and native repaint milestone - 2026-07-09

The Android-built xdg-shell client now has an interactive mode (`ARCHPHENE_INTERACTIVE_POINTER=1`) that waits for real Android input instead of synthetic server-side pointer events. `MainActivity` starts this probe after the UI is visible, forwards `MotionEvent` down/move/up into `wl_pointer.enter`, `wl_pointer.motion`, and `wl_pointer.button`, and the native client repaints the same `wl_shm` buffer after receiving the button press.

Verified in the emulator with `adb shell input tap 540 1200` after install/launch. Evidence is in `artifacts/kcalc-real-android-pointer-v2-report.txt`, `artifacts/kcalc-real-android-pointer-v2.png`, and `artifacts/kcalc-real-android-pointer-v2.xml`.

Key proof lines:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=5 formats=1 output=1080x2400 scale=1 seat_caps=3 seat_named=1 pointer_entered=1 pointer_moved=1 pointer_button=1 pointer_x=540 pointer_y=1200 pointer_dispatches=3 real_pointer_repainted=1 frame_done=1 buffer_released=1 cleanup_done=1
Android Wayland API interactive pointer exit code: 0
Android Wayland API interactive pointer timed out: false
Android Wayland API interactive pointer android events: 2
Android Wayland API interactive pointer bridge motion events: 2
Android Wayland API interactive pointer bridge button events: 2
Android Wayland API interactive pointer native repaint: true
Android Wayland API interactive pointer commits: 2
android->wayland object=9 opcode=0 wl_pointer.enter surface=11 x=540 y=1200
android->wayland object=9 opcode=2 wl_pointer.motion x=540 y=1200
android->wayland object=9 opcode=3 wl_pointer.button left pressed
```

Implementation notes:

- The bridge keeps the existing synthetic pointer lifecycle test as a baseline and adds a separate post-`setContentView` interactive probe.
- The Java compositor now synchronizes outgoing Wayland event writes so UI-thread input can be sent while the server read loop is blocked on native client requests.
- The shm fd reader now seeks to offset 0 and does not close the fd on the first read, which allows a second frame commit from the same shared memory buffer.
- The local bionic `libwayland-client` shim now implements `wl_display_dispatch()` for blocking event delivery outside roundtrip-only flows.

Next milestone: keyboard input. That requires `wl_keyboard` advertisement/bind, a keymap event with fd passing or no-keymap handling, Android `KeyEvent` to Linux evdev keycode mapping, and native repaint/text proof after key delivery.

## Real Android keyboard input and native repaint milestone - 2026-07-09

The Android-built xdg-shell client now binds `wl_keyboard` in addition to `wl_pointer`. The Java Wayland bridge handles `wl_seat.get_keyboard`, sends a bridge-local `WL_KEYBOARD_KEYMAP_FORMAT_NO_KEYMAP` keymap event, sends keyboard focus/modifier events after the xdg surface is committed, maps Android `KeyEvent` values to Linux evdev keycodes, and forwards key press/release events to the native client.

Verified in the emulator with `adb shell input tap 540 1200` followed by `adb shell input keyevent 29` (`KEYCODE_A`, mapped to evdev key `30`). Evidence is in `artifacts/kcalc-real-android-keyboard-v1-report.txt`, `artifacts/kcalc-real-android-keyboard-v1.png`, and `artifacts/kcalc-real-android-keyboard-v1.xml`.

Key proof lines:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=5 formats=1 output=1080x2400 scale=1 seat_caps=3 seat_named=1 pointer_entered=1 pointer_moved=1 pointer_button=1 pointer_x=540 pointer_y=1200 pointer_dispatches=3 real_pointer_repainted=1 keyboard_keymap=1 keyboard_entered=1 keyboard_key=1 keyboard_last_key=30 keyboard_dispatches=1 real_keyboard_repainted=1 frame_done=1 buffer_released=1 cleanup_done=1
Android Wayland API interactive keyboard android events: 2
Android Wayland API interactive keyboard bridge key events: 2
Android Wayland API interactive keyboard native repaint: true
Android Wayland API interactive keyboard last key: 30
android->wayland object=10 opcode=3 wl_keyboard.key key=30 pressed
android->wayland object=10 opcode=3 wl_keyboard.key key=30 released
```

Implementation notes:

- The bridge currently uses no-keymap mode for the local bionic shim, avoiding ancillary fd receive support in this prototype path.
- The keycode map is intentionally small but covers letters, digits, enter, space, backspace, tab, and escape.
- The interactive proof now commits three frames: initial render, pointer repaint, and keyboard repaint.

Next milestone: replace the bridge-local no-keymap shortcut with proper `wl_keyboard.keymap` fd passing and add a text/input-method path suitable for real toolkits.

## Wayland keyboard keymap fd milestone - 2026-07-09

The bridge now sends `wl_keyboard.keymap` using an actual file descriptor instead of the earlier bridge-local no-keymap shortcut. The Java compositor writes a minimal XKB v1 keymap to an app-private temp file, sends it with `LocalSocket.setFileDescriptorsForSend`, and the local bionic Wayland client shim receives ancillary fds with `recvmsg`. The native xdg client verifies that it received a readable fd, that the keymap format is XKB v1, that the keymap has nonzero size, and that the fd content contains `xkb_keymap`.

Verified in the emulator with `adb shell input tap 540 1200` followed by `adb shell input keyevent 29`. Evidence is in `artifacts/kcalc-keymap-fd-v1-report.txt`, `artifacts/kcalc-keymap-fd-v1.png`, and `artifacts/kcalc-keymap-fd-v1.xml`.

Key proof lines:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=5 formats=1 output=1080x2400 scale=1 seat_caps=3 seat_named=1 pointer_entered=1 pointer_moved=1 pointer_button=1 pointer_x=540 pointer_y=1200 pointer_dispatches=3 real_pointer_repainted=1 keyboard_keymap=1 keyboard_keymap_fd=1 keyboard_keymap_format=1 keyboard_keymap_size=550 keyboard_keymap_xkb=1 keyboard_entered=1 keyboard_key=1 keyboard_last_key=30 keyboard_dispatches=1 real_keyboard_repainted=1 frame_done=1 buffer_released=1 cleanup_done=1
server->client object=10 opcode=0 wl_keyboard.keymap xkb_v1 fd size=550
Android Wayland API interactive keyboard native repaint: true
android->wayland object=10 opcode=3 wl_keyboard.key key=30 pressed
android->wayland object=10 opcode=3 wl_keyboard.key key=30 released
```

Implementation notes:

- `archphene_wayland_client_android.c` now receives server-to-client ancillary fds for events, and passes the keymap fd into the `wl_keyboard_listener` callback.
- `MainActivity.RawWaylandShmServer` now sends a real XKB v1 keymap fd through the Android `LocalSocket` API.
- The native proof client validates the fd and XKB marker before accepting the environment roundtrip.

Remaining gap: this is still not full toolkit text input. Real Qt/GTK/Electron paths will need a valid enough keymap for xkbcommon, richer keycode/symbol/modifier handling, and probably text-input/input-method protocols for IME/composition.

## Wayland keyboard modifier state milestone - 2026-07-09

The bridge now tracks Android modifier key transitions and sends `wl_keyboard.modifiers` updates. The local XKB keymap was expanded with Shift, Control, Alt, letter/digit keycodes, modifier maps, and a two-level `a/A` symbol. Android key handling now maps Shift/Ctrl/Alt keycodes to Linux evdev keycodes and maintains a depressed-modifier mask.

Verified in the emulator with `adb shell input tap 540 1200` followed by `adb shell input keyevent 59` (`KEYCODE_SHIFT_LEFT`, mapped to evdev key `42`). Evidence is in `artifacts/kcalc-keyboard-modifiers-v1-report.txt`, `artifacts/kcalc-keyboard-modifiers-v1.png`, and `artifacts/kcalc-keyboard-modifiers-v1.xml`.

Key proof lines:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=5 formats=1 output=1080x2400 scale=1 seat_caps=3 seat_named=1 pointer_entered=1 pointer_moved=1 pointer_button=1 pointer_x=540 pointer_y=1200 pointer_dispatches=3 real_pointer_repainted=1 keyboard_keymap=1 keyboard_keymap_fd=1 keyboard_keymap_format=1 keyboard_keymap_size=1445 keyboard_keymap_xkb=1 keyboard_entered=1 keyboard_key=1 keyboard_last_key=42 keyboard_dispatches=2 keyboard_modifiers=3 keyboard_last_mods=0 keyboard_modifiers_nonzero=1 real_keyboard_repainted=1 frame_done=1 buffer_released=1 cleanup_done=1
Android Wayland API interactive keyboard modifier events: 2
android->wayland object=10 opcode=3 wl_keyboard.key key=42 pressed
android->wayland object=10 opcode=4 wl_keyboard.modifiers depressed=1
android->wayland object=10 opcode=3 wl_keyboard.key key=42 released
android->wayland object=10 opcode=4 wl_keyboard.modifiers depressed=0
```

Implementation notes:

- Modifier updates are now sent on modifier key down/up, with Shift=1, Control=4, and Alt/Mod1=8 in the bridge mask.
- The proof requires `keyboard_modifiers_nonzero=1`, so a plain key press no longer satisfies the interactive keyboard test by itself.
- The final `keyboard_last_mods=0` is expected for this test because Android sends Shift release before the client exits; the nonzero proof is preserved separately.

Next milestone: key repeat and text composition. Real desktop toolkits need repeat info/rate behavior plus text-input/input-method protocol support for Android IME and composed characters.

## Wayland keyboard repeat info milestone - 2026-07-09

The bridge now sends `wl_keyboard.repeat_info` after the XKB keymap fd and before keyboard focus. The Android Wayland server reports a repeat rate of 25 keys/sec and a 400 ms delay, and the bionic Wayland client shim dispatches opcode 5 into the native `wl_keyboard_listener`.

Verified in the emulator with `adb shell input tap 540 1200` followed by `adb shell input keyevent 59` (`KEYCODE_SHIFT_LEFT`, mapped to evdev key `42`). Evidence is in `artifacts/kcalc-keyboard-repeat-v1-report.txt`, `artifacts/kcalc-keyboard-repeat-v1.png`, and `artifacts/kcalc-keyboard-repeat-v1.xml`.

Key proof lines:

```text
wayland-client API xdg committed 1080x2400 stride=4320 bytes=10368000 serial=42 globals=5 formats=1 output=1080x2400 scale=1 seat_caps=3 seat_named=1 pointer_entered=1 pointer_moved=1 pointer_button=1 pointer_x=540 pointer_y=1200 pointer_dispatches=3 real_pointer_repainted=1 keyboard_keymap=1 keyboard_keymap_fd=1 keyboard_keymap_format=1 keyboard_keymap_size=1445 keyboard_keymap_xkb=1 keyboard_entered=1 keyboard_key=1 keyboard_last_key=42 keyboard_dispatches=2 keyboard_modifiers=3 keyboard_last_mods=0 keyboard_modifiers_nonzero=1 keyboard_repeat_info=1 keyboard_repeat_rate=25 keyboard_repeat_delay=400 real_keyboard_repainted=1 frame_done=1 buffer_released=1 cleanup_done=1
server->client object=10 opcode=5 wl_keyboard.repeat_info rate=25 delay=400
Android Wayland API interactive keyboard repeat info sent: true rate=25 delay=400
Android Wayland API interactive keyboard native repaint: true
```

Implementation notes:

- `MainActivity.RawWaylandShmServer` now emits repeat info as part of keyboard setup, so native clients receive keymap, repeat policy, focus, and modifier state in the normal Wayland order.
- `archphene_wayland_client_android.c` dispatches `wl_keyboard.repeat_info` to listener slot 5.
- `archphene_wayland_api_xdg_client.c` now requires repeat info during the environment roundtrip before accepting the bridge as toolkit-ready enough for keyboard handling.

Next milestone: text input and composition. The remaining gap is translating Android IME/editor actions into Wayland text-input/input-method protocol events instead of only forwarding raw keycodes.

## Clean Android surface and live frame milestone - 2026-07-09

The launcher no longer renders its diagnostic report over the application. Full diagnostics are written to the app-private `kcalc-report.txt` file and emitted under the `ArchpheneKCalc` logcat tag. Normal launch also skips the historical probe suite and starts the interactive Wayland path directly.

The compositor now presents every committed `wl_shm` frame to Android immediately instead of waiting for the native process to exit. The launch is laid out before starting the client, and the measured Android viewport is reused for the Wayland environment and xdg configuration. Android IME creation is gated until a future Wayland text-input request, so pointer focus does not cover the application with a keyboard.

Verified emulator artifacts:

- `artifacts/kcalc-live-frame-v1.png`: first Wayland frame visible within three seconds.
- `artifacts/kcalc-clean-surface-v3.png`: pointer and raw keyboard repaint visible with no diagnostic overlay or IME.
- `artifacts/kcalc-clean-surface-v3-report.txt`: two pointer events, two keyboard events, two commits, and the final 540x1200 pointer coordinates.
- `artifacts/kcalc-clean-timeout-v1-report.txt`: process timeout is reported as exit -1 / timed out true without being converted into a fake -127 launch failure.

Current boundary: the visible calculator-shaped frame is still produced by the native xdg bridge proof client, not the Arch Linux KCalc Qt executable. The next milestone is loading the packaged Qt/KDE runtime and making the real `kcalc` process connect to this compositor.