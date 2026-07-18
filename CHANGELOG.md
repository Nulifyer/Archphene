# Changelog

Notable user-facing changes will be recorded here.

## 1.0.1 - 2026-07-18

### Added

- Complete on-device package resolution, signature verification, closure staging, wrapper generation, persistent signing, and Android installation for supported x86_64 and AArch64 packages.
- Exact-ABI manager, Terminal, runtime, compositor, and wrapper artifacts for x86_64 and arm64-v8a, including a one-time update path from the published x86_64 `v1.0.0` manager.
- A first-party tabbed Android Terminal with managed Arch CLI packages, verified Bash, durable package requests, foreground sessions, and SAF project trees.
- Android brokers and private desktop adapters for documents, URLs, notifications, audio, microphone, printing, camera/PipeWire, drag-and-drop, accessibility, and encrypted secrets.
- Private AT-SPI2 translation validated with unmodified KCalc/Qt and Mousepad/GTK on x86_64 and physical AArch64.
- Secret Service, libsecret, and KWallet flows validated on x86_64 and physical AArch64.

### Changed

- Replaced application-specific compositor copies with one shared Rust Wayland compositor and metadata-driven Android wrapper host.
- Moved Linux closures into manager-owned content-addressed runtime packs with authenticated providers, Binder-death leases, deduplicated blobs, and process-tree cleanup.
- Added compact stateful package rows, package search/ranking, version selection and pinning, isolated concurrent preparation, durable diagnostics, and self-update progress.
- Added Android-aware Qt/GTK light/dark appearance, font and density controls, IME handling, rotation, popups, dialogs, and secondary-window restoration.

### Security

- Reject incompatible ABIs and 4 KB ELF objects on 16 KB systems before execution.
- Generate package-specific capabilities, permissions, MIME intents, labels, icons, and source/runtime metadata.
- Keep Android PackageInstaller, app UIDs, SELinux, runtime permissions, SAF grants, and Keystore as the security authorities.

### Known limitations

- Package search is broader than tested application compatibility; Qt, GTK, SDL, Electron, Rust-native, XWayland, multimedia, and portal coverage still need expansion.
- Vulkan, zero-copy dmabuf presentation, robust GPU-helper recovery, and sustained vendor desktop-mode validation remain incomplete.
- GrapheneOS Pixel, physical x86_64 Android, and the complete phone/tablet/docked/freeform release matrix remain unvalidated.
