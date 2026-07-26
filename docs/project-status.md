# Project status

Updated: 2026-07-25

This page separates validated behavior from planned platform work. Package search does not imply package compatibility.

## Greenfield Rust + Kotlin replacement

The new `android/app` shell and root Rust workspace now build with Gradle 9.6.1,
AGP 9.3, its built-in Kotlin 2.2.10 plugin, JDK 26.0.1, SDK/Build Tools 36.0.0,
NDK 29.0.14206865, and Rust/Cargo 1.88.0. The committed Gradle wrapper and
distribution have official SHA-256 checksums, resolved Gradle artifacts are
hash-verified, Cargo is locked, and the native container base is immutable.
The non-downloading toolchain contract, full offline Rust tests, Android lint,
and both exact-ABI APK builds pass. The APK contains one Kotlin Activity, one
Service-owned native runtime, reusable direct buffers for batched input and
status snapshots, and generation-checked bounded native handles.

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

The dense single-screen manager scaffold has been split into focused Packages,
Files, and Terminal sections with a persistent bottom navigation surface. The
selected section survives Activity recreation and rotation; switching sections
does not stop a running shared shell; storage pickers return to Files; and short
landscape windows omit nonessential terminal explanation so every control stays
on screen. Exact-ABI full-device portrait/landscape navigation, onboarding,
clean lifecycle, and persisted-folder gates pass on the emulator and Samsung.

At 840 dp and wider, the same stateful controls are composed once into a
persistent navigation rail, a two-column package workspace, side-by-side file
actions, and a terminal surface that consumes the remaining display. A
reversible emulator gate switches to tablet and external-display-sized Android
configurations, verifies the accessibility geometry, rotates the Activity,
captures full-device views of every section, restores the original display
settings, and proves the normal phone branch still passes. A physical Samsung
DeX/external-display audit and populated package/active-shell visual audits
remain planned; this clean-data pass did not download packages.

The latest bounded Rust package-journal record is now exposed as stable Binder
fields rather than reparsed UI text. Packages renders it as a recent-activity
card with package name, operation, state, exact progress, message, a static
two-view progress track, and the existing state-driven Cancel action. It does
not allocate or rewrite unchanged text during status polls. Debug-only native
journal fixtures prove a completed operation across manager restart and a
durable failed operation on both exact ABIs with full-device screenshots and no
network use.

Packages now opens on a real installed-package list derived directly from
pacman's local database. Rust validates and sorts at most 4,096 bounded regular
database entries once, caches that immutable snapshot in the runtime, and pages
60 compact records at a time through a coarse direct-buffer JNI call. Kotlin
publishes one revisioned snapshot after bootstrap and package mutation; the
Activity checks only its revision during status polls and uses recycled native
Android list rows for name, exact version, and explicit/dependency reason.
Installed and Search results are distinct retained modes, selecting an
installed row routes to exact package details, and a changed installed count
returns to the installed view. A debug-only 67-package local-database fixture
proves the second native page, virtualized scrolling, result-mode switching,
light/dark appearance, manager restart, scoped logs, and visually inspected
full-device screenshots on the emulator and Samsung without package downloads.

Official search results now use a separate revisioned Binder snapshot and
virtualized Android list rather than joining the bounded Rust response into one
large TextView. Repository, name, exact version, and description remain
separate fields; selecting a row fills the exact package and opens details,
while returning to Search results restores the submitted query. Matching
durable package activity replaces the row's repository label with its operation
and state, and the adapter can append an unmatched active job immediately from
the existing journal fields without reparsing display strings. A debug-only
dependency-free tar/gzip writer generates minimal real core/extra pacman
databases inside app storage. Pacman itself searches those catalogs, and both
exact ABIs pass three-result `dotnet` rows, row selection, retained query and
results across theme recreation, durable Failed overlay, scoped fatal logs, and
visually inspected full-device light/dark screenshots without network access.
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
their own focused deterministic gate.

The durable journal now also represents the future launcher pipeline's
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
manager state model and presentation, not the still-pending real launcher
builder and PackageInstaller handoff.

The first launcher implementation slice now discovers graphical applications
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

Package icons now cross the complete generic launcher pipeline. Rust resolves
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
The physical manager then migrated three real Foot launchers through normal
Android confirmation; the system installer and full-device app drawer showed
the package Foot icon, a cold restart settled at three current launchers, and
Foot still produced a real Wayland frame. The run also fixed stale-template
reconciliation for a wrapper already awaiting removal and removed the two
debug desktop fixtures without touching Linux user data. Capability
declaration derivation and per-launcher compatibility diagnostics remain open.

