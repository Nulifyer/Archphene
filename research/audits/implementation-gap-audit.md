# Archphene Bridge Implementation Gap Audit

Date: 2026-07-12

## Scope and current proof

The prototype proves that an unmodified Arch Linux GUI executable can run under a glibc runtime inside a normal Android app UID, connect to an app-local Wayland socket, render through Android, and receive pointer, keyboard, IME, clipboard, popup, resize, and limited document-broker events. KCalc runs on an ARM64 Samsung device; Mousepad runs on the x86_64 emulator. This does not prove GrapheneOS compatibility, production isolation, generic package conversion, or equivalence to a mature Wayland compositor.

Rotation is now covered on both compositor forks. KCalc has an automated physical-device regression (`scripts/test-kcalc-live-resize.ps1`); Mousepad was manually verified on the emulator with one stable process across portrait, landscape, and portrait return. KCalc also has a settled descriptor regression (`scripts/test-kcalc-fd-lifecycle.ps1`).

## P0: blockers before broad app support

### Replace the duplicated Java compositor

KCalc and Mousepad carry separate, multi-thousand-line manual Wayland implementations. Mousepad has decompiler artifacts and unchecked generic operations. The forks already drifted: KCalc received resize/configure and SHM lifecycle fixes that had to be ported separately to Mousepad. This is the largest correctness and security risk.

Build one shared native compositor service/library using `libwayland-server` or Smithay and generated protocol bindings. Keep Android policy, Activity/window mapping, IME, permissions, and document brokering in a narrow Java/Kotlin API. Do not add more application-specific protocol behavior to either fork.

Required protocol baseline:

- object type, version, opcode, payload-size, and lifecycle validation
- `wl_surface` pending/current state, damage, buffer scale/transform, frame callbacks
- xdg configure/ack sequencing and role enforcement
- popup grab stack, constraints, reposition, dismissal, and nested popups
- subsurface synchronized/desynchronized commit trees
- seat focus, pointer, keyboard, touch, data-device, and text-input-v3
- output changes, density, rotation, multi-window, and fractional scaling
- deterministic protocol errors instead of silently accepting unknown requests

The current parser caps every message at 4096 bytes despite the 16-bit Wayland wire size, ignores multiple unknown requests, and terminates the whole client connection on broad exceptions. Those are prototype shortcuts, not a stable compatibility boundary.

### Define package and signing trust

The manager is not yet a package converter. It can inspect installed wrappers and exercise an APK update transaction, but it does not resolve an Arch dependency closure, verify repository signatures, generate a wrapper, or sign an installable APK on-device.

Android updates require signer continuity. A production design must choose one trust model:

1. reproducible, server-built and repository-signed wrapper APKs;
2. per-user Android Keystore signing with explicit backup/recovery consequences; or
3. an OS-integrated installer/signing authority, which violates the no-OS-edit constraint.

Do not embed a universal APK signing private key in the manager. Update verification must support signing-certificate rotation and signed metadata, not only the current signer and a hash supplied through an Intent. The exported manager Activity also accepts test update extras and must be split into a non-exported test component or debug-only manifest.

Manifest validation and canonical payload containment are now enforced for the sample LAPK path, but arbitrary archives still need size limits, decompression-bomb limits, symlink/hardlink rejection, ownership/mode policy, package-version ordering, and atomic staging.

### Make wrapper permissions real policy

Descriptor `androidPermissions` and capability values are metadata today. A generator must map them to a generated Android manifest and runtime permission requests. Linux syscalls cannot trigger an Android permission dialog. File, camera, microphone, notification, location, USB, and network access need explicit broker APIs or wrapper policy.

Production wrappers and the manager must not be `android:debuggable="true"`. Current debug builds intentionally require `run-as` for tests.

### Establish a production runtime model

Each wrapper currently deletes and re-extracts its full native runtime closure into writable app-private storage on every launch. This is slow, non-atomic, duplicates roughly 100-175 MB per app before user data, and runs a mutable copy instead of package-verified native content.

Use `nativeLibraryDir` directly where possible. Materialize only layouts that the dynamic loader/toolkit requires, keyed by APK version and content hash, into a new directory; fsync and atomically rename it; then mark files read-only. Shared glibc across independently sandboxed Android UIDs is not possible as an ordinary writable path. Sharing requires immutable runtime APKs loaded through supported Android mechanisms, a broker process (which changes isolation), or OS support.

