# Archphene TODO

Updated: 2026-07-15

This is the prioritized completion queue for the Archphene Android application. Check items only after implementation and device validation. Historical experiment notes belong in research/; current behavior belongs in docs/.

## P0 - Public release blockers

- [ ] Complete general on-device package transactions.
  - [x] Flow the selected desktop-entry display name/executable and detected runtime toolkit through generic wrapper assembly.
  - [ ] Complete selected icon, MIME/document, ABI, and required bridge-capability metadata.
  - [ ] Generate package-specific Android metadata and resources.
  - [x] Persist and display bounded structured phase diagnostics, including legacy-job migration.
  - [ ] Prove with real packages that one failed transaction does not block unrelated jobs.
- [ ] Complete AArch64 package runtime support.
  - Integrate Arch Linux ARM repositories, keyrings, dependency resolution, and package verification.
  - Publish separate x86_64 and arm64-v8a release assets; accept any data packages but require matching ABI for native ELF files.
- [ ] Complete runtime-pack lifecycle safety.
  - Add running-process leases, external-uninstall reconciliation, explicit grant revocation, unchanged-closure reuse, 4 KB/16 KB page validation, and process-tree cleanup.
- [ ] Finish the Terminal product.
  - Add multiple sessions/tabs, foreground-service lifecycle, manager progress returned to the invoking command, and persisted project-tree mappings.
  - Select and package the user shell. Current bootstrap is Bionic sh; evaluate verified Arch bash first, then fish as an optional user choice.
  - Keep the native Termux terminal renderer unless image protocols or other modern terminal features justify a compatible extension.
- [ ] Complete Android capability and document brokers.
  - Add manager-owned user documents, multi-document conflict handling, persisted folder grants, and explicit permission/capability APIs.
  - Decide whether an optional broad file-access flow is justified. Prefer user-selected Storage Access Framework trees; do not request all-files access by default.
- [ ] Complete platform compatibility.
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