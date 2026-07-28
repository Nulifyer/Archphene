# File chooser MIME filters

Date: 2026-07-27

## Result

Archphene's private XDG FileChooser portal now carries MIME filters into the
Android system picker without exposing Android URI grants to Linux. The native
portal accepts the standard `filters` and `current_filter` structures, chooses
the current filter when supplied (otherwise the first filter), retains its
MIME rules, lowercases them for Android's case-sensitive matching, and sends a
bounded semicolon-delimited specification across the existing authenticated
bridge.

The manager and generated launcher validate the specification independently:
at most 16 unique types, 127 UTF-16 units per type, 2,048 UTF-16 units total,
one slash, and only MIME token characters plus a complete subtype wildcard.
A single rule becomes the intent type. Multiple disjoint or related rules use
Android's required `*/*` base type and a `String[]` `EXTRA_MIME_TYPES`.

This follows:

- [XDG FileChooser version 4](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.FileChooser.html)
- [Android `ACTION_OPEN_DOCUMENT`](https://developer.android.com/reference/kotlin/android/content/Intent.html#ACTION_OPEN_DOCUMENT:kotlin.String)
- [Android storage picker MIME example](https://developer.android.com/training/data-storage/use-cases#open-document)

XDG glob rules do not have an Android DocumentsUI equivalent. If a chosen
filter contains only globs, Archphene deliberately falls back to `*/*`.
DocumentsUI may display nonmatching documents disabled instead of hiding them;
the XDG contract likewise describes filters as user guidance rather than a
security boundary. Imported content remains subject to the existing descriptor
and size validation.

## Device gate

The reusable gate is:

```bash
./scripts/test-portal-chooser-filters.sh \
  --serial SERIAL \
  --package org.archphene.linux.p510d0cdd00e00000605a7743b9909630
```

It creates text/plain, application/json, and image/png files in Archphene Home,
sends an XDG filter containing only the first two MIME types through the real
private D-Bus portal, and opens the manager's real DocumentsProvider in
DocumentsUI. It asserts:

- the launcher reports `baseMime=*/* mimeCount=2`;
- text and JSON are visible and selectable;
- the PNG decoy is absent or disabled;
- the selected text arrives under `~/Documents/Android` with exact SHA-256;
- the portal caller receives a successful response;
- the launcher returns to Mousepad without a fatal event.

The gate passed on Android 16 x86_64 (`emulator-5554`) and Samsung Android 15
AArch64 (`RFCT90AEEFA`). Full-device picker and completion captures are under
`tooling/build/portal-chooser-filters/<serial>/` and were visually inspected
with Android status and navigation bars visible.

Afterward, the existing GTK 3 single-open, multi-open, Save As, and folder
portal suites passed on both devices, covering cancellation, exact bytes,
collision numbering, plural results, and nested folder snapshots.

## Remaining coverage

- Validate real Qt 6/KDE, GTK 4/libadwaita, and Electron callers.
- Decide whether an Android-side filter selector is warranted when an XDG
  caller supplies several named filters; the current bridge applies the
  caller's selected/default filter.
- Preserve glob-only filename filters if Android adds a compatible contract.
