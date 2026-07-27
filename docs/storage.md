# Linux Home and Android Storage Policy

Updated: 2026-07-27

Goal: give every Linux package one conventional shared Arch environment while
crossing into Android storage only through Android-controlled capabilities.

## Policy

The bridge needs two storage classes:

```text
app-private storage
  background reads/writes
  no prompt
  one shared Arch root owned by the Archphene Android app

user-visible storage
  Android files and folders the user selects
  brokered by Android UI or a previously granted capability
  shared only through explicit grants
```

Pacman and AUR packages intentionally share `/home/archphene`, `/usr`, `/etc`,
`/var`, and the same package database. Thin Android launcher entries will bind
to that shared runtime; they will not receive separate Linux roots. Android
still owns URI grants, system pickers, share flows, permissions, lifecycle,
and the boundary to storage outside Archphene.

## Direct Answer

If a Linux program wants to create, read, or update Linux-owned files in the
background, those files belong in Archphene's private shared root. Examples:

- caches
- temp files
- lock files
- sqlite databases
- language-server indexes
- extension metadata
- the shared pacman database and package cache
- per-user configuration in `/home/archphene`

Those operations should not prompt the user.

No Android storage prompt is needed for ordinary work inside this root. The
generic path bridge adapts Linux filesystem calls that Android's inherited
seccomp or SELinux policy blocks; individual applications are not patched.

If the Linux app wants to open a user document, save a user document, work inside a project folder, export media, or read/write files the user expects to see outside the app, the bridge must broker that through Android:

- `ACTION_OPEN_DOCUMENT`
- `ACTION_CREATE_DOCUMENT`
- `ACTION_OPEN_DOCUMENT_TREE`
- persisted URI permissions
- Android permission dialogs for dangerous permissions
- bridge-managed grant records

After the user grants a project folder, Archphene can persist its content-URI
grant until the user revokes it. SAF is not a mountable POSIX filesystem, so
selected Android trees will require explicit, conflict-safe synchronization
into a private POSIX mirror.

The first production bridge is now active: visible regular files and
directories under shared `/home/archphene` appear as
**Archphene Home** through one manager-owned `DocumentsProvider`. Dotfiles
remain private except for a virtual **Shell startup files** folder that maps
only `Edit .bashrc` and `Edit .bash_profile` to the two real user-owned files.
Those reviewed documents are writable but cannot be created, renamed, or
deleted through Android. Symlinks, special files, package state, runtime state,
and every other dotfile remain private.
Android's `MANAGE_DOCUMENTS` contract and per-URI grants gate other apps.
Kotlin implements the Android provider surface; Rust performs bounded
directory-descriptor traversal and mutation without following symlinks or
replacing an existing rename target.

Create/read/write/rename/delete, collision preservation, the two reviewed
startup-file opens, hidden/traversal/symlink rejection, cleanup, and real
DocumentsUI browsing pass on the x86_64 emulator and physical AArch64 Samsung.

One Android document can also be imported from the system picker, Open With,
or Share into `/home/archphene/Downloads`. Kotlin retains Android URI and
lifecycle ownership but passes only a read-only descriptor and bounded
metadata. Rust duplicates the descriptor, streams at most 16 GiB into a
private staging file, syncs it, and atomically publishes a non-replacing
destination. Duplicate names receive numbered variants and interrupted
staging is discarded explicitly on the next attempt. The exact content,
collision, restart-status, and system-picker gates pass on both targets.

The Files page can also hand visible regular files from Archphene Home to
Android without exporting another copy. **Open** resolves one document's MIME
type and sends one `ACTION_VIEW` content URI to Android's system chooser.
**Share** sends one `ACTION_SEND` URI or up to 32 deduplicated
`ACTION_SEND_MULTIPLE` URIs with an exact, same-family, or general common MIME
type. Both paths reject other authorities, directories, blank MIME types, and
oversized URIs, exclude the manager to prevent a self-import loop, and grant
read access without write access. Exact picker origin, URI, MIME, grant,
cleanup, fatal-log, single-share-regression, and full-device visual gates pass
on the x86_64 emulator and physical AArch64 Samsung.

**Export** uses the same Archphene Home source picker, then lets Android choose
an `ACTION_CREATE_DOCUMENT` destination and filename. Android owns both URI
grants and opens the source and destination descriptors. Rust duplicates those
descriptors and copies at most 16 GiB through one fixed 32 KiB buffer; file
bytes never enter Kotlin or JNI buffers. Each completed chunk publishes coarse
byte/percent progress and observes cancellation. The Files page temporarily
replaces Export with **Cancel export**; cancellation closes both descriptors
and removes the incomplete destination after provider close-time work settles.
Cancelling the destination picker creates no target.

