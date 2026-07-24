# Project status

Updated: 2026-07-24

This page separates validated behavior from planned platform work. Package search does not imply package compatibility.

## Greenfield Rust + Kotlin replacement

The new `android/app` shell and root Rust workspace now build with Gradle 9.6.1,
AGP 9.3, its built-in Kotlin plugin, JDK 26, SDK/Build Tools 36, NDK 29, and
Rust 1.88. The APK contains one Kotlin Activity, one Service-owned native
runtime, reusable direct buffers for batched input and status snapshots, and
generation-checked bounded native handles.

Clean-data and reuse gates pass on the API 36 x86_64 emulator and Samsung
SM-S908U. They prove cold launch, full-device insets and screenshots, touch
batching, Activity recreation, HOME/resume continuity, Back shutdown, private
root creation and required modes, version validation, and idempotent service
restart. The private root currently establishes conventional `/usr`, `/etc`,
`/var`, `/opt`, `/home`, `/tmp`, and `/mnt/android` layout locations; it does
not yet contain a complete base userspace, but verified package closures now
populate it incrementally through pacman's normal local database.

The replacement also owns a fixed 11,808-byte package-operation journal. It
holds at most 32 bounded jobs, enforces legal transitions, publishes updates
atomically, detects corruption, rejects symlink substitution, converts
interrupted active work into an explicit retryable failure, and reuses terminal
slots deterministically. Host tests prove the warmed in-memory input and job
paths allocate no heap objects.

The verified pacman, bsdtar, GnuPG/GPGConf, generic path bridge, patched glibc
loader, and complete ELF closures are now staged into content-addressed
exact-ABI Android native payloads. Rust validates a bounded signed-APK manifest
and every packaged file before creating private symlink aliases, invokes tools
directly through the patched loader with a cleared environment and bounded
output/timeout, and proves the real pacman version before publishing readiness.
Exact-ABI debug artifacts are 38 MiB for x86_64 and 36 MiB for arm64-v8a;
executable native payloads and signed keyrings are deliberately extracted
because child processes cannot consume them from mmap-only APK entries. Clean
and reused-root gates pass on the emulator and Samsung with full-device
screenshots, lifecycle/input checks, and scoped fatal logs.

Official exact-ABI repository catalogs are now connected. Rust selects the
fixed HTTPS endpoint and opens one bounded transfer file; Kotlin's Android TLS
stack writes through the duplicated descriptor; Rust validates the completed
size and file type, syncs it, and atomically publishes mode-0600 `core.db` and
`extra.db`. No fake-root pacman mutation or unbounded in-memory database copy
is used. Rust then runs read-only pacman search off the UI thread, validates
and normalizes at most 100 results into a fixed 16 KiB response, and treats
pacman's empty exit-1 no-match result normally. Clean refresh, search, and
process-death reuse gates pass with current `dotnet-sdk` results on x86_64 and
`btop` on Samsung AArch64, using full-device screenshots and scoped logs. The
official Arch Linux ARM repositories currently return no `dotnet-sdk` match;
the app shows that as an empty result rather than an error.

Read-only dependency resolution is also connected. Packaged pacman emits the
repository, package, version, archive filename, exact HTTPS URL, and download
size for the complete closure into the same fixed response. Rust rejects
unknown endpoints, unsafe fields, duplicates, missing targets, oversized
closures, and malformed output before Kotlin renders package details. The
emulator resolves current `dotnet-sdk` to 33 packages and a 185 MiB download;
Samsung resolves current AArch64 `btop` to nine packages and a 13 MiB
download. Both pass process-death reuse, scoped-log, and full-device screenshot
gates.

The Kotlin UI now exposes a functional **Install** action. It persists and
renders Queued, Resolving, Downloading, Verifying, Publishing, Installing,
Complete, and Failed work. Android transports exact official archives and
detached signatures through Rust-owned bounded descriptors; Rust enforces the
resolved byte size, atomically publishes mode-0600 cache files, imports the
sealed architecture-specific keyring and owner trust into a fresh private
keybox, verifies each detached signature, and checks the signed `.PKGINFO`
name, version, and architecture. AArch64 additionally requires the pinned Arch
Linux ARM build signer.

Immediately before mutation, Rust re-resolves and re-verifies the full bounded
closure. Pacman then commits it in dependency order to the shared private root,
using the generic compatibility layer to map Linux root ownership to the
Android app UID, copy when SELinux rejects hard links, and avoid Android app
seccomp's blocked `fchmodat2`. The current path recovers its bounded stale-lock
and incomplete-entry cases and proves the requested package through pacman's
local database.

