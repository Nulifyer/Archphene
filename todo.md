# Archphene TODO

Updated: 2026-07-25

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

- [x] Pin reproducible Rust, Kotlin, Android Gradle Plugin, Gradle, JDK, SDK, and NDK versions; build offline from verified caches after initial provisioning.
  - [x] Start with Rust 1.88, Kotlin built into AGP 9.3, Gradle 9.6.1, the installed JDK 26, SDK/Build Tools 36, and NDK 29. Use JDK 17 only if a measured compatibility failure requires AGP's documented minimum/default JVM.
  - [x] Commit the verified Gradle wrapper and dependency checksums, exact Rust toolchain and Android revisions, immutable native-container base, and a non-downloading local toolchain gate.
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
    - [x] Preflight the entire re-verified closure through pacman's normal dependency, conflict, and replacement preparation; reject missing, duplicate, changed-version, or unknown plan entries before mutation. Cache-only full-device gates pass on both targets.
    - [x] Commit the complete prepared archive set through one normal pacman transaction without `--nodeps` or blanket overwrite; persist and recover explicit install reasons across manager death. Cache-only remove/reinstall and restart-recovery gates pass on both targets.
    - [x] Allow cancellation while work is queued, resolving, downloading, or verifying; disconnect an active transfer, delete partial payloads, persist a terminal Cancelled result, and disable cancellation before pacman mutation. Cache-only cancellation and normal-commit regressions pass on both targets.
    - [ ] Prove a real older-to-newer repository update, including changed dependencies and replacements, on both targets.
    - [ ] Complete hooks/scriptlets, proven replacement handling, failure rollback/recovery, orphan cleanup, and low-storage recovery.
  - [ ] Terminal/PTY and shared command environment
    - [x] Execute one installed ELF command without a shell through the verified loader and generic path bridge, with bounded names, arguments, output, time, process group, environment, working directory, and symlink resolution.
    - [x] Pass clean `btop --version` execution, scoped-log, and full-device UI gates on the x86_64 emulator and AArch64 Samsung after signed install/remove/reinstall cycles.
    - [x] Support package-owned scripts only through conventional `/usr/bin` or `/bin` shebang interpreters that resolve to installed ELF programs inside the same root; reject Android-host and recursive script interpreters.
    - [x] Install Bash through the normal signed package flow and pass a warning-free root-contained script/argument/output gate with full-device screenshots on both targets.
    - [ ] Build the conventional interactive shared shell environment, locale policy, startup files, and user-selected command/path behavior without escaping into Android's `/system/bin`.
      - [x] Keep package-installed Bash interactive in a controlling PTY while Archphene owns line editing; disable Bash's redundant Readline path, which Android app seccomp kills while idle.
      - [x] Publish conventional `/home/archphene`, `/tmp`, XDG, and `/usr` PATH values; map `getcwd` back into the Linux root; activate the installed `C.utf8` data; and create user-owned `.bashrc`/`.bash_profile` defaults once without overwriting edits.
      - [x] Pass root-contained multibyte script plus interactive prompt, HOME, PWD, PATH, UTF-8 charmap, Activity recreation, scoped-log, and full-device gates on both targets.
      - [x] Discover declared, safely installed Bash/POSIX-shell adapters in Rust; expose a bounded Kotlin selector; persist the stable choice; refresh it after package mutation; lock it during a session; and pass exact-ABI process-restart, PTY, scoped-log, and full-device gates on both targets.
      - [ ] Add reviewed startup adapters for additional installed shells, user-editing UI/document access for shell startup files, and a documented PATH extension policy.
      - [ ] Provide a bounded, sandbox-scoped Linux `/proc`, `/sys`, and `/dev` compatibility view where Android SELinux hides host pseudo-files; do not expose Android-wide processes or fabricate unsupported monitoring guarantees.
    - [x] Add a four-slot generation-checked PTY registry with controlling-terminal setup, bounded nonblocking direct-buffer I/O, resize, deterministic close, and process-group kill/reap on runtime destruction.
    - [x] Pass package-installed Bash PTY open, 24×80 to 40×120 resize, bidirectional marker, close/reap, scoped-log, and full-device UI gates on both targets.
    - [x] Add user-controlled long-lived session ownership, backpressure queues, cancellation, exit status, Activity/process-death policy, and reboot recovery.
      - [x] Make the Service own a user-started package-installed Bash session across Activity recreation, with bounded fixed input/output rings, reusable direct I/O buffers, explicit stop, process-group reap, and preserved signed exit status.
      - [x] Pass two-command, rotation/rebind, retained-output, `exit 7`, restart/stop, child-reap, scoped-log, and full-device screenshot gates on the x86_64 emulator and AArch64 Samsung.
      - [x] Replace the 100 ms Kotlin polling loop with a Rust-owned `poll(2)` pump, fixed per-session wake channel, write-readiness backpressure, and coarse 16 KiB read batches; pass the full lifecycle gate on both targets.
      - [x] Keep a user-started shell alive across Home, Back, and task removal in a special-use foreground Service with a visible low-priority notification, open-app route, explicit Stop action, and notification permission requested at the user action.
      - [x] Prove identical manager/Linux process ownership, retained output, foreground state, notification title/Stop route, cleanup, and full-device visuals across Home and Back on both targets.
      - [x] Atomically publish a bounded Rust-owned active-session marker, remove it on clean close, surface abrupt manager death as an interrupted session, and require explicit restart rather than pretending a lost PTY was resumed.
      - [x] Pass same-UID `SIGKILL`, interrupted-state, retained-Home restart, clean-marker removal, and full-device screenshot gates on both targets.
      - [x] Validate the same interruption/restart policy across a real emulator and physical-device reboot, including durable marker retention, full-device visuals, explicit restart, and clean-marker removal.
    - [ ] Replace the diagnostic command panel with production terminal rendering, scrollback, selection, IME, hardware keyboard, clipboard, and accessibility.
      - [x] Establish a dependency-free Rust terminal-state core with bounded grids, strict streaming UTF-8, delayed VT autowrap, cursor/erase/scroll-region/basic SGR handling, bounded control strings, resize preservation, dirty-row tracking, and a warmed zero-allocation gate.
      - [x] Connect every exact PTY read and resize to session-owned Rust terminal state, then expose bounded, versioned coarse dirty-row snapshots through one direct-buffer JNI call without per-cell JNI traffic or warmed heap allocation.
      - [x] Consume terminal snapshots in a frame-paced Android renderer with one Service-owned reusable direct buffer, dimension-bound primitive cell storage, revision-gated JNI reads, and cached RenderNode rows that are re-recorded only when damaged.
      - [x] Size the PTY from measured 14sp monospace cells, give active sessions the available phone/tablet viewport, preserve cursor-adjacent rows when shrinking, and reconstruct a fresh Activity from one explicit full snapshot after rotation, Home, or Back.
      - [x] Expose a bounded terminal accessibility snapshot only when Android queries the node, keeping the normal frame path free of synthesized terminal strings.
      - [x] Route terminal focus through a full-editor Android `InputConnection` with local preedit display, bounded UTF-8 commit/delete delivery, and direct hardware Enter/Backspace/Tab/Escape/navigation/function/Ctrl/Alt handling without per-keystroke byte-array allocation.
      - [x] Prove direct text, Backspace correction, Enter execution, PTY delivery, rendering, accessibility, IME attachment, lifecycle, and full-device visuals with exact-ABI APKs on the emulator and Samsung.
      - [x] Preallocate bounded primary and alternate grids, implement DEC `47`/`1047`/`1049` screen switching plus cursor visibility/save/restore, resize both grids, and preserve warmed zero-allocation screen changes.
      - [x] Prove installed `tput` alternate-screen clearing, content, discard, exact primary restoration, scoped logs, and full-device visuals on both exact-ABI targets.
      - [x] Publish application cursor/keypad, bracketed-paste, newline, and backarrow modes through the existing damage flags; select static mode-correct Android hardware and IME sequences without extra JNI or per-key allocation.
      - [x] Prove exact normal `ESC [ A` and application `ESC O A` hardware Up sequences through installed `tput`, Bash byte capture, accessibility, scoped logs, and full-device screenshots on both ABIs.
      - [x] Implement bounded insert/delete/erase character and line operations, region scroll/reverse-index, repeat, insert mode, extended cursor movement/save/restore, and fixed-capacity programmable tab stops.
      - [x] Exercise the editing controls in the warmed zero-allocation gate, then prove installed `tput` ICH/DCH/IL/DL behavior through exact-ABI PTYs, accessibility, scoped logs, and visually inspected full-device screenshots on both targets.
      - [x] Preserve full-byte foreground/background indexes, render the fixed 256-color palette, and parse indexed SGR through the coarse damage/JNI path.
      - [x] Consume G0/G1 character-set designation correctly, render DEC special-graphics line drawing, and prove installed `tput` colors, background reset, line drawing, accessibility, scoped logs, and full-device visuals on both exact-ABI targets.
      - [x] Version the coarse damage protocol to 16-byte cells, preserve indexed colors or exact 24-bit RGB without palette quantization, and keep attributes compacted into the Android foreground array rather than adding another maximum-grid allocation.
      - [x] Prove distinct indexed/direct-RGB wire values, warmed zero allocation, exact `#123456` on `#abcdef`, unchanged 256-color/DEC rendering, broad PTY behavior, scoped logs, and visually inspected full-device screenshots on both ABIs.
      - [x] Add bounded Android clipboard paste through a long-press touch menu, `Ctrl+Shift+V`, InputConnection context action, and accessibility action; preserve literal UTF-8/newlines and wrap it only when the terminal publishes bracketed-paste mode.
      - [x] Prove normal and exact `ESC [ 200 ~`/`ESC [ 201 ~` bracketed bytes through Android clipboard, touch/hardware input, Bash capture, accessibility, scoped logs, and full-device screenshots on both ABIs.
      - [x] Follow Android's scaled 16sp baseline by default and add persistent bounded 10–32sp terminal sizing through pinch, long-press controls, and hardware `Ctrl+-`/`Ctrl++`/`Ctrl+0`; defer metric re-recording and PTY resize until pinch completion.
      - [x] Prove Auto, explicit 20sp, hardware/touch controls, process-restart persistence, reset, scoped logs, and visually inspected full-device screenshots on both exact-ABI targets.
      - [x] Pin `unicode-width` and `unicode-segmentation`, retain at most 16 Unicode scalar values per grapheme, and keep streaming width/segmentation plus protocol publication allocation-free after warm-up.
      - [x] Version terminal damage to bounded grapheme cells; render combining text, CJK, flags, emoji modifiers, and ZWJ families through reusable primitive/scratch storage while preserving one/two-column geometry and normalizing wide cells across edits and resize.
      - [x] Implement DEC origin and autowrap modes, then prove Unicode, exact last-column placement, margin-relative cursor positioning, accessibility, scoped logs, and full-device visuals on both exact-ABI targets.
      - [x] Add a preallocated 4 MiB/4,096-line primary-screen scrollback ring with bounded compact grapheme cells; exclude alternate-screen and partial-margin scrolling and preserve warmed zero-allocation output.
      - [x] Version damage with bounded history/viewport state, hide the cursor outside the live viewport, retain the viewed position while new rows arrive, and expose history through touch, mouse wheel, Shift+PageUp/PageDown, and Android accessibility actions.
      - [x] Prove visible history, return to live output, touch, wheel, hardware-page, accessibility, scoped-log, and full-device behavior on the exact-ABI emulator and Samsung builds; rerun broad PTY, color, editing, and Unicode gates on both.
      - [x] Add bounded visible-viewport word selection with touch-drag extension, exact cell highlighting, 2 KiB grapheme/newline copy, long-press Copy, `Ctrl+Shift+C`, InputConnection Copy, and accessibility Copy; clear stale selection on output, resize, scroll, or input.
      - [x] Prove long-press word selection, visible highlighting, Android Copy, exact clipboard paste-back, existing Paste, scroll gestures, scoped logs, and full-device visuals on both exact-ABI targets.
      - [x] Make overlong grapheme behavior explicit and bounded: retain at most 16 scalars, replace the truncated tail visibly, discard subsequent zero-width extenders, and resume at the next printable cell.
      - [ ] Complete remaining xterm controls/modes, logical soft-wrap joining and resize reflow across screen/history, selection handles/autoscroll and history-stable ranges, non-Latin/composing IME coverage, and richer accessibility before calling the surface production-ready.
      - [x] Replace the clipped temporary diagnostic strip during active sessions with the measured full-height terminal surface; retain emulator and Samsung full-device portrait/landscape/rebind evidence.
  - [ ] Wayland compositor, presentation, and lifecycle
    - [x] Run a real package-installed native Wayland client through the authenticated generated launcher, private manager-owned socket, shared Arch root, and manager-owned process group on physical AArch64.
    - [x] Separate Android physical pixels from Wayland logical coordinates, advertise integer and fractional output scale, tile the primary toplevel, preserve client-resolution subsurface rasters, reuse the steady output canvas, and fit the launcher around Android system bars and the visible IME. Foot passes crisp full-device inspection at 1080x2202 portrait, 1080x1343 with the Samsung keyboard, and 2241x978 landscape.
    - [ ] Replace remaining SHM snapshot/copy work with damage-driven retained buffers and HardwareBuffer/dmabuf paths before claiming sustained high-frame-rate or Vulkan production performance.
    - [ ] Repeat the real-client density, live-resize, lifecycle, and visual gate on the x86_64 emulator.
  - [ ] Pointer, touch, keyboard, IME, clipboard, and drag-and-drop
    - [x] Bridge authenticated `zwp_text_input_v3` state to a wrapper-owned Android `InputConnection`, including bounded UTF-8 surrounding text, preedit/commit, deletion, editor actions, content hints/purpose, keyboard show/hide, and deterministic detach. Real Samsung Foot accepts text from the Samsung IME.
    - [x] Transform Android touch and pointer coordinates from the physical Surface into the logical Wayland viewport so input and rendered content use the same density model.
    - [ ] Add composing/non-Latin production-client coverage, non-text clipboard formats, drag-and-drop, relative-pointer/pointer-lock behavior, and launcher accessibility semantics.
  - [ ] Android file integration and `/mnt/android`
    - [x] Expose visible regular files/directories from shared `/home/archphene` through a scoped Android `DocumentsProvider`; reject dotfiles, symlinks, traversal, spoofing controls, replacement rename, and root mutation through a Rust directory-descriptor broker.
    - [x] Import one Android document from the system picker, Open With, or Share directly into `~/Downloads` through a bounded Rust descriptor transaction; fsync before non-replacing publication, recover interrupted staging, number collisions, and retain coarse status across manager restart.
    - [x] Persist exactly one user-selected Android folder capability with explicit Connect/Change/Remove UX, read/write versus read-only status, process-restart validation, safe replacement ordering, and revoked-grant recovery.
    - [x] Materialize one connected Android tree as an initial atomic, non-replacing `~/Projects/<folder>` snapshot. Stream provider descriptors into Rust-owned bounded staging, preserve nested folders and dotfiles, reject unsafe paths/symlinks, recover stale staging, cancel through the Rust chunk loop with complete cleanup/retry, retain the published project after grant removal, and pass exact recursive/restart/full-device gates on both targets.
    - [ ] Evolve the initial snapshot into a conflict-safe synchronized POSIX mirror, then add drag-and-drop, exports, familiar home links, and `/mnt/android` sync/error status.
      - [x] Define an allocation-free three-way decision engine: one-sided edits/deletes propagate, identical changes converge, and concurrent edits, edit/delete races, unequal new files, and incompatible type changes preserve a conflict.
      - [x] Define and atomically persist a canonical versioned baseline manifest with a stable mapping ID, sorted unique safe paths, fixed fingerprints, strict entry/content/encoded-size limits, stale-temp recovery, symlink/substitution/corruption rejection, and round-trip tests.
      - [ ] Populate manifests with bounded SHA-256 baselines, execute resumable pull/push/delete plans transactionally, and retain both versions for every conflict.
  - [ ] Launcher wrapper generation and runtime-service binding
    - [x] Discover launchable shared-root desktop entries through a bounded Rust parser: accept safe structured `Exec` arguments without a shell, validate executable containment and modes, isolate malformed/symlink entries, cap files/bytes/results, page one immutable snapshot through coarse JNI, and surface scan status in the manager. Exact-ABI cold/restart and light/dark full-device gates pass on the emulator and Samsung.
    - [x] Reconcile complete discovery snapshots into a checksum-protected atomic manager-owned registry. Derive bounded pacman ownership, stable collision-checked Android identities, full launch/icon inputs, desired/published/pending generations, retryable build/install/removal states, and safe process-death/external-package reconciliation. Refuse incomplete catalogs and unsafe/corrupt paths. Host lifecycle tests plus exact-ABI cold-restart, cleanup, logs, and full-device gates pass on the emulator and Samsung.
    - [x] Resolve package icon names and absolute paths through bounded hicolor/pixmaps lookup plus fake-root symlink expansion, fingerprint icon bytes in the v2 registry, and place validated package PNGs in generated Android wrappers with an Archphene fallback. Preserve v1 Android identities during migration and fail closed on path, mode, size, format, dimension, digest, or race mismatches. Host lifecycle tests and a real Foot migration/app-drawer/launch gate pass on Samsung.
    - [ ] Derive reviewed Android capability declarations from the verified package closure and expose actionable per-launcher compatibility failures.
    - [x] Generate deterministic minimized thin launcher APKs, patch bounded binary-manifest identities, sign with one non-exportable RSA-3072 Android Keystore key using APK v3, and reverify signer/package/version/metadata plus the complete entry set and content digests before handoff.
    - [x] Remove repository-wide VCS/dirty-state metadata from the staged launcher-template bytes so unrelated manager commits cannot cause needless user-confirmed wrapper updates. The release template omits `META-INF/version-control-info.textproto`, and a dedicated gate requires byte-identical SHA-256 across forced full rebuilds.
    - [x] Stream only the verified APK digest into normal `PackageInstaller` sessions, preflight Android's unknown-source approval without stranding work, retain per-launcher install/uninstall confirmation, reconcile verified packages after process death, abandon and requeue interrupted sessions, quarantine identity conflicts, and remove wrappers without touching shared Linux package or user data. Exact fresh-install, denied-permission recovery, cold-restart, launch, and removal gates pass on the Android 16 x86_64 emulator and Android 15 AArch64 Samsung using full-device captures.
    - [ ] Run manager-owned Surface/input/lifecycle sessions against authenticated registry descriptors.
      - [x] Authenticate every wrapper Binder transaction from the kernel caller UID, exact installed package, persistent wrapper signer, bounded manifest identity, current Rust registry descriptor, generation, and exact launcher-template digest.
      - [x] Use a bounded versioned Binder protocol with a generation-checked session, client death token, cold-runtime retry, and real cross-process `Surface` attach/replace/detach/close ownership. Reject an untrusted manager-UID probe and malformed protocol input.
      - [x] Detect manager-signed wrappers built from an older template, advance their Android version monotonically, and republish them through normal user-confirmed PackageInstaller updates instead of quarantining them.
      - [x] Pass cold manager start, light/dark full-device Surface presentation, exact physical dimensions, and Binder-death cleanup on the AArch64 Samsung. Repeat this gate on the x86_64 emulator when it is attached again.
      - [x] Stage the Rust Wayland compositor as a reproducible dual-ABI production library, bind one private manager-owned socket per authenticated session, present committed SHM frames directly through `ANativeWindow`, and remove the socket deterministically after close or wrapper death.
      - [x] Add a bounded generation-checked manager process registry that reauthorizes the exact published descriptor, expands only structured desktop arguments, launches the exact installed `/usr/bin` command in its own process group, and supplies the private Wayland plus standard Qt/GTK/SDL environment without a shell.
      - [x] Submit bounded touch, primary-pointer, and hardware-key batches through one authenticated Binder transaction and one coarse direct-buffer JNI call. Samsung evidence covers the complete wrapper-to-compositor transport, fixed hardware-key mapping, HOME/resume session retention, Back cleanup, and a full-device fail-closed launch diagnostic.
      - [x] Drain merged stdout/stderr into one fixed 16 KiB tail ring, poll signed process exit state outside the frame hot path, terminate remaining process-group descendants when the leader exits, and report bounded start/stop/crash state through an authenticated one-way callback to a wrapper-owned Android overlay while native code remains the exclusive Surface pixel owner. A restored no-download Samsung fixture proves real ELF launch, captured loader diagnostics, exit 127, visible status, socket cleanup, and exact fixture restoration.
      - [x] Prove diagnostic-state portrait/landscape Surface replacement, normal Android reflow without a stretched retained buffer, same-session HOME/resume continuity, Back close, and socket cleanup in visually inspected full-device Samsung captures.
      - [x] Extend the fixed launcher input protocol with five mouse buttons, bounded horizontal/vertical axes, key repeat, Android modifier state, host focus transitions, and deterministic release/cancel cleanup. Validate records independently in Kotlin and Rust, cap active touches, cover the native mappings with 48 compositor tests, and pass a full-device Samsung HOME/resume/rotation/injected-input lifecycle gate without malformed-input rejection or a crash.
      - [x] Bridge focused-wrapper Android text clipboard and Wayland `wl_data_device` selections through bounded authenticated Binder messages. Limit text to 16,384 UTF-16 units/64 KiB UTF-8, cap queued transfers, perform nonblocking pipe I/O on a dedicated worker with two-second deadlines, reject invalid UTF-8, suppress stale/echoed revisions, defer Linux publication across focus loss, and clear descriptors deterministically. The 48-test native suite, full Samsung Wayland probe, production wrapper Unicode Binder gate, full-device capture, Back cleanup, and fatal-log check pass.
      - [x] Prove package-installed Foot connects from the shared AArch64 Arch root, resolves generic Unix sockets, user identity, Bash, runtime libraries, and Android system fonts, and presents a crisp density-aware client/subsurface tree. Full-device Samsung evidence covers safe system/IME insets, Samsung IME text, and live portrait/keyboard/landscape Surface replacement.
      - [ ] Repeat the production Foot client gate on the x86_64 emulator, then add HOME/resume, deliberate crash status, and descendant-cleanup variants on both targets.
        - [x] On Samsung, Home detaches the Surface without closing session 1; the same manager and wrapper PIDs survive, resume reattaches the Surface, and the retained Foot frame remains readable in a full-device capture.
        - [x] On Samsung, deliberately killing the real Foot leader reports visible exit `-9`, reaps its Bash descendant even though that child owns a separate process group, and permits a clean fresh-client relaunch. Stopped sessions no longer silently restart after an IME-driven Surface resize. A repeatable gate retains full-device evidence, rejects in-session relaunch, and checks fatal logs.
        - [ ] Repeat all production-client lifecycle variants on x86_64.
      - [ ] Add composing/non-Latin IME, non-text clipboard formats, drag-and-drop, pointer cursor/relative-pointer behavior, and accessibility semantics to the production launcher path.
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
- [x] Define how thin launcher APKs bind to the shared runtime service while Linux processes, packages, and files remain in that environment: a separately exported, versioned Binder authenticates the kernel-supplied caller UID, installed generated-wrapper signer, and bounded descriptor registry; the wrapper supplies an Android Surface, input batches, capability results, and a death token while the manager UID owns the Linux process and shared root.
- [ ] Preserve Android launcher entries, icons, intents, windows, notifications, and lifecycle without duplicating Linux roots.
- [ ] Define supervision, background execution, daemons, resource limits, crash recovery, and shutdown.
- [ ] Define trust for pacman, AUR builds, hooks, arbitrary executables, runtime content, and launcher signing.
  - [x] Pin and seal the official pacman runtime, exact repository endpoints, Arch keyring, and bounded official signer trust used by the current install path.
  - [ ] Define the remaining AUR, hook/scriptlet, arbitrary executable, and launcher-signing policies.
