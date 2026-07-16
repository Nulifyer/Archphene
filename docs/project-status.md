# Project status

Updated: 2026-07-15

This page separates validated behavior from planned platform work. Package search does not imply package compatibility.

## Validated

| Area | Evidence |
|---|---|
| Manager self-update | Public GitHub Releases discovery, bounded download, SHA-256 verification, signer/package validation, Android confirmation, replacement, restart reconciliation, and 0.9.0 to 1.0.0 device test |
| General x86_64 package transactions | Arch dependency resolution, package-signature verification, closure staging, desktop/terminal classification, package-specific label/executable/icon/MIME/toolkit/ABI/capability metadata, generated APK validation, persistent Android Keystore signing, and PackageInstaller installation pass with KCalc, Mousepad, and CLI packages; a concurrent missing-package failure does not block an unrelated CLI transaction |
| AArch64 package runtime | A cacheable Linux container resolves the current Arch Linux ARM pacman/GnuPG/libarchive closure, verifies every package with the pinned build-system key, reduces it to required AArch64 ELF objects, cross-builds patched glibc and the path broker, and emits a 70-entry checksum catalog. The dual-ABI manager selects isolated ARM repository/trust assets; Samsung tests pass package search, nine-package libalpm resolution, exact build-key verification, staging, terminal classification, authenticated runtime-pack publication, Terminal UID materialization, and `btop 1.4.7` execution |
| Qt and GTK bridge prototypes | KCalc and Mousepad GUI, input, popups, dialogs, clipboard/IME, resizing, and selected document workflows on the listed test devices |
| Shared bridge runtime | KCalc, Mousepad, and the native probe compile against one Android Activity/InputConnection/clipboard/window host and one Rust compositor; the application Activities are metadata-only subclasses |
| Shared runtime packs | Verified Arch dependency closures are published atomically as immutable content-addressed packs owned by the manager; an exported caller-authenticated provider grants exact read-only module URIs to the generated wrapper UID, Binder-death leases protect active wrappers, cold app-drawer relaunch loads the active pack, untrusted shell access is rejected, superseded/manual-cache unbound packs are reclaimed, and the KCalc wrapper shrank from 57 MB to 629 KB. KCalc launches in a dedicated PGID, survives rotation without duplication, and leaves no Linux descendants after Back or force-stop; a shell/grandchild cleanup probe passes on 4 KB and 16 KB emulators and a physical AArch64 Samsung device |
| Secondary-window registry | Parent/child xdg_toplevel ownership, active-window routing, composited phone policy, separate Android Dialog hosting in freeform/tablet mode, child input, close, and parent restoration pass the emulator regression |
| Native compositor bootstrap | Rust wayland-server core cross-compiles for Android x86_64 and AArch64; registry/compositor/SHM/xdg-shell/pointer/keyboard/touch seat discovery, SCM_RIGHTS SHM and sealed XKB v1 keymap FD transfer, checked padded-stride frames, ordered xdg configure queues, partial/final acknowledgements, mapped/unmapped lifecycle enforcement, and validated xdg_positioner state/destruction, commit-gated popup configure geometry, output-bound flip/slide/resize constraint adjustment, reactive output and committed-parent-geometry reconfiguration, double-buffered xdg window geometry, popup-grab focus preservation across root commits, snapshot-and-commit wl_region input state, effective-region popup fall-through, recursive wl_subsurface composition/input with parent-atomic synchronized content/position/stack latching, input-serial grab validation, nested topmost grab stacks, root-to-popup hit testing and local-coordinate pointer/button routing, clipped stacking-order SHM popup composition with ARGB/XRGB blending, child-first idempotent popup_done/teardown with root focus and pixels restored, wl_data_device_manager same-client input-serial-gated text source/offer/selection/cancellation lifecycle plus focused descriptor-backed Android ClipboardManager transfer in both directions, zwp_text_input_v3 focus and double-buffered enable/surrounding/cursor lifecycle with Android InputConnection content-purpose mapping, arbitrary UTF-8 preedit/commit, delete, editor-action, show/hide sequencing, and invalid-input rejection, demand-driven ClipboardManager reads with self-publish suppression, inverse wl_surface buffer transform/scale with retained-source reinterpretation, accumulated logical/buffer damage translated through synchronized subsurface trees into clipped root presentation batches, double-buffered wp_viewporter crop/destination scaling, wp_fractional_scale_v1 feedback, cursor-role SHM buffers isolated from application composition with Android PointerIcon transfer, zwp_pointer_gestures_v1 swipe/pinch/hold streams, and wl_output surface-enter/mode/scale updates, with post-ack Android bitmap presentation and Choreographer-timestamped frame-callback pacing, focused pointer, wl_pointer v9 value120/relative-direction wheel axes, wl_touch motion, two-pointer gestures, and hardware-key events routed from Android input, exact wire/pixel checks, and resource teardown pass on both |
| Package job scheduler | Per-package phase/error state and a bounded structured diagnostic history survive Activity recreation and manager process death; legacy jobs migrate without data loss, two preparation jobs can overlap, wrapper mutation/signing and Android confirmation are serialized, package failures are isolated, and list/detail progress, recent phases, cancel, retry, installer completion, and interrupted-completion reconciliation pass emulator tests |
| Terminal command channel | Up to eight foreground-service-owned PTYs issue collision-free per-request pacman facade commands. Search results and durable package-job resolve/download/install/complete/cancel/error states return through a signature- and caller-verified Terminal provider. A real `tree 2.3.2` install and fresh-session execution pass on the 4 KB x86_64 emulator; physical AArch64 executes managed `btop 1.4.7`; untrusted shell result injection is rejected. The 16 KB x86_64 emulator fails closed with an explicit upstream-loader compatibility result |
| Terminal project folders | A user-selected SAF tree receives a persisted read/write grant and a stable `$HOME/Projects/<alias>` local mirror. Emulator validation covers recursive initial pull, local push, Android pull, process-restart reuse without a prompt, simultaneous-edit conflict preservation, deferred deletions, symlink rejection, mapping removal, and fail-closed access after removal |
| Package discovery | Official Arch name/description candidates use deterministic exact, executable, prefix, token, and description ranking; executable ownership is merged from repository file databases, glmark2-es2-wayland resolves to glmark2, and installed-app multi-term search shares the same matching rules |
| OpenGL ES bridge | A manager-generated GLMark2 wrapper starts a same-UID Android virglrenderer helper, Mesa reports a virgl renderer backed by the emulator NVIDIA OpenGL ES translator, and the complete 1080x2205 suite finishes with score 12. The final fence-compatible build remains stable through repeated scenes without renderer errors. The aarch64 helper builds and creates its private socket under the Archphene UID on a Samsung Galaxy S22 Ultra |

