# App-Private HOME Bridge Emulator Results

## Question

Can a Linux payload read and write background files in the generated Android app sandbox without prompting the user?

## Result

Yes, with one important boundary.

The emulator verified that a Linux payload can write and read files under an app-private `HOME` path when the Android wrapper pre-creates the directory structure and launches the payload with private Linux environment variables:

```text
HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home
XDG_CACHE_HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home/.cache
XDG_CONFIG_HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home/.config
TMPDIR=/data/user/0/org.archpheneos.wrapper.exec/cache/linux-tmp
```

No Android storage prompt appeared, because this is normal app-private data owned by the generated Android app UID.

## Verified Output

Logcat showed the wrapper starting the payload from the APK native library directory:

```text
Starting private HOME bridge session: /data/app/.../lib/x86_64/libarchphene_private_home.so
private-home bridge process started.
```

The Linux payload saw the private `HOME`, wrote the file, read it back, and exited successfully:

```text
private-home linux stdout: private home test HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home
private-home linux stdout: private home wrote /data/user/0/org.archpheneos.wrapper.exec/files/linux-home/.cache/archphene-private-background.txt
private-home linux stdout: private home readback:
private-home linux stdout: Archphene app-private HOME test
private-home linux stdout: background write without Android storage prompt
private-home linux stdout: scoped to generated app sandbox
private-home bridge process exit code: 0
```

The file was verified with `run-as` as the app identity:

```text
files/linux-home
files/linux-home/.cache
files/linux-home/.cache/archphene-private-background.txt
files/linux-home/.config
Archphene app-private HOME test
background write without Android storage prompt
scoped to generated app sandbox
```

## Important Failed Variant

The first version of the C payload tried to create the directories itself with `mkdir` before writing. That process printed `HOME` and then exited with code `159`, which is `SIGSYS`:

```text
private-home linux stdout: private home test HOME=/data/user/0/org.archpheneos.wrapper.exec/files/linux-home
private-home bridge process exit code: 159
```

After removing the `mkdir` calls and letting Android pre-create the app-private directories, `fopen`, `fputs`, `fread`, and `fclose` worked.

This means app-private background storage is viable, but the bridge cannot assume every normal Linux filesystem syscall is allowed from an app-spawned process. Directory creation needs one of these paths:

- Android-side directory setup before launch
- a bridge request such as `private.mkdir`
- a libc/bridge patch that routes blocked filesystem operations through Android-safe calls
- a syscall compatibility probe per Android/GrapheneOS version and CPU architecture

## Files

- C payload: `prototypes/bridge-client-c/private_home_demo.c`
- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- Packaged payload: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_private_home.so`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

This confirms the storage policy split:

- app-internal background files can live in app-private storage with no prompt
- user-visible files and folders still need Android UI or persisted grants
- the bridge must own Linux path setup and syscall compatibility details

For Linux apps, `/home/user/.cache`, `/home/user/.config`, `/tmp`, and app-owned package state should map to private app storage. The bridge should create required base directories and provide broker calls for directory operations that hit Android's app-spawned syscall filter.

## Next Milestones

1. Add a path broker API for app-private files and directories.
2. Add a `private.mkdir` or `path.ensure_dir` bridge request and test it from C.
3. Probe `mkdir`, `mkdirat`, `open`, `openat`, `rename`, `unlink`, `fsync`, and `stat` from app-spawned payloads.
4. Route user-visible paths to SAF tree grants and app-private paths to private data.
5. Add a small FUSE-like or library-level path layer so Linux apps can keep normal file paths.
