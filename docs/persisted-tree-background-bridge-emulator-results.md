# Persisted Tree Background Bridge Emulator Results

## Question

Can a Linux payload create and read files in the background without prompting every time, while still staying inside Android's permission model?

## Result

Yes.

The emulator verified the capability model:

1. Linux asks Android for a project-folder grant.
2. Android launches `ACTION_OPEN_DOCUMENT_TREE`.
3. The user grants a specific subfolder.
4. Android persists the read/write URI permission.
5. The same Linux process performs background write and read requests without more prompts.
6. Android performs both operations through `DocumentsContract` and `ContentResolver`.

This is the correct bridge shape for Linux apps that need project/workspace access.

## Android Grant Boundary

The picker blocked broad roots:

```text
Can't use this folder
To protect your privacy, choose another folder
```

It also blocked the root `Download` folder. A narrower subfolder was used instead:

```text
/sdcard/Download/ArchpheneProject
```

Android then showed the real persistent folder grant confirmation:

```text
Allow Linux Hello to access files in ArchpheneProject?
This will let Linux Hello access current and future content stored in ArchpheneProject.
```

This is exactly the behavior the bridge should preserve: no ambient filesystem access, only an explicit scoped capability.

## Linux Request Sequence

The C payload sent three requests over one process/stdio session.

Tree grant:

```text
ARCHPHENE_BRIDGE_JSON {"id":"tree-open-c-1","type":"file.open_tree","reason":"grant project folder access"}
```

Background write:

```text
ARCHPHENE_BRIDGE_JSON {"id":"tree-write-c-1","type":"tree.write_file","relative_path":"archphene-background-bridge.txt","mime":"text/plain","text":"Archphene persisted tree grant test\nbackground write via Android ContentResolver\nbackground read via Android ContentResolver\n","reason":"background project file write"}
```

Background read:

```text
ARCHPHENE_BRIDGE_JSON {"id":"tree-read-c-1","type":"tree.read_file","relative_path":"archphene-background-bridge.txt","reason":"background project file read"}
```

## Android Responses

Tree grant:

```json
{"id":"tree-open-c-1","type":"file.result","portal":"android.saf.tree","granted":true,"reason":"android_saf_open_tree","uri":"content:\/\/com.android.externalstorage.documents\/tree\/primary%3ADownload%2FArchpheneProject","text":""}
```

Background write:

```json
{"id":"tree-write-c-1","type":"file.result","portal":"android.saf.tree","granted":true,"reason":"persisted_tree_write","uri":"content:\/\/com.android.externalstorage.documents\/tree\/primary%3ADownload%2FArchpheneProject\/document\/primary%3ADownload%2FArchpheneProject%2Farchphene-background-bridge.txt","text":""}
```

Background read:

```json
{"id":"tree-read-c-1","type":"file.result","portal":"android.saf.tree","granted":true,"reason":"persisted_tree_read","uri":"content:\/\/com.android.externalstorage.documents\/tree\/primary%3ADownload%2FArchpheneProject\/document\/primary%3ADownload%2FArchpheneProject%2Farchphene-background-bridge.txt","text":"Archphene persisted tree grant test\nbackground write via Android ContentResolver\nbackground read via Android ContentResolver\n"}
```

The C payload printed:

```text
c payload tree grant decision: granted
c payload tree write decision: granted
c payload tree read decision: granted
c payload background read text:
Archphene persisted tree grant test
background write via Android ContentResolver
background read via Android ContentResolver
```

No second prompt appeared for the write/read operations.

## File Content Verification

The created file was verified from the emulator shell:

```text
-rw-rw---- 1 u0_a205 media_rw 125 2026-07-09 12:29 /sdcard/Download/ArchpheneProject/archphene-background-bridge.txt
Archphene persisted tree grant test
background write via Android ContentResolver
background read via Android ContentResolver
```

## Emulator Evidence

Artifacts:

- `artifacts/archphene-wrapper-tree-before-open-select-window.xml`
- `artifacts/archphene-wrapper-tree-before-open-select.png`
- `artifacts/archphene-wrapper-tree-before-save-window.xml`
- `artifacts/archphene-wrapper-tree-before-save.png`
- `artifacts/archphene-wrapper-tree-before-grant-window.xml`
- `artifacts/archphene-wrapper-tree-before-grant.png`
- `artifacts/archphene-wrapper-tree-download-folder-window.xml`
- `artifacts/archphene-wrapper-tree-download-folder.png`
- `artifacts/archphene-wrapper-tree-project-open-window.xml`
- `artifacts/archphene-wrapper-tree-project-open.png`
- `artifacts/archphene-wrapper-tree-after-use-folder-window.xml`
- `artifacts/archphene-wrapper-tree-after-use-folder.png`
- `artifacts/archphene-wrapper-tree-after-background-window.xml`
- `artifacts/archphene-wrapper-tree-after-background.png`

The test setup created `/sdcard/Download/ArchpheneProject` with `adb shell mkdir` after the DocumentsUI new-folder dialog did not accept emulator tap input. The actual grant, write, and read were still Android-mediated through SAF.

## Files

- C bridge header: `prototypes/bridge-client-c/archphene_bridge.h`
- C bridge implementation: `prototypes/bridge-client-c/archphene_bridge.c`
- Tree background demo: `prototypes/bridge-client-c/tree_background_demo.c`
- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- Packaged payload: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_tree_background.so`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

This answers the background file access question:

- private app storage needs no prompt
- user/project storage needs a one-time scoped capability
- after the grant, background file operations can proceed without prompting
- Android remains the authority for the actual access

For desktop-style Linux apps, this maps to a project mount:

```text
/mnt/projects/ArchpheneProject -> content://com.android.externalstorage.documents/tree/primary%3ADownload%2FArchpheneProject
```

The bridge should expose that as a Linux path while internally routing reads/writes through `DocumentsContract`.

## Next Milestones

1. Add a path broker abstraction instead of hard-coded relative file requests.
2. Persist and list tree grants in bridge-managed state.
3. Add revoke handling and permission-error propagation.
4. Move transport from stdio to a socket or inherited file descriptors.
5. Add multiple in-flight request support.
6. Start the GUI surface bridge milestone.