- [x] Document that packages inside the shared Arch environment intentionally share one Linux trust domain.
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

  - [x] Use Android's Storage Access Framework for user-selected folders and persist grants across restarts.
  - [ ] Add familiar home links such as `~/Downloads` and `~/Documents`.
  - [ ] Clearly show unavailable, revoked, read-only, syncing, conflict, and error states instead of silently failing.
  - [x] Keep working project trees in private `~/Projects` POSIX storage and treat each selected SAF tree as an explicitly synchronized Android endpoint, never as a mount.
  - [ ] Keep package databases, builds, symlinks, executables, sockets, and other POSIX-dependent data in private Arch storage.
- [x] Add a first-run storage flow that explains private Linux storage and the optional Android-folder snapshot, lets the user choose a folder or skip without granting broad file access, persists either choice across restart, and leaves Connect/Change available later. Pass semantic, picker-cancellation, no-repeat, scoped-log, and visually inspected full-device gates on the exact-ABI emulator and Samsung.
- [x] Expose visible files in shared `/home/archphene` through a `DocumentsProvider` so Android Files, pickers, share sheets, browsers, and other apps can open and save them.
  - [x] Use `MANAGE_DOCUMENTS` plus Android URI grants rather than broad storage permission; keep dotfiles, symlinks, package/runtime trees, and unsupported file types private.
  - [x] Pass exact framework create/read/write/non-replacing-rename/delete, child, collision, traversal, bidi-spoof, and symlink gates plus visually inspected full-device DocumentsUI browsing on the emulator and Samsung.
