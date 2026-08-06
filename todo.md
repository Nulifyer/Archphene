# Archphene project plan

Updated: 2026-08-05

This file is the authoritative forward plan. It contains only current decisions,
open work, sequencing, and exit criteria. Completed implementation evidence
belongs in [`docs/project-status.md`](docs/project-status.md); supported
application claims belong in
[`docs/compatibility-matrix.md`](docs/compatibility-matrix.md); dated
experiments and rejected alternatives belong in [`research/`](research/).
[`docs/roadmap.md`](docs/roadmap.md) remains the short public roadmap and must
not grow a second detailed task list.

## Product destination

Archphene is an Android-native application platform for one user-owned Arch
Linux environment:

- Pacman and reviewed AUR packages share one filesystem, package database,
  home, toolchain, and Linux trust domain.
- The manager owns package state, Linux processes, the private Wayland
  compositor, durable recovery, and shared Android capability brokers.
- A published graphical Linux application receives a thin Android **app shell**
  with its own package identity, icon, tasks, windows, intents, notifications,
  and foreground Android interactions. The shell does not own another Linux
  root or executable closure.
- CLI and TUI packages remain available through Archphene Terminal and do not
  create app-drawer entries.
- Exact-ABI Linux code runs without CPU translation. Android remains the kernel,
  UID, SELinux, seccomp, lifecycle, permission, storage, and window authority.
- Phone mode presents one coherent touch-oriented Android task. Desktop mode
  maps independent Linux windows or documents to Android tasks where the
  platform and application support it.

The release objective is a dependable native Linux application platform, not a
claim that every Arch package can run. FEX, Hangover, and Proton are later
optional runtimes and must not delay or weaken the native exact-ABI path.

## Current baseline

The following foundations are implemented and remain regression requirements:

- verified official-package and reviewed AUR installation into one shared root,
  including recovery, rollback, lifecycle scripts, bounded maintenance
  adapters, and a separate no-network Builder UID;
- production Terminal, DocumentsProvider, SAF document import/export, project
  mirrors, synchronization, storage inventory, and package management;
- deterministic generated app shells signed with an Android Keystore identity,
  installed through PackageInstaller, and authenticated over Binder by caller
  UID, signer, descriptor, generation, and template digest;
- manager-owned Linux process groups, private Wayland sessions, Android
  Surface/input/IME/clipboard/accessibility integration, Android capability
  brokers, and foreground-service lifecycle;
- native Wayland Qt, GTK, SDL, Electron, Foot, GLMark2, camera, audio, printing,
  secrets, drag-and-drop, and document evidence on the maintained x86_64
  emulator and AArch64 Samsung device;
- OpenGL ES command acceleration through virpipe and a Bionic virglrenderer
  helper, with bounded llvmpipe fallback;
- three preallocated `AHardwareBuffer` output slots submitted through
  `ASurfaceTransaction`, damage-driven CPU upload, explicit fence ownership,
  and bounded resize generations.

The principal gaps are architectural rather than missing package-manager
features:

- app shells are still `singleTask` and do not provide a production
  multi-window/document-task model;
- the final accelerated graphics path still returns through SHM and CPU upload;
- Vulkan and XWayland are not supported release paths;
- physical DeX/external-display and GrapheneOS Pixel validation are missing;
- Code requires an explicitly accepted reduced Chromium isolation profile, and
  the C# breakpoint/debugger workflow is incomplete;
- official Arch x86_64 packages remain blocked on 16 KiB Android page-size
  devices.

## P0 — Native platform release path

Work in this section is ordered. A later milestone may be researched in
parallel, but it does not replace the exit criteria of an earlier milestone.

### 1. Finalize Android app-shell and window ownership

**Outcome:** one Linux application has one Android package identity and can own
one phone task or multiple Android desktop/document tasks without duplicating
its Linux environment.

- [x] Use **app shell** consistently for the generated Android package; reserve
  **launcher** for Android launcher surfaces and launch actions.
