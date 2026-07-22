# Archphene TODO

Updated: 2026-07-22

This is the prioritized completion queue for the Archphene Android application. Check items only after implementation and device validation. Historical experiment notes belong in research/; current behavior belongs in docs/.

## P0 - Public release blockers

- [ ] Complete post-migration Bash validation across every documented build and test entry point.
  - [x] Pass Bash syntax, public-repository, release-workflow, AT-SPI source, and shared Android-test helper contracts.
  - [x] Provision KCalc and Mousepad reproducibly through real on-device manager transactions on the x86_64 emulator.
  - [x] Pass the complete current-source x86_64 emulator regression in sequence, including manager update/install, runtime-pack lifecycle, KCalc, native compositor, and Mousepad document/input/window/theme gates.
  - [x] Pass the non-destructive ARM64 physical regression on the connected Samsung for bridge startup, KCalc calculation/rotation/resize, compositor completion, and FD/process cleanup.
  - [x] Fix migration defects found by those gates: `set -u` initialization, SIGPIPE-sensitive comparisons, substring UI matches, warm-intent delivery, deterministic cold launches, real skip-install behavior, keyboard education handling, and Mousepad menu/window routing.
  - [ ] Run the remaining standalone scripts not covered by the two broad regression entry points and confirm that each printed success claim is backed by an assertion. Hardware- and 16 KB-specific scripts remain tied to their named lanes.
    - Restore or deliberately rescope the highest-risk conversions first: secrets, real-app accessibility, accessibility capability, generated camera, Terminal project trees/home documents, GUI document broker, and drag-and-drop. A mechanical comparison found 80 old PowerShell check signals versus 5 Bash check signals in the secrets test, 51 versus 8 in real-app accessibility, 64 versus 5 in the accessibility capability test, 39 versus 8 in generated camera, and 41 versus 4 in Terminal project trees. These counts are triage signals rather than one-to-one assertion equivalence.

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
  - [x] Complete camera, accessibility, and secrets/keyrings integration.
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
- [x] Validate core raw Wayland interoperability with official unmodified diagnostic packages.
  - [x] Run official `wev` packages on x86_64 emulator and physical AArch64 through generated wrappers; validate pointer, horizontal/vertical wheel, touch, keyboard, modifiers, repeat, focus loss/restoration, and graceful close.
  - [x] Run official `wl-clipboard` packages on both devices; validate exact bidirectional plain-text transfer, focused selection ownership, demand-driven Android reads, Linux-to-Android source delivery, and no unsolicited clipboard reads.
  - [x] Route verified package subprocess commands through the trusted glibc loader and provide Android-compatible POSIX shared-memory names without making wrapper-private runtime files executable.
- [ ] Complete platform compatibility.
  - [x] Keep the manager usable on 16 KB x86_64 without Android's generic page-size dialog; show an explicit in-app restriction and block package transactions before incompatible 4 KB Arch ELF execution. Rebuilding the upstream x86_64 runtime remains a broader package-compatibility task.
  - [x] Build patched x86_64 glibc with 64 KB ELF alignment and execute an aligned dynamic probe under the manager UID on a real 16 KB Android emulator.
  - [ ] Build and sign a separate no-mixing 16 KB x86_64 package repository, then validate a complete GUI closure including late-loaded modules before enabling package transactions.
  - [ ] Complete remaining portal and broad toolkit/runtime validation.
  - [ ] Broaden Qt, GTK, SDL, Electron, Rust-native, XWayland, Vulkan, and zero-copy GPU validation.
    - [x] Include runtime-loaded Vulkan loader aliases, compose a separately managed `vulkan-swrast` dependency pack, and validate unmodified Arch `vulkaninfo` through llvmpipe device discovery plus confirmed removal/reinstall on x86_64.
    - [ ] Extend the generic runtime-pack model beyond shared libraries, source commands, and `/usr/share`: the real `code` transaction resolves, verifies, extracts, and classifies its 36-package closure, then fails closed because Electron requires package-owned `/usr/lib/<app>` data and dependency executables such as `electron42`. Preserve verified symlink semantics without adding a Code-specific bypass, then continue into Chromium sandbox and Ozone/Wayland validation.
  - [x] Detect mid-session virgl helper loss and restart the payload once with llvmpipe while preserving the Android Activity; validate by same-UID fault injection against a manager-generated GLMark2 wrapper.
  - [x] Bound the shared xdg_toplevel registry, reject cyclic/cross-client parent chains, clear destroyed-parent references, and validate a real GTK child control/close/parent-restoration flow.
  - [ ] Complete the general secondary-window policy for phone, tablet, freeform, and external displays.
