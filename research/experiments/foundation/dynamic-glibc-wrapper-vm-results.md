# Dynamic glibc wrapper VM results

Date: 2026-07-09

## Question

After proving that a static Linux ELF can execute from an APK native library directory, can the same no-OS-edit generated APK wrapper run a dynamically linked glibc Linux executable with the glibc loader and libc packaged inside the APK?

## Result

Partially.

The generated APK can package and extract:

- a dynamic Linux x86_64 executable
- a glibc dynamic loader
- `libc.so.6`

The app can execute the packaged glibc loader itself. However, launching the dynamic glibc payload through that loader from the Android app process exits with code `159`, which is consistent with `128 + SIGSYS`.

The same loader command succeeds from `adb shell` and `run-as`, so the packaged files and runtime layout are valid. The remaining blocker appears specific to processes spawned by the running Android app process, likely inherited app zygote/seccomp behavior.

## Prototype

- Wrapper source: `prototypes/lapk-wrapper-exec-test/`
- Build/install script: `scripts/build-install-lapk-wrapper-exec-test.sh`
- Dynamic payload source: `prototypes/linux-payloads/dynamic-hello/main.c`
- Dynamic payload binary: `prototypes/linux-payloads/bin/hello-dynamic-glibc-x86_64`
- Packaged dynamic executable: `lib/x86_64/libarchphene_dynamic_hello.so`
- Packaged loader: `lib/x86_64/libld.so.2`
- Packaged libc: `lib/x86_64/libc.so.6`

The dynamic payload was built with:

```text
$env:ZIG_LOCAL_CACHE_DIR = (Resolve-Path .\tooling\zig-cache).Path
$env:ZIG_GLOBAL_CACHE_DIR = (Resolve-Path .\tooling\zig-global-cache).Path
zig cc -target x86_64-linux-gnu -O2 .\prototypes\linux-payloads\dynamic-hello\main.c -o .\prototypes\linux-payloads\bin\hello-dynamic-glibc-x86_64
```

The first glibc runtime attempt used Zig cache artifacts:

- `libld.so.2`: 4776 bytes
- `libc.so.6`: 228160 bytes

That loader segfaulted in the app.

The second attempt used real Debian glibc runtime files extracted from the local `python:3.12-slim` Podman image:

- `ld-linux-x86-64.so.2`: 225672 bytes
- `libc.so.6`: 1995216 bytes

Those files were packaged as:

- `libld.so.2`
- `libc.so.6`

## VM evidence

The app sees all dynamic runtime files under the APK native library directory:

```text
Dynamic payload
Exists: true
Length: 8936
canExecute: true

Packaged glibc loader
Exists: true
Length: 225672
canExecute: true

Packaged glibc libc
Exists: true
Length: 1995216
canExecute: true
```

Android extracted all of them with executable mode and APK-data SELinux labels:

```text
-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0     8936 ... libarchphene_dynamic_hello.so
-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0  1995216 ... libc.so.6
-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0   225672 ... libld.so.2
```

Direct dynamic executable launch fails because the Linux interpreter path is not available on Android:

```text
Dynamic direct ELF launch
Exit code: -127
Start error: java.io.IOException: Cannot run program ".../libarchphene_dynamic_hello.so": error=2, No such file or directory
```

That is expected for a normal Linux dynamic ELF whose interpreter path points at Linux filesystem paths such as `/lib64/ld-linux-x86-64.so.2`.

The app can execute the packaged glibc loader:

```text
Packaged glibc loader help
Exit code: 0
Stdout:
Usage: .../libld.so.2 [OPTION]... EXECUTABLE-FILE [ARGS-FOR-PROGRAM...]
```

But launching the dynamic executable through the loader from the app fails:

```text
Dynamic packaged glibc loader launch
Command: [.../libld.so.2, --library-path, .../lib/x86_64, .../libarchphene_dynamic_hello.so]
Exit code: 159
Timed out: false
Stdout:
Stderr:
Start error:
```

Setting:

```text
GLIBC_TUNABLES=glibc.pthread.rseq=0
```

did not change the result.

Saved evidence:

- `artifacts/archphene-wrapper-dynamic-glibc-window.xml`
- `artifacts/archphene-wrapper-dynamic-glibc.png`

## Control tests

The exact same installed files succeed from `adb shell`:

```text
hello from dynamic glibc elf
pid=5137
```

The same installed files also succeed through `run-as org.archpheneos.wrapper.exec`:

```text
hello from dynamic glibc elf
pid=5140
```

This matters because it separates three issues:

1. APK extraction works.
2. The glibc loader and libc can run on the Android kernel.
3. The app-spawned process path still differs from shell/run-as, likely because the app process inherits zygote/app seccomp state.

## Current interpretation

The no-OS-edit generated APK route remains viable for:

- static Linux ELF payloads
- Android-native/Bionic bridge code
- packaged executable payloads installed as APK code

For unmodified dynamic glibc packages, the main new blocker is not SELinux file labeling. It is runtime compatibility under the app-spawned process sandbox.

The likely mitigation options are:

1. Prefer static or mostly-static payloads for the first bridge prototype.
2. Patch/rebuild glibc or use an alternate libc/runtime profile that avoids syscalls blocked by the app process seccomp policy.
3. Use Bionic/NDK ports for bridge-sensitive components.
4. Keep dynamic glibc support as a second-stage compatibility layer after syscall tracing identifies the exact blocked syscall path.
5. For full unmodified Arch package compatibility, accept that OS-level changes may still be required.

## Next tests

1. Add a small static syscall-probe payload to compare shell/run-as/app-spawned syscall behavior.
2. Try musl dynamic payloads with packaged `ld-musl-x86_64.so.1`.
3. Build the dynamic test with an older glibc baseline.
4. Test a real Arch package binary with its loader/libraries packaged.
5. Repeat on ARM64, where Android app zygote/seccomp behavior may differ in syscall details.
