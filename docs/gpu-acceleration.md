# GPU acceleration

Archphene accelerates Linux OpenGL ES applications without a VM, root access, or Android OS changes.

## Data path

```text
glibc Linux application
  -> Mesa virpipe Gallium driver
  -> private same-UID Unix socket
  -> Bionic virglrenderer helper
  -> Android EGL / OpenGL ES driver
  -> wl_shm frame
  -> Archphene Wayland compositor
  -> Android Activity
```

The Rust/Kotlin manager includes an exact-ABI native helper built from pinned
virglrenderer 1.3.0 and libepoxy 1.5.10 sources. The helper runs in the
manager's ordinary Android app domain, creates its socket in a manager-private
cache directory, and gains no Android permissions. Other Android UIDs cannot
reach the socket through the private parent directory.

Launcher authorization A2 carries the registry-derived integration topology.
Only a current descriptor with verified OpenGL/EGL topology can request the
bridge. The manager waits for the helper socket and sends its bounded path
through launch protocol G5; Rust reauthorizes the descriptor, verifies that
the path is an actual Unix socket, and selects Mesa `virpipe` through
`GALLIUM_DRIVER` and `VTEST_SOCKET_NAME`. Startup failure selects `llvmpipe`.
The session owns and reaps the helper and deletes its socket directory on
close. Manager startup also performs a bounded, non-recursive cleanup of only
exactly named private GPU runtime directories so process death cannot
accumulate sockets. Replacement-helper recovery from the retained Java bridge
has not yet been ported to the new manager.

## Android compatibility patches

The build applies narrowly scoped patches for Android NDK compilation and Android EGL:

- use Android EGL/OpenGL ES libraries instead of desktop GL/GBM;
- create a 1x1 pbuffer when the EGL implementation cannot provide a surfaceless context;
- disable unsupported dual-source blending;
- avoid deleting a shader object already invalidated by an Android GL translator;
- do not advertise native-fence FD export on the private pbuffer/vtest transport.

Source archives are downloaded from their upstream release URLs and verified
against pinned SHA-256 hashes before extraction. Release builds cross-compile
the Rust Wayland compositor and both x86_64 and AArch64 helpers in the Linux
Android-NDK container and stage only the selected ABI into an exact-device APK.

## Validation

The retained Java bridge was validated on the Android 16 x86_64 emulator and
physical AArch64 Samsung with an unmodified GLMark2 package. That evidence
reported:

- `GL_RENDERER: virgl (Android Emulator OpenGL ES Translator (NVIDIA ...))`;
- `GL_VERSION: OpenGL ES 3.0 Mesa 26.1.4-arch1.1`;
- `Surface Size: 1080x2205 windowed`;
- completion of every default GLMark2 scene with final score 12.

The old helper remained alive through repeated scene transitions, and bounded
replacement-helper recovery passed on both devices. This evidence is retained
as the migration target; it is not yet a support claim for the Rust/Kotlin
manager. The new manager currently passes dual-ABI packaging, authorization,
real-socket validation, helper readiness, process ownership, and software
fallback gates. A current shared-root Qt 5 launcher starts the exact-ABI helper,
connects, presents readable frames, and cleans up after close on both devices;
full-device screenshots and scoped fatal logs were inspected. Deliberately
seeded stale runtime directories are recovered after manager process death on
both devices. A current shared-root OpenGL test client still needs to repeat
the renderer, scene, helper-loss replacement, and llvmpipe tests.

## Current limits

GPU commands can execute on Android's GLES driver, but the final Linux window
is currently copied through `wl_shm`. This is not zero-copy and caps benchmark
throughput. Chromium 42 still starts its GPU process with
`--use-gl=disabled`, even with a live virgl helper; direct EGL, ANGLE, and
blocklist test profiles did not make it map a GL library. Those unproven flags
are not shipped. Chromium acceleration therefore remains gated on a compatible
Wayland GPU presentation/readback path rather than an app-specific override.
The next presentation milestone is Android `AHardwareBuffer`/dmabuf import
with explicit synchronization and SHM fallback. No Venus or Android-host
Vulkan transport is published yet, so accelerated Vulkan and Wayland Vulkan
presentation remain incomplete. Production power/thermal measurements and
broad physical-device testing are also incomplete.

References:

- [Android graphics architecture](https://source.android.com/docs/core/graphics/architecture)
- [Android NDK AHardwareBuffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
- [Mesa EGL documentation](https://docs.mesa3d.org/egl.html)
- [Wayland architecture](https://wayland.freedesktop.org/docs/html/ch02.html)
- [AOSP virglrenderer](https://android.googlesource.com/platform/external/virglrenderer/)
