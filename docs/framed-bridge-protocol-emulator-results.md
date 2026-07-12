# Framed bridge protocol emulator results

## Question

Can the bridge correlate Linux requests and Android responses with explicit request IDs instead of relying on ad hoc marker strings?

## Result

Yes.

The wrapper now accepts line-framed bridge requests:

```text
ARCHPHENE_BRIDGE_JSON { ...json... }
```

Each request includes an `id`, and every Android response echoes the same `id`. The emulator verified this for:

- notification permission request/result
- Storage Access Framework document request/result

## Permission Request

Linux to Android:

```text
ARCHPHENE_BRIDGE_JSON {"id":"perm-notifications-1","type":"permission.request","permission":"android.permission.POST_NOTIFICATIONS","reason":"desktop notifications"}
```

Android to Linux:

```json
{"id":"perm-notifications-1","type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":true,"reason":"already_granted"}
```

Linux verified the response ID and printed:

```text
linux payload permission decision: granted
```

## File Portal Request

Linux to Android:

```text
ARCHPHENE_BRIDGE_JSON {"id":"file-open-text-1","type":"file.open_document","mime":"text/plain","reason":"open user selected text document"}
```

Android to Linux:

```json
{"id":"file-open-text-1","type":"file.result","portal":"android.saf.open_document","granted":true,"reason":"android_saf","uri":"content://...","text":"Archphene SAF portal test\nselected by Android DocumentsUI\nread by wrapper and returned to Linux\n"}
```

Linux verified the response ID and printed:

```text
linux payload file portal decision: granted
```

## Emulator Evidence

The wrapper log showed both parsed request IDs:

```text
Bridge parsed framed request id=perm-notifications-1
Bridge wrote Linux response: {"id":"perm-notifications-1","type":"permission.result",...}

Bridge parsed framed request id=file-open-text-1
Bridge wrote Linux response: {"id":"file-open-text-1","type":"file.result",...}
```

No Linux payload reported a mismatched response ID.

Artifacts:

- `artifacts/archphene-wrapper-framed-current-window.xml`
- `artifacts/archphene-wrapper-framed-current.png`
- `artifacts/archphene-wrapper-framed-after-select-window.xml`
- `artifacts/archphene-wrapper-framed-after-select.png`

## Files

- Permission client payload: `prototypes/linux-payloads/permission-request/main.go`
- File client payload: `prototypes/linux-payloads/file-request/main.go`
- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

This is the minimum viable shape for a real bridge protocol:

- line-framed messages
- explicit message type
- explicit request ID
- structured result object
- Android-owned policy/UI decisions
- Linux-side request/result correlation

The current implementation still uses stdout/stdin and simple Java string parsing because this is an emulator milestone. The next production step is a small bridge client library plus a real parser/framer over dedicated pipes or a Unix domain socket.

## Next Milestones

1. Replace Java string matching with JSON parsing.
2. Add a Linux bridge client library so apps call helper APIs instead of printing protocol lines manually.
3. Support multiple in-flight requests over a socket transport.
4. Add `ACTION_CREATE_DOCUMENT` for save/write flows.
5. Add `ACTION_OPEN_DOCUMENT_TREE` for project-folder access.
