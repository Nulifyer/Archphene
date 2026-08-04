# Roadmap

Updated: 2026-08-04

This is the public roadmap. The detailed ordered implementation plan is
[`todo.md`](../todo.md), validated evidence is in
[`project-status.md`](project-status.md), and supported application claims are
in the [`compatibility matrix`](compatibility-matrix.md).

## Established foundation

Archphene now has the core production model: one manager-owned Arch root,
verified official and reviewed AUR package workflows, generated Android app
shells, authenticated Binder sessions, manager-owned Linux processes, a private
Rust Wayland compositor, Android input and capability brokers, production
Terminal and storage integration, and exact-ABI x86_64/AArch64 device evidence.

## Native platform release

The next release milestones are:

1. **Android app-shell windows** — evolve the current single-task shell into a
   bounded phone and desktop task/window model without duplicating Linux roots
   or process ownership.
2. **Graphics without readback** — GPU-compose SHM clients and let virpipe render
   into Android HardwareBuffers while preserving tested CPU and software
   fallbacks.
3. **Phone and desktop UX** — complete touch, pointer, IME, accessibility,
   document, freeform, DeX, and connected-display workflows using capability-
   driven policy rather than OEM detection.
4. **Daily-driver acceptance** — finish the Code, .NET, Git, debugger, browser,
   and representative application workflows on both maintained ABIs.
5. **Release operations** — add GrapheneOS Pixel and broader GPU/device evidence,
   automate physical-device soaks, and complete provenance, signing,
   reproducibility, licensing, update, rollback, and documentation audits.

## Compatibility expansion

After the native release path is complete:

- add private rootless XWayland sessions for legacy X11 applications;
- add an Android-backed Vulkan path only after multi-vendor presentation and
  synchronization evidence;
- broaden office, browser, Rust-native GPU, creative, multimedia, USB, stylus,
  gamepad, accessibility, and multi-window coverage;
- build a complete separately signed 16 KiB x86_64 package universe before
  enabling official x86_64 packages on 16 KiB Android.

## Optional foreign runtimes

FEX-based x86 Linux support and native ARM64 Wine/Hangover Windows support are
separate later compatibility profiles. They must reuse Archphene's app shells,
Wayland compositor, Android brokers, lifecycle, and diagnostics. Proton/DXVK
work begins only after the Vulkan bridge is proven.

Lepton, Waydroid, PRoot, VNC, root/chroot operation, and a custom Android Home
launcher are not part of the Android application architecture.
