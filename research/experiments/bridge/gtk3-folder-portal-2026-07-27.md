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

Full-device evidence is retained under:

- `tooling/build/folder-portal/emulator-5554/`
- `tooling/build/folder-portal/RFCT90AEEFA/`

The picker and returned Mousepad surfaces were visually inspected with the
complete Android status and navigation areas visible.

## Remaining coverage

- Exercise the GTK interposer from an installed GTK 3 application that exposes
  a folder-selection workflow.
- Validate Qt 6/KDE, GTK 4/libadwaita, and Electron callers.
- Add MIME/name filters where the Android picker contract can preserve them.
- Test slow, failing, and stalled document providers plus genuinely large
  provider-backed trees.
