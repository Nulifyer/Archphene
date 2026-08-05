# Project status

Updated: 2026-08-05

This page is the evidence ledger for implemented and validated behavior.
Package search does not imply package compatibility. Chronological checkpoints
record their state at the time of validation. The authoritative forward plan is
[`todo.md`](../todo.md); supported application claims are maintained in the
[compatibility matrix](compatibility-matrix.md).

## Generated app-shell visibility checkpoint

The generated app-shell template and manager now share the narrow
`org.archphene.category.GENERATED_APP_SHELL` marker. The template adds it to the
existing exported `MAIN` Activity, while the manager declares an exact marker
intent in `<queries>` and performs bounded `queryIntentActivities` discovery.
Only the generated package-name shape and production Activity class enter the
marker set. A marker match never authorizes a shell: reconciliation still starts
from the Rust registry and independently verifies package name, app-shell
signer, descriptor digest, Android version/generation, manager package,
template digest, capability metadata, and the manager's active PackageInstaller
sessions. Current-template shells must also resolve through the marker.

The investigation does not support removing `QUERY_ALL_PACKAGES` in the same
release. Pre-marker shells are invisible if a user skips the migration release,
and a hostile non-marker package occupying a deterministic generated identity
cannot be inspected before Android rejects the signed update. Archphene retains
the app-store visibility permission until both cases have a tested fail-closed
migration. No broader package data is used for launcher reconciliation.

## Manager-hosted Quick launch checkpoint

The Packages page now offers **Quick launch** when the exact resolved package
has one current graphical launcher descriptor. The manager passes the cached
package identity, full descriptor digest, and desired generation to a
non-exported manager Activity. The app-shell Service accepts the separate Binder
transaction only from the manager UID and reauthorizes the unpublished
descriptor before open, Surface attachment, input, close, and process start.
The native registry requires the descriptor to remain desired and generation-
current but does not require a published Android wrapper. This path does not
mutate launcher publication state or call PackageInstaller; **Add selected**
remains the explicit app-shell publication action.

On the Samsung SM-S908U at 420 dpi, the current manager Quick-launched
unmodified Mousepad descriptor generation 1877 into a 1080×2316 Android Surface
with a 432×926 logical output. The manager-owned Linux process connected,
published one toplevel, presented repeated frames, accepted a bounded touch
record, and exited after Back closed the session. The installed Mousepad app
shell was not used for this launch, and no new PackageInstaller confirmation was
accepted. This is a prototype claim: the manager surface does not yet provide
the generated app shell's complete document, notification, chooser, camera,
printing, secret, accessibility, multi-task, or restoration contract. Packages
with several graphical descriptors still require a chooser before Quick launch
can be offered.

## Android app-shell intent and lifecycle checkpoint

Binder protocol v21 preserves Android document actions when an app shell already
owns multiple tasks. A new `ACTION_VIEW`, `ACTION_EDIT`, or `ACTION_SEND` removes
the old independent Android attachments, closes the one manager-owned Linux
application, and starts one replacement root session with the exact validated
`content://` document. A cold document launch also replaces a root retained by
the 15-second reconnect grace instead of returning busy.

`ACTION_EDIT` now opens the granted URI read/write, imports through the same
bounded 512 MiB manager path, and retains only a session-scoped URI target in the
visible app shell. On close, the manager reopens the exact imported regular file
with `O_NOFOLLOW`, verifies one link and the size bound, and sends a read-only
descriptor through an authenticated v21 callback. The app shell queues provider
writeback on its document worker and opens the original URI with `rwt`; file
bytes enter neither a Binder parcel nor a file-sized managed aggregate. Missing
write permission, stale sessions, non-content URIs, signer or MIME mismatches,
links, oversized files, and manager loss fail closed.

On the Samsung SM-S908U, unmodified Mousepad held a root and independent child
task at the temporary 720 dp viewport. Each of `ACTION_VIEW`, `ACTION_EDIT`, and
`ACTION_SEND` then replaced both attachments and imported exact provider bytes;
the edit lane appended and wrote back `archphene-edit-writeback-<token>` to the
original provider URI. The wrapper-owned portal/classic notification lane also
passed while both tasks existed, including content-intent return and withdrawal.
Closing root session 5 retained Mousepad PID 31558 and child task 5760; closing
the child then removed the process. Force-stopping both tasks retained Mousepad
PID 28062 only for the reconnect grace, logged both Binder deaths, and removed
the process when the grace expired. The density override was restored to 420.

Manager absence and wrapper removal remain open because exercising either on the
current physical manager would terminate or uninstall retained user sessions;
they require an isolated populated manager instance or explicit destructive-test
authorization. This checkpoint makes no DeX or external-display claim.

## Android app-shell multi-task checkpoint

Binder protocol v21 now keeps one manager-owned Linux application session while
up to eight authenticated app-shell Activities attach independent window tokens
and Surfaces. The native compositor retains a bounded aggregate of 33,554,432
attached pixels, presents each independent toplevel into its Activity Surface,
keeps popups with their parent, and routes focus, input, IME, clipboard, cursor,
accessibility, documents, notifications, and other brokers through the active
authenticated task. A 15-second reconnect grace preserves the Linux process and
toplevels across app-shell process death while document-task identities reuse
their stable component and `archphene-window://` data.

On the Samsung SM-S908U, unmodified Mousepad opened `Untitled 2` from
`Ctrl+Shift+N`. At the normal 420 density override, the callback reported one
independent toplevel while Android retained one compact task. The compact task
now reserves a 56 dp Android control row only when independent windows exist;
its bounded `Switch window (2)` action and `Ctrl+Tab` shortcut cycle native
Wayland activation without creating another Android task.

A temporary 240 density override changed the live capability policy to a 720 dp
adaptive window; the same package then held task 5714 for `LauncherActivity` and
task 5715 for the internal `LauncherWindowActivity`. Both shared wrapper PID
29186, manager PID 28708, and Linux Mousepad PID 29025. Home, task switching,
child close, and root resume retained Linux PID 29025. Killing the background
app-shell process and reopening the primary task recreated both authenticated
sessions against root session 1, reused tasks 5714 and 5715, and retained Linux
PID 29025. Closing the child removed only its task and toplevel; closing the
final primary task removed Mousepad while the cached Android app-shell process
could remain.

The current policy also passed a live 420→240→420 transition. Mousepad PID 22126
remained unchanged as root task 5733 gained child task 5734, then the child
released only its Android attachment and root task 5733 resumed with the compact
switcher. Policy inputs are the current inset-adjusted window metrics, display,
pointer, hardware keyboard, and observed independent-window capability; no OEM
or model check is present. The device density override was restored to 420.

This is physical evidence for generic compact/adaptive task ownership, not a
DeX or external-display claim. Physical DeX, the x86_64 emulator parity matrix,
root-task close while another independent task remains, and the complete
single-window application regression remain open release gates.

The final source state passes all locked Rust workspace tests, workspace Clippy
with warnings denied, app/app-shell/Builder JVM tests and debug lint, every
repository contract, and exact x86_64 and arm64-v8a manager APK builds. A fresh
isolated `org.archphene.app.debug.p0window` install then passed the physical
Samsung base regression with PID 30535 and runtime generation 4294967297 stable
across recreation and Home/resume; the temporary package was removed and the
device density remained restored to 420.

## SuperTux checkpoint

The current workspace passes `cargo fmt`, all locked Rust workspace tests,
workspace Clippy with warnings denied, the complete JDK 26 Android unit/lint
gate, every repository/source contract, and Bash syntax validation for the
SuperTux workflow. Exact arm64-v8a and x86_64 manager APKs are installed on the
Samsung SM-S908U and emulator. Their generated app shells were updated through
Android's normal PackageInstaller confirmations; the current SuperTux wrapper
versions are 1727 on Samsung and 1158 on the emulator. Both use authenticated
Binder protocol v19.

The current app-shell work adds generic one-finger
Android-to-Wayland pointer routing, device-defined touch slop, SDL phone
orientation policy, three-level editor evidence for empty and populated text
fields, view-to-buffer input scaling against the compositor's returned logical output extent,
pointer-click keyboard focus, fullscreen/maximized state handling,
bounded per-input-kind diagnostics, and audio foreground/focus lifecycle
handling. The touch hot path uses retained primitive state rather than
per-motion heap objects. On the Samsung, a complete injected finger gesture
produced an accepted pointer-motion batch followed by one atomic motion,
primary-press, and primary-release batch: `kinds=0x40 records=1 result=1`, then
`kinds=0x100 buttonStates=0x3 records=3 result=3`. Full-device screenshots,
SurfaceFlinger geometry, and manager logs confirm the current 2241×978 Surface
inside the 2316×1080 landscape device frame; cropped app screenshots are not
used as visual evidence.

The state-preserving SuperTux gate now passes on both maintained devices. It
keeps the installed `supertux 0.7.0-1` SDL2/sdl2-compat menu quirk separate from
bridge conformance: an ordinary finger gesture proves generic Wayland pointer
focus and atomic primary-button delivery, while keyboard input activates menu
rows and enters a real contributed level. Full-device frames prove movement
and jumping without an Android IME, and the same wrapper, manager, and Linux
process survive Home/resume plus a live 1920×1200 resize. Pulse logs identify
`application.name = "supertux2"` and prove Android audio-focus grant, abandon,
and reacquisition. The same state-preserving workflow now enters the stock
Video menu, enables fullscreen, and restores windowed mode. Compositor
snapshots prove requested-state transitions from `windowStates=1` to
`windowStates=0` with advancing configure serials; full-device frames show the
corresponding checked and unchecked controls without process replacement. Both
runs reject scoped fatal logs and restore the exact prior SuperTux and Android
state. Signed manifests and inspected evidence are under
`tooling/artifacts/supertux-workflows/emulator-5554/fullscreen-final-3` and
`tooling/artifacts/supertux-workflows/RFCT90AEEFA/fullscreen-final-5`.

The audio failure exposed a package-generic loader gap rather than a SuperTux
special case. Pulse-enabled graphical launches now select SDL and OpenAL Pulse
backends and include the verified `/usr/lib/pulseaudio` private directory, so
OpenAL can load `libpulsecommon-17.0.so`. Generated app shells also suppress an
implicit ambiguous-text IME after hardware gameplay keys while preserving
explicit long-press and editor-backed IME requests, including fields that become
empty after editing. Pulse sink suspension is serialized and completes before
Android audio focus is abandoned.

Pointer capture is not an applicable SuperTux 0.7.0 workflow. An audit of the
exact upstream `v0.7.0` commit found no SDL relative-mode, mouse-grab, capture,
or pointer-lock request. Its [window creation][supertux-window] sets only
resizable and optional fullscreen flags; its [editor mouse path][supertux-mouse]
uses absolute coordinates; and cursor hiding calls only `SDL_ShowCursor`.
Archphene's generic relative/locked/confined-pointer bridge remains validated by
its real Wayland client and exact-ABI compositor probes. The SDL release lane
still requires a different unmodified application that explicitly requests
relative mode; no SuperTux-specific workaround or false capture claim was added.

[supertux-window]: https://github.com/SuperTux/supertux/blob/c11dfb2a2aa429be3db8de96b7fca6a6236eddc3/src/video/sdlbase_video_system.cpp#L85-L122
[supertux-mouse]: https://github.com/SuperTux/supertux/blob/c11dfb2a2aa429be3db8de96b7fca6a6236eddc3/src/editor/editor.cpp#L1222-L1225

The unrelated user-owned modification to
`scripts/test-archphene-package-update.sh` remains excluded from this work.

## Greenfield Rust + Kotlin replacement

The new `android/app` shell and root Rust workspace now build with Gradle 9.6.1,
AGP 9.3, its built-in Kotlin 2.2.10 plugin, JDK 26.0.2, SDK/Build Tools 36.0.0,
NDK 29.0.14206865, and Rust/Cargo 1.88.0. The committed Gradle wrapper and
distribution have official SHA-256 checksums, resolved Gradle artifacts are
hash-verified, Cargo is locked, and the native container base is immutable.
The non-downloading toolchain contract, full offline Rust tests, Android lint,
and both exact-ABI APK builds pass. The APK contains one Kotlin Activity, one
Service-owned native runtime, reusable direct buffers for batched input and
status snapshots, and generation-checked bounded native handles.

Exact-ABI manager builds are now one serialized build-and-copy transaction.
The build gate bypasses local task-cache restoration and verifies both the debug
Application class and requested native ABI before publishing an APK. This was
added after concurrent x86_64/AArch64 builds combined a valid manifest with a
dex missing `ArchpheneDebugApplication`; the invalid artifacts are now rejected
before they can be installed.

The package and app-shell trust boundary is now normative rather than spread
across implementation notes. It defines verified official packages, bounded
recursive AUR graphs built by the separate no-network Builder UID, exact
lifecycle-script authorization, disabled arbitrary libalpm hooks with fixed
maintenance adapters, contained executable and desktop ownership, APK-bound
native runtime content, AndroidKeyStore app-shell signing, authenticated Binder
identity, and explicit key-loss recovery. A source contract keeps these claims
linked to their Rust/Kotlin enforcement. Signed-scriptlet reverse rollback now
passes on x86_64 with verified `zsh` 5.9.1-1 and 5.9.2-1. The gate interrupts
the normal update after commit, exposes Repair and Roll back, restores the older
archive, and proves its `post_upgrade` script restores `/usr/bin/zsh` exactly
once without changing unrelated `/etc/shells` content. It preserves the install
reason, clears transaction residue, restores current `zsh` 5.9.2-1, and retains
the exact prior package job/recovery state, manager section/lifecycle, and
test-owned cache/staging state. APK replacement requires `--install-apk`;
the validated default run used the installed manager. Full-device evidence is under
`tooling/build/signed-scriptlet-rollback/emulator-5554`.

Graphical Linux sessions now retain the same Android foreground-service
protection as the shared shell and long package/file work. The manager records
the native GUI handles in a preallocated 16-slot array, reports the active
Linux-app count in its low-priority notification, and stays retained until the
last process closes. The lifecycle policy makes the surrounding behavior
explicit: session-owned process groups and descendants, no implicit package
daemon autostart, bounded logs and registries, Home/detach retention,
graceful-close/TERM/KILL teardown, Android process-death limits, and durable
recovery only for mutation state rather than dead interactive process memory.
State-preserving exact-ABI device gates confirm identical manager, generated
wrapper, and Linux PIDs across Home for stock Mousepad on the Samsung and Foot
plus Bash on the emulator. Android reports the manager as a `specialUse`
foreground service with the visible “1 Linux app is running” notification;
Back removes the Linux tree and notification. Full-device running, Home, and
notification-shade captures were inspected.

Generated Linux app shells now expose production Android notifications through
their exact V4 capability contract. Unmodified XDG portal and
freedesktop.org clients cross the private manager broker, while the
authenticated thin wrapper owns first-use Android 13+ consent, the fixed
32-entry pending queue, Android notification identity, return-to-task content
intent, and withdrawal. Exact AArch64 Samsung and x86_64 emulator runs pass
portal/classic post, wrapper attribution, first-use consent without a Linux
retry, content routing, withdrawal, fatal logs, and inspected full-device
notification shades. Inbound Android document/share intent filters remain the
next app-shell integration gap.

Release optimization is now an executable artifact gate rather than build-file
intent. The manager, isolated Builder, and generated app shell each carry a
small startup-only ART baseline profile; all three pass R8 and emit compiled
`baseline.prof` plus `baseline.profm` payloads. The gate inspects the packaged
x86_64 and arm64-v8a Rust libraries and requires them to be stripped, while the
workspace release profile requires one code-generation unit, ThinLTO,
panic-abort, and symbol stripping. Broad package, compositor, and Builder paths
remain outside the baseline profiles and continue to be governed by the
existing debug allocation, JNI, latency, memory, lifecycle, and soak gates.

The greenfield manager is not ready for tag publication. Its local unsigned
release APK passes the optimization gate. The build now accepts bounded
`archpheneVersionCode` and semantic `archpheneVersionName` Gradle properties
and rejects invalid values before task execution. Exact-ABI release builds can
therefore carry a prospective tag version without changing source. Both
`arm64-v8a` and `x86_64` release builds produced only their requested native ABI
with version `1.1.0-rc.1` and version code `1000001234`; over-limit codes and an
incomplete semantic version were rejected during configuration. The current tag
workflow and `verify-release-apk.sh` still build and validate
`prototypes/linux-app-manager-stub` as `org.archpheneos.manager`. Publishing a
new tag now would therefore release the legacy manager rather than the tested
greenfield application. Release migration must still resolve the production
package/update identity, pass the tag version into separate ABI builds, and
teach the verifier the new package contract. Existing release-signing
secrets are present, and the draft-first workflow source contract passes; no tag
or remote release was created during this audit.

Clean-data and reuse gates pass on the API 36 x86_64 emulator and Samsung
SM-S908U. They prove cold launch, full-device insets and screenshots, touch
batching, Activity recreation, HOME/resume continuity, Back shutdown, private
root creation and required modes, version validation, and idempotent service
restart. The private root currently establishes conventional `/usr`, `/etc`,
`/var`, `/opt`, `/home`, `/tmp`, and `/mnt/android` layout locations; it does
not yet contain a complete base userspace, but verified package closures now
populate it incrementally through pacman's normal local database.

The greenfield manager now has a dependency-free theme baseline: all surfaces,
text, selectors, system bars, and action states use paired Android light/dark
resources; native actions use sentence case; and the visible header reports
user-facing readiness rather than internal native handles. Debug builds retain
the bounded runtime evidence used by lifecycle gates without placing it in the
visible header. Reversible light/dark semantic and full-device visual gates,
plus clean-data lifecycle gates, pass on the emulator and Samsung.

The manager now exposes explicit Auto/Light/Dark Linux color policy and a
default-on Material You switch alongside the three Auto-first geometry, text,
and control sliders. Color and accent changes are live; geometry, font, visible
control size, and touch targets remain predictable relaunch snapshots. Turning
Material You off selects a stable Archphene accent. Binder protocol v13 sends
the exact resolved opaque background and foreground to generated wrappers, so
their content, status bar, and navigation bar repaint immediately even when an
explicit Linux scheme opposes Android's current mode.

The shared Rust runtime republishes bounded GTK 3/4 and KDE colors through a
primitive JNI call while retaining the current published geometry, font, and
control dimensions. The checksum-pinned GTK helper watches the physical
manager-owned atomic settings file through inotify and retains the private
Settings portal as an event-driven fallback. GTK 4 is activated through the
generic preload path and defers its initial apply until `GtkSettings` exists;
neither ABI performs steady-state polling. Stock Mousepad passes live
light-to-dark-to-light updates on Samsung through the current GTK 3 wrapper,
while current unmodified Snapshot proves the GTK 4 helper, portal state, and
camera lifecycle on the exact x86_64 emulator and AArch64 Samsung without an
app-shell, manager, or Linux process restart. Snapshot's camera canvas remains
intentionally black and is not treated as broad libadwaita visual evidence.
The dual-device production gate checks the manager accessibility tree, exact
preference state/restoration, helper diagnostics, stable processes, camera
pause/resume while Settings is foreground, fatal logs, and measured
full-device status/navigation-bar luminance.

Generated-app-shell framework accessibility search now validates the caller's
query before case normalization. At most 1,024 UTF-16 code units are admitted;
limit-plus-one returns no matches without allocating a lowercase copy, while
null and blank queries retain their prior empty result. Three direct JVM tests
cover exact admission, overflow, null, and blank input. Launcher-template unit
tests and lint pass with zero errors, as do both exact-ABI manager builds that
package the release template. After installing the rebuilt AArch64 manager,
the physical Samsung retained a stable Foot process pair and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/accessibility-search-bound-foot-20260804`.

The live GTK bridge no longer reads mutable shared-home `settings.ini` and
`gtk.css` through GLib allocate-to-EOF path APIs. One 64 KiB-plus-one reader
opens with `O_NOFOLLOW|O_CLOEXEC`, requires a regular file, and binds device,
inode, mode, size, modification time, and change time across the completed
read. Settings parsing and GTK 3/4 CSS loading consume those bounded snapshots;
the toolkit does not reopen the CSS path. The host contract accepts exact
64 KiB and rejects limit-plus-one and symbolic-link fixtures. Warning-denied
x86_64/AArch64 helper builds, regenerated prebuilt hashes, and exact manager APK
builds pass; the AArch64 sysroot pin moved to available GLib 2.88.3 with its
verified SHA-256 after the retired 2.88.2 URL returned 404. The rebuilt Samsung
manager staged the byte-exact helper
`ff138b4b426394cae6eb70c61c60d66bd662f46a6e59f03bc98c886aa26f8c60`.
Runtime/hash evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/gtk-bounded-config-20260804`, and the
clean full-device package-runtime regression is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/gtk-bounded-config-foot-20260804`.
Direct GTK visual runtime evidence is not claimed in this checkpoint: both
installed Mousepad and Seahorse wrappers are rejected by app-shell trust before
registry lookup and require explicit wrapper repair.

The Qt 6 platform-theme module likewise watches `kdeglobals` through an
event-driven exact-file watcher created after Qt's event dispatcher is ready;
it re-arms after atomic replacement, uses a directory fallback only if the file
is unexpectedly absent, and performs no steady-state polling. A native gate
loads the production plugin and requires a same-process light-to-dark palette
update (currently 25 ms). Both rebuilt plugin architectures are in the exact
manager APKs installed on the emulator and Samsung. Current shared-root,
unmodified KCalc opens Android DocumentsUI for single-file Open and Save/create
on both devices; cancellation returns to the same live dialog without changing
a file.

Appearance publication now reopens the mutable shared-root `kdeglobals` file
with `O_NOFOLLOW|O_CLOEXEC`, reads through a fixed 64 KiB-plus-one buffer, and
requires stable device, inode, mode, length, modification time, and change time
across the path, opened descriptor, completed read, and final path. Concurrent
growth can no longer make the prior `fs::read` allocate past the policy ceiling,
and a link, replacement, shrink, growth, or permission change fails closed
before Archphene replaces user state. Exact-limit, limit-plus-one, static
overflow, and symbolic-link tests bring the pinned process suite to 31 passing
tests; warning-denied Clippy and both exact-ABI manager builds pass. The rebuilt
manager on the physical Samsung reopened its 1,394-byte managed KDE config,
started a stable Foot session, rendered exact command output in a 1080×2202
frame, and emitted clean scoped logs. The full-device screenshot and hashed
config evidence are under
`tooling/artifacts/visual-audit/RFCT90AEEFA/kde-config-bounded-read-foot-20260804`.

The dense single-screen manager scaffold has been split into focused Packages,
Files, and Terminal sections with a persistent bottom navigation surface. The
selected section survives Activity recreation and rotation; switching sections
does not stop a running shared shell; storage pickers return to Files; and short
landscape windows omit nonessential terminal explanation so every control stays
on screen. Exact-ABI full-device portrait/landscape navigation, onboarding,
clean lifecycle, and persisted-folder gates pass on the emulator and Samsung.

The Files section now presents one bounded storage inventory across both
Archphene Android UIDs. It separates package downloads, the installed shared
Linux system, manager and isolated-Builder AUR build data, Archphene Home, and
device free space. Rust counts allocated blocks through descriptor-rooted
no-follow traversal, caps depth and entries, tolerates only entries that
disappear during a live scan, and counts hard-linked file storage once.
Downloads opens the existing package-selective cache manager; the installed
system routes to Packages; Home routes to Android's document browser; and AUR
cleanup is separately confirmed. That cleanup removes only reviewed
manager-owned AUR caches/outputs and the Builder workspace, then reloads actual
post-cleanup state. The exact-ABI automated gate clears a 13 MB cross-UID
fixture to 0 B on the emulator and Samsung while package downloads, installed
package state, and Home remain exact. A real Samsung cleanup reclaimed 4 GiB
while its 122 MB downloads, 2.3 GB installed system, and 86 kB Home remained
unchanged. Full-device light and dark captures are visually clean.

Debug builds now install StrictMode main-thread I/O and closeable-leak
diagnostics after OEM application initialization. Navigation, Terminal text,
and Linux appearance settings read and commit through one serialized preference
worker while UI and app-shell paths consume immutable in-memory snapshots.
Native library loading, runtime creation, and stale AUR artifact cleanup run on
the existing bootstrap worker. A state-preserving gate cold-starts the manager,
changes and restores App scale, verifies the restored value after process
death, and resolves a real pacman package on each architecture. It reports zero
Archphene-owned violations or fatal events on the x86_64 emulator and physical
AArch64 Samsung and records visually inspected full-device screenshots.

Bootstrap stale-AUR-output cleanup now bounds every matching directory entry,
not only successfully deleted regular files. It processes at most 64 matches
and visits only match 65 to report truncation; admitted non-regular entries stay
untouched and their warning paths remain bounded. A direct JVM regression uses
65 matching directories and proves exactly 64 unsafe results, one truncation,
zero removals, and no cleanup error. The complete JDK 26 app unit/lint gate and
exact x86_64/AArch64 manager builds pass. On physical Samsung, rebuilt manager
startup produced the same 64-plus-one boundary against 65 test-owned cache
directories, retained all of them, and removed the fixture afterward. Runtime
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/stale-aur-output-bound-20260804`;
the subsequent stable 1080×2202 Foot full-device gate is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/stale-aur-output-bound-foot-20260804`.

Samsung `IdsController` lifecycle events are retained with their count as
explicitly reported framework-only evidence rather than hidden or attributed
to Archphene. Preferred-shell and storage-onboarding writes now use the same
preference worker. Service teardown invalidates public handles immediately and
moves connection cancellation, tracked-worker draining, synchronization
cleanup, lifecycle transitions, and native destruction onto a bounded
`ArchpheneShutdown` worker. The repeatable gate leaves the foreground in 48 ms
on the emulator and 99 ms on Samsung and observes no Archphene StrictMode or
fatal event. The manager-side Wayland compositor now records its creating
thread and rejects every state/JNI call from another thread; blocking clipboard
descriptor I/O is conversely rejected on that compositor owner. Current
generated Foot app shells pass authenticated Surface/input/frame and off-thread
Android-to-Linux clipboard gates with visually inspected full-device captures
on both exact ABIs. The runtime now asserts its package, AUR, storage,
synchronization, shell, launcher-publisher, and bootstrap worker boundaries.
Project Sync consumes a validated in-memory mapping rather than reading
preferences from its caller. Durable package-job writes/fsync, retained AUR
artifact checks/deletion, and network cancellation run off-main while the
accepted request and progress remain immediate. A non-clearing gate proves
Queued progress, worker-thread journaling, cancellation, clean diagnostics, and
visually inspected full-device presentation on both exact ABIs. The legacy Foot
workflow is now migrated to the manager-owned shared root and session model.
Only a debug manager receiver can inject bounded Unicode IME test input;
generated app shells contain no test intents. Real app-shell Surface/Binder
input, clipboard-worker transfer, pointer selection, scrollback, live resize,
close, force-stop cleanup, and cold relaunch pass with inspected full-device
evidence on both exact ABIs.

Generated-app-shell clipboard Binder protocol v16 now carries an optional
bounded `text/html` representation beside a required plain-text fallback. The
native Wayland compositor advertises HTML only when present and keeps the
requested format attached to every queued descriptor; Linux HTML is converted
to a bounded Android fallback before publishing `ClipData`. A single delayed
focus-return retry covers the interval where Android reports window focus
before granting clipboard reads, without adding polling. Current unmodified
Code accepts a 29-byte fallback from a 43-byte rich clip through the dedicated
clipboard worker on both exact ABIs. Full-device editor captures show the exact
text, and native MIME-routing, Android unit, lint, and exact-ABI build gates
pass. Binary formats and a package-installed Linux HTML producer remain open.

The current wide Code workspace also exposed a Samsung touch displacement
during IME resize. Android had already resized the app-shell Surface while the
client's old buffer and root hit-test layout remained 432x881 until it
acknowledged the pending 432x537 `xdg_toplevel` configure. The compositor now
records each exact pending toplevel size and, only while it matches the current
output, applies the visible Surface transform to hit testing as well. A
held-client physical-device gate sustains and inspects that formerly transient
state; exact stable prompt and terminal-action touches, IME transitions,
full-device captures, native regressions, and exact-ABI builds pass on the
Samsung and emulator.

Active performance is now measured at the actual shared-runtime boundaries.
Debug-only dormant counters distinguish Terminal and compositor JNI calls,
direct-buffer traffic, JNI array copies, and explicit Kotlin payload copies;
the device probe also samples ART allocation/object/GC deltas and measures raw
hardware-event-to-Terminal-draw or Wayland-present latency. Presentation
diagnostics use one 128-byte direct-buffer snapshot instead of 32 scalar JNI
queries, while dispatch flags avoid unchanged clipboard and IME polling. The
budgeted one-second Terminal and Foot gate passes on the exact x86_64 emulator
and AArch64 Samsung with zero GC, bounded memory/process resources, rendered
frame differences, and inspected full-device captures. A repeated-window gate
now adds two active and two idle minutes for each surface with independent ART,
JNI/copy, latency, resource, battery, and thermal samples. Both exact ABIs pass
all 16 windows with zero GC and zero peak descriptor/thread growth. Samsung
Terminal/Foot allocation maxima are 196,608/1,268,272 bytes with 30/154 ms
active-window latency p95; emulator maxima are 98,304/1,334,912 bytes with
18/84 ms p95. Maximum thermal status remains zero at 37.7 C/30.2 C.

That sustained gate exposed an emulator-only graphics-fence leak: the legacy
CPU `ANativeWindow_lock`/post path retained one `sync_file` descriptor per
presented key frame, growing Foot from 160 to 250 descriptors in four windows
while Samsung's mapper stayed flat. Generated-app-shell presentation now uses
three preallocated `AHardwareBuffer`s submitted through `ASurfaceTransaction`.
Acquire fences transfer to Android, previous-buffer release fences are polled
without blocking the compositor and explicitly closed, and resize generations
remain bounded. Exact reruns hold Foot flat at 157 Samsung and 133 emulator
descriptors while preserving visually inspected output.

The CPU-upload buffer allocator now checks the complete usage combination with
`AHardwareBuffer_isSupported` before requiring `GPU_COLOR_OUTPUT`. If that
preferred allocation fails, it retries the established CPU-write and
GPU-sampled baseline instead of rejecting Surface attachment on a device with a
narrower usage matrix. Both exact-ABI compositor builds, the pinned compositor
tests, and warning-denied Clippy pass. A standalone physical Samsung `SM-S908U`
probe reports `supported=1 allocate=0` for both combinations. The rebuilt
arm64-v8a manager then passes the Foot visual gate at 2316×978 with stable
processes, visible command output, bounded geometry, clean scoped logs, and
inspected full-device evidence under
`tooling/artifacts/visual-audit/RFCT90AEEFA/hardware-buffer-usage-fallback-20260804`.

The retained-frame-to-Android RGBA conversion now has a host-testable bounded
slice boundary shared with the locked `AHardwareBuffer` path. Its warmed test
copies one clipped two-row damage region into a padded preallocated destination
1,000 times with zero measured allocations, verifies exact channel conversion,
and proves that undamaged pixels and stride padding remain byte-identical. The
pinned compositor suite now has 107 passing tests; warning-denied Clippy and
both exact-ABI release builds pass. After installing the rebuilt arm64-v8a
manager, the physical Samsung Foot gate retained stable processes and a
1080×2202 output, rendered the exact command result without clipping, and
produced clean scoped logs. The inspected full-device evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/hardware-buffer-zero-allocation-20260804`.

At 840 dp and wider, the same stateful controls are composed once into a
persistent navigation rail, a two-column package workspace, side-by-side file
actions, and a terminal surface that consumes the remaining display. Package
review actions and package mutation actions occupy two semantic rows with
single-line labels and full touch targets, preventing the physical Samsung's
former mid-word Remove wrapping without shrinking accessible text. A reversible
gate switches both maintained devices to tablet and external-display-sized
Android configurations, verifies accessibility geometry, rotates the Activity,
and captures full-device views of every section. It restores the complete
Archphene sandbox, prior section/running state, display overrides, and rotation;
it does not download packages. A separate state-preserving emulator gate moves
the same unmodified Seahorse Android task from the 1080x2205 phone display to a
real temporary 1920x1080 Android display and back while App scale remains 125%.
The task and manager/wrapper/Linux process IDs remain stable as the compositor
converges from 346x706 to 1024x506 logical pixels and back with no pending
resize or fatal event. The gate requires inspected full-device captures and
restores the scale preference, secondary display, prior manager section, and
foreground Activity. Physical Samsung DeX/external-display hardware remains
planned.

The latest bounded Rust package-journal record is now exposed as stable Binder
fields rather than reparsed UI text. Packages renders it as a recent-activity
card with package name, operation, state, exact progress, message, a static
two-view progress track, and the existing state-driven Cancel action. It does
not allocate or rewrite unchanged text during status polls. Debug-only native
journal fixtures prove a completed operation across manager restart and a
durable failed operation on both exact ABIs with full-device screenshots and no
network use. A separate state-preserving real-reboot gate interrupts the
Resolving phase at 5% on the emulator and Samsung, then proves the journal
recovers it as Failed with its exact progress, bounded interruption reason, and
enabled Review action instead of disappearing, remaining active, or retaining
Cancel. It neither downloads a fixture archive nor changes the existing package
database/cache. The gate also handles Samsung's Android-owned USB prompt and
post-boot service-readiness delay before judging Archphene.

Packages now opens on a real installed-package list derived directly from
pacman's local database. Rust validates and sorts at most 4,096 bounded regular
database entries once, caches that immutable snapshot in the runtime, and pages
60 compact records at a time through a coarse direct-buffer JNI call. Kotlin
publishes one revisioned snapshot after bootstrap and package mutation; the
Activity checks only its revision during status polls and uses recycled native
Android list rows for name, exact version, verified capability class, and
explicit/dependency reason. Rust derives Graphical, CLI, Library, and System
bits only from each installed package's bounded pacman `files` record; missing
metadata remains visibly Not analyzed instead of being guessed from names or
descriptions. The final dual-device inventory gate also caught a parser
regression in ordinary pacman `files` records: directory entries legitimately
end in `/`. Rust now removes exactly that trailing delimiter before validating
the bounded relative path, ignores the directory itself for capability
classification, and still rejects empty segments, traversal, absolute paths,
and embedded NULs. The current exact builds enumerate 139 x86_64 and 138
AArch64 packages without hiding the Installed view behind a refresh error.
Installed and Search results are distinct retained modes, selecting an
installed row routes to exact package details, and a changed installed count
returns to the installed view. A debug-only 67-package local-database fixture
proves the second native page, all capability classes, virtualized scrolling,
result-mode switching, light/dark appearance, manager restart, scoped logs, and
visually inspected full-device screenshots on the emulator and Samsung without
package downloads.

Official search results now use a separate revisioned Binder snapshot and
virtualized Android list rather than joining the bounded Rust response into one
large TextView. Repository, name, exact version, and description remain
separate fields; selecting a row fills the exact package and opens details,
while returning to Search results restores the submitted query. Matching
durable package activity replaces the row's repository label with its operation
and state, and the adapter can append an unmatched active job immediately from
the existing journal fields without reparsing display strings. A debug-only
dependency-free tar/gzip writer generates minimal real core/extra pacman
databases inside app storage. Pacman itself searches those catalogs and
libalpm's `-Quq` comparison is the authority for whether a differing version is
an update. Uninstalled candidate archives remain explicitly Not analyzed;
installed classifications apply only to the installed version. A locally newer
version renders Not an update, disables the primary action, and explains that
Archphene will not downgrade it automatically. Both exact ABIs pass four-result
`dotnet` rows covering available, exact-installed, update, and locally-newer
states, row selection, retained query and results across theme recreation,
durable Failed overlay, scoped fatal logs, and visually inspected full-device
light/dark screenshots without network access.

Reviewed AUR packages now join that same bounded search snapshot instead of
existing only in a separate evidence panel. The manager retains matching
official rows, replaces any prior AUR review with exactly one community row,
and bounds the combined result at 100 entries. Source, exact candidate version,
exact installed version, installed file class, and candidate compatibility
provenance remain separate: an unverified built candidate says Not analyzed
even when the installed package has a known Graphical/CLI/Library/System class.
For differing versions, Rust asks pacman itself to evaluate the exact
`package>candidate` dependency, so Update and Not an update use Arch/libalpm
ordering rather than a Kotlin or Rust approximation. Tapping the AUR row
returns to the retained trust/recipe/build evidence and never falls through to
official resolution. Exact-ABI no-network fixtures and real installed Foot
states pass in full-device emulator light and Samsung dark captures; durable
activity remains visible without hiding the AUR source.

Candidate compatibility is a separate state from file class. Search and Details
say Not analyzed while any signed archive is absent. Once the complete freshly
resolved closure is cached, Rust reverifies every archive and signature, then
streams both Zstandard and XZ package tars with fixed header storage and bounded
paths, links, entry count, expanded bytes, and per-entry size. Before pacman can
mutate the shared root, the review rejects runtime ELF for another CPU ABI,
native ELF hidden in an `any` package, malformed runtime ELF, executables that
the bridge cannot launch, and ELF load segments incompatible with the Android
device's actual page size. Valid relocatable ELF static-library metadata remains
ABI checked without being mistaken for a loadable executable; this distinction
was required by glibc's real `libmcheck.a`. Blockers name the exact closure
package. Passing this review is shown as Bridge eligible, not as a validated
application claim; data/library/service-only targets remain Managed only. A
successful review publishes only a single-use, process-memory capability bound
to the target name and exact resolution SHA-256. Install or Update must consume
that exact capability before pacman mutation, and the transaction still
reverifies every archive, so a catalog race, process restart, or writable
on-disk forgery cannot substitute an unreviewed closure.
The normal `btop` manager gate now passes review, install, deliberate cache
tamper/recovery, remove, verified-cache reinstall, Terminal execution, and
process restart over real signed 139-package x86_64 and 138-package AArch64
closures. Full-device light/dark captures were inspected. The first uncached
Samsung run took 4m22s end to end, making reuse of unchanged review results a
measured product requirement.
Unchanged reviews are now reusable without trusting timestamps or
descriptions. A bounded content address covers the exact resolution, target
ABI, actual page size, immutable packaged keyring/owner-trust identity, and
every archive and signature byte. Rust atomically publishes a canonical
checksummed mode-0600 result, discards corrupt derived records, invalidates any
changed payload/signature/trust input, and caps the cache at 1,024 entries. A
cache hit recreates only the same single-use in-memory
capability; pacman mutation still reverifies the closure. Cache-hit
install/tamper/remove/reinstall gates pass on both exact ABIs.

The uncached review is now cooperatively cancellable without JNI callbacks or
per-entry managed allocations. One shared Rust token is checked at every
64 KiB content-hash chunk, decompressor read, archive-entry boundary, package
verification boundary, and result-publication boundary. Details renders the
active compatibility phase in the existing activity card and exposes Cancel;
install/update uses the same token, and Service shutdown cancels it before
joining workers. A prepare/cancel handshake removes the race between the
managed cancellation request and entry into the blocking native review. Cold
139-package x86_64 and 138-package AArch64 gates cancel before mutation,
preserve the package database and archive/signature bytes, leave no partials or
commit intent, and pass scoped fatal logs plus inspected full-device light/dark
screenshots. The normal Samsung signed-package lifecycle still passes
verify, deliberate cache tamper/redownload, removal, cache reinstall, Terminal
execution, and process restart afterward. The post-install
app-shell/toolkit/broker capability result remains pending.
The local Binder path makes the Queued boundary deterministic in code: after
the journal commit, the Service posts worker start to the next main-Looper turn
and the Activity synchronously consumes the new job revision before its Install
or Remove click returns. A bounded one-shot debug-only worker gate now holds
that real worker off the UI thread. Both exact ABIs prove the matching
`dotnet-sdk` row and recent-activity card in durable Queued state, Cancel before
cache/network/mutation, cold-restored Cancelled/Review, scoped fatal logs, and
visually inspected full-device screenshots. Release builds cannot arm the
gate. Returning to a broad search query also disables Install/Remove
immediately; those actions no longer remain misleadingly enabled for a
previously resolved exact package. Full populated package details still require
their own focused deterministic gate. Exact package-name results are now ranked
before substring matches while retaining pacman's repository ordering within
each group; a regression covers `strace` ahead of
`gst-plugin-rstracers`.

The durable journal now also represents the future app-shell pipeline's
Building, Publishing, and Awaiting Android confirmation states without
renumbering any existing v1 value. Rust treats them as active, enforces a
forward transition graph, recovers them after interruption, and retains the
warmed zero-allocation update path. A debug-only Service-bound presentation
fixture advances one real native journal job through Queued, Resolving,
Downloading, Verifying, Building, Publishing, Installing, Awaiting Android
confirmation, and Complete. Both exact ABIs render every exact percentage and
message, expose Cancel only before the mutation boundary, retain Complete
after a cold restart, leave the cache and pacman database untouched, emit no
fatal logs, and pass visually inspected full-device screenshots. Existing
device gates separately cover durable Failed and Cancelled. This validates the
manager state model and presentation, not the still-pending real app-shell
builder and PackageInstaller handoff.

The first app-shell implementation slice now discovers graphical applications
from the shared Arch root rather than assuming one runtime per Android wrapper.
Rust enumerates at most 4,096 directory entries, examines at most 1,024
`.desktop` candidates and 4 MiB of source, publishes at most 256 entries,
validates required fields and visibility, tokenizes `Exec` into bounded
structured arguments without a shell, and accepts only executable targets whose
canonical path remains inside the shared root.
Malformed, unavailable, oversized, and symlinked entries are isolated instead
of aborting the catalog. One cached immutable catalog is paged through a
versioned 16 KiB direct-buffer JNI response; Kotlin validates pagination and
ordering, publishes one revisioned snapshot, and shows launchable/ignored
counts beside the installed-package list. Debug-only shared-root fixtures prove
two valid entries, hidden-entry filtering, unavailable-executable and symlink
rejection, cold manager restart, clean fixture removal, scoped logs, and
visually inspected light/dark full-device presentation on both exact ABIs. The
persistent Rust registry now adds bounded pacman ownership, deterministic
collision-checked Android package identities, full structured launch/icon
inputs, desired/published/pending generations, and explicit build, install,
update, removal, failure, and retry states. It uses a checksum-protected,
mode-0600 atomic file, rejects incomplete catalogs and unsafe/corrupt paths,
preserves late PackageInstaller confirmations across desktop changes, and
provides a verified-PackageManager reconciliation boundary for manager death or
external wrapper removal. Host lifecycle/safety tests pass. Exact-ABI cold
restarts preserve byte-identical registries on the emulator and Samsung;
fixture removal reconciles cleanly and the manager reports the exact remaining
catalog without fatal logs.

App-shell-only state transitions no longer repeat desktop parsing, pacman
ownership scans, and executable integration profiling. The Rust runtime keeps
the already bounded immutable desktop catalog after an authoritative scan;
Kotlin explicitly requests a rescan only after package-tree mutation and pages
the retained catalog after PackageInstaller reconciliation, review, retry,
dismissal, and result transitions. On the physical Samsung, a state-preserving
bootstrap logged one scan across its 151 installed packages followed by one
cached replay of the same four launchable entries and identical
examined/rejected/truncated evidence. The full-device installed-app view
remained correct with three current Android app shells.

Package icons now cross the complete generic app-shell pipeline. Rust resolves
absolute icon paths and freedesktop hicolor/pixmaps names within the shared
Arch root, expands relative and fake-root absolute symlinks with a fixed
limit, rejects escapes, loops, writable/non-regular files, and publishes a
normalized root path. The launcher registry fingerprints bounded icon bytes
with corrected v2 descriptor framing, migrates v1 registries without changing
their installed Android package identities, and republishes a wrapper when
the icon content changes. Kotlin reopens the normalized path without following
the final link, bounds PNG size/dimensions/pixels, checks the expected SHA-256,
and substitutes exactly one manifest-referenced resource before signing.
Android then parses and decodes the generated icon during final APK
verification. Missing or unsupported icons use the Archphene mark.

Host tests cover hicolor preference, absolute package links into `/usr/lib`,
escape/loop/writable rejection, icon-content updates, and v1 migration. A
debug-only signed wrapper proved exact custom PNG replacement on Samsung.
The physical manager then migrated three real Foot app shells through normal
Android confirmation; the system installer and full-device app drawer showed
the package Foot icon, a cold restart settled at three current app shells, and
Foot still produced a real Wayland frame. The run also fixed stale-template
reconciliation for a wrapper already awaiting removal and removed the two
debug desktop fixtures without touching Linux user data. Capability declaration
derivation and per-app-shell compatibility diagnostics are now implemented; the
remaining app-shell gaps are listed in `Pending`.

The manager now builds minimized app-shell wrappers from one staged template,
patches bounded manifest identity fields, signs each APK with a persistent
non-exportable RSA-3072 Android Keystore key, re-verifies its signer and exact
entry digests, and streams the verified result through normal user-confirmed
PackageInstaller sessions. A wrapper binds only to the exported app-shell
session Service. Every transaction revalidates the kernel-supplied caller UID,
its single exact installed package, signer, descriptor, generation, manager
identity, and SHA-256 of the template from which it was built before consulting
the current Rust registry. A bounded raw Binder v1 session accepts a death
token and real cross-process Surface, releases replaced or detached surfaces,
and retries while the shared runtime is still starting.

The physical AArch64 Samsung passes untrusted-caller and malformed-version
rejection, cold manager start, exact 1080x2316 Surface attachment,
full-device light/dark presentation, explicit close, and abrupt wrapper
Binder-death cleanup. It also exposed and fixed a Samsung-only decor-insets
ordering crash. Installing a manager with a changed launcher template detects
manager-signed stale wrappers, advances their Android versions, and republishes
normal user-confirmed updates.

The next production slice is now connected behind that authenticated Surface.
The dual-ABI Rust compositor owns a private per-session socket and presents
committed SHM frames directly through `ANativeWindow`. A bounded Rust registry
launches only the reauthorized desktop descriptor's installed executable,
without a shell, in a manager-owned process group with private Wayland and
standard Qt/GTK/SDL environment. Fixed-size touch, five-button pointer,
bounded horizontal/vertical axis, and hardware-key batches cross one Binder
transaction and one direct-buffer JNI call. Key records include repeat and
Android modifier state. Both Kotlin and Rust enforce the record semantics;
native focus loss releases held keys and buttons and cancels active touches.
On Samsung, full-device and scoped-log gates prove the complete input
transport, native socket creation/removal, retained session across HOME/resume
and rotation, and deterministic Back cleanup without a malformed-input
rejection or crash. The native suite contains 48 compositor tests, including
button/modifier mappings and inactive-host cleanup. Process status is polled
outside the frame hot path; merged stdout/stderr drains into a fixed 16 KiB
tail ring, and exited leaders trigger remaining group cleanup. Start, stop,
and failure state travels back through an authenticated one-way Binder
callback to a wrapper-owned Android overlay, leaving native code as the
exclusive owner of compositor pixels. A temporary no-download
Samsung gate pointed the existing Kate fixture at the already staged glibc
loader: the real manager process path captured `loader cannot load itself`,
reported exit 127 on the full device, removed the socket, and restored
the fixture to its original SHA-256.

Fresh full-device Samsung captures prove that this status overlay reflows
normally from 1080x2316 portrait to 2316x1080 landscape without retaining or
stretching the old compositor buffer. Scoped logs prove the same authenticated
session across HOME/resume and the dimension-changing Surface reattach; Back
then closes the session, drains its compositor thread, and leaves no private
socket. A cold-manager variant backgrounds the wrapper during readiness,
resumes after bootstrap, and still authenticates and attaches; this covers the
retry-cancellation lifecycle race found during the final code audit. Forcing
manager process death while the wrapper is visible also produces a fresh
manager PID, reauthenticates a new session, reattaches the same wrapper Surface,
and returns to the bounded status without user intervention or a fatal log.

The production session now also bridges text clipboard without granting the
background manager direct Android clipboard access. Only the focused wrapper
reads/writes `ClipboardManager`; its additive Binder transaction is
UID/signer/descriptor/generation authenticated and bounded to 16,384 UTF-16
units and 64 KiB UTF-8. Rust caps pending Wayland copy/paste descriptors at
four, and a dedicated manager worker switches each descriptor to nonblocking
mode and enforces a two-second poll deadline. Strict UTF-8 decoding,
revision-based stale/echo suppression, deferred focus publication, selection
clear, detach, and close are explicit. The compositor suite now has 48 tests,
including exact pipe success, overflow, and timeout cases. The physical
Samsung passes the existing real Wayland bidirectional demand-driven clipboard
probe and a production generated-wrapper Unicode Android-to-Binder gate at 28
UTF-8 bytes. The final generation-135 wrapper repeats that gate with 27 bytes
after the focus-deferral audit. Full-device captures show the system clipboard
confirmation; Back drains the compositor and clipboard workers and leaves no
socket or fatal log.

A production Foot package now closes the first real-client gap on the physical
AArch64 Samsung. The package was installed through Archphene's signed pacman
transaction and its generated app shell was updated through normal Android
confirmation. The generic glibc bridge maps private-root pathname Unix sockets,
full-path and PATH-based exec/spawn calls, and bounded passwd identity without
changing Foot or Bash. The shared root publishes `archphene` at
`/home/archphene`; an app-managed fontconfig root includes installed Arch fonts
and Android's system fonts, so Foot remains runnable after `ttf-dejavu` is
removed through the normal manager UI.

The Android font path is now an explicit read-only bridge instead of a
configuration-only claim. Absolute `/system/fonts` reads and fontconfig's
contained directory-FD-relative enumeration pass through to Android's
root-owned system partition; other `/system` paths remain outside the managed
Linux namespace, and Android continues to deny app-UID writes. Both exact ABIs
enumerate a nonempty font cache and resolve `Droid Sans Mono`. The emulator,
which has no installed Arch font package, now passes the full Foot
Home/resume/crash/relaunch gate with inspected full-device output; Samsung
passes the same lifecycle gate.

Private ELF search paths are now scoped to the process that needs them. Rust
initial launch and the exact-ABI C exec/spawn bridge traverse only the reachable
`DT_NEEDED` closure, bounded to 256 objects and 256 dependencies per object.
They export at most 64 canonical root-contained, non-world-writable absolute
RUNPATH/RPATH directories; `$ORIGIN` entries participate in traversal without
being added globally. This replaces the global `/usr/lib/pulseaudio` exception
and prevents unrelated application-private libraries from mixing. A
deterministic transitive host fixture passes, and interactive Bash launches the
installed Foot binary through the nested bridge on both the x86_64 emulator and
AArch64 Samsung with inspected full-device captures.

The app shell now treats Android pixels and Wayland logical coordinates as
separate spaces. It derives logical size, integer output scale, and fractional
scale from Android density, maps touch/pointer coordinates back to that logical
space, and retains the client's high-resolution raster while composing
client-side-decoration subsurfaces at the physical output size. The steady
output canvas is reused. Mandatory Android edge-to-edge behavior is handled by
safe system-bar/display-cutout/IME insets rather than drawing Linux controls
under the status, gesture, or keyboard areas. Full-device Samsung captures
prove crisp Foot pixels at a 1080x2202 portrait Surface, a live 1080x1343
keyboard Surface, and a live 2241x978 landscape Surface.

The same authenticated session bridges `zwp_text_input_v3` to the wrapper's
Android `InputConnection`: bounded surrounding text, preedit/commit, deletion,
editor actions, content hint/purpose, and show/hide lifecycle remain native
Wayland state rather than synthetic key text. A real Samsung keyboard tap
commits `hi` into Foot, with the manager logging the first bounded IME command.
The production diagnostics report a 1080x2202 physical composite sourced from
Foot's 1079x2134 raster and 411x813 logical surface tree. The entire Rust
workspace, 48 compositor tests, glibc path/exec/socket/identity probes, runtime
source contract, exact AArch64 build/install, scoped fatal-log check, and
full-device portrait/IME/landscape inspection pass.

The generated app shell now also matches Android's composition-finish
semantics. Wayland preedit is deliberately absent from client surrounding
text, so clearing preedit when an IME called `finishComposingText()` discarded
the user's accepted candidate. Each InputConnection now retains only its
latest successfully delivered bounded preedit, commits that exact value on
finish, and clears it after an explicit commit or cancellation. On the
physical Samsung, the stock HeliBoard path delivered a real preedit operation
to unmodified VS Code; the underlined `m` remained after the keyboard was
dismissed. On the API 36 x86_64 emulator, stock Google Keyboard selected its
direct-commit path and entered `heu` into unmodified Code - OSS. Both tests
used manager-generated wrappers, scoped logs, exact-ABI builds, and
full-device screenshots. Real user-driven non-Latin composition remains open;
Japanese/CJK/emoji coverage is still deterministic boundary coverage plus
unit coverage, not a claim about an installed non-Latin Android IME.

The shared command boundary now applies one package-independent policy to
dependency programs. Bare command lookup resolves the installed shared-root
`/usr/bin`; exact absolute execution may resolve a regular executable anywhere
inside the shared Arch root. The bridge canonicalizes the target, rejects root
escape and parent traversal, requires executable package mode, and rejects
group/world-writable files before invoking the verified loader. Host probes
cover access, exec, spawn, a real nested ELF, escaping symlinks, and writable
targets. Both exact-ABI APKs build with the refreshed bridge. On the physical
AArch64 Samsung, a root-contained Bash wrapper under `/usr/bin` launched an
unmodified Bash ELF copied to `/usr/lib/archphene-bridge-test`, returned its
real AArch64 version through the manager UI, emitted no fatal log, and left no
fixture behind.

The follow-up closes the remaining generic path shapes without adding a
Code-specific rule. Root-aware canonicalization expands at most 40 relative or
absolute Arch symlinks, interprets absolute targets inside the private root,
and rejects cycles or traversal above it. A bounded 256-byte shebang parser
allows one installed ELF interpreter and the kernel's optional single
interpreter argument; recursive or non-ELF interpreters fail closed. Host
probes cover direct and spawned nested scripts, shebang arguments, bare PATH
scripts, valid relative/absolute links, loops, and escapes. Exact-ABI manager
builds now execute the complete `/usr/bin` wrapper → `/usr/lib` script →
absolute root-internal symlink → ELF chain on both the x86_64 emulator and
physical AArch64 Samsung. Both return the real architecture-specific Bash
version, remove every fixture, emit no fatal log, and pass visually inspected
full-device captures.

The launcher template no longer embeds AGP's repository-wide
`META-INF/version-control-info.textproto`. That metadata made the authenticated
template digest change after unrelated manager commits or dirty-tree changes,
which correctly triggered stale-wrapper reconciliation but needlessly asked
the user to update every app shell. Template release builds now disable VCS
metadata at the supported per-build-type DSL boundary. A dedicated gate rejects
the entry and forces two complete launcher rebuilds; both produce the same
`3db705420d7e923e8f91a770a086a8806248e136de47c6bfd90517e50608fcd3`
SHA-256.

Production Code - OSS on the x86_64 emulator and VS Code on the physical
AArch64 Samsung now exercise the standard cursor-shape-v1 protocol through
authenticated manager/wrapper Binder v6. The compositor validates the focused
pointer-enter serial, maps all 36 protocol shapes to Android system pointer
icons, and versions repeated same-size changes without per-motion allocation.
Host protocol tests prove text and crosshair transitions; exact-ABI builds,
native compositor probes, scoped device logs, and inspected full-device
captures pass. Authenticated Binder v7 also transfers changed legacy
client-supplied ARGB cursor surfaces with a 256×256/65,536-pixel bound and
strict bitmap/hotspot validation. Stock Qt 5 publishes repeated 24×24 arrow
cursors with exact 3,1 hotspots through current generated app shells on both
ABIs; Samsung additionally proves a 24×24 pointing-hand cursor with hotspot
11,12. The device gate additionally found and fixed an Android
ownership error: explicitly recycling the prior cursor bitmap could crash
`ViewRootImpl` while it compared
deferred pointer-icon updates, so accepted cursors now remain framework-owned
until Android can reclaim them.

Pointer confinement now accepts bounded compound `wl_region` add/subtract
operations rather than disabling every non-rectangular request. Each region is
capped at 64 ordered operations; relative motion partitions the segment in a
fixed 516-edge stack buffer and stops at the first outside interval without
heap allocation, including a subtracted hole followed by a second allowed
island. A real host Wayland client proves that discontinuous case, and the
exact x86_64/AArch64 native probes publish visible full-device success.

Package resolution now treats Qt's Wayland platform modules as dependency-owned
Archphene runtime companions. A closure containing, or an existing shared root
owning, `qt5-base` or `qt6-base` adds the matching signed `qt5-wayland` or
`qt6-wayland` package unless it is already present. A clean emulator review of
stock `qt5ct` visibly included `qt5-wayland` in its 23-package closure and
installed both through the normal manager flow; the migrated Samsung root was
repaired through the same UI. This remains generic toolkit adaptation rather
than a modified application.

The sealed AArch64 path bridge now exports `shm_open` and `shm_unlink` under
both `GLIBC_2.17` and `GLIBC_2.34`. This fixed current stock ARM `wev`, whose
new symbol import had bypassed the older interposer and attempted to use
Android's absent conventional `/dev/shm`; host bridge gates and a real shared
Samsung Code-terminal launch pass.

Real user-driven non-Latin composing input beyond the deterministic manager boundary, non-text
clipboard formats, app-shell accessibility, and real-client pointer
lock/confinement variants still need explicit production-client coverage.
SHM snapshots and client-buffer normalization also remain copied; the new
reused physical output canvas is not a claim of zero-copy, Vulkan, or sustained
high-frame-rate readiness.

The recent-activity action is now terminal-state aware. Complete has no dead
disabled Cancel button; active pre-commit work exposes Cancel; and durable
Failed or Cancelled work exposes Review. Review fills the exact package,
switches to details, and requests a fresh bounded resolution against current
signed catalogs and current installed state before Install, Update, Verify, or
Remove can become available again. It deliberately does not replay stale
closure metadata. The debug Failed fixture routes through this path with
catalogs absent and proves the fail-closed resolution diagnostic without any
network request; an explicit post-review Retry action and richer recovery
classes remain planned.

The same device gate exposed a narrow-screen header collision between the
package name, phase label, and recovery action. The activity card now stacks
the package name and phase in a flexible text column beside the action, with
single-line end ellipsis only after the available column is exhausted. Fresh
full-device emulator and Samsung captures verify the complete package name,
phase, message, and touch target without increasing the fixed card height.

Retry is now gated by the durable job revision rather than merely by a matching
package name. Install/Update failures and cancellations expose the primary
action as Retry only after the exact terminal revision has resolved
successfully against the current signed catalogs and installed database; failed
removals use the same rule on Remove. A later failed attempt advances the
revision, disables the stale action, and exposes Review again. Catalog refresh
also locks Review, Retry, and Remove consistently with the executor. The
generated debug pacman catalog now contains sufficient real metadata for
`pacman --print` resolution, and the exact APK passes the successful no-download
Retry gate plus the fail-closed missing-catalog Review gate on both the emulator
and Samsung.

Package failures now use one bounded diagnostic policy for install and removal.
It distinguishes network, Linux-storage, repository/trust, changed-state,
catalog, generic pre-mutation, post-mutation, and failed state-refresh cases.
Once pacman mutation has begun, a failure immediately refreshes the installed
package and shell snapshots before publishing Failed; if that refresh cannot be
completed, the card directs the user to restart before Review. A failed durable
journal update also publishes the terminal result in memory so the current UI
cannot remain misleadingly stuck on Installing while restart recovery repairs
the journal.

The activity card is 140dp high with a 64dp stacked name/phase header, 6dp
progress track, and up to three bounded diagnostic lines. Its three text views
discard stale horizontal scroll offsets during state polling. Eight
production-classifier fixtures pass from the exact APK on the emulator and
Samsung, with settled full-device screenshots confirming that short package
names and the longest partial-mutation guidance remain fully on-screen.
Forward Repair is implemented and tested for interrupted removal and a
partially committed install filesystem. Exact rollback is also available when
the journal could bind the complete prior signed closure; unavailable or
invalid prior artifacts leave only forward Repair. The removal-side
database-corruption matrix remains pending.

Linux-storage preflight failures now retain typed required/available byte
counts and render those exact capacities with Clear cache before Review. The
operation runs off the Activity thread, resolves the failed package's current
verified closure again, inventories the manager-owned
`var/cache/pacman/pkg`, and sends only package names outside that closure
through the bounded selected-cleanup Rust/JNI boundary. If exact closure
resolution is no longer possible, cleanup retains every cached package rather
than guessing which dependencies are disposable. Rust validates the directory
and every bounded entry, rejects symlinks, directories, non-Unicode or unknown
names before deleting anything, removes only recognized package archives,
detached signatures, and partial downloads, then syncs the directory.
Installed state, Linux home files, catalogs, project data, and the failed
transaction's verified downloads remain outside the deletion set.

The card reports reclaimed unrelated bytes (or that none can safely be freed),
then advances only that durable job to Review. Kotlin persists the bounded
result against the Rust journal's durable job ID plus exact terminal identity,
so a cold Service restart restores it while a later identical failure cannot
inherit stale recovery. The state-preserving exact-ABI gates create a 3.5 KiB
synthetic unrelated set and exercise the full UI on both targets. The empty
x86_64 emulator cache and the Samsung's populated cache pass; all 548
pre-existing Samsung archive/signature files remain byte-identical, synthetic
files are removed, and inspected full-device screenshots show the exact
capacity evidence and persistent Review state.

A separate bounded emulator-only gate constrains the real app filesystem to
48 MiB free using recognized unrelated package-cache artifacts. The normal
signed two-package `base`/`strace` closure reaches its actual 66 MiB preflight
requirement, fails before mutation, selectively reclaims about 5.4 GiB without
touching the verified closure, resolves Review into Retry, and installs
`strace` to completion with no journal, repair snapshot, or pacman lock. The
gate is non-installing by default, refuses physical devices and active shared
shells, isolates pre-existing package and compatibility caches, removes its
fresh target through the normal manager flow, and byte-verifies restoration of
the complete database, caches, durable job/recovery state, navigation, and
prior manager lifecycle. Full-device failure, recovery, and completion frames
pass visual inspection; Samsung passes the corresponding typed recovery and
state-preservation matrix without filling its personal storage volume.
The shared formatter now reports one decimal below ten units instead of
overstating reclaimed capacity through whole-unit ceiling.

The Packages workspace also exposes Downloads independently of a failure.
Rust scans at most 4,096 cache artifacts once, groups archives, detached
signatures, and partial downloads by package/version/architecture, and retains
one immutable snapshot for 32-row JNI pages. The Android dialog reports total
disk use, consolidates several cached versions into one selectable package,
supports cleanup of up to 256 selected package names, and keeps Clear all behind
a second confirmation that states installed packages remain installed.
Inventory and cleanup share the package-operation concurrency boundary and keep
the foreground Service alive until the refreshed snapshot is available.

A debug-only marker-owned fixture adds two versions of one package plus an
unselected sibling without replacing existing cache files. Exact-APK emulator
and Samsung gates select and remove only the target, verify the sibling remains,
exercise but cancel Clear all, remove the remaining fixture, and compare the
complete prior cache listing afterward. Both real populated caches and the
259/248-package databases remain unchanged; full-device captures verify the
phone inventory and confirmation layouts.

The native selected-cache cleanup boundary now enforces its 256-package limit
during request decoding rather than collecting every newline-delimited item and
checking the count afterward. Its parser preallocates exactly 256 borrowed
entries, admits the exact limit, and rejects item 257, empty input, and trailing
empty entries before cache cleanup or runtime-registry access. The crate's five
direct Rust tests pass; the new boundary test includes exact-limit and
limit-plus-one cases. App unit tests and lint plus both exact-ABI builds pass.
The subsequent Foot regression on the physical Samsung passed with a 34 px
font, 126 px controls, stable Android and Linux processes, and visible command
output. Full-device evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-cache-selection-bound-foot-20260804`.

All user-started manager work now shares one Service-retention predicate:
bootstrap, catalog refresh, search/resolution, package mutation/cache cleanup,
bounded commands, storage import/mirroring, and the interactive shell cannot be
torn down by Activity finish or task removal while active. Package, catalog,
command, and storage work promote the Service to a low-priority foreground
notification with an app-open route and package progress; a simultaneous shell
retains its Stop action. Once the UI is gone and the final operation completes,
the Service removes the notification and stops rather than leaking an idle
runtime. A debug-only one-shot completion gate lets the exact APK exercise the
real operation without slowing release code: both Pixel Launcher and Samsung
One UI Recents gestures prove foreground promotion, active task-removal
retention, cache completion, shutdown ordering, empty cache, exact cold-restart
result restoration, scoped fatal logs, and visually inspected full-device
screenshots. Verified partial-transaction repair and rollback subsequently
passed on both exact ABIs; remaining package work is listed in `Pending`.

Visible files in the shared `/home/archphene` are now available to Android
Files, system pickers, and explicitly granted Android consumers through an
exported `DocumentsProvider` protected by Android's `MANAGE_DOCUMENTS`
contract. Dotfiles, symbolic links, unsupported file types, package state, and
the rest of the private Arch root are not enumerated. Kotlin owns the Android
provider contract while a dedicated Rust storage crate walks bounded document
IDs through directory descriptors with `O_NOFOLLOW`, performs non-replacing
rename, and owns create/open/delete mutation. It rejects hidden names, path
traversal, control and bidirectional-spoof characters, symlink traversal, root
mutation, and blocking special-file races.

Exact-ABI device gates pass on the API 36 x86_64 emulator and physical
AArch64 Samsung. Each exercises framework create, exact read/write, child
relationships, rename collision preservation, delete and cleanup; directly
attacks dotfiles, `..`, and a live symlink; then browses the retained visible
fixture through Android DocumentsUI and captures a full-device screenshot.

DocumentsProvider child enumeration now counts every physical directory entry
before filtering hidden names and symbolic links. A query admits exactly 4,096
physical entries and rejects entry 4,097 while preserving the independent
visible-child ceiling and deterministic sorting. Three direct JVM tests cover
the exact hidden-entry boundary including a symbolic link, physical
limit-plus-one, and visible limit-plus-one; the complete app unit/lint gate and
both exact-ABI manager builds pass. The rebuilt physical Samsung then passed
the normal provider and shell-startup DocumentsUI gate with inspected
1080×2202 full-device captures. The captures and hashes are under
`tooling/artifacts/visual-audit/RFCT90AEEFA/documents-provider-physical-bound-visual-20260804`;
the physical overflow boundary is established by the JVM tests rather than a
direct shell provider query because Android requires a granted DocumentsUI
capability for that Binder route.

Exported DocumentsProvider IDs now enforce their 1,024-byte UTF-8 and
32-segment policies before allocating a complete encoded copy or splitting all
slash-delimited components. The code-point walker rejects malformed surrogates
and returns immediately on byte overflow. The path parser retains at most 32
validated visible segments and rejects segment 33 before creating it. Direct
JVM tests cover Home, exact depth, depth overflow, an exact 1,024-character
510-segment flood, byte overflow, and malformed high/low surrogates. The
complete app unit/lint gate and both exact-ABI builds pass. The subsequent
physical-Samsung Foot regression retained stable Android and Linux processes,
a 34 px font, 126 px controls, and visible command output in inspected
full-device frames. Evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/document-id-segments-bound-foot-20260804`.

The manager now owns exactly one persisted Android tree capability selected
through DocumentsUI. Its Service validates the real persisted read/write flags
off the main thread after restart, reports read-only and revoked states,
records a replacement before releasing the old grant, and exposes explicit
Connect, Change, and Remove actions. Exact-ABI emulator and Samsung gates prove
connect, replacement, process-restart persistence, external revocation,
reconnect, read-only recovery, removal, scoped logs, cleanup, and visually
inspected full-device screenshots.

The connected tree can also be materialized as one initial
`~/Projects/<folder>` snapshot. Kotlin traverses SAF while Rust streams the
provider descriptors into bounded descriptor-relative staging, preserves
nested directories and development dotfiles, syncs the contents, and
atomically publishes a non-replacing project. Exact recursive content, empty
files, `.git/config`, stale-stage recovery, chunk-level cancellation with
complete cleanup and retry, process-restart persistence, grant removal with
retained Linux content, scoped logs, cleanup, and full-device visuals pass on
both exact-ABI targets. It subsequently evolved into a conflict-safe,
crash-recoverable synchronized mirror with pull, push, deletion, conflict
copies, cancellation, and history. It remains an explicit mirror rather than
a live SAF mount.

Project synchronization now bounds mutable SAF provider metadata before it is
retained or copied into additional objects. Root and child document IDs admit
at most 4 KiB of UTF-8, MIME types and display names at most 255 bytes, and
control, bidirectional-spoof, or malformed-surrogate fields fail before URI
construction, directory-queue insertion, or result-map retention. The bounded
UTF-8 predicate exits as soon as a field crosses its limit instead of scanning
the remainder. Android's Cursor necessarily materializes the provider's one
field value first, but Archphene no longer retains or duplicates an unbounded
ID/MIME value across its 10,000-entry tree. Five direct protocol tests cover
exact ASCII and multibyte boundaries, limit-plus-one, spoofing, controls, and
malformed Unicode; app unit tests and lint plus both exact-ABI builds pass.
After installing the rebuilt AArch64 manager, physical Samsung retained a
stable Foot process pair and exact visible output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-provider-field-bound-foot-20260804`.

The initial Android project-mirror traversal now applies that same 4 KiB safe
document-ID predicate to the tree root before its first query and immediately
after each child Cursor value is materialized. The root was formerly unchecked,
and children required only nonempty text before URI construction and directory
queue retention. Oversized, control/bidirectional, and malformed-surrogate IDs
now fail at the same boundary as later synchronization. Existing protocol tests
cover exact ASCII/multibyte limits, limit-plus-one, spoofing, and malformed
Unicode; app unit/lint and both exact-ABI builds pass. The subsequent Samsung
Foot full-device regression passed with stable processes and visible output.
Evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/initial-mirror-root-id-bound-foot-20260804`.

Project-sync recovery document access now applies the same provider-field
policy to queried display names, its parent ID before constructing a child
query URI, and a matching child ID and MIME type before constructing or
retaining the result. Document IDs admit at most 4 KiB and names/MIME types 255
bytes; unsafe path names, controls, bidi spoofing, and malformed Unicode fail
closed. Existing protocol tests cover each exact and overflow
boundary. App unit/lint and both exact-ABI builds pass. The subsequent Samsung
Foot full-device regression passed with stable processes and visible output;
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-recovery-all-fields-foot-20260804`.

Activity document handoffs and persisted-tree selection now reuse one
allocation-free UTF-8 admission helper. Manager-owned document and external
tree URIs admit at most 4 KiB, exported document IDs 1 KiB, and derived display
names 255 bytes without first constructing complete encoded copies. The walker
exits on the first byte above policy and rejects malformed surrogate pairs
before MIME derivation, chooser handoff, or tree-capability retention. Direct
JVM tests cover exact ASCII and multibyte limits, overflow, empty text, and
malformed high/low surrogates. The complete app unit/lint gate and both
exact-ABI builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/activity-document-fields-bound-foot-20260804`.

Conflict-path aggregation is now bounded across every deferred-delete and
rescan pass, rather than only when history is eventually persisted. One sync
retains at most 64 distinct paths in first-seen order. A 65th unique path marks
the user-visible count as `64+`, stops scanning the remainder of that pass's
conflicts, and prevents later passes from growing or rescanning the aggregate;
duplicates do not consume capacity. Persisted history continues to receive the
same 64-path sample. Four direct JVM tests cover exact admission,
limit-plus-one, duplicate capacity, and duplicate-versus-new input against a
full aggregate. App unit tests and lint plus both exact-ABI builds pass. The
rebuilt physical-Samsung manager then retained stable Foot processes and exact
visible output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-conflict-aggregate-bound-foot-20260804`.

The private Linux home now includes Documents, Downloads, Media, Pictures, and
Shared. Root bootstrap publishes only exact managed aliases for those
directories under `/mnt/android`; a replaced link, substituted directory, or
wrong target fails closed instead of being silently adopted. Archphene Home is
the Android-visible side through the existing `DocumentsProvider`, while
selected external Android folders remain explicit `~/Projects` snapshots
rather than pretend live mounts. Exact root tests and provider CRUD/security
gates pass on both ABIs. On the physical Samsung, an unmodified Foot/Bash
session also reads an Android-provider-owned Shared fixture through
`/mnt/android/shared`, with the complete device frame retained.

On first clean launch, the manager now explains that the conventional Linux
environment stays in private app storage and that choosing an Android folder
creates an initial `~/Projects` snapshot rather than a live mount or broad
all-files grant. The user can choose a folder or select Not now; either choice,
including picker cancellation, is remembered so the prompt does not nag after
restart, while the normal Connect/Change action remains available. Semantic
UI, picker-cancellation, no-repeat restart, scoped-log, and visually inspected
full-device gates pass on the exact-ABI emulator and Samsung.

Up to 32 Android documents can now enter the shared environment from the
multi-select system picker, Open With, Share, or `ACTION_SEND_MULTIPLE`.
Android passes one read-only content descriptor at a time; the Service
deduplicates the bounded batch in selection order and reuses fixed direct
request/output buffers. Rust duplicates and streams each descriptor into a
private staging file without transferring file bytes through JNI or Kotlin,
enforces a 16 GiB per-file limit, syncs it, then publishes it into
`~/Downloads` with a non-replacing descriptor-relative rename. Existing names
become bounded ` (2)` variants, interrupted staging is recovered on the next
attempt, and invalid/spoofing display names receive a safe fallback. A failed
provider item does not discard later selections; durable status reports
partial success honestly. The Activity consumes each incoming batch once.

Incoming Activity collection now applies the 32-document policy to the complete
aggregate before copying clip or stream entries. A data URI, all `ClipData`
items, and `ACTION_SEND_MULTIPLE` streams share one overflow-safe count check;
an aggregate limit-plus-one is rejected before `addAll`, and every accepted
append leaves the Activity-owned list bounded. The framework still owns initial
Intent unparceling, but Archphene no longer makes a second unbounded copy.
Direct JVM coverage proves exact admission, each aggregate overflow shape, and
invalid counts; app unit tests and lint plus both exact-ABI builds pass. The
rebuilt manager on physical Samsung imported a normal ordered two-item stream
batch with no third item or fatal event. Runtime evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/document-import-aggregate-bound-20260804`;
the subsequent clean full-device Foot regression is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/document-import-aggregate-bound-foot-20260804`.

Per-document URI validation now enforces its 4 KiB UTF-8 ceiling without first
allocating a complete `toByteArray()` copy of caller-controlled text. The
code-point walker returns immediately on limit-plus-one and rejects unpaired
UTF-16 surrogates before Java `URI` parsing, while exact-limit multibyte content
remains valid. Six direct policy tests now include an exact 4,096-byte URI,
one-byte overflow, empty input, and malformed high/low surrogates. App unit
tests and lint plus both exact-ABI builds pass. The rebuilt manager on physical
Samsung passed the subsequent clean Foot full-device regression under
`tooling/artifacts/visual-audit/RFCT90AEEFA/document-import-uri-text-bound-foot-20260804`.
No fresh import-runtime claim is made for this checkpoint: the device's
pre-existing noncancelable pending-launcher dialog prevented the new incoming
Intent from reaching the Activity, and that unrelated queue was left intact.
The prior aggregate-bound checkpoint retains normal two-item physical import
evidence.

The shared native storage-request decoder now enforces each JNI operation's
one-to-three-field schema while parsing. It previously cloned every
tab-delimited field from the admitted 8 KiB request and checked the count only
afterward. The parser now preallocates only the expected bounded field vector,
rejects an extra or empty field before cloning it, and rejects expected counts
outside the production schema. The crate's six Rust tests pass; the new
boundary test covers valid three-field input, extra and empty fields, invalid
counts, and 4,096 one-byte fields in an exact 8,191-byte request. Formatting,
warning-denied Clippy, app unit tests and lint, and both exact-ABI builds pass.
The subsequent physical-Samsung Foot regression retained stable Android and
Linux processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/storage-request-field-bound-foot-20260804`.

Exact ACTION_VIEW, ACTION_SEND, and deduplicated ACTION_SEND_MULTIPLE byte
content, collision, restart-status, system-picker cancellation, cleanup,
scoped-log, and visually inspected full-device gates pass on the x86_64
emulator and physical AArch64 Samsung. The same current APKs also pass live
byte progress, chunk cancellation, cooperative and ignored provider-open
timeouts, stalled and paced descriptor reads, process recovery, and exact
retry. Directory trees enter through the manager and Linux portal
`ACTION_OPEN_DOCUMENT_TREE` paths, while wrapper drag-and-drop uses the
capability-scoped conflict-safe document broker. Fresh dual-ABI drag/drop gates
and the complete mirror/synchronization/recovery matrix pass on both targets.

The Files page can now open one visible Linux file or share one through 32
visible Linux files through Android's standard flows. Both actions open
DocumentsUI at Archphene Home and accept only bounded regular document URIs
from Archphene's own provider. Open sends the resolved MIME type, URI,
ClipData, and read-only grant through `ACTION_VIEW`; Share uses `ACTION_SEND`
for one URI and `ACTION_SEND_MULTIPLE` for several, deduplicates the selection,
and negotiates an exact, same-family, or general common MIME type. Archphene
itself is excluded to avoid copying its own files back into Downloads.
State-preserving scripts verify each chooser's exact URI set, MIME clip, and
read-without-write grants on the emulator and Samsung, with cleanup, fatal-log
checks, and visually inspected full-device selection/chooser frames.
Independent physical and emulator single-file Share runs also select Messages
and show Android granting that separate UID temporary read access without
sending anything. The manager records only evidence it actually has: that the
Android chooser was opened, or that launching it failed. It does not claim an
external viewer or recipient completed the action. Open success, a forced
chooser-launch failure, single Share, and two-file Share status all survive
manager process restart on both exact ABIs, with inspected full-device frames.

The Files page also exports one regular Archphene Home document to an
Android-selected `ACTION_CREATE_DOCUMENT` destination. Android owns and opens
both scoped descriptors; Rust duplicates them and transfers at most 16 GiB
through one fixed 32 KiB buffer without routing file bytes through Kotlin or
JNI. Each chunk publishes bounded progress and observes cancellation. Before
writing, the manager retains the destination's persistable write grant and a
durable running record; cancellation removes the incomplete output, and
startup removes an uncommitted output after process death. Exact-device gates
cover picker cancellation, normal exact-byte completion/restart, visible
byte/percent progress, chunk-boundary cancellation, and manager death after a
real nonempty partial write. Both destinations are removed, the Linux source
is unchanged, and full-device phone/wide frames plus fatal-log checks pass on
the emulator and Samsung. The Linux desktop-portal Save As path is separately
proven through stock GTK 3, Electron/Chromium, and Qt 6 callers.

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

Runtime startup no longer deletes and reimports the unchanged packaged GPG
trust anchor on every manager process. Rust keys the bounded derived keybox to
the immutable packaged keyring and ownertrust identities, rejects
symlink/non-file/oversized cache entries, and rebuilds on any identity change.
The retained loading state remains visible while startup runs. Forced rebuild
versus steady reuse measures 1,708–1,843 ms versus 232–237 ms on the emulator
and 315–323 ms versus 222–230 ms on Samsung; first reuse after a real reboot
measures 442 ms and 1,222 ms respectively. Both targets remain under the
1,000 ms steady and 1,500 ms post-boot budgets, execute installed `btop`, and
reverify its signed closure through the reused keybox.

Immediately before mutation, Rust re-resolves and re-verifies the full bounded
closure. It asks pacman to print the complete archive set, then requires every
planned name/version to match the already verified resolution exactly before
allowing mutation. A cache-only gate verifies installed `btop` through that
archive preflight without rewriting or downloading package payloads on the
x86_64 emulator and AArch64 Samsung.

A focused host contract found the replacement boundary: local
`-U --print` does not report an installed package that a conflict-accepting
transaction would remove. Archphene's ordinary `--noconfirm` transaction fails
that conflict closed, so it cannot silently replace a package. The contract proves that an isolated
`--dbonly --ask 4` transaction against a copied local database exposes the
exact replacement while leaving live state unchanged.

Rust now performs that authoritative simulation against a bounded mode-0700
private copy of pacman's local database after re-verifying every archive. It
accepts only unchanged installed records plus additions/upgrades matching the
exact archive set, derives every removed name/version, limits the returned plan
to 48 removals, and deletes the preview database on every success, failure, or
runtime restart.
Kotlin strictly parses the versioned plan and parks the cancellable worker in a
durable Awaiting confirmation state. A blocking Android review names every
installed package/version that would be removed, explains the shared Arch
environment, and offers Cancel or explicit Replace. Approval marks a
single-use native capability bound to the exact package resolution and removal
set. Rust then re-resolves, re-verifies, and re-simulates the transaction
immediately before mutation; any change fails closed. It atomically snapshots
each removed pacman ownership record, binds its canonical SHA-256 into the
durable mutation intent, and only then enables pacman's exact conflict-
acceptance bit. Repair verifies and restores missing or damaged retained
records before replaying the same plan forward. Successful completion proves
the requested version and every consented removal, then clears the intent and
bounded snapshots.

Kotlin package install, AUR install, and removal-plan decoding now parses the
bounded 16 KiB UTF-8 response incrementally. The previous path copied the text
with `dropLast`, allocated every newline-delimited record, and then allocated
every tab-delimited field before validating the declared removal count. The
decoder now reads only the version header, exact two-field summary, and the
declared zero-to-48 exact three-field removal records. It rejects a first extra
line or field immediately and retains only the bounded result. Direct JVM tests
cover exact 48-removal admission and exact 16 KiB newline and tab floods. The
complete app unit/lint gate and both exact-ABI builds pass. The subsequent
physical-Samsung Foot regression retained stable Android and Linux processes,
a 34 px font, 126 px controls, and visible command output in inspected
full-device frames. Evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-removal-plan-bound-foot-20260804`.

A controlled exact-ABI device gate establishes `wcurl 0.0-1` as an installed
conflict baseline, then installs current signed official `curl 8.21.0-1`
through the ordinary manager path. The emulator and physical Samsung both show
the exact `wcurl` removal with Cancel/Replace, commit only after Replace,
immediately reconcile curl as Installed, and leave no preview database,
mutation intent, recovery snapshot, lock, partial payload, or fatal log.
Full-device light/dark review and completion captures were visually inspected.

Pacman commits the complete prepared archive set through one normal
dependency-checking transaction; the former per-package `--nodeps` and
blanket-overwrite mutations are gone. New packages enter as dependencies. A
fresh user-requested install marks its target explicit, while an update
preserves the target's existing explicit/dependency reason. A bounded mode-0600
intent preserves every explicit package selected by that operation and restores
those reasons idempotently after manager death. Startup rejects malformed, oversized,
symlinked, or broadly writable intents, removes a stale database lock, validates
the local database, and only then deletes the recovered intent. Cache-only
verify/remove/reinstall and forced restart-recovery gates pass on both devices
without rewriting package payloads.

Official package mutation recovery is now explicit and forward-only. Rust
atomically publishes a bounded mode-0600 intent immediately before pacman
mutation. Install/update intents retain the exact signed resolution and
explicit targets; removal intents retain the exact installed package/version
plus the SHA-256 of a private atomic copy of pacman's bounded local ownership
record. Startup validates but does not silently execute the intent, leaves
its verified package inputs unavailable to cache cleanup, and distinguishes an
interrupted mutation from work that was safe to retry. The manager exposes one
Repair action, removes a stale lock only inside that explicit path, re-verifies
the retained transaction, validates pacman's local database, proves the final
state, and only then clears the intent. A deterministic same-UID `SIGKILL`
gate deletes `strace`'s live database description while its installed files
remain. On the x86_64 emulator and AArch64 Samsung, Repair verifies the
retained record digest, atomically reconstructs pacman's ownership metadata,
completes normal removal, clears every private temporary/snapshot/intent path,
and restores `strace` as explicit through the normal signed package flow.
Unknown snapshot entries and digest changes fail closed; orphaned bounded
snapshots are cleaned only when no mutation exists. A second exact-ABI gate
leaves pacman's database at the current Foot version while removing its
installed executable.
Repair deliberately omits pacman's normal `--needed` optimization, forcibly
reinstalls the complete retained signed closure, restores the executable,
preserves the exact version and explicit install reason, and clears the intent,
reason journal, database lock, and partial cache state on both devices.
Launcher reconciliation and publication remain paused while the mutation is
pending, so a transiently missing desktop file cannot launch Android's wrapper
uninstall confirmation before Repair.

A third exact-ABI gate removes both Foot's executable and its pacman local
`desc` record. Repair quarantines only the exact damaged `name-version`
directory after bounded no-link validation, rebuilds that record from the
already verified signed archive with pacman's database-only mode, then performs
the normal forced reinstall. The original install-reason intent is merged
rather than overwritten. The restored `desc`, `files`, and `mtree`, exact
version/reason, database validation, quarantine cleanup, transaction cleanup,
launcher inventory, and full-device presentation pass on the emulator and
Samsung. The same gate also removes both `base` and Foot local descriptions in
one retained transaction; batched database reconstruction and the complete
postconditions pass on both devices. Exact official-package rollback now also
passes on both devices; whole-operation AUR recovery remains open.

The generic compatibility layer maps Linux root ownership to the Android app
UID, copies when SELinux rejects hard links, avoids Android app seccomp's
blocked `fchmodat2`, and maps generic root-relative mutation calls without
package-specific changes. The current path validates pacman's local database
and proves the requested package and version. Real AArch64 and x86_64
older-to-newer upgrades, including changed dependency sets and reviewed
package replacement, now pass on both exact ABIs.
Reviewed AUR lifecycle scripts are enabled under the exact capability described
above. Verified official and exactly reviewed AUR lifecycle scripts now retain
libalpm's native dependency-ordered per-package pre/mutation/post lifecycle.
Pacman alone receives the conventional fake-root `PATH=/usr/bin:/bin`, so
unmodified scripts resolve installed commands through the existing loader and
path bridge; other packaged tools retain their private absolute command path.
Generic libalpm hooks remain isolated through an exact bounded private
`HookDir` and are replaced after official mutation by installed,
root-contained trust and desktop-cache adapters. Accepted replacements also
pass deterministic manager death after their exact snapshot and durable
mutation intent are published but before pacman begins. Restart exposes Repair
at the retained commit boundary; repair restores the old record, revalidates
the authorized plan, completes signed official `curl`, and clears all
transaction residue on both exact ABIs. Exact rollback, reviewed
dependency-orphan cleanup, and retry-to-completion under real storage pressure
also pass; whole-operation AUR recovery remains open.

The hook boundary no longer lets pacman discover and execute arbitrary
root-owned hook commands in the Android fake root. Rust scans at most 1,024
entries across the system and local hook directories, validates bounded
regular `.hook` files or existing `/dev/null` overrides, and publishes an
app-private mode-0700 directory containing an exact `/dev/null` override for
each name. Both pacman configurations place that absolute `HookDir` in
`[options]`, and every mutation refreshes it before pacman starts. After an
official install, removal, repair, or rollback, Archphene conditionally runs
only installed root-contained adapters for trust, GIO modules, GLib schemas,
fontconfig, GDK-Pixbuf, GTK input modules, desktop MIME associations, shared
MIME data, and dconf. Missing subsystem directories are valid for a partial
Arch root. Adapter failure retains the mutation journal and exact repair
inputs; this was exercised on Samsung when `update-mime-database` exposed a
path-translation defect, then repaired through the visible retained Repair
flow after correction. The reusable gate proves no uncontrolled libalpm hook
ran, all applicable caches have transaction-fresh timestamps, and 24 x86_64
plus 29 AArch64 hooks are isolated. Normal reviewed dependency cleanup,
residue checks, and inspected full-device completion pass on both targets.

Official package lifecycle scripts no longer inherit the private Android-host
command path. The signed `fontconfig` reinstall/repair gate verifies native
`post_upgrade`, including `vercmp` and the font-cache rebuild, without a
missing-command or arithmetic failure while preserving exact package version
and install reason. Native rollback selection comes from libalpm using the
restored signed archive and the installed forward package record. Signed
`zsh` older/newer exact-ABI rollback now covers observable reverse
upgrade/removal behavior.

Package operations are now user-cancellable while queued, resolving,
downloading, or verifying. The Activity enables a visible Cancel action
immediately after queueing; the Service records the request, interrupts its
worker, disconnects any active HTTPS transfer, and relies on Rust's owned
download object to remove unpublished partial files. A synchronized commit
boundary disables cancellation before pacman mutation, so the UI never claims
that an in-flight transaction was cancelled. The bounded journal records a
terminal Cancelled result that survives manager restart. Cache-only gates on
the emulator and Samsung cancel `btop` verification at 5%, prove its executable,
database/cache payloads, and transaction intent remain unchanged, inspect
full-device screenshots, and then rerun successful verify/remove/reinstall on
both targets. Cancellation during a deliberately slow live network transfer
and cancellation after actual process death remain broader failure-matrix work.

Exact installed-version queries now drive state-specific Install, Update,
Verify, and Remove actions. Removal derives both package-only and recursive
pacman plans. A blocking phone-sized review identifies every unused dependency
by exact name/version, explains the shared Arch environment, and offers Cancel,
Keep dependencies, or Remove all. Rust retains the single-use choice,
independently rejects explicit packages from the cleanup tail, re-simulates
before mutation, and snapshots every authorized local ownership record.
`angle-grinder`/`jemalloc` gates prove package-only preservation on the
emulator, cleanup on Samsung, and complete multi-package repair after
deterministic manager death on the emulator.

The update command now has a distinct Rust/JNI path instead of reusing fresh
installation semantics. On July 27, a signed cached AArch64
`libsysprof-capture` 50.0-2.1 dependency was established as the older baseline
and updated through the normal Samsung manager UI to 50.0-3. Pacman's local
record retained `%REASON%=1`, no lock or partial payload remained, and the
search-result row changed from the older-version warning to Installed in the
same completion revision. The reusable gate derives the APK's exact pacman and
GPGV payloads, prepares and validates the APK-bound trust keybox, requires an
exact detached-signature `VALIDSIG`, uses pacman only to establish the signed
older test baseline, and captures the real manager review/completion as
full-device screenshots. The x86_64 emulator now passes the same gate from the
official Arch Linux Archive `libsysprof-capture 50.0-2` input to the current
50.0-3 repository package. It retains dependency reason 1, immediately
reconciles the row, and leaves neither a database lock nor a partial payload.

The gate now optionally proves dependency metadata as well as target versions.
On x86_64, signed archived `btop` 1.4.3-3 declares `gcc-libs` and `glibc`; the
normal manager flow updates it to 1.4.7-1, whose verified `.PKGINFO` declares
`glibc`, `hicolor-icon-theme`, `libgcc`, and `libstdc++`. On physical AArch64,
signed `angle-grinder` 0.19.6-2 declares `glibc` and `libgcc`; the manager
updates it to 0.19.6-3 with a newly resolved `jemalloc` dependency. The gate
extracts both metadata records through the exact APK-packaged `bsdtar`, requires
every named new dependency in pacman's local database, and preserves the
target's dependency reason. Samsung's review visibly contains the exact
three-package `base`, `jemalloc`, and `angle-grinder` closure. Full-device
review/completion captures, exact versions, database state, cleanup, and fatal
logs pass on both ABIs.

Clean nine-package `btop` transaction cycles pass on the x86_64 emulator and
AArch64 Samsung. Both gates deliberately corrupt the target archive, prove
rejection, redownload and reverify it, remove the package conservatively, prove
its executable and database entry are gone, reinstall from the verified cache,
and prove the durable Complete result survives manager process death. Full-
device screenshots also verify the responsive closure view and state-driven
actions. Physical AArch64 and emulated x86_64 older-to-newer,
changed-dependency, and reviewed replacement transactions now pass.
Accepted replacement interruption and forward Repair also pass from a durable
pre-pacman boundary on both devices. The gate is non-installing by default and
restores the borrowed signed package, complete local database, package and
compatibility caches, durable job/recovery state, navigation, and prior manager
lifecycle after each run. A separate post-pacman gate proves exact
signed rollback on both ABIs, including removal of AArch64's newly introduced
`jemalloc` dependency and restoration of the prior dependency reason.
The signed script-bearing reverse-scriptlet gate and whole-operation AUR
recovery remain open, so this is not yet a complete production transaction
engine.

Package-compatibility cache pruning now counts every directory entry rather
than deleting an unlimited prefix of temporary files before applying the
1,024-record limit. It admits the complete record set plus one interrupted-
publication temporary file, validates all 1,025 entries before cleanup, and
rejects entry 1,026 without deleting any fixture. The direct overflow regression
brings the pinned package crate to 129 passing tests; warning-denied all-target
Clippy and exact x86_64/AArch64 manager builds pass. The rebuilt manager on the
physical Samsung then started a stable Foot session with a 34 px font, 126 px
controls, exact visible command output, clean scoped logs, and an inspected
1080×2202 full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-compatibility-cache-bound-foot-20260804`.

The shared package-runtime exact-file reader now allocates and initializes one
array at the metadata-admitted size, fills it with `read_exact`, and probes one
trailing byte. The former `Vec::with_capacity` plus `read_to_end` path could
reallocate while consuming malformed growth before rejecting it. This boundary
is used by no-follow bounded package state reads and local pacman-description
validation; their descriptor/path metadata stability checks remain unchanged.
Existing direct tests cover exact input, truncation, growth, and an unbounded
source. All 130 package tests, the locked Rust workspace test suite, the
complete Android source unit/lint gate, and both exact-ABI manager/Builder builds
pass. Package-only warning-denied Clippy is currently blocked by unrelated
pre-existing Rust 1.97 style lints in package parsing code. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font, 126
px controls, and visible command output in inspected full-device frames. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-exact-read-allocation-foot-20260804`.

Desktop-entry loading now reuses that shared exact-file reader after its
existing no-follow open and stable metadata checks. It allocates one initialized
buffer at the admitted file size, fills it exactly, and probes for concurrent
growth instead of using `Vec::with_capacity` plus a bounded `read_to_end` that
could reallocate before rejecting changed input. All 17 desktop-entry tests, the
locked Rust workspace suite, the complete Android source unit/lint gate, and
both exact-ABI manager/Builder builds pass. The physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/desktop-entry-exact-read-foot-20260804`.

Package-compatibility cache records and persisted AUR build/graph capabilities
now also reuse the shared exact-file reader after their existing no-follow,
type, mode, and stable metadata checks. Each path allocates one initialized
buffer at the admitted metadata size, fills it exactly, and probes one trailing
byte instead of allowing bounded `read_to_end` growth before reporting a size
or manifest mismatch. Direct cache-corruption, capability-tampering, and exact
read size-change tests pass with all 130 package tests, the locked Rust workspace
suite, the complete Android source unit/lint gate, and both exact-ABI
manager/Builder builds. The physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and exact visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-cache-capability-exact-read-foot-20260804`.

AUR snapshot provenance headers and ordinary tar entries now allocate one
initialized array at the header-declared size and fill it with `read_exact`
through a shared snapshot helper. The former `Vec::with_capacity` plus bounded
`read_to_end` paths could grow their backing allocation before size validation;
the helper now also probes one trailing byte and rejects truncation or growth.
Direct exact/truncated/grown reader coverage and all 18 AUR tests pass with all
131 package tests, the locked Rust workspace suite, the complete Android source
unit/lint gate, and both exact-ABI manager/Builder builds. The physical-Samsung
Foot regression retained stable processes, a 34 px font, 126 px controls, and
exact visible command output in inspected full-device frames. Hashed evidence
is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-snapshot-exact-read-foot-20260804`.

Persisted AUR lifecycle-capability manifests now reuse the shared exact-file
reader after their no-follow, mode, type, and stable metadata checks. Package
archive analysis also initializes one array at each admitted desktop entry's
declared size, copies the already-read fixed header into it, fills the exact
remainder with `read_exact`, and probes one trailing byte. These paths no longer
depend on `Vec::with_capacity` plus `read_to_end` before reporting a manifest or
archive size mismatch. Lifecycle reconciliation, terminal desktop-entry, and
runtime-blocker regressions pass with all 131 package tests, the locked Rust
workspace suite, the complete Android source unit/lint gate, and both exact-ABI
manager/Builder builds. The physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and exact visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-lifecycle-desktop-exact-read-foot-20260804`.

The command-line AUR review example now reads its bounded RPC and snapshot
inputs into one initialized array at the metadata-declared size, fills it with
`read_exact`, probes one trailing byte, and rechecks path metadata. The former
`Vec::with_capacity` plus limit-based `read_to_end` path could reallocate while
consuming changed input before rejecting it. A direct example test covers empty,
exact, truncated, grown, and over-limit inputs. The example test, all 131 package
tests, the locked Rust workspace suite, the complete Android source unit/lint
gate, and both exact-ABI manager/Builder builds pass. Warning-denied example
Clippy remains blocked by unrelated existing Rust 1.97 style lints in the
package library. The physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and exact visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-review-example-exact-read-foot-20260804`.

One-shot local pacman-description reads used for installed-origin lookup and
desktop-entry package ownership now reuse the shared exact-file reader after
their existing no-follow and stable metadata checks. Each path allocates one
initialized metadata-sized byte vector, fills it with `read_exact`, probes one
trailing byte, and parses UTF-8 directly from that backing storage instead of
growing a small `String` through `read_to_string`. Existing invalid-UTF-8 error
behavior remains unchanged. Origin, malformed-description, and desktop-owner
regressions pass with all 131 package tests, the locked Rust workspace suite,
the complete Android source unit/lint gate, and both exact-ABI manager/Builder
builds. The physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and exact visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-description-exact-read-foot-20260804`.

Repeated local pacman-description scans now resize and reuse one byte buffer to
the current metadata-admitted length, fill that exact slice with `read_exact`,
and probe one trailing byte. Installed-package catalog construction and install-
reason preservation no longer stream each record into a reusable `String` whose
capacity could grow from bytes beyond the observed file size. The helper clears
partial state on truncation or growth and retains capacity only from admitted
metadata. Direct reusable-buffer exact/truncated/grown tests, installed-package
page coverage, and update-reason regressions pass with all 131 package tests,
the locked Rust workspace suite, the complete Android source unit/lint gate, and
both exact-ABI manager/Builder builds. The physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-description-reusable-exact-foot-20260804`.

ELF dependency profiling now reads each bounded dynamic-string-table name into
one retained fixed-size stack buffer with a single `read_exact`, validates its
terminating NUL and UTF-8, and allocates only the accepted final `String`. The
former byte-at-a-time reads and per-name temporary vectors are removed while
the existing name and dependency-count ceilings remain unchanged. ELF profile
regressions, all 131 package tests, the locked Rust workspace suite, the
complete Android source unit/lint gate, and both exact-ABI manager/Builder
builds pass. The physical-Samsung Foot regression retained stable processes, a
34 px font, 126 px controls, and exact visible command output in inspected
full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/elf-needed-fixed-scratch-foot-20260804`.

Local pacman `files` ownership scans now retain each streamed line in a fixed
4 KiB stack buffer rather than a heap vector that could grow from its initial
256-byte capacity. This covers both foreign-file conflict preflight and desktop
entry source/executable ownership discovery while preserving exact size,
section, path, ambiguity, and overlong-line handling. A direct regression proves
the exact 4 KiB boundary and rejects its next byte. All 131 package tests, the
locked Rust workspace suite, the complete Android source unit/lint gate, and
both exact-ABI manager/Builder builds pass. The physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-local-files-fixed-scratch-foot-20260804`.

Bounded package-command output now restores a wrapped retained tail by rotating
its existing byte vector in place. The former conversion allocated a second
tail-sized vector and copied both ring segments before returning ordered output.
Exact-tail and truncation-notice regressions pass with all 131 package tests,
the locked Rust workspace suite, the complete Android source unit/lint gate,
and both exact-ABI manager/Builder builds. The physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-command-tail-in-place-foot-20260804`.

Process-tree discovery and identity revalidation now parse each bounded
`/proc/<pid>/stat` record directly from the existing 4 KiB stack buffer and
retain only the optional parent PID and start time with descriptor identity.
The former path allocated a `String` for every root, candidate, parent, and
revalidation read before immediately parsing and dropping it. Malformed-record
skip/error behavior, no-follow opens, exact descriptor/path identity checks,
and process reuse defenses remain unchanged. All 31 process tests, scoped
all-target warning-denied Clippy, the locked Rust workspace suite, the complete
Android source unit/lint gate, and both exact-ABI manager/Builder builds pass.
The physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and exact visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/process-stat-stack-parse-foot-20260804`.

Managed `kdeglobals` admission now allocates one initialized buffer at the
opened descriptor's admitted metadata length plus a single growth-probe byte,
rather than allocating the complete 64 KiB policy ceiling for every ordinary
configuration. The read must return exactly the admitted length; truncation or
the first growth byte fails before marker inspection, and the existing
no-follow and descriptor/path metadata-stability checks remain unchanged. All
31 process tests, scoped all-target warning-denied Clippy, the locked Rust
workspace suite, the complete Android source unit/lint gate, and both exact-ABI
manager/Builder builds pass. The physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and exact visible command
output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/kde-config-exact-allocation-foot-20260804`.

Process launch-plan ELF parsing now reuses one fixed 2,049-byte stack buffer for
the bounded dynamic `RUNPATH`/`RPATH` and every needed-library name. The former
helper allocated a temporary byte vector for each string before allocating the
final retained needed-library `String`; search paths still allocate only their
accepted normalized records, and needed names now allocate only their final
retained strings. String-table bounds, NUL termination, UTF-8, path, name, and
dependency-count validation remain unchanged. All 31 process tests, scoped
all-target warning-denied Clippy, the locked Rust workspace suite, the complete
Android source unit/lint gate, and both exact-ABI manager/Builder builds pass.
The physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and exact visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/process-elf-string-scratch-foot-20260804`.

The same process launch-plan ELF parser now records up to 256 dynamic
needed-library string-table offsets in a fixed stack array with an explicit
count. The former temporary `Vec<u64>` could allocate and grow while walking
the bounded dynamic table before the final needed-library collection was
created. Exact dependency order and the existing first-overflow rejection are
preserved. All 31 process tests, scoped all-target warning-denied Clippy, the
locked Rust workspace suite, the complete Android source unit/lint gate, and
both exact-ABI manager/Builder builds pass. The physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/process-elf-needed-offsets-fixed-foot-20260804`.

Package compatibility ELF profiling now also records its up-to-256 dynamic
needed-library string-table offsets in a fixed stack array with an explicit
count. The temporary growing `Vec<u64>` is removed while exact encounter order,
first-overflow rejection, and the final exact-capacity retained dependency list
remain unchanged. All ten ELF profile tests, all 131 package tests, the locked
Rust workspace suite, the complete Android source unit/lint gate, and both
exact-ABI manager/Builder builds pass. Package warning-denied Clippy remains
blocked only by three unrelated existing Rust 1.97 style lints after the
changed local-file scanner's warning was resolved. The physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and exact
visible command output in inspected full-device frames. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-elf-needed-offsets-fixed-foot-20260804`.

Package compatibility ELF profiling now also records up to 256 admitted load
segments in a fixed stack array with an explicit count. The former temporary
`Vec<LoadSegment>` allocation and growth are removed; virtual-address mapping
receives only the populated prefix, while program-header, file-region, dynamic
segment, and string-table validation remain unchanged. All ten ELF profile
tests, all 131 package tests, the locked Rust workspace suite, the complete
Android source unit/lint gate, and both exact-ABI manager/Builder builds pass.
Package warning-denied Clippy remains blocked by the same three unrelated Rust
1.97 style lints. The physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and exact visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-elf-load-segments-fixed-foot-20260804`.

Process launch-plan ELF parsing now rejects every load segment whose file range
exceeds the opened object or whose virtual range overflows, requires dynamic
segment sizes to contain complete 16-byte records, and requires an explicit
`DT_NULL` terminator. Virtual-to-file translation also uses checked address and
offset arithmetic. A direct regression covers an out-of-file load segment, a
misaligned dynamic segment, and a dynamic table without a terminator. All 32
process tests, scoped warning-denied Clippy, the locked Rust workspace suite,
the complete Android source unit/lint gate, and both exact-ABI manager/Builder
builds pass. The physical-Samsung Foot regression retained stable processes, a
34 px font, 126 px controls, and exact visible command output in inspected
full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/process-elf-segment-bounds-foot-20260804`.

Process launch-plan ELF parsing now also rejects a second `PT_DYNAMIC` segment
and requires the complete declared dynamic string table to remain inside one
validated file-backed load segment. Virtual-to-file mapping returns the exact
remaining segment extent, so a string-table start near a load boundary cannot
authorize later bytes merely because they exist elsewhere in the file. The
malformed-ELF regression now also covers duplicate dynamic segments and a
partially mapped string table. All 32 process tests, scoped warning-denied
Clippy, the locked Rust workspace suite, the complete Android source unit/lint
gate, and both exact-ABI manager/Builder builds pass. The physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and exact
visible command output in inspected full-device frames. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/process-elf-string-containment-foot-20260804`.

Process launch-plan ELF dependency resolution now streams its two conventional
library candidates and the bounded private search-path candidates in lookup
order. The former path first allocated and populated a temporary vector for
every candidate, including all private-path joins even when `/usr/lib` or
`/lib` resolved the dependency immediately. Resolution order, canonical root
containment, file-type, and world-writable rejection remain unchanged. All 32
process tests, scoped warning-denied Clippy, the locked Rust workspace suite,
the complete Android source unit/lint gate, and both exact-ABI manager/Builder
builds pass. The physical-Samsung Foot regression retained stable processes, a
34 px font, 126 px controls, and exact visible command output in inspected
full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/process-elf-lazy-dependency-candidates-foot-20260804`.

Package compatibility ELF graph traversal now also constructs library
candidates lazily in source-directory, `/usr/lib`, then `/lib` order. The
former fixed candidate array allocated all three `PathBuf` values before
testing the first, even when the source-local object resolved immediately.
Root-contained regular-file resolution and candidate priority remain
unchanged. All ten ELF profile tests, all 131 package tests, the locked Rust
workspace suite, the complete Android source unit/lint gate, and both exact-ABI
manager/Builder builds pass. Package warning-denied Clippy remains blocked only
by the same three unrelated Rust 1.97 style lints in package parsing code. The
physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and exact visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-elf-lazy-library-candidates-foot-20260804`.

Package and Terminal warning-denied Clippy gates are current with Rust 1.97.
Package assumed-dependency parsing now uses `?` for its final exact-version
alternative, and final search-result publication uses one equivalent let-chain.
Terminal palette operations use a `while let` iteration, while bounded Base64
padding validation uses the standard multiple-of predicate. All 131 package
tests, all 46 Terminal tests plus its warmed allocation gate, scoped all-target
warning-denied Clippy for both crates, the locked Rust workspace suite, the
complete Android source unit/lint gate, and both exact-ABI manager/Builder
builds pass. The full warning-denied workspace Clippy audit now proceeds past
both crates and is blocked by 19 pre-existing Rust 1.97 style findings in the
compositor (`manual_is_multiple_of`, `collapsible_if`, and
`unnecessary_unwrap`). The physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and exact visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-terminal-rust197-clippy-foot-20260804`.

The compositor's first low-risk Rust 1.97 warning slice is resolved. Wayland
pixel conversion now uses the standard multiple-of predicate for its four-byte
pixel boundary, and the host drag-source probe consumes its optional received
descriptor through a let-chain rather than checking and then unwrapping it.
The 107 compositor tests, the locked Rust workspace suite, the complete Android
source unit/lint gate, and both exact-ABI manager/Builder builds pass. Scoped
warning-denied compositor Clippy confirms those two findings are gone and now
reports only 17 pre-existing `collapsible_if` findings. The physical-Samsung
Foot regression retained stable processes, a 34 px font, 126 px controls, and
exact visible command output in inspected full-device frames. Hashed evidence
is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/compositor-rust197-low-risk-clippy-foot-20260804`.

Local-package database repair cleanup now reapplies its five-file limit when it
reopens a record after validation. The second enumeration can fill only the
pre-sized five-path vector and rejects a concurrent sixth entry before deleting
any file. A deterministic post-open growth regression proves all six files and
the record directory remain after `OutputLimit`. The pinned package crate now
passes 130 tests together with warning-denied all-target Clippy and formatting;
exact x86_64/AArch64 manager builds also pass. The rebuilt AArch64 manager then
retained a stable Samsung Foot session with exact visible command output, clean
scoped logs, and an inspected 1080×2202 frame. Hashed full-device evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/database-repair-rescan-bound-foot-20260804`.

A first shared-command slice is also connected. A separate Rust process crate
resolves one exact installed command under `/usr/bin`, follows at most 16
relative or Linux-root absolute symlinks without leaving the private root,
rejects writable programs and shell syntax, and launches it directly through
the verified loader and generic path bridge. ELF programs run directly.
Scripts must name a conventional `/usr/bin` or `/bin` shebang interpreter that
resolves to an installed ELF program inside the same root; Android-host,
missing, malformed, and recursive script interpreters fail closed. The process
receives a cleared, conventional shared-home/XDG environment, installed
`C.UTF-8` locale data, a fixed argument budget, 15 KiB combined-output limit, 30-second
deadline, and its own process group; temporary output is mode 0600 and removed
on every return path. Kotlin crosses JNI once per command on a worker thread
and exposes a deliberately small diagnostic command panel.

Clean signed install/remove/reinstall cycles execute `btop --version` and show
exit 0 plus the real installed version in full-device screenshots on both the
x86_64 emulator and AArch64 Samsung. The Samsung gate additionally dismisses
the software IME before locating the on-screen Run action, avoiding the
emulator-only assumption that a hardware keyboard leaves the lower controls
visible. Bash is also installed through the normal signed package flow on both
targets, then runs a temporary root-contained `#!/usr/bin/bash` script with an
argument. The fixture is removed after the warning-free result is visible in a
full-device screenshot; Bash remains a normal shared package. This does not
yet constitute a terminal: interactive PTYs, cancellation, locale
provisioning, terminal emulation/scrollback, and durable session supervision
remain open.

The raw PTY foundation is now connected separately from terminal rendering.
Rust owns a four-slot generation-checked registry. Each session opens
`/dev/ptmx` nonblocking, grants and unlocks its exact slave, validates the
bounded `/dev/pts/<number>` path, applies bounded window dimensions, creates a
new session and controlling terminal immediately before exec, and transfers at
most 16 KiB per JNI read or write. Resize uses the kernel window-size ioctl.
EOF finalization terminates descendants before reaping the session leader and
preserves either its signed exit code or negative terminating signal. Close,
handle destruction, and runtime destruction reap the entire process group
deterministically.

The Android Service now owns one user-controlled package-installed Bash
session across Activity recreation. It uses fixed 8 KiB input and 16 KiB
output rings, reusable 4 KiB direct JNI buffers, partial-write backpressure,
explicit stop, and preserved signed exit status. Each session creates one
fixed Unix wake channel and one cloned PTY poll descriptor. Rust blocks in
`poll(2)` for output, pending-write readiness, or an explicit input/stop/close
wake; Kotlin's former 100 ms sleep loop is gone. Each notification drains at
most four reusable 4 KiB buffers before blocking again. Bash runs with
`--noediting`: Archphene's Android terminal layer must own line editing, and
the redundant GNU Readline idle path is killed by the inherited Android app
seccomp profile. This is a generic shared-shell policy, not a patch to Bash.

A user-started shell promotes the runtime owner to an Android special-use
foreground Service. Its low-priority ongoing notification opens Archphene and
publishes an explicit Stop action; Android 13+ notification permission is
requested only when the user starts a shell. Home, Back, and task removal no
longer terminate an active session. Once the shell exits or is stopped, the
notification is removed, the Linux process group is reaped, and an unbound
Service stops itself.

Rust also owns a fixed `active\n` session marker under the private Arch root.
It is published atomically with mode 0600 after PTY creation, rejects
symlink/non-file substitution, and is removed after the last clean PTY close.
If manager death or reboot leaves it behind, the next bootstrap sets a durable
snapshot flag and the UI says the previous session was interrupted, retaining
the same Home but requiring an explicit fresh shell. Same-UID `SIGKILL`,
interrupted-state rendering, restart, clean marker removal, and full-device
screenshots pass on both maintained targets. The same marker retention,
interrupted-state rendering, explicit restart, and clean-marker removal also
pass after real reboots of the emulator and physical Samsung.

User-visible processes now receive Linux-facing `HOME=/home/archphene`,
`TMPDIR=/tmp`, conventional XDG locations, and
`PATH=/home/archphene/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/bin`;
bridge-private host paths remain
only in the explicit loader variables. The path bridge maps `getcwd` and
`get_current_dir_name` back into the shared root, so both `PWD` and `pwd`
report `/home/archphene` instead of Android's private data path. `LOCPATH`
points directly at the verified locale data installed by the architecture's
official glibc package, enabling `C.UTF-8` without generating or bundling a
second locale. Root bootstrap creates `.bashrc`, `.bash_profile`, `.zshrc`,
and Fish `~/.config/fish/config.fish` defaults once, rejects symlink
substitution, and never overwrites user edits. It also creates private
`~/.local/bin`; that conventional directory is first in the shared PATH for
terminal and graphical processes, while additional startup-file PATH changes
intentionally affect only shells that source them.

Interactive lifecycle gates now pass on the x86_64 emulator and AArch64
Samsung. Each proves the Archphene prompt, HOME, PWD, conventional PATH, and
UTF-8 charmap, sends and observes two markers, forces an
Activity recreation by changing device rotation, proves the Service retains
the session and output, proves Home and Back retain the same Android and Linux
processes with a real foreground notification and Stop route, observes
`exit 7`, restarts and explicitly stops the shell, proves no loader child
survives, checks scoped fatal logs, restores rotation, and captures full-device
screenshots. The images also honestly show
that the temporary two-line diagnostic strip clips in landscape. Explicit
process-death/reboot interruption and restart policy, a real terminal parser
and renderer, scrollback, selection, IME/hardware-keyboard handling,
clipboard, and accessibility remain open.

Shell launch is no longer hardcoded in Kotlin. Rust reads the bounded,
non-symlinked, non-group/world-writable shared `/etc/shells`, cross-checks each
supported adapter against the safely resolved installed executable, and
publishes only the reviewed Bash, POSIX-shell, Zsh, and Fish launch records.
The Kotlin Service reads and writes the stable identifier through one
prewarmed preference path, honors the former UI preference key as a migration
fallback, refreshes discovery after package mutation, prebuilds each bounded
NUL-delimited PTY request once, and hides the selector while a session is
active. Unsupported or unsafe catalogs disable shell launch without making
package management unavailable.

The readable Android selector, process-death persistence, Bash/POSIX/Zsh/Fish
PTY startup, stop/reap behavior, scoped fatal logs, and full-device screenshots
pass with exact-ABI APKs on the API 36 x86_64 emulator and AArch64 Samsung.
Stock Zsh and Fish were installed through Archphene's normal package UI on both
targets; Bash is restored as the selected default after each gate. Android
Files now exposes a virtual **Shell startup files** directory containing
writable `Edit .bashrc`, `Edit .bash_profile`, `Edit .zshrc`, and
`Edit Fish config` documents. Rust maps only those four stable identities
through descriptor-relative no-follow opens of the real user-owned files;
Android cannot create, rename, or delete entries there, and arbitrary dotfiles
remain private. Host substitution tests and exact-ABI DocumentsUI
read/write/security/full-device gates pass on both targets. The remaining
production terminal work remains pending.

The production terminal replacement now has a separate Rust state-core
foundation. It bounds grids to 200 by 400 cells, parses strict streaming UTF-8
without per-feed allocation, implements delayed VT autowrap,
cursor movement, erase operations, scroll regions, basic SGR attributes,
bounded OSC/DCS/APC suppression, resize preservation, and coarse dirty-row
tracking. Host gates cover split multibyte input, malformed/control input,
wrapping, scrolling, color state, resize bounds, and 1,000 warmed parser/grid
updates with zero heap allocations.

Every PTY session now owns one of those terminal states. Each successful native
PTY read feeds the exact returned bytes into it, and resize updates both the
kernel PTY and grid. Android can drain changed rows through one direct-buffer
JNI call using the bounded `ATRM` version-1 wire format: a fixed 32-byte header
plus fixed eight-byte cells, capped at 640,032 bytes for the maximum grid.
Undersized output fails without consuming damage. The caller owns and reuses
the buffer, and the warmed parse-plus-transfer gate performs 1,000 updates
without heap allocation.

A real host PTY test proves that colored, bold process output reaches the wire
as terminal cells rather than synthetic Kotlin text. The JNI entry point is
exported by both exact Android ABIs, both ABI-specific APKs build, and Android
lint passes.

Android rendering is now connected as a bounded first production slice.
Starting a shared shell replaces the package workspace with the available
full-height terminal surface instead of a clipped 64 dp diagnostic strip. The
view measures scaled automatic monospace cells, resizes the real PTY to the resulting
rows and columns, stores only the current dimensioned primitive cell arrays,
and draws Android `RenderNode` rows. A Service-owned 640,032-byte direct buffer
is allocated lazily once and survives Activity recreation. The Choreographer
path checks a monotonic Service revision before JNI, drains all accumulated
native damage in one call, and re-records only changed row nodes.

Rotation testing exposed that preserving the top-left grid discarded recent
output when 36 portrait rows became seven landscape rows. Shrink now anchors
the copied window at the cursor. Home/Back testing then exposed that a fresh
Activity could not reconstruct rows already consumed by its predecessor; the
same wire call now supports an explicit bounded full snapshot used once by a
new view, while steady state remains dirty-only. On-demand accessibility node
queries synthesize at most 8 KiB of visible text; normal frames no longer build
diagnostic terminal strings.

The complete shared-shell lifecycle and installed-shell selection gates pass
with exact-ABI APKs on the API 36 x86_64 emulator and physical AArch64 Samsung.
They cover Bash and POSIX-shell prompts, conventional Home/PATH/UTF-8 output,
phone-width wrapping, portrait/landscape PTY resize, Activity recreation,
Home/Back process retention and full reconstruction, foreground notification,
exit status, restart, stop/reap, unsafe-shell-catalog recovery, scoped fatal
logs, accessibility trees, and visually inspected full-device screenshots.

Terminal focus now opens Android's software keyboard through a full-editor
`InputConnection` while keeping composing text local until commit. Committed
text is encoded directly into one reusable 8 KiB UTF-8 buffer; the Service
copies it atomically into its fixed PTY queue and wakes the Rust pump. Hardware
Enter, Backspace, forward Delete, Tab, Escape, navigation, Insert, F1-F12,
common Ctrl characters, Alt escape prefixes, and AltGr text use static
sequences or that same scratch buffer rather than allocating a byte array per
key. Preedit is capped at 2,048 UTF-16 code units, oversized commits fail
atomically before entering the editable shadow, and IME surrounding deletion
is bounded to 64 characters with both backward and forward terminal deletion.

The exact-ABI lifecycle gate now focuses the terminal, attaches each device's
IME, types a command containing a deliberate final error, removes it through
the hardware Backspace route, executes it through Enter, and waits for a Bash
builtin result that did not appear in the typed command. This passes on the API
36 emulator and physical Samsung, including scoped fatal logs and visually
inspected full-device IME screenshots. A separate deterministic composing gate
keeps one full-editor InputConnection, replaces an in-progress Japanese
candidate locally, exposes that preedit separately to Android accessibility,
and proves that Japanese, CJK, an emoji modifier, and a ZWJ sequence do not
reach Bash until finish. It then commits the exact UTF-8 result and clears the
preedit on both exact-ABI devices, with full-device before/after evidence.

The terminal core now owns preallocated primary and alternate grids of the
current bounded dimensions. DEC private modes `47`, `1047`, and `1049` switch
or clear them with complete damage publication; cursor visibility and
save/restore modes are retained, and resize preserves both screens around
their respective cursors. Repeated `1049` entry, rendering, and exit remain
allocation-free after construction. Installed `tput` gates prove primary
preservation, alternate clearing/content, discarded alternate output, and
exact primary restoration with full-device screenshots on both ABIs.

Input modes now travel in reserved flags of the same versioned terminal-damage
header, so mode changes add no JNI call. Rust tracks application cursor/keypad,
bracketed paste, ANSI newline, and DEC backarrow state. Android uses static
normal/application cursor, keypad, Enter, and Backspace sequences; IME newline
commit/action and surrounding deletion follow the same newline/backarrow
modes. The reusable text buffer still provides the only dynamic key payload.
The warmed parser/damage gate repeatedly changes all modes without allocation.

An exact device gate runs Bash `read -n 3`, sends Android hardware Up, and
captures the bytes as normal `ESC [ A`. Installed `tput smkx` then changes the
real terminal modes and the same key is captured as application `ESC O A`;
`tput rmkx` restores normal mode. Exact-ABI builds, Bash byte assertions,
accessibility, scoped logs, and visually inspected full-device screenshots
pass on the emulator and Samsung.

The terminal core also handles the common in-place operations used by editors
and full-screen tools: ICH/DCH/ECH, IL/DL, SU/SD, reverse index, repeat,
insert mode, CNL/CPL/VPA/HPA, ANSI cursor save/restore, and programmable
forward/backward tab stops. Tab state is a fixed 400-column array and all edit
operations mutate the preallocated grid with bounded `copy_within` calls.
These paths are included in the warmed zero-allocation gate. A device fixture
uses installed Bash and `tput` to produce ICH/DCH/IL/DL sequences through the
real PTY; exact content, accessibility, scoped logs, and visually inspected
full-device screenshots pass on both exact-ABI targets.

Foreground and background indexes now retain all eight wire bits in Android
instead of being truncated to the original 16 colors. Rust parses
`38/48;5;n`; RGB SGR is bounded and mapped to its nearest indexed color until
the damage protocol gains exact direct-color fields. Android creates one fixed
256-entry palette at class initialization and keeps the existing 8-byte cell,
coarse direct buffer, and row cache. No per-cell or per-frame allocation was
added.

The installed-`tput` color gate initially exposed a parser bug: the `ESC ( B`
ASCII designation in `sgr0` leaked a literal `B` with the previous background.
The streaming parser now consumes G0/G1 designations, handles SI/SO selection,
and maps the DEC special-graphics set to Unicode line drawing. Rust wire and
warmed allocation tests pass; installed 256-color foreground/background,
reset, line drawing, accessibility, scoped logs, and visually inspected
full-device screenshots pass on both exact-ABI targets.

Android clipboard paste is bounded to 2,048 UTF-16 code units, encoded into
the existing reusable 8 KiB terminal input buffer, and submitted through the
existing Service queue. No new JNI call or per-key allocation was added.
Literal paste newlines remain LF; when Rust publishes bracketed-paste mode in
the existing damage flags, Android adds exact `ESC [ 200 ~` and
`ESC [ 201 ~` wrappers. Paste is available from a long-press touch menu,
hardware `Ctrl+Shift+V`, the InputConnection context action, and accessibility.
The popup is allocated only for the explicit long-press action; one reusable
gesture detector handles the motion stream.

A bounded exported receiver exists only in the debug source set to seed the
real Android clipboard deterministically; release manifests do not include
it. The device gate uses that clipboard, installed Bash, normal line capture,
exact bracketed byte capture, touch and hardware routes, accessibility,
scoped logs, and full-device screenshots. It passes on the emulator and
Samsung exact-ABI builds.

Terminal text now follows Android's scaled 16sp baseline in automatic mode.
An explicit choice is bounded to integer 10–32sp steps and persists in the
Android app sandbox. Users can change it with pinch zoom, the standard
long-press menu, or hardware `Ctrl+-`/`Ctrl++`; `Ctrl+0` and the labeled menu
item restore Auto. Pinch motion previews with a canvas transform and commits
font metrics, cached-row re-recording, persistence, and the coarse PTY resize
only when the gesture ends.

The manager Terminal uses the repository's checksum-pinned, no-ligature
JetBrains Mono face and packages its OFL license instead of trusting Android's
device-dependent generic `monospace` alias. Every grapheme is painted at its
explicit cell coordinate using existing scratch storage. This removed an OEM
regression where Samsung compressed a packed prompt while leaving the typed
cell and cursor on the wider terminal grid.

Exact-ABI device gates prove Auto, explicit 20sp, touch/hardware controls,
process-restart persistence, reset, scoped logs, and full-device screenshots
on the emulator and Samsung. The broad PTY/input/lifecycle gate and
clipboard/bracketed-paste gate still pass on both targets after the gesture and
menu changes. Offline Android lint also passes.

The terminal damage protocol is now version 4 with a 40-byte header and fixed
76-byte cells. Each cell carries up to 16 Unicode scalar values, one/two-column
width or continuation state, independently encoded foreground/background, and
compact attributes.
The exact dependency lock pins `unicode-width` 0.2.2 and
`unicode-segmentation` 1.13.3; both are available to the established offline
Cargo cache. Streaming grapheme assembly, width calculation, grid mutation,
and damage publication remain allocation-free after warm-up.

Rust preserves decomposed combining text, CJK width, regional-indicator flags,
emoji modifiers, and ZWJ families. Wide cells are normalized after overwrite,
insert/delete/erase, and resize so edits cannot leave orphaned continuation
cells. Android uses dimension-bound primitive arrays and one reusable UTF-16
row scratch buffer rather than per-cell strings. It anchors each grapheme to
its exact terminal column, including after blank cells.

Palette colors remain integer indexes; direct SGR `38;2`/`48;2` colors carry
exact 24-bit RGB. Android stores attributes in unused high bits of its
foreground integer, so protocol v4 still uses the same single coarse JNI read.
The maximum reusable direct buffer is explicitly bounded at 6,080,040 bytes,
and normal dirty updates publish only the affected rows.

The primary screen now owns a preallocated compact scrollback ring bounded to
4 MiB and 4,096 stored physical rows. Full-screen primary scrolling appends
cells without warmed heap allocation; alternate-screen output and
application-defined partial scroll regions never enter history. Stored rows
are chunked to the current width when a narrower viewport reads them, while
full logical-line joining across soft wraps remains pending.

Protocol v4 publishes the available visual-history rows and the applied
viewport offset. Android requests one full bounded viewport when scrolled,
hides the live cursor, keeps a viewed position anchored as new history arrives,
and returns to live output when the user types. Touch drags, pointer wheels,
hardware Shift+PageUp/PageDown, and framework accessibility scroll actions use
the same clamped offset. The accessibility snapshot reads the visible history
rather than the hidden live cursor row.

The Android terminal surface now supports bounded selection over its currently
visible grid. A long press selects the complete nonblank cell run under the
finger, dragging extends the range, and a cell-aligned overlay makes the exact
range visible without rebuilding terminal strings. Copy assembles at most
2 KiB of the selected graphemes and row separators only on the explicit user
action, then publishes through Android's clipboard. The long-press menu,
`Ctrl+Shift+C`, InputConnection context action, and accessibility Copy share
that path. Output, viewport changes, resize, and terminal input clear stale
grid-relative ranges.

The entire Rust workspace, damage-format contracts, and warmed zero-allocation
gate pass. Exact-ABI builds render installed `tput` 256-color output, exact
`#123456` on `#abcdef`, reset, DEC line drawing, accessibility markers, scoped
logs, and visually inspected full-device screenshots on the emulator and
Samsung. Installed Bash/terminfo gates additionally prove DEC autowrap disable
and origin-relative cursor positioning, decomposed accents, CJK, flags,
modifiers, ZWJ emoji, accessibility text, and exact last-column rendering on
both targets. The broad PTY/render/input/lifecycle, color, and editing gates
also pass on both targets with protocol v4. A dedicated exact-ABI gate proves
visible retained history, touch, mouse wheel, hardware page navigation,
accessibility, return to live output, scoped logs, and visually inspected
full-device screenshots on the emulator and physical Samsung.
An additional exact-ABI gate selects a complete terminal word by touch, checks
the visible cell highlight and Copy menu in a full-device screenshot, and
pastes the clipboard back through the PTY for an exact string assertion.
Existing touch-menu/bracketed Paste and scrollback gesture gates still pass on
both targets.

Overlong Unicode clusters now have an explicit bounded policy. A cell retains
at most 16 scalars, replaces its truncated tail with U+FFFD, discards further
zero-width extenders, and resumes at the next printable cell; one internal
attribute bit prevents repeated growth without changing Android's rendered
style. The warmed allocation gate and exact-ABI full-device Unicode workflows
prove the visible replacement boundary alongside combining text, CJK, flags,
emoji, autowrap, and origin mode on both devices.

That device gate also exposed an incomplete generic root-identity bridge:
glibc's filesystem-ID query reached Android's blocked x86_64 syscall 122.
The path bridge now consistently virtualizes uid, gid, effective uid/gid, and
filesystem uid/gid as root while Android's kernel continues enforcing the real
app UID. The source contract, host identity probe, sealed exact-ABI bridges,
installed `tput`, scoped logs, and device gates pass.

Trying the installed full-screen `btop` exposed an independent compatibility
boundary: stock Android SELinux denies an ordinary app access to `/proc/stat`,
so btop gets beyond terminal and identity initialization but exits when it
cannot parse global CPU statistics. Archphene does not bypass that policy or
fabricate CPU telemetry.

The generic path bridge now bounds the kernel filesystem view that is actually
reliable. `readdir`, `readdir64`, `scandir`, `scandir64`, and direct
`getdents64` enumeration of `/proc` omit every numeric process whose status is
unreadable to the app UID, preventing Android-wide PID disclosure while
retaining Archphene's same-UID process tree. `/proc/self`, CPU-topology sysfs,
safe character devices, and the private shared-memory mapping remain available.
Host contracts and exact x86_64/AArch64 probes pass on the emulator and
physical Samsung with full-device captures. This safely completes the bounded
view; it does not make full-screen system monitors compatible where Android
withholds the underlying metrics.

This is not yet the production terminal promised by the milestone. Consecutive
soft-wrapped rows now join into bounded logical lines across scrollback and the
live primary screen, reflow at the current width without splitting wide
graphemes, and cache their width measurement outside resize and line-extension
work. Resize pulls a trailing history continuation into the live viewport,
returns only rows above the cursor-preserving viewport to history, preserves
hard line boundaries, and retains physical-grid semantics for alternate-screen
TUIs. Exact-ABI portrait-to-landscape gates preserve one manager process and
visibly reunite a marker that began in portrait history and ended on the live
screen on both the emulator and Samsung.

Selection now uses bounded coordinates in the combined scrollback/live
document instead of transient viewport-cell indexes. Damage protocol v7
publishes a scrollback-origin epoch; appending output preserves historical
ranges, while actual ring eviction, resize, or selected live-screen mutation
invalidates them rather than copying unrelated cells. Rust serializes at most
8 KiB of selected UTF-8 directly from stored history and the live grid,
omitting newlines across soft wraps, through one Service-owned direct buffer.
Android renders fully visible endpoint handles with 48 dp hit regions, permits
endpoint crossing, preserves selection while the viewport moves, and performs
preallocated, three-frame-throttled edge autoscroll. Accessibility exposes the
selected state and Copy action. Exact x86_64 emulator and AArch64 Samsung gates
prove long-press selection, off-screen/back stability, rendered-handle
discovery and drag, edge autoscroll into old history, exact clipboard
paste-back, scoped fatal logs, and inspected full-device frames. The remaining
terminal device work is consolidated below.

The current terminal core now honors xterm background-color erase across
character, line, display, edit, and scroll-created cells while resetting
foreground and attributes. Its unit and warmed zero-allocation gates pass.
Android now coalesces terminal text-change and scroll accessibility events,
publishes the cursor or visible selection as UTF-16 text offsets, accepts
bounded accessibility selection, and includes nonblank content below the
cursor. The implementation passes Android-10 lint/unit checks and exact
x86_64/AArch64 builds. The current exact-ABI production-terminal gate now runs
through package-installed Bash and `tput` on both devices; full-device captures
show the background color extending through the erased remainder of the line.

The terminal now advertises and handles Android character, word, and line
movement granularities, emits exact traversal events, and extends bounded
terminal selection when the accessibility action requests it. Two retained
locale iterators keep TalkBack gestures off the frame path and avoid rebuilding
Unicode boundary machinery for every gesture; unit coverage includes combining
graphemes, terminal punctuation, lines, edges, and unsupported requests. A
debug-only exact damage-protocol Activity presents the production terminal View
without weakening or adding hooks to the release APK. The maintained device
script installs nothing, restores accessibility and notification state,
verifies the installed Google TalkBack service is bound, requires real
accessibility focus plus the character/word/line mask, drives next-item
navigation, and retains full-device focus-border pixels. That workflow passes
on the API 36 emulator. Samsung's preinstalled TalkBack still opens its
user-owned first-run tutorial, so a debug-only public-framework accessibility
service tests the exact production View there without changing vendor
onboarding. It performs accessibility focus, selected-text, and word-traversal
actions, receives the exact focus/selection/traversal events, retains
full-device selected/focus evidence, and restores every secure accessibility
setting afterward. The same framework gate also passes on the emulator.

Auditing the terminal against the locally installed `xterm-256color` terminfo
also exposed two terminfo-used controls that had been silently ignored. DEC
soft reset now preserves visible content while restoring scrolling margins,
saved cursor, character sets, rendition, and update-affecting modes. DEC
reverse-screen mode is queryable through the fixed reply ring and published in
one reserved damage-flag bit; Android applies it as an XOR with per-cell
inverse video and re-records every row only when the mode changes. Rust unit,
warmed zero-allocation, and clippy gates plus Android lint/unit and exact
x86_64/AArch64 build gates pass.

The advertised dynamic-color capability is no longer a false promise. A
fixed-size OSC parser accepts bounded OSC 4 palette set/query and OSC 104
selective/all reset commands, including xterm `rgb:` and one-to-four-digit
per-component `#` forms. Cells retain their indexed color internally and on the
damage wire while the palette is unchanged; only an overridden index is
published as exact direct RGB, so no palette block, protocol expansion, or
per-frame allocation is required. A change invalidates existing rows, queries
use the fixed reply ring, and malformed, partial, or overlong commands cannot
partially mutate the palette. Unit and warmed zero-allocation gates pass.
Clippy and exact x86_64/AArch64 manager builds also pass.

DECLRMM/DECSLRM horizontal margins are now modeled independently for primary
and alternate screens, so `CSI s` remains cursor save unless left/right margin
mode is active. Origin-relative addressing and cursor reports, CR/NEL, tabs,
autowrap, wide graphemes, character/line edits, and vertical scrolling honor
the active rectangle. Partial-width scrolling copies only the bounded columns,
preserves outside content, clears boundary-crossing wide cells, and never
mislabels a rectangle as full-width scrollback. Resize, soft/hard reset, mode
queries, and alternate-screen transitions restore bounded defaults. Focused
unit, warmed zero-allocation, clippy, Android lint/unit, exact x86_64/AArch64
build, and APK-install gates pass; the exact Bash PTY probe is prepared but
awaits restoration of the clean-data device roots.

The terminal now consumes bounded DECSCUSR cursor requests and publishes
block, underline, or bar shape plus steady/blinking behavior in reserved damage
flags. The terminfo-used DEC private mode 12 now independently controls and
reports blink state, so `cnorm`/`cvvis` no longer fall through as ignored
sequences. Android renders those shapes without another terminal-grid allocation,
resets the 500 ms phase when cursor content or presentation changes, and stops
the callback while the surface is detached, hidden, or unfocused. Rust unit,
warmed zero-allocation, and clippy gates plus Android lint/unit and exact
x86_64/AArch64 builds pass. Both APKs are installed and cold-launch without
scoped fatal errors. The live package-installed PTY gate visibly alternates an
exact red blinking bar cursor on both devices while retaining phone-scale
terminal geometry.

Mouse and focus reporting now cover the modes used by the local
`xterm-256color` description and common full-screen applications: X10, VT200,
button-motion, any-motion, focus in/out, and normal, UTF-8, SGR, URXVT, or
SGR-pixel coordinates. Protocol and encoding modes are mutually exclusive,
queryable, reset-safe, and published through reserved damage flags. Android
maps touch, external buttons, wheel, modifiers, cell motion, and pixel motion
into an allocation-free encoder backed by the existing reusable terminal input
array. Motion is emitted only after the reported cell or pixel changes, while
Shift retains local selection and history scrolling. Rust unit/allocation and
clippy gates, exact Kotlin encoder tests, Android lint/unit, both exact builds
and installs, scoped cold-launch logs, and inspected full-device manager
frames pass. The live package-installed PTY gate now verifies exact SGR reports
from touchscreen press/release, external mouse press/release, mouse wheel, and
Home/resume focus-out/focus-in on the emulator and Samsung.

DEC private synchronized-output mode 2026 now withholds terminal damage without
consuming dirty rows and publishes the complete current frame when the client
ends the update. A monotonic generation distinguishes an end/start pair parsed
within one PTY read, so a new frame never inherits the preceding frame's
deadline. If a client crashes or leaves the mode set, a 250 ms fail-safe
releases a full frame. While suppression is active, Android bypasses its normal
revision early return and polls the existing native damage buffer until release
or timeout; the steady-state parser/grid/damage path remains allocation-free.
Rust unit/allocation and clippy gates, Android unit/lint, both exact builds and
installs, scoped cold-launch logs, and inspected full-device light/dark manager
frames pass. Rapid full-device sequences from the live PTY on both targets
contain only the complete prior screen or `ATOMIC-FRAME-COMPLETE`, never its
partial prefix. A separately held synchronized frame becomes visible through
the 250 ms fail-safe without requiring later PTY output.

SGR 5/6 text blink and SGR 25 reset now survive the fixed terminal grid,
compact scrollback, and damage protocol v7. The Rust `Cell` remains 76 bytes:
the previous internal truncation marker moved into existing struct padding and
the already-reserved scrollback byte, leaving the high wire attribute bit for
blink. Android packs blink into the unused high bit of its existing glyph-width
byte and tracks one Boolean per row, then re-records only rows that actually
contain blinking content on the existing 500 ms
callback. Text stays visible while the surface is hidden or unfocused and when
Android's animator setting disables motion. There is no per-frame allocation;
the full Rust workspace tests, warmed allocation gate, warnings-denied Clippy,
Android unit/lint, exact x86_64/AArch64 builds and installs, cold launches,
scoped logs, and inspected full-device light/dark manager frames pass. Timed
full-device live-PTY captures on both targets visibly alternate the SGR
blinking text while stable content remains unchanged.

OSC 12 now accepts bounded cursor-color set/query commands and emits an exact
16-bit-per-channel RGB reply through the fixed terminal reply ring; OSC 112
restores the default. Damage protocol v7 adds one fixed RGB field, and Android
uses it for block, bar, underline, and IME-composition cursors without
per-frame allocation. Malformed and multi-value commands fail closed, hard
reset restores the default, and soft reset preserves the selected color. Full
workspace test/clippy, Android unit/lint, exact x86_64/AArch64 builds and
installs, cold launches, scoped logs, and inspected full-device light/dark
manager frames pass. The live exact-ABI PTY gate shows the requested red color
on the blinking bar cursor on both targets.

Xterm DEC private mode 1034 is now bounded, queryable, resettable, and
published through a reserved damage flag. Android keeps the normal ESC-prefix
Meta behavior until the mode is enabled. Under the terminal's UTF-8 locale,
enabled ASCII and control chords set the logical high bit and encode that
value as valid UTF-8; non-ASCII chords safely retain ESC-prefixed UTF-8. The
parser/damage loop remains warmed-allocation-free. Exact encoder tests, full
workspace test/clippy, Android unit/lint, exact x86_64/AArch64 builds and
installs, cold launches, scoped logs, and inspected full-device light/dark
manager frames pass. Real hardware Alt+A reaches package-installed Bash as the
exact UTF-8 encoding of the high-bit value on both targets.

OSC 52 now provides a deliberately write-only terminal-to-Android clipboard
bridge. It accepts only xterm clipboard selector characters, strictly decodes
padded or unpadded Base64, requires valid UTF-8, and caps decoded data at 2
KiB. Clipboard queries are ignored without a reply, so a terminal process
cannot read Android clipboard contents; malformed or oversized commands do not
mutate the clipboard. Fixed terminal, runtime, JNI, and service buffers carry
only the newest pending write, and one coalesced main-thread callback publishes
it without logging content. The redundant 16 KiB raw PTY-output mirror and its
snapshot copy were removed, leaving only the session phase outside the native
terminal. Host unit, warmed-allocation, and real-PTY tests, full workspace
test/clippy, Android unit/lint, exact x86_64/AArch64 builds and installs, cold
launches, scoped logs, and inspected full-device manager frames pass. A live
OSC 52 write from package-installed Bash reaches Android's clipboard and
pastes the exact bounded value back through the terminal on both targets.

The material `xterm-256color` contract audit is complete. Title/title-stack,
hyperlink, printer, and window operations remain deliberate no-ops where they
cannot affect visible terminal correctness or a safe Android interaction. The
consolidated exact-ABI shell, visual, input, clipboard, and accessibility
device proofs now pass on the emulator and Samsung.

The temporary command field and Run/Send controls are no longer present in the
production manager. Active sessions reserve only the measured terminal plus a
52 dp status/Stop row; normal regression input goes through the focused
terminal InputConnection and hardware Enter path. Noninteractive command
coverage remains available only through a bounded debug receiver that is
absent from the release manifest.

The validated prototype below remains reference evidence until replacement
vertical slices pass equivalent gates. Installed prototype state is no longer a
replacement requirement.

## Latest regression snapshot

On July 29, 2026, the final 11 standalone entry points left unreviewed after the
PowerShell-to-Bash migration were compared with their removed sources and made
assertion-complete. The audit restored explicit drag-provider readiness and
grant outcomes, fresh ARM probe identity, architecture-aware legacy catalog
metadata, real visible-window touch coordinates, fatal-log checks, prior
running/APK-state restoration, local-only Podman image policy, and an explicit
opt-in for the legacy two-emulator APK-replacement gate. The obsolete
hand-built KCalc version test now delegates to the supported manager-generated
wrapper replacement transaction. All Bash files pass syntax checks and every
audited entry point exposes a working help path. The x86_64 manager-native
static contract and both disposable PipeWire/GStreamer container gates pass;
drag/drop passes on the x86_64 emulator and AArch64 Samsung; and the Samsung
passes the fresh ARM bridge probe, ABI-aware legacy catalog, full-device
Mousepad `wl_touch` gate, Qt/GTK descriptor-library rejection/healthy relaunch,
and Android-confirmed byte/signature-stable KCalc replacement. Both connected
targets report 4 KiB pages, so the two 16 KiB lanes remain unavailable rather
than being reported as passes. The emulator's retained legacy manager has no
legacy KCalc/Mousepad wrappers, and the available AArch64 APK is the greenfield
manager without the retired legacy static catalog; neither missing fixture was
silently installed or treated as evidence.

The destructive-default audit has also closed every unguarded standalone
`pm clear` path. Thirteen manager and test-owned bridge gates that require a
clean sandbox now reject ordinary invocation before ADB or artifact access and
require explicit `--clean-data`; older clean-start gates retain their explicit
`--reset-data` or `--reset-app-data` contracts. The generated-camera gate is
the sole non-flagged clear path because it archives and restores the exact
private sandbox and permission state. Terminal project-tree coverage now
preserves app data by default, generates per-run safe aliases and Android
folder names, rejects any pre-existing preference/path collision, and removes
only the fixture it successfully claimed. Its physical Samsung run passed SAF
selection, nested bidirectional synchronization, conflict idempotence, deferred
deletion, symlink rejection, restart persistence, grant removal, and cleanup;
no test alias, private mirror/state directory, or Android folder remained.
Mandatory APK fixtures and the remaining non-`pm clear` mutation classes still
need the same explicit-default audit.

All 46 tests that already had an optional APK-install path are now
non-installing by default. `--install-apk` is the explicit action that permits
`adb install -r`; the old `--skip-install` option remains accepted for command
compatibility, and development examples that intentionally replace a build
now show the action. The default current-manager base gate passes on the
emulator with its existing 288-package shared system and inspected full-device
frame. The default physical Samsung project-tree gate also passes without an
install argument, removes its complete fixture, and leaves the installed
Terminal APK byte-identical at
`35565acb0286b5b0a3577a067192233c40a7c073ea1bfa87fe1dc35821909a9a`.
Tests with mandatory fixture installation, exact APK restoration, or package
publication semantics remain separate audit classes.

The latest six conversions cover Linux-to-Android Open, Share, multi-share,
Export, export recovery, and authenticated app-shell Save. Representative
installed-manager runs pass on the physical Samsung and x86_64 emulator with
no retained document fixture. Open/Share now also wait for an actionable
Android system chooser accessibility tree before taking their full-device
evidence. This exposed prior captures of the still-visible picker or an
unrendered black transition; the replacement Samsung and emulator frames show
the populated platform open/share sheets.

The 13 clean-sandbox manager, package-presentation, onboarding, camera,
secrets, and accessibility gates now separate their two destructive actions:
`--clean-data` authorizes only the named sandbox reset, while `--install-apk`
separately authorizes package replacement. Without the install action they
require the expected package to be present and exercise its current installed
bytes. Syntax/help and the executable mutation-policy gate pass without
contacting either device; the deliberately destructive clean-data lanes were
not rerun merely to validate their argument boundary.

The two older interrupted-session gates are now state-aware as well. They use
the installed manager unless `--install-apk` is explicit, reject an existing
active shared shell before installing permissions or stopping processes,
restore the original notification grant and whether the manager was running,
and remove the test session marker through the normal Stop flow. The real
reboot path additionally rejects before ADB unless `--allow-reboot` is
present. Current process-death and explicitly authorized emulator-reboot runs
both pass; their full-device frames show the bounded interrupted-session
message, shell selector, and actionable Start shell control.

The standalone script mutation rules are now executable rather than review
convention. `test-script-device-mutation-policy.sh` currently covers 19
app-data-reset scripts, 50 optional APK-install scripts, three reboot scripts,
and 31 permission/settings writers; each must fail closed behind its named
action or expose a restoration path. The first run found implicit reboot paths
in startup and package recovery. Startup now uses the installed manager by
default and restores prior running state; its emulator trust-cache rebuild and
reuse pass at 1,846 ms and 427 ms respectively with a clean full-device frame.
Package reboot requires `--allow-reboot`, optionally installs only through
`--install-apk`, restores exact pre-test durable job and recovery-file hashes,
restores manager running state, and leaves the 288-package database and cache
unchanged. Its explicitly authorized emulator run passes with a readable
Failed/Review recovery card and no retained synthetic job.

On July 22, 2026, a current-source x86_64 debug manager was built with the reproducible Podman toolchain, installed on the API 36 emulator, and passed the complete broad emulator regression in one sequence. The run covered package update and refresh, repository search and version selection, Android app-settings routing, authenticated runtime-pack execution and cleanup, KCalc launch/calculation/menu/rotation, native compositor input, Android PackageInstaller update, and Mousepad document, IME, touch, secondary-window, and live-theme behavior.

The connected Samsung SM-S908U (Android 15, AArch64) now runs the exact current-source manager and Terminal under the maintained development signer. Because the original prototype key was unavailable, the explicitly authorized reset was preceded by verified archives of both installed APKs and their private state; 1.60 GiB of manager package/runtime data and 46 MiB of Terminal home/runtime were restored under the new UIDs. Managed Arch Bash, the ARM native catalog, and manager startup/catalog rendering pass. The old Foot, KCalc, and Mousepad wrapper APKs plus persistent Linux homes were separately archived before their deliberate signer migrations.

Foot, KCalc, and Mousepad have now completed that physical migration and are installed by the manager under its maintained wrapper signer. Their restored Linux homes survive repeated same-signer updates. Foot passes its focused visual/runtime workflow; KCalc passes calculation, menus, contrast, live theme, manager appearance overrides, rotation, and descriptor lifecycle; Mousepad passes accessibility, IME, touch, Preferences checkbox/close interaction, Material You checked-state pixels, primary-host cleanup, and document restart/conflict/writeback. The migration also exposed and fixed a destructive ordering bug: Archphene now obtains Android's per-source install consent before uninstalling an older-signed wrapper and asks for final replacement confirmation only after returning from Settings.

The focused Foot workflow now passes on both the current-source x86_64 emulator and AArch64 Samsung wrapper under the manager-owned session/shared-root architecture. A debug-only manager boundary supplies exact UTF-8 preedit/commit while the generated app shell remains free of test intents. The gate uses real wrapper input for bounded pointer selection, the authenticated clipboard worker in both directions, visible scrollback, live display resize with stable manager/wrapper/Linux processes, graceful session close, force-stop cleanup, and clean cold relaunch. It retains and visually checks full-device frames rather than app-only captures. Testing also fixed wrapper drag continuity for synthetic and OEM mouse sources that omit `buttonState` on move events. An earlier run exposed and fixed an auxiliary-command boundary bug: Bash found Android's unverified `clear` through `/system/bin` and leaked the glibc preload into Android's linker. Verified pack commands are now first in PATH and exact-path brokered, while unpublished host commands fail with status 127 on both architectures. Publishing additional dependency commands remains a separate bounded product decision.

The current Foot gate also distinguishes omitted IME operations from explicit
empty strings. Three successive Japanese preedit-only updates now replace the
visible candidate without an accidental empty commit, and the final exact
Japanese/CJK/emoji-modifier/ZWJ value reaches Bash unchanged. The complete
workflow passes on the exact x86_64 and AArch64 APKs. Its PID inspection now
consumes Android's full process table under `pipefail`, avoiding a false SIGPIPE
failure on devices with a busy process list.

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

The current real Code-OSS transaction on x86_64 completes through the generic
official-package path. Pacman resolves, downloads, detached-signature verifies,
and installs its current 198-package, 428 MiB closure; desktop discovery then
publishes the Code-OSS Android app shell. Desktop reconciliation now retains
entries only when their owning package was explicitly installed, preventing
Code's dependency closure from publishing unrelated Avahi browsers. This run
closed four general package-engine gaps: dependency resolution now has a
separate bounded, Service-reused 256 KiB direct response rather than exceeding
the general 16 KiB command channel; GnuPG machine status is parsed as raw bytes so a valid
signature is not rejected because a human diagnostic contains non-UTF-8;
successful large transaction output is bounded but discarded without a large
heap copy; and legitimate zero-byte pacman `files` records no longer make an
otherwise complete desktop scan appear truncated. A genuinely truncated scan
still pauses launcher reconciliation rather than mutating the registry from
incomplete evidence.

The first AUR trust boundary is now implemented without executing community
recipes. Rust borrows bounded AUR v5 JSON fields, then parses one at-most-4 MiB
cgit snapshot with at most 128 entries and 8 MiB expanded content. It requires
exact request, package-base, version, architecture, and snapshot-path
agreement; rejects traversal, links, special files, duplicate paths, missing
recipe files, and malformed source/checksum pairs; verifies every local source
present in the snapshot; records the cgit PAX commit plus the exact snapshot
SHA-256; and reports dependencies, install-script presence, insecure or
unverified sources, and visible PKGBUILD functions. The current live
`visual-studio-code-bin` `1.130.0-1` snapshot passes for AArch64 with 13 runtime
dependencies, two selected sources, exact SHA-256 values, no insecure
transport, and no unverified source. The Android manager now fetches the exact
RPC endpoint and only the Rust-approved snapshot path, transports the bounded
review through a versioned binary JNI wire, and renders the community trust
warning, shared trust domain, maintainer, cgit commit, snapshot SHA-256,
AArch64 sources and checksums, dependencies, build functions, install script,
and exact PKGBUILD. A live full-device Samsung gate reviewed the current
`visual-studio-code-bin` candidate while keeping official Install disabled and
preserving all 36 local pacman-database entries. At that review stage, installed
disk impact, an unprivileged build, and AUR result installation remained
pending.

The review boundary also supports real AUR split packages. RPC identity is
validated against the requested split name, while the retained snapshot uses
the canonical package base expected by the isolated Builder. The parser
recognizes exact hyphenated split functions as well as normalized legacy names,
preserves ordered duplicate checksum entries, and selects SHA-512 ahead of
SHA-256 when both are declared. Review wire v3 identifies the checksum
algorithm; Rust uses algorithm-qualified cache names and rehashes each source,
while Kotlin derives a separate SHA-256 input digest for the Builder manifest.
On the physical Samsung, current `dotnet-sdk-bin` `10.0.10.sdk302-1` reviewed
from package base `dotnet-core-bin`, displayed both exact SHA-512 declarations,
and verified the 225 MiB ARM64 SDK source. The completed split-package model
now resolves package-base siblings and sibling providers recursively, excludes
them from the official build closure, and carries the exact required-output set
through the versioned review boundary.

The next AUR boundary now resolves each architecture-selected declaration as a
snapshot-local source, supported direct-HTTPS source, or unsupported transport.
Supported remote sources require a bounded safe filename and SHA-256 or
SHA-512. Android
follows at most five HTTPS-only redirects and streams into a Rust-owned private
cache descriptor; Rust re-reads the complete file, hashes it, and atomically
promotes only matching bytes. Verified cache entries are rehashed before reuse,
while a tampered entry is discarded. On the physical Samsung, the current Code
AArch64 source downloaded as 220,653,390 bytes (211 MiB), matched
`4b67f4e83154dfb281ed5e8ed7be03d9ce3c489bb00c8653c5207d61744d864b`,
then passed a cache-reuse run and an independent device `sha256sum`. Both runs
kept official Install disabled and preserved all 36 pacman database entries.
The visually inspected evidence is a full-device screenshot. Installed/build
disk impact, explicit approval, isolated execution, output provenance, and
installation remain pending.

The physical Samsung also establishes the build-process boundary selected for
the next slice. Its stock kernel rejects unprivileged user namespaces and
mount/network namespace creation. A live Android isolated-process probe had no
network and could not read manager-private data, but could not create a normal
workspace through a granted directory descriptor. Archphene therefore now
includes one hidden same-signer Builder companion APK with no
launcher Activity or `INTERNET` permission. The current device gate verifies
manager UID 10430 and builder UID 10345 are distinct, both APK signatures
match, the builder runs as SELinux `untrusted_app`, owns its private workspace,
cannot read the manager sentinel, writes through a manager-opened descriptor,
and cannot be read directly by the manager. The full 211 MiB Code source gate
then passes through the manager while all 36 pacman database entries remain
unchanged. This proves the Android boundary only; provisioning the verified
build root, executing an approved recipe, verifying output provenance, showing
final disk impact, and installation remain pending.

The next Samsung slice now stages exact reviewed inputs across that boundary
without executing them. Rust atomically retains the 2,237-byte cgit snapshot
and rehashes it plus the cached 220,653,390-byte Code source before returning
read-only descriptors. The Builder requires bounded regular inputs,
independently hashes and atomically publishes 220,655,627 total bytes, and
writes canonical manifest
`9ed97af5bf70bc6a007cc34be4cb4a41a02881c8f336ff90b818b658f109fef0`.
The live gate independently verifies the Builder-side Code digest, manifest,
distinct UID/no-network/no-launcher properties, unchanged 36-entry pacman
database, disabled Install action, resumed Activity, scoped fatal log, and
full-device screenshot. Because no community recipe can execute yet, the
Builder workspace has not become hostile. Before execution is enabled, its
reuse path must move to no-follow directory-FD Rust operations and supervise
all same-UID descendants.

The initial AUR build-environment slice resolved one bounded pacman plan:
`base-devel` plus all reviewed `makedepends` and `checkdepends`, with validated
version constraints removed only for repository lookup. The initial
130-package result was correctly rejected as incomplete:
it used the manager's installed-package database and omitted dependencies an
empty Builder root still needs. Resolution now copies current sync catalogs
into an ephemeral manager-owned database with no local package state. The live
Samsung AArch64 candidate is therefore 152 official packages and 224,514,136
archive bytes.

The manager has downloaded the exact archives and detached signatures into its
bounded cache. Rust requires the pinned AArch64 signer and exact package
name/version/architecture, retains the original resolution, and independently
reverifies the whole closure before success. A Samsung cache-reuse pass
reverified all 152 packages, removed the ephemeral database, visibly rendered
the verified 215 MiB closure, kept Install disabled, and left all 36
shared-root pacman entries unchanged. Rust now also emits a bounded canonical
manifest with every exact resolution identity, URL, archive size/digest, and
detached-signature size/digest. Kotlin validates all 152 entries against the
retained resolution and exposes the whole-manifest SHA-256; Rust will reopen a
package descriptor only while that closure is retained as verified. Measuring
extracted/build space and provisioning the minimal root remain pending.

The verified closure now crosses into the separate Builder UID in bounded
eight-package Binder batches. A new Rust Builder library resets its private
closure directory through no-follow directory descriptors, accepts only the
manifest-bound archive and signature at each index, streams through a fixed
64 KiB buffer, and atomically publishes the manifest only after all retained
files pass a final rehash. Host tests prove a hostile symlink cannot escape the
workspace. The physical Samsung gate independently observes all 152
archive/signature pairs, 224,514,136 archive bytes plus 86,032 signature bytes,
and the same
`a0b7315c89d9f3915f2e8b313b6a09cae77c17c72cc212f3f1fcdeadfb0ab39d`
manifest digest while the shared pacman database remains at 36 entries. The
epoch-qualified filenames found in the real closure also corrected the
Builder validator to accept pacman's already-verified `:` filename syntax.

The isolated build root now passes its physical-device gate. Builder Rust scans
the entire published XZ/Zstandard closure before mutation, resets stale or
partial roots through no-follow directory-FD traversal, rehashes every archive
again before extraction, and rejects traversal, unsupported entries, and
symlink-parent escapes. Samsung app storage denies hard links even for the
owning UID, so archive hard links are materialized as bounded regular copies
and counted in the storage plan. After syncing the filesystem, the Builder
publishes a closure-bound root manifest. A deliberately failed `tzdata`
extraction exposed this Android behavior; the recovery run then provisioned all
152 packages, 48,271 archive entries, and 1,221,416,416 bytes with executable
`bash`, `makepkg`, and `fakeroot`, while the shared pacman database stayed at
36 entries. The gate captures the full Samsung display. The retained completed
transaction card still consumed too much of the phone review and became a
tracked UX defect.

The verified Builder execution bridge now reaches that root without packaging
the manager's complete runtime a second time. A generated dual-ABI manifest
binds the patched loader, its required libraries, and the path bridge by
digest-bearing filename, full SHA-256, and exact size. Builder Rust checks
those fields, safe file modes, and native-library path containment, resets its
root-local aliases with no-follow operations, and invokes the unmodified root
tool through the existing bounded process environment. Eleven host Builder
tests cover valid preparation and runtime tampering. The cold physical Samsung
gate then executes `makepkg (pacman) 7.1.0` as Builder UID 10345, validates the
48,271-entry/1,221,416,416-byte root, and leaves all 36 shared-root packages
unchanged.

Root preparation no longer hides a whole-closure scan inside one blocking
Binder request. Builder Rust retains a bounded scan cursor, reverifies and
inspects at most eight archives per call, reports cumulative packages, entries,
and expanded bytes, and requires a separate root-reset transition before
extraction. The manager checks cancellation between every batch, aborts the
Builder session on interruption, and keeps one visible AUR activity card with
the exact phase and a **Cancel AUR** action through source verification,
closure staging, scan, extraction, and build. A physical Samsung run rendered
the current 250-package scan through 66,877 entries/1,744,486,351 expanded
bytes; a second full-device run cancelled at 200/250 without publishing a new
Builder report. Output handling now separately reports Builder verification
and descriptor copy, independent manager verification, and final completion
for each split output.

Reviewed-input reuse is no longer a Kotlin filesystem boundary. A Rust-owned
session now removes both substituted v2 state and the legacy
`aur-build-workspace` through no-follow directory descriptors, stages each
bounded regular descriptor with fixed-buffer SHA-256 verification, and
reverifies all bytes before atomically publishing the canonical manifest.
Thirteen Builder host tests cover hostile symlink recovery, legacy cleanup, and
post-stage tampering. The Samsung upgrade gate removes the former 211 MiB
Kotlin-owned source tree, republishes 220,655,627 exact reviewed bytes under
`aur-build-workspace-v2`, reuses and reverifies all 152 signed closure members,
executes the toolchain probe, and preserves the 36-entry shared pacman state.

Builder execution now has physical evidence as well. Rust resets and prepares
the exact reviewed recipe through no-follow directory descriptors, repeats
same-UID cleanup, retains one bounded makepkg process group, exposes capped
logs, supports cancellation, enforces a 30-minute timeout, and reaps on exit.
The path bridge gained the stock Arch behaviors exposed by the real Code build:
version-correct ARM stat symbols, directory-FD traversal, temporary-file
rename, fakeroot message queues and identity, optional Landlock fallback,
hard-link copying, fortified fake-root `realpath`, and the standard `alpm`
account. The final Samsung run builds
`visual-studio-code-bin-1.130.0-1-aarch64.pkg.tar.xz`; its `.PKGINFO` reports
1,079,048,720 installed bytes and its `.BUILDINFO` exactly records all 152
signed build packages. At that point, product-side hostile-output enumeration,
descriptor copying, and manager re-verification remained the next trust
boundary.

The stale-process half of Builder supervision now has physical evidence.
Before Rust opens reusable Builder state on Android, it scans at most 4,096
same-UID `/proc` candidates, records and rechecks each kernel start time before
signaling, kills every process other than the current Builder service, and
fails closed if any remains runnable after bounded retries. The Samsung gate
creates an orphaned `sleep` as Builder UID 10345 before service startup and
proves it is terminated while manager UID 10430 completes the entire review
gate.

That trust boundary and generic AArch64 install are now complete. Runtime
`depends` join `base-devel`, `makedepends`, and `checkdepends`, yielding a
250-package, 321,419,288-byte current Code closure. The Builder provisions
66,878 verified entries totaling 1,744,478,772 bytes, reruns the reviewed
recipe, then Rust safely enumerates hostile output, validates exact
`.PKGINFO`/`.BUILDINFO` provenance, and copies only the selected archive through
a manager-owned descriptor. The manager independently reverifies the copy and
retained closure, installs the signed official runtime dependencies, retains
the AUR archive content-addressed, and commits it through pacman with exact
plan/version postconditions and durable mutation recovery. The physical
Samsung gate installed `visual-studio-code-bin-1.130.0-1`, retained archive
SHA-256
`51e44c87e8ffbe9b7f3c441bfad6ab8e2fdff1d9f0402d0fa27b94d9a11d3c5c`,
and found `/usr/bin/code` plus `code.desktop` in the shared root. It also proves
Builder UID 10345 remains distinct from manager UID 10430, all 250
archive/signature pairs are reverified, the final pacman database has 194
entries, and the compact full-device UI no longer exposes raw makepkg output or
an unrelated terminal activity card. The exact rebuilt manager also removed six
unrecoverable stale output copies on startup, reducing its transient cache from
1.2 GiB to 3.5 KiB, then installed the generated Visual Studio Code Android app
shell through Android's confirmation UI. After another manager restart,
bounded no-follow pacman-local inspection identifies its `none` validation
origin, renders an honest disabled Installed action, and enables conservative
Remove instead of failing official-repository resolution. Cancelled
PackageInstaller confirmations now persist as a distinct terminal launcher
state instead of being reconciled back to publication after manager restart.

Verified AUR build capabilities now survive manager process death without
trusting stale Kotlin state. Rust atomically publishes a bounded manifest and
mode-0600 content-addressed output files only after independently verifying the
Builder copy. Reattachment requires the exact reviewed package identity,
commit/source evidence, signed official closure, ordered output identities,
sizes, and SHA-256 values, then hashes every file and runs the full
Builder-output verifier again. A physical Samsung built the current 212 MiB
Code archive, killed and replaced the manager, re-established the exact
250-package closure, and restored the ready-to-install capability without
running Build again. The full-device result shows disabled **Built**, enabled
**Install**, and “restored verified build · ready to install.”

Reviewed AUR lifecycle scripts now cross the same exact-evidence boundary.
Rust retains the effective `.install` path and bytes for every required split
output, review wire v5 renders all of them, and independent Builder-output
verification rejects missing, extra, or changed `.INSTALL` content. Before
pacman mutation, a mode-0600 manager capability outside the shared Arch root
binds each package/version/archive/script digest. Install and upgrade may run
only those reviewed AUR scripts; removal rehashes the installed pacman-local
script and fails closed without the exact capability. Controlled fixtures run
all install, upgrade, and removal phases through the production fake-root
bridge. On Samsung, current Code restored its 212 MiB output after manager
death, installed with exact script SHA-256
`c910a24270895767939b23194673d76641432aa107e6e81ca8ad6f7a8fc6e9b7`,
survived another manager death, removed normally, and pruned the capability.
Full-device screenshots and the installed/removed UI states were inspected.
Official forward scriptlet execution and the generic libalpm hook boundary are
now covered. A signed script-bearing reverse rollback gate and whole-operation
AUR recovery remain open.

Recursive AUR recovery now advances at package-base boundaries rather than only
after the whole graph completes. After each independently verified base, Rust
atomically replaces the mode-0600 graph capability with an exact complete
dependency-first prefix. Restart rehashes and revalidates every retained
archive, reviewed base/version/output identity, build provenance, lifecycle
script, graph digest, ABI, and signed-closure digest before Kotlin prepares the
first unfinished base with only its verified ancestors. Partial-base output is
rejected, and the final native install path separately requires every reviewed
base. The Rust workspace, strict Clippy, Android JVM tests/lint, and both
release JNI ABIs pass.

The exact-device prefix-recovery gate now passes on the physical AArch64
Samsung. The Manager was force-stopped immediately after it atomically retained
current `libpamac-aur 11.7.4-2` as base 1/2 for `pamac-aur 11.7.5-1`. Restart
reverified the current 356-package, 448,395,520-byte signed closure and restored
that exact prefix without rebuilding it; only base 2/2 ran before the final
transaction. The gate exposed two generic recovery defects. A valid adaptive
Ninja wrapper change from `-j2` to `-j4` was incorrectly treated as tampering,
so only exact Archphene-generated wrappers may now be atomically updated while
arbitrary content still fails closed. Final AUR preflight also attempted to
resolve the verified archives before their reviewed official runtime
dependencies existed. It now derives bounded libalpm
`--assume-installed package=version` entries from the exact verified archive
metadata for the two non-mutating plan passes only. Commit still installs the
signed official dependencies, repeats an assumption-free plan, rechecks
ownership/removals, and performs one final two-archive transaction. The device
finished with 312 packages, exact `libpamac-aur`/`pamac-aur` records, four
reconciled Android app shells, and no lock, mutation journal, retained graph
capability, or fatal log.

The AUR pre-install decision surface is now explicitly gated as a complete
contract rather than by one token per section. Six mutually compact,
expandable, selectable sections expose source origins and licenses; maintainer,
community/shared-environment trust, and Android permission status; verified
source, closure, and isolated-root disk use; snapshot/source/closure/Builder
and final-package digests; runtime/build/check dependencies, visible recipe
functions, install script, and exact PKGBUILD; and bounded build logs. After
independent output verification the action row shows disabled **Built** and an
enabled **Install**, while Build environment shows exact archive and installed
sizes before mutation. The no-network ready-to-install fixture passes on the
exact x86_64 emulator and AArch64 Samsung with visually inspected full-device
light/dark frames and unchanged 152/151-package databases.

The manager shows explicit Retry/Dismiss choices, persists dismissal, and only
Retry submits another Android session. Exact-ABI emulator and Samsung gates
remove one generated wrapper, cancel the Android confirmation, force-stop and
restart the manager without a resubmission, then Retry and restore the wrapper
through one successful confirmation. Reviewed batch selection and later
app-shell management are now implemented as well. When one reconcile introduces
two or more desktop entries, Rust holds the complete set in `NeedsReview`; no
wrapper can be claimed until one bounded, generation-checked batch records
every choice atomically. The manager presents one default-selected checklist
with Add selected, Skip all, and Not now. Unselected entries become durable
Dismissed app shells, and the package summary opens them later for
re-enablement. Emulator and Samsung gates create two package-owned desktop
entries, inspect full-device review/manage views, exercise skip,
partial/re-enable paths through real generated wrappers, and restore the exact
original package sets. App-shell recovery now also covers manager state loss:
a trusted installed wrapper above a reset registry's desired generation is
republished at a still-higher Android version rather than downgraded. An
installed wrapper with an untrusted signer remains a visible retryable failure;
explicit Retry persists removal across catalog refresh, opens Android's
uninstall confirmation, waits for confirmed absence, then opens the trusted
replacement install confirmation. A real stale emulator Foot identity
completed that full-device flow and returned to Current. Recursive
cross-package-base AUR recovery and signed script-bearing reverse rollback
coverage remain open.

Split-package build and installation now close the boundary exposed by .NET.
The Builder returns every required archive instead of only the selected output;
the manager independently reopens and verifies each archive, stages the set
content-addressed, and gives pacman one atomic local-package transaction.
Samsung built and installed all six current `dotnet-core-bin` outputs with
`dotnet-sdk-bin` explicit and its five siblings recorded as dependencies.
The selected SDK archive SHA-256 is
`38b29e8c763c33bd649c3f05fbfb6b08275b1911efafea396455b045755abcb7`;
the host archive SHA-256 is
`4d7a6efe0637a261842fc2c792914ccad314ecc48ac0c505b3ac127c259dc79b`.
Same-version installation is a real verification/reinstallation transaction,
not a silent `--needed` no-op.

Recursive AUR dependency graphs now cross the complete physical build and
install path. Rust remains authoritative for the bounded dependency-first
order and exact versioned edges; each package base receives a separately reset
Builder recipe, verified graph-dependency manifest, independently checked
outputs, and the same signed official closure. The manager retains all graph
outputs until one final pacman transaction, where pacman's own validated
provider resolution may satisfy capabilities such as `libalpm.so=16` without
weakening exact user-package resolution. On the physical AArch64 Samsung,
`libpamac-aur 11.7.4-2` and `pamac-aur 11.7.5-1` built as two isolated bases
against the exact 339-package, 443,742,032-byte official closure, installed
together, persisted exact pacman local records across Manager replacement and
restart, and published the Add/Remove Software Android app shell.

That run also closed two generic bridge regressions. Official package
post-install scriptlets required by the disposable Builder root now execute
once against the exact closure-bound root before an AUR recipe; capability
xattrs are virtualized inside that fake root because Android app UIDs cannot
own or use Linux file capabilities. Nested GPGME helpers now canonicalize only
manager-authorized sealed command symlinks to their immutable APK targets
before explicit-loader execution, avoiding a symlink loop while leaving
package executables under normal root containment checks. A signed `libnotify`
pacman preflight passes through the packaged bridge after restart. Durable
graph restoration now stores one mode-0600 capability bound to the canonical
Rust graph, every exact review, the signed closure, and every
content-addressed output. On Samsung, the same two-base graph retained a
1,318-byte capability, survived Manager replacement/restart, independently
revalidated both archives and their dependency provenance after the reviews
and 339-package closure were reconstructed, and returned directly to
**Built**/**Update** without running either recipe again. The physical gate
also found a stale 256-package persisted-provenance bound; it now matches the
bounded 512-official-plus-256-AUR Builder model and has a 339/340-package
regression.

Persisted AUR outputs are now checked before the Manager binds or provisions
the Builder. Restoration uses the newly reverified signed-closure digest
directly; a capability miss still follows the unchanged isolated build path,
while a hit remains installable without transient Builder state. A physical
Samsung replay reverified all 339 official packages, independently restored
the current `libpamac-aur` → `pamac-aur` graph without a Builder service
binding or any closure staging/root scan/provisioning, and then completed a
real same-version `pamac-aur 11.7.5-1` transaction. The compact action now
advances through **AUR**, **Prepare**, **Sources**, **Build**, and **Built**
instead of repeating **Verify**; full-device evidence covers dependency
review, exact base/package verification counters, restored **Built** /
**Update**, and completed installation.

Builder concurrency is no longer a fixed two-worker policy. One bounded pure
decision uses available processors, free memory, Android's low-memory signal,
and current thermal severity to select one through four workers once per
reviewed isolated root. The selected value is retained in the Builder report,
bounded again across Binder/JNI, and applied consistently to Make, Cargo,
CMake, and a verified Ninja wrapper. The physical Samsung selected
**Auto · 4**, exposed that value in the full-device build evidence, published
an exact `ninja -j4` wrapper, started current `libpamac-aur` through the new
protocol, and cancelled/reaped the build normally.

Isolated Builder stale-process cleanup now bounds the complete `/proc` scan,
not only same-UID matches. Each cleanup round visits at most 4,096 entries,
including nonnumeric and foreign-UID entries, retains at most 1,024 same-UID
process identities, and fails with `OutputLimit` before signaling on either
overflow. Host tests prove both exact admission boundaries and that target
1,025 is not appended. The pinned Builder crate passes 24 tests together with
warning-denied all-target Clippy and formatting. Exact x86_64/AArch64 manager
and Builder APK builds pass; the rebuilt AArch64 pair was installed on the
physical Samsung before a stable Foot run with exact command output, clean
scoped logs, and an inspected 1080×2202 frame. Hashed full-device evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/builder-proc-scan-bound-foot-20260804`.

Builder package-archive metadata extraction now allocates exactly each tar
header's admitted `.PKGINFO`, `.BUILDINFO`, or `.INSTALL` size, fills that
buffer with `read_exact`, and probes one trailing byte. The former
`Vec::with_capacity` plus `read_to_end` paths could grow beyond the declared
entry size before rejecting malformed input, and the package-runtime metadata
reader duplicated that behavior into caller buffers. The shared reader rejects
empty, oversized, truncated, or grown entries without retaining partial state.
Direct tests cover exact input and both size changes; all 25 Builder tests, the
locked Rust workspace test suite, warning-denied Builder Clippy without
dependency linting, the complete Android source unit/lint gate, and both
exact-ABI manager/Builder builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/builder-archive-metadata-exact-read-foot-20260804`.

Builder stale-process identity reads now consume Android `/proc/*/status` and
`stat` through one initialized 16 KiB vector rather than growing a 4 KiB
`Vec::with_capacity` through `read_to_end`. The shared bounded stream reader
retries interrupted reads, truncates the one allocation to the observed logical
length, accepts an exact-limit file only at EOF, and rejects the first overflow
byte. Direct tests cover short, exact, overflowing, and invalid zero limits; all
26 Builder tests, warning-denied Builder Clippy without dependency linting, the
locked Rust workspace suite, the complete Android source unit/lint gate, and
both exact-ABI manager/Builder builds pass. The physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/builder-proc-fixed-read-foot-20260804`.

Prepared Builder roots deliberately are not reused between untrusted recipes.
Every recipe currently executes as the same Builder application UID, and a
physical Samsung probe confirmed that this identity can mutate the prepared
root's `/usr`. Owner read-only modes and hardlinks are therefore not an
isolation boundary, while Android denies the required user and mount
namespaces on the production device. Archphene continues to reuse
manager-owned signed archives and independently verified outputs, but
reprovisions each recipe root until a distinct UID, kernel-enforced immutable
lower layer, or equivalent mount boundary is available.

Current AArch64 Code now reaches and retains its Ozone/Wayland workbench without
a package patch. The generic bridge passes Chromium's kernel `/proc`
directory-FD probes, returns `ENOSYS` for raw `statx` so libuv selects its
translated fallback, maps conventional and imported-syscall shared-memory
access, publishes a physical app-private `TMPDIR` for inline syscalls, and
translates absolute `dlopen`/`dlmopen` paths for native Node, Qt, GTK, and
language-runtime modules. Logical `/proc/self/exe` readlink and exec support
lets Chromium re-enter the actual Electron binary rather than the Android
loader.

Chromium also deliberately removes `LD_PRELOAD` from utility-process
environments. A bounded no-heap re-exec path now reconstructs only the
initially verified bridge, root, loader, library, command-directory, identity,
and process-group contract while preserving ordinary caller environment
entries. A clean-environment host regression proves the bridge remains active
and can read a logical root path. On the Samsung, full-device evidence and the
live process tree show Code's main, network, shared-process, file-watcher, and
extension-host services remaining alive instead of aborting with the prior
deliberate `SIGTRAP`.

The next physical-device pass closes the immediate Code resize/input and PTY
gaps without a Code patch. Activity-window touch and pointer coordinates are
translated into the inset app-shell Surface before Wayland mapping. Phone
auto-density preserves a 432-logical-pixel short edge, so Code commits an exact
1080-wide Samsung raster rather than a 1134-pixel buffer that requires lossy
downscaling. Full-device captures with the IME and at full height show crisp
text and exact visible hamburger-menu activation. Generic `scandir`,
`scandir64`, and `inotify_add_watch` translation keeps logical
`/home/archphene` scans and watchers alive.

The generic bridge now implements `openpty` and `forkpty` over
`TIOCGPTPEER`, creates a controlling session for the PTY child, and allows
normal terminal process-group changes while retaining the supervised GUI
policy elsewhere. Stock Code opens a warning-free integrated Bash with working
job control on Samsung. Force-stopping its Android app shell removes both Code
and the terminal shell while the manager remains alive.

This is still a partial Electron claim. The temporary device tests established
that Code requires `--no-sandbox` and `--disable-dev-shm-usage`; the manager now
has a reviewed generic replacement for user `code-flags.conf`. It is
default-off, requires an explicit reduced-isolation warning, is captured once
per app-shell session, and appends the two flags only when the exact desktop
entry or retained supervised-process observation contains Chromium topology.
Unit tests prove that consent or topology alone is insufficient and that
existing flags are not duplicated. Exact-ABI emulator/Samsung gates prove
Cancel, explicit Enable, process-restart persistence, state restoration, and
visually inspected full-device light/dark presentation. Current Code
installations on both roots also pass the real supervised-process gate without
a user `code-flags.conf`; the generic policy supplies both required flags while
a current Foot app shell remains unaffected.

The diagnostic `--disable-gpu` flag was previously removed on both device
lanes: full-device rendering and normal close remained stable, and Samsung
created a Chromium GPU process. That process still reported
`--use-gl=disabled`, so this is not an accelerated-rendering claim. A later
verbose audit found the concrete first boundary: Electron 42 sees Samsung's
`/dev/dri/renderD128`, Android SELinux denies the app domain access, Chromium
fails render-node/GBM initialization, and the GPU process crashes three times
before software reinitialization. The emulator has no `/dev/dri`. Temporary
direct-EGL, ANGLE, blocklist, and single-process diagnostics were removed.
Accelerated rendering now explicitly requires both a safe render-node/GBM
strategy and compositor presentation/readback; the C# debugger and sustained
lifecycle also remain open.

Current unmodified Visual Studio Code on the physical AArch64 Samsung and
Code - OSS on the x86_64 emulator follow live explicit Light→Dark→Light changes
through Archphene's standard appearance portal when Code's standard automatic
color-scheme preference is enabled. Both state-preserving runs retain the same
manager, wrapper, and Electron leader; restore the exact Code configuration and
Archphene Auto preference; reject scoped fatal logs; and retain inspected
full-device light/dark/returned-light frames. Samsung measures 249.9 to 29.7
mean luma across 99.9% of sampled pixels. The emulator independently measures
254.2 to 30.6 and back to 254.2 across the complete sampled surface. Current
x86_64 evidence is under
`tooling/artifacts/visual-audit/emulator-5554/code-appearance-current`.

Current x86_64 Code-OSS now also reaches its full Ozone/Wayland workbench with
the shared process, Node workers, extension host, file watcher, and integrated
PTY alive. The generated app shell starts with Android's IME hidden until an
intentional touch requests it, avoiding a stale keyboard resize during cold
startup. Closing the Android session first sends `xdg_toplevel.close`; Code
flushes its application, shared, and workspace storage before a bounded
SIGTERM/SIGKILL fallback. Reopening a stopped single-task app shell now clears
the old Surface attachment and immediately binds the new authenticated session.
Full-device emulator captures prove cold launch and close/relaunch, and the
current Samsung manager and all four desired wrappers were reconciled before a
clean full-device Code cold launch, Back cleanup, and relaunch.

The first real extension-marketplace UI gate failed before HTTP. A bounded
temporary Chromium netlog showed Open VSX host resolution returning
`getaddrinfo` `EAI_AGAIN` and `ERR_NAME_NOT_RESOLVED`; no TLS request was made.
The manager now publishes up to four validated, canonical IPv4/IPv6 addresses
from Android's active `LinkProperties` through bounded direct JNI into an
atomic, mode-0600, no-follow resolver file owned by the Rust Arch root. Scoped
IPv6 interface identifiers are preserved, unchanged configurations avoid
flash churn, default-network callbacks refresh the file, and a missing active
network retains the last usable configuration instead of installing a public
resolver. Host tests cover malformed input, canonicalization, replacement,
mode repair, unchanged inode retention, and hostile destination/staging links.

Android DNS selection now bounds every examined `LinkProperties` candidate,
not only the retained unique addresses. One refresh examines at most 32
candidates while selecting at most four distinct nonempty servers. Candidate
33 before four usable results fails closed and retains the prior resolver;
finding four unique servers early stops immediately even if the framework list
is much larger. Four direct JVM tests cover exact 32-candidate admission,
limit-plus-one rejection before reading the extra value, early completion,
null/empty entries, and duplicates. App unit tests and lint plus both exact-ABI
builds pass. The rebuilt physical-Samsung manager published its one normal
Android DNS server without a fatal event. Runtime evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/android-dns-candidate-bound-20260804`;
the subsequent clean Foot full-device regression is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/android-dns-candidate-bound-foot-20260804`.

Managed `resolv.conf` equality checks now stream through one fixed 128-byte
stack buffer instead of allocating and growing a complete file-sized `Vec`
before comparison. Metadata still requires the exact expected size and mode;
the streamed check independently rejects the first differing chunk, a short
read, or one trailing byte. Direct Rust tests cover a multi-chunk exact match,
middle mismatch, truncation, and growth. All 12 root tests, the locked Rust
workspace test suite, warning-denied root Clippy without dependency linting, the
complete Android source unit/lint gate, and both exact-ABI manager/Builder builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/resolver-stream-compare-foot-20260804`.

A direct glibc runtime probe exposed the remaining boundary: resolver loading
uses an internal libc `fopen` that cannot be interposed by the preload bridge.
The old packaged libc therefore read Android's absent `/etc/resolv.conf`, fell
back to `127.0.0.1`, and returned `EAI_AGAIN` despite the correct private-root
file. A narrowly scoped glibc patch now selects only
`$ARCHPHENE_RUNTIME_ROOT/etc/resolv.conf` for the Android compatibility build,
including libc's normal file-change detection. Both checksum-pinned x86_64 and
AArch64 runtimes were rebuilt from the verified glibc source.

A permanent exact-APK gate now runs through the real shared terminal on the
API 36 x86_64 emulator and physical AArch64 Samsung. One process resolves
Open VSX, atomically switches to an unavailable documentation-prefix resolver
and observes failure, restores Android's resolver and succeeds again. It then
uses the installed Arch `curl`, generated standard Arch CA bundle, and real TLS
to validate a bounded Open VSX API result. The gate verifies mode 0600 resolver
state, the exact checksum-named patched libc mapped by the live child, scoped
fatal logs, cleanup, and full-device screenshots. AArch64 additionally exposed
and fixed versioned `dlopen@GLIBC_2.34` interposition needed by p11-kit; generic
`statx` fallback, safe parent-path/symlink handling, and large-file temporary
entry points now have host regressions.

The real Code extension workflow now passes on both targets. Code - OSS searches
Open VSX and installs Red Hat YAML on x86_64; Visual Studio Code performs the
equivalent search and install through Microsoft's gallery on AArch64. Both
workbenches retain the extension, open a real YAML document, and record
`redhat.vscode-yaml` activation on `onLanguage:yaml` followed by successful
document-symbol, link, folding, code-action, and diagnostic providers.

That workflow exposed a separate generic GTK regression. Arch's current
GdkPixbuf delegates SVG loading to Glycin, whose helper starts through
Bubblewrap; Android's application sandbox cannot create Bubblewrap's nested
namespace, so Code's native GTK file chooser aborted while loading an Adwaita
SVG icon. The shared package runtime now publishes the repository's
checksum-pinned no-Glycin GdkPixbuf and librsvg compatibility libraries for the
exact ABI, creates a bounded mode-0600 loader cache that names only the verified
SVG module, and exports it to every Linux child. Full-device emulator and
Samsung captures show the unmodified native chooser with rendered SVG icons,
and both Code processes remain alive through selection.

The current Rust/Kotlin launch path now also stages the checksum-pinned GTK
settings and Qt platform-theme/style modules, publishes bounded GTK 3/4 and KDE
appearance files atomically, and carries Android light/dark, Material color,
text, visible-control, and touch-target values through a versioned launcher
request. It does not reintroduce global Qt or GTK geometry scaling. On Samsung,
the GTK module records the expected Android-light `Adwaita` and 12-point font
policy. Qt's production platform-theme plugin now consumes atomic `kdeglobals`
replacement through a deferred exact-file `QFileSystemWatcher`, with exact x86_64 and
AArch64 rebuilds and a deterministic same-process palette regression. A
separate Wayland defect explained both the chooser's tiny scale and
its displaced touches: only a focused surface had received `wl_surface.enter`.
Every mapped surface now receives output membership and preferred buffer scale;
the stock chooser commits scale 3, its visible Open action works, and Code opens
the shared `ArchpheneMvp` tree.

The private portal now replaces stock GTK 3 single- and multiple-file Open,
Save As, and folder-selection dialogs with Android DocumentsUI. Save As mirrors
stable writes through a bounded private staging file into the chosen Android
descriptor. Open imports at most 32 documents and 512 MiB per batch into
durable, collision-safe `~/Documents/Android/<display name>` copies. Folder
selection streams a bounded SAF tree directly into Rust-owned,
descriptor-relative staging and atomically publishes a non-replacing
`~/Projects/<folder>` snapshot. The versioned Binder path rejects
duplicate Android URIs and malformed result shapes, owns every descriptor
exactly, reserves non-replacing destinations, and removes the complete batch on
copy failure. The GTK bridge preserves every selected file through the
toolkit's plural getters; Android URIs and grants never cross into Linux, and
editing an imported copy never updates the original provider document. Save As
is the only outbound desktop-portal operation and always uses a newly selected
Android destination. Reopening imports another collision-numbered snapshot.
The single-file gate now edits the first Linux snapshot and proves the Android
source remains byte-exact before importing the unchanged source again.
Cancellation, repeated single-file collision behavior, exact bytes,
application-visible dual-file results, exact nested/dot/empty folder content,
folder collision numbering, full-device captures, scoped fatal logs, and the
current x86_64/AArch64 wrapper ABIs pass on the emulator and Samsung. The folder
contract was exercised from the portal probe inside an unmodified generated
Mousepad app shell; a stock GTK folder-selecting application, Qt 6 callers, and
GTK 4 multiple-file/Save/folder callers remain.

Electron Open and Save As now pass through the same portal with current Code app
shells on both exact ABIs. Binder protocol v16 retains v15's provider-final
validated Android display name with the Save descriptor. The manager places each
save in a unique private nested slot while exposing that exact basename to the
desktop caller, so Code no longer shows an opaque session-prefixed filename.
Recovery accepts both the nested layout and legacy flat staging without
following links. The state-preserving gate proves one-file and two-file Open,
all three exact Linux imports, exact-name and byte-exact Linux Save staging,
the byte-exact Android destination, process survival, configuration/lifecycle
restoration, and inspected full-device results on the emulator and Samsung.

Current unmodified Seahorse now supplies the first GTK 4/libadwaita caller
proof. Its real **Import from file** action opens Android DocumentsUI with the
toolkit's MIME filter and receives one exact-name, byte-exact snapshot under
`~/Documents/Android`. The repeatable gate selects a MIME-eligible but
intentionally invalid `.key`, so Seahorse visibly reviews and rejects the data
without importing a secret. It requires unchanged keyring/dconf inventories,
healthy manager/wrapper/Linux processes, no fatal logs, exact manager lifecycle
restoration, complete fixture cleanup, and inspected full-device picker/result
frames on the x86_64 emulator and AArch64 Samsung.

The Archphene Qt 6 appearance theme now delegates only native file-dialog
helpers to Qt's stock `xdgdesktopportal` theme. It primes Qt's asynchronous
FileChooser-version discovery on the first event-loop turn, after the platform
theme factory has unwound, and guards the stock portal theme's Archphene
base-theme lookup against recursive portal construction. Unmodified KCalc on
the physical AArch64 Samsung and x86_64 emulator now opens Android DocumentsUI
from both **Import Scheme** and **Export Scheme**. Open and Save/create
cancellation return to the same live KDE dialog, emit exact portal cancellation
results, change no files, and pass full-device and scoped fatal-log inspection.
Qt multiple-file and folder selection remain pending.

Folder streaming also has a sliding 30-second boundary around provider queries,
descriptor opens, and reads. It first cancels the SAF operation or closes the
active descriptor. If a provider ignores both for another two seconds, only
the generated app-shell process is terminated; the manager-owned pipe observes
EOF and Rust discards descriptor-relative staging. A debuggable Archphene
DocumentsProvider deliberately ignores cancellation for 60 seconds. Its modern
Android 16 query path and Samsung Android 15 legacy query path both trigger at
the expected deadline, leave the manager alive and no partial project, and
permit the subsequent normal folder/collision/cancellation gate to pass.
Full-device frames show the responsive picker before the stall and the
dialog-free Android surface after the app shell is stopped.

The same provider boundary injects deterministic list and descriptor-open
failures through the real DocumentsProvider/Binder route. On both exact ABIs,
each failure leaves the app shell and manager alive, publishes no partial
project, and returns a failed portal response. The generated app shell also
shows a native, readable “Couldn’t import that folder” message instead of
silently dropping back into the Linux app. A normal retry then imports the
exact fixture, with picker, feedback, and completed full-device frames
inspected on both devices.

The real DocumentsProvider path also passes the exact 10,000-entry portal
contract boundary. The fixture spans three bounded child cursors and contains
9,997 files, including empty files and four nonempty hash sentinels. The
manager imported all entries and 57 nonempty bytes in 13 seconds on the
emulator and 49 seconds on Samsung. Counts, sampled hashes, empty content,
app-shell and manager survival, fatal logs, and complete picker/returned-app frames
are independently checked on both devices.

Slow but healthy provider streams are distinguished from stalls. A debuggable
DocumentsProvider returns three 16-byte pipe chunks 20 seconds apart, so the
whole transfer takes 40 seconds while every read remains within the sliding
30-second deadline. Both exact ABIs complete without a false timeout and
preserve exact content and both processes. Folder transfers lasting over 500
milliseconds now cover the Linux surface with a native indeterminate progress
indicator and “Large folders may take a moment” explanation, then restore the
application on completion. Complete progress and returned-app frames were
inspected on Samsung and the emulator.

The same private frontend implements XDG Settings portal v2 for the standard
appearance color scheme, accent, contrast, and reduced-motion keys, including
the intentionally nested legacy `Read` result. It honors D-Bus no-reply calls,
removing GTK's rejected startup error. The contract probe passes on both exact
ABIs. Color scheme and Material You accent now remain live through a private
11-byte versioned state record. The manager writes it with fsync and atomic
rename from the app-shell worker, while the portal accepts only a no-follow
regular file with the exact size and grammar. Android configuration/display
changes and wallpaper-color callbacks publish only actual value changes and
the portal emits the corresponding `SettingChanged` signals. A real
light-to-dark-to-light transition preserves the app shell and Linux process and
matches fresh `ReadOne` results on Android 16 x86_64 and Samsung Android 15
AArch64. The device gate preserves the prior night-mode setting, rejects fatal
logs, and captures full-device frames after the transition settles.

The x86_64 package lane renders immediate durable progress for a real
`dotnet-sdk` request and resolves the same shared-root `base` plus SDK closure
for details, download, size preflight, and commit. The original reused-emulator
gate correctly rejected a 757 MiB requirement with only 454 MiB available,
preserved a readable 95% Failed/Review activity card, and left no .NET/base
local-database entries or lock.

A subsequent clean API 36 emulator with a 12 GiB data partition completed the
normal manager installation. The shared root now runs `dotnet --info`, creates
an ASP.NET Core MVC project, restores it, and builds it successfully. The
generic glibc bridge gained the contracts exposed by the stock toolchain:
logical executable `realpath`, optional `get_mempolicy` fallback, translated
`mkdtemp`, empty-path preservation, and translated `utimensat`. Host probes
cover each boundary. A second restore with a completely absent HOME proves
.NET can create and permission its first `NuGet.Config` without manual setup:
the Android terminal reports a successful restore/build and the resulting file
is mode 0600. This required the patched glibc itself—not only the preload
bridge—to translate an internal `chmod` call that managed code reaches
directly. The build now runs a preload-free direct-libc regression, and the
same AArch64 glibc passes that probe on the physical Samsung.

Kestrel remains alive while Archphene is backgrounded, and Android Chrome
loads the generated MVC home page at `127.0.0.1:5000`. The test deliberately
runs from the project directory so ASP.NET receives the normal content root.
The same x86_64 project is available inside Code's integrated terminal:
`dotnet --info`, `dotnet new mvc`, restore, build, and run complete through the
generic runtime, and the browser reaches the running service. C# language
service installation, debugging, and breakpoints remain open.

Current VS Code on the physical AArch64 Samsung now passes the same
user-visible path with a stock .NET 10 MVC project. The production compositor
keymap carries the complete common US punctuation and modifier set, so the
integrated terminal preserves `/`, `-`, `.`, and `:` instead of silently
dropping them. The package runtime publishes a manifest-verified `xdg-open`
adapter at the conventional Linux command boundary; the preload bridge executes
that Android-native adapter without the glibc loader, and the adapter calls the
private XDG OpenURI portal rather than receiving the privileged Android broker
address. VS Code created, restored, built, and ran the project at
`127.0.0.1:5263`; unmodified `xdg-open` brought Brave forward with the rendered
MVC home page. The full-device browser frame and authenticated wrapper log were
captured before the server and exact test tree were removed. Only the
user-approved C# debugger-extension, breakpoint, and debug-control portion
remains open.

On 2026-08-04, the current Samsung `SM_S908U` app shell was independently
reopened through ADB as Visual Studio Code. Its Archphene-owned VS Code profile
contains no extensions, while the available host C# extension is
`ms-dotnettools.csharp-2.140.9-linux-x64` and cannot execute in the phone's
AArch64 root. The remaining debugger gate therefore requires an explicitly
approved AArch64 C# extension and its .NET runtime dependency before it can
prove a breakpoint, variable inspection, continue, and restart on the device.

With explicit device approval, the physical Samsung VS Code terminal installed
`ms-dotnettools.csharp` 2.140.9 and its
`ms-dotnettools.vscode-dotnet-runtime` 3.1.0 dependency on 2026-08-04. The
terminal's extension listing names both extensions, and a newly created stock
.NET 10 MVC project restored and built successfully. The workspace/debugger
acceptance remains open until the project is opened through the production
folder flow and a breakpoint is paused, inspected, continued, and restarted.

With user approval, the normal Archphene package UI has now installed stock Git
2.55.0 into both shared roots. The executable is immediately visible from the
manager Terminal and each existing Code integrated Bash. A bounded device gate
opens the current generated Code app shell, invokes an alphanumeric user command
through the real integrated terminal, and verifies an actual `git init`,
`git add`, and `git status --porcelain` transaction reports `A  sample.txt`.
The gate removes its private command, result, and work tree, rejects fatal
Android logs, and passes with inspected full-device captures on the exact
x86_64 emulator and AArch64 Samsung. The initial Samsung capture also confirmed
the phone-layout issue: the stock Welcome and Copilot panes left a needlessly
narrow workspace and terminal, so Git readiness alone did not close the
phone-first Code UX task.

The generic phone-workspace path now closes that UX gap without changing Code
configuration or adding an application exception. Settings explicitly tells
users that Auto prioritizes readable phone content and that 75% fits more
desktop panes. The retained choice expands the normal 432-logical-pixel phone
canvas to 576 pixels while Code continues to submit an exact physical frame;
Code's own standard pane controls remain the authority for Explorer and Chat
visibility. A state-preserving gate selects 75% through the public Settings UI,
recreates the manager, starts the current generated Code app shell, opens its
integrated terminal, checks resolved appearance and frame geometry, rejects
fatal logs, and restores the prior preference. It passes with inspected
full-device light/dark captures on the x86_64 emulator and AArch64 Samsung.

Physical AArch64 now passes the corresponding generic package and runtime
boundary. On Samsung, the six-output AUR transaction installs SDK 10.0.302 and
runtime 10.0.10 into the shared root. Manager commands complete `dotnet --info`,
`dotnet new mvc`, restore, and build; the shared terminal starts Kestrel and the
Android browser renders the generated site at `127.0.0.1:5000`. Full-device
screenshots cover the build, terminal server, and browser rather than cropping
the Linux surface. Stopping the shell also reaps Kestrel after a host regression
proved descendants that create another process group are collected before the
leader is terminated. Generic logical absolute package symlinks are preserved
across both fake-chroot builds and root-identity installs; exact-root legacy
physical links remain readable without accepting arbitrary Android paths.

The same current-source arm64 manager preserves the Samsung shared root at 35
installed packages and three current Foot app shells. Cached startup completes
in 255 ms, and a fresh generated Foot launch authenticates generation 328,
starts the manager-owned Linux process, connects the real Wayland client,
presents its first 1080×2202 frame, and resizes cleanly for the Samsung IME.
Pressing Home detaches that Surface with `close=false`; the observed manager
and wrapper PIDs remain unchanged, and resume reattaches session 1 with its
readable frame intact. A repeatable physical-device crash gate then sends
`SIGKILL` to the real Foot leader through the manager UID, observes the
wrapper-owned `Foot stopped (exit -9).` state, and proves that both the leader
and its Bash child disappear even though Bash created a separate process
group. The same generated app shell subsequently starts a fresh Foot/Bash tree
and presents a new Wayland frame without a fatal Android log. Repeating the
gate exposed an IME-resize race that had recreated the compositor and silently
relaunched a stopped client in the same authenticated session. The Service now
retains an explicit terminal message across every later Surface attachment;
two consecutive physical-device runs prove no process exists before the
wrapper is explicitly closed and reopened.

The exact x86_64 production path now passes the same lifecycle and crash
contract with current Foot 1.27.0-2. The emulator refreshed real repository
catalogs, installed the complete 150-package signed closure through the normal
manager transaction, approved only the primary Foot desktop entry, and
installed its generated thin APK through Android confirmation. Home detaches
only the Android Surface: the manager, wrapper, Foot leader, and separate-group
Bash child retain their exact PIDs, and resume reattaches the same session with
a readable full-device frame. Deliberate Foot `SIGKILL` renders
`Foot stopped (exit -9).`, removes both Linux processes, and a subsequent
explicit reopen presents a fresh Wayland frame without a fatal Android log.
The repeatable gate was updated from an obsolete log phrase to the current
first-attachment frame contract instead of weakening the runtime assertion.

Generated wrappers now bind those production paths to an explicit capability
contract. Rust publishes `wayland,input,ime,clipboard` with each claimed
launcher generation; the assembler places the exact value in signed manifest
metadata and verifies the completed APK, while the wrapper and manager both
reject a mismatch before session authorization. The migration gate found and
fixed a policy error that initially quarantined a correctly signed older
template: immutable package/signer/descriptor identity is now checked
separately from the upgradeable template/capability version. The emulator
updated Foot through Android confirmation, and Samsung updated btop++, VS Code,
Foot Server, Foot, and Foot Client through five confirmations. Direct APK
inspection and complete post-update Foot lifecycle/crash runs pass on both
ABIs with full-device evidence.

The native runtime execution boundary is now allocation-bounded as well. Its
process-global cancellation registry previously retained unknown future IDs
in an unbounded `HashMap`; a caller could grow native heap state without ever
starting a process. It is now a fixed 32-entry registry with explicit
`ENOSPC`, one-shot early-cancellation, duplicate-ID, running/cancelling,
removal, and slot-reuse semantics. Direct tests cover those state transitions,
and `unsafe_op_in_unsafe_fn` is enforced across every production Rust crate
that contains unsafe operations. Builder, manager JNI, process, and storage
crates additionally deny unsafe outside their existing explicit boundary
modules. Every exported compositor JNI symbol and its Java
array/direct-buffer/surface/bitmap conversion is now visibly enclosed in one
documented boundary module. The workspace passes Clippy with warnings denied,
all 260 Rust tests, and optimized Android builds for both exact ABIs.
The remaining raw-pointer handles are now gone: fixed synchronized registries
cap core/probe instances at eight and generated-app-shell instances at four,
encode a slot plus generation into each positive opaque handle, and reject
exhaustion, stale use, double destroy, and stale-after-reuse without allocating
per call. Direct tests and an exact JNI-name Android probe pass those cases on
both maintained ABIs. That probe also caught and fixed non-tiled direct Wayland
surfaces being expanded to the default 320×160 output instead of retaining
their exact client raster. Splitting the remaining syscall/native-window FFI
stays open.

The visually inspected evidence is a full-device screenshot rather than an
app-only frame. The complete Rust workspace passes 259 tests, including the
large-resolution, raw-signature-status, empty-files-record, loader-path, JNI,
compositor, terminal, storage, AUR snapshot, and warmed-allocation regressions.

The current Rust/Kotlin manager now also exposes separate persisted automatic
or bounded controls for Linux workspace geometry, toolkit text, and visible
control size. A manager-owned app shell snapshots them at process start, so a
running app remains stable and a relaunch applies the new policy. Physical
Samsung validation covers Foot at an explicit 150% geometry setting
(288×587 logical output) and the restored automatic phone setting (432×881),
using full-device screenshots and real shell input.

That gate exposed a generic terminal-emulator regression in manager process
supervision: Foot obtains a PTY name, closes the master inherited by its child,
then calls `setsid` before reopening the slave and claiming the controlling
terminal. The glibc bridge now recognizes that bounded PTY-session transition
while continuing to suppress unrelated daemon escape from the supervised GUI
process tree. Host regressions reproduce Foot's exact ordering plus `openpty`
and `forkpty`; the sealed AArch64 bridge starts stock Foot and Bash without
permission errors, accepts a command, and remains manager-owned.

Official package search now preserves pacman's installed annotation through a
strict six-field Rust/JNI snapshot. Rows distinguish an available official
package, the same installed version, and a differing installed version without
guessing application type from its name or description. The no-network
emulator fixture covers all three states, durable failed/queued/cancelled row
overlays, cold restoration, Retry, light/dark visuals, and exact non-mutation.
The physical Samsung manager was updated in place and shows the real current
Foot package as Installed while preserving its 255-package shared root.
Verified package-file classification, version-order-safe update labels, and
reviewed AUR result integration now pass as described above.

The Obtainium follow-up retained one suitable phone UX pattern without copying
its GPL-3.0 Flutter implementation: only the matching or appended active
package row replaces idle trailing space with compact progress. Archphene keeps
its richer exact operation/phase/percentage string and durable recovery card;
zero-percent work is indeterminate and later phases are determinate. Recycled
native views avoid rebuilding the list. State-preserving emulator and Samsung
gates prove immediate accessible Queued feedback, safe Cancel, cold-restored
Cancelled state, exact package/database/cache restoration, clean scoped logs,
and inspected full-device light/dark presentation.

The shared-file namespace is now concrete on both devices: root bootstrap
creates exact fail-closed `/mnt/android/{documents,downloads,media,pictures,shared}`
aliases to the corresponding private home directories. The Files page explains
the Archphene Home and snapshot boundary, DocumentsProvider CRUD/security gates
pass on x86_64 and AArch64, and a full-device Samsung Foot/Bash run reads an
Android-side Shared fixture through the Linux alias. The complete Rust workspace
passes 259 tests; Android debug lint and the minified release build pass.

The manager's new Share action opens at Archphene Home, selects only its own
bounded regular provider documents, excludes Archphene as a circular target,
and gives Android's chooser an exact MIME-typed read-only URI grant. The
state-preserving exact-APK gate and full-device picker/chooser review pass on
both targets; separate emulator and Samsung Messages runs receive the
temporary cross-UID read grant without sending the fixture. A non-destructive
wide-layout gate also verifies side-by-side file cards and equal 68 dp Import
and Share actions.

The follow-up current-source storage audit passes on both devices. Connect now
supplies Android's primary-storage root as its initial URI, so DocumentsUI does
not inherit Archphene Home/Shared from the preceding Share flow. The test
helper also selects device storage explicitly rather than depending on picker
history. Both targets pass connect, replace, restart, revoke, reconnect,
read-only, disconnect, cancellation cleanup/retry, exact recursive snapshot,
stale-stage recovery, atomic publication, retained local project, scoped logs,
and full-device frames. The follow-up synchronizer is described below;
byte-level transfer progress and richer Android mapping status remain.

The private FileChooser portal now preserves bounded XDG MIME filters through
Android DocumentsUI. It reads the selected filter, or the first available
filter when no selection is supplied, canonicalizes at most 16 MIME rules, and
validates the serialized request again in the manager service and generated
app shell. Multiple rules use Android's required `*/*` base type plus
`EXTRA_MIME_TYPES`; a single rule remains narrow. Glob-only XDG filters fall
back to `*/*` because Android has no equivalent filename-pattern contract.
The reusable device gate presents text/plain and application/json beside an
image/png decoy: both Android 16 x86_64 and Samsung Android 15 AArch64 leave
the requested documents selectable, disable the decoy, import exact bytes,
return to a clean Mousepad frame, and emit no fatal logs. The pre-existing
single-open, multi-open, Save As, and folder suites also pass unchanged on
both devices after the shared parser change.

Generated app shells and the manager-side app-shell policy now parse signed
incoming-document MIME declarations incrementally. Their previous `split(';')`
paths allocated every entry from the admitted 2,080-UTF-16-unit metadata string
before checking the 16-type limit. Both parsers now use pre-sized bounded result
and duplicate sets, validate each type as it is admitted, preserve case-sensitive
duplicate semantics, and reject type 17 before creating its substring. Direct
JVM coverage proves exact 16-type admission, type 17 rejection, and an exact
2,080-unit delimiter flood. Launcher-template and app unit tests and lint plus
both exact-ABI builds pass. The subsequent physical-Samsung Foot regression
retained stable Android and Linux processes, a 34 px font, 126 px controls, and
visible command output in inspected full-device frames. Latest evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/app-launcher-mime-parser-bound-foot-20260804`.

Manager document imports now expose the current document and live copied-byte
count without routing file bytes through Kotlin or JNI. The Files-page Import
action becomes Cancel import while Rust is streaming, and a separate native
cancellation token is checked at every fixed-size copy chunk. Cancellation
removes staging and never publishes the destination; completed documents from
an earlier item in the same batch remain honestly counted. The exact-APK gate
repeats single, collision, ordered multi-document, process-restart, picker
cancellation, slow-copy progress, and mid-copy cancellation on Android 16
x86_64 and Samsung Android 15 AArch64. Both exact ABIs preserve prior files,
leave no cancelled destination or staging residue, emit the scoped cancellation
log, and pass inspected full-device progress and terminal-state frames.
Provider access is now bounded as well. Metadata queries and descriptor opens
use Android cancellation with a 30-second deadline and a short fail-safe
process-recovery grace period for providers that ignore cancellation. Rust
polls imported descriptors without blocking indefinitely, checks cancellation
every 100 ms, and resets its 30-second idle deadline whenever bytes arrive.
The separate-process hostile provider gate proves cooperative open timeout,
ignored-open manager recovery and durable interrupted status, stalled-pipe
rollback, a three-chunk stream whose total duration exceeds its debug deadline,
normal exact-byte retry, and no partial publication on both exact ABIs. The
resulting full-device failure and recovery messages are readable on both
targets.

The initial mirror now records a real synchronization ancestor while it copies:
a stable random 128-bit mapping identity, sorted directory entries, exact byte
counts, and streaming SHA-256 fingerprints are atomically retained in private
state. Disconnecting the scoped Android grant retains that identity alongside
the local project, allowing a later same-tree reconnect instead of creating an
unrecoverable project collision. The physical Samsung gate independently
decodes the manifest and verifies every recursive fixture digest after
cancellation/retry, restart, and disconnect. That ancestor now feeds the
regular-file transaction path below.

The next synchronization slice is now operational for regular files. Rust
rescans the descriptor-contained Linux project, fingerprints Android
descriptors, merges the two snapshots with the retained ancestor, and pages a
fixed binary action plan. Verified atomic Linux pulls, Android
staging/backup/rehash pushes, exact file deletions in both directions, and
deterministic conflict copies pass on Samsung. A bounded checksum-protected
Android mutation journal is tested with forced manager death both before and
after baseline commit: the former restores and replans, while the latter
removes the committed backup without resurrecting data. Empty directory
deletion now propagates in both directions, and multiple Android removals are
serially checkpointed during one user-visible Sync. A separately locked native
cancellation token interrupts Android and Linux fingerprint loops; exact
256 MiB cancellation fixtures leave no partial file. Forced death on both sides
of Android replacement publication also proves rollback/retry and finalization.
A bounded SAF bridge gives listings, metadata calls, and descriptor opens
cancellation signals plus 30-second deadlines. Because Android document
mutations expose no cancellation signal, their watchdog terminates the manager
and leaves recovery to the already-persisted journal or next three-way scan.
A live action-only status distinguishes pushes to Android, pulls into
Archphene, deletions on either side, and conflict preservation while displaying
running pull, push, and conflict counts. Converged entries are excluded from
the total. A bounded checksum-protected 16-entry History retains exact conflict
paths and supports Retry across manager restart. The complete matrix and
visually inspected full-device progress frames pass on the exact x86_64
emulator and physical AArch64 Samsung.

The manager Terminal now accepts strict bounded CSI prefix/intermediate
grammar, clears saved scrollback for xterm `CSI 3 J` without erasing the live
screen, and answers Secondary DA, character-size, and ANSI/DEC mode queries
through its existing fixed reply ring. Malformed and overlong forms fail
closed, and the warmed parser remains allocation-free. Exact-ABI probes run
the queries through a real Bash PTY on the emulator and Samsung. Their
full-device captures also verify the production session layout: the obsolete
command-entry and Run/Send row is gone, the shell selector disappears while a
session is active, and only a compact status/Stop row remains below the
full-height terminal.
Every affected terminal regression now enters fixture commands through the
actual focus, Android text-editor, and hardware-Enter path rather than the
removed textbox. Noninteractive backend-command coverage uses a bounded
debug-manifest-only receiver, so release builds do not regain a second command
surface. The inactive footer reports either the selected shell's readiness or
the need to install a supported shell instead of advertising the removed
action.

Package details now join the verified archive result to installed desktop-entry
source and executable ownership, the exact broker-capability contract, and the
generated wrapper's signer/generation/template/publication state. Terminal
desktop entries no longer produce graphical wrappers. Real Foot installs on the
x86_64 emulator and AArch64 Samsung pass the normal three-app-shell review,
stale-signer uninstall recovery, one-time Android source permission, serialized
system confirmations, Ready presentation, cold manager restart, and native
Wayland launch. This gate found that the GTK settings preload relied on GLib
symbols already existing in the target process, causing Foot Client to exit on
`g_object_unref`. Both checked-in modules now explicitly link
GIO/GObject/GModule/GLib; the AArch64 build uses a checksum-pinned Arch Linux ARM
GLib sysroot. Full-device Foot frames map cleanly on both devices. Exact
static toolkit/protocol derivation now walks each installed ELF's bounded
dependency graph with fixed-size ELF/program/dynamic-table reads rather than
loading whole binaries. Package details can report Qt 5/6, GTK 3/4, SDL 2/3,
native Wayland, X11 linkage, OpenGL/EGL, and Vulkan with incomplete-graph
provenance. Current Foot reports Native Wayland on both devices. Scripts and
plugin- or `dlopen`-only stacks are now covered by a separate observed-process
topology. Every active generated app shell scans only its supervised process
group on a two-second startup warm-up and a 30-second steady cadence, streams
`/proc` maps through fixed stack buffers, recognizes loaded
toolkit/protocol/graphics SONAMEs and Chromium child roles, and accumulates
results against the exact launcher generation. The
Android `/data/user/0` and `/data/data` alias is accepted only after the two
roots have identical device/inode identity. Registry v4 migration, stale
generation rejection, content-change reset, malformed map/root cases, and an
exact Chromium-role fixture pass. Real Foot launches persist Native Wayland
observations on current x86_64 and AArch64 installs; package details retain
static, observed, partial-scan, and launcher-coverage provenance and do not
promote any topology into a compatibility claim.

Optional bridge evidence is now similarly fail-closed. The bounded verified ELF
walk recognizes exact Pulse, CUPS, PipeWire, libsecret, and KWallet SONAMEs and
binds their audio-output, printing, camera, and secret-storage bits into
launcher registry v6. A changed closure advances the existing Android app-shell
identity for review; v5 migration starts with no invented capability evidence.
Package launcher review R4 carries both detected and unavailable masks, rejects
overclaims, and names unavailable functions in official and installed-AUR
details. The fixed V4 core contract remains unchanged. Printing and audio output
are the first optional brokers promoted from evidence to authority. Only a
verified CUPS-linked app shell receives the exact V5 `printing` contract. Binder
protocol v11 carries one bounded PDF descriptor from the manager-owned portal
to the authenticated visible wrapper; the wrapper rechecks file type and size,
reparses page structure, stages the document privately, and invokes Android
PrintManager. The real Samsung XDG PreparePrint/Print gate renders one page,
exposes Save as PDF, cleans cancellation state, and rejects malformed PDF and
pipe descriptors.

Only a verified Pulse-linked executable closure receives `audio-output`. The
manager starts a session-scoped private Pulse server backed by Android AAudio
with OpenSL ES fallback, passes the private native-protocol address only to that
manager-owned Linux process, and closes it with the session. Bionic audio
libraries stay outside every Arch glibc loader path, and playback requests no
Android runtime permission. Unmodified `pavucontrol`, bounded 48 kHz stereo
playback, an active non-audio denial, cleanup, scoped fatal logs, and inspected
full-device output pass on the x86_64 emulator and AArch64 Samsung. Microphone
input remains a separate unported capability and permission boundary.

Generated-app-shell secret catalog publication now encodes directly into one
fixed 1 MiB buffer. The previous `ByteArrayOutputStream` could grow beyond that
policy and then allocate a second complete `toByteArray()` copy before checking
the limit. Byte, unsigned-short, big-endian integer, and length-prefixed UTF-8
writes now reserve their complete capacity before mutating the buffer; overflow
therefore fails before truncating or writing the caller's output descriptor.
Eight direct JVM tests cover the shared JSON index buffer plus exact binary
string capacity, pre-prefix overflow rejection, and byte-identical legacy
integer encoding. Launcher-template unit tests and lint pass with zero errors,
and both exact-ABI manager builds package the updated release template. After
installing the rebuilt AArch64 manager, physical Samsung retained stable Foot
processes and exact visible command output in inspected full-device frames.
Hashed visual evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/secret-catalog-bounded-buffer-foot-20260804`.

## Validated

| Area | Evidence |
|---|---|
| Manager self-update | Public GitHub Releases discovery, bounded download, SHA-256 verification, signer/package validation, Android confirmation, replacement, and restart reconciliation. The reproducible workflow published exact-ABI `v1.0.1` assets; live `0.9.0` to `1.0.1` updates pass on x86_64 and physical AArch64. The restored standalone gate independently verifies the real published x86_64 `v1.0.0` checksum/baseline, one-time compatibility alias, production signer continuity, stable UID/first-install identity, installed version, and reconciled restart in an isolated temporary AVD. |
| General x86_64 package transactions | Arch dependency resolution, package-signature verification, closure staging, desktop/terminal classification, package-specific label/executable/icon/MIME/toolkit/ABI/capability metadata, generated APK validation, persistent Android Keystore signing, and PackageInstaller installation pass with KCalc, Mousepad, and CLI packages; a concurrent missing-package failure does not block an unrelated CLI transaction |
| AArch64 package runtime | A cacheable Linux container resolves the current Arch Linux ARM pacman/GnuPG/libarchive closure, verifies every package with the pinned build-system key, reduces it to required AArch64 ELF objects, cross-builds patched glibc and the path broker, and emits a 70-entry checksum catalog. The dual-ABI manager selects isolated ARM repository/trust assets; Samsung tests pass package search, nine-package libalpm resolution, exact build-key verification, staging, terminal classification, authenticated runtime-pack publication, Terminal UID materialization, and `btop 1.4.7` execution |
| Qt, GTK, and direct-Wayland appearance | Unmodified KCalc/Qt and Mousepad/GTK3 pass functional, accessibility, geometry, contrast, popup/dialog, density, and live-theme gates on current x86_64. Current-source Samsung wrappers repeat those core Qt/GTK3 cases. GNOME Text Editor/GTK4 passes stable-process live light/dark and cold dark launch on current-source x86_64 and manager-owned AArch64. Foot passes readable density-aware visuals, live theme switching, UTF-8 IME, clipboard/selection, scrollback, resize, and lifecycle on both architectures. Broader application and external-display coverage remain. |
| Shared bridge runtime | KCalc, Mousepad, generated wrappers, and native probes compile against one Android Activity/InputConnection/clipboard/window host and one Rust compositor; the application Activities are metadata-only subclasses. Official unmodified `wev` and manager-generated `wl-paste`/`wl-copy` packages validate core seat input, exact bidirectional plain-text transfer, persistent source ownership, focused selection/source protocol, and demand-driven Android clipboard reads on x86_64 emulator and physical AArch64. Binder protocol v16 additionally preserves bounded Android HTML plus its required plain fallback; current Code consumes the exact fallback on both ABIs. |
| Shared runtime packs | Verified Arch dependency closures are published atomically as immutable content-addressed packs owned by the manager; an exported caller-authenticated provider grants exact read-only module URIs to the generated wrapper UID, Binder-death leases protect active wrappers, cold app-drawer relaunch loads the active pack, untrusted shell access is rejected, superseded/manual-cache unbound packs are reclaimed, and the KCalc wrapper shrank from 57 MB to 629 KB. Each launch uses an Activity-tied subreaper supervisor so daemonized GUI descendants remain owned until the complete Linux tree exits. KCalc survives rotation without duplication and leaves no Linux descendants after Back or force-stop; the runtime-FD lease/cleanup regression passes after the supervisor change. |
| Secondary-window registry | Parent/child xdg_toplevel ownership is bounded to 32 simultaneous windows, rejects cyclic and cross-client parents, and clears destroyed-parent references. Active-window routing, composited phone policy, separate Android Dialog hosting in freeform/tablet mode, semantic child input, close, and parent restoration pass the emulator regression; current-source Samsung Mousepad also passes checkbox interaction, child close, primary restoration, and final host cleanup. |
| Native compositor bootstrap | Rust wayland-server core cross-compiles for Android x86_64 and AArch64; registry/compositor/SHM/xdg-shell/pointer/keyboard/touch seat discovery, SCM_RIGHTS SHM and sealed XKB v1 keymap FD transfer, checked padded-stride frames, ordered xdg configure queues, partial/final acknowledgements, mapped/unmapped lifecycle enforcement, and validated xdg_positioner state/destruction, commit-gated popup configure geometry, output-bound flip/slide/resize constraint adjustment, reactive output and committed-parent-geometry reconfiguration, double-buffered xdg window geometry, popup-grab focus preservation across root commits, snapshot-and-commit wl_region input state, effective-region popup fall-through, recursive wl_subsurface composition/input with parent-atomic synchronized content/position/stack latching, input-serial grab validation, nested topmost grab stacks, root-to-popup hit testing and local-coordinate pointer/button routing, clipped stacking-order SHM popup composition with ARGB/XRGB blending, child-first idempotent popup_done/teardown with root focus and pixels restored, wl_data_device_manager same-client input-serial-gated text source/offer/selection/cancellation lifecycle plus focused descriptor-backed Android ClipboardManager transfer in both directions, zwp_text_input_v3 focus and double-buffered enable/surrounding/cursor lifecycle with Android InputConnection content-purpose mapping, arbitrary UTF-8 preedit/commit, delete, editor-action, show/hide sequencing, and invalid-input rejection, demand-driven ClipboardManager reads with self-publish suppression, inverse wl_surface buffer transform/scale with retained-source reinterpretation, accumulated logical/buffer damage translated through synchronized subsurface trees into clipped root presentation batches, double-buffered wp_viewporter crop/destination scaling, wp_fractional_scale_v1 feedback, cursor-role SHM buffers isolated from application composition with Android PointerIcon transfer, zwp_pointer_gestures_v1 swipe/pinch/hold streams, and wl_output surface-enter/mode/scale updates, with post-ack Android bitmap presentation and Choreographer-timestamped frame-callback pacing, focused pointer, wl_pointer v9 value120/relative-direction wheel axes, wl_touch motion, two-pointer gestures, and hardware-key events routed from Android input, exact wire/pixel checks, and resource teardown pass on both |
| Package job scheduler | Per-package phase/error state and a bounded structured diagnostic history survive Activity recreation, manager process death, and reboot; legacy jobs migrate without data loss, two preparation jobs can overlap, wrapper mutation/signing and Android confirmation are serialized, and package failures are isolated. List/detail progress, recent phases, cancel, retry, installer completion, and interrupted-completion reconciliation pass emulator tests. Active foreground package work also survives real Recents dismissal, shuts down only after completion, and restores exact cache-recovery results after cold restart on both exact-ABI targets. Real-reboot gates on the emulator and Samsung recover pre-mutation work as an exact-progress Failed/Review result without package or cache mutation. |
| Terminal command channel | Up to eight foreground-service-owned PTYs issue collision-free per-request pacman facade commands. Search results and durable package-job resolve/download/install/complete/cancel/error states return through a signature- and caller-verified Terminal provider. A verified Arch Bash 5.3.15 closure becomes the default shell after installation; PTY startup, `C.UTF-8`, package queries, and home writes pass on the 4 KB x86_64 emulator and physical ARM64 Samsung device. A real `tree 2.3.2` install and fresh-session execution pass on x86_64, physical AArch64 executes managed `btop 1.4.7`, and untrusted shell result injection is rejected. The 16 KB x86_64 emulator fails closed with an explicit upstream-loader compatibility result |
| Terminal project folders | A user-selected SAF tree receives a persisted scoped grant and an atomic `$HOME/Projects/<alias>` mirror. Recursive initial copy, exact SHA-256 baselines, no-op sync, bidirectional file/directory additions, edits, and deletions, serial crash-safe multi-delete checkpoints, deterministic conflict copies, live action-only directional progress, durable exact conflict history/Retry, native long-hash cancellation, bounded SAF deadlines with fail-closed mutation timeout recovery, restart/disconnect retention, and forced deletion/replacement journal recovery pass on physical AArch64 and exact x86_64. Terminal command exposure remains. |
| Android storage boundary | Production manifests and packaged manager, Builder, and thin-app-shell APKs request no `MANAGE_EXTERNAL_STORAGE`, legacy `READ_EXTERNAL_STORAGE`, or legacy `WRITE_EXTERNAL_STORAGE`. The CI source contract and installed-package audit cover the manager plus every generated app shell; the current physical Samsung and x86_64 emulator pass while all supported exchange remains behind SAF, scoped URI grants, the DocumentsProvider, and explicit synchronized mirrors. |
| Android capability broker | The current Rust/Kotlin manager publishes a V4 core wrapper contract for Wayland, input, IME, clipboard, documents, OpenURI, and notifications, plus exact optional printing and audio-output variants only for matching verified executable closures. One randomized same-UID socket serves each authenticated visible app shell's private XDG portal. OpenURI accepts bounded HTTP(S) only, notifications remain wrapper-attributed, and Binder protocol v16 carries the implemented callbacks, bounded HTML/plain clipboard payloads, and the provider-final Save As display name. The same boundary binds registry-derived desktop MIME declarations into each signed wrapper without exposing Android grants or paths to Linux. Repeatable exact-ABI gates cover browser policy, notifications, document intake, unsupported MIME, and optional-capability denial on x86_64 and physical AArch64; the physical device also passes the complete printing path. |
| Audio input/output | Verified Pulse evidence selects exact `audio-output,audio-input` app-shell contracts. A session-scoped manager-owned Pulse server renders through AAudio with OpenSL ES fallback and exposes a private mono 48 kHz PCM16 source. Input permission is never inferred from playback or requested at startup: the first source stream triggers authenticated one-shot manager consent, and approved capture runs under Android's microphone foreground-service and privacy authority. Bionic audio dependencies cannot contaminate glibc application loading, and app shells without the reviewed capability are denied. Unmodified `pavucontrol`, bounded playback/capture, emulator denial, Samsung privacy-forced silence, real nonzero samples, foreground/cache teardown, fatal-log checks, and inspected full-device light/dark presentation pass on x86_64 and physical AArch64. |
| Printing | Verified CUPS evidence selects the V5 app-shell contract without an Android runtime permission. The manager-owned XDG adapter accepts one regular PDF descriptor, and the authenticated visible wrapper enforces a 256 MiB/four-job bound, validates `%PDF-`, reparses page structure with Android's PDF engine, stages only in private cache, and opens PrintManager. PreparePrint, rendered one-page full-device preview, Save as PDF discovery, cancellation cleanup, malformed-PDF failure, and non-regular-FD rejection pass on physical AArch64. Non-CUPS app shells fail closed on both maintained devices; a printing-capable x86_64 package remains to be exercised. |
| Accessibility transport | A declared `accessibility` capability publishes a bounded acyclic virtual node tree on the compositor View and queues Android click, focus, set-text, and scroll actions back to Linux. A private AT-SPI2 adapter translates unmodified Qt, GTK 3, and GTK 4 application trees, actions, focus, menus, and secondary windows. Cache topology is hydrated with live component bounds, actions, state, relations, and newly discovered children; selected/sensitive state is preserved, model-button labels follow standard `LABELLED_BY` relations, semantic default actions are role-scoped, and defunct transient controls are removed. Binder protocol v16 maps each tree's source viewport through the compositor's exact fitted-content rectangle and presentation canvas, including stale primary buffers and oversized secondary windows. A test-only AccessibilityService verifies normalized framework bounds and semantic actions through KCalc, Mousepad, Qt5 Settings, and GNOME Text Editor controls, edits, tabs, menus, dialogs, file-chooser result selection/Open, rotation, and parent restoration on x86_64 and physical AArch64 while preserving prior app state. Current Samsung proof includes aligned Mousepad Preferences Close targets and Qt5 Settings OK/Cancel/Apply bounds that remain 43 px high after rotation. |
| Camera and PipeWire | The current Rust/Kotlin V9 path derives `camera` from verified launcher evidence, gives only those wrappers optional Android camera declarations, and requests `CAMERA` on first use. A fixed three-frame Camera2 I420 producer feeds one manager-owned private XDG Camera/PipeWire runtime assembled from verified APK payloads. Official unmodified Arch Snapshot passes current state-preserving gates on x86_64 and physical AArch64: permission, 640×480 stream startup, exact GTK4/Cairo environment, Linux presentation, fatal-free cleanup, and full-device zero-magenta inspection; the emulator shows its live virtual scene at luma 0–253. Earlier isolated coverage retains denial/no-reprompt, pre-consent rejection, invalid dimensions, bounded JPEG bytes, and per-plane diagnostics. The Cairo rule applies only to GTK4 camera app shells; ordinary GTK4 wrappers remain accelerated. |
| Secrets and keyrings | A declared `secrets` capability exposes a per-wrapper AES-256-GCM collection backed by a non-exportable Android Keystore key and conditionally publishes `org.freedesktop.secrets` on the private session bus. Sender-bound plain and DH/AES sessions, create/search/get/set/replace/delete, content types, item signals, zero-length values, ciphertext, metadata, process-restart persistence, bounds, stale-socket rejection, and no-log-leak checks pass on 4 KB and 16 KB x86_64 emulators and physical AArch64. Unmodified Arch libsecret and KWallet clients pass on 4 KB x86_64. Official Arch Linux ARM `secret-tool`, patched `kwalletd6`, and official `kwallet-query` pass encrypted store/read/clear, daemon-restart persistence, and cleanup on physical AArch64; upstream 4 KB-aligned Arch x86_64 clients are skipped on 16 KB Android. |
| Drag-and-drop | Generated GUI wrappers declare a `drag-drop` capability. Bounded plain text and `text/uri-list` map bidirectionally between Android `DragEvent` and standard Wayland data devices. Android files import through the conflict-safe document session; Linux exports are restricted to visible-home files and use exact Android URI grants. Copy negotiation, transfer, import/writeback, denied and granted provider access, completion, cancellation, and cleanup pass on x86_64 emulator and physical AArch64. |
| GUI application documents | One manager-owned **Archphene Apps** DocumentsProvider exposes each generated GUI wrapper's visible Linux home while dotfiles and runtime state remain private. Per-wrapper endpoints require the manager signature permission and verify the calling package. `ACTION_VIEW` and `ACTION_EDIT` support up to 32 URI grants, collision-safe same-name imports, hash-based writeback, and concurrent Android-edit conflict copies. A document sent to an active `singleTask` wrapper presents a native warning before a generic safe restart; Cancel preserves the running app. Manager CRUD, direct-provider denial, active-app restart, same-name import, conflict preservation, writeback, and DocumentsUI browse pass on the x86_64 emulator and physical AArch64. |
| Package discovery | Official Arch name/description candidates use deterministic exact, executable, prefix, token, and description ranking; executable ownership is merged from repository file databases, glmark2-es2-wayland resolves to glmark2, and installed-app multi-term search shares the same matching rules |
| Recursive AUR package graphs | Rust-authorized dependency-first reviews, exact official/AUR provider partitioning, separate isolated Builder roots, digest-bound dependency manifests, independent output verification, one final pacman transaction, and durable graph-prefix restoration pass on physical AArch64 with current `libpamac-aur` → `pamac-aur`. The latest exact 356-package signed closure, forced death after base 1/2, no-rebuild prefix restore, remaining base, final two-archive transaction, both installed pacman records, generated app-shell handoff, clean recovery state, and full-device completion presentation pass. |
| OpenGL ES bridge | Current manager-generated GLMark2 wrappers start a same-UID Android virglrenderer helper on their first launch. The bounded ELF profiler recognizes exact literal `dlopen` SONAMEs without file-sized allocation, while process-map observation remains the fallback. Mesa reports virgl over the emulator NVIDIA OpenGL ES translator and completes 32 logged default scene variants at 1080x2205 with score 14 and exit 0. On the Samsung Galaxy S22 Ultra, virgl uses Qualcomm Adreno 730 / OpenGL ES 3.2 and completes 33 logged variants at 1080x2202 with score 12 and exit 0. Both devices pass distinct full-device frames, bounded geometry, stable-host, fallback/fatal-log, and same-UID fault-injection gates. |
| 16 KB x86_64 loader | Patched glibc 2.43 is reproducibly linked with 64 KB PT_LOAD alignment and a 16 KB common page size. Every emitted loader/runtime ELF passes an independent alignment audit, and a similarly aligned dynamic executable runs through it inside the manager UID on the API 36 16 KB x86_64 emulator. Official Arch x86_64 package closures remain 4 KB-only and stay blocked. |
| Release display matrix | Fail-closed KCalc, Mousepad, and Foot gates combine raw/PNG frames, contrast, semantic trees, toolkit config, actual content geometry, scoped logs, and manifests across phone/tablet/docked emulator density profiles. Current-source Samsung repeats the core KCalc, Mousepad, and Foot phone cases. Kate separately passes stable-process tablet rotation plus an actual temporary 1920x1080 emulator display, task placement, mapping, and targeted input. Unmodified Seahorse additionally passes a state-preserving live task move from phone to a temporary 1920x1080 display and back at explicit 125% geometry: manager/wrapper/Linux identities remain stable and the compositor converges from 346x706 to 1024x506 logical pixels and back. Sustained physical external-display coverage remains. |

## In progress

Local debug builds can remain multi-ABI. Release builds emit independently signed x86_64 and arm64-v8a manager APKs whose embedded Terminal, package runtime, trust data, and wrapper templates contain only the selected ABI. Both variants launch pacman on matching devices, and the ARM manager has generated, installed, and launched a real KCalc wrapper on the Samsung test device.

The pinned Rust 1.88 workspace is now a separate pull-request and main-branch
gate. It checks formatting, all locked host tests (including the warmed
zero-allocation core, job, Terminal, retained-SHM snapshot-patch, and
synchronized-subsurface release steps), and
all-target Clippy with warnings denied. The compositor gate performs 1,000
warmed in-place snapshot patches and the associated pending-damage
take/convert/restore handoff while proving scratch-buffer and committed-frame
reuse, exact damaged-pixel updates, and zero allocations on the measured test
thread. A second real Wayland fixture first drives parent-commit and explicit-
desynchronization releases through the protocol, verifies callback publication
and retained callback-queue capacity, then invokes the same release path 1,000
warmed times while recycling cached damage, one live frame callback, the
compositor traversal stack, and callback and damage queue capacity. That
measured release step also performs zero allocations. The retained-SHM gate now
also patches an already-detached synchronized cache 1,000 times through the
production `SurfaceState` ownership decision without changing the visible
committed raster or allocating. The first pending synchronized snapshot still
requires semantic detachment from visible content. The remaining full Android
presentation copy is outside these claims.
Exact Android performance soaks remain local until a maintained physical-device
runner is available.

Commit damage collection is now bounded and reusable. A surface retains at most
64 pending surface rectangles and 64 pending buffer rectangles; excess requests
in either coordinate space promote that commit to a full snapshot rather than
growing attacker-controlled vectors. Synchronized
subsurface damage waiting for a parent commit and compositor damage waiting for
Android presentation use the same bound and collapse to a conservative union.
Successful commits return the pending vectors and converted-damage scratch
capacity to the surface. The thread-isolated gate now performs the actual
take/convert/restore handoff alongside each retained-SHM patch for 1,000 warmed
iterations with zero measured allocations. Direct regressions verify overflow
reset on the next commit, forced full-raster reads, bounded union coverage, and
preservation of undamaged pixels. Synchronized-subsurface release now uses one
retained depth-first traversal stack, translates cached rectangles directly into
presentation damage, returns cleared cached vectors to their surfaces, appends
callbacks directly to the retained presentation queue, retains stacking-request
capacity, and drains presented callbacks without discarding queue capacity. A
real Wayland parent/child fixture first drives both
release triggers through the protocol, then invokes the same warmed release path
1,000 times with cached damage and a live callback and measures zero allocations
on the test thread. An already-detached synchronized raster is also reusable
across additional cached commits: the ownership check follows transform and
viewport source chains, preserves the visible committed raster byte-exact, and
disables in-place mutation after publication. The warmed gate performs this
production cache-state decision and retained patch for 1,000 iterations with
zero measured allocations. The first pending synchronized snapshot remains
intentionally detached, and the full Android presentation copy remains open.
Rebuilt exact x86_64 and AArch64
compositor probes pass. The probe now accounts explicitly for the
mapped toplevel's retained activation configure before proving that two later
resize configures remain ordered and independently acknowledged.

The common direct-root app-shell path no longer constructs a redundant composed
output raster before copying into Android's retained `AHardwareBuffer`. When the
tiled root exactly covers the physical output and needs no cursor, popup,
secondary overlay, transform, viewport, subsurface, geometry adjustment, or
ARGB opaque-region processing, `last_frame` aliases the original retained client
raster and computes diagnostics directly from it. All non-equivalent scenes
still use the compositor canvas. Popup capture deep-copies an aliased root once
to preserve its stable base across later in-place client damage. Unit tests cover
direct eligibility, physical-size mismatch, transform and viewport fallback,
low-alpha opaque-region fallback, and popup-base isolation; the full workspace
and strict Clippy gates plus rebuilt x86_64 and AArch64 Android compositor probes
pass.

Release graphics diagnostics count seven distinct stages in the fixed
presentation snapshot: SHM snapshot, CPU conversion, GPU readback, texture
upload, GPU composition, direct `AHardwareBuffer` submission, and
SurfaceFlinger release. The initial physical baseline reported four SHM
snapshots, four CPU conversions, four direct AHB submissions, two asynchronous
releases, and zero GPU stages.

The current Bionic EGL/GLES renderer now imports the existing bounded AHB ring
as retained EGL images, keeps one source texture per Android window, converts
and uploads only the admitted SHM damage rectangle, and draws a fixed full-frame
quad into the selected output slot. Resize generations destroy retired EGL
images before releasing their AHBs. Missing extensions and every initialization,
import, shader, framebuffer, or draw failure select the existing CPU-locked
fallback for that window. GPU work currently completes through `glFinish` before
the existing SurfaceControl release-fence path; there is no `AHardwareBuffer_lock`,
`glReadPixels`, Kotlin frame copy, or JNI frame copy on the GPU path.

An isolated unmodified Mousepad Quick launch on physical Samsung `SM-S908U`
reported four SHM snapshots, four texture uploads, four GPU compositions, four
direct AHB submissions, two asynchronous releases, and zero CPU conversions or
GPU readbacks. Hardware text input visibly updated the correctly oriented
full-device frame, portrait/landscape recreation recovered the same path, and
Back closed the session and runtime cleanly. Host Rust tests/Clippy, Android app
unit/lint, the source contract, and exact AArch64/x86_64 builds pass. Virpipe
still returns through SHM, so this is not a zero-copy or direct-virgl claim.

The no-readback/no-managed-copy gate rejects `AHardwareBuffer_lock` and
`glReadPixels` in the GPU renderer, heap-building primitives in its warmed
render function, and managed frame-buffer allocation/copy primitives in Kotlin
dispatch. A fixed damaged-region staging buffer completes 1,000 conversions
with zero measured Rust allocations. The physical Samsung run recorded 1,688
live compositor dispatches, zero JNI array-copy bytes, zero Kotlin-copy bytes,
GPU upload/composition, and zero CPU conversion/readback. The wider service and
debug probe still allocated bounded ART objects, so the claim applies to the
frame path rather than the entire Android process.

The next virpipe stage now has a fixed manager-side AHB presentation protocol
and bounded registry. Every 64-byte `APHB` v1 frame authenticates the exact
session, helper generation, and 128-bit session token. At most three live resources may
declare bounded RGBA dimensions and exact estimated bytes; resource IDs and
slots are unique, fence sequences strictly increase, and release is required
before reuse. Declared allocation bytes may include validated stride padding.
One Present may be outstanding per resource; manager-to-helper Release must
match its exact 64-bit sequence, and DropResource is accepted only while idle.
Four focused tests reject cross-session/generation/token frames,
reserved trailing fields, unsafe dimensions, duplicates, unknown resources, and
stale fences. The active vtest helper does not yet consume this side channel or
bind guest scanout resources to transferred AHB handles, so the corresponding
release-plan item remains open and current virpipe frames still return through
SHM.

The private Wayland commit-identity half is also defined but deliberately not
advertised. Version 1 binds one object to one `wl_surface`; its double-buffered
`set_resource` request carries the helper generation, resource ID, and 64-bit
fence sequence into the exact next commit. A bounded Rust state machine permits
32 unique surfaces and three known resources, rejects duplicate or stale
identity, requires an exact match to the latest authenticated present before a
Wayland claim can latch, clears all identities on helper replacement/resource
release, lets a normal `wl_buffer` replace GPU identity, and retains identity for damage-only
commits. Five tests pass, and `wayland-scanner` accepts the XML. Mesa/vtest
sender integration, acquire-fence consumption, and release return remain open. An
additional generated client/server socket test enables the otherwise dormant
global, binds one surface, latches one scoped identity on the exact commit, and
clears it on the next commit. Production does not enable the global, so no new
direct-presentation claim is published.

The dormant compositor global is now fed only through the fixed APHB registry,
not direct test-only resource insertion. An authenticated Hello must precede
Resource/Present/Release frames; duplicate Hello and pre-handshake traffic fail.
Each frame is applied to cloned APHB and Wayland identity registries and becomes
visible only when both transitions succeed. The generated-client socket test
uses this coordinated path with a 64-bit fence sequence above `u32::MAX` before
claiming the exact surface commit. Native AHB handle and acquire-fence receipt
are implemented. The helper private Resource/Present sender, blocking Release
receiver, generic Mesa/Wayland identity sender, dual-ABI runtime staging,
Android direct submission, and SurfaceFlinger-backed Release return are now
implemented. Production activation remains withheld for composited scenes.

The Android vtest helper now implements the scoped APHB bootstrap and a dormant
AHB-backed resource path. Its
private socket, session ID, helper generation, and 128-bit lowercase-hex token
are strict all-or-none arguments. Valid configuration connects and writes Hello
before the vtest socket becomes visible; malformed, partial, overlong, or failed
connections terminate the helper. Dual-ABI release builds pass. A same-UID
physical Samsung listener received the exact 64 bytes for session 77,
generation 9, token `6a` repeated 16 times, and zero reserved bytes. A partial
configuration exited 1 with the expected bounded-contract error. With the
authenticated channel active, private vtest commands allocate at most three
bounded RGBA AHBs, import each EGL image as the exact virgl render resource,
send scoped Resource plus the NDK handle, conservatively complete GPU work, and
send Present plus the same monotonic 64-bit sequence returned to the client.
Both Android ABI builds pass. A same-UID physical Samsung command probe reported
`resource=1 stride=64 sequence=1 marker=46`. Private command 41 blocks for the
exact scoped Release, accepts marker `52` with zero or one close-on-exec fence,
waits a received fence, and permits reuse only after the matching sequence.
Malformed scope, resource, sequence, marker, truncation, or descriptor count
closes the channel. The Samsung probe completed Present sequences 1 and 2 around
one marker-only Release (`46,52,46`). The pinned Mesa 26.1.5 patch now issues
commands 39, 40, and 41 for eligible RGBA/BGRA display targets, sends the exact
returned sequence through the private Wayland request before the matching
surface commit, and falls back to `wl_shm` unless explicitly activated with the
private global. Reproducible x86_64 and AArch64 builds pass. The package runtime
now stages the pinned EGL/GLES/Gallium tree, and the launcher passes the scoped
helper side-channel arguments after binding the same-UID manager endpoint.
`ARCHPHENE_AHB_PRESENT` remains absent in production until imported composition
preserves popups, cursors, transforms, and alpha.

The dormant manager endpoint also has a bounded outbound Release transport. It
admits at most three responses, transactionally commits registry release only
after queue admission, resumes partial nonblocking 64-byte frame writes before
marker `52`, and transfers zero or one RAII-owned release-fence FD. Disconnect
closes queued descriptors. Host tests prove exact frame/marker ordering for both
marker-only and fenced responses. Direct Android buffer-release callbacks now
feed exact helper generation, resource, sequence, and optional fence ownership
into this queue; stale-generation callbacks are discarded after replacement.
On the API 35 Samsung `SM-S908U`, an isolated probe submitted two external AHBs,
observed the legacy previous-buffer release for exact identity `{7,1,1}`, and
then passed the complete native compositor suite.

The manager-side dormant control endpoint now admits Hello and Android Resource
handles. It binds only a bounded absolute path and accepts
only the current UID through `SO_PEERCRED`, reassembles fixed 64-byte frames,
processes at most four per compositor dispatch, discards partial state across
helper reconnect, and performs device/inode-checked socket cleanup. Unit tests
cover fragmented delivery, reconnect, unsafe paths, and replacement-socket
preservation. Android Resource receipt uses the NDK native-handle transport,
retries would-block without reading another frame, validates exact RGBA
dimensions, one layer, stride-backed declared bytes, required GPU sampling and
render usage, and reserved fields, and retains at most three handles. Resource
and idle DropResource commit handle and identity state transactionally. A live
API 35 Samsung nonblocking socket round trip accepted a valid 64×32 AHB and
atomically rejected a mismatched declaration. The host core test still returns
`Unsupported` because AHardwareBuffer is Android-only. The production launcher
now binds the endpoint before helper startup and can replace it only with a
strictly newer helper generation. Mesa activation remains disabled.

APHB direction and reuse are no longer ambiguous. Helper-to-manager traffic is
Hello, Resource, Present, and idle-only DropResource. Manager-to-helper Release
carries the exact Present sequence, marker `52`, and zero or one SurfaceFlinger
release fence. The registry rejects a second Present or drop while ownership is
outstanding, stale/mismatched release, and inbound Release on the manager
endpoint. Present now requires one marker byte with zero or one `SCM_RIGHTS`
acquire-fence descriptor. A descriptor is close-on-exec; marker-only records
that the helper completed rendering conservatively before send. Receipt is
nonblocking, retains one explicit acquire state per resource, and retries
would-block before parsing another frame. Host tests cover both forms and reject
a wrong marker; the Samsung round trip includes the descriptor path. Fence
consumption is implemented in the helper's blocking wait. Manager-side direct
submission now returns the SurfaceFlinger callback fence through scoped Release.

API 36 per-buffer release handling is implemented behind runtime symbol
resolution. `ASurfaceTransaction_setBufferWithRelease` receives one heap-owned
callback context with a weak presentation reference and exact slot ID; its fence
returns that slot to the available ring. When the symbol is absent, the existing
transaction-completion callback obtains the previous release fence exactly as
before. Dual-ABI ELF inspection shows no direct API 36 symbol dependency. On the
API 35 Samsung `SM-S908U`, an isolated Mousepad Quick launch reported
`Surface release mode=legacy transaction completion`, rendered four GPU-composed
AHB submissions, observed two SurfaceFlinger releases, and closed cleanly. A
clean Android 16/API 36 x86_64 emulator submitted three AHBs through the same
production path, reported `Surface release probe=API36 per-buffer callback`,
observed the release callback and fence, and then passed the complete native
compositor probe.

Hardware key, repeat, and modifier delivery no longer clones focused
`WlKeyboard` resources into a temporary vector. Focused resources stream in
retained order through a reusable live/same-client iterator; accepted events
repeat that allocation-free filter after key-state mutation when modifier
delivery needs a second pass. Debug resource counting uses the same iterator.
Pinned Rust 1.88 compositor tests and all-target warning-denied Clippy pass;
exact x86_64/AArch64 compositor builds pass.

Project-synchronization journal and history loading now uses a fixed 8 KiB
scratch buffer with exact 64/128 KiB stream ceilings instead of calling
unbounded `readBytes()` after a path-length check. Legacy `.bak` state is
validated as a no-follow regular file, restored with atomic replacement,
reopened with `NOFOLLOW_LINKS`, and decoded from at most limit-plus-one bytes.
JVM tests cover empty, exact, oversized, chunked, and zero-progress streams;
backup-only and backup-over-base recovery; missing state; and symlink rejection.
JDK 26 app unit/lint and exact x86_64/AArch64 manager builds pass.

Packaged manager and isolated-Builder runtime manifests now read through
API-29-compatible fixed 8 KiB scratch buffers with their declared stream
ceilings instead of allocating complete assets before size validation.
Exact-limit assets remain accepted; limit-plus-one and empty assets fail before
native parsing. Shared JVM tests cover exact, oversized, chunked, and
zero-progress streams. JDK 26 app/Builder unit compilation and lint plus exact
x86_64/AArch64 manager and Builder builds pass.

The isolated Builder's manager-data boundary probe now opens the supplied
sentinel and reads one byte instead of allocating the complete manager-selected
file. This preserves readable-empty-file handling and the result of open or
first-byte failures while preventing a hostile same-signer caller from driving
an unbounded diagnostic allocation.
JVM tests cover empty, missing, and sparse files larger than the JVM array
limit. JDK 26 Builder unit/lint and exact x86_64/AArch64 Builder builds pass.

The isolated Builder now reads `/proc/self/attr/current` through a 258-byte
bounded stream instead of unbounded `readText()`. This preserves the existing
256-character SELinux-context validation plus trailing newline/NUL allowance;
oversized or unreadable proc state falls back to `unavailable` before Binder
validation. Builder JVM tests cover exact and limit-plus-one reads. JDK 26
Builder unit/lint and exact x86_64/AArch64 Builder builds pass.

The manager now reads at most 64 bytes from the isolated Builder's writable
boundary-probe descriptor before ASCII decoding. The exact UID-bearing marker
remains accepted, while a compromised Builder cannot trigger an unbounded
whole-file string allocation. Existing bounded-stream JVM tests cover exact,
oversized, chunked, and zero-progress reads. JDK 26 app unit/lint and exact
x86_64/AArch64 manager builds pass.

App-shell secret-record and interrupted-write discovery now uses a bounded
directory stream instead of an uncapped `listFiles()` array. Each pass admits
at most 256 records, removes at most 256 stale temporary files, and scans at
most 513 recognized entries. Unknown names, symbolic links, and non-regular
matching entries fail closed. Stale cleanup deletes its bounded accepted batch
before reporting overflow, allowing repeated calls to recover instead of
locking the store permanently. JVM tests cover matching and total-entry limits,
symbolic links, unknown names, overflow progress, and mixed-entry recovery.
Launcher-template unit/lint and exact release plus x86_64/AArch64 manager builds
pass.

Generated-app-shell private print cleanup now uses the same bounded no-follow
directory walker instead of allocating the complete staging directory before
checking its 32-file ceiling. Unknown names and unsafe file types fail closed.
Inactive PDF files are deleted as each accepted entry is visited, so overflow
is reported only after bounded cleanup progress rather than retaining the whole
stale set forever. JVM tests prove active-file preservation and stale-file
recovery across overflow. Launcher-template unit/lint, exact release, and
x86_64/AArch64 manager builds pass.

Each generated app shell's Android PDF adapter now admits one active writer
instead of creating a thread for every overlapping `onWrite()` call.
Cancellation closes the owned streams so pending I/O observes descriptor
closure, completion reconciles late cancellation after cleanup, and the task
gate releases before exactly one terminal callback. `onFinish()` rejects later
writes. Concurrent JVM tests cover one-winner admission, close/release behavior,
release-before-callback ordering, and exactly-once completion. Launcher-template
unit/lint, exact release, and x86_64/AArch64 manager builds pass.

Verified camera-runtime link discovery now collects through a bounded directory
stream instead of allocating an uncapped `listFiles()` array before checking the
128-link ceiling. Entry 129 fails before validation or link creation, empty
runtimes still fail, and successful traversal retains only the declared
maximum. JVM tests cover empty, exact-limit, overflow traversal, and bounded
collection rejection. JDK 26 app unit/lint and exact x86_64/AArch64 manager
builds pass.

The debuggable DocumentsProvider now reads its delay and failure-injection
controls through a 256-byte bounded stream before UTF-8 parsing instead of
calling unbounded `readText()` on cache files. Exact-limit controls remain
accepted, limit-plus-one fails before string allocation, and release behavior
remains excluded by the existing debuggable gate. JVM tests cover exact,
oversized, and multibyte content. JDK 26 app unit/lint and exact
x86_64/AArch64 manager builds pass.

Package trust-source state now reopens with `O_NOFOLLOW|O_CLOEXEC`, requires the
opened device, inode, and length to match the validated regular file, and reads
through a limit-plus-one adapter instead of allowing `read_to_end()` to follow
concurrent growth. Missing, linked, oversized, substituted, shrunk, and grown
state fails closed without allocating beyond the declared ceiling. Package
tests cover exact, symlink, missing, static overflow, post-metadata growth, and
unbounded-reader behavior. Pinned Rust 1.88 package tests and warning-denied
Clippy pass.

Shell discovery now reads `/etc/shells` through the same bounded no-follow
reader instead of `read_to_string()`. Validation binds the opened descriptor's
device, inode, mode, and length before UTF-8 parsing, so a concurrent
group/world-writable replacement cannot inherit permission approval from stale
metadata and file growth cannot exceed the existing ceiling. Existing
shell-adapter tests cover accepted declarations and writable rejection;
bounded-reader tests cover links, overflow, growth, and shrink. Pinned Rust 1.88
package tests and warning-denied Clippy pass.

Durable package-mutation intent loading now uses the bounded no-follow reader
instead of `fs::read()`. Owner-only mode and size are validated again on the
opened descriptor before parsing, then the path is re-statted and required to
identify the same inode. Concurrent replacement, permission widening, growth,
shrink, links, and oversized state fail closed without exceeding the journal
ceiling. Mutation recovery tests cover valid official/AUR intents, writable
state rejection, and opened/path metadata policies; shared bounded-reader tests
cover atomic replacement, growth, and shrink. Pinned Rust 1.88 package tests and
warning-denied Clippy pass.

Interrupted-install reason recovery now uses the same bounded no-follow reader
instead of `fs::read()`. The opened stable descriptor must retain owner-only
permissions before parsing, and links, replacement, growth, shrink, or
limit-plus-one state fail closed without allocating beyond the 64 KiB intent
ceiling. Recovery tests cover valid explicit reasons, oversized state, and
symbolic links; shared bounded-reader tests cover descriptor and path
replacement policies. Pinned Rust 1.88 package tests and warning-denied Clippy
pass.

Managed root fontconfig reuse now reopens the file with
`O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK`, compares it through a fixed expected-size
buffer plus a one-byte overflow probe, and requires stable identity, mode, size,
and timestamps before accepting reuse. Content or mode repair publishes the
exact private replacement atomically through a caller-owned unique temporary
file; concurrent bootstraps cannot remove or rename another writer's partial
file. Symbolic-link substitution cannot redirect permission changes, and FIFO
substitution fails closed without blocking bootstrap. Root tests cover content
and mode repair, concurrent publication and cleanup, link-target preservation,
and FIFO rejection. Pinned Rust 1.88 root tests and warning-denied Clippy pass.

Launcher-registry loading now uses `O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK`, a fixed
metadata-sized buffer, and a separate one-byte overflow probe. Device, inode,
mode, size, and timestamps must remain stable across initial path metadata, the
opened descriptor, the completed read, and the final path before decoding.
Exact, grown, shrunk, interrupted, replaced, corrupt, and linked registry tests
pass without allocating beyond the 4 MiB ceiling. Pinned Rust 1.88 launcher
tests and warning-denied Clippy pass.

Bounded package-file copies now stream exactly the validated metadata size and
probe overflow separately instead of copying until EOF. Device, inode, mode,
size, and timestamps must remain stable across initial validation, the opened
source descriptor, copy completion, and the final path. Concurrent growth,
shrink, or replacement therefore fails closed. Tests cover exact, grown,
shrunk, oversized, and linked sources without writing growth bytes past the
expected destination size. Pinned Rust 1.88 package tests and warning-denied
Clippy pass.

Retained local-package database repair files now use the stable exact-copy path
instead of a second `io::copy()`-to-EOF implementation. Valid empty pacman
metadata files remain supported while every source keeps the 1 MiB per-file
ceiling, no-follow opening, final identity checks, and mode-0600 destination.
Removal-repair round trips plus exact-copy empty, growth, shrink, replacement,
overflow, and link tests pass with pinned Rust 1.88 and warning-denied Clippy.

Local pacman package identity and repair-match descriptions now parse through
stable bounded reads instead of `read_to_string()`. Device, inode, mode, size,
and timestamps remain tied to the no-follow descriptor and final path, so
concurrent growth, shrink, or replacement fails before parsing. Repair matching
preserves its previous non-match behavior for content-read failures or invalid
UTF-8 while still propagating open and metadata failures. Package tests cover
valid restored records, invalid text, links, overflow, growth, shrink, and
replacement with pinned Rust 1.88 and warning-denied Clippy.

The isolated AUR resolution database now copies signed repository catalogs
through the stable exact-copy path instead of `io::copy()` to EOF. Each catalog
remains within its repository ceiling and must retain stable device, inode,
mode, size, and timestamps through the final path check. Any failure removes
the partial database generation. Fresh-database, exact-copy, growth, shrink,
replacement, overflow, and link tests pass with pinned Rust 1.88 and
warning-denied Clippy.

Android same-UID process cleanup is now bounded before signaling. It scans at
most 4,096 `/proc` entries and retains at most 1,024 matching process IDs.
Quota, iterator, and non-transient metadata failures abort before any signal;
every retained UID is revalidated before the first kill. Unit tests cover exact
admission limits and prove a late validation failure sends zero signals. Pinned
Rust 1.88 compositor tests, warning-denied Clippy, and exact x86_64/AArch64
manager builds pass.

Process-tree `/proc/<pid>/stat` reads now use a fixed 4 KiB no-follow descriptor.
Every child-parent edge is bound to confirmed start-time plus descriptor
device/inode identities. Vanished-process `ESRCH` is normal scan churn, captured
descendants remain usable after leader exit, and exact identities are signaled
through `pidfd_send_signal`, preventing PID reuse from redirecting `SIGKILL`.
Unsupported pidfd policy fails closed. Tests cover exact, short, oversized,
interrupted, malformed, stale-component, detached-session, and live pidfd paths.
Pinned Rust 1.88 process tests, warning-denied Clippy, and exact
x86_64/AArch64 manager builds pass.

Android GPU helper ownership now survives graceful and forced shutdown. The
manager waits one bounded interval after `destroy()` and another after
`destroyForcibly()`. If the helper still lives, its process and socket state
remain tracked, replacement startup is rejected, and one daemon reaper performs
final process and filesystem cleanup. Caller interruption is restored only
after the forced wait. JVM tests use readiness handshakes for forced and
interrupted paths and a stubborn process for the timeout result. JDK 26 app
unit/lint and exact x86_64/AArch64 manager builds pass.

Android presentation now retains content validity and one conservative stale
damage union independently for each of its three reusable `AHardwareBuffer`
slots. Fresh, resized, invalidated, and evidence-free slots receive a full copy.
Otherwise a selected slot converts only the physical rectangle covering every
frame that slot missed, while `ASurfaceTransaction` receives the current frame's
damage rectangle. Logical compositor damage is scaled outward to physical frame
coordinates. Copy or unlock failure invalidates the slot before reuse, so a
partially written buffer can never become a partial-copy baseline. Host tests
cover scaling, full initialization, clean reuse, stale union, and invalidation;
the full workspace and strict Clippy gates plus rebuilt exact x86_64 and AArch64
Android probes pass. CPU conversion remains damage-driven until direct client
dmabuf import removes it.

Untrusted Wayland surface and frame-callback growth is now bounded. One client
can retain at most 128 surfaces and one compositor 256. Callback ownership is
limited to 64 for any one surface, 128 per client, and 256 total across pending,
synchronized-cache, and presentation phases. Admission first removes dead
callback resources. Surface destruction completes and drains callbacks from
every ownership phase, so churn
cannot strand protocol objects or quota. Real protocol clients verify pending,
synchronized-cache, and presentation cleanup, then exercise three independent
fatal boundaries: callback 65 on one surface, surface 129, and callback 129
distributed across surfaces. Two-client fixtures then saturate the 256-surface
and 256-callback global limits independently and prove a third client fails
closed. After the offending and quota-holding clients disconnect, a final client
reconnects to the same compositor, commits, and has its callback presented. The
full workspace and strict Clippy gates plus rebuilt exact x86_64 and AArch64
Android probes pass.

Pending subsurface order and XDG configure state are also bounded without
discarding compositor state. Repeated `place_above` and `place_below` requests
update one final-order snapshot containing only the parent's bounded child
surfaces. A parent commit applies that snapshot, the old vectors become
reusable storage, and subsurface or surface destruction removes stale children
from committed and pending order. Each XDG surface retains at most 64 sent,
unacknowledged configures and one latest-wins deferred compositor configure.
Acknowledging a valid serial drains it and every predecessor, then immediately
sends the deferred configure. Deferred output sizes remain visible to layout
and input mapping while backpressured. A client request that would exceed the
64-configure boundary is rejected by disconnecting only that client before its
requested window state changes. A real protocol fixture applies 3,072
sibling-relative order requests, crosses above/below lists, reuses the retained
snapshot, destroys a referenced pending sibling, saturates the configure
boundary, replaces deferred state twice, acknowledges and receives the latest
state automatically, verifies cleanup after overflow, and presents a callback
for a final healthy client. The full workspace and strict Clippy gates plus
rebuilt exact x86_64 and AArch64 Android probes pass.

Wayland SHM descriptor, buffer, and client-surface frame memory now has explicit
ownership quotas. A client can retain at most 16 SHM pools and 128 SHM buffers;
the compositor-wide limits are 32 and 256. Each pool is capped at 128 MiB, with
256 MiB per client and 512 MiB total across pool creation and growth. Potential
buffer patch storage uses the same byte limits. Destroying a `wl_shm_pool` or
`wl_buffer` resource does not release its charge while a buffer or pending
surface attachment still owns the backing descriptor. The charge disappears
only when the final retained owner does. Committed and synchronized-cache frame
chains deduplicate shared source rasters and are capped at 512 MiB per surface
and client and 1 GiB total. Before valid replacement work allocates, the
compositor checks the transient union of current and projected snapshot,
transform, and viewport chains. Any individual raster allocation is capped at
128 MiB, including viewport destinations. Compositor-owned output and popup-base
rasters remain outside these client quotas and follow the configured Android
output size. Invalid geometry still reaches its normal protocol error rather
than being reclassified as quota exhaustion. Real
protocol clients verify pool 17 and buffer 129 rejection, two-client saturation
of the 32-pool and 256-buffer global boundaries, exact 256 MiB pool growth,
rejected one-byte growth, retention after protocol-object destruction, release
after the pending attachment commits, and complete cleanup after each offending
client disconnects. The full workspace and strict Clippy gates plus rebuilt
exact x86_64 and AArch64 Android probes pass.

Wayland clipboard and drag protocol resources are bounded independently of
payload-transfer limits. One client may retain 16 data sources, eight data
devices, and 64 generated data offers; compositor-wide limits are 32, 16, and
128. A source can retain at most 32 unique MIME types, 256 bytes for one type,
and 4 KiB in aggregate. Repeated identical MIME offers do not consume quota.
Admission removes dead resources before counting, explicit source destruction
and device release make capacity reusable, and an offer-hoarding client
is disconnected before another offer is allocated. A real protocol fixture
holds each exact per-client boundary, destroys and replaces a source and device,
rejects source 17, device nine, MIME 33, and offer 65, verifies every MIME
count edge, checks cleanup after each offending client, and creates a source from
a final healthy client. Direct boundary tests cover exact and overflowing MIME
length and aggregate-byte limits plus per-client/global admission arithmetic.
The full workspace and strict Clippy gates plus rebuilt exact x86_64 and AArch64
Android probes pass.

Retained Wayland input and region protocol objects now have explicit ownership
quotas. One client may retain eight pointers, four keyboards, four touches,
four text inputs, eight relative pointers, eight combined swipe, pinch, and hold
gestures, and 64 regions. The corresponding compositor-wide limits are 16,
eight, eight, eight, 16, 16, and 128. Admission removes dead resources before
counting. Explicit release or destruction immediately restores capacity, while
overflow disconnects only the offending client. A real protocol fixture holds
every exact per-client boundary, mixes all three gesture types under one shared
quota, destroys and replaces each resource category, rejects the next pointer,
text input, gesture, relative pointer, and region, verifies cleanup, and creates
a pointer from a final healthy client. A second fixture uses two independent
clients to saturate the global pointer boundary, rejects only a third client,
and proves both holders remain connected. Direct tests cover every
per-client/global admission pair. The full workspace and strict Clippy gates
plus rebuilt exact x86_64 and AArch64 Android probes pass.

Wayland client admission is bounded before protocol-resource quotas apply. One
compositor retains at most 32 active clients. Filesystem-socket admission may
consume 24 slots, reserving eight for clients supplied through the runtime's
owned-descriptor path. Each compositor dispatch accepts at most eight queued
socket clients and inserts them directly without building a temporary stream
collection; further connections remain in the kernel's bounded listen backlog.
Backend disconnect callbacks release both total and source-specific capacity,
while failed insertion rolls back its reservations. Host fixtures fill socket
capacity in exact eight-client batches, verify filesystem backpressure and
reserved descriptor admission, disconnect one holder and accept the queued
replacement, fill all 32 descriptor-adopted slots, reject slot 33, and reuse a
released slot. The full workspace and strict Clippy gates plus rebuilt exact
x86_64 and AArch64 Android probes pass.

Wayland output bindings now follow the compositor's single-physical-output
model. Each of the at most 32 active clients may retain one `wl_output`, making
32 the exact compositor-wide ceiling without denying a first binding to any
admitted client. A repeated binding disconnects only that client. Explicit
release and client disconnect restore capacity. Destruction removes an entered
output only from same-client surfaces because Wayland protocol IDs are local to
one connection and can collide across clients. A real protocol fixture proves
the exact per-client boundary, release and replacement, repeated-bind
rejection, saturation by all 32 filesystem and descriptor-adopted clients,
same-numbered output cleanup isolation across their surfaces, complete cleanup,
and a successful final binding and round trip. The full workspace and strict
Clippy gates plus rebuilt exact x86_64 and AArch64 Android probes pass.

XDG popup-positioner resources are now bounded independently of surfaces. One
client may retain 64 `xdg_positioner` objects and the compositor may retain 128.
Admission removes dead resources before counting. Explicit destruction and
client disconnect remove the retained object and update the existing diagnostic
count. A real protocol fixture holds the exact per-client boundary, destroys and
replaces one positioner, rejects positioner 65, uses two independent clients to
saturate the global boundary, rejects positioner 129 without disconnecting either
holder, verifies complete cleanup, and creates a final healthy positioner. The
full workspace and strict Clippy gates plus rebuilt exact x86_64 and AArch64
Android probes pass.

Cursor-shape protocol devices are also bounded. One client may retain eight
`wp_cursor_shape_device_v1` objects and the compositor may retain 16 across
pointer and tablet targets. Both manager creation requests use the same retained
resource quota. Explicit destruction restores capacity immediately, and client
disconnect removes every owned device. A real pointer-protocol fixture holds the
exact per-client boundary, destroys and replaces one device, rejects device
nine, uses two independent clients to saturate the global boundary, rejects
device 17 without disconnecting either holder, verifies complete cleanup, and
creates a final healthy device. The full workspace and strict Clippy gates plus
rebuilt exact x86_64 and AArch64 Android probes pass.

Bindings to advertised Wayland globals now share one ownership quota. A client
may retain 16 bindings across the compositor's 14 interfaces, leaving two slots
for compatible repeated binding. The 32-client admission ceiling makes 512 the
exact aggregate maximum. Every accepted bind records its client-local object
identity. Explicit protocol destruction and client disconnect remove that
identity; binding 17 disconnects before interface-specific state or events are
published. A real protocol fixture binds all 14 interfaces, consumes both spare
slots, destroys and replaces a manager binding, rejects binding 17, fills all
512 slots with 24 filesystem and eight descriptor-adopted clients, verifies
single-holder and complete cleanup, and reconnects at exact capacity. The
lifecycle design was checked against libwayland's resource destruction order,
KWin's descriptor-created client path and connection-buffer controls, and
Hyprland's protocol ownership and retained bound-output handling. The full
workspace and strict Clippy gates plus rebuilt exact x86_64 and AArch64 Android
probes pass.

Wayland XDG-surface and subsurface role objects now have explicit ownership
quotas matching their backing-surface ceiling. One client may retain 128
`xdg_surface`, 128 `xdg_popup`, and 128 `wl_subsurface` objects; each
compositor-wide limit is 256. The equal `wl_surface` limits make the popup
ceiling intrinsic while preserving an independent defensive admission check.
Role validation happens before quota admission, and destroying a live role
object restores object capacity without erasing the surface's permanent XDG
popup, XDG toplevel, or subsurface role. `wl_surface.destroy` reports a defunct
role object only while the corresponding toplevel, popup, or subsurface object
is still alive. Real protocol fixtures hold exact XDG-surface and subsurface
capacity, destroy and replace resources, remove a hierarchy parent while its
children remain valid, reject XDG surface 129 after all backing surfaces have
been destroyed, reject parent-first destruction with live role objects, and
accept role-object-first teardown. Direct tests tie all six role-object limits
to the existing per-client and compositor-wide surface bounds. The full
workspace and strict Clippy gates plus rebuilt exact x86_64 and AArch64 Android
probes pass.

Auxiliary Wayland surface resources now remain bounded even after their backing
objects become inert. One client may retain 128 combined locked and confined
pointer constraints, 128 `wp_viewport` objects, and 128 fractional-scale
feedback objects; each compositor-wide ceiling is 256. Pointer-constraint
activation eligibility is tracked separately from live protocol ownership, so
an ended one-shot constraint cannot reactivate but remains charged until its
resource is destroyed. Dead backing surfaces and pointers stop functional
activation without prematurely releasing child-object quota. Viewports now have
compositor-level ownership tracking equivalent to fractional-scale feedback. A
real protocol fixture fills each exact per-client boundary, mixes locked and
confined constraints, destroys and replaces one object, removes every backing
surface and pointer while the children remain live, independently rejects object
129 for all three categories, and verifies cleanup before the next client. Direct
tests tie all six limits to the existing surface ceilings. The full workspace
and strict Clippy gates plus rebuilt exact x86_64 and AArch64 Android probes
pass.

Retained XDG toplevel metadata now has explicit byte ceilings. A title accepts
at most 2 KiB of UTF-8 and an app ID at most 1 KiB. Combined with the existing
32-toplevel limit, client-supplied retained identity text cannot exceed 96 KiB.
An oversized setter disconnects only its client through the resource-limit path
before the stored value or window-change serial is mutated. A real protocol
fixture accepts both exact boundaries, rejects title and app-ID
boundary-plus-one requests independently, verifies cleanup after each offender,
and leaves a final healthy client connected. The full workspace and strict
Clippy gates plus rebuilt exact x86_64 and AArch64 Android probes pass.

Android audio playback control now applies bounded latest-state backpressure.
The single worker may retain one running Pulse suspension command and one
replaceable latest request; obsolete queued transitions cannot accumulate in an
unbounded executor. Every control subprocess starts draining its merged output
before the bounded wait, retains at most a valid 512-byte UTF-8 prefix, and
discards the remaining bytes. Timeout cleanup force-kills and reaps the process
before retry, closes its stream, and boundedly joins the daemon drainer. A
helper that cannot be reaped blocks further helper creation instead of allowing
process accumulation. Startup waits for Pulse's completed-daemon diagnostic
rather than treating an early socket path as control readiness. The server-log
drainer now rejects irrelevant lines before taking the startup monitor, which
removes the lock inversion that stalled its process pipe on Samsung, and
playback-input churn coalesces one main-thread focus reconciliation instead of
blocking that drainer on a control subprocess. A known final session close
cancels the deferred suspension and force-reaps the server instead of asking
Samsung's AAudio sink to suspend while its process tree is already closing.
Deterministic JVM tests prove
that a 100-task burst
retains only the latest pending task, 64 KiB output drains fully while retaining
512 bytes, pipe drainage starts before the consumer waits, and an incomplete
multibyte tail is removed. The JDK 26 app unit/lint gate passes. Current direct
Gradle APKs also pass the manager-owned 48 kHz stereo output gate with
`pavucontrol` on the x86_64 emulator and SuperTux on the physical AArch64
Samsung, including private-runtime teardown within the 15-second gate. The
emulator then passes a separate non-audio app-shell denial probe.

Audio-runtime cleanup now streams at most 32 matching cache roots instead of
allocating an uncapped `listFiles()` array. Before deleting one runtime, it
collects a no-follow postorder bounded to 160 descendants and depth 3. Entry
161 or depth 4 rejects the complete tree before mutation; a valid nested tree
still deletes normally. Direct JVM tests prove both overflow cases retain every
path and byte, plus successful bounded cleanup. The complete JDK 26 app
unit/lint gate and exact x86_64/AArch64 manager builds pass. On physical
Samsung, explicit app-shell Service startup encountered an exact test-owned
161-file audio runtime, logged the entry-limit rejection, retained all 161
files, and allowed exact fixture cleanup afterward. Runtime evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/audio-runtime-cleanup-bound-20260804`;
the subsequent stable 1080×2202 Foot full-device gate is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/audio-runtime-cleanup-bound-foot-20260804`.

Long-lived Pulse server stdout and stderr now use fixed 1 KiB read and 512-byte
line buffers instead of `BufferedReader.readLine()`. Overlong lines are consumed
completely while retaining one prefix. Truncation is explicit, and truncated
lines remain log-visible but cannot become readiness or playback-input events.
LF, CR, CRLF, blank, and final unterminated line behavior remains intact. JVM
tests cover a 64 KiB hostile line, following valid input, complete consumption,
truncation state, and mixed delimiters. JDK 26 app unit/lint and exact
x86_64/AArch64 manager builds pass.

Camera-runtime cleanup now validates its complete no-follow postorder before
deleting anything. The existing 160-descendant and depth-3 ceilings therefore
reject entry 161 or depth 4 without partially deleting earlier paths; valid
nested runtimes still delete normally, and owned live paths remain excluded.
Direct JVM regressions prove valid removal plus byte/path-exact retention for
both overflow classes. The complete JDK 26 app unit/lint gate and exact
x86_64/AArch64 manager builds pass. On physical Samsung, explicit app-shell
Service startup encountered an exact test-owned 161-file camera runtime, logged
the entry-limit rejection, retained all files, and allowed exact fixture cleanup
afterward. Runtime evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/camera-runtime-atomic-cleanup-20260804`;
the subsequent stable 1080×2202 Foot full-device gate is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/camera-runtime-atomic-cleanup-foot-20260804`.

Camera-runtime diagnostics now use fixed 1 KiB read and 512-byte line buffers.
An overlong helper line is drained completely while only its bounded prefix is
logged, and the next line remains independently visible. Teardown retains any
helper or log drainer that cannot be reaped. A process-wide lifecycle registry
blocks same-session replacement across service generations and excludes every
live runtime directory from stale cleanup. Deterministic JVM tests cover
registry retention and eventual release, same-session ownership, stale-cleanup
exclusion, a 64 KiB hostile line, a following CRLF line, and a final
unterminated line. The JDK 26 app unit/lint gate passes. Direct Gradle APKs also
pass the current Snapshot camera gate on the x86_64 emulator and AArch64
Samsung, including the private PipeWire stream, GTK4 Cairo environment, Linux
frame presentation, and full-device pixel inspection.

Private D-Bus and XDG portal diagnostics now use fixed 1 KiB read and 512-byte
line buffers instead of unbounded `BufferedReader.readLine()` allocation.
Overlong tails are discarded while helper output remains fully drained. LF,
CR, CRLF, and final unterminated lines preserve prior line semantics. A
deterministic JVM test drains a 64 KiB hostile line, publishes only its bounded
prefix, and preserves following mixed-delimiter lines. The JDK 26 app unit/lint
gate and current exact-APK private portal startup within the Snapshot camera
gate pass on the x86_64 emulator and AArch64 Samsung.

Private portal broker request parsing now enforces the protocol's six-field
maximum while splitting. The prior `String.split` created every tab-delimited
substring in the admitted 16 KiB ASCII line before operation handlers checked
their exact field counts. The replacement preallocates six slots, admits the
largest current six-field secret request, and rejects field seven without
retaining the remaining request as fields. A direct JVM regression covers the
exact limit, field seven, and an exact 16,383-character hostile line containing
8,192 one-character fields. The complete app unit/lint gate and both exact-ABI
builds pass. The subsequent physical-Samsung Foot regression retained stable
Android and Linux processes, a 34 px font, 126 px controls, and visible command
output in inspected full-device frames. Evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/portal-request-field-bound-foot-20260804`.

Authenticated app-shell callback responses now use the same bounded splitter.
Secret read responses admit four fields, secret list/catalog responses admit
two, and camera capture responses admit four; each path preallocates only that
schema and rejects the first extra field. This removes the second unbounded
substring/list allocation after Binder has materialized the framework-owned
response string under the existing 16 KiB secret or 128-character camera
ceiling. A direct JVM regression covers valid four-field input, extra fields
against both schema sizes, and an exact 16,384-character tab flood. The complete
app unit/lint gate and both exact-ABI builds pass. The subsequent physical
Samsung Foot regression retained stable Android and Linux processes, a 34 px
font, 126 px controls, and visible command output in inspected full-device
frames. Evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/portal-response-field-bound-foot-20260804`.

HTTP(S) portal URI and logical-home file-path validation now enforces the 4 KiB
UTF-8 policy before allocating a complete encoded copy. The allocation-free
code-point scan stops at byte 4,097 and rejects malformed surrogate pairs.
Logical paths likewise inspect slash boundaries directly instead of splitting
every component before rejecting exact `.` or `..` traversal. Direct JVM tests
cover exact ASCII and multibyte byte limits, overflow, malformed high/low
surrogates, a delimiter-heavy safe path, and nested traversal. The complete app
unit/lint gate and both exact-ABI builds pass. The subsequent physical-Samsung
Foot regression retained stable Android and Linux processes, a 34 px font,
126 px controls, and visible command output in an inspected 1080×2202 frame.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/portal-uri-allocation-bound-foot-20260804`.

Generated-app-shell browser dispatch now applies the same allocation-free 4 KiB
UTF-8 boundary to portal callbacks and direct manager requests. The policy was
extracted from the Activity so direct JVM tests can prove exact ASCII and
multibyte admission, one-code-point overflow, malformed high/low surrogate
rejection, and the existing host-bearing HTTP(S)-only, no-userinfo, valid-port
rules. Rejected text fails before URI parsing or Android `ACTION_VIEW`, without
creating a complete encoded copy. The complete manager, launcher-template, and
Builder source unit/lint gate plus both exact-ABI manager builds pass. The
subsequent physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and visible command output in an inspected full-device
frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/generated-browser-uri-bound-foot-20260804`.

Generated-app-shell Android document names now share one directly tested,
incremental policy. Provider display names, multi-document results, Save As
suggestions, and manager result transfer admit at most 255 UTF-16 units and 255
UTF-8 bytes, reject exact `.`/`..`, separators, controls, and malformed
surrogates, and stop at byte 256 instead of constructing a complete encoded
copy. Direct launcher-template JVM tests cover exact ASCII and multibyte
boundaries, overflow, unsafe names, and malformed high/low surrogates. The
complete manager, launcher-template, and Builder source unit/lint gate plus both
exact-ABI manager builds pass. The subsequent physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and visible command
output in an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/generated-document-name-bound-foot-20260804`.

Generated-app-shell accessibility action text and print titles now use a shared
allocation-free UTF-8 length policy before Binder transfer or print staging.
Accessibility payloads retain their 4,096-UTF-16-unit and 16 KiB byte limits;
print titles retain 256 UTF-16 units and 512 bytes. The walker admits an empty
accessibility payload, exits on the first overflowing code point, and rejects
malformed surrogate pairs instead of allowing encoder replacement. Direct
launcher-template JVM tests cover empty text, exact ASCII/two-byte/four-byte
boundaries, overflow, negative limits, and malformed high/low surrogates. The
complete manager, launcher-template, and Builder source unit/lint gate plus both
exact-ABI manager builds pass. The subsequent physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and visible command
output in an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/generated-accessibility-print-text-bound-foot-20260804`.

The generated app shell's Android accessibility provider now applies that same
allocation-free policy at framework `ACTION_SET_TEXT` ingress. Oversized or
malformed text therefore fails before reaching the Activity callback and its
independent second validation boundary; the provider no longer creates a
complete encoded copy while checking the admitted text. Shared JVM coverage now
includes exact and overflowing 16 KiB ASCII and four-byte inputs alongside the
malformed-surrogate cases. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/generated-accessibility-provider-text-bound-foot-20260804`.

Manager-side app-shell callbacks now reuse an allocation-free UTF-8 policy for
production and debug print titles, HTML-derived clipboard text, and Android
document result names. Their existing 512-byte, 64 KiB, and 255-byte ceilings
are enforced while walking code points instead of after complete encoded-copy
allocation; malformed surrogates fail before callback transfer or retention.
The shared app JVM tests distinguish nonempty URI/name admission from
empty-permitted generic text and cover exact multibyte limits, overflow,
negative limits, and malformed Unicode. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and visible
command output in an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/manager-launcher-text-bound-foot-20260804`.

Manager Binder ingress for generated-app-shell accessibility actions now applies
the same allocation-free policy before queuing text. Its independent
4,096-UTF-16-unit and 16 KiB ceilings reject overflow and malformed surrogates
without constructing a complete encoded copy after Parcel materialization.
App JVM coverage includes exact and overflowing 16 KiB ASCII and four-byte
shapes. The complete source unit/lint gate and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in an
inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/manager-accessibility-text-bound-foot-20260804`.

The remaining manager-side Android document-name boundaries now reuse
allocation-free 255-byte UTF-8 admission. Portal open/save/directory result
names and exported DocumentsProvider visible entries reject overflow and
malformed surrogates before retention or publication without creating complete
encoded copies. Provider policy continues to exclude hidden, traversal,
separator, control, and bidirectional-spoof names. Direct JVM tests cover exact
ASCII and multibyte limits, overflow, hidden/separator names, and malformed
high/low surrogates. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/provider-portal-document-name-bound-foot-20260804`.

Runtime-visible Android folder labels, imported-file names, and launcher-registry
display names now use allocation-free UTF-8 admission at their 128-byte,
255-byte, and 256-byte ceilings. Overflow and malformed surrogates fail before
status or registry retention without complete encoded copies, while existing
hidden, traversal, separator, control, and bidirectional-spoof rules remain.
App JVM tests add exact and overflowing 128-byte ASCII and multibyte labels to
the shared boundary coverage. The complete source unit/lint gate and both
exact-ABI manager builds pass. The subsequent physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and visible command
output in an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/runtime-visible-label-bound-foot-20260804`.

Project-synchronization history validation now uses the existing
allocation-free protocol UTF-8 length walker instead of encoding complete
temporary copies for project, message, and conflict-path limit checks. The
actual serializer creates its one required persisted field byte array only
after the complete entry passes validation. Direct history JVM tests cover an
exact 128-byte multibyte project name, overflow, malformed Unicode, round-trip,
corruption, trailing data, and unsafe conflict paths. The complete source
unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and visible command output in an inspected full-device frame.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-history-length-bound-foot-20260804`.

Generated Android app-shell labels now enforce their 128-UTF-16-unit and
512-byte policy without first allocating a complete encoded copy. The
incremental check rejects malformed surrogates before APK metadata construction
while preserving blank, control, and bidirectional-spoof rejection. Direct app
JVM tests cover exact ASCII and three-byte 128-unit labels, UTF-16 overflow,
blank text, bidi controls, and malformed high/low surrogates. The complete
source unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and visible command output in an inspected full-device frame.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-label-bound-foot-20260804`.

Package-job messages are now sanitized and truncated in one bounded pass. The
former path replaced the complete input three times, then repeatedly allocated
and UTF-8-encoded shrinking prefixes. The replacement retains at most 192
UTF-16 units and UTF-8 bytes in one bounded builder, translates tab/CR/LF to
spaces, stops reading after the retained prefix, and falls back to the existing
generic message on malformed Unicode. Direct JVM tests cover empty/control
input, 10,000-character ASCII, exact three-byte and four-byte limits, and
malformed high/low surrogates. The complete source unit/lint gate and both
exact-ABI manager builds pass. The subsequent physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and visible command
output in an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-job-message-bound-foot-20260804`.

The latest native package-job record now decodes into exactly nine preallocated
fields instead of trimming and limit-splitting the complete 16 KiB response.
Field ten fails before retention, terminal LF runs are skipped by index,
embedded CR/LF is rejected, and empty fields remain available for the existing
identifier, operation, state, progress, repository, package, and message
validation. Direct JVM tests cover ordinary and empty-field records, terminal
newlines, field underflow and overflow, internal line breaks, and an exact
16 KiB tab flood. The complete source unit/lint gate and both exact-ABI manager
builds pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-job-record-parser-bound-foot-20260804`.

Native package-runtime probe output now scans by line indexes instead of
creating and trimming one string per line before locating the first pacman
version. LF, CRLF, CR, and a final unterminated line remain supported; only the
selected `Pacman v` line is constructed and trimmed. Direct JVM tests cover
first-match selection, every delimiter, missing and cross-line markers, and an
exact 16 KiB line flood. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-runtime-probe-parser-bound-foot-20260804`.

The visible AUR build phase now comes from one reverse line scan instead of
creating and trimming every line in the bounded Builder log. The scanner
supports LF, CRLF, CR, blank tails, and an unterminated final line, then
constructs only the last nonempty phase and truncates it to 160 UTF-16 units.
Direct JVM tests cover every delimiter, trimming, blank input, truncation,
invalid limits, and an exact 8 KiB line flood. The complete source unit/lint
gate and both exact-ABI manager builds pass. The subsequent physical-Samsung
Foot regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in inspected full-device frames. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-build-phase-parser-bound-foot-20260804`.

Native launcher removal and publication claims now decode into exactly three
and nine preallocated fields instead of trimming and splitting their complete
4 KiB outputs. Fields four and ten fail before retention, terminal LF runs are
skipped by index, embedded CR/LF is rejected, and the existing package,
descriptor, generation, capability, digest, and MIME validation remains
unchanged. Direct JVM tests cover both exact schemas, terminal newlines, empty
fields, underflow and overflow, internal line breaks, and exact 4 KiB tab
floods. The complete source unit/lint gate and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-claim-parser-bound-foot-20260804`.

Native portal-folder import, project-mirror completion, and document-import
reports now decode into their exact three- or two-field schemas instead of
splitting complete 8 KiB storage outputs before checking field count. The next
field fails before retention, CR/LF is rejected, and the existing safe-name,
entry-count, byte-count, and progress reconciliation remains at the service
boundary. Direct JVM tests cover every schema, empty fields, underflow and
overflow, line breaks, and exact 8 KiB tab floods. The complete source
unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/storage-report-parser-bound-foot-20260804`.

Native launcher authorization now decodes into exactly six preallocated fields
instead of copying away its optional terminal LF and limit-splitting the
complete 3 KiB response. Field seven fails before retention, embedded or
repeated line breaks are rejected, and the existing authorization version,
visibility, generation, capability, label, and MIME checks remain unchanged.
Direct JVM tests cover valid terminated and unterminated responses, empty
fields, underflow and overflow, line breaks, and an exact 3 KiB tab flood. The
complete source unit/lint gate and both exact-ABI manager builds pass. The
subsequent physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-authorization-parser-bound-foot-20260804`.

Native package-compatibility state now decodes into exactly seven preallocated
fields instead of counting newlines, copying away the required terminal LF,
and limit-splitting the complete 16 KiB response. Field eight fails before
retention, embedded CR/LF is rejected, and the existing canonical count,
capability, status, diagnostic, package-name, and consistency validation
remains unchanged. Direct JVM tests cover valid and contradictory states,
required termination, canonical numbers, unsafe names, field overflow, and an
exact 16 KiB tab flood. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-compatibility-parser-bound-foot-20260804`.

Reviewed AUR candidate state now decodes into exactly two fields by delimiter
indexes instead of limit-splitting the complete 16 KiB response. A third field
fails before retention, CR/LF is rejected, and the empty installed-version
field remains available only for the existing `available` state while
installed/update/different consistency checks remain unchanged. Direct JVM
tests cover populated and empty-version states, missing and excess fields, line
breaks, and an exact 16 KiB tab flood. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in inspected full-device frames. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-candidate-state-parser-bound-foot-20260804`.

Durable pending-package mutation decoding now retains at most four parsed
fields instead of splitting the complete 16 KiB record and then joining its
status fields into another copy. Install recovery still accepts only three
fields or an exact fourth `rollback` field, removal accepts exactly three, and
a fifth delimiter fails before another field is retained. The persisted status
is constructed once from the validated record suffix. Direct JVM tests cover
exact rollback admission, removal, malformed records, limit-plus-one input, and
an exact 16 KiB tab flood. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/pending-package-mutation-parser-bound-foot-20260804`.

Native package-launcher review decoding now preallocates and retains exactly the
protocol's 19 fields instead of copying away its terminal newline and splitting
the complete 16 KiB output before validating field count. A twentieth field is
rejected before its substring is retained. Existing launcher count, topology,
capability, observation, and state consistency rules are unchanged. Direct JVM
tests cover valid ready, pending, and bridge records; contradictory and
over-limit values; and an exact 16 KiB tab flood. The complete source unit/lint
gate and both exact-ABI manager builds pass. The subsequent physical-Samsung
Foot regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in an inspected full-device frame. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-launcher-review-parser-bound-foot-20260804`.

Native package-cache pages now parse directly into at most 32 rows of exactly
five preallocated fields. The previous path copied away terminal newlines,
created a string for every row, split every row into fields, and enforced the
32-row bound only afterward. Row 33 and field six now fail before retention;
the existing service still validates field meaning, ordering, byte totals,
aggregation, and summary consistency. Direct JVM tests cover normal and
terminal-newline pages, exact row admission, row and field overflow, empty
input, and an exact 16 KiB tab flood. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in an inspected full-device frame. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-cache-page-parser-bound-foot-20260804`.

Native package-cache summaries now decode directly into their exact entry-count
and byte-count values instead of trimming and splitting the complete 16 KiB
output before enforcing the three-field schema. A fourth field fails before
construction, optional terminal LF runs are skipped by index, and the existing
zero-to-1,024 entry and nonnegative-byte checks remain at the service boundary.
Direct JVM tests cover valid limits, field underflow and overflow, empty,
malformed, and overflowing values, internal newlines, and an exact 16 KiB tab
flood. The complete source unit/lint gate and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-cache-summary-parser-bound-foot-20260804`.

Package-cache pagination now passes the summary's remaining declared-entry
count into every page parse. Previously, a final page could retain and
aggregate up to 31 records past the zero-to-1,024 summary count before offset
reconciliation rejected it. The parser now preallocates to the smaller of 32
and the remaining count and rejects its next row before parsing or retention;
the complete snapshot collections preallocate to the validated total. Direct
JVM tests cover exact custom admission, the next row, invalid custom bounds,
default 32-row behavior, malformed fields, and hostile input. The complete
source unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and visible command output in an inspected full-device frame.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-cache-remaining-page-bound-foot-20260804`.

Native desktop-entry pages now parse directly into one six-field header and at
most 256 rows of exactly ten fields. The previous path copied away the terminal
newline, split the complete 16 KiB page into lines, copied its row tail, and
split each row before enforcing the desktop-entry count. Header field seven,
row 257, row field 11, blank internal rows, and unterminated pages now fail
before excess retention. Existing identity, ordering, argument, MIME, package,
pagination, and scan-summary validation remains in the service. Direct JVM
tests cover header-only and populated pages, exact row admission, header/row
field and row-count overflow, missing termination, blank rows, and exact 16 KiB
newline and tab floods. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/desktop-entry-page-parser-bound-foot-20260804`.

Desktop-entry Exec arguments and MIME declarations now use allocation-free
bounded scans instead of splitting every unit-separator token and copying then
splitting the semicolon payload before applying grammar. Argument token 33 and
MIME type 17 fail immediately, mirroring the package runtime's 32-argument and
16-MIME producer bounds. Empty tokens, malformed literals, missing MIME
termination, and types without `/` still fail. Direct JVM tests cover every
fixed argument, literals, exact count boundaries, malformed and empty tokens,
exact MIME boundaries, and exact 16 KiB delimiter floods. The complete source
unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and visible command output in an inspected full-device frame.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/desktop-entry-spec-bound-foot-20260804`.

Native project-synchronization plan paths now validate incrementally instead of
splitting the complete decoded path before enforcing its 64-segment depth
limit. The parser retains no segment list, constructs and validates at most 64
component substrings, and rejects segment 65 before constructing it. Empty,
leading, trailing, traversal, unsafe-name, UTF-8, control, bidirectional-spoof,
and malformed-Unicode rules remain unchanged. Direct JVM tests cover exact 64-
and 65-segment paths, empty and traversal components, unsafe Unicode, and a
4,095-character delimiter-heavy input. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in inspected full-device frames. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-path-bound-foot-20260804`.

Persisted project-synchronization history conflict paths now reuse the bounded
protocol path validator instead of splitting every slash-delimited component
before checking safety. Validation retains no segment list, accepts exactly 64
safe components, and rejects component 65 or a 4 KiB delimiter flood without
unbounded component retention. Existing UTF-8, traversal, empty-component,
control, bidirectional-spoof, and malformed-Unicode rules remain unchanged.
Direct JVM tests cover exact 64- and 65-component paths, the delimiter flood,
round-trip, corruption, and traversal. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in inspected full-device frames. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-history-path-bound-foot-20260804`.

AUR install-script paths now validate in one allocation-free character scan
instead of splitting the caller-bounded 4 KiB path and regex-matching every
component. Relative-path, nonempty-component, traversal, 240-character
component, and ASCII filename-grammar rules remain unchanged. Valid
delimiter-heavy input does not retain component substrings. Direct JVM tests
cover safe nested paths, exact and overflowing component lengths, traversal,
separators, invalid characters, and a 4,095-character path. The complete source
unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font, 126
px controls, and visible command output in inspected full-device frames. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-install-script-path-bound-foot-20260804`.

Native launcher-icon logical paths now validate in one allocation-free scan
instead of splitting every slash-delimited component before opening the icon.
The same predicate enforces the existing root-relative, 240-character,
nonempty-component, and traversal rules at publication admission and file
loading. Canonical root containment, no-follow opening, digest verification,
and decoded-image bounds remain unchanged. Direct JVM tests cover nested and
Unicode paths, exact and overflowing total lengths, relative paths, repeated
and trailing separators, and traversal. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in inspected full-device frames. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-icon-path-bound-foot-20260804`.

Generated-app-shell template and signed-APK ZIP entry names now validate in one
allocation-free scan instead of splitting every slash-delimited component. The
scanner preserves the 240-character ceiling plus relative, nonempty-component,
traversal, and backslash rejection before extraction or signed-output
acceptance. Direct JVM tests cover ordinary nested and Unicode names, exact and
overflowing lengths, absolute and trailing paths, repeated separators,
traversal, and backslashes. The complete source unit/lint gate and both
exact-ABI manager builds pass. The subsequent physical-Samsung Foot regression
retained stable processes, a 34 px font, 126 px controls, and visible command
output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-entry-path-bound-foot-20260804`.

Generated-app-shell accessibility action responses now use the shared bounded
portal field parser instead of limit-splitting the complete response. The
parser retains exactly four preallocated fields, preserves the required empty
encoded field for internal refresh, and rejects field five before retention.
Direct JVM tests cover ordinary and trailing-empty four-field responses,
excess fields, invalid schema limits, and an exact 16 KiB tab flood. The
complete source unit/lint gate and both exact-ABI manager builds pass. The
subsequent physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/accessibility-response-parser-bound-foot-20260804`.

Package-detail status lines now scan and update by delimiter indexes instead of
materializing a line sequence and mutable list for launcher-review and
installed-version refreshes. Prefix detection allocates no line strings; a
successful replacement builds only the final LF-normalized text, while a
missing target returns the original string. Direct JVM tests cover LF, CRLF,
CR, mixed delimiters, exact prefix boundaries, first-match replacement, empty
target lines, trailing delimiters, and unchanged missing targets. The complete
source unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font, 126
px controls, and visible command output in inspected full-device frames. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-status-line-scan-foot-20260804`.

Installed-shell and direct-command null-separated UTF-8 requests now encode
into one exact bounded byte array instead of creating a sublist, one byte array
per argument, and an encoded-argument list before constructing the final
request. The preflight counts ASCII, multibyte, supplementary, separator, and
malformed-surrogate replacement bytes against the 16 KiB native ceiling before
allocation. Direct JVM tests prove byte-exact UTF-8 parity, skipped metadata
fields, malformed-surrogate parity with the JVM encoder, exact-limit admission,
overflow rejection, empty fields, and invalid caller bounds. The complete
source unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font, 126
px controls, and visible command output in inspected full-device frames. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/command-request-utf8-bound-foot-20260804`.

Generated-app-shell native clipboard reads now validate a successful length
against both direct-buffer capacity and the independent 64 KiB clipboard
policy before allocating a Java byte array or copying from the buffer. An
invalid successful length becomes rejected clipboard content while the tracked
transfer still completes instead of allocating from the native result. Shared
JVM boundary tests cover exact policy admission, policy-plus-one with spare
capacity, negative limits, limits above capacity, and invalid capacities. The
complete source unit/lint gate and both exact-ABI manager builds pass. The
subsequent physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/clipboard-native-length-bound-foot-20260804`.

The remaining manager-native positive-length copies now use the shared output
admission before Java allocation and direct-buffer reads. Package-runtime
probes, AUR provider candidates, installed-shell catalogs, and generic
package-cache or status UTF-8 results use the same tested capacity boundary
instead of ad hoc constant or buffer checks. A source audit confirms every
`outputLength`-sized manager byte array is admitted through
`checkedNativeOutputLength`. Shared tests cover zero, exact capacity,
capacity-plus-one, negative lengths, and invalid capacities. The complete
source unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font, 126
px controls, and visible command output in inspected full-device frames. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/remaining-native-length-admission-foot-20260804`.

Generated-app-shell camera, audio, GPU, and private D-Bus Unix socket paths now
use allocation-free bounded UTF-8 admission instead of encoding complete
temporary byte arrays only to check length. The shared policy reserves the
native terminator byte, stops at the first overflow, and rejects malformed
Unicode. Direct JVM tests cover exact ASCII and multibyte admission, one-byte
overflow, malformed surrogates, and invalid native path limits. The complete
source unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font, 126
px controls, and visible command output in inspected full-device frames. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-socket-path-utf8-foot-20260804`.

Generated-app-shell file portal URI output now validates the
`toASCIIString()` character count directly instead of allocating a redundant
US-ASCII byte array only to check the 4 KiB protocol ceiling. Because every
output character is ASCII, the character and encoded-byte counts are exact.
HTTP and logical-home path admission also reuse the shared allocation-free
UTF-8 walker instead of a duplicate implementation. Direct JVM tests cover
exact and overflowing ASCII URI expansion, percent-encoded Unicode and spaces,
traversal, exact ASCII and multibyte HTTP limits, controls, and malformed
surrogates. The complete source unit/lint gate and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/portal-file-uri-ascii-bound-foot-20260804`.

Signed generated-app-shell verification now streams each non-signature ZIP entry
through SHA-256 under the existing 4 MiB per-entry ceiling instead of first
retaining a complete entry byte array solely for hashing. The bounded digest
reader uses a fixed 16 KiB buffer, probes one byte beyond the ceiling, and
handles legal zero-progress streams without spinning. Direct JVM tests cover
empty and exact-limit inputs, limit-plus-one rejection, chunked and
zero-progress reads, and invalid limits. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/signed-entry-streaming-foot-20260804`.

Generated-app-shell template ZIP entry extraction now reuses the shared bounded
input reader instead of a separate `ByteArrayOutputStream` loop. The former loop
could spin indefinitely when an input stream legally returned zero from a bulk
read. The shared reader falls back to one bounded byte on zero progress, probes
one byte beyond the 4 MiB entry ceiling, and retains no oversized byte. Direct
JVM tests cover empty and exact input, limit-plus-one rejection, chunked reads,
and repeated zero-progress bulk reads; launcher assembly regressions also pass.
The complete source unit/lint gate and both exact-ABI manager builds pass. The
subsequent physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-entry-bounded-input-foot-20260804`.

Generated-app-shell binary Android-manifest string replacement now writes each
UTF-8 or UTF-16 string-pool record into one exactly sized output array. The
former path grew a `ByteArrayOutputStream` around the already encoded bytes and
then allocated a second complete output copy. Length-prefix sizing uses checked
arithmetic, preserves Android's one- or two-unit length formats, and leaves the
required terminator bytes in the exact result. Direct JVM tests prove byte-exact
ASCII, multibyte, supplementary, UTF-16LE, and two-byte UTF-8 length-prefix
encoding. The complete source unit/lint gate and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/binary-xml-exact-string-foot-20260804`.

Project-synchronization journal and history CRC32 encoding now computes the
checksum while writing through a bounded stream, appends it to the same backing
buffer, and creates only the final persisted copy. The former path retained a
complete body copy, rescanned it for CRC32, copied it into a second output
stream, and then copied that stream again; aggregate history overflow was
checked only after those allocations. Body writes now reserve the eight-byte
checksum trailer and reject the first byte beyond the 64 KiB journal or 128 KiB
history ceiling. Direct JVM tests cover exact-limit output, limit-plus-one,
single and bulk writes, invalid limits, maximum journal fields, oversized
aggregate conflict history, round-trip compatibility, and corruption. The
complete source unit/lint gate and both exact-ABI manager builds pass. The
subsequent physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-checksum-output-bound-foot-20260804`.

Native project-synchronization requests now preflight and write tab-separated
UTF-8 directly into the caller's reusable `ByteBuffer`. The former path first
joined every field into another complete string and then allocated its complete
encoded byte array before checking the 8 KiB protocol ceiling. The encoder now
counts separators and code points against both the native ceiling and actual
destination capacity, rejects malformed surrogates before clearing the buffer,
and emits ASCII, two-, three-, and four-byte sequences without intermediate
payload allocation. Direct JVM tests prove byte-exact parity with the standard
UTF-8 encoder, exact ASCII and supplementary limits, limit-plus-one rejection,
smaller destination rejection without mutation, and empty, tabbed, or malformed
field rejection. The complete source unit/lint gate and both exact-ABI manager
builds pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-request-utf8-bound-foot-20260804`.

Exact UTF-8 length checks no longer allocate complete encoded copies solely to
measure them. The shared malformed-Unicode-safe walker now returns the exact
byte count under an optional ceiling and remains the implementation behind the
existing boolean admission policy. Generated-app-shell stored-entry alignment
uses it for ZIP name length, while document-import and folder-import native
response checks compare the reported length without re-encoding the complete
response. A source audit finds no remaining production
`toByteArray(StandardCharsets.UTF_8).size` checks in the manager package.
Direct JVM tests cover empty, ASCII, two-, three-, and four-byte text, exact and
overflowing ceilings, negative limits, and malformed high and low surrogates;
launcher assembly regressions also pass. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/exact-utf8-length-foot-20260804`.

The manager DocumentsProvider now sends home and shell-startup open, create,
rename, and delete requests through the same bounded tab-separated UTF-8
encoder as project synchronization. It preflights the complete request against
the 4 KiB storage ceiling, allocates the exact direct JNI buffer, and writes
fields into it without first allocating a joined string and complete encoded
byte array. Empty fields, tabs, malformed Unicode, aggregate overflow, and a
smaller destination fail before buffer mutation. Direct JVM tests prove
byte-exact standard UTF-8 output into a direct buffer plus all rejection cases;
project-sync request regressions prove the shared encoder's 8 KiB use. The
complete source unit/lint gate and both exact-ABI manager builds pass. The
subsequent physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/provider-request-utf8-bound-foot-20260804`.

Selected package-cache cleanup requests now use the shared bounded delimiter
encoder with LF instead of sorting into another collection, joining the full
selection string, and allocating its complete UTF-8 byte array before creating
the direct JNI buffer. The caller thread copies and sorts the bounded selection
once, checks strict uniqueness and membership, and performs only an
allocation-free length preflight. The package worker allocates the exact direct
buffer and emits the request immediately before native cleanup; the synchronous
recovery path uses the same encoding. The shared encoder now supports any
non-NUL ASCII delimiter while rejecting delimiter-bearing fields, malformed
Unicode, invalid delimiters, and aggregate overflow before mutation. Direct JVM
tests prove LF byte parity, exact capacity, capacity-minus-one, embedded-LF,
multibyte, and invalid-delimiter behavior. The complete source unit/lint gate
and both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-cache-request-utf8-bound-foot-20260804`.

Manager package-command requests now encode directly into their exact or
reusable direct JNI buffers instead of first allocating complete UTF-8 byte
arrays and then copying them. This covers resolution, compatibility analysis,
launcher review, installed and available version state, installation size,
installed origin, durable job enqueue, ordinary package commands, install and
removal plans, pending-mutation inspection, and reviewed AUR candidate state.
Single-field requests use the new bounded direct `putUtf8`; two-field requests
reuse the tab-delimited encoder. Both preflight malformed Unicode and capacity
before clearing a destination. A source audit finds no remaining
`packageName.toByteArray(StandardCharsets.UTF_8)` request staging in the runtime
service. Direct JVM tests prove exact standard UTF-8 parity for ASCII,
multibyte, and supplementary text plus capacity-minus-one and malformed-input
rejection. The complete source unit/lint gate and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-command-direct-utf8-foot-20260804`.

The remaining audited runtime JNI request paths now preflight and encode UTF-8
directly into exact or reusable direct buffers. This covers package search,
package-job messages, download filenames, package verification and cache
validation, document-import fields, bootstrap root paths, and the packaged
native-library path. URI admission also uses the shared allocation-free UTF-8
walker instead of creating temporary encoded arrays solely to measure them. The
three remaining runtime-service `toByteArray(StandardCharsets.UTF_8)` calls
retain encoded bytes as actual diagnostic launch-configuration or queued shell
command data rather than staging a native request or length check. Targeted
document-import, direct UTF-8, and project-sync JVM tests pass, as do the complete
source unit/lint gate and both exact-ABI manager builds. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font, 126 px
controls, and visible command output in inspected full-device frames. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/remaining-runtime-request-utf8-foot-20260804`.

Rust DocumentsProvider IDs and project-mirror paths now validate and retain
segments incrementally. The former parsers collected every slash-delimited
segment from the admitted 1 KiB or 4 KiB input before enforcing their 32- and
64-segment depth limits. The shared parser now validates each segment before
retention and rejects segment 33 or 65 without adding it. Direct storage tests
cover both exact depth limits and their first overflow; all 34 storage tests,
the locked Rust workspace test suite, targeted warning-denied storage Clippy,
the complete Android source unit/lint gate, and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/storage-path-segment-bound-foot-20260804`.

Project-sync manifest loading now allocates exactly the metadata-admitted file
length, fills that buffer with `read_exact`, and probes one trailing byte for
concurrent growth. The former `Vec::with_capacity` plus `read_to_end` path could
grow and reallocate beyond the expected size before rejecting a changed file.
Short reads and first-byte growth now fail without returning partial state.
Direct tests cover exact input, truncation, and growth; all 35 storage tests,
the locked Rust workspace test suite, targeted warning-denied storage Clippy,
the complete Android source unit/lint gate, and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/sync-manifest-exact-read-foot-20260804`.

Project-synchronization journal fingerprints now decode by delimiter indexes
instead of splitting the complete bounded history field before checking its
exact three-field schema. A third colon fails without constructing field
substrings. The one numeric field remains bounded, and all 32 digest bytes are
decoded directly from lowercase hexadecimal nibbles without two-character
temporary strings. Direct JVM tests cover valid file and directory records,
missing and extra fields, an 8 KiB colon flood, uppercase and nonhexadecimal
digests, digest-length boundaries, and negative or oversized file sizes. The
complete source unit/lint gate and both exact-ABI manager builds pass. The
subsequent physical-Samsung Foot regression retained stable processes, a 34 px
font, 126 px controls, and visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-fingerprint-bound-foot-20260804`.

Native storage-usage summaries now parse directly into their eight numeric
values instead of copying away a terminal newline and splitting the complete
fixed 16 KiB output into an arbitrary field list before enforcing the exact
nine-field schema. The parser accepts no newline or exactly one terminal LF,
rejects field ten before constructing it, and preserves nonnegative,
arithmetic-overflow, two-million-entry, and category-byte validation. Direct
JVM tests cover both valid terminators, field underflow and overflow, an extra
terminal newline, malformed, negative, and overflowing numbers, and a near-16
KiB field flood. The complete source unit/lint gate and both exact-ABI manager
builds pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/storage-usage-parser-bound-foot-20260804`.

Native AUR provider candidates now parse directly into at most 32 validated,
unique package names instead of creating a line string for every record,
filtering those strings into a list, and allocating a second distinct list
before enforcing the producer bound. The returned native length is checked
against the fixed 16 KiB direct buffer before the required byte copy, and
candidate 33 fails before substring construction. The parser preserves
LF/CRLF/CR handling, blank-line skipping, package-name grammar, and source
order. Direct JVM tests cover every delimiter form, exact 32-candidate
admission, candidate 33, duplicates, invalid and overlong names, and an exact
16 KiB blank-line flood. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-provider-parser-bound-foot-20260804`.

Native launcher-registry summaries now decode directly into a fixed ten-number
array instead of copying away terminal newlines and splitting the complete
fixed 16 KiB status before enforcing its exact 11-field schema. Terminal LF
runs are skipped by index, and field 12 fails before the final numeric
substring is constructed. Existing generation, total, per-state range, and
state-sum validation remains at the service boundary. Direct JVM tests cover
valid summaries with and without terminal LF runs, invalid headers, field
underflow and overflow, malformed and overflowing numbers, and an exact 16 KiB
tab flood. The complete source unit/lint gate and both exact-ABI manager builds
pass. The subsequent physical-Samsung Foot regression retained stable
processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-summary-parser-bound-foot-20260804`.

Native project-synchronization plan summaries now decode directly into a fixed
seven-count array instead of splitting the complete fixed 8 KiB output,
copying its action-count tail, and mapping every count before enforcing the
schema. Field eight fails before its substring is constructed. Every count
retains the zero-to-10,000 project-entry bound, and observed plan actions now
compare directly against the fixed array rather than another boxed list.
Direct JVM tests cover exact admission, field underflow and overflow, empty,
negative, malformed, and over-limit values, an invalid caller limit, and an
exact 8 KiB tab flood. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/project-sync-summary-parser-bound-foot-20260804`.

Native official-package search output now parses directly into at most 100
rows of exactly six fields instead of copying away terminal newlines, creating
a line string for every result, and splitting each row before applying the
result bound. Row 101 and field seven fail before excess substring retention.
LF, CRLF, CR, and terminal LF runs remain supported, while blank internal rows
still fail closed. The six published result collections now preallocate to the
protocol maximum; existing repository, package, version, description, install
state, installed-version, duplicate, and capability validation remains at the
service boundary. Direct JVM tests cover delimiter forms, exact row admission,
row and field overflow, blank rows, invalid caller limits, and exact 16 KiB
newline and tab floods. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-search-page-parser-bound-foot-20260804`.

Resolved official and AUR package payloads now parse directly into the
caller-bounded one-to-512 six-field records instead of creating a line string
and split list for every row before checking the closure limit. The next
package and field seven fail before excess retention. Empty lines and
LF/CRLF/CR remain supported, positive archive-size validation occurs during
record construction, and the result preallocates to the requested maximum.
Direct JVM tests cover delimiter forms, exact 512/513 boundaries, empty input,
field underflow and overflow, invalid sizes and caller limits, and 320 KiB
newline and tab floods. The complete source unit/lint gate and both exact-ABI
manager builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/resolved-payload-parser-bound-foot-20260804`.

Installed-package version and reviewed-AUR-candidate decoding now validate JNI
success lengths against their fixed 16 KiB direct-buffer capacities before
allocating result byte arrays or copying native output. The shared admission policy accepts zero through
exact capacity and rejects negative, capacity-plus-one, and invalid-capacity
inputs. Direct JVM tests cover every boundary. The complete source unit/lint
gate and both exact-ABI manager builds pass. The subsequent physical-Samsung
Foot regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in inspected full-device frames. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-candidate-output-length-bound-foot-20260804`.

The same admission now protects the remaining audited native result copies for
package search and resolution, AUR environment/graph/closure verification,
package compatibility and launcher review, available-version state,
installation size, installed origin, install/removal plans, pending mutation,
direct command output, and the latest package job. Every successful JNI length
is checked against its associated direct-buffer capacity before byte-array
allocation or copy. The shared boundary tests, complete source unit/lint gate,
and both exact-ABI manager builds pass. The physical-Samsung Foot regression
also passed with stable processes, a 34 px font, 126 px controls, and visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/jni-output-length-audit-foot-20260804`.

Native installed-shell catalogs now parse directly into at most eight rows of
three-to-seven fields. The previous path created a line string per record,
split all fields, and only then enforced row count, field count, 64-unit,
printable-ASCII, and argument-space limits. The parser supports LF, CRLF, CR,
and a final unterminated row; ignores blank lines; rejects row nine and field
eight before excess retention; and rejects an overlong field before substring
construction. Direct JVM tests cover exact row, field, and length admission and
overflow; every delimiter form; blank input; invalid characters and arguments;
and exact 16 KiB newline and tab floods. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in an inspected full-device frame. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/shell-catalog-parser-bound-foot-20260804`.

Native verified-build closures now parse directly into the declared zero-to-512
nine-field package rows and one three-field summary. The previous path split
the complete 512 KiB manifest into lines, copied and filtered its row tail,
split the summary, copied the package-row prefix, and then split every package.
The bounded parser supports LF, CRLF, CR, and an unterminated summary; ignores
blank body lines; and rejects an extra row or field before excess retention.
Existing resolved-package identity, hash, byte, signature, and summary checks
remain unchanged. Direct JVM tests cover zero, ordinary, and exact-512
closures; mixed delimiters; row and field underflow/overflow; invalid counts
and sizes; and exact-limit newline and tab floods. The complete source unit/lint
gate and both exact-ABI manager builds pass. The subsequent physical-Samsung
Foot regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in an inspected full-device frame. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/verified-build-closure-parser-bound-foot-20260804`.

Restored persisted AUR output manifests now parse directly into the declared
zero-to-256 seven-field rows. The previous path copied away terminal newlines,
split the complete 512 KiB text, split its header, copied the row tail, mapped
split rows into an intermediate list, and copied that list into an array.
Terminal LF runs are now skipped by index; an extra row, internal blank row, or
eighth field fails before excess retention. Existing package identity, metrics,
hash, canonical cache path, file size, and restored-log validation remains
unchanged. Direct JVM tests cover zero, ordinary, and exact-256 manifests;
optional terminal newlines; row/header underflow and overflow; count and size
bounds; and exact-limit newline and tab floods. The complete source unit/lint
gate and both exact-ABI manager builds pass. The subsequent physical-Samsung
Foot regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in an inspected full-device frame. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/persisted-aur-output-parser-bound-foot-20260804`.

Restored persisted AUR graph-output manifests now parse directly into the
producer-bounded one-to-256 eight-field rows. The previous path copied away
terminal newlines, split the complete 512 KiB text and header, then copied,
mapped, split, and recopied every row before checking the native output count.
Terminal LF runs are skipped by index; count 257, an extra or blank row, and
field nine fail before excess retention. Existing graph-boundary completion,
expected package order, identity, metrics, hash, canonical cache path, file
size, and restored-log validation remains unchanged. Direct JVM tests cover
ordinary and exact-256 manifests, optional terminal newlines, count, header,
row, and field bounds, and exact-limit newline and tab floods. The complete source
unit/lint gate and both exact-ABI manager builds pass. The subsequent
physical-Samsung Foot regression retained stable processes, a 34 px font,
126 px controls, and visible command output in an inspected full-device frame.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/persisted-aur-graph-output-parser-bound-foot-20260804`.

Native launcher-registry pages now parse directly into one three-field header
and at most 256 eight-field rows. The previous path copied away terminal
newlines, split the complete 8 KiB page and header, copied the row tail, and
split every row before applying the total-entry bound. Terminal LF runs are
skipped by index; row 257, an internal blank row, or field nine fails before
excess retention. The service preallocates its bounded aggregate and now
rejects a nonfinal page that makes no offset progress instead of looping
indefinitely. Existing pagination totals, package and descriptor identity,
generation and status, visible label, and source-package validation remains
unchanged. Direct JVM tests cover header-only, ordinary, and exact-256 pages;
optional terminal newlines; row and header field bounds; blank pages and rows;
and exact-limit newline and tab floods. The complete source unit/lint gate and
both exact-ABI manager builds pass. The subsequent physical-Samsung Foot
regression retained stable processes, a 34 px font, 126 px controls, and
visible command output in an inspected full-device frame. Hashed evidence is
under
`tooling/artifacts/visual-audit/RFCT90AEEFA/launcher-registry-page-parser-bound-foot-20260804`.

Portal MIME-filter parsing is now incremental as well. The previous bounded
`split` still constructed up to 17 substrings before applying the 16-type
schema, and lowercased each raw field before rejecting its 127-unit field
limit. The parser now preallocates its 16-entry normalized result and duplicate
set, validates raw field shape before lowercase allocation, preserves
case-insensitive duplicate rejection, and rejects type 17 before constructing
it. Direct JVM tests cover exact 16-type admission, type 17 rejection, and an
exact 2,048-unit overlong field. The complete app unit/lint gate and both
exact-ABI builds pass. The subsequent physical-Samsung Foot regression retained
stable processes, a 34 px font, 126 px controls, and visible command output in
an inspected full-device frame. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/portal-mime-parser-bound-foot-20260804`.

Private D-Bus and XDG portal teardown now has process-wide ownership and bounded
waits. Graceful and forced helper waits each stop after two seconds. Helper
processes, log drainers, broker/client/import/mirror workers, and save
finalization remain strongly owned until they stop; an autonomous bounded retry
completes transient cleanup after a session owner drops its bridge reference.
Replacement startup cannot overlap an unreaped portal, and stale recovery
excludes every live runtime and save path. Directory-import cancellation carries
a random operation token through the runtime, preventing an old bridge from
cancelling a newer import.

Final Android provider writes run on one tracked finalizer. If its current write
does not stop, teardown closes only that destination descriptor. The intact
staging file is synced and atomically moved into a mode-0700 manager-private
recovery directory before cleanup; startup also recovers interrupted staging
instead of deleting it. Recovery is capped at 32 files and 1 GiB. Capacity
exhaustion preserves staging, parks retries without CPU or log churn, and fails
closed rather than discarding bytes.
Deterministic JVM tests cover two-stage process deadlines, forced reap, cleanup
readiness, live-path exclusion, failed-copy disposition, atomic recovery,
capacity rejection, and stale slot recovery. The JDK 26 app unit/lint gate
passes. Current direct Gradle APKs pass the Snapshot portal/camera gate on the
x86_64 emulator and AArch64 Samsung. After wrapper force-stop, neither device
retains a portal helper process or `cache/p*` runtime directory.

Portal runtime and save cleanup no longer calls `listFiles()` before enforcing
its limits. The shared directory-stream walker now bounds an active save at
eight entries, a staging slot at one file, stale save recovery at 128 session
directories, each stale runtime at four entries, and the manager cache scan at
4,096 entries while retaining at most 128 matching runtimes. Save recovery
validates its complete bounded entry set before moving data and reuses each
validated staging file rather than enumerating the slot again. Direct JVM tests
cover current and legacy recovery plus slot, per-session save, stale-directory,
cache-scan, and runtime-entry overflow without partial mutation. The complete
JDK 26 app unit/lint gate and exact x86_64/AArch64 manager builds pass. On the
physical Samsung, manager startup removed an exact test-owned four-entry stale
runtime, then retained stable manager/wrapper/Linux processes while Foot
rendered exact command output in a clean 1080×2202 full-device frame. The
inspected screenshot, scoped logs, and hashed recovery result are under
`tooling/artifacts/visual-audit/RFCT90AEEFA/portal-runtime-recovery-20260804`.

Preference persistence now uses one keyed coalescing worker bounded by ten task
domains—startup loading and nine persistent preference keys—instead of an
unbounded executor queue. Repeated
slider and toggle writes retain only the latest pending value for each key and
move it behind older distinct work. Snapshot mutation and enqueue use one lock,
so concurrent setters cannot enqueue writes out of snapshot order.
Deterministic JVM tests cover a 100-update burst, distinct-key
capacity rejection, cross-key ordering, task and failure-reporter exceptions,
and blocked-task close. The JDK 26 app unit/lint gate passes. Current exact APKs
also pass the Auto/Light/Dark and Material You appearance-policy gate on the
x86_64 emulator and AArch64 Samsung without restarting manager, wrapper, or
Linux processes.

Generated-app-shell status, IME-state, cursor, Linux-clipboard, pointer-capture,
and appearance Binder bursts now coalesce into one scheduled main-thread
callback and one replaceable latest payload per type. Status, clipboard
selection, capture intent, and appearance stay latest-wins; pointer-capture
merging preserves an
intermediate release and waits for Android's loss callback before recapture.
Replaced custom cursor bitmaps are recycled before Android takes ownership.
Activity destruction atomically closes all slots, rejects late Binder work, and
recycles retained payloads. IME merging preserves inactive-to-active restart and
intermediate-deactivation cleanup markers, preventing a collapsed
transition from reusing the prior editor buffer or carrying its soft-keyboard
state into the next editor. Scheduling happens while the slot is locked, so a
failed first post cannot erase a concurrent replacement. Deterministic JVM
tests cover a 100-update burst, one-payload retention,
replacement/clear/close disposal, scheduling failure, and both transition
markers. Launcher-template debug unit tests and lint pass; exact release
R8/template assembly passes in both x86_64 and AArch64 manager builds.

Non-coalescible generated-app-shell document, browser-URI, and notification
callbacks now enter a 32-item FIFO instead of posting unbounded main-thread
closures. One drain remains scheduled at a time. Generation tokens invalidate
removed lifecycle callbacks, stop-time pause rejects races, and session tags
prevent old manager actions from entering a replacement session. Overflow,
lifecycle clearing, and session close explicitly cancel one-way document
requests; URI and notification overflow fails closed. Microphone consent remains
on its manager-enforced single-pending-request path. JVM tests cover FIFO
capacity, rejection and cleanup, scheduler failure, stale-drain invalidation,
pause/resume, and close. Launcher-template debug unit/lint and exact
x86_64/AArch64 release R8/template assembly pass.

Manager Android-clipboard submissions now retain one pending payload and one
Surface-thread drain. Replacements cancel the earlier queued drain and repost a
generation-tagged drain at the replacement's queue position, preserving order
against Linux clipboard completion and Surface lifecycle work. Consumption
restores canonical clipboard state before compositor publication, preventing an
intervening Linux completion from resurfacing after reattachment. Session close
cancels pending work, and failed posting rolls state back. JVM tests cover a
100-update burst, one-runnable retention, queue-barrier ordering, canonical
latest-state restoration, scheduling failure/retry, and close rejection. JDK 26
app unit/lint and exact x86_64/AArch64 manager builds pass.

Manager Surface attachment churn now retains one pending generation-tagged
attachment drain. Replacements move that drain to the latest request's
Surface-thread queue position, release never-consumed intermediate `Surface`
objects immediately, and carry only the original attached `Surface` into final
application. Detach and session close cancel queued work, serialize an in-flight
predecessor's release behind its Surface-thread use, and leave lifecycle cleanup
owning the final requested `Surface`. Failed posting releases the rejected
Surface without replacing accepted geometry. JVM tests cover retained-state
merging, distinct replacement/lifecycle disposal, a 100-attachment exact-once
release model, ordering, replacement failure, and close. JDK 26 app unit/lint and
exact x86_64/AArch64 manager builds pass.

Generated-app-shell Android accessibility delivery now uses a 64-event FIFO with
one scheduled main-thread drain. Events retain only bounded node identity, role,
text, and host state. Detach pauses and clears the queue so stale SurfaceView
events cannot enter a replacement host. Overflow latches one conservative root
subtree-change notification after capacity returns, with at most one recovery
queued during sustained bursts. Framework events are recycled unless successful
parent delivery transfers ownership. JVM tests cover exact capacity and FIFO
behavior, stale-drain invalidation, pause/resume, cleanup, and repeated overflow
recovery. Launcher-template debug unit/lint and exact x86_64/AArch64 release
R8/template assembly pass.

Manager preference, configuration, wallpaper, and runtime-connection appearance
notifications now coalesce to one pending generation-tagged Surface-thread
update. Replacement cancels and repositions the queued update, so appearance is
resolved from the latest preference snapshot and Android configuration at its
final queue position without retaining one closure per change. Service teardown
closes the slot before Surface-thread shutdown. Ordered-dispatch JVM tests cover
100-update retention, replacement ordering, scheduling failure preservation,
close rejection, and payload disposal. JDK 26 app unit/lint and exact
x86_64/AArch64 manager builds pass.

Runtime package-progress notification refreshes now coalesce to one pending
generation-tagged main-thread update instead of posting one closure per durable
job revision. The drain reads current bounded package state and active-operation
state, so replacements render only latest progress and completion cannot revive
a stale foreground notification. Runtime-service teardown closes and removes the
queued update. Ordered-dispatch JVM tests cover 100-update retention,
replacement ordering, scheduling failure, and close rejection. JDK 26 app
unit/lint and exact x86_64/AArch64 manager builds pass.

Preference startup now retains at most one current MainActivity readiness
observer while I/O is pending or main-thread delivery is queued. Activity
recreation replaces and deactivates the previous callback; `onDestroy()` and
failed Handler posting cancel and unlink registration. One-shot delivery remains
registry-validated, so replacement after worker drain cannot mutate a stale
Activity. JVM tests cover 100-generation replacement, cancellation, one-shot
delivery, post-drain replacement, and unlinking. JDK 26 app unit/lint and exact
x86_64/AArch64 manager builds pass.

Wayland touch down, motion, up, and cancel delivery no longer clones matching
`WlTouch` resources into temporary vectors. Delivery streams over the already
bounded retained touch-resource list with live and same-client filtering. Cancel
uses one retained-list pass and matches any active touch surface for that client,
preserving one cancel/frame pair per live touch without heap collection or
deduplication. Pinned Rust 1.88 full workspace tests and all-target
warning-denied Clippy pass; exact x86_64/AArch64 compositor builds pass.

Swipe, pinch, and hold begin/end delivery no longer clones matching protocol
resources into temporary vectors. Each transition first confirms a live
same-client gesture, allocates its serial only for an accepted event, then
streams in retained-resource order over the already bounded gesture lists;
update delivery already streamed directly. Pinned Rust 1.88 compositor tests and
all-target warning-denied Clippy pass; exact x86_64/AArch64 compositor builds
pass.

Manager, Builder, and launcher-template Kotlin source now has a separate JDK 26
CI lane for debug unit tests and Android lint. That lane uses an explicit
source-validation mode which omits native/runtime assembly and rejects any APK
assembly request, so only complete normal builds can emit installable artifacts.
The release workflow also provisions JDK 26.0.2; the prior JDK 17 setup could
not pass the repository's current build contract. The current 2026-08-04
source-validation gate passes debug unit tests and lint for `android:app`,
`android:launcher-template`, and `android:builder` under that pinned JDK.

Five more equivalent compositor conditionals now use Rust let-chains: retained
SHM damage reuse, root pointer-coordinate scaling, pointer-confinement surface
bounds, selection-source cancellation, and Linux drag-source cancellation. All
107 compositor tests and the locked Rust workspace suite pass, as do the
complete Android source unit/lint gate and exact x86_64/AArch64 manager builds.
Scoped warning-denied compositor Clippy confirms these findings are gone and now
reports 12 remaining `collapsible_if` findings. The physical-Samsung Foot gate
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/compositor-rust197-letchains-foot-20260804`.

The Rust 1.97 warning-denied workspace gate is clean. The compositor's remaining
12 nested conditionals now use equivalent let-chains across popup lifecycle,
subsurface state, toplevel activation/restoration, popup composition and
dismissal, and pointer-coordinate handling. Runtime PTY teardown likewise joins
its empty-registry and session-marker checks without changing marker-removal
errors. All 107 compositor tests, all 10 runtime tests, the locked Rust workspace
suite, full all-target warning-denied workspace Clippy, the complete Android
source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/rust197-workspace-clippy-clean-foot-20260804`.

Builder AUR dependency manifests now parse their package and summary records
incrementally instead of collecting every tab-delimited field before enforcing
the exact 11- or four-field schema. Package storage is preallocated to the
existing bounded maximum, and the first excess field fails before any record is
retained. A regression rejects a tab flood at the exact 256 KiB manifest limit.
All 27 Builder tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate, and
exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate retained
stable processes, a 34 px font, 126 px controls, and exact visible command output
in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/builder-aur-dependency-field-bound-foot-20260804`.

Package removal mutation-intent headers now consume only the package, optional
legacy version, and optional legacy database digest through the delimiter
iterator instead of collecting every tab-delimited field before enforcing the
one-to-three-field schema. Field four fails before temporary field retention,
while current multi-record removal intents and both legacy forms retain their
existing validation and recovery semantics. The round-trip suite now also
rejects a tab flood at the exact intent-size ceiling. All 131 package tests, the
locked Rust workspace suite, full all-target warning-denied workspace Clippy,
the complete Android source unit/lint gate, and exact x86_64/AArch64 manager
builds pass. The physical-Samsung Foot gate retained stable processes, a 34 px
font, 126 px controls, and exact visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-removal-intent-field-bound-foot-20260804`.

Official and AUR rollback removal preflights now validate Pacman's unordered
package-name output with a fixed four-word bitset instead of collecting and
sorting every output line plus a cloned expected vector. The validator accepts
each of at most 256 expected names exactly once and rejects unknown, duplicate,
missing, blank, or over-limit results without output-sized allocation. Direct
tests cover reverse-order exact admission, every mismatch class, and the full
256-name boundary. All 132 package tests, the locked Rust workspace suite, full
all-target warning-denied workspace Clippy, the complete Android source
unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/rollback-plan-fixed-set-foot-20260804`.

AUR lifecycle-capability identity validation now checks the required-package
list's uniqueness directly within the existing 256-package bound instead of
allocating a temporary `BTreeSet` of every package reference. The validation
still rejects empty, oversized, malformed, duplicate, and target-omitting
lists. A direct regression covers 256 unique names, a duplicate at that limit,
and a 257th-name overflow. All 133 package tests, the locked Rust workspace
suite, full all-target warning-denied workspace Clippy, the complete Android
source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-capability-identity-uniqueness-foot-20260804`.

Replacement-repair snapshot validation now records matched removals in one
fixed `u64` bitset plus a count instead of cloning up to 48 package names into a
temporary vector. The validator explicitly rejects replacement inputs above
that policy bound and continues to reject repeated package names, unmatched
snapshot entries, missing entries, invalid identities, and digest mismatches.
The restoration regression now also exercises a missing replacement record and
an over-limit record list. All 133 package tests, the locked Rust workspace
suite, full all-target warning-denied workspace Clippy, the complete Android
source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/replacement-repair-fixed-set-foot-20260804`.

Desktop discovery now allocates a desktop ID and path only when the bounded
1,024-candidate heap admits that entry; once full, lexicographically later
entries are compared by their borrowed ID and discarded without constructing a
temporary owned candidate. AUR provider search likewise validates every RPC
record but clones a package name only on first admission instead of cloning all
records and deduplicating afterward. Existing candidate-prefix coverage passes,
and the provider regression now proves duplicate valid records collapse while a
malformed duplicate still fails closed. All 133 package tests, the locked Rust
workspace suite, full all-target warning-denied workspace Clippy, the complete
Android source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/desktop-aur-admission-allocation-foot-20260804`.

Desktop executable discovery now probes absolute paths directly and constructs
relative `/usr/local/bin`, `/usr/bin`, and `/bin` candidates one at a time,
stopping at the first safe root-contained executable instead of eagerly
allocating a temporary vector with all three paths. A direct regression proves
the conventional `/usr/local/bin` precedence remains intact when the same
program is also installed under `/usr/bin`; escape, normalized-link, and
root-absolute-link coverage remains green. All 134 package tests, the locked
Rust workspace suite, full all-target warning-denied workspace Clippy, the
complete Android source unit/lint gate, and exact x86_64/AArch64 manager builds
pass. The physical-Samsung Foot gate retained stable processes, a 34 px font,
126 px controls, and exact visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/desktop-lazy-executable-resolution-foot-20260804`.

Shell discovery rows and desktop-catalog page headers now format directly into
their fixed `ToolOutput` storage through its bounded `fmt::Write`
implementation. This removes one temporary heap `String` per admitted shell and
one per catalog page while preserving exact wire bytes and output-limit errors.
The exact four-shell catalog and desktop page protocol regressions pass. All 134
package tests, the locked Rust workspace suite, full all-target warning-denied
workspace Clippy, the complete Android source unit/lint gate, and exact
x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate retained
stable processes, a 34 px font, 126 px controls, and exact visible command
output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/direct-tool-output-formatting-foot-20260804`.

AUR `.SRCINFO` parsing now recognizes architecture-qualified dependency,
provider, source, and checksum keys by comparing each borrowed key suffix with
the requested architecture. It no longer allocates seven formatted key strings
for every parse. Exact architecture matching remains mandatory; the source
selection regression now also proves `source_x86_64_extra` and
`sha256sums_x86_64_extra` are ignored rather than treated as x86_64 records.
All 134 package tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate, and
exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate retained
stable processes, a 34 px font, 126 px controls, and exact visible command output
in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/srcinfo-borrowed-architecture-keys-foot-20260804`.

SHA-512 AUR source cache keys now allocate their exact 135-character output
once, append the `sha512-` domain prefix, and encode digest nibbles directly
into that string. This replaces the temporary 128-character hexadecimal string
previously copied by `format!`; established SHA-256 cache paths remain
unchanged. The SHA-512 source staging/reopen regression now also checks the
exact key prefix and length. All 134 package tests, the locked Rust workspace
suite, full all-target warning-denied workspace Clippy, the complete Android
source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/sha512-cache-key-single-allocation-foot-20260804`.

Pacman's missing-package diagnostic classifier now extracts the package name
between the fixed borrowed prefix and suffix instead of allocating a formatted
expected diagnostic for every query. It still accepts only one exact line; the
installed-query regression now explicitly rejects a different package name and
otherwise-correct output followed by a second line. All 134 package tests, the
locked Rust workspace suite, full all-target warning-denied workspace Clippy,
the complete Android source unit/lint gate, and exact x86_64/AArch64 manager
builds pass. The physical-Samsung Foot gate retained stable processes, a 34 px
font, 126 px controls, and exact visible command output in inspected full-device
frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/missing-package-borrowed-match-foot-20260804`.

AUR RPC package and provider validation now recognizes canonical snapshot paths
by comparing the borrowed package-name segment between the fixed
`/cgit/aur.git/snapshot/` prefix and `.tar.gz` suffix. This removes one
temporary formatted path per validated result while retaining exact matching;
the RPC identity regression now explicitly rejects a `.tar.gz.extra` suffix.
All 134 package tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate,
and exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/rpc-snapshot-borrowed-match-foot-20260804`.

Pacman's missing-repository-target classifier now removes at most one terminal
newline and compares the borrowed text after the fixed
`error: target not found: ` prefix. This replaces two formatted expected
diagnostics per singleton repository probe while retaining exact package and
whole-output matching. Direct regressions cover the accepted unterminated form
and reject a different package, a second newline, and a package-name suffix.
All 134 package tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate,
and exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/repository-target-borrowed-match-foot-20260804`.

AUR archive admission, recovery, and rollback-cache discovery now compare the
borrowed filename remainder after an existing hexadecimal digest with the
required `-` boundary. This removes temporary formatted digest-prefix strings
from all three paths; initial intent publication also computes its retained
digest text once instead of encoding it again after validation. A direct
regression covers exact admission and missing, substituted, prepended, or
punctuation-only digest boundaries. All 135 package tests, the locked Rust
workspace suite, full all-target warning-denied workspace Clippy, the complete
Android source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-digest-prefix-borrowed-foot-20260804`.

Pacman local-database directory validation now compares each borrowed entry
name with its parsed package name, one required `-`, and its complete version.
Transaction preview copying, installed-package inventory, replacement-snapshot
validation, and replacement-snapshot cleanup no longer allocate a formatted
`name-version` string for every entry. A direct regression covers hyphens in
both identity components and rejects missing separators, suffixes, prefixes,
and different package names. All 136 package tests, the locked Rust workspace
suite, full all-target warning-denied workspace Clippy, the complete Android
source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/local-database-borrowed-identity-foot-20260804`.

Replacement repair now recognizes every already-authorized local database
entry by applying the borrowed identity-component matcher directly to the
directory entry name. The bounded nested scan no longer formats a
`name-version` string and joins a temporary path for each replacement
candidate examined against every local entry. Existing exact replacement
snapshot and restoration coverage passes, including complete cleanup and
digest validation. All 136 package tests, the locked Rust workspace suite,
full all-target warning-denied workspace Clippy, the complete Android source
unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/replacement-scan-borrowed-identity-foot-20260804`.

AUR PKGBUILD step discovery now recognizes package functions directly from the
borrowed parsed function name. It accepts either the exact
`package_<package-name>` spelling or the conventional form with every package
name hyphen replaced by an underscore, without allocating two expected
function strings or an intermediate normalized package name for each review.
A direct regression covers both accepted forms and rejects mixed, prefixed,
suffixed, and truncated near-matches. All 137 package tests, the locked Rust
workspace suite, full all-target warning-denied workspace Clippy, the complete
Android source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-package-function-borrowed-match-foot-20260804`.

AUR provided-package derivation now retains unique package and virtual-provider
names directly in one pre-sized vector, then sorts that retained result. This
removes the temporary node-allocating `BTreeSet` and enforces the existing
256-name aggregate ceiling before retaining name 257 instead of after building
the oversized set. Duplicate declarations remain accepted even at exact
capacity. Direct boundary coverage and the existing sorted VS Code provider
review pass. All 138 package tests, the locked Rust workspace suite, full
all-target warning-denied workspace Clippy, the complete Android source
unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-provided-vector-bound-foot-20260804`.

AUR split-package dependency traversal now retains its unique reachable
package frontier in one pre-sized vector instead of a node-allocating
`BTreeSet`. Providers already retained or pending are not enqueued again, and
the existing 256-package ceiling is enforced before retaining or enqueuing
package 257. The final package list remains sorted. Direct regression coverage
accepts an exact 256-package chain and rejects the next package before growth.
All 139 package tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate,
and exact x86_64/AArch64 manager builds pass.

Concurrent publication of the same root-managed file no longer reports a
valid atomic replacement as `InvalidEntry`. The descriptor/path identity check
still rejects a non-regular final path, but an intervening regular-file
replacement now proceeds through publication of the current managed content.
The previously reproducible
`managed_file_publication_owns_unique_temporary_files` regression passed three
consecutive focused runs, and scoped root Clippy passes. The subsequent
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
The signed manifest records Android PID 7339 and Linux PID 7416. Hashed
evidence for both changes is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-split-frontier-root-publication-foot-20260804`.

Reviewed AUR graph topology now represents dependency-base relationships as 32
fixed `u32` rows and reachability as one fixed boolean array. This replaces the
temporary `BTreeSet` values for dependency pairs and reachable bases. Kahn
ordering uses one pre-sized ready vector with a bounded lexicographic minimum
scan instead of allocating ordered-set nodes, while preserving dependency-first
and package-base tie ordering. Multiple dependency requirements from one base
to the same provider contribute one indegree relationship as before. The graph
regression now proves this deduplication and deterministic ordering across two
simultaneously ready providers. All 139 package tests, the locked Rust workspace
suite, full all-target warning-denied workspace Clippy, the complete Android
source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
The signed manifest records Android PID 7964 and Linux PID 8072. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-graph-fixed-topology-foot-20260804`.

Package ELF integration profiling now tracks its at-most-256 visited object
paths in one pre-sized vector instead of a node-allocating `BTreeSet`. The
bounded traversal compares queued paths against that retained vector, rejects
object 257 before retaining it, and moves each newly admitted queue string into
the visited collection instead of cloning it. Existing duplicate suppression,
root-object handling, dependency resolution, completeness reporting, and the
4,096-edge ceiling remain unchanged. The exact dependency-graph profile
regression passes. All 139 package tests, the locked Rust workspace suite, full
all-target warning-denied workspace Clippy, the complete Android source
unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
The signed manifest records Android PID 8366 and Linux PID 8467. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-elf-visited-vector-foot-20260804`.

Package ELF integration profiling now admits resolved libraries and script
delegates into one unique bounded frontier. Paths already visited or pending
are discarded without another queue entry, and the combined visited/pending
set rejects distinct object 257 before retention. This prevents repeated ELF
dependencies from occupying duplicate `VecDeque` storage while preserving the
same breadth-first profile order and incomplete result for an oversized graph.
A direct boundary regression proves duplicate admission at exact capacity and
rejects a new path without growing the queue. All 140 package tests, the locked
Rust workspace suite, full all-target warning-denied workspace Clippy, the
complete Android source unit/lint gate, and exact x86_64/AArch64 manager builds
pass. The physical-Samsung Foot gate retained stable processes, a 34 px font,
126 px controls, and exact visible command output in inspected full-device
frames. The signed manifest records Android PID 8688 and Linux PID 8780. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-elf-unique-frontier-foot-20260804`.

Package-search update annotation now retains Pacman's validated quiet-update
names in one pre-sized vector instead of a node-allocating `BTreeSet`. Each
name is checked against the at-most-100 differing search candidates and prior
retained names before ownership allocation; annotation performs the same
bounded borrowed membership scan. Unknown or duplicate output still fails
closed, and search-row order and update labels remain unchanged. The package
search state regression now explicitly rejects duplicate quiet-update output.
All 140 package tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate,
and exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. The signed manifest records
Android PID 9054 and Linux PID 9152. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-update-vector-match-foot-20260804`.

Selective package-cache cleanup now validates the caller's borrowed at-most-256
package slice directly instead of constructing a temporary `BTreeSet<&str>`.
Each name is checked against only its prior bounded slice for duplicates, and
scanned cache artifacts use borrowed slice membership before deletion. Empty,
oversized, malformed, and duplicate selections still fail before the cache is
scanned or mutated. Existing cache inventory coverage proves duplicate
rejection, mutation-intent exclusion, exact selected-byte reclamation, and
retention of unselected artifacts. All 140 package tests, the locked Rust
workspace suite, full all-target warning-denied workspace Clippy, the complete
Android source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
The signed manifest records Android PID 9472 and Linux PID 9548. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-cache-borrowed-selection-foot-20260804`.

Incoming-package foreign-owner preflight now validates transaction and allowed
replacement identities directly within their existing bounded slices. This
removes two temporary `BTreeSet<&str>` collections while still rejecting a
duplicate transaction package, a duplicate replacement, or any identity in
both sets before archive listing or local-database scanning. Each installed
package identity is compared against the borrowed transaction/replacement
slices before its ownership record is inspected; canonical incoming file paths
remain in their separately bounded ordered set. Existing preflight coverage
continues to distinguish foreign-owned conflicts from unrelated installed
files. All 140 package tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate,
and exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. The signed manifest records
Android PID 9745 and Linux PID 9845. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-preflight-borrowed-identities-foot-20260804`.

Libalpm hook suppression now retains unique validated hook names in one bounded
vector and sorts once before creating deterministic `/dev/null` overrides. This
replaces the temporary node-allocating `BTreeSet` while preserving the 1,024
directory-entry ceiling, path/type/size validation, stale-override cleanup, and
one override for a same-named system and local hook. The hook regression now
includes an exact custom hook that shadows a system hook and proves one safe
override is published. All 140 package tests, the locked Rust workspace suite,
full all-target warning-denied workspace Clippy, the complete Android source
unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
The signed manifest records Android PID 10086 and Linux PID 10166. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/package-hook-vector-dedup-foot-20260804`.

Reviewed AUR graph planning now checks official provider identities directly in
the package runtime's existing bounded official-target slice. The runtime no
longer clones those targets into a temporary `BTreeSet<String>` before graph
planning, and the package planner accepts the borrowed slice while retaining
its 256-provider bound, name validation, official-provider preference, and
missing/ambiguous provider behavior. Focused package graph and runtime
build-target regressions pass. All 140 package tests, all 10 runtime tests, the
locked Rust workspace suite, full all-target warning-denied workspace Clippy,
the complete Android source unit/lint gate, and exact x86_64/AArch64 manager
builds pass. The physical-Samsung Foot gate retained stable processes, a 34 px
font, 126 px controls, and exact visible command output in inspected
full-device frames. The signed manifest records Android PID 10375 and Linux PID
10455. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-official-provider-borrowed-slice-foot-20260804`.

AUR builder-environment target derivation now retains unique official
dependencies directly in one pre-sized vector and sorts only the final bounded
result. Per-review split outputs and reviewed graph dependencies are matched
against their existing bounded slices instead of constructing three temporary
`BTreeSet` values. The 256-target ceiling is enforced before retaining target
257, and a boundary regression proves exact-256 acceptance and 257 rejection.
All 11 runtime tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate,
and exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. The signed manifest records
Android PID 10716 and Linux PID 10797. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-target-vector-dedup-foot-20260804`.

Process launch-time ELF dependency traversal now retains visited objects in one
pre-sized vector and admits only dependencies absent from both that vector and
the pending `VecDeque`. This replaces the node-allocating `BTreeSet`, prevents
duplicate pending paths from inflating the frontier, and rejects unique object
257 before queue retention. A boundary regression proves exact-256 admission,
visited and queued deduplication, and pre-retention overflow rejection. All 33
process tests, the locked Rust workspace suite, full all-target warning-denied
workspace Clippy, the complete Android source unit/lint gate, and exact
x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate retained
stable processes, a 34 px font, 126 px controls, and exact visible command
output in inspected full-device frames. The signed manifest records Android
PID 11908 and Linux PID 12028. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/process-elf-frontier-vector-foot-20260804`.

Reviewed AUR transaction planning now passes its existing bounded owned
runtime-assumption vector directly through both Pacman plan simulations. The
planner and preview accept `&[String]` and borrow each argument only while
building the command, eliminating the temporary parallel `Vec<&str>` without
changing the 255-assumption ceiling, expression validation, ordering, or
Pacman `--assume-installed` arguments. All 140 package tests, the locked Rust
workspace suite, full all-target warning-denied workspace Clippy, the complete
Android source unit/lint gate, and exact x86_64/AArch64 manager builds pass.
The physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
The signed manifest records Android PID 12504 and Linux PID 12582. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-assumption-owned-slice-foot-20260804`.

Durable install-mutation recovery now validates its existing bounded owned
explicit-target vector directly against the persisted package resolution.
`parse_resolution_output` and its mode variant accept any bounded string-like
slice through `AsRef<str>`, so ordinary borrowed command targets and recovered
owned targets share the same validation without constructing a temporary
`Vec<&str>`. Exact target-presence, name, architecture, duplicate, closure, and
256-target checks remain unchanged. The focused mutation-intent round-trip, all
140 package tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate,
and exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. The signed manifest records
Android PID 12792 and Linux PID 12871. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/mutation-intent-owned-targets-foot-20260804`.

All three Android AUR install paths now pass their existing bounded owned
runtime-dependency vectors directly into the package engine. Generic
`install_dependencies` accepts any `AsRef<str>` slice, borrows each dependency
while constructing the single required Base-prefixed Pacman target vector, and
removes three temporary parallel `Vec<&str>` allocations at the JNI boundary.
The 255-dependency ceiling, Base deduplication, provider-aware resolution,
durable recovery target, and signed dependency transaction remain unchanged.
All 140 package tests, all six Android adapter tests, the locked Rust workspace
suite, full all-target warning-denied workspace Clippy, the complete Android
source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
The signed manifest records Android PID 13111 and Linux PID 13187. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-dependency-owned-slice-foot-20260804`.

Builder dependency provisioning now passes its existing owned Pacman install
and dependency-check argument vectors directly into the process environment.
The bounded process command validator, root/non-root runners, and command
builder accept `AsRef<str>` slices and borrow arguments through process spawn,
eliminating two temporary parallel `Vec<&str>` allocations without changing
command-name, argument-count, per-argument, NUL, or total-request validation.
All 27 Builder tests, 33 process tests, and 140 package tests pass alongside the
locked Rust workspace suite, full all-target warning-denied workspace Clippy,
the complete Android source unit/lint gate, and exact x86_64/AArch64 manager
builds. The physical-Samsung Foot gate retained stable processes, a 34 px font,
126 px controls, and exact visible command output in inspected full-device
frames. The signed manifest records Android PID 13553 and Linux PID 13645.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/builder-owned-command-arguments-foot-20260804`.

Copied-database install preview now derives its authorized removals into one
pre-sized 48-entry vector and rejects removal 49 before cloning or retaining
it. This replaces filter/clone/collect growth and moves the existing removal
ceiling into `derive_install_transaction_plan` itself, so direct callers cannot
temporarily construct an oversized plan before a later check. The regression
covers a valid replacement, a changed-dependency update, and 49-removal
pre-retention rejection. All 140 package tests, the locked Rust workspace
suite, full all-target warning-denied workspace Clippy, the complete Android
source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames.
The signed manifest records Android PID 13842 and Linux PID 13922. Hashed
evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/preview-removal-preallocation-foot-20260804`.

Official install repair now passes its persisted bounded owned explicit-target
vector directly through archive reason derivation. Generic `install_resolution`
and mutation-intent publication accept `AsRef<str>` slices, so live borrowed
targets and recovered owned targets share the same path without constructing a
temporary parallel `Vec<&str>`. Exact explicit-target matching, durable intent
serialization, replacement restoration, signed installation, and repair
verification remain unchanged. The focused retained-archive repair regression,
all 140 package tests, the locked Rust workspace suite, full all-target
warning-denied workspace Clippy, the complete Android source unit/lint gate,
and exact x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate
retained stable processes, a 34 px font, 126 px controls, and exact visible
command output in inspected full-device frames. The signed manifest records
Android PID 14182 and Linux PID 14260. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/repair-owned-target-slice-foot-20260804`.

Reviewed AUR build-environment partitioning now reuses its one pre-sized
borrowed target vector for both repository probes. After graph planning, the
runtime retains only targets not supplied by a same-review split output in that
vector instead of collecting a second parallel `Vec<&str>`. Target order,
official/provider classification, missing-target handling, split-output
exclusion, and the 256-target ceiling remain unchanged. All 11 runtime tests,
the locked Rust workspace suite, full all-target warning-denied workspace
Clippy, the complete Android source unit/lint gate, and exact x86_64/AArch64
manager builds pass. The physical-Samsung Foot gate retained stable processes,
a 34 px font, 126 px controls, and exact visible command output in inspected
full-device frames. The signed manifest records Android PID 14554 and Linux PID
14635. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/aur-partition-vector-reuse-foot-20260804`.

Official and AUR rollback now derive currently installed forward additions
directly into one exactly pre-sized Pacman argument vector. Each path validates
the non-cascading print plan against that vector's bounded package tail, then
rewrites and compacts the fixed command prefix in place for execution. This
removes the separate selected-addition vector and second execution-argument
vector while preserving exact set validation, `-R` non-cascading removal,
database-lock recovery, and final absence checks. All 140 package tests, the
locked Rust workspace suite, full all-target warning-denied workspace Clippy,
the complete Android source unit/lint gate, and exact x86_64/AArch64 manager
builds pass. The physical-Samsung Foot gate retained stable processes, a 34 px
font, 126 px controls, and exact visible command output in inspected
full-device frames. The signed manifest records Android PID 14833 and Linux PID
14915. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/rollback-command-vector-reuse-foot-20260804`.

Removal repair now derives its canonical bounded package-name tail directly in
one exactly pre-sized Pacman argument vector. After exact name/version plan
validation and scriptlet authorization, it rewrites that vector's fixed prefix
in place for the final non-cascading removal, retaining or deleting the existing
slot according to `--noscriptlet` policy. This removes the temporary parallel
name vector and second execution-argument vector while preserving requested
package precedence, sorted dependency cleanup, exact plan comparison, scriptlet
authorization, and database-lock recovery. All 140 package tests, the locked
Rust workspace suite, full all-target warning-denied workspace Clippy, the
complete Android source unit/lint gate, and exact x86_64/AArch64 manager builds
pass. The physical-Samsung Foot gate retained stable processes, a 34 px font,
126 px controls, and exact visible command output in inspected full-device
frames. The signed manifest records Android PID 15109 and Linux PID 15187.
Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/removal-repair-command-vector-reuse-foot-20260804`.

Persisted explicit-install reason recovery now parses package names directly
into its required owned bounded vector. A shared generic parser retains the
existing borrowed representation for publication validation but selects owned
strings during file recovery, eliminating the temporary parallel `Vec<&str>`
and subsequent collect pass. Header, trailing-newline, logical-name, duplicate,
256-package, and encoded-size validation remain unchanged. The focused intent
and retained-archive repair regressions, all 140 package tests, the locked Rust
workspace suite, full all-target warning-denied workspace Clippy, the complete
Android source unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames. The
signed manifest records Android PID 15409 and Linux PID 15491. Hashed evidence
is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/install-reason-direct-owned-foot-20260804`.

Builder AUR dependency installation now parses the direct-buffer requirement
manifest into borrowed bounded strings. The parser rejects requirement 257
before retention, validates the 128 KiB envelope, terminal newline, package
expression, uniqueness, and strict ordering, and passes the same borrowed slice
through generic dependency installation. Pacman's final `-T` check likewise
borrows those names in one pre-sized argument vector instead of cloning every
requirement into owned strings twice. Direct coverage proves exact-256
admission plus overflow, missing-newline, unsorted, duplicate, and unsafe-name
rejection. All 28 Builder tests, the locked Rust workspace suite, full
all-target warning-denied workspace Clippy, the complete Android source
unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames. The
signed manifest records Android PID 15824 and Linux PID 15908. Hashed evidence
is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/builder-aur-requirement-borrowed-foot-20260804`.

Builder AUR dependency installation now constructs its Pacman `-U` command in
one exactly pre-sized `Cow<str>` vector. Seventeen fixed arguments and the two
shared generated runtime paths remain borrowed; only per-package staged archive
paths are owned. The same generated configuration path is reused by the later
borrowed `-T` check, and archive argument storage is released before that check.
This removes fixed-argument String allocation and duplicate path formatting
without changing isolated root, cache, hook, scriptlet, dependency, or install
reason flags. All 28 Builder tests, the locked Rust workspace suite, full
all-target warning-denied workspace Clippy, the complete Android source
unit/lint gate, and exact x86_64/AArch64 manager builds pass. The
physical-Samsung Foot gate retained stable processes, a 34 px font, 126 px
controls, and exact visible command output in inspected full-device frames. The
signed manifest records Android PID 16108 and Linux PID 16185. Hashed evidence
is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/builder-pacman-cow-arguments-foot-20260804`.

Builder dependency-install verification now reuses its existing generated
Pacman configuration path for every installed-package query. Exact `pacman -Q`
output is checked by borrowed prefix, separator, version, and terminal-newline
components instead of allocating one formatted expected line per package. A
direct regression accepts the exact package/version record and rejects missing
or repeated newlines, extra separators, and package/version suffixes. All 29
Builder tests, the locked Rust workspace suite, full all-target warning-denied
workspace Clippy, the complete Android source unit/lint gate, and exact
x86_64/AArch64 manager builds pass. The physical-Samsung Foot gate retained
stable processes, a 34 px font, 126 px controls, and exact visible command
output in inspected full-device frames. The signed manifest records Android
PID 16545 and Linux PID 16622. Hashed evidence is under
`tooling/artifacts/visual-audit/RFCT90AEEFA/builder-pacman-query-borrowed-foot-20260804`.
