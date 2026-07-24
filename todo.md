# Archphene TODO

Updated: 2026-07-24

This file is the remaining prioritized work, not a history of completed tests. Validated behavior belongs in `docs/project-status.md`, `docs/compatibility-matrix.md`, and `research/experiments/`.

## Product target

Archphene should feel like one normal, user-owned Arch Linux installation inside the Archphene Android app:

- Pacman and AUR packages share one filesystem, home, package database, toolchain, and process environment.
- Graphical Linux packages appear individually in Android's app drawer through thin launcher wrappers, but run inside the shared Arch environment.
- CLI tools installed by one package are immediately available to every terminal and graphical application.
- Android remains responsible for app installation confirmation, permissions, lifecycle, display/input, and access to Android files.
- Archphene generically adapts Wayland, Qt, GTK, SDL, Electron, and terminal applications; it does not ship application-specific source patches.

The first daily-use acceptance target is VS Code with `dotnet-sdk`: create an MVC project in the integrated terminal, debug it with breakpoints, run it, and open the local web application in Android's browser.

## P0 - Greenfield Rust + Kotlin implementation

- [x] Choose the production language boundary.
  - Rust owns the shared Arch runtime, package engine, process supervision, storage synchronization, compositor, input state, rendering, and persistent operation state.
  - Kotlin owns the Android application shell: activities, services, lifecycle, permissions, PackageInstaller, SAF/DocumentsProvider, IME, accessibility, notifications, and system UI.
  - Legacy Java/C/Rust prototypes remain as reference until replacements pass their gates; preserving installed prototype state is not required.
- [x] Establish the production source tree without adding new features to `prototypes/`:

  ```text
  android/app/                 Kotlin Android shell
  crates/archphene-core/       platform-independent Rust domain/runtime core
  crates/archphene-android/    small Android/JNI adapter
  crates/archphene-*/          bounded feature crates added only as needed
  tests/                       host contracts and cross-boundary fixtures
  ```

- [ ] Pin reproducible Rust, Kotlin, Android Gradle Plugin, Gradle, JDK, SDK, and NDK versions; build offline from verified caches after initial provisioning.
  - [x] Start with Rust 1.88, Kotlin built into AGP 9.3, Gradle 9.6.1, the installed JDK 26, SDK/Build Tools 36, and NDK 29. Use JDK 17 only if a measured compatibility failure requires AGP's documented minimum/default JVM.
- [x] Build the first vertical slice.
  - [x] Kotlin Activity binds to one Archphene runtime Service.
  - [x] The Service owns exactly one Rust runtime handle and survives Activity recreation.
  - [x] Rust exposes a versioned ABI and fixed-format status snapshot.
  - [x] Kotlin reuses direct buffers for batched commands/events and never transfers rendered frames as Java arrays or Bitmaps.
  - [x] Clean shutdown is idempotent and releases every native handle; the base owns no descriptors, worker threads, or child processes yet.
  - [x] Host unit tests, allocation gate, Rust Android cross-builds, Kotlin compilation, APK packaging/alignment, emulator installation, and Samsung installation pass.
  - [x] Full-device emulator and Samsung gates prove bounded touch batching, visible insets, Activity recreation, HOME/resume continuity, and normal Back shutdown while restoring screen and rotation state.
