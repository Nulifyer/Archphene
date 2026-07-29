# Roadmap

Archphene is moving from application-specific proofs toward a package-driven Android application platform.

## P0: product foundation

1. **On-device package conversion**
   - synchronize Arch and Arch Linux ARM repository databases;
   - resolve dependency closures;
   - verify package signatures and extraction safety;
   - discover and register desktop entrypoints, icons, toolkit, ABI, and capabilities from the shared Arch root;
   - generate a wrapper APK from a reusable template;
   - sign with a persistent per-device identity;
   - install through Android PackageInstaller;
   - authenticate the thin launcher over Binder and render a manager-owned Linux process into its Surface;
   - preserve per-package progress, cancellation, retry, and process-death reconciliation;
   - allow bounded parallel preparation while serializing wrapper mutation, signing, and Android confirmation.

2. **Shared Wayland compositor**
   - maintain the shared Rust compositor used by KCalc, Mousepad, generated wrappers, and native probes;
   - expand generated Wayland protocol bindings beyond the validated core;
   - retain the validated native SHM, xdg-toplevel, pointer, XKB keymap, focus, and hardware-key lifecycle gates for x86_64 and AArch64;
   - enforce object, role, version, configure/ack, buffer, popup, and subsurface lifecycles;
   - add deterministic protocol errors and fuzzable parsers.

3. **Production runtime model**
   - keep one conventional manager-owned Arch root and package database;
   - validate 4 KB and 16 KB page-size compatibility;
   - supervise each launch in a dedicated manager-owned process group tied to an authenticated Binder death token;
   - avoid per-wrapper Linux roots and runtime-pack duplication;
   - document that installed Linux packages intentionally share one trust domain.

4. **Permission and document policy**
   - generate manifest permissions from declared capabilities;
   - extend the manager-owned user-document provider from individual document grants to persisted GUI project trees.
   - expose Android services through descriptor-gated manager brokers and authenticated launcher requests;
   - expose the validated URL and notification brokers through app-private standard XDG portal and freedesktop.org adapters.

## P1: desktop usability

- general secondary-window mapping for phone, tablet, and Android desktop/freeform modes;
- broader clipboard MIME types beyond the validated demand-driven plain-text `wl-clipboard` path and remaining pointer protocol completeness;
- zero-copy Android HardwareBuffer/dmabuf and Vulkan presentation building on the validated OpenGL ES virpipe path, with SHM fallback;
- remaining portal adapters and richer notification, file-transfer, and device-service policies;
- broader accessibility and input-method compatibility beyond the validated Qt/GTK AT-SPI2 paths;
- rollback, health checks, storage quotas, and vulnerability status.

## P2: compatibility and platform validation

- broader Qt, GTK, SDL, Electron, and Rust-native application coverage;
- reproducible x86_64 and AArch64 CI package fixtures;
- supported GrapheneOS Pixel validation;
- broader 16 KB device coverage; current ARM64 runtime artifacts and manager/self-update paths pass, while upstream Arch x86_64 packages remain 4 KB-only;
- sustained desktop-mode performance and multi-window testing.

Historical alternatives and evidence are indexed under [Research](../research/README.md).