The manager now builds minimized launcher wrappers from one staged template,
patches bounded manifest identity fields, signs each APK with a persistent
non-exportable RSA-3072 Android Keystore key, re-verifies its signer and exact
entry digests, and streams the verified result through normal user-confirmed
PackageInstaller sessions. A wrapper binds only to the exported launcher
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
transaction and its generated launcher was updated through normal Android
confirmation. The generic glibc bridge maps private-root pathname Unix sockets,
full-path and PATH-based exec/spawn calls, and bounded passwd identity without
changing Foot or Bash. The shared root publishes `archphene` at
`/home/archphene`; an app-managed fontconfig root includes installed Arch fonts
and Android's system fonts, so Foot remains runnable after `ttf-dejavu` is
removed through the normal manager UI.

The launcher now treats Android pixels and Wayland logical coordinates as
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
scripts, valid relative/absolute links, loops, and escapes. Both exact-ABI APKs
build with the refreshed bridge. The physical Samsung gate now executes the
complete `/usr/bin` wrapper → `/usr/lib` script → absolute root-internal symlink
→ ELF chain and returns the real Bash version in a visually inspected
full-device capture without a fatal log. The x86_64 device repetition remains
open.

The launcher template no longer embeds AGP's repository-wide
`META-INF/version-control-info.textproto`. That metadata made the authenticated
template digest change after unrelated manager commits or dirty-tree changes,
which correctly triggered stale-wrapper reconciliation but needlessly asked
the user to update every launcher. Template release builds now disable VCS
metadata at the supported per-build-type DSL boundary. A dedicated gate rejects
the entry and forces two complete launcher rebuilds; both produce the same
`3db705420d7e923e8f91a770a086a8806248e136de47c6bfd90517e50608fcd3`
SHA-256.

The equivalent real-client gate remains open on the x86_64 emulator, which is
not currently attached. HOME/resume, deliberate client crash/descendant
cleanup, non-Latin composing input, non-text clipboard, pointer lock, and
launcher accessibility still need explicit production-client variants.
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
One-tap failure-card catalog refresh and repair/rollback tooling remain
pending.

Linux-storage failures now expose Clear cache before Review. The operation runs
off the Activity thread through a dedicated Rust/JNI boundary and is limited to
the manager-owned `var/cache/pacman/pkg` directory. Rust first validates the
directory and every bounded entry, rejects symlinks, directories, non-Unicode
or unknown names before deleting anything, then removes only recognized package
archives, detached signatures, and partial downloads and syncs the directory.
Installed package state, Linux home files, catalogs, and project data are
outside the cleanup boundary. The card reports reclaimed bytes (or an empty
cache), then advances only that durable job to Review; a cleanup error also
becomes a handled attempt with restart guidance rather than inviting a
misleading loop. Kotlin persists the bounded result against the Rust journal's
durable job ID plus its exact terminal identity, so a cold Service restart
restores the result while a later identical failure cannot inherit stale
recovery.

The exact APK's debug fixture creates a 4 KiB rounded archive/signature/partial
set, invokes Clear cache from the full Android UI, verifies the directory is
empty through the app sandbox, cold-restarts the manager, and captures the
restored post-cleanup Review state on both the emulator and Samsung.
Network-disabled Rust tests also prove fail-closed behavior when an unknown
cache entry is present and verify that an empty cache is idempotent.

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
screenshots. Package-specific selection, broader disk-use controls, catalog
recovery, and partial-transaction repair remain pending.

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
both exact-ABI targets. It is not yet a live or conflict-safe synchronizer:
subsequent pull/push, change manifests, conflict copies, deletion policy,
sync-plan cancellation, `/mnt/android`, and export/share remain open.

On first clean launch, the manager now explains that the conventional Linux
environment stays in private app storage and that choosing an Android folder
creates an initial `~/Projects` snapshot rather than a live mount or broad
all-files grant. The user can choose a folder or select Not now; either choice,
including picker cancellation, is remembered so the prompt does not nag after
restart, while the normal Connect/Change action remains available. Semantic
UI, picker-cancellation, no-repeat restart, scoped-log, and visually inspected
full-device gates pass on the exact-ABI emulator and Samsung.