Clean nine-package `btop` installs pass on the x86_64 emulator and AArch64
Samsung. Both gates deliberately corrupt the target archive, prove rejection,
redownload and reverify it, check the installed executable and database entry,
then prove the durable Complete result survives manager process death with
full-device screenshots and clean scoped logs. Package update/removal,
hooks/scriptlets, closure-wide rollback, full dependency validation,
cancellation, and low-storage recovery remain open, so this is not yet a
complete production transaction engine.

The validated prototype below remains reference evidence until replacement
vertical slices pass equivalent gates. Installed prototype state is no longer a
replacement requirement.

## Latest regression snapshot

On July 22, 2026, a current-source x86_64 debug manager was built with the reproducible Podman toolchain, installed on the API 36 emulator, and passed the complete broad emulator regression in one sequence. The run covered package update and refresh, repository search and version selection, Android app-settings routing, authenticated runtime-pack execution and cleanup, KCalc launch/calculation/menu/rotation, native compositor input, Android PackageInstaller update, and Mousepad document, IME, touch, secondary-window, and live-theme behavior.

The connected Samsung SM-S908U (Android 15, AArch64) now runs the exact current-source manager and Terminal under the maintained development signer. Because the original prototype key was unavailable, the explicitly authorized reset was preceded by verified archives of both installed APKs and their private state; 1.60 GiB of manager package/runtime data and 46 MiB of Terminal home/runtime were restored under the new UIDs. Managed Arch Bash, the ARM native catalog, and manager startup/catalog rendering pass. The old Foot, KCalc, and Mousepad wrapper APKs plus persistent Linux homes were separately archived before their deliberate signer migrations.

Foot, KCalc, and Mousepad have now completed that physical migration and are installed by the manager under its maintained wrapper signer. Their restored Linux homes survive repeated same-signer updates. Foot passes its focused visual/runtime workflow; KCalc passes calculation, menus, contrast, live theme, manager appearance overrides, rotation, and descriptor lifecycle; Mousepad passes accessibility, IME, touch, Preferences checkbox/close interaction, Material You checked-state pixels, primary-host cleanup, and document restart/conflict/writeback. The migration also exposed and fixed a destructive ordering bug: Archphene now obtains Android's per-source install consent before uninstalling an older-signed wrapper and asks for final replacement confirmation only after returning from Settings.

The focused Foot workflow now passes on both the current-source x86_64 emulator and AArch64 Samsung wrapper: real InputConnection UTF-8 preedit/commit, Android-to-Linux clipboard paste, Linux selection copy/paste, visible scrollback, live display resize with stable Android/Linux processes, graceful Activity/runtime destruction, force-stop, and clean cold relaunch. Samsung dark-mode frames and emulator light-mode frames are retained as evidence. The run also exposed and fixed an auxiliary-command boundary bug: Bash found Android's unverified `clear` through `/system/bin` and leaked the glibc preload into Android's linker. Verified pack commands are now first in PATH and exact-path brokered, while unpublished host commands fail with status 127 on both architectures. Publishing additional dependency commands remains a separate bounded product decision.