- [x] Define stable identities for application, Linux session, Android task,
  Linux toplevel, imported document, and attached Surface. Document which state
  survives Activity recreation, Home, display movement, manager restart, and
  process exit.
- [x] Extend the authenticated Binder/session model from one Surface per Linux
  session to a bounded set of window tokens and Surfaces. The manager must keep
  Linux process ownership while each Activity owns only its Android window and
  foreground capabilities.
- [x] Implement the compact policy: one task hosts the primary toplevel and
  composes popups, transients, and ordinary secondary windows. Provide a bounded
  in-app window switcher only when an application has independent windows.
- [x] Implement the desktop policy: primary and independent document/toplevel
  windows may receive separate Activity/task instances; popups, menus, and
  tooltips stay with their parent.
- [x] Replace `singleTask` only after multi-window session ownership is ready.
  Use a multi-instance-compatible Activity mode, bounded Recents, document
  intents, and explicit New Window behavior. Do not create one APK per window.
- [x] Drive compact/adaptive/desktop policy from the current window metrics,
  caption and system insets, display, pointer, keyboard, and application
  capability. Do not branch on Samsung, DeX, Pixel, or model names.
- [x] Preserve exact intent and lifecycle behavior across multiple tasks.
  - [x] Preserve `ACTION_VIEW`, `ACTION_EDIT`, `ACTION_SEND`, notifications,
    root-task close, and wrapper force-stop behavior.
  - [x] Validate app-shell removal and manager absence without altering retained
    user sessions on the maintained physical target.
- [x] Prototype manager-hosted **Quick launch** for trying a graphical package
  without another PackageInstaller confirmation. Keep **Add to Android** as the
  explicit action that publishes the real app shell.
- [x] Investigate replacing `QUERY_ALL_PACKAGES` with a narrow marker intent
  query for generated shells. Continue to verify every candidate by package
  name, signer, descriptor, generation, installer state, and embedded metadata.

**Exit criteria**

- One unmodified multi-document application opens, resumes, closes, and
  restores two independent Android tasks without restarting its Linux process.
- The same package uses the compact single-task policy on a phone viewport.
- Rotation, Home/resume, Activity recreation, live display movement, wrapper
  force-stop, manager death, and final-window close have deterministic results.
- Existing single-window Foot, KCalc, Mousepad, GNOME Text Editor, Code, and
  SuperTux workflows remain green on both maintained ABIs.

### 2. Remove the accelerated graphics readback path

**Outcome:** CPU-rendered clients require at most one damage upload, while
virpipe clients can reach SurfaceFlinger without GPU readback and full-frame CPU
copying.

- [x] Add release diagnostics that distinguish SHM snapshot, CPU conversion,
  GPU readback, texture upload, GPU composition, direct AHB submission, and
  SurfaceFlinger release. Do not label the current AHB upload path zero-copy.
- [x] Add a Bionic EGL/GLES renderer to import the bounded output
  `AHardwareBuffer` ring, upload only changed SHM regions into retained textures,
  and GPU-compose the output. Keep the current CPU conversion path as the
  mandatory fallback.
- [x] Prove the GPU-composition path uses no `AHardwareBuffer_lock`,
  `glReadPixels`, full-frame Kotlin/JNI copies, or per-frame managed allocation.
