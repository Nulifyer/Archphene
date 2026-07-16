# Archphene TODO

Updated: 2026-07-16

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
  - [x] Validate 4 KB and 16 KB ELF page compatibility. Runtime executables and published modules now fail closed before execution when an ELF load segment is incompatible; the AArch64 runtime is 64 KB-aligned, while current upstream Arch x86_64 packages remain 4 KB-only and are explicitly unsupported on 16 KB x86_64 Android until rebuilt.
  - [x] Clean up the complete Linux process tree when a wrapper exits. Managed launches use a dedicated process group, parent-death signal, cancellable execution registry, and final dedicated-UID descendant sweep.
- [x] Finish the Terminal product.
  - [x] Add multiple sessions/tabs and a foreground-service lifecycle. PTYs survive Activity closure under a visible Android notification, close independently by process group, and die with the Terminal app process.
  - [x] Return manager progress and terminal results to the invoking command. Per-request files correlate exact search/install/remove/upgrade requests with durable manager jobs; the signed manager reports bounded phases and terminal outcomes through a signature-protected Terminal provider.
  - [x] Add persisted project-tree mappings. User-selected SAF trees retain scoped read/write grants and synchronize through stable `$HOME/Projects/<alias>` POSIX mirrors with bounded traversal, conflict copies, deferred deletions, symlink rejection, grant release, and restart persistence.
  - [x] Select and package the user shell. Terminal boots with Bionic sh until a verified Arch Bash closure is installed, then selects managed Bash on restart; x86_64 emulator and physical ARM64 device tests cover PTY startup, locale data, package queries, and home writes.
  - [x] Keep the native Termux terminal renderer unless image protocols or other modern terminal features justify a compatible extension.
- [ ] Complete Android capability and document brokers.
  - [x] Expose visible per-app Linux homes through one manager-owned DocumentsProvider while hiding dotfiles and private runtime state behind a signature-protected wrapper endpoint.
  - [x] Import up to 32 granted Android documents with collision-safe Linux names and preserve concurrent Android edits as bounded conflict copies before writeback.
  - [x] Validate manager CRUD, direct-provider denial, same-name import, conflict preservation, and writeback on the x86_64 emulator; validate manager CRUD and direct-provider denial on physical AArch64.
  - [ ] Deliver a document sent to an already-running `singleTask` wrapper through a generic activation or safe restart policy; cold document launch is validated.
  - [ ] Add explicit Android capability APIs for audio, notifications, URL handling, printing, camera, drag-and-drop, accessibility, and secrets/keyrings.
  - [x] Reject broad all-files access as the default. Use user-selected Storage Access Framework documents and trees; reconsider an optional advanced flow only with a concrete compatibility requirement.
- [ ] Complete platform compatibility.
  - Rebuild or relocate the bundled x86_64 manager tools and glibc loader so the 16 KB control-plane APK does not trigger Android's page-size warning. Package execution already rejects incompatible 4 KB upstream Arch binaries before publication.
  - Audio, notifications, URL handling, printing, camera, drag-and-drop, accessibility, secrets/keyrings, and remaining portals.
  - Broader Qt, GTK, SDL, Electron, Rust-native, XWayland, Vulkan, and zero-copy GPU validation.
  - General secondary-window policy for phone, tablet, freeform, and external displays.
- [ ] Complete end-user release and update lifecycle.
  - GitHub Actions must produce signed ABI-correct manager artifacts and checksums from a reproducible Linux build.
  - A user installing only the GitHub release APK must receive compatible manager, bridge, runtime, wrapper-template, and Terminal updates through Archphene.
  - Validate rollback/error handling and ensure self-update never selects an incompatible universal or ABI asset.
- [ ] Pass the public release validation gate.
  - Emulator phone/tablet/docked/freeform matrix, Samsung ARM64 control plane, physical x86_64 target, GrapheneOS Pixel, sustained desktop mode, dark/light, rotation, IME, lifecycle, update, uninstall, and concurrent-failure tests.
  - Publish measured manager, Terminal, generated-wrapper, shared-runtime, and per-application storage costs.

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
- [ ] Run a final public-repository audit for secrets, binaries, oversized files, stale experiment claims, duplicate docs, CI reproducibility, issue templates, and contribution instructions.

## Next project - ArchpheneOS

Start only after the Android application is complete and the end user confirms the release.

- [ ] Boot an x86_64 VM into an AOSP/GrapheneOS-derived laptop image and validate the security model before attempting Arch package integration.
- [ ] Define which GrapheneOS guarantees are device-specific and cannot be carried to generic x86 hardware.
- [ ] Design a laptop privilege model where Android applications remain sandboxed and Linux administration requires explicit escalation.