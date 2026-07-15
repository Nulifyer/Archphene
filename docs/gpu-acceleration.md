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

The generated wrapper includes a native helper built from pinned virglrenderer 1.3.0 and libepoxy 1.5.10 sources. The helper runs in the wrapper's ordinary Android app domain, creates its socket in the app-private cache directory, and gains no Android permissions. Other Android UIDs cannot reach the socket through the private parent directory.

At launch the bridge waits for the helper socket. A ready helper selects Mesa `virpipe` through `GALLIUM_DRIVER` and `VTEST_SOCKET_NAME`; startup failure selects `llvmpipe`. This fallback is decided before the Linux GUI starts. Mid-session helper-loss recovery is not yet automatic.

## Android compatibility patches

The build applies narrowly scoped patches for Android NDK compilation and Android EGL:

- use Android EGL/OpenGL ES libraries instead of desktop GL/GBM;
- create a 1x1 pbuffer when the EGL implementation cannot provide a surfaceless context;
- disable unsupported dual-source blending;
- avoid deleting a shader object already invalidated by an Android GL translator;
- do not advertise native-fence FD export on the private pbuffer/vtest transport.

Source archives are downloaded from their upstream release URLs and verified against pinned SHA-256 hashes before extraction. Release CI cross-compiles both the Rust Wayland compositor and the x86_64 GPU helper in the Linux Android-NDK container.

## Validation

The Android 16 x86_64 emulator uses host GPU acceleration backed by an NVIDIA GeForce RTX 5080. A GLMark2 package found through Arch repository file metadata was resolved, verified, wrapped, signed, installed, and repaired by the manager. The installed generic wrapper reported:

- `GL_RENDERER: virgl (Android Emulator OpenGL ES Translator (NVIDIA ...))`;
- `GL_VERSION: OpenGL ES 3.0 Mesa 26.1.4-arch1.1`;
- `Surface Size: 1080x2205 windowed`;
- completion of every default GLMark2 scene with final score 12.

The release helper remained alive through repeated scene transitions with no fence-export, context-loss, dispatch, or disconnect errors. The aarch64 helper also cross-compiles and creates its private socket under the Archphene app UID on a Samsung Galaxy S22 Ultra whose system renderer is Qualcomm Adreno 730 / OpenGL ES 3.2. A complete ARM GLMark2 run still requires aarch64 manager/runtime-pack generation.

## Current limits

GPU commands execute on Android's GLES driver, but the final Linux window is currently copied through `wl_shm`. This is not zero-copy and caps benchmark throughput. The next presentation milestone is Android `AHardwareBuffer`/dmabuf import with explicit synchronization and SHM fallback. Vulkan/venus, helper restart after a mid-session failure, production power/thermal measurements, and broad physical-device testing are not complete.

References:

- [Android graphics architecture](https://source.android.com/docs/core/graphics/architecture)
- [Android NDK AHardwareBuffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
- [Mesa EGL documentation](https://docs.mesa3d.org/egl.html)
- [Wayland architecture](https://wayland.freedesktop.org/docs/html/ch02.html)
- [AOSP virglrenderer](https://android.googlesource.com/platform/external/virglrenderer/)