- [ ] Support Android-to-Archphene import through Open With, Share, drag-and-drop, and file-picker flows.
  - [x] Stream a single picker, Open With, or Share document descriptor into `~/Downloads` without copying bytes through JNI/Kotlin; preserve exact bytes, publish atomically, number collisions, recover stale staging, and bound imports at 16 GiB.
  - [x] Keep the Service-owned operation/status through Activity recreation, consume incoming intents once, persist completed/failed/interrupted status, and bound the pending Activity queue to one URI.
  - [x] Pass exact ACTION_VIEW/ACTION_SEND content, duplicate-name, process-restart status, system-picker launch, fatal-log, cleanup, and visually inspected full-device gates on the emulator and Samsung.
  - [ ] Add multi-document, directory-tree, drag-and-drop, progress/cancel, provider-timeout, and conflict-aware synchronized import.
- [ ] Support Archphene-to-Android open, save, export, and share flows.
- [ ] Avoid `MANAGE_EXTERNAL_STORAGE` as the default; evaluate an optional advanced/sideloaded mode only if SAF cannot satisfy a demonstrated workflow.
- [ ] Test grant creation, persistence, revocation, rename, deletion, conflicts, large trees, offline providers, uninstall behavior, and malicious paths.

## P0 - Package system and shared Arch behavior

