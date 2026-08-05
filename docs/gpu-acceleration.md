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
  -> bounded damaged-region texture upload
  -> EGL/GLES composition into an AHardwareBuffer output slot
  -> SurfaceFlinger / Android Activity
```

The Rust/Kotlin manager includes an exact-ABI native helper built from pinned
virglrenderer 1.3.0 and libepoxy 1.5.10 sources. The helper runs in the
manager's ordinary Android app domain, creates its socket in a manager-private
cache directory, and gains no Android permissions. Other Android UIDs cannot
reach the socket through the private parent directory.

App-shell authorization A3 carries the registry-derived integration topology
alongside the signed app-shell MIME declaration.
Only a current descriptor with verified OpenGL/EGL topology can request the
bridge. The manager waits for the helper socket and sends its bounded path
through launch protocol G6; Rust reauthorizes the descriptor, verifies that
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

On devices with `EGL_ANDROID_image_native_buffer` and `GL_OES_EGL_image`, the
compositor imports its existing bounded three-slot AHB ring as retained EGL
images. One retained RGBA texture receives only the admitted SHM damage
rectangle, then a fixed GLES 2 shader composes the complete output into the
selected slot. Resize generations remain bounded, retired images are destroyed
before their AHBs, and `glFinish` conservatively completes rendering before the
existing SurfaceControl handoff. Initialization, import, shader, framebuffer,
or draw failure permanently selects the existing CPU-locked conversion path for
that Android window.

The fixed presentation snapshot also exposes cumulative counters for SHM
snapshot, CPU conversion, GPU readback, texture upload, GPU composition, direct
`AHardwareBuffer` submission, and SurfaceFlinger release. Unimplemented GPU
stages report explicit zeroes. These diagnostics describe the actual path and
do not classify either the SHM upload or conservative GPU finish as zero-copy.

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
app shell proves one replacement-helper reconnect followed by an explicit
llvmpipe reconnect; both stages publish fresh frames on the emulator and
Samsung.

An isolated unmodified Mousepad Quick launch on the physical Samsung validated
the EGL/GLES path without changing retained app shells. Four presented frames
reported four SHM snapshots, four damaged-region texture uploads, four GPU
compositions, four direct AHB submissions, two completed asynchronous
SurfaceFlinger releases, and zero CPU conversions or GPU readbacks. Hardware
text input visibly updated the retained texture, portrait/landscape recreation
returned to a correctly oriented full-device frame, and Back closed the session
cleanly. The preceding CPU-path baseline reported the inverse diagnostic split
and remains the mandatory fallback evidence.

The GPU hot-path proof is deliberately narrower than whole-process allocation
accounting. Its source contract rejects `AHardwareBuffer_lock`, `glReadPixels`,
heap-building primitives in `GpuRenderer::render`, and managed frame-buffer
allocation or copy primitives in Kotlin dispatch. A warmed test stages the same
damaged rectangle 1,000 times with zero measured Rust allocations. During the
physical run, 1,688 compositor dispatches reported zero JNI array-copy and
Kotlin-copy bytes while diagnostics retained GPU upload/composition and zero CPU
conversion/readback. ART recorded unrelated bounded service and debug-probe
objects, so Archphene does not claim that the complete Android process is
allocation-free.

## Current limits

GPU commands can execute on Android's GLES driver, but virpipe output still
returns to the compositor through `wl_shm`. The current output renderer then
performs a CPU channel conversion for the damaged rectangle before one texture
upload; it does not read GPU output back or CPU-lock the destination AHB. This
is not zero-copy and does not yet connect virgl resources directly to the
output ring. Current Electron 42 on Samsung sees `/dev/dri/renderD128`, but
Android SELinux denies the ordinary app domain access; Chromium consequently
fails render-node/GBM initialization, crashes its GPU process three times, and
restarts it with `--use-gl=disabled`. The emulator exposes no `/dev/dri`.
A live virgl helper plus direct EGL, ANGLE, blocklist, and single-process
diagnostic profiles did not produce a safe accelerated path. Those temporary
profiles were removed. Chromium acceleration therefore remains gated on both a
safe userspace render-node/GBM strategy and compatible Wayland GPU
presentation/readback rather than an app-specific override.
The next presentation milestone is a host-allocated virpipe resource that can
enter the EGL/AHB path without returning through SHM, followed by native-fence
production instead of the current conservative `glFinish`. The 2026-08-04
Samsung `SM_S908U` SurfaceFlinger probe exposes
`EGL_ANDROID_image_native_buffer` and `GL_OES_EGL_image`, but not
`EGL_EXT_image_dma_buf_import`. Native-buffer image import is therefore a
viable Android composition boundary on that device; direct client-dmabuf import
must be capability-negotiated and cannot replace SHM there. No Venus or
Android-host Vulkan transport is published yet, so accelerated Vulkan and
Wayland Vulkan presentation remain incomplete. Production power/thermal
measurements and broad physical-device testing are also incomplete.

The manager-side protocol groundwork is implemented but not yet connected to
the helper. It defines one fixed 64-byte `APHB` v1 side-channel frame carrying
the exact session ID, helper generation, and 128-bit session token on every
message. A three-resource registry bounds dimensions, pixels, estimated bytes,
slot identity, duplicate resources, and strictly increasing fence sequences;
release permits bounded slot reuse. Cross-session, stale-generation,
wrong-token, malformed/trailing-field, oversized-resource, unknown-resource,
duplicate-resource, and stale-fence cases fail closed in unit tests. Actual AHB
handle transfer, vtest scanout-resource binding, and helper-to-compositor
presentation remain open, so this contract alone does not remove virpipe SHM
readback.

References:

- [Android graphics architecture](https://source.android.com/docs/core/graphics/architecture)
- [Android NDK AHardwareBuffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
- [Mesa EGL documentation](https://docs.mesa3d.org/egl.html)
- [Wayland architecture](https://wayland.freedesktop.org/docs/html/ch02.html)
- [AOSP virglrenderer](https://android.googlesource.com/platform/external/virglrenderer/)
