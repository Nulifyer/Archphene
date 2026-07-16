# Roadmap

Archphene is moving from application-specific proofs toward a package-driven Android application platform.

## P0: product foundation

1. **On-device package conversion**
   - synchronize Arch and Arch Linux ARM repository databases;
   - resolve dependency closures;
   - verify package signatures and extraction safety;
   - select the desktop entrypoint, icon, toolkit, ABI, and capabilities;
   - generate a wrapper APK from a reusable template;
   - sign with a persistent per-device identity;
   - install through Android PackageInstaller;
   - preserve per-package progress, cancellation, retry, and process-death reconciliation;
   - allow bounded parallel preparation while serializing wrapper mutation, signing, and Android confirmation.

2. **Shared Wayland compositor**
   - replace duplicated KCalc and Mousepad Java implementations;
   - use generated Wayland protocol bindings;
   - retain the validated native SHM, xdg-toplevel, pointer, XKB keymap, focus, and hardware-key lifecycle gates for x86_64 and AArch64;
   - enforce object, role, version, configure/ack, buffer, popup, and subsurface lifecycles;
   - add deterministic protocol errors and fuzzable parsers.

3. **Production runtime model**
   - atomically materialize immutable runtime contents;
   - validate 4 KB and 16 KB page-size compatibility;
   - isolate each wrapper Linux tree in a dedicated process group and Android UID cleanup boundary;
   - reduce per-app runtime duplication without weakening UID isolation.

4. **Permission and document policy**
   - generate manifest permissions from declared capabilities;
   - extend the manager-owned user-document provider from individual document grants to persisted GUI project trees.
   - expose Android services through same-UID, metadata-gated brokers; URL opening and notifications are validated;
   - expose the validated URL and notification brokers through app-private standard XDG portal and freedesktop.org adapters.

## P1: desktop usability

- general secondary-window mapping for phone, tablet, and Android desktop/freeform modes;
- drag-and-drop, clipboard MIME types, cursor and pointer protocols;
- zero-copy Android HardwareBuffer/dmabuf and Vulkan presentation building on the validated OpenGL ES virpipe path, with SHM fallback;
- audio, printing, camera, secrets/keyrings, and remaining portals; URL handling and notifications are implemented through private standard desktop adapters;
- accessibility and input-method completeness;
- rollback, health checks, storage quotas, and vulnerability status.

## P2: compatibility and platform validation

- broader Qt, GTK, SDL, Electron, and Rust-native application coverage;
- reproducible x86_64 and AArch64 CI package fixtures;
- supported GrapheneOS Pixel validation;
- 16 KB page-size Android devices;
- sustained desktop-mode performance and multi-window testing.

Historical alternatives and evidence are indexed under [Research](../research/README.md).