- [ ] Complete pacman transaction semantics against the shared Arch root.
  - [x] Re-verify signed archives immediately before mutation and commit the complete prepared closure through one normal pacman transaction.
  - [x] Preflight the complete verified closure with normal pacman dependency/conflict/replacement checks and require an exact name/version plan before mutation.
  - [x] Recover a bounded stale database lock and durable explicit-install-reason intent after interruption.
  - [x] Preserve pacman install reasons, validate the local database after mutation, and support conservative non-cascading removal with exact postcondition checks.
  - [x] Cancel safely before the commit boundary, including active-transfer disconnect, partial-file cleanup, durable Cancelled state, and exact installed/cache postconditions.
  - [x] Support large bounded dependency closures and transaction transcripts without widening every command response: a reusable dedicated 256 KiB direct resolution buffer carries the current 198-package Code closure, successful pacman output is size-checked without a large heap copy, failure diagnostics remain capped at 16 KiB, and detached-signature status parsing accepts non-UTF-8 human diagnostics while requiring exact GnuPG status tokens.
  - [ ] Add hooks/scriptlets, proven upgrades/replacements, failure rollback/recovery, orphan cleanup, and storage-failure recovery.
- [ ] Add a bounded AUR workflow suitable for packages such as `visual-studio-code-bin`.
  - [x] Parse bounded AUR v5 metadata and one pinned cgit snapshot without executing it; require exact request/base/version/AArch64 agreement, reject unsafe or oversized tar content, record the cgit commit and snapshot SHA-256, pair every selected source with its SHA-256 or explicit `SKIP`, verify local snapshot source bytes and install-script presence, and enumerate dependencies plus visible PKGBUILD functions. Host fixtures and the live current `visual-studio-code-bin` AArch64 snapshot pass.
  - [x] Fetch the exact AUR RPC and Rust-approved cgit snapshot over pinned Android HTTPS, carry the bounded review over a versioned binary JNI wire, and render its community trust warning, maintainer, commit, snapshot digest, architecture-selected sources and checksums, dependencies, build functions, install script, and exact PKGBUILD. A live full-device Samsung gate confirms the official Install action remains disabled and the pacman database is unchanged.
  - [x] Resolve snapshot-local versus direct-HTTPS versus unsupported sources, require bounded safe cache names and SHA-256 for the supported remote path, follow at most five credential-free HTTPS redirects, stream through a Rust-owned private-cache descriptor, and have Rust re-read and hash the completed file before promotion. Tampered cache entries are discarded; verified entries are rehashed and reused. The physical Samsung gate verifies the current 220,653,390-byte Code AArch64 source, independently checks its on-device SHA-256, preserves all 36 pacman database entries, and records a full-device screenshot.
  - [ ] Show source, PKGBUILD, maintainer, signatures/checksums, build steps, permissions, and disk impact before installation.
  - [ ] Run builds as an unprivileged Linux user and clearly communicate that installed Arch/AUR packages share one trust domain.
