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

After the user grants a project folder, background reads/writes inside that granted folder can proceed without prompting again until the grant is revoked.
The Terminal companion now provides the first concrete Android-facing side of this split. Its visible home entries are available as **Archphene Home** through a terminal-owned `DocumentsProvider`; dotfiles and runtime state are excluded. This permits Android Files and share/document flows to use scoped content-URI grants while commands continue to use ordinary paths inside the terminal sandbox. The `archphene-import` and `archphene-export` terminal commands now provide explicit one-document transfers. Direct project-tree access and persisted tree-to-path translation remain path-broker work.

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
/home/user/Projects
  bridge-brokered user-visible storage

/mnt/projects/<name>
  persisted tree grant backed by Android SAF
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
| Project folder | persisted tree URI from `ACTION_OPEN_DOCUMENT_TREE` | prompt once per folder | Android DocumentsUI |
| Background project file read/write | previously granted tree URI | no repeat prompt | persisted URI permission |
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

The emulator regression proves parser rejection and both sides of the access boundary: direct wrapper access is denied, while explicit launch-time grants permit a static ELF, a patched-glibc fixture, and a program with a separately granted `DT_NEEDED` library to execute without wrapper copies. The dynamic path accepts a bounded descriptor/basename set and creates a wrapper-private symlink view over inherited program, loader, and library descriptors. Package-derived dependency-graph catalogs, complete application closures, durable cold-start access, and post-reboot reconciliation remain unfinished.

## Next Milestones

1. Implement a path broker that maps Linux paths to private files, runtime modules, and SAF grants.
2. Store persisted tree grants in bridge-managed state.
3. Add grant listing, revocation, and missing-grant errors.
4. Add a small path-broker C API before attempting GUI apps.
5. Add a syscall probe for app-private path operations: `mkdir`, `mkdirat`, `open`, `openat`, `rename`, `unlink`, `fsync`, and `stat`.

