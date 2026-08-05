# Mesa virpipe sender patch

This component patches Mesa 26.1.5 so an explicitly enabled generic Wayland
virpipe client can preserve one host resource identity through the exact
`wl_surface.commit` that presents it.

The patch adds three bounded operations:

- It creates eligible 8-bit RGBA/BGRA display targets with private vtest
  command `39` instead of a shared-memory backing store.
- It obtains the exact 64-bit Present sequence through command `40` and sends
  that identity through `org_archphene_gpu_surface_v1.set_resource` before the
  same surface commit.
- It waits for command `41` before the same resource is reused or destroyed.
  Other ring resources may render later frames while SurfaceFlinger owns the
  submitted resource.

The path activates only when `ARCHPHENE_AHB_PRESENT=1`,
`ARCHPHENE_GPU_HELPER_GENERATION` is a valid nonzero 32-bit integer, and the
Wayland compositor advertises the private global. Otherwise, Mesa keeps its
existing `wl_shm` path.

Build both native Linux architectures with:

```sh
./scripts/build-mesa-virpipe-sender-podman.sh x86_64
./scripts/build-mesa-virpipe-sender-podman.sh aarch64
```

The build outputs an install tree under
`tooling/build/mesa-virpipe-sender/<architecture>/`. The package-runtime staging
step installs this tree for both ABIs. Production omits
`ARCHPHENE_AHB_PRESENT` until non-direct scenes can import and compose received
AHBs without losing popups, cursors, transforms, or alpha.