- [ ] Make installed commands, libraries, desktop files, MIME handlers, fonts, themes, and services immediately discoverable across all Linux apps.
- [x] Generate, update, reconcile, and remove Android launcher wrappers from desktop entries without deleting shared package or user state incorrectly.
- [ ] Define ownership when several packages provide desktop entries or depend on the same files.
- [ ] Handle package upgrades, downgrades, replacements, hooks, interrupted transactions, rollback, orphan cleanup, and low-storage failures.
- [x] Use one generic command policy instead of publishing package-specific exceptions: PATH lookup resolves installed shared-root `/usr/bin` commands, while exact absolute execution may resolve a verified package-owned executable anywhere inside the shared Arch root. Android host commands and root escapes remain unavailable.
- [ ] Complete the verified runtime model for package-owned `/usr/lib/<app>` trees, data, scripts, and valid symlinks.
  - [x] Resolve regular nested executables through the verified loader only when their canonical path remains inside the shared root, their executable mode is present, and they are not group/world writable. Host tests cover real ELF execution, spawn, access, escape, and writable-file rejection; a full-device Samsung gate proves a package-owned `/usr/bin` Bash wrapper launching an unmodified ELF from `/usr/lib`.
  - [x] Resolve relative and fake-root absolute symlinks without interpreting them against Android's host root; cap expansion at 40 links and reject loops or traversal above the shared root.
  - [x] Execute bounded nested shebang programs through one installed ELF interpreter, preserve the optional single kernel shebang argument, and reject recursive/non-ELF interpreters. Host exec/spawn probes and the Samsung `/usr/bin` wrapper → `/usr/lib` script → absolute symlink → ELF gate pass.
  - [x] Translate Arch's standard `/usr/lib/pulseaudio` loader path into the private root so unmodified `libpulse` resolves `libpulsecommon` without an application-specific rule.
  - [x] Preserve normal `/proc/self/exe` semantics across the explicit glibc loader: publish the verified real target on initial launch and replace stale values on every nested `exec`/`posix_spawn`. Host direct/spawn probes and the current Samsung Foot client pass with the refreshed sealed bridge.
  - [ ] Generalize verified absolute/private RUNPATH translation without globally mixing unrelated application-private libraries.
  - [ ] Repeat the nested executable/symlink/script device gate on the x86_64 emulator.
