# Linux Home And Storage Policy

Date: 2026-07-09

Goal: define how ArchpheneOS should map Linux paths to Android storage without bypassing Android permissions.

## Policy

The bridge needs two storage classes:

```text
app-private storage
  background reads/writes
  no prompt
  scoped to the generated Android app identity

user-visible storage
  open/save/project files the user interacts with
  brokered by Android UI or a previously granted capability
  shared only through explicit grants
```

This lets Linux apps behave normally while keeping Android in charge of permissions.

## Direct Answer

If the Linux app wants to create, read, or update implementation files in the background, those files belong in that app's sandbox. Examples:

- caches
- temp files
- lock files
- sqlite databases
- language-server indexes
- extension metadata
- package-manager state for that generated app
- app-owned config that should not be shared

Those operations should not prompt the user.

The emulator has now verified the basic app-private write/read path: the Android wrapper pre-created `files/linux-home/.cache`, launched a Linux payload with `HOME` pointed at `getFilesDir()/linux-home`, and the payload wrote/read `.cache/archphene-private-background.txt` with exit code `0` and no storage prompt.

The failed variant matters too: a Linux payload that tried to create the private directories itself with `mkdir` exited with `SIGSYS` (`159`). So the policy is not just "map HOME to app data". The bridge must also provide directory setup or broker calls for filesystem operations blocked by Android's app-spawned syscall filter.

If the Linux app wants to open a user document, save a user document, work inside a project folder, export media, or read/write files the user expects to see outside the app, the bridge must broker that through Android:

- `ACTION_OPEN_DOCUMENT`
- `ACTION_CREATE_DOCUMENT`
- `ACTION_OPEN_DOCUMENT_TREE`
- persisted URI permissions
- Android permission dialogs for dangerous permissions
- bridge-managed grant records

After the user grants a project folder, the bridge can continue using its content URIs without prompting again until the grant is revoked.
The Terminal companion provides the first concrete Android-facing side of this split. Its visible home entries are available as **Archphene Home** through a terminal-owned `DocumentsProvider`; dotfiles and runtime state are excluded. This permits Android Files and share/document flows to use scoped content-URI grants while commands continue to use ordinary paths inside the terminal sandbox. `archphene-import` and `archphene-export` provide explicit one-document transfers. `archphene-project add <alias>` persists a selected tree grant and maps a synchronized local POSIX mirror at `$HOME/Projects/<alias>`.

Generated GUI wrappers now expose the same policy through one manager-owned **Archphene Apps** document root. Each installed generated GUI app appears as a directory backed by its visible `files/linux-home` entries. Dotfiles are never enumerated. The wrapper endpoint is not a browsable public DocumentsProvider: manager operations require the manager signature permission and package identity. It also accepts Android-enforced, exact per-URI grants for visible-home files exported by Linux drag-and-drop; ungranted callers and dotfile paths remain denied. Android Files normally interacts with the manager DocumentsProvider.

`ACTION_VIEW` and `ACTION_EDIT` launches accept up to 32 granted documents. The bridge imports them atomically into `Documents/Android`, allocates distinct Linux names for identical display names, hashes local and provider state, and writes back only changed writable documents. If Android and Linux both changed a document, the Android version is retained as `<name>.android-conflict-<hash>` before the Linux edit is written to the granted URI. A new document sent to an active `singleTask` wrapper displays a native warning that unsaved changes may be lost; Cancel leaves the app running, while **Restart and open** closes the previous document session, terminates the Linux process tree, and recreates the same generic wrapper with the new grants. The x86_64 emulator and a physical AArch64 device both verify manager create/read/write/rename/delete, private-provider denial, active-app restart, same-name import, conflict preservation, writeback, and DocumentsUI browse through a real Mousepad wrapper.

SAF is not a POSIX filesystem and unprivileged Android cannot mount arbitrary document trees with FUSE. The current project bridge therefore synchronizes explicitly rather than intercepting every filesystem syscall. It preserves simultaneous edits as conflict copies, defers deletions, rejects symlinks/path escapes, and retains the local mirror when a mapping is removed. A future live path broker would require OS support or a descriptor/RPC interception layer with clearly documented compatibility limits.

## Virtual Linux Layout

The Linux process should see familiar paths, but those paths are policy-backed views, not raw unrestricted Android filesystem access.

Recommended layout:

```text
/usr
/opt
/lib
  read-only app/runtime modules

/var/lib/<app>
  app-private package/runtime state

/tmp
/run/user/<uid>
  app-private temporary runtime state

/home/user/.cache
  app-private by default

/home/user/.config
  app-private by default, optionally bridge-synced per app family

/home/user/Documents
/home/user/Downloads
/home/user/Projects/<name>
  synchronized local mirror of a persisted Android tree grant

/mnt/projects/<name>
  reserved future live path-broker view; not currently exposed
```

The Linux app can use normal paths. The bridge decides whether the backing storage is private app data, a read-only runtime module, or an Android content URI.

## Permission Table

