# Create-document bridge emulator results

## Question

Can a Linux payload ask Android to create and write a user-selected document without bypassing Android storage permissions?

## Result

Yes.

The emulator verified a new bridge request type:

```text
file.create_document
```

The Linux C payload requested a text document save. The Android wrapper launched `ACTION_CREATE_DOCUMENT`, the user confirmed the save location in DocumentsUI, the wrapper wrote the payload text through `ContentResolver.openOutputStream`, and the Linux payload received the created content URI.

## Linux Request

The C payload sent:

```text
ARCHPHENE_BRIDGE_JSON {"id":"file-create-text-c-1","type":"file.create_document","mime":"text/plain","display_name":"archphene-created-by-bridge.txt","text":"Archphene create document portal test\nwritten by Android ContentResolver\nrequested by Linux payload\n","reason":"create user selected text document"}
```

The wrapper parsed:

```text
Bridge parsed framed request id=file-create-text-c-1
Bridge parsed Android file portal request: create_document text/plain
```

## Android Flow

Android DocumentsUI opened with:

```text
archphene-created-by-bridge.txt
```

The native `SAVE` button was used. Android returned:

```text
Android create document callback: granted URI content://com.android.providers.downloads.documents/document/13.
```

The wrapper wrote the requested text with Android's `ContentResolver`.

## Linux Response

The wrapper returned:

```json
{"id":"file-create-text-c-1","type":"file.result","portal":"android.saf.create_document","granted":true,"reason":"android_saf_create_document","uri":"content:\/\/com.android.providers.downloads.documents\/document\/13","text":""}
```

The C payload printed:

```text
c payload create document decision: granted
c payload created uri: content://com.android.providers.downloads.documents/document/13
```

No mismatched response ID, unsupported request, invalid JSON, cancellation, or write failure appeared in the verified output.

## File Content Verification

The created file was verified through the emulator shell:

```text
-rw-rw---- 1 u0_a205 media_rw 100 2026-07-09 12:18 /sdcard/Download/archphene-created-by-bridge.txt
Archphene create document portal test
written by Android ContentResolver
requested by Linux payload
```

## Emulator Evidence

Artifacts:

- `artifacts/archphene-wrapper-create-document-before-open-select-window.xml`
- `artifacts/archphene-wrapper-create-document-before-open-select.png`
- `artifacts/archphene-wrapper-create-document-before-save-window.xml`
- `artifacts/archphene-wrapper-create-document-before-save.png`
- `artifacts/archphene-wrapper-create-document-after-save-window.xml`
- `artifacts/archphene-wrapper-create-document-after-save.png`

## Files

- C bridge header: `prototypes/bridge-client-c/archphene_bridge.h`
- C bridge implementation: `prototypes/bridge-client-c/archphene_bridge.c`
- Create-document demo: `prototypes/bridge-client-c/create_document_demo.c`
- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- Packaged payload: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_create_document.so`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

This is the write-side complement to `ACTION_OPEN_DOCUMENT`.

A Linux app does not need broad storage access or a direct shared-storage write path for basic save/export flows. It can ask the bridge to launch Android's user-mediated file creation UI, and Android performs the actual content-provider write under the app's normal permission model.

For desktop apps, this maps cleanly to:

- Save As
- Export
- Write new project file
- Write generated media/document output

It does not yet cover editing an already-opened document in place, directory/project access, atomic replace semantics, or background autosave.

## Next Milestones

1. Add `ACTION_OPEN_DOCUMENT_TREE` for project-folder access.
2. Add write-back to a previously opened document URI.
3. Move bridge traffic from stdio to a dedicated Unix domain socket or inherited file descriptors.
4. Add multiple in-flight request tracking.
5. Add a GUI bridge milestone: Linux app surface to Android activity/window.