- [ ] Extend the private virpipe/vtest contract with a host-allocated,
  AHardwareBuffer-backed presentable resource. Scope every resource and fence to
  one authenticated session and helper generation.
  - [x] Define the fixed authenticated APHB side channel and the bounded private
    Wayland commit-identity contract. Reject duplicate surfaces/resources,
    cross-generation identities, stale fences, and unbounded bindings.
  - [x] Add strict optional helper arguments for the private present socket,
    session ID, helper generation, and 128-bit token. Connect and send the exact
    APHB Hello before publishing the vtest socket.
  - [x] Add the dormant same-UID manager endpoint with fixed-frame reassembly,
    bounded dispatch, reconnect-safe partial-frame discard, and inode-checked
    cleanup. Reject Resource/Present until handle/fence receipt is connected.
  - [x] Receive Resource handles through the Android NDK, validate exact RGBA
    dimensions, stride-backed byte accounting, layers, usage, and reserved
    fields, and retain at most three handles until idle DropResource.
  - [x] Receive one Present marker with zero or one close-on-exec acquire-fence
    descriptor, retry incomplete nonblocking packets before frame parsing, and
    retain one explicit acquire state per resource. Marker-only means the helper
    completed rendering conservatively before send.
  - [x] Add fail-closed private vtest commands that allocate one of three RGBA
    AHBs, import it as the exact virgl resource, send scoped Resource, and send
    the matching 64-bit Present after conservative GPU completion.
  - [x] Receive the exact scoped manager Release through a blocking private
    vtest wait, consume its optional release fence, and permit reuse only after
    the matching 64-bit sequence completes.
  - [x] Add a three-entry nonblocking manager Release queue that writes each
    fixed frame before marker `52`, transfers zero or one release-fence FD, and
    commits registry release only after queue admission.
  - [x] Add a pinned generic Mesa virpipe winsys/Wayland sender that carries the
    exact command-40 sequence into the matching surface commit and waits on
    command 41 before reuse. Preserve `wl_shm` unless explicitly activated.
  - [x] Stage the patched Mesa runtime and connect the manager's received AHB
    presentation and SurfaceFlinger-backed Release sender.
- [ ] Import virgl-produced AHBs as EGL images and return release fences before
  buffer reuse. Bound dimensions, slots, estimated bytes, outstanding fences,
  pending releases, and device-loss recovery.
  - [x] Keep dormant direct SurfaceControl submission fail-closed unless the
    committed resource is the root, exact full-output, single-region opaque
    surface with no popup, custom cursor, child, transform, viewport crop, or
    overlay layout. Return every rejected resource to the bounded registry.
- [ ] Add a direct-submit fast path for a sole opaque full-output surface with no
  popup, cursor, transform, alpha, or compositor work. All other cases remain
  GPU-composited.
- [x] Use release-aware Surface transactions on API 36 where available while
  preserving the current tested callback/fence path on older supported APIs.
  - [x] Resolve `ASurfaceTransaction_setBufferWithRelease` at runtime, retain
    one callback context per submitted buffer, and preserve the legacy
    transaction-completion path when the symbol is absent.
  - [x] Exercise the per-buffer callback on a live API 36 target and preserve
    the physical API 35 Samsung legacy fallback.
- [ ] Advertise standard Linux dmabuf only after a live format/modifier
  allocation-and-import round trip succeeds. The current Samsung must retain
  AHB/SHM fallback because it lacks `EGL_EXT_image_dma_buf_import`.
- [ ] Expand graphics testing to Qualcomm, Tensor/Mali, another Mali/vendor
  family, emulator/gfxstream, 4 KiB, and 16 KiB systems.

**Exit criteria**

- Animated GLMark2 and SuperTux frames contain no GPU-to-CPU readback in the
  accelerated path and retain exact visual output, resize, Home/resume, helper
  replacement, and software fallback behavior.
- SHM clients use one bounded damage upload and no full-frame CPU composition
  when the GPU path is available.
- Frame, input, memory, descriptor, thread, power, and thermal metrics improve
  or remain within explicit release budgets on every supported fallback.

### 3. Complete phone and Android desktop UX

**Outcome:** supported applications have intentional interaction policies on a
phone and behave as ordinary resizable Android applications on desktop-sized
windows and connected displays.

- [ ] Publish compatibility labels derived from evidence: Phone optimized,
  Phone usable, Tablet/desktop recommended, Precise pointer recommended,
  Hardware keyboard recommended, and Unsupported capability.
- [ ] Offer remembered Direct touch, Touchpad, Pen, and Game interaction
  profiles without application-specific input patches.
- [ ] Keep Auto-first geometry, text, and control policy; add only generic
  fit/pan/zoom or modifier controls required by fixed desktop layouts.