- [ ] Cache unchanged closure analysis and wrapper inputs so repeat installs do not rescan large package trees.
- [ ] Complete and validate the separate 16 KB-aligned x86_64 package/runtime strategy before enabling transactions there.

## P0 - Manager UX and reliability

- [ ] Make every install/update/remove operation appear in the app list immediately with persistent state and progress.
  - [x] The current exact-package details flow immediately shows persistent resolve, download, verify, install, complete, and failure state.
  - [x] Replace the ambiguous transaction strip with a stable recent-activity card backed by the existing bounded Rust journal fields: package, operation, state, exact progress, message, static progress track, and state-driven Cancel. Prove durable Complete across manager restart and durable Failed on both exact ABIs with debug-only native journal fixtures, full-device screenshots, and no package downloads.
  - [x] Build the actual installed-package list from one bounded Rust snapshot of pacman's local database; page the cached snapshot through coarse JNI, publish one revisioned Binder object, and render recycled Android rows with exact version and explicit/dependency reason. Prove 67-package multi-page light/dark, mode-switch, scroll, restart, scoped-log, and full-device behavior on the emulator and Samsung without downloads.
  - [x] Build the structured available-package list from Rust's bounded real-pacman search response; publish one revisioned Binder snapshot, render recycled repository/name/version/description rows, route row selection to details, restore the visible query across recreation, and overlay matching durable operation state. Pass generated no-network pacman-catalog light/dark, selection, recreation, failed-row, scoped-log, and full-device gates on both ABIs.
  - [x] Prove a live newly queued operation appears in its matching or appended package row before the worker advances, then complete retry/recovery actions. A generated no-network catalog and one-shot debug-only worker gate prove the real durable Queued row/card, safe Cancel before cache/network/mutation, cold-restored Cancelled/Review, and exact retry gating on both ABIs with visually inspected full-device screenshots. Install/Remove also disable immediately when the visible package field no longer matches the freshly resolved exact package.
  - [x] Show queued, resolving, downloading, verifying, building, publishing, installing, awaiting Android confirmation, completed, failed, and cancelled states. Append the launcher phases without renumbering persisted v1 states, enforce legal forward transitions in Rust, and pass a no-network live durable-state presentation gate with exact progress, cancellation boundary, cold restart, scoped logs, and visually inspected full-device screenshots on both ABIs. This proves manager presentation; the real launcher builder/PackageInstaller pipeline remains separately tracked.
  - [ ] Keep state correct across rotation, backgrounding, process death, reboot, and manager restart.
    - [x] Retain bootstrap, catalog, package, command, storage, and shell work when the Activity finishes or its task is dismissed; promote user-started long work to one foreground Service notification, stop only after unobserved work is idle, and pass exact-ABI Recents-dismissal/cold-reopen gates on the emulator and Samsung.
  - [ ] Provide actionable diagnostics, retry controls, and package-scoped failure isolation.
    - [x] Replace the dead disabled Cancel control with no action for terminal success and a state-driven Review action for durable Failed/Cancelled jobs. Review prefills the exact package and performs a fresh signed-repository/installed-state resolution before enabling any retry or removal; it never replays stale transaction metadata.
    - [x] Keep long package names, phase labels, and the Review/Cancel touch target readable in the fixed-height activity card by stacking name and phase in a flexible column; verify full-device captures on the emulator and Samsung.
    - [x] Add explicit Retry only after the exact durable failure/cancellation revision resolves successfully against current catalogs and installed state. A subsequent failure creates a new revision and requires Review again; catalog refresh locks Review, Retry, and Remove consistently with the executor.
    - [x] Classify network, storage, trust, changed-state, catalog, generic, and post-mutation failures into bounded actionable guidance; show up to three readable lines and verify settled full-device captures for every class on the emulator and Samsung.
    - [x] After an install/remove failure once mutation has begun, refresh the installed-package and shell snapshots before offering Review. If refresh fails, require a manager restart; if the durable journal update also fails, publish a local terminal state rather than leaving the UI stuck on Installing.
    - [x] Add a one-tap Clear cache recovery for storage failures. Rust validates the bounded manager-owned download cache in a fail-closed first pass, deletes only package archives/signatures/partials, syncs the directory, and reports reclaimed bytes. Kotlin binds the handled recovery to the persistent Rust job ID, restores its exact Review result after Service/process restart, and retains the foreground operation through real Recents dismissal; Rust tests and exact-APK emulator/Samsung gates cover the JNI and lifecycle paths.
    - [ ] Add package-specific cache selection and disk-use controls, one-tap catalog refresh from the activity card, and a verified repair/rollback workflow for interrupted or partial pacman transactions.
  - [x] Show a state-driven Cancel action only before Linux package mutation and retain the durable Cancelled result across manager restart.