The repository audit, release-workflow contract, AT-SPI source contract, Bash syntax sweep, and Android test-helper regression also pass. This validates the broad entry points, not every hardware-specific or standalone script in `scripts/`. A mechanical comparison against the removed PowerShell tests found several high-risk conversions with fewer check signals. Secrets, accessibility, the capability broker, direct and unmodified-consumer camera paths, printing, microphone capture, Terminal managed-shell/project-tree/home-document/transfer behavior, Terminal companion isolation, GUI documents, standalone Mousepad SAF/edit/save/cold-reopen and Open-dialog/IME workflows, manager list/detail/update/filter/background-job/package-replacement/production-self-update behavior, package-runtime trust, `wl-clipboard`, and drag-and-drop now have restored assertions and executed device lanes; finishing the remaining standalone-script assertion audit remains a P0 item in `todo.md`. The Open-dialog gate also drove a generic AT-SPI cache fix: GTK file-chooser rows now expose live, distinct bounds and state and can be selected and opened through Android framework actions on both devices. The restored manager workflow found that physical AArch64 update checks were querying Arch Linux's x86-only web metadata and returning HTTP 404; pacman applications now compare against fresh, bounded metadata from the repository selected by the current runtime policy, and KCalc reports current on both architectures. The package-runtime gate now independently proves ABI-specific pacman execution, nonempty libalpm resolution, live repository download, exact signer continuity through detached verification, and GPG rejection after byte tampering on x86_64 and AArch64. The managed-shell gate now proves the real GNU architecture, package inventory/search/info, writable persistent Home, cold restart, and clean runtime output on both devices. It found that generic managed commands could not re-enter the APK-owned loader and that manager requests depended on Android commands correctly denied by the bridge; loader trust is now captured immutably, AArch64 interposers export the `GLIBC_2.17` symbols imported by Bash, requests use Bash-native operations, and only bounded internal `sleep` plus `cat` cross into Android. The companion gate independently proves manager-embedded/build-output Terminal parity, version/UID isolation, PTY ownership, signed request/result routing, forged-intent rejection, and provider denial on both devices. Terminal document transfer independently proves exact SAF import/export plus hidden-home and traversal denial with unique, self-cleaning fixtures on both devices. The capability-broker gate uses a disposable ABI-matched NDK probe under the current generated KCalc UID and proves permission gating, notification lifecycle, HTTPS dispatch, unsafe-URI rejection, and cross-UID denial without replacing the wrapper. The restored clipboard gate drives official manager-generated `wl-paste` and `wl-copy` packages through Android confirmation and proves exact bidirectional content, persistent source ownership, focused selection/source protocol, demand-driven reads, and clean exit on x86_64 and physical AArch64. The legacy KCalc update entry point now delegates to the supported manager-generated wrapper transaction instead of the retired hand-built APK path; replacement checks prove exact bytes/signer, stable version/UID/first-install identity, Android confirmation, manager completion, and post-update execution on both devices. The hardened microphone gate directly measures bounded raw capture and rejects silence; current `pavucontrol` wrappers produced 287,040 bytes with 215,281 nonzero samples on x86_64 and 288,000 bytes with 207,607 nonzero samples on Samsung, with fixture cleanup and physical privacy restoration verified. The restored production updater gate verifies the published `v1.0.0` checksum and exact x86_64 baseline, discovers and installs the real `v1.0.1` compatibility asset, proves production signer and stable install identity, and rejects stale post-restart update state; its destructive run passed in an independently cloned temporary AVD.

The latest standalone tranche additionally passes the glibc path/exec broker, x86_64 and AArch64 manager-native catalogs, signed runtime-provider denial, unpublished-command isolation, audio playback, nonzero microphone capture, Foot legacy-config preservation, Terminal prompt collapse, manager downgrade/checksum rejection, persistent Keystore wrapper signing, and direct libalpm candidate signature verification. Device cases pass on both maintained targets and restore privacy, notification, package, and running state. The signing gate now requires byte-identical manager input, preserves version/app-ID/first-install identity, verifies nonempty v2/v3 output, and deletes its large fixtures. The stale AArch64 signature gate now consumes the current staged artifact and read-only Podman cache instead of deleted fixture paths; all 109 manifest archives reverify against the pinned Arch Linux ARM build fingerprint, and the complete non-installing Samsung physical suite passes without skipping that gate. The host PipeWire producer audit found that the migrated test never enabled its synthetic source and therefore could not register in a container; the repaired producer gate and the full unmodified-GStreamer frame/timestamp/cleanup/runtime-preload gate now pass. The AArch64 native-readiness script also runs through the pinned Android-native container when NDK 29 is absent from the configured host SDK.

The first package-compatibility wave now has current-source x86_64 and manager-owned AArch64 wrappers for GNOME Text Editor, Kate, and Foot. Kate's daemonized GUI process is retained by the Activity-tied runtime supervisor and remains stable through tablet rotation and a real temporary 1920x1080 emulator display with display-targeted input. Foot passes its complete focused workflow on both architectures. GNOME Text Editor passes generic SAF edit/writeback/cold reopen, live/cold theme, state-preserving tab/menu/Preferences accessibility, Android paste, non-Latin/emoji preedit and commit, Linux copy, exact Android clipboard readback, and graceful state-restoring close on both devices.