## In progress

Local debug builds can remain multi-ABI. Release builds emit independently signed x86_64 and arm64-v8a manager APKs whose embedded Terminal, package runtime, trust data, and wrapper templates contain only the selected ABI. Both variants launch pacman on matching devices, and the ARM manager has generated, installed, and launched a real KCalc wrapper on the Samsung test device.

1. **Architecture support**
   - exercise the ABI-specific release workflow on a tagged GitHub prerelease;
   - use official Arch Linux packages only for x86_64 and Arch Linux ARM packages/trust roots for AArch64;
   - accept repository packages marked any, but require exact CPU ABI for native ELF files;
   - do not silently emulate x86_64 on ARM.


## Pending

- Complete capability-to-Android-permission brokers.
- Manager-owned user document provider and complete multi-document conflict handling.
- Zero-copy Android HardwareBuffer/dmabuf presentation, Vulkan, physical ARM end-to-end GL application validation, and robust helper-loss recovery; the current OpenGL ES virpipe path presents through SHM.
- Audio, notifications, printing, camera, drag-and-drop, accessibility, secrets/keyrings, URL handling, and remaining portals.
- Broader Qt, GTK, SDL, Electron, and Rust-native compatibility.
- GrapheneOS Pixel and sustained desktop-mode validation.
- An Arch user shell, as described in [Terminal applications](terminal-apps.md). Project trees currently use explicit synchronized mirrors; a live SAF path broker and manager-owned GUI document integration remain pending.
- Post-compositor Qt/GTK theme, density, font, focus, menu, and dialog consistency work.

## Package-manager efficiency rules

- Cache repository databases, verified package archives, dependency graphs, extracted immutable modules, and wrapper templates by content hash.
- Download once and reuse only after signature/hash verification.
- Bound downloads by package-declared and global size limits.
- Keep Android confirmation serialized so users always know which app is being installed.
- Continue unrelated jobs after one package fails.
- Persist state before every phase transition so process death or reboot can resume or report a precise failure.