- [x] Make the current package details and operation content responsive and scrollable; full-device emulator and Samsung audits show the complete `btop` closure with state-driven Install/Update/Verify and Remove actions.
- [x] Profile and fix slow first launch after boot; retain the visible runtime-loading state while avoiding unchanged verification-keyring rebuilds.
  - [x] Cache the derived GPG keybox only for the exact immutable packaged keyring/ownertrust identity, reject unsafe or oversized cache entries, and prove real signed-package verification after reuse.
  - [x] Gate steady cold-process readiness below 1,000 ms and post-boot readiness below 1,500 ms on both targets; final steady reuse is 237 ms on the emulator and 222 ms on Samsung, with post-boot reuse at 442 ms and 1,222 ms respectively.
- [ ] Perform a complete UX pass over discovery, package details, installation, launcher creation, updates, storage, permissions, setup, settings, and recovery.
  - [x] Establish a dependency-free light/dark visual baseline for the greenfield manager: use theme resources instead of hardcoded dark panels, sentence-case native actions, readable selector states, and a user-facing readiness header while retaining debug runtime evidence outside the visible text. Pass reversible full-device light/dark and lifecycle gates on the emulator and Samsung.
  - [x] Replace the dense single-screen scaffold with focused Packages, Files, and Terminal navigation; preserve the selected section across Activity recreation; keep an active shell alive when navigating away; and pass full-device portrait/landscape, onboarding, lifecycle, and persisted-folder gates on the emulator and Samsung.
  - [x] Add an allocation-stable 840 dp adaptive branch with a persistent navigation rail, two-column package workspace, side-by-side file actions, and a maximized terminal surface. Pass reversible accessibility-geometry and full-device visual gates at tablet and external-display-sized configurations, then prove restoration to the phone branch and rerun Samsung phone navigation.
  - [ ] Validate the adaptive branch on a real Samsung DeX or physical external display, including live display moves, keyboard/mouse focus, window resizing, and density changes; the current gate safely simulates the Android configurations on the emulator but is not physical-display evidence.
  - [x] Audit the populated installed-package list and durable terminal Complete/Failed cards in the new navigation on both exact ABIs, including light/dark full-device views, pagination, and manager restart.
  - [x] Audit populated available search rows, row-to-details selection, query/result recreation, and matching durable Failed state on both exact ABIs with generated local pacman databases.
  - [ ] Audit full populated details, every live durable transaction phase, post-review Retry/recovery, and an active terminal in the new navigation.
