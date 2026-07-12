# Storage Access Framework bridge emulator results

## Question

Can a Linux payload running inside the generated APK request file access without receiving broad filesystem permissions?

## Result

Yes, for a user-selected document.

The emulator validated this bridge path:

1. Android wrapper starts the packaged Linux file-request payload from `nativeLibraryDir`.
2. Linux payload prints a file portal request on stdout.
3. Android wrapper parses the request and launches `ACTION_OPEN_DOCUMENT`.
4. Android DocumentsUI displays the system file picker.
5. User selects a text file from Downloads.
6. Android wrapper receives the selected `content://` URI in `onActivityResult()`.
7. Android wrapper reads the URI through `ContentResolver`.
8. Android wrapper writes a structured file result back to Linux stdin.
9. Linux payload receives the response and prints a granted file portal decision.

This does not grant Linux code broad storage access. The Linux payload only receives the bridge result for the user-selected document.

## Bridge Messages

Linux to Android:

```text
ARCHPHENE_BRIDGE_REQUEST open_document text/plain
```

Android to Linux:

```json
{"type":"file.result","portal":"android.saf.open_document","granted":true,"reason":"android_saf","uri":"content://...","text":"..."}
```

## Test Document

The test file was pushed to emulator Downloads:

```text
/sdcard/Download/archphene-saf-test.txt
```

Contents:

```text
Archphene SAF portal test
selected by Android DocumentsUI
read by wrapper and returned to Linux
```

## Emulator Evidence

DocumentsUI showed the real Downloads root and the selectable file:

```text
Files in Downloads
archphene-saf-test.txt
96 B
```

Wrapper and Linux payload log after selection:

```text
Bridge parsed Android file portal request: open_document text/plain
Android file portal callback: granted URI content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Farchphene-saf-test.txt.
Bridge wrote Linux response: {"type":"file.result","portal":"android.saf.open_document","granted":true,"reason":"android_saf","uri":"content://...","text":"Archphene SAF portal test\nselected by Android DocumentsUI\nread by wrapper and returned to Linux\n"}
file linux stdout: linux payload received file portal response: {"type":"file.result",...}
file linux stdout: linux payload file portal decision: granted
file bridge process exit code: 0
```

Artifacts:

- `artifacts/archphene-wrapper-saf-picker-current-window.xml`
- `artifacts/archphene-wrapper-saf-picker-current.png`
- `artifacts/archphene-wrapper-saf-roots-window.xml`
- `artifacts/archphene-wrapper-saf-downloads-window.xml`
- `artifacts/archphene-wrapper-saf-after-select-window.xml`
- `artifacts/archphene-wrapper-saf-after-select.png`
- `artifacts/archphene-saf-test.txt`

## Files

- Linux payload: `prototypes/linux-payloads/file-request/main.go`
- Packaged payload: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_file_request.so`
- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

The bridge can model Android file access correctly:

- Linux asks for a file capability.
- Android shows DocumentsUI.
- User chooses the document.
- Android owns URI permission and file reading.
- Linux receives only the result the bridge intentionally returns.

This is the correct default for Linux desktop apps on GrapheneOS/Android: no broad storage permission, no direct scan of shared storage, and no bypass around the Android picker.

## Limits

This milestone validates read access for one selected text document.

Remaining work:

1. Verify cancellation and denial behavior cleanly.
2. Add request IDs and a framed bridge protocol.
3. Return file descriptors or streamed chunks instead of embedding file text in JSON.
4. Test `ACTION_CREATE_DOCUMENT` for save/write flows.
5. Test `ACTION_OPEN_DOCUMENT_TREE` for user-approved project folders.
