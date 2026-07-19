# Archphene TODO

Updated: 2026-07-19

This is the prioritized completion queue for the Archphene Android application. Check items only after implementation and device validation. Historical experiment notes belong in research/; current behavior belongs in docs/.

## P0 - Public release blockers

- [x] Complete general on-device package transactions for supported x86_64 desktop and CLI packages.
  - [x] Flow the selected desktop-entry display name/executable and detected runtime toolkit through generic wrapper assembly.
  - [x] Preserve the selected desktop-entry icon through generic wrapper assembly.
  - [x] Generate exact Android document intents from up to 16 MIME types declared by the selected desktop entry; wrappers without MIME types advertise no file intents.
  - [x] Record the validated package architecture and detected runtime toolkit in generated wrapper metadata; reject unsupported ABI/template combinations.
  - [x] Generate and validate a required bridge-capability contract; gate incoming-document brokers on package-declared MIME support.
  - [x] Generate package-specific Android labels, icons, source/runtime metadata, capabilities, and exact document intent resources.
  - [x] Persist and display bounded structured phase diagnostics, including legacy-job migration.
  - [x] Prove on the emulator with concurrent real package transactions that one resolution failure does not block an unrelated CLI package install.
- [x] Complete AArch64 package runtime support.
  - [x] Build a reproducible, checksum-cataloged AArch64 pacman/GnuPG/libarchive closure from official Arch Linux ARM repositories; verify every package against the pinned build-system key and cross-build matching patched glibc.
  - [x] Embed the verified AArch64 runtime and separate trust assets in the ARM manager, then prove package search, resolution, verification, staging, and Terminal publication on Samsung.
  - [x] Generate arm64-v8a desktop wrapper templates and prove a real Qt package through Android PackageInstaller and app-drawer launch.
  - [x] Publish separate x86_64 and arm64-v8a release assets; accept `any` data packages but require matching ABI for native ELF files.
- [x] Complete runtime-pack lifecycle safety.
  - [x] Add authenticated runtime-pack leases backed by stable provider clients and Binder death tokens. Running wrappers survive unbinding/GC and release on exit/death; Terminal leases each pack until its private copy is hash-verified and committed.
  - [x] Reconcile external Android uninstalls and revoke grants per package without disrupting wrappers that share a pack.
  - [x] Reuse unchanged closures before copying runtime-pack modules.
  - [x] Store verified modules once as manager-owned content-addressed blobs, migrate legacy per-pack copies, preserve per-pack authorization, garbage-collect unreferenced blobs, and validate migration plus generated KCalc launch on physical AArch64.
  - [x] Validate 4 KB and 16 KB ELF page compatibility. Runtime executables and published modules now fail closed before execution when an ELF load segment is incompatible; the AArch64 runtime is 64 KB-aligned, while current upstream Arch x86_64 packages remain 4 KB-only and are explicitly unsupported on 16 KB x86_64 Android until rebuilt.
  - [x] Clean up the complete Linux process tree when a wrapper exits. Managed launches use a dedicated process group, parent-death signal, cancellable execution registry, and final dedicated-UID descendant sweep.
- [x] Finish the Terminal product.
  - [x] Add multiple sessions/tabs and a foreground-service lifecycle. PTYs survive Activity closure under a visible Android notification, close independently by process group, and die with the Terminal app process.
  - [x] Return manager progress and terminal results to the invoking command. Per-request files correlate exact search/install/remove/upgrade requests with durable manager jobs; the signed manager reports bounded phases and terminal outcomes through a signature-protected Terminal provider.
  - [x] Add persisted project-tree mappings. User-selected SAF trees retain scoped read/write grants and synchronize through stable `$HOME/Projects/<alias>` POSIX mirrors with bounded traversal, conflict copies, deferred deletions, symlink rejection, grant release, and restart persistence.
  - [x] Select and package the user shell. Terminal boots with Bionic sh until a verified Arch Bash closure is installed, then selects managed Bash on restart; x86_64 emulator and physical ARM64 device tests cover PTY startup, locale data, package queries, and home writes.
  - [x] Keep the native Termux terminal renderer unless image protocols or other modern terminal features justify a compatible extension.