- [ ] Complete real-user non-Latin IME coverage, the x86_64 GTK semantic action
  lane, binary/custom clipboard formats, and an unmodified SDL relative-pointer
  representative.
- [ ] Finish Qt multiple-file/folder and GTK 4 multiple-file/save/folder
  DocumentsUI workflows through the existing portal boundary.
- [ ] Validate the manager adaptive layout and app-shell window policy on
  physical Samsung DeX or another real connected display, including display
  movement, hotplug, density, keyboard/mouse focus, IME routing, audio routing,
  sleep/resume, freeform resize, and system caption insets.
- [ ] Treat Android 17 behavior as tracked compatibility work until its final
  SDK and OEM implementations are available. Keep target API 36 release gates
  authoritative for the current release.

**Exit criteria**

- The phone matrix passes touch, IME, rotation, Back, Home/resume, permissions,
  accessibility, documents, and readable full-device visuals.
- The desktop matrix passes independent tasks, keyboard, mouse/touchpad,
  pointer capture, drag-and-drop, resize, display movement, hotplug, and
  sustained use on physical hardware.
- Mode selection uses capabilities and current window state, not OEM detection.

### 4. Close the daily-driver acceptance workflow

**Outcome:** the release has one demanding end-to-end developer workflow plus a
small set of representative native applications; it does not depend on a long
list of shallow launch tests.

- [ ] With explicit user approval, install and review the required C# language
  and debugger components in Code.
- [ ] Complete the MVC workflow from Code: open the shared project, edit and
  search, use Git, restore/build, stop at a breakpoint, inspect state, continue,
  restart, run Kestrel, and open the site through production OpenURI.
- [ ] Repeat the complete workflow on x86_64 and physical AArch64 with phone
  touch/IME and desktop keyboard/mouse variants.
- [ ] Make the Chromium reduced-isolation decision explicit. Keep the current
  default-off confirmation unless a real Android-compatible sandbox boundary is
  proven; never turn `--no-sandbox` into an invisible compatibility tweak.
- [ ] Finish the remaining release representatives in
  `docs/compatibility-matrix.md`. Require a complete workflow and named hardware
  rather than package installation or first-frame evidence.
- [ ] Perform one complete UX audit of discovery, compatibility review,
  installation, Quick launch, Add to Android, updates, storage, permissions,
  recovery, settings, and removal.

**Exit criteria**

- The Code + .NET workflow passes end to end and restores all test-owned state.
- Every release representative has a reproducible workflow, explicit unsupported
  boundaries, and actionable failure reporting.
- No application-specific source patch is required to satisfy a generic bridge
  contract.

### 5. Release platform, security, and operations

**Outcome:** support claims are tied to real devices, distribution constraints,
and reproducible artifacts.

- [ ] Validate the manager, generated shells, package runtime, GPU fallback,
  permissions, lifecycle, and update path on a supported GrapheneOS Pixel.
- [ ] Add a physical x86_64 Android target before making a physical x86_64
  support claim.
- [ ] Measure Android phantom-process limits, memory pressure, process-heavy
  applications, and OEM background behavior. Keep process/session limits visible
  and fail before uncontrolled trimming.
- [ ] Automate the maintained physical-device soak in CI or a controlled device
  runner. Preserve the current non-destructive and state-restoring defaults.
- [ ] Document that the full pacman/AUR product is distributed outside normal
  Google Play executable-code policy. If a Play edition is pursued, define it as
  a separate curated product rather than weakening the main architecture.