| Linux path or action | Backing store | Prompt behavior | Android authority |
| --- | --- | --- | --- |
| `/usr`, `/opt`, `/lib` | read-only LAPK/LRPK modules | no prompt | package verification and mount policy |
| `/var/lib/pacman`, `/var/lib/<app>` | app-private data | no prompt | app sandbox |
| `/tmp`, `/run/user/<uid>` | app-private volatile data | no prompt | app sandbox |
| `/home/user/.cache` | app-private data | no prompt | app sandbox |
| `/home/user/.config` | app-private default, opt-in shared profile later | no prompt unless importing/exporting | app sandbox or bridge broker |
| `Open File` | content URI from `ACTION_OPEN_DOCUMENT` | prompt per document unless persisted | Android DocumentsUI |
| `Save As` or export | content URI from `ACTION_CREATE_DOCUMENT` | prompt per target | Android DocumentsUI |
| Project folder | local mirror plus persisted tree URI from `ACTION_OPEN_DOCUMENT_TREE` | prompt once per folder | Android DocumentsUI and explicit sync |
| Background project file read/write | `$HOME/Projects/<name>` local mirror | no repeat prompt | Terminal sandbox; bridge sync uses persisted URI permission |
| Camera, mic, notifications, contacts | Android runtime permissions | prompt through Android permission APIs | Android PermissionController |

## Home Folder Rule

There should not be a raw shared POSIX home directory that every Linux app can freely read and write.

Instead, `/home/user` should be a virtual home assembled from:

- app-private home pieces for implementation details
- user-visible document/project grants
- optional shared profile directories managed by policy
- stable synthetic paths for previously granted Android content URIs

That preserves desktop compatibility without turning the phone into one global mutable Linux account.

## Background Access Rule

Background access is allowed when one of these is true:

1. The path resolves to app-private storage owned by the generated Android app.
2. The path resolves to a read-only runtime dependency mounted for that app.
3. The path resolves under a previously granted and still-valid document tree.
4. The operation uses an Android permission the app already holds.

If none of those is true, the bridge should fail with a permission error or request a user-mediated grant. It should not silently widen access.

## Bridge Contract

The bridge should expose a path broker API:

```text
resolve("/home/user/Projects/foo/main.c")
  -> requires tree grant "Projects/foo"
  -> content://...

resolve("/home/user/.cache/zed/index")
  -> app-private file path

resolve("/usr/lib/libgtk-3.so")
  -> read-only runtime module path
```

Linux code should not need to know whether the final backing resource is a normal file, a FUSE view, a content URI, or an Android provider operation. The bridge owns that translation.

## Consequences For Linux Apps

Editors such as VS Code, Zed, GIMP, Blender, Kdenlive, and LibreOffice need a project/document grant model:

- app internals stay in private storage
- project folders are explicit user grants
- autosave and background indexing work inside granted project folders
- export/save-as goes through Android save UI
- app permissions remain attached to the generated Android app package

Terminal-style apps such as `btop` should usually need only app-private paths plus specific brokered capabilities, not broad storage.

## Validated Runtime Descriptor Proof

Linux builds generate a bounded catalog from the exact immutable module bytes for the selected x86_64 or AArch64 release ABI and place it inside the signed manager APK. The parser rejects malformed, duplicate, traversing, unknown, and out-of-bounds entries. A non-exported provider accepts only exact catalog URIs and read mode, verifies canonical file paths, sizes, and digests, and returns read-only descriptors. The manager grants those URIs only on an explicit wrapper launch.

The emulator regression proves parser rejection and both sides of the access boundary: direct wrapper access is denied, while explicit launch-time grants permit a static ELF, a patched-glibc fixture, and a program with a separately granted `DT_NEEDED` library to execute. Package-derived closures are stored persistently once under the manager UID as SHA-256-addressed blobs. Per-pack manifests authorize only the exact hashes in that pack, so deduplication does not widen a wrapper's module access. Validation migrates legacy per-pack copies into the blob store, and garbage collection retains every blob referenced by a valid installed pack.

The current launcher creates a wrapper-private, bounded transient cache for the program and named libraries so the dynamic loader and late `dlopen()` calls receive stable path names. The cache is removed after a normal exit, purged on the next launch after an interrupted exit, and remains reclaimable Android cache data. It is not a second persistent package closure. A future native descriptor-only launcher may eliminate this compatibility cache, but it must first prove that applications and libraries do not close inherited descriptors before late loading.

Measure an attached test device with:

```powershell
.\scripts\measure-android-storage.ps1 -Serial <serial> `
  -OutputJson tooling/build/storage/report.json `
  -OutputMarkdown tooling/build/storage/report.md
```

The report separates APK bytes, installed code, persistent private data, transient cache, and manager runtime-store categories. Public size claims must use a documented clean install and workload; a development device snapshot includes caches and test state and is not a release baseline.

## Clean v1.0.1 x86_64 Baseline

Measured on 2026-07-19 using a wiped Android 16 x86_64 AVD with 4 KB pages. Values are MiB rounded to one decimal and come from `measure-android-storage.ps1` reports under the ignored `tooling/build/storage/` directory.

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

## Next Milestones

1. Add a small descriptor/RPC path-broker C API before claiming live SAF path translation.
2. Extend GUI wrappers from individual granted documents to persisted project trees with explicit lifecycle and power policy.
3. Add a syscall probe for app-private path operations: `mkdir`, `mkdirat`, `open`, `openat`, `rename`, `unlink`, `fsync`, and `stat`.
4. Validate a descriptor-only dynamic-loader view against unmodified Qt and GTK applications before removing the transient named-module cache.