- [x] Complete end-user release and update lifecycle.
  - [x] Configure a commit-pinned, reproducible Linux workflow that creates a draft, builds and verifies signed x86_64 and arm64-v8a manager/Terminal/runtime artifacts, uploads checksums, and publishes only after all assets exist.
  - [x] Require exact-ABI self-update assets; reject ABI-neutral and wrong-ABI releases on 4 KB x86_64, 16 KB x86_64, and physical AArch64.
  - [x] Validate local Android update confirmation, restart reconciliation, signed downgrade rejection, checksum rejection, and retention of the installed version after failures.
  - [x] Publish `v1.0.1`, verify both ABI artifacts and embedded companions, then run the exact-ABI live update and the real published `v1.0.0` x86 migration regression.
- [ ] Pass the public release validation gate.
  - [x] Emulator phone/tablet/docked/freeform matrix with dark/light, rotation, IME, lifecycle, update, uninstall, and concurrent-failure tests.
  - [x] Samsung ARM64 control plane and sustained phone/freeform regression.
  - [ ] Install and run the exact current-source ARM64 manager on the physical device. The attached Samsung's existing manager data is signer-bound to a different development certificate; replacing it requires either the original key or an explicit app-data reset.
  - [ ] Physical x86_64 Android target.
  - [ ] Supported GrapheneOS Pixel and sustained external-display desktop mode.
  - [x] Add reproducible Android storage measurement that separates APK, installed code, persistent app data, transient execution cache, shared runtime blobs, pack metadata, downloads, and staging.
  - [x] Publish clean-install manager, Terminal, generated-wrapper, shared-runtime, and per-application storage costs; distinguish steady-state data from transient loader cache and clearable archives.
  - [x] Validate descriptor-library runtime paths with unmodified Qt and GTK applications. Stock glibc cannot resolve sonames through Android `/proc/self/fd` links, so retain the bounded transient named-module cache; normal KCalc and Mousepad launches remain healthy after the fail-closed probes.

## P1 - Product and UX

- [x] Refresh the manager UI against current Obtainium while retaining the user-selected bottom Apps/Settings navigation and centered Add action.
  - Compact rows use one stateful action, full-width phase progress, bounded version labels, accessible controls, and enough bottom padding on phone and tablet layouts.