- [ ] Complete the public-repository provenance, SBOM, licensing, secret,
  reproducibility, signing, update, rollback, and release-artifact audit.
  - [x] Migrate the tag workflow from `prototypes/linux-app-manager-stub` to
    `android/app`. Use the new-product `org.archpheneos.manager` identity, inject
    the tag version, emit exact-ABI assets, and update the verifier before
    creating a new tag.
  - [x] Derive target-filtered manager and Builder Rust runtime closures from
    locked Cargo metadata and include package URLs, source checksums, and
    declared licenses in each verified SPDX release SBOM.
  - [x] Publish deterministic checksum-bound Rust license archives containing
    every discovered license, notice, and copyright file from the exact Cargo
    source packages represented in each APK SBOM.
  - [x] Record the canonical evidence-bound native/runtime component inventory
    and fail before any release write while an inventory blocker remains.
  - [x] Package and checksum-bind discovered source license and notice files for
    D-Bus, Mbed TLS, Mesa, PipeWire, libepoxy, and virglrenderer.
  - [x] Publish the exact glibc source revision, Archphene patch, and x86_64 and
    AArch64 build-control files as a deterministic corresponding-source bundle.
  - [x] Bind staged Qt plugin hashes to their historical project source paths and
    commits, and pin the x86_64 build image and package-repository snapshot.
  - [x] Rebuild all three x86_64 Qt bridge plugins from their historical commits
    and require byte-for-byte equality before the release audit gate.
  - [x] Rebuild the AArch64 Qt platform-theme and style plugins from historical
    source and require byte-for-byte equality before the release audit gate.
  - [x] Rebuild the AArch64 KF6Config plugin byte-for-byte using its historical
    headers and an ABI-only link stub; no KF6 implementation code is embedded.
  - [x] Bind both ABI copies of every Termux PulseAudio closure package to its
    exact historical package-recipe commit and verify all downloaded bytes.
  - [x] Publish deterministic corresponding source, build-framework snapshots,
    and indexed licenses for all 14 Termux PulseAudio closure packages.
  - [x] Validate GTK x86_64 and AArch64 compatibility artifacts by checksum and
    maintained source/build contracts without requiring historical reconstruction.
  - [x] Build and test Arch x86_64 and AArch64 package runtimes from current
    signed repositories while recording exact resolved artifact versions.
  - [x] Resolve every recorded native/runtime notice, license-text,
    reproducibility, provenance, corresponding-source, and relinking blocker.
- [ ] Update README, architecture, security, roadmap, compatibility matrix,
  status, changelog, support policy, and release notes from proven behavior.

**Exit criteria**

- Exact-ABI release APKs and generated shells are reproducible from recorded
  source inputs and pass signer/content verification.
- The release device matrix passes package, storage, terminal, compositor,
  graphics, input, permissions, lifecycle, recovery, update, and full-device
  visual gates.
- Unsupported hardware, page size, package closure, or bridge capability fails
  before mutation or launch with an actionable explanation.

## P1 — Compatibility expansion after the native release

### X11 applications

- [ ] Add one private rootless XWayland process per authenticated application
  session using pre-opened sockets, no TCP listener, bounded logs, process-group
  ownership, and `xwayland-shell-v1` association visible only to XWayland.
- [ ] Start with software/SHM presentation and validate `xterm`, a GTK/X11
  application, a Qt/xcb application, clipboard, IME, popups, DPI, resize, and
  teardown before adding glamor or buffer-sharing optimizations.
- [ ] Document that X11 clients within one session share the X11 trust domain.

### Vulkan and demanding graphics

- [ ] Build a standalone Android-host Vulkan capability probe covering AHB
  external memory, queue ownership, synchronization, resize, device loss, and
  presentation.
- [ ] Evaluate a small Archphene transport, Venus vtest, and relevant
  Vortek-style AHB techniques. Do not adopt gfxstream or Venus without a
  zero-readback prototype and acceptable maintenance scope.
- [ ] Claim Vulkan only after unmodified `vkcube-wayland` renders through the
  production app shell on multiple physical GPU families.
- [ ] Gate DXVK and VKD3D-Proton separately from basic Vulkan support.

### Broader applications and Android capabilities

- [ ] Expand representative workflows to office, browser, Rust-native GPU,
  creative, multimedia, USB, stylus, gamepad, accessibility, and multi-window
  applications.
- [ ] Add richer notification actions, remaining safe portal operations, and
  binary clipboard/file-transfer formats only behind exact capability contracts.
- [ ] Broaden printing, audio, camera, accessibility, keyring, appearance, and
  chooser coverage beyond the currently validated clients.