- [x] Complete Android capability and document brokers.
  - [x] Expose visible per-app Linux homes through one manager-owned DocumentsProvider while hiding dotfiles and private runtime state behind a signature-protected wrapper endpoint.
  - [x] Import up to 32 granted Android documents with collision-safe Linux names and preserve concurrent Android edits as bounded conflict copies before writeback.
  - [x] Validate manager CRUD, direct-provider denial, active-app restart, same-name import, conflict preservation, writeback, and DocumentsUI browse on the x86_64 emulator and physical AArch64.
  - [x] Deliver a document sent to an already-running `singleTask` wrapper through a shared safe-restart policy with an explicit unsaved-work warning, Cancel action, and debug-only automated regression.
  - [x] Add a bounded same-UID Android capability broker and ABI-specific glibc client for HTTP(S) URL handling and notification permission/post/withdraw; validate unsafe-URI and cross-UID rejection and dynamic runtime-pack publication on the emulator.
  - [x] Add a private session bus and standard XDG portal adapters so unmodified applications can reach the validated URL and notification primitives.
  - [x] Generate capability-specific wrapper manifests so camera and microphone permissions are declared only when their matching bridge capability is enabled; verify all eight document/permission variants and a manager-signed first install on the emulator.
  - [x] Add capability-scoped Linux audio playback through a wrapper-private Pulse native-protocol server backed by Android AAudio, with OpenSL ES fallback.
  - [x] Add XDG printing through the Android system print UI with bounded same-UID PDF transfer, private staging, cancellation cleanup, and invalid-document rejection.
  - [x] Add microphone capture with an explicit `RECORD_AUDIO` request and separate input capability; validate grant, denial/no-reprompt, privacy-switch silence, process cleanup, and real nonzero capture on x86_64 emulator and physical AArch64.
  - [x] Add capability-scoped bidirectional plain-text drag-and-drop between Android and standard Wayland data devices; validate copy negotiation, bounded payload transfer, completion, cancellation, and resource cleanup on x86_64 emulator and physical AArch64.
  - [x] Complete URI/file drag-and-drop through the SAF/document broker without exposing raw `content://` URIs to Linux applications; validate protocol negotiation, import/writeback, visible-home export, exact URI grants, denial without grants, and cleanup on x86_64 emulator and physical AArch64.
  - [ ] Complete camera, accessibility, and secrets/keyrings integration.
    - [x] Add a capability-scoped Camera2 permission/state and bounded one-shot JPEG descriptor API; validate the real Android grant and denial/no-reprompt paths plus 1280x720 capture on x86_64 emulator and physical AArch64.
    - [x] Add the private PipeWire producer and XDG Camera portal adapter required by unmodified Linux camera consumers.
      - [x] Validate an official unmodified Arch Snapshot package on the x86_64 emulator through Android grant and denial paths, timestamped PipeWire frames, and process/lease cleanup.
      - [x] Repeat the generated unmodified-consumer validation with the AArch64 runtime on a physical ARM device.
    - [x] Add a bounded virtual-node accessibility tree, Android framework events/focus, and reverse click/edit/scroll action queue; validate through a test-only AccessibilityService on 4 KB and 16 KB x86_64 emulators and physical AArch64.
    - [x] Add the private AT-SPI2 D-Bus adapter and secondary-window semantic ownership required by unmodified Qt/GTK applications.
      - [x] Implement and source-validate the private bus/status, socket/embed, registry/event, bounded traversal/publication, cross-process child, reverse-action, password-redaction, and transient-snapshot contracts.
      - [x] Validate Android semantic queue retention, sticky multi-window ownership, cross-window action rejection, offset-root bounds, and lifecycle on 4 KB and 16 KB x86_64 emulators and physical AArch64.
      - [x] Build and link the adapter in the reproducible Linux toolchain, then validate real unmodified Qt and GTK controls, focus, edits, menus, dialogs, and secondary windows on x86_64 and AArch64.
    - [x] Add a per-wrapper Android Keystore-backed encrypted secret store with bounded descriptor APIs for store/read/list/delete; validate ciphertext, metadata, overwrite, process-restart persistence, limits, deletion, lifecycle, and no log exposure on x86_64 emulator and physical AArch64.
    - [x] Add a capability-gated private Secret Service D-Bus adapter with sender-bound sessions, login collection/search/properties, create/get/set/replace/delete, zero-length values, and disconnect cleanup; validate its wire contract on 4 KB and 16 KB x86_64 emulators and physical AArch64.
    - [x] Validate packaged Arch libsecret and KWallet clients against the private Secret Service adapter before claiming broad unmodified-toolkit compatibility.
      - [x] Validate official Arch x86_64 libsecret and KWallet clients on a 4 KB emulator, including encrypted sessions, write/read/clear, restart persistence, and no plaintext logs.
      - [x] Build and validate the official Arch Linux ARM libsecret client closure on physical AArch64; encrypted store/lookup/clear passes through the private Secret Service adapter.
      - [x] Build the patched AArch64 KWallet compatibility daemon and validate official `kwallet-query` on physical ARM; upstream Arch x86_64 clients remain unusable on 16 KB Android because their ELF files are 4 KB-aligned.
  - [x] Reject broad all-files access as the default. Use user-selected Storage Access Framework documents and trees; reconsider an optional advanced flow only with a concrete compatibility requirement.
