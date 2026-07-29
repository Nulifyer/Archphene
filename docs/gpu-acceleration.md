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
`GALLIUM_DRIVER` and `VTEST_SOCKET_NAME`. Startup failure selects `llvmpipe`
with Mesa's explicit software-rendering switch.
The session owns and reaps the helper and deletes its socket directory on
close. Manager startup also performs a bounded, non-recursive cleanup of only
exactly named private GPU runtime directories so process death cannot
accumulate sockets. The existing process poll detects an unexpected helper
exit without another monitor thread: it restarts the Linux client once with a
replacement helper, then restarts once with llvmpipe if that helper also exits.

Static profiling follows bounded ELF `DT_NEEDED` graphs. Some applications,
including GLMark2, load EGL/GLES with `dlopen` instead. For executables no
larger than 8 MiB, the profiler also streams the file through fixed buffers and
recognizes only exact NUL-terminated library SONAMEs; it neither allocates a
file-sized buffer nor treats prose or partial names as capabilities. A shared
32 MiB catalog budget bounds total hint I/O. The existing bounded process-map
observation remains the fallback for larger executables, exhausted scan
budgets, and names constructed at runtime.

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

The current Rust/Kotlin manager was validated on the Android 16 x86_64
emulator and physical AArch64 Samsung with the official unmodified Arch
GLMark2 package. A clean generated descriptor identifies GLMark2's literal
`dlopen` EGL/GLES names and starts virgl on the first launch, without a
learn-once software run.

- The emulator reports the Android Emulator OpenGL ES translator through
  virgl, OpenGL ES 3.0 / Mesa 26.1.5, 32 logged default scene variants, score
  14, and exit 0.
- Samsung reports virgl on Adreno 730, OpenGL ES 3.2 / Mesa 26.1.5, 33 logged
  default scene variants, score 12, and exit 0.
- Both runs retain the same Android host, fit the 432-dp Wayland output to the
  complete app Surface, and pass two distinct nonblank full-device frames,
  bounded geometry, software-fallback rejection, and scoped fatal-log checks.

The manager also passes dual-ABI packaging, authorization, real-socket
validation, helper readiness, process ownership, and software fallback gates.
Deliberately seeded stale runtime directories are recovered after manager
process death on both devices. Killing the helper twice under a current Qt 5
launcher proves one replacement-helper reconnect followed by an explicit
llvmpipe reconnect; both stages publish fresh frames on the emulator and
Samsung.

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
