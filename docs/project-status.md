# Project status

Updated: 2026-07-13

This page separates validated behavior from planned platform work. Package search does not imply package compatibility.

## Validated

| Area | Evidence |
|---|---|
| Manager self-update | Public GitHub Releases discovery, bounded download, SHA-256 verification, signer/package validation, Android confirmation, replacement, restart reconciliation, and 0.9.0 to 1.0.0 device test |
| KCalc package transaction | x86_64 Arch dependency resolution, package-signature verification, closure staging, wrapper assembly, persistent Android Keystore signing, and PackageInstaller installation performed at manager runtime |
| Qt and GTK bridge prototypes | KCalc and Mousepad GUI, input, popups, dialogs, clipboard/IME, resizing, and selected document workflows on the listed test devices |
| Native compositor bootstrap | Rust wayland-server core cross-compiles for Android x86_64 and AArch64; registry/compositor/SHM/xdg-shell/pointer-and-keyboard seat discovery, SCM_RIGHTS SHM and sealed XKB v1 keymap FD transfer, checked padded-stride frames, ordered xdg configure queues, partial/final acknowledgements, mapped/unmapped lifecycle enforcement, and validated xdg_positioner state/destruction and initial popup parent/geometry/configure/ack/teardown, and wl_output surface-enter/mode/scale updates, with post-ack Android bitmap presentation, focused pointer and hardware-key events routed from Android input, exact wire/pixel checks, and resource teardown pass on both |
| Manager status UI | Static current-version state, active spinner, and separate download/install progress are implemented |

## In progress

1. **Shared native compositor**
   - continue after the validated registry, SHM, surface, initial xdg-toplevel, map/unmap, xdg_positioner, initial popup role/configure/destruction, pointer, XKB keymap, focus, hardware-key, and live output mode/scale slices with popup grabs/reposition/composition/dismissal, clipboard, and Android IME/text input;
   - replace both application-specific Java compositor forks only after cross-toolkit regression gates pass;
   - keep clipboard reads focused and user-initiated to prevent Android clipboard privacy notifications.

2. **General package transactions**
   - replace the single global operation field with persistent per-package jobs;
   - expose resolve -> download -> verify -> stage -> wrap -> sign -> install in both list and detail views;
   - allow two network/verification jobs concurrently, but serialize wrapper mutation, signing, and Android installation confirmation;
   - isolate failures by package and retain actionable logs and retry state.

3. **Architecture support**
   - publish x86_64 and arm64-v8a manager/runtime artifacts;
   - use official Arch Linux packages only for x86_64 and Arch Linux ARM packages/trust roots for AArch64;
   - accept repository packages marked any, but require exact CPU ABI for native ELF files;
   - do not silently emulate x86_64 on ARM.

4. **Runtime storage**
   - materialize immutable, verified runtime modules atomically;
   - share read-only runtime bytes without sharing Linux application UID, writable state, or Android permissions;
   - validate 4 KB and 16 KB page-size devices and process-tree cleanup.

## Pending

- Complete capability-to-Android-permission brokers.
- Manager-owned user document provider and complete multi-document conflict handling.
- General secondary-window registry for phone, tablet, and Android desktop/freeform modes.
- GPU/EGL/Vulkan and dmabuf presentation with SHM fallback.
- Audio, notifications, printing, camera, drag-and-drop, accessibility, secrets/keyrings, URL handling, and remaining portals.
- Broader Qt, GTK, SDL, Electron, and Rust-native compatibility.
- GrapheneOS Pixel and sustained desktop-mode validation.
- Android-integrated terminal environment described in [Terminal applications](terminal-apps.md).
- Post-compositor Qt/GTK theme, density, font, focus, menu, and dialog consistency work.

## Package-manager efficiency rules

- Cache repository databases, verified package archives, dependency graphs, extracted immutable modules, and wrapper templates by content hash.
- Download once and reuse only after signature/hash verification.
- Bound downloads by package-declared and global size limits.
- Keep Android confirmation serialized so users always know which app is being installed.
- Continue unrelated jobs after one package fails.
- Persist state before every phase transition so process death or reboot can resume or report a precise failure.