- [ ] Review Obtainium's source, license, screenshots, app-list structure, and update progress UI. Adapt suitable open-source patterns to Archphene's compact list, spinner, and richer phase strings without copying blindly.
- [ ] Ensure search results distinguish graphical apps, CLI tools, libraries, installed packages, available updates, AUR results, unsupported packages, and compatibility status.
- [ ] Add clear disk-use estimates and controls for package archives, shared runtime data, build caches, and user files.

## P0 - VS Code and .NET daily-use milestone

- [ ] Install Code through the generic package/AUR pipeline with no Code-specific bridge exceptions.
  - [x] On x86_64, the generic official-package path installs the current 198-package, 428 MiB Code closure, discovers four desktop entries, publishes four generated launchers, and reaches the packaged Electron executable.
  - [ ] Add the bounded AUR workflow needed for an equivalent `visual-studio-code-bin` installation on AArch64.
- [ ] Validate Electron/Chromium multiprocess startup, Ozone Wayland, sandbox behavior, rendering, IME, clipboard, dialogs, file watching, extensions, and lifecycle.
  - [x] Resolve Electron's standard PulseAudio private-library dependency through the generic runtime environment.
  - [ ] Repeat the x86_64 Electron launch with the generic `/proc/self/exe` repair. Chromium's ICU loader derives `icudtl.dat` from the running executable path, and the prior explicit-loader process exposed the loader instead of Electron; host direct/spawn probes and Samsung Foot now pass, but Electron itself remains unvalidated while the emulator is stopped.
- [ ] Install `dotnet-sdk` through the same shared package system and make `dotnet` available in Code's integrated terminal.
- [ ] Create a new ASP.NET Core MVC project in shared Arch storage.
- [ ] Open the project in Code and validate editing, search, Git, terminal PTY, language services, restore/build, and extension-host subprocesses.
- [ ] Run the project under the debugger, stop at breakpoints, inspect state, continue, and restart.
- [ ] Open the served localhost URL in Android's browser and validate routing back to the running Linux process.
- [ ] Validate the workflow with touch/IME on a phone and keyboard/mouse on tablet or external display.
- [ ] Repeat the complete milestone on the x86_64 emulator and physical AArch64 Samsung using full-device screenshots and scoped logs.

## P1 - Generic desktop integration quality

- [x] Replace global `QT_SCALE_FACTOR` compatibility behavior with compositor-advertised logical size plus integer/fractional output scale; physical Samsung portrait/landscape reconfiguration passes without app-specific settings.
- [ ] Extend the compositor density gate to live moves between Android displays and user-selected geometry scale.
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

- [x] Update README, architecture, security, roadmap, and storage documentation to the approved shared-environment model; clearly mark older per-wrapper runtime-pack results as historical.
- [ ] Document the Android/Linux filesystem boundary, `/mnt/android`, backup/export, permissions, revocation, and uninstall consequences.
- [ ] Document normal-Arch compatibility limits imposed by Android's kernel, SELinux, seccomp, background execution, and lack of root/systemd assumptions.
- [ ] Update README, roadmap, project status, compatibility matrix, security model, changelog, and release notes after behavior is implemented.
- [ ] Run the final public-repository, provenance, licensing, secret, reproducibility, CI, and release-artifact audit.

## Later - ArchpheneOS

Start only after the Android application reaches its release gate and the user approves the next project.

- [ ] Boot an x86_64 VM into an AOSP/GrapheneOS-derived laptop image and validate its security model.
- [ ] Define which GrapheneOS guarantees depend on supported Pixel hardware and cannot transfer directly to generic x86 systems.
- [ ] Design explicit Linux administration and escalation while Android applications remain sandboxed.