Before writing, the manager retains the destination's persistable write grant
and a durable running record. If the process dies after writing begins,
startup removes the uncommitted destination before releasing the grant;
providers without recovery-safe access are rejected before mutation. Exact
destination bytes, unchanged Linux source, picker cancellation/retry, live
transfer cancellation, nonempty process-death recovery, durable completion,
cleanup, fatal logs, and visually inspected full-device phone/wide layouts pass
on both maintained targets.
Source name and MIME metadata come from the already bounded Archphene document
ID, so picker handoff does not make the Activity thread stat the Linux file;
the exact-device export gate rejects any Archphene-owned StrictMode violation.

The manager can now connect exactly one user-selected Android folder through
`ACTION_OPEN_DOCUMENT_TREE`. It persists only Android's scoped URI capability,
shows whether the provider granted read/write or read-only access, keeps the
old capability until a replacement is durably recorded, and offers explicit
Change and Remove actions. On process restart it checks Android's actual
persisted permissions; a removed permission becomes a visible revoked state
instead of stale success. Connect, replacement, restart persistence,
revocation, reconnect, read-only recovery, removal, and full-device visual
gates pass on both exact-ABI targets.

A connected tree can now be materialized once into
`/home/archphene/Projects/<folder>`. Kotlin walks the Android provider but
passes each read-only file descriptor and bounded path metadata directly to
Rust. Rust creates the recursive tree in private descriptor-relative staging,
enforces entry, depth, path, file, and total-byte limits, syncs every file, and
publishes the complete project with a non-replacing atomic rename. Dotfiles
needed by development projects, including `.git`, are preserved; symlinks and
unsafe paths are rejected. Interrupted staging is recovered without following
a substituted symlink. The published private project remains available if the
Android grant is later removed.

On a clean first launch, the manager explains this boundary before asking for
any Android folder. It states that Linux packages and projects use private app
storage, that a selected Android folder is copied into `~/Projects` as an
initial snapshot rather than live synchronization, and that no broad all-files
permission is required. Choose folder opens Android's system tree picker; Not
now and picker cancellation both suppress repeat prompting across process
restart. The normal Connect/Change action remains available afterward.
Semantic UI, picker-cancellation, no-repeat restart, scoped-log, and visually
inspected full-device gates pass on the exact-ABI emulator and Samsung.

The first import creates the private mirror rather than a live mount. Afterward
the user invokes explicit Sync to reconcile changes in either direction; Linux
applications continue to use the stable private POSIX path between syncs.
Exact recursive content, empty files, nested dotfiles, stale-stage recovery,
restart persistence, grant removal, and the complete mutation/crash-recovery
matrix pass on both exact-ABI targets.

The initial snapshot itself is cancellable. The Service exposes the same folder
action as Cancel while work is active, interrupts provider traversal, and
signals a shared Rust token even while the file transaction is outside the
global runtime lock. Rust checks that token before and after every fixed 32 KiB
read, rejects publication after cancellation, and discards partial staging.
Cancel, exact absence, retry, normal publication, scoped logs, and full-device
regressions pass on the emulator and Samsung.

Synchronization uses a three-way decision, never “newest timestamp wins.”
For each bounded relative path, Archphene compares the last common fingerprint
with the current private-Linux and Android fingerprints. A change on only one
side is propagated to the other. Matching changes are adopted. A delete is
propagated only when the other side still matches the baseline. Concurrent
edits, edit-versus-delete, new unequal files at the same path, and incompatible
file/directory changes are conflicts whose content must be preserved on both
sides. The allocation-free Rust decision engine and its exhaustive
edit/delete/type-change table are implemented. The canonical versioned
baseline format also has a bounded Rust codec: it binds a nonzero 128-bit
mapping identity to one safe project name and at most 10,000 sorted unique
paths, rejects invalid directory fingerprints, oversized content, corruption,
unknown versions, noncanonical order, and trailing data, and is capped at
4 MiB. Rust persists each mapping under private `var/lib/archphene/storage`
through a mode-0600 temporary file, file sync, atomic replacement, and
directory sync; stale regular temporaries recover while symlinked, nonregular,
oversized, or mapping-substituted state fails closed. The initial mirror now
creates one cryptographically random nonzero mapping identity and calculates
each file's SHA-256 in the same fixed 32 KiB copy loop, so publication does not
require a second read. It records directories, empty files, byte counts, and
digests in the baseline before exposing the project. The identity and baseline
survive manager restart and scoped-grant removal so reconnecting the same tree
does not lose its common ancestor. A debug-only physical-device gate
independently decodes the binary baseline and hashes all six recursive fixture
entries.

