# Android JSON parser bridge emulator results

## Question

Can the Android wrapper parse and build bridge messages with a real JSON API instead of Java string scanning and manual escaping?

## Result

Yes.

The wrapper now uses Android's built-in `org.json.JSONObject` for:

- parsing Linux bridge requests
- routing by `type`, `permission`, and `mime`
- building permission results
- building Storage Access Framework results
- building error responses

The emulator re-ran the C bridge client flow successfully after the parser change.

## Verified Flow

Permission request:

```text
ARCHPHENE_BRIDGE_JSON {"id":"perm-notifications-c-1","type":"permission.request","permission":"android.permission.POST_NOTIFICATIONS","reason":"desktop notifications"}
```

Wrapper response:

```json
{"id":"perm-notifications-c-1","type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":true,"reason":"already_granted"}
```

File portal request:

```text
ARCHPHENE_BRIDGE_JSON {"id":"file-open-text-c-1","type":"file.open_document","mime":"text/plain","reason":"open user selected text document"}
```

Wrapper response:

```json
{"id":"file-open-text-c-1","type":"file.result","portal":"android.saf.open_document","granted":true,"reason":"android_saf","uri":"content:\/\/com.android.providers.downloads.documents\/document\/msf%3A40","text":"Archphene SAF portal test\nselected by Android DocumentsUI\nread by wrapper and returned to Linux\n"}
```

The escaped slashes in the URI are normal JSON output from `JSONObject`. The C client parsed the response and printed the unescaped URI:

```text
c payload selected uri: content://com.android.providers.downloads.documents/document/msf%3A40
```

## Emulator Evidence

Artifacts:

- `artifacts/archphene-wrapper-json-parser-before-select-window.xml`
- `artifacts/archphene-wrapper-json-parser-before-select.png`
- `artifacts/archphene-wrapper-json-parser-after-select-window.xml`
- `artifacts/archphene-wrapper-json-parser-after-select.png`

The final wrapper UI showed:

```text
Bridge parsed framed request id=perm-notifications-c-1
Bridge wrote Linux response: {"id":"perm-notifications-c-1","type":"permission.result",...}
permission linux stdout: c payload permission decision: granted

Bridge parsed framed request id=file-open-text-c-1
Bridge wrote Linux response: {"id":"file-open-text-c-1","type":"file.result",...}
file linux stdout: c payload file portal decision: granted
file linux stdout: c payload selected text:
file linux stdout: Archphene SAF portal test
file linux stdout: selected by Android DocumentsUI
file linux stdout: read by wrapper and returned to Linux
```

No `Bridge request JSON parse failed`, `invalid_json`, or mismatched response ID appeared in the verified output.

## Files

- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- C bridge client: `prototypes/bridge-client-c/archphene_bridge.c`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

This removes the Java-side protocol parsing shortcut. Android can now enforce bridge policy from structured messages instead of brittle substring matching.

The Linux C side still has a small prototype parser. That should be replaced when the transport moves from one-shot stdio to a socket or explicit bridge file descriptors.

## Next Milestones

1. Add `ACTION_CREATE_DOCUMENT` for save/write flows.
2. Add `ACTION_OPEN_DOCUMENT_TREE` for project-folder access.
3. Move bridge traffic from stdio to a dedicated Unix domain socket or inherited file descriptors.
4. Add multiple in-flight request tracking.
5. Add a GUI bridge milestone: Linux app surface to Android activity/window.
