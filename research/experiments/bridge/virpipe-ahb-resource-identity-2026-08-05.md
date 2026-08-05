# Virpipe AHB resource identity audit

Date: 2026-08-05

## Question

Can the current Arch Mesa virpipe client and pinned virglrenderer 1.3.0 vtest
helper submit one host-allocated `AHardwareBuffer` to the manager compositor
without GPU readback or a heuristic frame match?

## Finding

Not with the current contracts. Vtest protocol version 4 creates virgl
resources and returns SHM or exported file descriptors, but it has no Android
AHB presentation command. More importantly, the helper's virgl resource ID does
not survive Mesa's current display-target transfer into the Wayland `wl_shm`
buffer committed to the manager compositor. The compositor therefore cannot
prove that a side-channel AHB corresponds to one exact Wayland surface commit.

A helper-only “latest frame” side channel is unsafe. Transfer completion and
Wayland commit ordering can race, and a session can contain multiple ordinary
surfaces, popups, cursors, resize generations, and independent Android windows.
Choosing the latest helper buffer would permit stale or cross-surface content
without an authenticated resource-to-commit identity.

## Evidence

- `vtest/vtest_protocol.h` in virglrenderer 1.3.0 defines protocol version 4
  through `VCMD_RESOURCE_EXPORT_FD`; it defines no AHB or present command.
- `vtest/vtest_renderer.c` creates a virgl resource, optionally attaches SHM,
  and services transfer-get requests by resource ID.
- Archphene passes only `GALLIUM_DRIVER=virpipe` and `VTEST_SOCKET_NAME` to the
  unmodified Linux application runtime.
- The manager compositor receives standard Wayland `wl_shm` buffers. No vtest
  resource ID or authenticated present token accompanies those commits.
- The current Samsung exposes `EGL_ANDROID_image_native_buffer` but not
  `EGL_EXT_image_dma_buf_import`, so a Linux dmabuf shortcut is not available on
  that maintained device.

## Required experiment

The next implementation must propagate one bounded identity end to end:

1. A versioned generic Mesa virpipe winsys extension selects an eligible
   presentable resource and negotiates support with the private vtest helper.
2. The helper allocates or imports at most three bounded AHB resources, binds
   each to the matching virgl render target, and reports the scoped resource ID
   and production fence through the fixed `APHB` side channel.
3. A private Wayland buffer extension carries the same authenticated resource
   identity on the exact surface commit. Unsupported Mesa falls back to
   `wl_shm` without changing application behavior.
4. The manager accepts the AHB only when session, helper generation, token,
   resource ID, dimensions, slot, and fence sequence all match. SurfaceFlinger
   release must return a fence before helper reuse.
5. GLMark2 and SuperTux must prove exact visuals, resize, popup composition,
   helper replacement, and software fallback without `TRANSFER_GET`,
   `glReadPixels`, or CPU frame copies in the accepted path.

This requires a reviewed generic Mesa runtime component, not an
application-specific source patch. Until that experiment succeeds, the current
virpipe-to-SHM path and explicit non-zero-copy claim remain authoritative.