- [x] Refine search controls and empty/loading/error states using the same compact visual language.
- [ ] Finish Qt and GTK appearance consistency.
  - [x] Reproduce and document the July visual regressions: KCalc menu/status metrics are not phone-touch sized, while Mousepad combines dark foregrounds with light GTK surfaces and makes Preferences unreadable. Audit the current Qt, GTK, compositor, and test ownership boundaries against upstream Qt, GTK, KDE, Hyprland, niri, and Wayland contracts.
  - [x] Remove the partial high-priority GTK recoloring that mixed Adwaita surfaces and Archphene foregrounds. Keep Adwaita responsible for complete light/dark widget states, restrict generated CSS to sizing/popup decoration, and expose Material You through semantic accent color names.
  - [x] Add display-aware Auto plus explicit 18, 20, and 22 dp visible-control policy independently from geometry, text scaling, and the larger 32/40/48 dp interaction targets; feed both metrics into GTK and the Qt style.
  - [x] Replace the appearance cycle-buttons with described, accessible discrete sliders for geometry, text, and control density; make all three fresh-install defaults Auto, expose 18/20/22 dp visible controls with 20 dp phone Auto, and retain explicit 100-200% text sizing.
  - [x] Publish Manager appearance changes to running wrappers and prove that Mousepad applies scale, text, and control CSS live without changing its Android or Linux PID. Keep reopen guidance explicit for toolkits that cannot fully relayout launch-time scale state.
  - [x] Validate the public slider endpoints on current-source emulator wrappers: Mousepad changes live from 100%/18 dp visible controls to 200%/22 dp with stable Android and Linux PIDs while retaining 32/48 dp interaction targets, and KCalc launches with the rebuilt 42 pt Qt policy. Rebuild and checksum the Qt plugins for x86_64 and AArch64.
  - [x] Build and install the current wrapper/manager artifacts through real manager package transactions; visually validate the GTK ownership fix, Qt target/status metrics, and direct-Wayland adapter on the emulator.
  - [ ] Replace the global `QT_SCALE_FACTOR` compatibility shim with compositor-advertised logical size/fractional output scale, including live moves between phone, tablet, freeform, and external displays.
  - [x] Add fail-closed visual artifacts and semantic assertions for contrast, enabled/disabled state, control targets, clipping, overlap, popup constraints, focus, and safe secondary-window geometry. The gates combine scoped logs, actual Wayland content geometry, accessibility trees, toolkit configuration, raw/PNG frames, and manifests.
  - [ ] Pass KCalc and Mousepad in light/dark and Auto/18/20/22 dp visible-control modes across phone, tablet, and docked layouts; repeat current-source core cases on physical AArch64.
    - [x] Current-source x86_64 emulator matrices pass for both apps, including live system light/dark, phone/tablet/docked automatic density, all explicit phone density modes, real accessibility trees, KCalc menus/calculation, and Mousepad Preferences/IME/document/touch flows.
    - [ ] Repeat current-source core cases on physical AArch64. The connected Samsung's installed manager/wrappers use the earlier development signer; do not erase its manager key and existing app sandboxes merely to replace that signer.
      - The non-destructive July 22 physical audit reconfirmed the signer split (`d088…b312` installed versus `1a3e…2338` current). Its older Mousepad wrapper exposes a recovery-dialog viewport/presentation mismatch and a stale black host after the Linux process exits; current-source installation remains required before claiming parity.
    - [ ] Define a generic overflow/panning policy for fixed desktop layouts at the explicit 200% phone endpoint. Toolkit metrics are correct, but Mousepad's menubar exceeds the phone width and KCalc's status text can clip; keep the maximum available for accessibility and do not add app-specific patches.
  - [x] Reproduce Foot's direct-Wayland phone regression: the existing launch smoke accepted an approximately 8 pt terminal font and 26 px client-decoration buttons. Add a stable Foot configuration adapter with Android-sized text, theme/accent colors, padding, and density-specific CSD targets while preserving a user override file.
  - [ ] Validate the rebuilt Foot adapter across phone/tablet/docked layouts, then complete Unicode/compose, selection, clipboard, scrollback, hardware keyboard, and lifecycle cases.
    - [x] Current-source emulator density matrices and the focused PTY gate pass with a 42 px phone font, 126 px touch CSD controls, bounded frames, readable dark/light palettes, stable processes, and visible command output. Android system light/dark now updates a running Foot process through its supported signals without restarting Foot or its shell; the supervisor records and signals only the exact target PID.
    - [ ] Complete Foot-specific Unicode/compose, selection, scrollback, resize, and destructive lifecycle flows; the generic `wev` hardware/modifier/repeat and `wl-clipboard` bidirectional protocol suites already pass.
  - [ ] Pass Foot direct-Wayland, GLMark2 accelerated, and Vulkan-frontier regressions before expanding the daily-use acceptance run to complete Kate and Code workflows.
    - [x] Foot visual/live-theme and GLMark2 moving-frame GPU gates pass on the current x86_64 emulator build.
    - [ ] Keep Vulkan presentation unclaimed: unmodified `vulkaninfo` enumerates llvmpipe, but no Android-backed ICD/Venus path or `vkcube-wayland` presentation exists.
    - [ ] Continue Code after the generic `/usr/lib/<app>` data/dependency-command pack model is implemented; the current transaction fails closed before wrapper creation with the exact unsupported symlink path.
  - [x] Physical AArch64 dark/light validation covers KCalc and Mousepad at the automatic 150% phone geometry scale and 17 pt toolkit text.
  - [x] Qt 6/KDE, GTK 3, and GTK 4/libadwaita system-theme changes repalette running widgets without restarting the Linux process; raw screenshot-pixel regressions inspect the Linux surface and reject Android-chrome-only false positives.
  - [x] Prove on current-source x86_64 KCalc, Kate, Mousepad, and GNOME Text Editor that explicit manager light/dark choices override the opposite Android mode and Material You changes generated semantic colors and rendered app pixels.
  - [x] Keep the representative KCalc menus/status, Mousepad Preferences/popup/close controls, and Foot CSD/text readable and bounded on the current emulator build; tablet/docked density remains configurable in Appearance settings and the visual gates now reject chrome-only or screenshot-change false positives.
  - [x] Scale generic GTK check/radio indicators and title-button glyphs independently from their touch targets. Current-source Mousepad now renders legible checked/unchecked controls and close affordances in light/dark mode, preserves density-specific phone/tablet/docked metrics, and finishes its Android host when the primary Linux window exits instead of leaving a stale frame.
  - [ ] Rebuild the current Qt platform-theme change for AArch64 from the pinned ARM Qt development runtime, then validate current-source physical manager overrides and Material You without replacing signer-bound user data.
  - [ ] Restore the missing GLib development dependency in the GTK settings bridge's declared clean container and rerun its reproducible x86_64/AArch64 build.
