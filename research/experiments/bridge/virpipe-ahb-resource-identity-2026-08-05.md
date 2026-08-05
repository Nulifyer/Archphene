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

## 2026-08-05 manager contract update

The manager-side protocol and state transition are now fixed but not advertised.
`org_archphene_gpu_surface_v1.set_resource` carries the helper generation,
resource ID, and split 64-bit fence sequence into one exact surface commit. The
bounded state machine enforces unique surfaces, three known resources, monotonic
fences, helper replacement, resource release, standard-buffer replacement, and
damage-only retention. This closes the manager contract ambiguity; Mesa sender,
vtest Resource/Present transport, fence consumption, and release return remain required before
production can enable the global. Generated Rust bindings and an opt-in
client/server socket test now prove exact set/commit and clear/commit latching.
The test no longer inserts identity resources directly: it authenticates Hello,
Resource, and a 64-bit Present frame through the APHB registry, atomically
coordinates both bounded registries, and then submits the matching Wayland
claim. Generic Mesa/Wayland identity production remains the next external
sender boundary.

The Android vtest helper now has a dormant strict APHB Hello and AHB resource sender. It accepts
the present socket, session ID, helper generation, and 128-bit token only as one
complete configuration and sends Hello before exposing vtest. Both ABI builds
pass, and a same-UID Samsung listener received the exact 64-byte frame. Current
manager launches intentionally omit the arguments until the complete sender and
release path exists. Private vtest command 39 allocates one of three bounded
RGBA AHBs, imports its EGL image as the exact virgl resource, and sends Resource
plus its native handle. Command 40 conservatively completes rendering, sends
Present with a marker-only acquire state, and returns the same 64-bit sequence
to the client. Both ABIs build. A same-UID Samsung probe observed
`resource=1 stride=64 sequence=1 marker=46`. Private command 41 blocks for the
exact scoped manager Release, accepts marker `52` with zero or one close-on-exec
fence, waits a received fence, and permits reuse only after sequence equality.
Malformed framing or descriptor cardinality closes the channel. The Samsung
probe completed sequences 1 and 2 around marker-only Release (`46,52,46`).
The generic Mesa/Wayland sender is now implemented as a pinned Mesa 26.1.5
patch. Eligible 8-bit RGBA/BGRA display targets use private commands 39 and 40,
send the returned exact identity through `set_resource` before the same surface
commit, then wait on command 41 before `eglSwapBuffers` returns. The path
requires explicit activation, a valid helper generation, and the private
Wayland global; otherwise Mesa retains `wl_shm`. Reproducible x86_64 and AArch64
builds pass. Production
and the manager's SurfaceFlinger-backed Release sender remain disabled because
the custom Mesa install tree is not staged and received helper AHBs are not yet
submitted to Android presentation.

The manager now has the transport half of Release sending. A three-entry queue
admits only an exact registry-valid sequence, writes the full scoped frame before
marker `52`, and optionally transfers one RAII-owned fence FD. Partial
nonblocking writes resume in order, and disconnect drops all queued ownership.
Host tests cover marker-only and fenced forms. Connecting actual SurfaceFlinger
callback results remains open.

The compositor now has a dormant same-UID fixed-frame listener. It bounds path
length and frames per dispatch, validates `SO_PEERCRED`, safely reassembles
fragments, drops partial state on helper replacement, and preserves a socket
that replaced its own inode. Scoped Hello passes end to end in a core test. The
Android endpoint now consumes the immediately following AHB native handle,
retries would-block before reading another frame, validates its exact bounded
metadata and stride-backed byte declaration, and retains it until idle
DropResource. Resource and DropResource commit native-handle and identity state
transactionally. A live API 35 Samsung nonblocking socket probe accepted a valid
64×32 AHB and atomically rejected a mismatched declaration. Present now consumes
one marker byte carrying zero or one close-on-exec `SCM_RIGHTS` acquire-fence
descriptor. Marker-only means the helper conservatively completed rendering
before send. The endpoint retains one explicit acquire state per resource and
retries would-block before reading another frame. Host tests cover both forms
and reject a wrong marker; the Samsung probe exercises the descriptor form.
Fence consumption and release return remain open.

APHB now distinguishes per-frame release from resource destruction. Present
allows one outstanding sequence per resource. The manager must return Release
with that exact sequence and a SurfaceFlinger fence before reuse; DropResource
is helper-to-manager and idle-only. Declared allocation bytes may include real
AHB stride padding. This removes the earlier ambiguity where Release destroyed
the registry entry instead of returning buffer ownership.