Validate every bundled ELF for Android 16 KB page-size compatibility and execute the upstream glibc test suite under each supported Android kernel/seccomp profile. Current success on one Samsung device does not validate GrapheneOS Pixels or future Android kernels.

## P1: required user workflows

### Documents and shared home

The current document session imports one `content://` URI into a private file and writes it back when `FileObserver` reports a save. It uses `lastModified` as its dirty token, starts one thread per event, has no external-change conflict detection, and assumes provider support for output mode `rwt`. Sync failure is log-only.

Replace this with a bounded serial executor and a document table containing URI, persisted-grant state, content fingerprint/version, local path, dirty state, and last error. Use provider-capability fallbacks, detect conflicts, and surface failures in Android UI. Support multiple open documents.

A per-wrapper `DocumentsProvider` exposes each app's Linux home to Android but does not create one shared POSIX home across app UIDs. The safe no-OS-edit model is a manager-owned document provider plus explicit per-app URI/tree grants. Private app state remains in each wrapper sandbox; user-visible documents live behind SAF and can be attached in Discord or opened from Downloads. A transparent shared POSIX path would require a FUSE/portal layer and careful revocation semantics.

### Process and lifecycle control

`Process.destroy()` only targets the direct Linux process. Launch each app in a tracked process group, terminate descendants predictably, close compositor sockets, and reap children. Define behavior for Activity stop, task removal, configuration changes, Android process death, and legitimate background work. Background execution must use Android foreground-service/WorkManager rules rather than attempting to bypass them.

### Android window mapping

Popup surfaces should remain compositor-managed transient surfaces. Secondary xdg toplevels such as preferences and file dialogs need a policy:

- phone/tablet: modal child surface in the same Activity with correct focus, back handling, and IME insets;
- desktop/freeform mode: separate Android window/task where platform support permits it.

The current Mousepad child-toplevel logic is app-shaped and title-based. Replace it with role/parent metadata and a general window registry.

### Rendering performance

The bridge currently copies SHM into Java arrays and Bitmaps and recomposes full frames. At phone desktop resolutions this creates large transient allocations and scales poorly for editors, browsers, video, Blender, or GIMP. Frame callbacks are not synchronized to Android presentation.

Move composition to native code, schedule presentation from `Choreographer`, honor damage, and add a zero/low-copy path using HardwareBuffer/dmabuf where platform policy permits it. Keep SHM as a compatibility fallback.

## P2: compatibility breadth

- complete clipboard MIME types, drag-and-drop, primary selection, cursor shapes, relative pointer, pointer constraints, tablets, and accessibility
- locale, fonts, themes, portals, notifications, audio, printing, secrets/keyrings, and URL opening
- GPU/EGL/Vulkan translation and per-driver validation
- audio through an Android-aware PipeWire/Pulse broker
- reproducible Arch and Arch Linux ARM closure resolution with repository-key verification
- crash reporting, per-app resource limits, storage quotas, rollback, SBOMs, and vulnerability/update status
- x86_64 and AArch64 CI plus 4 KB and 16 KB Android page-size devices

## Recommended milestone order

1. Freeze feature growth in the Java compositor and extract a shared native protocol core.
2. Re-run KCalc and Mousepad interaction, rotation, popup, dialog, IME, clipboard, and FD tests against that core.
3. Implement atomic, cached, 16 KB-safe runtime packaging and process-group lifecycle control.
4. Implement the multi-document SAF broker and manager-owned shared user-document provider.
5. Define APK signing/update trust, then build a host-side package resolver/generator before considering on-device conversion.
6. Add one demanding GTK app and one demanding Qt app only after the shared bridge passes the baseline suite.

## Security boundary statement

The app bridge can preserve Android per-app UIDs and Android permission brokers, but it cannot reproduce GrapheneOS security updates or hardware/kernel hardening on unsupported devices. A Samsung test validates the stock Android app sandbox on that Samsung. A generic x86 Android/Arch hybrid is a separate OS port with different verified-boot, firmware, driver, SELinux, update, and exploit-mitigation properties.

## Primary references

- Wayland xdg-shell protocol: https://wayland.app/protocols/xdg-shell
- Android 16 KB page-size guidance: https://source.android.com/docs/core/architecture/16kb-page-size/16kb
- Android signing certificate history: https://developer.android.com/reference/android/content/pm/PackageInfo.html
- Android verified signing information: https://developer.android.com/reference/android/content/pm/PackageManager.html
- Existing compositor comparison: [`wayland-compositor-reference-review.md`](../references/wayland-compositor-reference-review.md)