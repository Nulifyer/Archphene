# Split Storage Bridge Emulator Results

## Question

Can one Linux app use the split storage policy in a single run?

Specifically:

- app-private background files go to the generated Android app sandbox with no prompt
- user-visible project files go through Android folder grant and then background read/write without repeat prompts

## Result

Yes.

The emulator verified a single Linux payload that performed both storage classes in sequence:

1. Write/read app-private cache under `$HOME/.cache`.
2. Request a project folder through `ACTION_OPEN_DOCUMENT_TREE`.
3. Accept Android's scoped folder confirmation for `ArchpheneProject`.
4. Write a user-visible project file through `DocumentsContract` and `ContentResolver`.
5. Read that project file back through the persisted tree grant.
6. Exit with code `0`.

## Private App-Sandbox Side

The Android wrapper launched the payload with private Linux paths:

```text
HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home
XDG_CACHE_HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home/.cache
XDG_CONFIG_HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home/.config
TMPDIR=/data/user/0/org.archpheneos.wrapper.exec/cache/linux-tmp
```

The Linux payload wrote and read:

```text
/data/user/0/org.archpheneos.wrapper.exec/files/linux-home/.cache/archphene-split-private.txt
```

Verified logcat output:

```text
split-storage linux stdout: split private HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home
split-storage linux stdout: split private wrote /data/user/0/org.archpheneos.wrapper.exec/files/linux-home/.cache/archphene-split-private.txt
split-storage linux stdout: split private readback:
split-storage linux stdout: Archphene split-storage private side
split-storage linux stdout: background cache write in generated app sandbox
split-storage linux stdout: no Android storage prompt expected
```

Verified with `run-as`:

```text
Archphene split-storage private side
background cache write in generated app sandbox
no Android storage prompt expected
```

No Android storage UI was involved for this app-private file.

## User-Visible Project Side

After the private write/read, the same Linux process requested a project folder grant:

```text
ARCHPHENE_BRIDGE_JSON {"id":"split-tree-open-1","type":"file.open_tree","reason":"grant split-storage project folder"}
```

Android opened DocumentsUI. The test used:

```text
/sdcard/Download/ArchpheneProject
```

Android showed the scoped confirmation:

```text
Allow Linux Hello to access files in ArchpheneProject?
This will let Linux Hello access current and future content stored in ArchpheneProject.
```

After tapping `ALLOW`, the wrapper persisted the grant:

```text
Android tree grant callback: persisted URI content://com.android.externalstorage.documents/tree/primary%3ADownload%2FArchpheneProject.
```

The Linux payload then wrote this project file without another prompt:

```text
archphene-split-user-visible.txt
```

Bridge write request:

```text
ARCHPHENE_BRIDGE_JSON {"id":"split-tree-write-1","type":"tree.write_file","relative_path":"archphene-split-user-visible.txt","mime":"text/plain","text":"Archphene split-storage user-visible side\nbackground project write after Android tree grant\nContentResolver owns the actual file access\n","reason":"split-storage background project write"}
```

Android response:

```json
{"id":"split-tree-write-1","type":"file.result","portal":"android.saf.tree","granted":true,"reason":"persisted_tree_write","uri":"content:\/\/com.android.externalstorage.documents\/tree\/primary%3ADownload%2FArchpheneProject\/document\/primary%3ADownload%2FArchpheneProject%2Farchphene-split-user-visible.txt","text":""}
```

Bridge read request:

```text
ARCHPHENE_BRIDGE_JSON {"id":"split-tree-read-1","type":"tree.read_file","relative_path":"archphene-split-user-visible.txt","reason":"split-storage background project read"}
```

Android response:

```json
{"id":"split-tree-read-1","type":"file.result","portal":"android.saf.tree","granted":true,"reason":"persisted_tree_read","uri":"content:\/\/com.android.externalstorage.documents\/tree\/primary%3ADownload%2FArchpheneProject\/document\/primary%3ADownload%2FArchpheneProject%2Farchphene-split-user-visible.txt","text":"Archphene split-storage user-visible side\nbackground project write after Android tree grant\nContentResolver owns the actual file access\n"}
```

The payload exited successfully:

```text
split-storage bridge process exit code: 0
```

Verified from shared storage:

```text
-rw-rw---- 1 u0_a205 media_rw 136 2026-07-09 12:52 /sdcard/Download/ArchpheneProject/archphene-split-user-visible.txt
Archphene split-storage user-visible side
background project write after Android tree grant
ContentResolver owns the actual file access
```

## Emulator Evidence

Artifacts:

- `artifacts/split-storage-before-folder-grant-window.xml`
- `artifacts/split-storage-before-folder-grant.png`
- `artifacts/split-storage-after-use-folder-window.xml`
- `artifacts/split-storage-after-complete-window.xml`
- `artifacts/split-storage-after-complete.png`

## Files

- C payload: `prototypes/bridge-client-c/split_storage_demo.c`
- C bridge client: `prototypes/bridge-client-c/archphene_bridge.c`
- C bridge header: `prototypes/bridge-client-c/archphene_bridge.h`
- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- Packaged payload: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_split_storage.so`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

This is the current storage contract in executable form:

```text
/home/user/.cache/*
  -> generated app private data
  -> no storage prompt
  -> background access allowed

/home/user/Projects/<granted-project>/*
  -> Android SAF tree URI
  -> prompt once for the folder grant
  -> background access allowed after the persisted grant
```

This is the right model for editor/project apps:

- VS Code/Zed language indexes and caches: app-private home
- autosave or project files: granted project tree
- Save As/export: `ACTION_CREATE_DOCUMENT`
- Open File: `ACTION_OPEN_DOCUMENT`

The next implementation milestone is a path broker that maps normal Linux paths onto these two backing stores automatically.