- [ ] Decide whether a live SAF-backed path abstraction is worth its provider
  semantics and failure modes; keep synchronized POSIX mirrors as the default.

### Additional platform coverage

- [ ] Build and sign a complete non-mixing 16 KiB x86_64 package universe before
  enabling official x86_64 package transactions on 16 KiB Android.
- [ ] Finish the reproducible AArch64 Qt/KDE bridge sysroot.
- [ ] Improve Builder root reuse only after a stronger kernel-enforced immutable
  lower-root boundary exists; fresh per-build roots remain the safe default.

## P2 — Optional foreign runtimes

These are separate compatibility profiles. They must reuse the app-shell,
Wayland, audio, input, document, capability, lifecycle, and diagnostics
boundaries rather than introducing PRoot, VNC, or another desktop container.

### x86 Linux on ARM64

- [ ] Run a bounded FEX feasibility lane on a 4 KiB ARM64 device: static x86_64
  CLI, dynamic glibc CLI, process supervision, Wayland, Pulse, and virpipe.
- [ ] Require W^X JIT behavior, Android seccomp/signal compatibility, safe root
  lookup, and 16 KiB strategy before product integration.
- [ ] Choose explicitly between a complete x86 compatibility environment and a
  content-addressed foreign runtime. Never mix x86_64 and AArch64 packages in
  one pacman database or silently emulate a rejected package.

### Windows applications

- [ ] Prototype native AArch64 Wine/Hangover with FEXCore and WOWBox64 engines,
  Wine's Wayland driver, the existing Pulse endpoint, and per-app Wine prefixes.
- [ ] Start with a simple Win32 application. Add DXVK only after the production
  Vulkan bridge passes; add VKD3D-Proton only when the device meets its stronger
  requirements.
- [ ] Treat compatibility profiles as versioned, reviewable data bound to exact
  runtime and device versions. Profiles must never download executable
  components implicitly.

### Optional isolation profile

- [ ] Research a separate Android runner UID with immutable runtime inputs and
  brokered files for untrusted foreign applications. Do not describe an app
  shell as process isolation while Linux execution remains under the manager
  UID.

## Not planned for the Android application

- A custom Android Home/launcher replacement.
- PRoot or `ptrace` mediation in the normal execution path.
- VNC or a complete Linux desktop framebuffer as the primary UI.
- Root, chroot, mounts, hidden APIs, private SurfaceFlinger APIs, or direct
  vendor device-node access.
- Per-wrapper Linux roots or package closures in the production Arch model.
- Application-specific source patches used to manufacture support claims.
- Lepton or Waydroid inside Archphene; they solve Android-on-Linux and require a
  different host authority.
- ArchpheneOS work before the Android application reaches its release gate and
  the user explicitly starts that separate project.

## Permanent engineering guardrails

- Preserve Android's UID, SELinux, seccomp, lifecycle, permission, installer,
  storage, and window boundaries. Compatibility code must not claim privilege it
  does not have.
- Authenticate Binder callers from kernel UID and installed signer; never trust
  caller-supplied package names, Linux paths, or capability strings.
- Keep JNI coarse and versioned. Pass descriptors, direct buffers, Surfaces,
  AHardwareBuffers, and bounded snapshots instead of object graphs or pixels.
- Allocate no heap objects in warmed frame, pointer, touch, audio callback, or
  compositor dispatch paths. Bound every queue, field, path, window, process,
  descriptor, package operation, buffer, and timeout before retention.
- Cache only verified immutable inputs by content identity. Persist state before
  every external handoff or mutation boundary and make recovery explicit.
- Keep Android installation confirmation serialized, continue unrelated jobs
  after one package fails, and preserve state-restoring test defaults.
- Use full-device screenshots, rendered-pixel checks, accessibility trees,
  geometry, input traces, logs, resource counters, and exact artifact manifests
  for user-visible claims.
- Require emulator and physical-device evidence proportional to the changed
  boundary. A simulated desktop configuration is not physical DeX evidence.