- [ ] Add capabilities in test-gated vertical slices; do not port code merely because it existed before.
  - [x] Shared private Arch root and bootstrap
    - [x] Create and version a conventional private root under Android app storage with bounded paths and required modes.
    - [x] Reject unsafe layout entries and unknown versions; repair known directory modes on reuse.
    - [x] Bootstrap off Android's main thread and publish readiness through the fixed native snapshot.
    - [x] Host safety tests plus clean/reused-root gates pass on the emulator and Samsung using full-device screenshots.
  - [x] Package catalog/search and persistent job state
    - [x] Add a Rust-owned, fixed-size persistent operation journal with bounded fields and legal state transitions.
    - [x] Atomically publish journal updates, reject corruption/symlinks, recover interrupted work explicitly, and avoid warmed in-memory heap allocation.
    - [x] Bootstrap and reuse the empty journal on the emulator and Samsung; report readiness through the native snapshot.
    - [x] Package the verified pacman runtime as content-addressed exact-ABI native payloads, validate its signed-APK manifest, and execute the real pacman binary through its patched glibc loader.
    - [x] Emit 38 MiB x86_64 and 36 MiB arm64-v8a compressed debug APKs instead of a 168 MB universal test artifact; Android extracts the executable runtime and signed repository keyring to device filesystem paths at installation.
    - [x] Pass clean/reused-root, lifecycle, input, log, and full-device visual gates with real pacman execution on the emulator and Samsung.
    - [x] Connect authoritative repository catalogs and expose real search results.
      - [x] Android's HTTPS stack transports bytes from Rust-selected, exact-ABI official endpoints through one bounded file descriptor; Rust owns temporary-file safety, size limits, sync, atomic publication, modes, and readiness.
      - [x] Rust executes bounded read-only pacman searches off the UI thread, validates and normalizes result fields, caps output, and treats empty pacman exit 1 as a normal no-results state.
      - [x] Clean catalog refresh, package search, process-death reuse, scoped logs, and full-device screenshots pass with `dotnet-sdk` on x86_64 and `btop` on Samsung AArch64. The official ARM repositories currently have no `dotnet-sdk` result.
    - [x] Resolve an exact package and its real dependency closure without mutating the root.
      - [x] Pacman emits repository, package, version, archive, exact HTTPS URL, and download size through a fixed response; Rust rejects untrusted endpoints, unsafe fields, duplicates, missing targets, oversized closures, and malformed output.
      - [x] Kotlin renders the target, package count, bounded download size, and closure off the main thread. Process-death reuse, scoped logs, and full-device screenshots pass for the 33-package `dotnet-sdk` x86_64 closure and 9-package `btop` AArch64 closure.
    - [x] Queue real signed-package operations from the Kotlin UI and render every durable phase immediately.
      - [x] The UI persists Queued, Resolving, Downloading, Verifying, Publishing, Complete, and Failed transitions before rendering them; installation is also rendered explicitly, and the latest result survives Activity and manager process death.
      - [x] Android transports exact official archives and detached signatures through Rust-owned bounded descriptors. Rust enforces resolved sizes, atomic mode-0600 cache publication, signed name/version/architecture identity, the packaged Arch keyring, and the pinned Arch Linux ARM build signer.
      - [x] Current `btop` closures install on the emulator and Samsung; deliberate cache tampering is rejected, redownloaded, and reverified on both, with scoped logs and full-device screenshots.
  - [ ] Pacman install/update/remove
    - [x] Install a bounded exact official package closure into the shared Arch root only after re-verifying every cached signature and package identity.
    - [x] Adapt the generic runtime to Android app-UID ownership, SELinux hard-link denial, app seccomp's blocked `fchmodat2`, and the explicit-loader environment without modifying individual packages.
    - [x] Commit dependency-ordered package transactions durably, recover a stale database lock or incomplete current package entry after interruption, and prove the requested package through pacman's local database.
    - [x] Pass clean full-device `btop` install, cache-tamper, process-death, executable, and package-database gates on the x86_64 emulator and AArch64 Samsung.
    - [x] Query exact installed versions, preserve explicit/dependency install reasons, reconcile a current signed package, and conservatively remove only when pacman accepts the non-cascading plan.
    - [x] Pass clean full-device install, verify/tamper recovery, remove, absence, verified-cache reinstall, database-validity, and process-death gates on both targets.
    - [ ] Prove a real older-to-newer repository update, including changed dependencies and replacements, on both targets.
    - [ ] Complete hooks/scriptlets, replacements, dependency consistency checks, closure-wide rollback, orphan cleanup, cancellation, and low-storage recovery.
  - [ ] Terminal/PTY and shared command environment
    - [x] Execute one installed ELF command without a shell through the verified loader and generic path bridge, with bounded names, arguments, output, time, process group, environment, working directory, and symlink resolution.
    - [x] Pass clean `btop --version` execution, scoped-log, and full-device UI gates on the x86_64 emulator and AArch64 Samsung after signed install/remove/reinstall cycles.
    - [x] Support package-owned scripts only through conventional `/usr/bin` or `/bin` shebang interpreters that resolve to installed ELF programs inside the same root; reject Android-host and recursive script interpreters.
    - [x] Install Bash through the normal signed package flow and pass a warning-free root-contained script/argument/output gate with full-device screenshots on both targets.
    - [ ] Build the conventional interactive shared shell environment, locale policy, startup files, and user-selected command/path behavior without escaping into Android's `/system/bin`.
    - [x] Add a four-slot generation-checked PTY registry with controlling-terminal setup, bounded nonblocking direct-buffer I/O, resize, deterministic close, and process-group kill/reap on runtime destruction.
    - [x] Pass package-installed Bash PTY open, 24×80 to 40×120 resize, bidirectional marker, close/reap, scoped-log, and full-device UI gates on both targets.
    - [ ] Add user-controlled long-lived session ownership, backpressure queues, cancellation, exit status, Activity/process-death policy, and reboot recovery.
    - [ ] Replace the diagnostic command panel with production terminal rendering, scrollback, selection, IME, hardware keyboard, clipboard, and accessibility.
  - [ ] Wayland compositor, presentation, and lifecycle
  - [ ] Pointer, touch, keyboard, IME, clipboard, and drag-and-drop
  - [ ] Android file integration and `/mnt/android`
  - [ ] Launcher wrapper generation and runtime-service binding
  - [ ] Qt, GTK, native Wayland, SDL, Electron, and XWayland adaptation
  - [ ] Audio, camera, printing, notifications, URLs, secrets, and accessibility
  - [ ] GPU acceleration, external displays, and secondary windows
  - [ ] AUR builds and the VS Code + .NET acceptance workflow
