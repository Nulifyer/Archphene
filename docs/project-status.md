# Project status

Updated: 2026-07-15

This page separates validated behavior from planned platform work. Package search does not imply package compatibility.

## Validated

| Area | Evidence |
|---|---|
| Manager self-update | Public GitHub Releases discovery, bounded download, SHA-256 verification, signer/package validation, Android confirmation, replacement, restart reconciliation, and 0.9.0 to 1.0.0 device test |
| General x86_64 package transactions | Arch dependency resolution, package-signature verification, closure staging, desktop/terminal classification, package-specific label/executable/icon/MIME/toolkit/ABI/capability metadata, generated APK validation, persistent Android Keystore signing, and PackageInstaller installation pass with KCalc, Mousepad, and CLI packages; a concurrent missing-package failure does not block an unrelated CLI transaction |
| Qt and GTK bridge prototypes | KCalc and Mousepad GUI, input, popups, dialogs, clipboard/IME, resizing, and selected document workflows on the listed test devices |
| Shared bridge runtime | KCalc, Mousepad, and the native probe compile against one Android Activity/InputConnection/clipboard/window host and one Rust compositor; the application Activities are metadata-only subclasses |
| Shared runtime packs | Verified Arch dependency closures are published atomically as immutable content-addressed packs owned by the manager; an exported caller-authenticated provider grants exact read-only module URIs to the generated wrapper UID, cold app-drawer relaunch loads the active pack, untrusted shell access is rejected, superseded/manual-cache unbound packs are reclaimed, and the KCalc wrapper shrank from 57 MB to 629 KB |
| Secondary-window registry | Parent/child xdg_toplevel ownership, active-window routing, composited phone policy, separate Android Dialog hosting in freeform/tablet mode, child input, close, and parent restoration pass the emulator regression |
| Native compositor bootstrap | Rust wayland-server core cross-compiles for Android x86_64 and AArch64; registry/compositor/SHM/xdg-shell/pointer/keyboard/touch seat discovery, SCM_RIGHTS SHM and sealed XKB v1 keymap FD transfer, checked padded-stride frames, ordered xdg configure queues, partial/final acknowledgements, mapped/unmapped lifecycle enforcement, and validated xdg_positioner state/destruction, commit-gated popup configure geometry, output-bound flip/slide/resize constraint adjustment, reactive output and committed-parent-geometry reconfiguration, double-buffered xdg window geometry, popup-grab focus preservation across root commits, snapshot-and-commit wl_region input state, effective-region popup fall-through, recursive wl_subsurface composition/input with parent-atomic synchronized content/position/stack latching, input-serial grab validation, nested topmost grab stacks, root-to-popup hit testing and local-coordinate pointer/button routing, clipped stacking-order SHM popup composition with ARGB/XRGB blending, child-first idempotent popup_done/teardown with root focus and pixels restored, wl_data_device_manager same-client input-serial-gated text source/offer/selection/cancellation lifecycle plus focused descriptor-backed Android ClipboardManager transfer in both directions, zwp_text_input_v3 focus and double-buffered enable/surrounding/cursor lifecycle with Android InputConnection content-purpose mapping, arbitrary UTF-8 preedit/commit, delete, editor-action, show/hide sequencing, and invalid-input rejection, demand-driven ClipboardManager reads with self-publish suppression, inverse wl_surface buffer transform/scale with retained-source reinterpretation, accumulated logical/buffer damage translated through synchronized subsurface trees into clipped root presentation batches, double-buffered wp_viewporter crop/destination scaling, wp_fractional_scale_v1 feedback, cursor-role SHM buffers isolated from application composition with Android PointerIcon transfer, zwp_pointer_gestures_v1 swipe/pinch/hold streams, and wl_output surface-enter/mode/scale updates, with post-ack Android bitmap presentation and Choreographer-timestamped frame-callback pacing, focused pointer, wl_pointer v9 value120/relative-direction wheel axes, wl_touch motion, two-pointer gestures, and hardware-key events routed from Android input, exact wire/pixel checks, and resource teardown pass on both |
| Package job scheduler | Per-package phase/error state and a bounded structured diagnostic history survive Activity recreation and manager process death; legacy jobs migrate without data loss, two preparation jobs can overlap, wrapper mutation/signing and Android confirmation are serialized, package failures are isolated, and list/detail progress, recent phases, cancel, retry, installer completion, and interrupted-completion reconciliation pass emulator tests |
| Package discovery | Official Arch name/description candidates use deterministic exact, executable, prefix, token, and description ranking; executable ownership is merged from repository file databases, glmark2-es2-wayland resolves to glmark2, and installed-app multi-term search shares the same matching rules |
| OpenGL ES bridge | A manager-generated GLMark2 wrapper starts a same-UID Android virglrenderer helper, Mesa reports a virgl renderer backed by the emulator NVIDIA OpenGL ES translator, and the complete 1080x2205 suite finishes with score 12. The final fence-compatible build remains stable through repeated scenes without renderer errors. The aarch64 helper builds and creates its private socket under the Archphene UID on a Samsung Galaxy S22 Ultra |

## In progress

The debug manager and Terminal APKs are multi-ABI and run as arm64-v8a on the Samsung test device. This validates the Android control plane and PTY only; release assets must remain x86_64 until the Arch Linux ARM runtime, repositories, and trust roots are integrated.

1. **Architecture support**
   - publish x86_64 and arm64-v8a manager/runtime artifacts;
   - use official Arch Linux packages only for x86_64 and Arch Linux ARM packages/trust roots for AArch64;
   - accept repository packages marked any, but require exact CPU ABI for native ELF files;
   - do not silently emulate x86_64 on ARM.

2. **Runtime storage**
   - add running-process leases so garbage collection cannot remove a pack still used by a wrapper;
   - reconcile bindings after uninstall and retain explicit grant revocation across all lifecycle paths;
   - avoid repeatedly extracting an unchanged verified dependency closure;
   - validate 4 KB and 16 KB page-size devices and process-tree cleanup.

## Pending

- Complete capability-to-Android-permission brokers.
- Manager-owned user document provider and complete multi-document conflict handling.
- Zero-copy Android HardwareBuffer/dmabuf presentation, Vulkan, physical ARM end-to-end GL application validation, and robust helper-loss recovery; the current OpenGL ES virpipe path presents through SHM.
- Audio, notifications, printing, camera, drag-and-drop, accessibility, secrets/keyrings, URL handling, and remaining portals.
- Broader Qt, GTK, SDL, Electron, and Rust-native compatibility.
- GrapheneOS Pixel and sustained desktop-mode validation.
- Terminal tabs/sessions, foreground-service lifecycle, persisted project-tree mappings, an Arch user shell, and manager progress returned to invoking commands, as described in [Terminal applications](terminal-apps.md).
- Post-compositor Qt/GTK theme, density, font, focus, menu, and dialog consistency work.

## Package-manager efficiency rules

- Cache repository databases, verified package archives, dependency graphs, extracted immutable modules, and wrapper templates by content hash.
- Download once and reuse only after signature/hash verification.
- Bound downloads by package-declared and global size limits.
- Keep Android confirmation serialized so users always know which app is being installed.
- Continue unrelated jobs after one package fails.
- Persist state before every phase transition so process death or reboot can resume or report a precise failure.