Appearance propagation now covers Qt 6/KDE, GTK 3, GTK 4/libadwaita, and adapted
Foot. The rebuilt KCalc, Mousepad, and Foot fixtures pass
semantic accessibility/content-geometry gates, live light/dark, automatic
phone/tablet/docked density, and all explicit phone density modes. KCalc menus
and status, Mousepad Preferences, and Foot's 42 px text/126 px touch CSD are
readable and bounded on x86_64. Current-source AArch64 KCalc and Mousepad core
appearance/interaction cases also pass on Samsung, including manager light/dark
override, real Material You widget pixels, and stable-process Mousepad changes
from 100%/18 dp to 200%/22 dp with independent 32/48 dp interaction targets.

The first real Code-OSS transaction resolved, signature-verified, extracted,
and classified its 36-package closure. It then failed closed before wrapper
creation on an absolute icon symlink into `/usr/lib/code`. This exposes the
generic Electron package-model gap: runtime packs do not yet publish package-owned
`/usr/lib/<app>` trees or dependency executables such as `electron42`. Code must
not receive an application-specific bypass.

## Validated

| Area | Evidence |
|---|---|
| Manager self-update | Public GitHub Releases discovery, bounded download, SHA-256 verification, signer/package validation, Android confirmation, replacement, and restart reconciliation. The reproducible workflow published exact-ABI `v1.0.1` assets; live `0.9.0` to `1.0.1` updates pass on x86_64 and physical AArch64. The restored standalone gate independently verifies the real published x86_64 `v1.0.0` checksum/baseline, one-time compatibility alias, production signer continuity, stable UID/first-install identity, installed version, and reconciled restart in an isolated temporary AVD. |
| General x86_64 package transactions | Arch dependency resolution, package-signature verification, closure staging, desktop/terminal classification, package-specific label/executable/icon/MIME/toolkit/ABI/capability metadata, generated APK validation, persistent Android Keystore signing, and PackageInstaller installation pass with KCalc, Mousepad, and CLI packages; a concurrent missing-package failure does not block an unrelated CLI transaction |
| AArch64 package runtime | A cacheable Linux container resolves the current Arch Linux ARM pacman/GnuPG/libarchive closure, verifies every package with the pinned build-system key, reduces it to required AArch64 ELF objects, cross-builds patched glibc and the path broker, and emits a 70-entry checksum catalog. The dual-ABI manager selects isolated ARM repository/trust assets; Samsung tests pass package search, nine-package libalpm resolution, exact build-key verification, staging, terminal classification, authenticated runtime-pack publication, Terminal UID materialization, and `btop 1.4.7` execution |
| Qt, GTK, and direct-Wayland appearance | Unmodified KCalc/Qt and Mousepad/GTK3 pass functional, accessibility, geometry, contrast, popup/dialog, density, and live-theme gates on current x86_64. Current-source Samsung wrappers repeat those core Qt/GTK3 cases. GNOME Text Editor/GTK4 passes stable-process live light/dark and cold dark launch on current-source x86_64 and manager-owned AArch64. Foot passes readable density-aware visuals, live theme switching, UTF-8 IME, clipboard/selection, scrollback, resize, and lifecycle on both architectures. Broader application and external-display coverage remain. |
| Shared bridge runtime | KCalc, Mousepad, generated wrappers, and native probes compile against one Android Activity/InputConnection/clipboard/window host and one Rust compositor; the application Activities are metadata-only subclasses. Official unmodified `wev` and manager-generated `wl-paste`/`wl-copy` packages validate core seat input, exact bidirectional plain-text transfer, persistent source ownership, focused selection/source protocol, and demand-driven Android clipboard reads on x86_64 emulator and physical AArch64. |
| Shared runtime packs | Verified Arch dependency closures are published atomically as immutable content-addressed packs owned by the manager; an exported caller-authenticated provider grants exact read-only module URIs to the generated wrapper UID, Binder-death leases protect active wrappers, cold app-drawer relaunch loads the active pack, untrusted shell access is rejected, superseded/manual-cache unbound packs are reclaimed, and the KCalc wrapper shrank from 57 MB to 629 KB. Each launch uses an Activity-tied subreaper supervisor so daemonized GUI descendants remain owned until the complete Linux tree exits. KCalc survives rotation without duplication and leaves no Linux descendants after Back or force-stop; the runtime-FD lease/cleanup regression passes after the supervisor change. |
| Secondary-window registry | Parent/child xdg_toplevel ownership is bounded to 32 simultaneous windows, rejects cyclic and cross-client parents, and clears destroyed-parent references. Active-window routing, composited phone policy, separate Android Dialog hosting in freeform/tablet mode, semantic child input, close, and parent restoration pass the emulator regression; current-source Samsung Mousepad also passes checkbox interaction, child close, primary restoration, and final host cleanup. |
| Native compositor bootstrap | Rust wayland-server core cross-compiles for Android x86_64 and AArch64; registry/compositor/SHM/xdg-shell/pointer/keyboard/touch seat discovery, SCM_RIGHTS SHM and sealed XKB v1 keymap FD transfer, checked padded-stride frames, ordered xdg configure queues, partial/final acknowledgements, mapped/unmapped lifecycle enforcement, and validated xdg_positioner state/destruction, commit-gated popup configure geometry, output-bound flip/slide/resize constraint adjustment, reactive output and committed-parent-geometry reconfiguration, double-buffered xdg window geometry, popup-grab focus preservation across root commits, snapshot-and-commit wl_region input state, effective-region popup fall-through, recursive wl_subsurface composition/input with parent-atomic synchronized content/position/stack latching, input-serial grab validation, nested topmost grab stacks, root-to-popup hit testing and local-coordinate pointer/button routing, clipped stacking-order SHM popup composition with ARGB/XRGB blending, child-first idempotent popup_done/teardown with root focus and pixels restored, wl_data_device_manager same-client input-serial-gated text source/offer/selection/cancellation lifecycle plus focused descriptor-backed Android ClipboardManager transfer in both directions, zwp_text_input_v3 focus and double-buffered enable/surrounding/cursor lifecycle with Android InputConnection content-purpose mapping, arbitrary UTF-8 preedit/commit, delete, editor-action, show/hide sequencing, and invalid-input rejection, demand-driven ClipboardManager reads with self-publish suppression, inverse wl_surface buffer transform/scale with retained-source reinterpretation, accumulated logical/buffer damage translated through synchronized subsurface trees into clipped root presentation batches, double-buffered wp_viewporter crop/destination scaling, wp_fractional_scale_v1 feedback, cursor-role SHM buffers isolated from application composition with Android PointerIcon transfer, zwp_pointer_gestures_v1 swipe/pinch/hold streams, and wl_output surface-enter/mode/scale updates, with post-ack Android bitmap presentation and Choreographer-timestamped frame-callback pacing, focused pointer, wl_pointer v9 value120/relative-direction wheel axes, wl_touch motion, two-pointer gestures, and hardware-key events routed from Android input, exact wire/pixel checks, and resource teardown pass on both |
| Package job scheduler | Per-package phase/error state and a bounded structured diagnostic history survive Activity recreation and manager process death; legacy jobs migrate without data loss, two preparation jobs can overlap, wrapper mutation/signing and Android confirmation are serialized, package failures are isolated, and list/detail progress, recent phases, cancel, retry, installer completion, and interrupted-completion reconciliation pass emulator tests |
| Terminal command channel | Up to eight foreground-service-owned PTYs issue collision-free per-request pacman facade commands. Search results and durable package-job resolve/download/install/complete/cancel/error states return through a signature- and caller-verified Terminal provider. A verified Arch Bash 5.3.15 closure becomes the default shell after installation; PTY startup, `C.UTF-8`, package queries, and home writes pass on the 4 KB x86_64 emulator and physical ARM64 Samsung device. A real `tree 2.3.2` install and fresh-session execution pass on x86_64, physical AArch64 executes managed `btop 1.4.7`, and untrusted shell result injection is rejected. The 16 KB x86_64 emulator fails closed with an explicit upstream-loader compatibility result |
| Terminal project folders | A user-selected SAF tree receives a persisted read/write grant and a stable `$HOME/Projects/<alias>` local mirror. Emulator validation covers recursive initial pull, local push, Android pull, process-restart reuse without a prompt, simultaneous-edit conflict preservation, deferred deletions, symlink rejection, mapping removal, and fail-closed access after removal |
| Android capability broker | A randomized same-UID abstract Unix socket, bounded protocol, generated capability declarations, and ABI-specific glibc client provide Android URL opening and notification post/withdraw. Each wrapper now starts a private D-Bus session plus XDG OpenURI/Notification, classic freedesktop.org notification, and `xdg-open` adapters. A manager-generated KCalc passes contract discovery, permission-queued notification post/withdraw, HTTPS dispatch, unsafe-URI rejection, and process lifecycle on x86_64; the contract and first-use notification lifecycle also pass on physical AArch64. |
| Audio input/output | Packages with a verified Pulse client receive an `audio-output` wrapper that starts a private Pulse native-protocol server backed by Android AAudio with OpenSL ES fallback. Microphone input is a disabled-by-default per-wrapper setting and separate `audio-input` capability. The first Linux source stream triggers Android `RECORD_AUDIO` consent; grant, denial/no-reprompt, privacy-switch silence, process cleanup, and real nonzero capture pass with current manager-generated `pavucontrol` wrappers on x86_64 emulator and physical AArch64. The standalone capture gate also asserts bounded duration/bytes, nonzero samples, crash rejection, fixture cleanup, and restoration of the physical privacy switch. |
| Printing | The private XDG print adapter accepts regular PDF descriptors only, copies them into bounded same-UID staging, and opens Android's system print UI. PreparePrint, a rendered one-page preview, Save as PDF discovery, cancellation cleanup, invalid-PDF failure, non-regular-FD rejection, and authenticated runtime-pack binding pass on x86_64 emulator and physical AArch64. |
| Accessibility transport | A declared `accessibility` capability publishes a bounded acyclic virtual node tree on the compositor View and queues Android click, focus, set-text, and scroll actions back to Linux. A private AT-SPI2 adapter translates unmodified Qt, GTK 3, and GTK 4 application trees, actions, focus, menus, and secondary windows. Cache topology is hydrated with live component bounds, actions, state, relations, and newly discovered children; selected/sensitive state is preserved, model-button labels follow standard `LABELLED_BY` relations, semantic default actions are role-scoped, and defunct transient controls are removed. A test-only AccessibilityService verifies normalized framework bounds and semantic actions through KCalc, Mousepad, and GNOME Text Editor controls, edits, tabs, menus, dialogs, file-chooser result selection/Open, and parent restoration on x86_64 and physical AArch64 while preserving Text Editor's exact prior session. |
| Camera and PipeWire | A declared `camera` capability requests Android `CAMERA` only on first use, supports bounded one-shot Camera2 JPEG capture, and exposes a private XDG Camera/PipeWire stream. Real grant, denial/no-reprompt, pre-consent rejection, invalid dimensions, 1280x720 JPEG bytes, per-plane I420 diagnostics, and an official unmodified Arch Snapshot consumer with timestamped frames, foreground pixel inspection, and cleanup pass on x86_64 emulator and physical AArch64. Camera-capable GTK4 wrappers use Cairo because Samsung's GSK GL/NGL paths rendered valid planar textures as solid magenta; ordinary GTK4 wrappers remain accelerated. |
| Secrets and keyrings | A declared `secrets` capability exposes a per-wrapper AES-256-GCM collection backed by a non-exportable Android Keystore key and conditionally publishes `org.freedesktop.secrets` on the private session bus. Sender-bound plain and DH/AES sessions, create/search/get/set/replace/delete, content types, item signals, zero-length values, ciphertext, metadata, process-restart persistence, bounds, stale-socket rejection, and no-log-leak checks pass on 4 KB and 16 KB x86_64 emulators and physical AArch64. Unmodified Arch libsecret and KWallet clients pass on 4 KB x86_64. Official Arch Linux ARM `secret-tool`, patched `kwalletd6`, and official `kwallet-query` pass encrypted store/read/clear, daemon-restart persistence, and cleanup on physical AArch64; upstream 4 KB-aligned Arch x86_64 clients are skipped on 16 KB Android. |
| Drag-and-drop | Generated GUI wrappers declare a `drag-drop` capability. Bounded plain text and `text/uri-list` map bidirectionally between Android `DragEvent` and standard Wayland data devices. Android files import through the conflict-safe document session; Linux exports are restricted to visible-home files and use exact Android URI grants. Copy negotiation, transfer, import/writeback, denied and granted provider access, completion, cancellation, and cleanup pass on x86_64 emulator and physical AArch64. |
| GUI application documents | One manager-owned **Archphene Apps** DocumentsProvider exposes each generated GUI wrapper's visible Linux home while dotfiles and runtime state remain private. Per-wrapper endpoints require the manager signature permission and verify the calling package. `ACTION_VIEW` and `ACTION_EDIT` support up to 32 URI grants, collision-safe same-name imports, hash-based writeback, and concurrent Android-edit conflict copies. A document sent to an active `singleTask` wrapper presents a native warning before a generic safe restart; Cancel preserves the running app. Manager CRUD, direct-provider denial, active-app restart, same-name import, conflict preservation, writeback, and DocumentsUI browse pass on the x86_64 emulator and physical AArch64. |
| Package discovery | Official Arch name/description candidates use deterministic exact, executable, prefix, token, and description ranking; executable ownership is merged from repository file databases, glmark2-es2-wayland resolves to glmark2, and installed-app multi-term search shares the same matching rules |
| OpenGL ES bridge | Manager-generated GLMark2 wrappers start a same-UID Android virglrenderer helper. Mesa reports virgl over the emulator NVIDIA OpenGL ES translator and completes the 1080x2205 suite with score 12. On the Samsung Galaxy S22 Ultra, virgl uses Qualcomm Adreno 730 / OpenGL ES 3.2 and completes every 1080x2202 scene with score 15 and exit code 0. Both devices pass sustained distinct-frame gates and same-UID fault injection that replaces the helper and Linux process once with rendered virpipe recovery while retaining the Android host |
| 16 KB x86_64 loader | Patched glibc 2.43 is reproducibly linked with 64 KB PT_LOAD alignment and a 16 KB common page size. Every emitted loader/runtime ELF passes an independent alignment audit, and a similarly aligned dynamic executable runs through it inside the manager UID on the API 36 16 KB x86_64 emulator. Official Arch x86_64 package closures remain 4 KB-only and stay blocked. |
| Release display matrix | Fail-closed KCalc, Mousepad, and Foot gates combine raw/PNG frames, contrast, semantic trees, toolkit config, actual content geometry, scoped logs, and manifests across phone/tablet/docked emulator density profiles. Current-source Samsung repeats the core KCalc, Mousepad, and Foot phone cases. Kate separately passes stable-process tablet rotation plus an actual temporary 1920x1080 emulator display, task placement, mapping, and targeted input; sustained physical external-display coverage remains. |