Single Android documents can now enter the shared environment from the system
picker, Open With, or Share. Android passes one read-only content descriptor;
Rust duplicates and streams it into a private staging file without transferring
file bytes through JNI or Kotlin, enforces a 16 GiB limit, syncs it, then
publishes it into `~/Downloads` with a non-replacing descriptor-relative
rename. Existing names become bounded ` (2)` variants, interrupted staging is
recovered on the next attempt, and invalid/spoofing display names receive a
safe fallback. The Service owns coarse durable status and the Activity consumes
each incoming intent once.

Exact ACTION_VIEW and ACTION_SEND byte-content, collision, restart-status,
system-picker, cleanup, scoped-log, and full-device visual gates pass on the
x86_64 emulator and physical AArch64 Samsung. Multi-document and folder import,
drag-and-drop, progress/cancel, provider timeouts, and export remain open.

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
closure. It now asks pacman to prepare the complete archive set with normal
dependency, conflict, and replacement checks, then requires every planned
name/version to match the already verified resolution exactly before allowing
mutation. A cache-only gate verifies installed `btop` through that real
preflight without rewriting or downloading package payloads on the x86_64
emulator and AArch64 Samsung. Pacman now commits the complete prepared archive
set through one normal dependency-checking transaction; the former per-package
`--nodeps` and blanket-overwrite mutations are gone. New packages enter as
dependencies, while a bounded mode-0600 intent preserves every existing
explicit package plus the requested target and restores those reasons
idempotently after manager death. Startup rejects malformed, oversized,
symlinked, or broadly writable intents, removes a stale database lock, validates
the local database, and only then deletes the recovered intent. Cache-only
verify/remove/reinstall and forced restart-recovery gates pass on both devices
without rewriting package payloads.

The generic compatibility layer maps Linux root ownership to the Android app
UID, copies when SELinux rejects hard links, avoids Android app seccomp's
blocked `fchmodat2`, and maps generic root-relative mutation calls without
package-specific changes. The current path validates pacman's local database
and proves the requested package and version. Scriptlets/hooks remain disabled,
and real upgrade/replacement, failure injection, rollback/recovery, orphan
cleanup, and low-storage behavior remain open.

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
Verify, and Remove actions. Removal first asks pacman for a non-cascading plan,
fails if dependents make that unsafe, removes only the requested target, then
proves both its executable and local database record are absent. Dependencies
remain installed for the pending orphan-cleanup policy.

Clean nine-package `btop` transaction cycles pass on the x86_64 emulator and
AArch64 Samsung. Both gates deliberately corrupt the target archive, prove
rejection, redownload and reverify it, remove the package conservatively, prove
its executable and database entry are gone, reinstall from the verified cache,
and prove the durable Complete result survives manager process death. Full-
device screenshots also verify the responsive closure view and state-driven
actions. A real older-to-newer repository upgrade, hooks/scriptlets,
closure-wide rollback, cancellation, orphan cleanup, and low-storage recovery
remain open, so this is not yet a complete production transaction engine.

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
`PATH=/usr/local/sbin:/usr/local/bin:/usr/bin`; bridge-private host paths remain
only in the explicit loader variables. The path bridge maps `getcwd` and
`get_current_dir_name` back into the shared root, so both `PWD` and `pwd`
report `/home/archphene` instead of Android's private data path. `LOCPATH`
points directly at the verified locale data installed by the architecture's
official glibc package, enabling `C.UTF-8` without generating or bundling a
second locale. Root bootstrap creates `.bashrc` and `.bash_profile` defaults
once, rejects symlink substitution, and never overwrites user edits.

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
publishes only the reviewed Bash and POSIX-shell launch records. The Kotlin
Service persists
the stable shell identifier, refreshes discovery after package mutation,
prebuilds each bounded NUL-delimited PTY request once, and keeps the selector
disabled while a session is active. Unsupported or unsafe catalogs disable
shell launch without making package management unavailable.

The readable Android selector, process-death persistence, distinct
`archphene:~$` and `sh-5.3$` PTY prompts, stop/reap behavior, scoped fatal
logs, and full-device screenshots pass with exact-ABI APKs on the API 36
x86_64 emulator and AArch64 Samsung. Bash is restored as the selected default
after each gate. Additional shell-specific startup adapters, editable startup
files, and the production terminal surface remain pending.

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
view measures 14sp monospace cells, resizes the real PTY to the resulting
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
inspected full-device IME screenshots. This automated injection validates the
hardware-style Android key route; broader OEM/non-Latin composing and commit
behavior still needs dedicated coverage.

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
row scratch buffer rather than per-cell strings. It shapes contiguous nonblank
grapheme runs while anchoring runs after blank cells to their exact terminal
column; a Samsung full-device gate exposed and fixed the prior proportional
whitespace drift.

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

