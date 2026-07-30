# Electron folder portal validation — 2026-07-30

## Result

Current unmodified Code - OSS on Android 16 x86_64 and Visual Studio Code on
Samsung Android 15 AArch64 now open folders selected through Android
DocumentsUI. The generic bridge imports the tree under
`/home/archphene/Projects`, returns the logical Linux path, and does not require
an application-specific patch.

## Root cause

The Android bridge previously formed logical file results with
`Uri.Builder().scheme("file").path(path)`. That serialized as
`file:/home/archphene/...`. GLib's portal probe accepted the value, but Chromium
discarded it because it was not a canonical file URL.

`PortalFileUri` now:

- accepts only bounded paths below `/home/archphene/`;
- rejects control characters and `.` or `..` traversal components;
- percent-encodes the path as ASCII; and
- emits the standard `file:///home/archphene/...` form.

The same helper covers folder results, imported open documents, and Save As
staging results.

## Regression coverage

- Kotlin unit tests require exact space/Unicode encoding and reject paths
  outside Home, traversal, and embedded NUL.
- The native portal probe parses the response dictionary, requires exactly one
  URI for folder selection, and requires the
  `file:///home/archphene/Projects/` prefix.
- `scripts/test-folder-portal.sh` retains its nested-file, dot-directory,
  empty-file, collision, cancellation, exact-hash, scoped-log, and
  full-device checks.
- `scripts/test-archphene-code-folder-portal.sh` snapshots the complete Code
  configuration, refuses active sessions and path collisions, drives Code's
  standard Open Folder command into Android DocumentsUI, verifies the imported
  file hash and live wrapper/Linux process group, captures only full-device
  frames, rejects fatal events, removes the fixture, and restores configuration
  plus manager lifecycle.

The production gate passed on:

- `emulator-5554`: current Code - OSS, x86_64;
- `RFCT90AEEFA`: current Visual Studio Code, AArch64.

Inspected full-device evidence is retained under
`tooling/build/code-folder-portal/<serial>/{picker,code-open}.png`.