- [ ] Delete legacy implementation source only after every retained capability has equivalent tests and the user approves removal.

### Performance and safety rules

- [ ] Keep JNI narrow, versioned, and coarse-grained; no per-pixel, per-object, or avoidable per-input-event JNI chatter.
- [ ] Pass file descriptors, direct buffers, shared memory, `ANativeWindow`, and HardwareBuffer handles instead of copying payloads.
- [ ] Preallocate bounded queues and reusable scratch buffers for input, frame metadata, logs, package progress, and bridge messages.
- [ ] Do not allocate or free heap objects in frame, pointer-motion, touch-motion, audio-callback, or compositor dispatch hot paths after warm-up.
- [ ] Put explicit limits on queues, strings, paths, manifests, documents, windows, processes, descriptors, and package operations; apply backpressure rather than unbounded growth.
- [ ] Confine Rust `unsafe` to reviewed FFI/syscall modules with safe wrappers, ownership documentation, null/alignment/length validation, and targeted tests.
- [ ] Keep the only global JNI state to a bounded synchronized handle registry; capability state stays in its owned runtime. Handles have generation checks, deterministic destruction, and use-after-close rejection.
- [ ] Keep blocking I/O, package work, and filesystem synchronization off Android's main thread and compositor/render threads.
- [ ] Measure cold/warm startup, RSS/PSS, Java/Kotlin allocations and GC, native allocations, JNI calls, copied bytes, frame time, input latency, descriptors, threads, and child processes.
- [ ] Add allocation-count and steady-state soak gates; performance regressions fail CI rather than becoming documentation notes.
- [ ] Use release builds, R8, baseline profiles, stripped native libraries, panic-abort, and LTO only after debug diagnostics and tests remain adequate.

### Architecture gates

- [x] Define one Archphene-owned private Arch root with conventional `/usr`, `/etc`, `/var`, `/opt`, `/home`, and `/tmp` semantics.
- [ ] Define how thin launcher APKs bind to the shared runtime service while Linux processes, packages, and files remain in that environment.
- [ ] Preserve Android launcher entries, icons, intents, windows, notifications, and lifecycle without duplicating Linux roots.
- [ ] Define supervision, background execution, daemons, resource limits, crash recovery, and shutdown.
- [ ] Define trust for pacman, AUR builds, hooks, arbitrary executables, runtime content, and launcher signing.
  - [x] Pin and seal the official pacman runtime, exact repository endpoints, Arch keyring, and bounded official signer trust used by the current install path.
  - [ ] Define the remaining AUR, hook/scriptlet, arbitrary executable, and launcher-signing policies.
- [ ] Document that packages inside the shared Arch environment intentionally share one Linux trust domain.
- [x] Wipe the emulator and Samsung prototype installations only when the new base APK is ready; retain source and any explicitly requested evidence.

## P0 - Android and Linux file integration

- [ ] Present Android-accessible storage inside Linux under:

  ```text
  /mnt/android/downloads
  /mnt/android/documents
  /mnt/android/pictures
  /mnt/android/media
  /mnt/android/shared
  ```

  - [ ] Use Android's Storage Access Framework for user-selected folders and persist grants across restarts.
  - [ ] Add familiar home links such as `~/Downloads` and `~/Documents`.
  - [ ] Clearly show unavailable, revoked, read-only, syncing, conflict, and error states instead of silently failing.
  - [ ] Decide and document where a synchronized POSIX mirror is required because SAF is not a mountable POSIX filesystem.
  - [ ] Keep package databases, builds, symlinks, executables, sockets, and other POSIX-dependent data in private Arch storage.