Explicit Sync now rescans both sides into bounded Rust manifests and pages a
fixed binary three-way plan to the Android service. Android pulls publish
through private descriptor-relative Rust staging only after revalidating both
source and destination fingerprints. Linux pushes use a verified provider
staging document, rename the previous file to a backup, publish and rehash the
replacement, then remove the backup. Exact file deletions propagate in both
directions; Android removal stages one verified backup per baseline commit.
A checksum-protected atomic journal restores that backup after pre-commit
process death or completes its removal after post-commit death. Simultaneous
file edits retain the Android bytes in a deterministic hash-suffixed Linux
conflict copy without overwriting either original. No-change, additions,
edits, deletions, conflicts, and forced process death at both journal phases
pass on physical AArch64 and the exact x86_64 emulator build with full-device
captures. Empty directory deletion now propagates in both directions, and
multiple Android removals are serially journaled and checkpointed during one
user-visible Sync. A separately locked fixed-size cancellation registry stops
Android descriptor hashes, Linux snapshots, verified opens, pulls, and
conflict copies without waiting for the main native runtime lock; exact
256 MiB cancellation fixtures on both targets leave neither partial Linux nor
Android files. Forced death before and after Android replacement publication
also proves rollback/retry and published-version finalization. One bounded SAF
bridge now gives initial-mirror and synchronization listings, metadata queries,
and descriptor opens Android cancellation signals plus 30-second deadlines.
Document mutations have no Android cancellation API, so their deadline
watchdog terminates the manager and lets the persisted journal or next
three-way scan resolve the ambiguous result. A checksum-protected bounded
history retains the latest 16 synchronization results, exact conflict paths,
and an explicit Retry route across manager restart.
During a transaction, the Files page reports only actions that actually mutate
or preserve data—not already-converged plan entries—and distinguishes pushes to
Android, pulls into Archphene, deletions on either side, and conflict
preservation. Running pull, push, and conflict counts remain visible beside the
bounded Cancel action. The exact transaction/recovery matrix and full-device
progress frames pass on both maintained ABIs.

The SAF capability itself is never presented as a POSIX mount; Linux sees the
private POSIX mirror and changes cross the boundary only during explicit Sync.
Drag-and-drop import and richer `/mnt/android` mapping status are still
planned.

## Virtual Linux Layout

The Linux process should see familiar paths, but those paths are policy-backed views, not raw unrestricted Android filesystem access.

Recommended layout:

```text
/usr
/opt
/lib
  shared verified packages and runtime support

/var/lib/pacman
/var/lib/archphene
  shared package and runtime state inside the Android sandbox

/tmp
/run/user/<uid>
  app-private temporary runtime state

/home/archphene/.cache
  shared private Linux cache

/home/archphene/.config
  shared private Linux configuration

/home/archphene/Documents
/home/archphene/Downloads
/home/archphene/Projects/<name>
  synchronized local mirror of a persisted Android tree grant

/mnt/android/documents
/mnt/android/downloads
/mnt/android/pictures
/mnt/android/media
/mnt/android/shared
  fail-closed managed aliases to the matching private home directories
```

Linux applications use normal paths. The bridge decides whether the backing
storage is the private shared Arch root, a synchronized Android grant, or a
brokered Android content URI.

The familiar home directories are conventional private directories.
Single-document imports land in `~/Downloads`; `/mnt/android/downloads` is an
alias to that same directory, not Android's public Downloads collection. A
connected Android folder is synchronized with its private
`~/Projects/<folder>` mirror and is never represented as a mountable content
URI.

## Permission Table