## In progress

Local debug builds can remain multi-ABI. Release builds emit independently signed x86_64 and arm64-v8a manager APKs whose embedded Terminal, package runtime, trust data, and wrapper templates contain only the selected ABI. Both variants launch pacman on matching devices, and the ARM manager has generated, installed, and launched a real KCalc wrapper on the Samsung test device.

1. **Architecture support**
   - maintain the validated exact-ABI release workflow and production self-update regressions;
   - use official Arch Linux packages only for x86_64 and Arch Linux ARM packages/trust roots for AArch64;
   - accept repository packages marked any, but require exact CPU ABI for native ELF files;
   - do not silently emulate x86_64 on ARM.


## Pending

- Broaden printing, audio, accessibility, and keyring compatibility beyond the validated applications and devices.
- Zero-copy Android HardwareBuffer/dmabuf presentation, Vulkan, and broader physical-device GL application coverage; the current validated x86_64 and AArch64 OpenGL ES virpipe path presents through SHM, replaces a failed helper once, and retains a bounded llvmpipe fallback only if replacement recovery fails.
- Rich notification actions, non-HTTP URI policies, and remaining desktop portals.
- Broader Qt, GTK, SDL, Electron, and Rust-native compatibility.
- GrapheneOS Pixel and sustained desktop-mode validation.
- Project trees and granted GUI documents currently use explicit synchronized mirrors; a live SAF path broker remains pending. Optional shells beyond managed Bash have not been selected.
- Build a separately signed 16 KB x86_64 package universe. The Archphene-owned glibc loader now passes real 16 KB Android execution, but official Arch executables and shared objects remain 4 KB-aligned. The manager continues to block Add/install on 16 KB x86_64 until an entire no-mixing closure, including late-loaded modules, is rebuilt and validated.
- Pin the missing KConfig development sysroot needed for a completely self-contained AArch64 Qt bridge rebuild, and restore the GTK settings bridge's clean container dependency declaration.
- Broaden the validated Qt/GTK theme, density, focus, menu, and dialog behavior beyond KCalc, Kate, Mousepad, and GNOME Text Editor and across the remaining release representatives.

## Package-manager efficiency rules

- Cache repository databases, verified package archives, dependency graphs, extracted immutable modules, and wrapper templates by content hash.
- Download once and reuse only after signature/hash verification.
- Bound downloads by package-declared and global size limits.
- Keep Android confirmation serialized so users always know which app is being installed.
- Continue unrelated jobs after one package fails.
- Persist state before every phase transition so process death or reboot can resume or report a precise failure.
