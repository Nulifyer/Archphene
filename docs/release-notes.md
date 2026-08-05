# Archphene greenfield release notes

This release replaces the published Java prototype with the Rust/Kotlin
greenfield application. It provides one manager-owned Arch Linux environment,
an integrated Android Terminal, generated Android app shells for reviewed
graphical packages, and a separate no-network Builder for reviewed AUR recipes.

## Install

Choose assets matching the Android device ABI:

- ARM64: `Archphene-arm64-v8a-<version>.apk`
- x86_64: `Archphene-x86_64-<version>.apk`

Verify the matching `.apk.sha256` file before installation. Install the matching
`Archphene-Builder-<abi>-<version>.apk` only when AUR builds are needed. The
Builder has no launcher Activity or Android network permission and must have the
same production signer as the manager.

The published `v1.0.1` manager is a historical prototype. This release does not
migrate its private package/runtime state or separate Terminal data. Back up any
needed files, uninstall the prototype manager and Terminal, and then install the
greenfield APKs. Generated app shells from the prototype must be removed and
republished by the greenfield manager.

## Added

- One shared manager-owned Arch root, package database, home, process model, and
  durable package recovery path for verified official packages and reviewed AUR
  outputs.
- A native Android Terminal integrated into the manager with PTY-backed Bash,
  Unicode IME, hardware input, selection, clipboard, scrollback, resize,
  accessibility, foreground lifecycle, and shared project access.
- Deterministic generated app shells with separate Android package/task
  identities, authenticated Binder sessions, Android Surfaces, compact window
  switching, independent adaptive/document tasks, intents, notifications,
  documents, and capability-specific Android brokers.
- Manager-hosted **Quick launch** for trying one compatible graphical entry
  without publishing an app shell. **Add to Android** remains the explicit full
  integration path.
- Private Wayland, Pulse/AAudio, Camera/PipeWire, printing, accessibility,
  secrets, clipboard, drag-and-drop, document, project-sync, and Android URI
  bridges behind bounded capability contracts.
- Virpipe OpenGL ES acceleration with bounded software fallback, retained
  damaged-region GPU composition for SHM clients, and explicit graphics-path
  diagnostics.

## Validated devices

Current-source exact-ABI builds have broad emulator and physical-device evidence
on:

- API 36 x86_64 Android emulator;
- Samsung Galaxy S22 Ultra (`SM-S908U`), Android 15, ARM64.

This is not evidence for physical x86_64 hardware, GrapheneOS, Samsung DeX,
external displays, or every Android GPU/vendor family.

## Known limits

- Package search is not a compatibility guarantee. Packages can fail review for
  unsupported ABI, ELF page size, toolkit, protocol, device, sandbox, script,
  or Android capability requirements.
- Upstream Arch x86_64 packages remain 4 KiB-only and are blocked on 16 KiB
  x86_64 Android. ARM64 package/runtime artifacts support the maintained 4 KiB
  and 16 KiB paths.
- Virpipe rendering still returns through SHM in the production path. Direct
  virpipe `AHardwareBuffer` presentation is not enabled, and Vulkan and XWayland
  are not supported release paths.
- Physical DeX/external-display and GrapheneOS Pixel validation remain open.
- Chromium-based applications can require a reduced-isolation profile. It
  remains explicit and disabled by default; Archphene does not silently add
  `--no-sandbox`.
- The complete C# breakpoint/debugger workflow and broader non-Latin IME,
  binary clipboard, SDL relative-pointer, and multi-vendor graphics matrices
  remain open.

Report reproducible issues through the repository bug form with Android version,
device model, ABI, package version, and relevant Archphene diagnostics.