- [ ] Add a first-run storage flow: explain the model, let the user grant a folder, and allow skipping or changing it later.
- [ ] Expose appropriate Archphene files through a `DocumentsProvider` so Android Files, pickers, share sheets, browsers, and other apps can open and save them.
- [ ] Support Android-to-Archphene import through Open With, Share, drag-and-drop, and file-picker flows.
- [ ] Support Archphene-to-Android open, save, export, and share flows.
- [ ] Avoid `MANAGE_EXTERNAL_STORAGE` as the default; evaluate an optional advanced/sideloaded mode only if SAF cannot satisfy a demonstrated workflow.
- [ ] Test grant creation, persistence, revocation, rename, deletion, conflicts, large trees, offline providers, uninstall behavior, and malicious paths.

## P0 - Package system and shared Arch behavior

- [ ] Complete pacman transaction semantics against the shared Arch root.
  - [x] Re-verify signed archives immediately before mutation and make each dependency-ordered package commit durable and resumable.
  - [x] Recover the bounded stale-lock and incomplete-current-entry cases proven by the current install flow.
  - [x] Preserve pacman install reasons, validate the local database after mutation, and support conservative non-cascading removal with exact postcondition checks.
  - [ ] Add hooks/scriptlets, upgrades, replacements, full dependency validation, closure-wide rollback, orphan cleanup, cancellation, and storage-failure recovery.
- [ ] Add a bounded AUR workflow suitable for packages such as `visual-studio-code-bin`.
  - [ ] Show source, PKGBUILD, maintainer, signatures/checksums, build steps, permissions, and disk impact before installation.
  - [ ] Run builds as an unprivileged Linux user and clearly communicate that installed Arch/AUR packages share one trust domain.
- [ ] Make installed commands, libraries, desktop files, MIME handlers, fonts, themes, and services immediately discoverable across all Linux apps.
- [ ] Generate, update, and remove Android launcher wrappers from desktop entries without deleting shared package or user state incorrectly.
- [ ] Define ownership when several packages provide desktop entries or depend on the same files.
- [ ] Handle package upgrades, downgrades, replacements, hooks, interrupted transactions, rollback, orphan cleanup, and low-storage failures.
- [ ] Decide the bounded policy for publishing dependency commands to app processes; do not add package-specific exceptions.
- [ ] Extend the verified runtime model to package-owned `/usr/lib/<app>` trees, data, executables, and valid symlinks. This currently blocks Code/Electron.
- [ ] Cache unchanged closure analysis and wrapper inputs so repeat installs do not rescan large package trees.
- [ ] Complete and validate the separate 16 KB-aligned x86_64 package/runtime strategy before enabling transactions there.

## P0 - Manager UX and reliability

- [ ] Make every install/update/remove operation appear in the app list immediately with persistent state and progress.
  - [x] The current exact-package details flow immediately shows persistent resolve, download, verify, install, complete, and failure state.
  - [ ] Show queued, resolving, downloading, verifying, building, installing, awaiting Android confirmation, completed, failed, and cancelled states.
  - [ ] Keep state correct across rotation, backgrounding, process death, reboot, and manager restart.
  - [ ] Provide actionable diagnostics, retry/cancel controls, and package-scoped failure isolation.
- [x] Make the current package details and operation content responsive and scrollable; full-device emulator and Samsung audits show the complete `btop` closure with state-driven Install/Update/Verify and Remove actions.
- [ ] Profile and fix slow first launch after boot; display useful loading/synchronization state instead of a frozen or empty UI.
- [ ] Perform a complete UX pass over discovery, package details, installation, launcher creation, updates, storage, permissions, setup, settings, and recovery.
- [ ] Review Obtainium's source, license, screenshots, app-list structure, and update progress UI. Adapt suitable open-source patterns to Archphene's compact list, spinner, and richer phase strings without copying blindly.
- [ ] Ensure search results distinguish graphical apps, CLI tools, libraries, installed packages, available updates, AUR results, unsupported packages, and compatibility status.
- [ ] Add clear disk-use estimates and controls for package archives, shared runtime data, build caches, and user files.

## P0 - VS Code and .NET daily-use milestone

- [ ] Install Code through the generic package/AUR pipeline with no Code-specific bridge exceptions.
- [ ] Validate Electron/Chromium multiprocess startup, Ozone Wayland, sandbox behavior, rendering, IME, clipboard, dialogs, file watching, extensions, and lifecycle.
- [ ] Install `dotnet-sdk` through the same shared package system and make `dotnet` available in Code's integrated terminal.
- [ ] Create a new ASP.NET Core MVC project in shared Arch storage.
- [ ] Open the project in Code and validate editing, search, Git, terminal PTY, language services, restore/build, and extension-host subprocesses.
- [ ] Run the project under the debugger, stop at breakpoints, inspect state, continue, and restart.
- [ ] Open the served localhost URL in Android's browser and validate routing back to the running Linux process.
- [ ] Validate the workflow with touch/IME on a phone and keyboard/mouse on tablet or external display.
- [ ] Repeat the complete milestone on the x86_64 emulator and physical AArch64 Samsung using full-device screenshots and scoped logs.