- [ ] Execute the release-gate representatives in `docs/compatibility-matrix.md`.
  - [x] Promote the reviewed package matrix into maintained documentation and separate non-normative research candidates.
  - [x] Run the first x86_64 candidate wave through official resolution/signature verification and complete-closure wrapper installation; capture GTK 4, complex Qt, and native-Wayland terminal results without promoting launch-only evidence to validated support.
  - [ ] Complete `gnome-text-editor` document, clipboard, composition, accessibility, cold-lifecycle, and current-source physical AArch64 workflows; basic IME, popup, resize, and warm-resume pass on the emulator, while a differently signed physical build launches and cold-reopens an existing document.
  - [x] Reconcile manager-validated auxiliary shebang commands with the wrapper materializer without weakening ELF-only validation for the program, loader, or libraries; the original Kate launch exception is gone.
  - [x] Adopt daemonized Linux GUI descendants beneath an Activity-tied subreaper supervisor. Current-source x86_64 Kate now maps its full UI, retains stable Android/Linux processes through tablet portrait/landscape changes, and renders on a temporary 1920x1080 emulator display where targeted pointer and keyboard input create text.
  - [ ] Complete Kate tabs, split views, large documents, dialogs, sessions, secondary Linux windows, save/reopen, destructive lifecycle, and current-source physical AArch64 workflows.
  - [x] Repair Foot child-shell linkage and runtime data on current-source x86_64 by publishing verified Bash, staging X11 locale data, selecting `C.UTF-8`, and supplying a monospace fallback. A live Bash PTY maps without the former linkage/locale/font warnings and executes typed input without process churn.
  - [ ] Complete Foot Unicode/compose, hardware keyboard, modifiers/repeat, scrolling, selection, clipboard, resize, destructive lifecycle, and current-source physical AArch64 workflows.
  - [ ] Complete the SuperTux SDL suite; a differently signed physical build maps and renders its title screen and first-run modal, while sustained rendering, audio focus, controls/pointer capture, pause/resume, fullscreen, and current-source parity remain.
  - [ ] Test the remaining representative phone, tablet, docked, GPU, document, multimedia, accessibility, and failure cases.

## P2 - Documentation and repository readiness

- [x] Add deterministic package and installed-app search ranking.
  - Exact package, executable ownership, prefix/token, and description matches are ranked; glmark2-es2-wayland resolves to glmark2.
- [x] Document non-root Android Terminal limitations in docs/terminal-apps.md.
- [x] Ignore repository-local generated tooling and external reference checkouts.
- [x] Review the remaining untracked package matrix, generated artifacts, licenses, and source provenance before tracking or deleting anything.
  - The pre-work tree had no untracked files, generated build outputs remain ignored, and the 2026-07-22 public-repository audit passed 575 tracked paths, prebuilt checksums, licensing/community-file policy, secret/size checks, and commit-pinned Actions.
- [ ] Update README, focused user docs, roadmap, project status, changelog, and release notes after P0/P1 behavior is final.
- [ ] Run a final public-repository audit after P0/P1 behavior is final.
  - [x] Add an automated source audit for required community files, stale licensing claims, generated/secret/release artifacts, oversized tracked files, private-key material, prebuilt manifests/checksums, and commit-pinned Actions.
  - [ ] Recheck stale experiment claims, duplicate docs, source provenance, release notes, and CI results immediately before publication.

## Next project - ArchpheneOS

Start only after the Android application is complete and the end user confirms the release.

- [ ] Boot an x86_64 VM into an AOSP/GrapheneOS-derived laptop image and validate the security model before attempting Arch package integration.
- [ ] Define which GrapheneOS guarantees are device-specific and cannot be carried to generic x86 hardware without additional work.
- [ ] Design a laptop privilege model where Android applications remain sandboxed and Linux administration requires explicit escalation.
