# Dynamic musl wrapper VM results

Date: 2026-07-09

## Question

Can the generated APK wrapper run a dynamically linked musl Linux executable from an Android app process without OS-level edits?

## Result

Yes.

The wrapper APK successfully packaged:

- a dynamic Linux x86_64 musl executable
- a musl loader extracted from an Alpine-based local image

The Android app then launched the dynamic musl executable through the packaged musl loader. It exited successfully from the same app-spawned context where the dynamic glibc payload fails with a `SIGSYS`-style exit.

## Prototype

- Dynamic payload source: `prototypes/linux-payloads/dynamic-hello/main.c`
- Dynamic musl binary: `prototypes/linux-payloads/bin/hello-dynamic-musl-x86_64`
- Packaged musl payload: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_dynamic_musl.so`
- Extracted musl loader: `tooling/musl-alpine-x86_64/ld-musl-x86_64.so.1`
- Packaged musl loader: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libld-musl-x86_64.so`
- Wrapper source: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- Build/install script: `scripts/build-install-lapk-wrapper-exec-test.ps1`

The dynamic musl executable was built with Zig:

```powershell
$env:ZIG_LOCAL_CACHE_DIR = (Resolve-Path .\tooling\zig-cache).Path
$env:ZIG_GLOBAL_CACHE_DIR = (Resolve-Path .\tooling\zig-global-cache).Path
zig cc -target x86_64-linux-musl -dynamic -O2 .\prototypes\linux-payloads\dynamic-hello\main.c -o .\prototypes\linux-payloads\bin\hello-dynamic-musl-x86_64
```

The musl loader was extracted from the locally available `postgres:16-alpine` image:

```powershell
podman cp "<container>:/lib/ld-musl-x86_64.so.1" .\tooling\musl-alpine-x86_64\ld-musl-x86_64.so.1
```

It was renamed to `libld-musl-x86_64.so` so Android's native library extraction path would install it under the APK native library directory.

## VM evidence

Android extracted both musl files as APK native code:

```text
Dynamic musl payload
Exists: true
Length: 6232
canExecute: true

Packaged musl loader
Exists: true
Length: 666216
canExecute: true
```

Both files are executable and labeled as APK data:

```text
-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0 6232 ... libarchphene_dynamic_musl.so
-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0 666216 ... libld-musl-x86_64.so
```

Direct execution of the dynamic musl binary fails, as expected, because the Linux interpreter path does not exist on Android:

```text
Dynamic musl direct ELF launch
Exit code: -127
Start error: java.io.IOException: Cannot run program ".../libarchphene_dynamic_musl.so": error=2, No such file or directory
```

Explicit execution through the packaged musl loader succeeds:

```text
Dynamic packaged musl loader launch
Command: [.../libld-musl-x86_64.so, .../libarchphene_dynamic_musl.so]
Exit code: 0
Timed out: false
Stdout:
hello from dynamic glibc elf
pid=5746
Stderr:
Start error:
```

The stdout text says `glibc` because this test reused the same tiny C source as the glibc test. The successful runtime path is musl.

Saved evidence:

- `artifacts/archphene-wrapper-musl-window.xml`
- `artifacts/archphene-wrapper-musl.png`

## Comparison With glibc

The dynamic glibc payload also installs correctly, and the app can run the glibc loader's `--help` path. However, launching the glibc payload through the glibc loader exits with status `159`.

The syscall probe shows app-spawned child processes receive `SIGSYS` for at least:

- `rseq`
- `openat2`

The dynamic musl payload avoids the tested blocked path and runs successfully.

## Architecture Implication

For a no-OS-edit Archphene bridge, the first practical dynamic runtime target should be musl, not glibc.

That does not mean generic Arch packages work unchanged. Arch packages are generally glibc-based. But it gives us a viable compatibility lane:

1. Convert or rebuild packages against musl for the first working app catalog.
2. Package each app as a real APK.
3. Install the musl loader and app-specific libraries into the APK native library directory.
4. Launch the app through the packaged musl loader.
5. Use the bridge SDK for Android permissions, storage, lifecycle, and UI integration.

For unmodified Arch/glibc packages, more runtime work is still required:

- patched glibc or older glibc baseline
- syscall compatibility profiling
- package rejection when forbidden syscalls are required
- possible app-process launch strategy changes

## Next Tests

1. Package a musl dynamic payload with an additional shared library.
2. Try a real small CLI package rebuilt for musl.
3. Add a minimal Unix-socket bridge between Android Java and the musl process.
4. Create a generated APK template that declares runtime type: `static`, `musl-dynamic`, or `glibc-dynamic-experimental`.
5. Test the same musl wrapper on ARM64.