| Linux path or action | Backing store | Prompt behavior | Android authority |
| --- | --- | --- | --- |
| `/usr`, `/opt`, `/lib` | verified packages in the shared root | no prompt | package verification policy |
| `/var/lib/pacman`, `/var/lib/archphene` | shared private Arch data | no prompt | Archphene app sandbox |
| `/tmp`, `/run/user/<uid>` | app-private volatile data | no prompt | app sandbox |
| `/home/archphene/.cache` | shared private Arch data | no prompt | Archphene app sandbox |
| `/home/archphene/.config` | shared private Arch data | no prompt | Archphene app sandbox |
| Visible `/home/archphene` files in Android | Archphene `DocumentsProvider` | Android URI grant | Android DocumentsUI |
| Import an Android file | content URI from `ACTION_OPEN_DOCUMENT` | prompt per document unless persisted | Android DocumentsUI |
| Open a Linux file in Android | Archphene content URI sent through `ACTION_VIEW` | choose the Linux file, then an Android viewer | Archphene `DocumentsProvider` and Android resolver |
| Manager Export | source from Archphene Home; destination URI from `ACTION_CREATE_DOCUMENT` | choose the Linux source and one Android target | Archphene `DocumentsProvider` and Android DocumentsUI |
| Linux-app `Save As` | content URI from a future desktop-portal bridge | prompt per target | Android DocumentsUI |
| Connected Android folder | one persisted tree URI from `ACTION_OPEN_DOCUMENT_TREE` plus a private synchronized mirror | prompt once, again after removal/revocation | Android DocumentsUI |
| Background project file read/write | `$HOME/Projects/<name>` private POSIX mirror | no repeat prompt after sync setup | Archphene sandbox; explicit Sync crosses the persisted URI grant |
| Camera, mic, notifications, contacts | Android runtime permissions | prompt through Android permission APIs | Android PermissionController |

## Home Folder Rule

`/home/archphene` is intentionally one normal shared POSIX home for every
Linux application in the environment. This is required so editors, terminals,
language servers, compilers, and Git see the same projects and configuration.

That trust does not cross the Android boundary automatically. Only visible
home entries are exposed through **Archphene Home**; dotfiles and the rest of
the Arch root remain private. Android folders enter through explicit grants
and bridge-managed paths or mirrors under `/mnt/android` and familiar home
links.

## Background Access Rule

Background access is allowed when one of these is true:

1. The path resolves inside Archphene's private shared Arch root.
2. The path resolves to a verified package/runtime dependency in that root.
3. The path resolves under a previously granted and still-valid document tree.
4. The operation uses an Android permission the app already holds.

If none of those is true, the bridge should fail with a permission error or request a user-mediated grant. It should not silently widen access.

## Backup, revocation, and uninstall

Archphene's shared root is application-private state, not an Android shared
folder. Android backup is deliberately disabled because a partial restore of
the pacman database, package payloads, signing identity, and Linux home would
not be a coherent Arch installation. Until a complete verified archive
export/import workflow exists, users should:

- synchronize important project trees to their connected Android folders;
- use **Archphene Home** in Android Files to copy ordinary visible home files;
- use the manager's Share action for one or several visible regular files.

These routes do not include hidden configuration, package state, sockets,
symlinks, or the rest of the Arch root. They are not a full-environment backup.

Revoking or removing a connected-folder grant stops later synchronization but
does not delete the private `~/Projects/<folder>` mirror. Reconnecting requires
Android's system picker again. Removing a generated desktop launcher removes
only that Android entry; it does not remove its pacman package or shared Linux
data.

Clearing Archphene's Android storage or uninstalling the manager deletes the
shared Arch root, package database, package cache, Linux home, synchronization
records, and manager-held launcher signing key. Android also revokes the
manager's document grants. Thin launcher APKs are separate installed packages,
so Android may leave them in the app drawer, but without the manager and its
matching key/registry they fail closed and cannot start Linux applications.
Users must synchronize or copy important files before clearing data or
uninstalling. A future release needs an explicit whole-environment
backup/export and stale-launcher cleanup workflow.

## Bridge Contract

The bridge should expose a path broker API:

```text
resolve("/home/archphene/Projects/foo/main.c")
  -> requires tree grant "Projects/foo"
  -> content://...

resolve("/home/archphene/.cache/zed/index")
  -> app-private file path

resolve("/usr/lib/libgtk-3.so")
  -> read-only runtime module path
```

Linux code should not need to know whether the final backing resource is a normal file, a FUSE view, a content URI, or an Android provider operation. The bridge owns that translation.

## Consequences For Linux Apps

Editors such as VS Code, Zed, GIMP, Blender, Kdenlive, and LibreOffice need a project/document grant model:

- Linux internals stay in the shared private Arch root
- project folders are explicit user grants
- autosave and background indexing work inside granted project folders
- export/save-as goes through Android save UI
- Android grants remain attached to Archphene or a narrowly scoped launcher

Terminal-style apps such as `btop` usually need only the shared private root
plus specific brokered capabilities, not broad Android storage.

## Historical prototype evidence

The sections below describe the retained Java/prototype implementation and its
measurements. They are reference evidence, not the architecture or current
support claims of the Rust + Kotlin replacement.