Trying the installed full-screen `btop` then exposed the next independent
compatibility boundary. Stock Android SELinux denies the app access to
`/proc/stat`; btop now gets beyond terminal/identity initialization but exits
when it cannot parse CPU statistics. Archphene still needs a bounded
sandbox-scoped `/proc`, `/sys`, and `/dev` view that represents its Linux
environment without leaking Android-wide processes or pretending unavailable
kernel metrics are reliable.

This is not yet the production terminal promised by the milestone. Bounded
physical-row scrollback is implemented, but resizing still preserves the
current cursor window rather than joining soft-wrapped logical lines and
reflowing them consistently across the screen/history boundary. Remaining
xterm controls, logical reflow, selection handles/autoscroll and ranges stable
across history movement, an explicit overlong-grapheme user-visible policy,
broader non-Latin composing IME behavior, and richer accessibility remain
required. The temporary command field remains as a fallback above the
renderer; direct terminal input no longer depends on it.

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

The current real Code-OSS transaction on x86_64 completes through the generic
official-package path. Pacman resolves, downloads, detached-signature verifies,
and installs its current 198-package, 428 MiB closure; desktop discovery then
publishes four generated Android launchers, including Code - OSS. This run
closed four general package-engine gaps: dependency resolution now has a
separate bounded, Service-reused 256 KiB direct response rather than exceeding
the general 16 KiB command channel; GnuPG machine status is parsed as raw bytes so a valid
signature is not rejected because a human diagnostic contains non-UTF-8;
successful large transaction output is bounded but discarded without a large
heap copy; and legitimate zero-byte pacman `files` records no longer make an
otherwise complete desktop scan appear truncated. A genuinely truncated scan
still pauses launcher reconciliation rather than mutating the registry from
incomplete evidence.

Code now reaches the packaged Electron executable without a Code-specific
bridge exception. Translating Arch's standard `/usr/lib/pulseaudio` directory
into the private-root loader path resolves `libpulsecommon`; Electron then
aborts with `Invalid file descriptor to ICU data received`. Electron/Chromium
multiprocess startup, Ozone rendering, sandboxing, and the daily-use workflow
therefore remain unvalidated. A generic policy for other package-private
absolute RUNPATH trees also remains open; globally mixing arbitrary
`/usr/lib/<app>` directories would risk resolving the wrong private library.

Source-level tracing identified a generic candidate for that ICU failure.
Processes started through an explicit glibc loader otherwise expose the loader
through `/proc/self/exe`, so Electron cannot derive the neighboring
`icudtl.dat` path. The bridge's existing bounded readlink substitution was not
wired into the greenfield runtime and remained stale across nested execution.
Initial commands now publish the verified real program, and every intercepted
`exec` or `posix_spawn` replaces that value with its newly verified target.
Host probes prove both direct and spawned target paths, the sealed dual-ABI
bridges rebuild, and current Samsung Foot still connects and presents. This is
not yet an Electron success claim; the x86_64 launch must be repeated when the
emulator is used again.

The same current-source arm64 manager preserves the Samsung shared root at 35
installed packages and three current Foot launchers. Cached startup completes
in 255 ms, and a fresh generated Foot launch authenticates generation 328,
starts the manager-owned Linux process, connects the real Wayland client,
presents its first 1080×2202 frame, and resizes cleanly for the Samsung IME.
Pressing Home detaches that Surface with `close=false`; the observed manager
and wrapper PIDs remain unchanged, and resume reattaches session 1 with its
readable frame intact.
The visually inspected evidence is a full-device screenshot rather than an
app-only frame. The complete Rust workspace passes 174 tests, including the
large-resolution, raw-signature-status, empty-files-record, loader-path, JNI,
compositor, terminal, storage, and warmed-allocation regressions.

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
| Package job scheduler | Per-package phase/error state and a bounded structured diagnostic history survive Activity recreation and manager process death; legacy jobs migrate without data loss, two preparation jobs can overlap, wrapper mutation/signing and Android confirmation are serialized, and package failures are isolated. List/detail progress, recent phases, cancel, retry, installer completion, and interrupted-completion reconciliation pass emulator tests. Active foreground package work also survives real Recents dismissal, shuts down only after completion, and restores exact cache-recovery results after cold restart on both exact-ABI targets. |
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
- Project trees and granted GUI documents currently use explicit synchronized mirrors; a live SAF path broker remains pending. The replacement selects installed Bash or its POSIX-shell mode; additional shell-specific startup adapters remain pending.
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
