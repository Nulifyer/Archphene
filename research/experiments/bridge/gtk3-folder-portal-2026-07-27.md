# GTK 3 folder portal

Date: 2026-07-27

## Result

Archphene's private XDG FileChooser portal now routes a single directory
request to Android `ACTION_OPEN_DOCUMENT_TREE`. Android walks the selected SAF
tree and writes one bounded binary stream to a pipe. The Rust storage boundary
parses that stream directly into descriptor-relative staging and atomically
publishes a non-replacing `~/Projects/<folder>` snapshot.

Android content URIs and grants do not enter the Linux process. Limits are
10,000 entries, 64 path components, 4 KiB paths, 2 GiB per file, 16 GiB total,
and 64 KiB transfer chunks. Unsafe paths, duplicate filesystem entries,
malformed records, over-limit content, provider failures, and cancellation
leave no published partial tree.

The checksum-pinned GTK 3 compatibility module recognizes both
`GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER` and
`GTK_FILE_CHOOSER_ACTION_CREATE_FOLDER` and delegates them to the native GTK
chooser/portal path. DocumentsUI's picker retains its own create-folder
action. The end-to-end device gate used the XDG portal probe from an unmodified
generated Mousepad launcher session; a stock GTK application with a real
folder-selection workflow remains a separate caller-validation task.

## Device evidence

The reusable gate is:

```bash
./scripts/test-folder-portal.sh \
  --serial SERIAL \
  --package org.archphene.linux.p510d0cdd00e00000605a7743b9909630
```

It passed on:

- x86_64 Android emulator `emulator-5554`
- AArch64 Galaxy S22 Ultra `RFCT90AEEFA`

Each run selected an Android Downloads fixture containing nested files, an
empty file, and a `.git` directory. SHA-256 values matched the host fixtures.
A second selection published `ArchphenePortalTree (2)`, and cancelling a third
selection created no new project and left the launcher alive.

The companion timeout gate is:

```bash
./scripts/test-folder-portal-provider-timeout.sh \
  --serial SERIAL \
  --package org.archphene.linux.p510d0cdd00e00000605a7743b9909630
```

It selects the current manager's own DocumentsProvider, then asks that
debuggable provider to ignore cancellation for 60 seconds. A sliding
30-second query/open/read watchdog cancels or closes the active operation and,
after a two-second grace period, stops only the blocked launcher. Both devices
retained the manager process and published no partial project. A normal portal
run passed immediately afterward. Android 16 exercised the provider's modern
query hook, while Samsung Android 15 exercised its legacy hook.

Explicit provider failures are covered by:

```bash
./scripts/test-folder-portal-provider-failure.sh \
  --serial SERIAL \
  --package org.archphene.linux.p510d0cdd00e00000605a7743b9909630
```

The manager's debuggable DocumentsProvider rejects the generated launcher's
list and descriptor-open operations in separate runs. Both processes survive,
Rust publishes no partial project, and the portal caller receives failure.
The wrapper presents a native “Couldn’t import that folder” message over the
Linux app. Removing the fault immediately permits an exact one-file import.
The picker, feedback, and completion evidence is full-device output on both
exact ABIs.

The maximum-size real-provider gate is:

```bash
./scripts/test-folder-portal-large-tree.sh \
  --serial SERIAL \
  --package org.archphene.linux.p510d0cdd00e00000605a7743b9909630
```

It builds an exact 10,000-entry tree inside the manager's Linux home and reads
it back through DocumentsProvider and Binder. Three child cursors expose 9,997
files; four marker files contain 57 bytes and the rest include preserved empty
files. The emulator completed the import in 13 seconds and Samsung in 49
seconds. Exact counts, sampled hashes, empty content, process survival, fatal
logs, and full-device picker/completion frames pass on both.

The slow-progress gate is:

```bash
./scripts/test-folder-portal-slow-provider.sh \
  --serial SERIAL \
  --package org.archphene.linux.p510d0cdd00e00000605a7743b9909630
```

A pipe-backed DocumentsProvider yields three 16-byte chunks with two 20-second
gaps. The 40-second total exceeds the watchdog interval, but each individual
read makes progress before its sliding deadline. Both devices import exact
bytes without process loss or a false timeout. After a 500-millisecond delay,
the wrapper shows a native indeterminate indicator with explanatory text and
clears it when Mousepad returns; the full-device progress and completion
frames are inspected on both.

Full-device evidence is retained under:

- `tooling/build/folder-portal/emulator-5554/`
- `tooling/build/folder-portal/RFCT90AEEFA/`
- `tooling/build/folder-portal-provider-failure/`
- `tooling/build/folder-portal-large-tree/`
- `tooling/build/folder-portal-slow-provider/`

The picker and returned Mousepad surfaces were visually inspected with the
complete Android status and navigation areas visible.

## Remaining coverage

- Exercise the GTK interposer from an installed GTK 3 application that exposes
  a folder-selection workflow.
- Validate Qt 6/KDE, GTK 4/libadwaita, and Electron callers.
- Add MIME/name filters where the Android picker contract can preserve them.