- [ ] Complete platform compatibility.
  - [x] Keep the manager usable on 16 KB x86_64 without Android's generic page-size dialog; show an explicit in-app restriction and block package transactions before incompatible 4 KB Arch ELF execution. Rebuilding the upstream x86_64 runtime remains a broader package-compatibility task.
  - [x] Build patched x86_64 glibc with 64 KB ELF alignment and execute an aligned dynamic probe under the manager UID on a real 16 KB Android emulator.
  - [ ] Build and sign a separate no-mixing 16 KB x86_64 package repository, then validate a complete GUI closure including late-loaded modules before enabling package transactions.
  - Remaining portal and broad toolkit/runtime validation.
  - Broader Qt, GTK, SDL, Electron, Rust-native, XWayland, Vulkan, and zero-copy GPU validation.
    - [x] Include runtime-loaded Vulkan loader aliases and validate unmodified Arch `vulkaninfo` through ICD discovery in Terminal.
  - [x] Detect mid-session virgl helper loss and restart the payload once with llvmpipe while preserving the Android Activity; validate by same-UID fault injection against a manager-generated GLMark2 wrapper.
  - [x] Bound the shared xdg_toplevel registry, reject cyclic/cross-client parent chains, clear destroyed-parent references, and validate a real GTK child control/close/parent-restoration flow.
  - General secondary-window policy for phone, tablet, freeform, and external displays.
- [x] Complete end-user release and update lifecycle.
  - [x] Configure a commit-pinned, reproducible Linux workflow that creates a draft, builds and verifies signed x86_64 and arm64-v8a manager/Terminal/runtime artifacts, uploads checksums, and publishes only after all assets exist.
  - [x] Require exact-ABI self-update assets; reject ABI-neutral and wrong-ABI releases on 4 KB x86_64, 16 KB x86_64, and physical AArch64.
  - [x] Validate local Android update confirmation, restart reconciliation, signed downgrade rejection, checksum rejection, and retention of the installed version after failures.
  - [x] Publish `v1.0.1`, verify both ABI artifacts and embedded companions, then run the exact-ABI live update and the real published `v1.0.0` x86 migration regression.
- [ ] Pass the public release validation gate.
  - [x] Emulator phone/tablet/docked/freeform matrix with dark/light, rotation, IME, lifecycle, update, uninstall, and concurrent-failure tests.
  - [x] Samsung ARM64 control plane and sustained phone/freeform regression.
  - [ ] Physical x86_64 Android target.
  - [ ] Supported GrapheneOS Pixel and sustained external-display desktop mode.
  - [x] Add reproducible Android storage measurement that separates APK, installed code, persistent app data, transient execution cache, shared runtime blobs, pack metadata, downloads, and staging.
  - [x] Publish clean-install manager, Terminal, generated-wrapper, shared-runtime, and per-application storage costs; distinguish steady-state data from transient loader cache and clearable archives.
  - Validate descriptor-only runtime module paths against late `dlopen()` in unmodified Qt and GTK applications before deciding whether the current bounded transient named-module cache can be removed.

## P1 - Product and UX

- [ ] Refresh the manager UI against current Obtainium.
  - Use its compact row hierarchy, spacing, progress presentation, top-right Settings action, bottom-right + Add action, and bottom list padding as references while retaining Archphene's explicit phase/error status.
  - Remove the old bottom navigation only after phone, tablet, accessibility, and back-navigation validation.
- [ ] Refine search controls and empty/loading/error states using the same compact visual language.
- [ ] Finish Qt and GTK appearance consistency.
  - Validate actual pixel text/control sizes against Android font scale and density.
  - Match Material You light/dark colors while preserving usable toolkit contrast, popup borders/shadows, close targets, and desktop-mode density controls.
- [ ] Review and execute the compatibility matrix in pacman-test-packages.md.
  - Promote the reviewed matrix into docs/ or research/, then test representative phone, tablet, docked, GPU, document, multimedia, accessibility, and failure cases.

## P2 - Documentation and repository readiness

- [x] Add deterministic package and installed-app search ranking.
  - Exact package, executable ownership, prefix/token, and description matches are ranked; glmark2-es2-wayland resolves to glmark2.
- [x] Document non-root Android Terminal limitations in docs/terminal-apps.md.
- [x] Ignore repository-local generated tooling and external reference checkouts.
- [ ] Review the remaining untracked package matrix, generated artifacts, licenses, and source provenance before tracking or deleting anything.
- [ ] Update README, focused user docs, roadmap, project status, changelog, and release notes after P0/P1 behavior is final.
- [ ] Run a final public-repository audit after P0/P1 behavior is final.
  - [x] Add an automated source audit for required community files, stale licensing claims, generated/secret/release artifacts, oversized tracked files, private-key material, prebuilt manifests/checksums, and commit-pinned Actions.
  - [ ] Recheck stale experiment claims, duplicate docs, source provenance, release notes, and CI results immediately before publication.

## Next project - ArchpheneOS

Start only after the Android application is complete and the end user confirms the release.

- [ ] Boot an x86_64 VM into an AOSP/GrapheneOS-derived laptop image and validate the security model before attempting Arch package integration.
- [ ] Define which GrapheneOS guarantees are device-specific and cannot be carried to generic x86 hardware without additional work.
- [ ] Design a laptop privilege model where Android applications remain sandboxed and Linux administration requires explicit escalation.
