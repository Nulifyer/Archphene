# C bridge client emulator results

## Question

Can Linux app code call a small bridge client library instead of printing bridge protocol lines by hand?

## Result

Yes.

The emulator verified static x86_64 musl C payloads using `archphene_bridge.h` / `archphene_bridge.c` helpers for:

- Android runtime permission requests
- Storage Access Framework document selection
- response ID correlation
- returning Android decisions and selected document content back to Linux code

This moves the prototype from "Linux payloads know the wire protocol" toward "Linux payloads call an SDK".

## Bridge API

The C prototype exposes:

```c
int archphene_request_permission(
    const char *id,
    const char *permission,
    const char *reason,
    ArchpheneBridgeResult *result);

int archphene_open_document(
    const char *id,
    const char *mime,
    const char *reason,
    ArchpheneBridgeResult *result);
```

The helper emits the current line-framed JSON request:

```text
ARCHPHENE_BRIDGE_JSON { ...json... }
```

It then reads one response frame from stdin, parses the result fields needed by the demo, and lets app code validate the echoed request ID.

## Permission Request

The C payload sent:

```text
ARCHPHENE_BRIDGE_JSON {"id":"perm-notifications-c-1","type":"permission.request","permission":"android.permission.POST_NOTIFICATIONS","reason":"desktop notifications"}
```

The wrapper returned:

```json
{"id":"perm-notifications-c-1","type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":true,"reason":"already_granted"}
```

The C app code printed:

```text
c payload permission decision: granted
```

## File Portal Request

The C payload sent:

```text
ARCHPHENE_BRIDGE_JSON {"id":"file-open-text-c-1","type":"file.open_document","mime":"text/plain","reason":"open user selected text document"}
```

The wrapper launched Android DocumentsUI. After selecting `archphene-saf-test.txt`, Android returned the selected content URI and wrapper-read text:

```json
{"id":"file-open-text-c-1","type":"file.result","portal":"android.saf.open_document","granted":true,"reason":"android_saf","uri":"content://com.android.providers.downloads.documents/document/msf%3A40","text":"Archphene SAF portal test\nselected by Android DocumentsUI\nread by wrapper and returned to Linux\n"}
```

The C app code printed:

```text
c payload file portal decision: granted
c payload selected uri: content://com.android.providers.downloads.documents/document/msf%3A40
c payload selected text:
Archphene SAF portal test
selected by Android DocumentsUI
read by wrapper and returned to Linux
```

## Build Shape

The C demos were built as static Linux x86_64 musl binaries with Zig and copied into the generated APK native library directory:

- `libarchphene_permission_request.so`
- `libarchphene_file_request.so`

Installed payload evidence from the wrapper:

```text
Linux permission request payload
Length: 1362488
canExecute: true

Linux file request payload
Length: 1362680
canExecute: true
```

## Emulator Evidence

Artifacts:

- `artifacts/archphene-wrapper-c-client-before-select-window.xml`
- `artifacts/archphene-wrapper-c-client-before-select.png`
- `artifacts/archphene-wrapper-c-client-after-select-window.xml`
- `artifacts/archphene-wrapper-c-client-after-select.png`

The final wrapper UI showed:

```text
Bridge parsed framed request id=perm-notifications-c-1
Bridge wrote Linux response: {"id":"perm-notifications-c-1","type":"permission.result",...}
permission linux stdout: c payload received bridge response: {"id":"perm-notifications-c-1",...}
permission linux stdout: c payload permission decision: granted

Bridge parsed framed request id=file-open-text-c-1
Bridge wrote Linux response: {"id":"file-open-text-c-1","type":"file.result",...}
file linux stdout: c payload received file portal response: {"id":"file-open-text-c-1",...}
file linux stdout: c payload file portal decision: granted
file linux stdout: c payload selected text:
file linux stdout: Archphene SAF portal test
file linux stdout: selected by Android DocumentsUI
file linux stdout: read by wrapper and returned to Linux
```

No mismatched response IDs were reported.

## Files

- C bridge header: `prototypes/bridge-client-c/archphene_bridge.h`
- C bridge implementation: `prototypes/bridge-client-c/archphene_bridge.c`
- Permission demo: `prototypes/bridge-client-c/permission_request_demo.c`
- File demo: `prototypes/bridge-client-c/file_request_demo.c`
- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

This confirms the bridge can be presented as a Linux-side SDK without OS edits. That matters for day-one compatibility because patched launchers, wrapper shims, or app integration layers can call stable helper APIs while Android remains responsible for permission UI and content-provider access.

The current C client is intentionally narrow:

- stdout/stdin transport
- one request at a time
- small hand-rolled JSON escaping/parsing
- no async dispatch
- no GUI/window bridge yet

## Next Milestones

1. Replace simple Java and C JSON handling with real parsers.
2. Move from stdio to a dedicated Unix domain socket or explicit inherited file descriptors.
3. Add multiple in-flight request tracking.
4. Add `ACTION_CREATE_DOCUMENT` for save/write flows.
5. Add `ACTION_OPEN_DOCUMENT_TREE` for project-folder access.
6. Add a GUI bridge milestone: Linux app surface to Android activity/window.