## Historical per-wrapper prototype evidence

The sections below record the retired Java prototype's per-wrapper runtime-pack
storage measurements. They do not describe the approved production model.
Greenfield Archphene keeps packages, Linux processes, and user state in the one
shared manager-owned root described above; thin launcher APKs do not
materialize a second Linux home or persistent package closure.

### Validated runtime descriptor proof

Linux builds generate a bounded catalog from the exact immutable module bytes for the selected x86_64 or AArch64 release ABI and place it inside the signed manager APK. The parser rejects malformed, duplicate, traversing, unknown, and out-of-bounds entries. A non-exported provider accepts only exact catalog URIs and read mode, verifies canonical file paths, sizes, and digests, and returns read-only descriptors. The manager grants those URIs only on an explicit wrapper launch.

The emulator regression proves parser rejection and both sides of the access boundary: direct wrapper access is denied, while explicit launch-time grants permit a static ELF, a patched-glibc fixture, and a program with a separately granted `DT_NEEDED` library to execute. Package-derived closures are stored persistently once under the manager UID as SHA-256-addressed blobs. Per-pack manifests authorize only the exact hashes in that pack, so deduplication does not widen a wrapper's module access. Validation migrates legacy per-pack copies into the blob store, and garbage collection retains every blob referenced by a valid installed pack.

The current launcher creates a wrapper-private, bounded transient cache for the program and named libraries so the dynamic loader and late `dlopen()` calls receive stable path names. The cache is removed after a normal exit, purged on the next launch after an interrupted exit, and remains reclaimable Android cache data. It is not a second persistent package closure.

A debug-only descriptor-library probe keeps the executable named while exposing every library through inherited read-only descriptors. Unmodified KCalc and Mousepad both fail closed before toolkit startup: Android permits the inherited descriptors but stock glibc cannot resolve soname links through `/proc/self/fd`, producing missing `libKF6Notifications.so.6` and `libmousepad.so.0` respectively. The failed probes clean to less than 64 MiB, and immediate normal launches of both applications succeed. Archphene therefore retains the named transient cache. Removing it requires an FD-aware glibc object loader, not a Wayland or toolkit change. Reproduce the gate with `scripts/test-runtime-descriptor-libraries.sh` on debug wrappers.

Measure an attached test device with:

```bash
./scripts/measure-android-storage.sh --serial <serial> \
  --output-json tooling/build/storage/report.json \
  --output-markdown tooling/build/storage/report.md
```

The report separates APK bytes, installed code, persistent private data, transient cache, and manager runtime-store categories. Public size claims must use a documented clean install and workload; a development device snapshot includes caches and test state and is not a release baseline.

### Clean v1.0.1 x86_64 baseline

Measured on 2026-07-19 using a wiped Android 16 x86_64 AVD with 4 KB pages. Values are MiB rounded to one decimal and come from `measure-android-storage.sh` reports under the ignored `tooling/build/storage/` directory.

| State | APK | Installed code | Persistent data | Transient cache |
|---|---:|---:|---:|---:|
| Manager installed, before first launch | 89.8 | 196.1 | 0.0 | 0.0 |
| Manager after first launch | 89.8 | 196.1 | 0.1 | 0.1 |
| Terminal after first launch | 1.3 | 2.9 | 0.1 | 0.1 |
| Generated KCalc after install, before launch | 3.2 | 7.7 | 0.0 | 0.0 |
| Generated KCalc after first launch | 3.2 | 7.7 | 29.1 | 359.1 |
| Generated KCalc after normal Back/exit | 3.2 | 7.7 | 29.1 | 0.1 |

Installing KCalc made the manager retain 582.7 MiB of package state: 362.8 MiB for the content-addressed runtime-pack store and 219.8 MiB for package-runtime state. The latter included 206.4 MiB of verified package archives, which the **Clear cache and refresh all** action can delete when no package operation is active. Shared blobs are persistent because installed wrappers depend on them; unreferenced packs are garbage-collected.

The 359.1 MiB KCalc launch cache is the named private view required by the current dynamic-loader and late-`dlopen()` compatibility path. A normal app exit removes it. Android force-stop prevents lifecycle cleanup, so the cache can remain until the next wrapper launch purges stale views or Android reclaims cache storage. This peak must not be presented as steady-state application data.

### Runtime cache decision

The bounded named-module cache is required by the supported stock-glibc runtime. Persistent package bytes remain deduplicated under the manager UID; the per-wrapper cache exists only while Linux processes need pathname-based loading and is reclaimable by normal exit, the next launch, or Android cache management.