## P1 - Generic desktop integration quality

- [ ] Replace global `QT_SCALE_FACTOR` compatibility behavior with compositor-advertised logical size and fractional output scale, including live display moves.
- [ ] Define a generic overflow/panning policy for fixed desktop layouts at 200% phone text scaling without app-specific patches.
- [ ] Finish reproducible AArch64 Qt/KDE and GTK settings bridge builds by pinning the required KConfig and GLib development sysroots.
- [ ] Complete secondary-window behavior for phone, tablet, freeform, and external displays.
- [ ] Validate automatic and explicit appearance settings across:
  - [ ] Qt 6/KDE
  - [ ] GTK 3
  - [ ] GTK 4/libadwaita
  - [ ] native Wayland/Foot
  - [ ] SDL
  - [ ] Electron/Chromium
  - [ ] XWayland
- [ ] Keep geometry scale, text scale, visible control size, and touch target size distinct, documented, live where supported, and predictable after relaunch.
- [ ] Validate Android light/dark, explicit Archphene override, Material You accents, font settings, phone/tablet/docked auto policy, and runtime display changes.
- [ ] Continue using full-device screenshots, rendered-pixel checks, accessibility trees, content geometry, input traces, and logs for visual claims.

## P1 - Compatibility and performance

- [ ] Complete the release representatives in `docs/compatibility-matrix.md`; package search or launch alone is not a support claim.
- [ ] Finish SuperTux gameplay, pointer capture, audio focus/interruption, and fullscreen/window transitions on emulator and Samsung.
- [ ] Validate XWayland with a representative unmodified X11 application.
- [ ] Validate an Android-backed Vulkan presentation path; keep Vulkan presentation unclaimed until `vkcube-wayland` renders.
- [ ] Add a low/zero-copy Android HardwareBuffer or dmabuf presentation path while retaining SHM fallback.
- [ ] Broaden testing to Rust-native, browser, office, creative, multimedia, accessibility, USB, and multiwindow applications.
- [ ] Validate sustained external-display use with keyboard/mouse, display hotplug, density changes, audio routing, sleep/resume, and thermal/memory pressure.

## P1 - Test and release gates

- [ ] Finish the remaining standalone Bash-script assertion audit and run each applicable entry point.
- [ ] Keep tests state-preserving by default and require explicit flags for destructive device changes.
  - [x] The signed package regression preserves state by default and requires `--clean-data` for uninstall/data reset.
- [ ] Require emulator and physical-device coverage for runtime, storage, package, input, visual, permission, and lifecycle changes.
- [ ] Capture device screenshots rather than app-only frames whenever asserting what the user sees or where touch lands.
- [ ] Add long-running upgrade, package churn, process-death, reboot, storage-pressure, network-failure, and recovery tests.
- [ ] Validate on a supported GrapheneOS Pixel and a physical x86_64 Android target before a public support claim.
- [ ] Pass the full phone, tablet, docked, GPU, document, multimedia, accessibility, failure, and release-signing matrix.

## P2 - Documentation and publication

- [ ] Update architecture and storage documentation to the approved shared-environment model; clearly mark older per-wrapper research as historical.
- [ ] Document the Android/Linux filesystem boundary, `/mnt/android`, backup/export, permissions, revocation, and uninstall consequences.
- [ ] Document normal-Arch compatibility limits imposed by Android's kernel, SELinux, seccomp, background execution, and lack of root/systemd assumptions.
- [ ] Update README, roadmap, project status, compatibility matrix, security model, changelog, and release notes after behavior is implemented.
- [ ] Run the final public-repository, provenance, licensing, secret, reproducibility, CI, and release-artifact audit.

## Later - ArchpheneOS

Start only after the Android application reaches its release gate and the user approves the next project.

- [ ] Boot an x86_64 VM into an AOSP/GrapheneOS-derived laptop image and validate its security model.
- [ ] Define which GrapheneOS guarantees depend on supported Pixel hardware and cannot transfer directly to generic x86 systems.
- [ ] Design explicit Linux administration and escalation while Android applications remain sandboxed.
