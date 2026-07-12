# App-spawned syscall probe VM results

Date: 2026-07-09

## Question

Which Linux syscalls are blocked when a packaged Linux payload is spawned by the Android app process?

This matters because the dynamic glibc payload packaged in the generated APK exits with status `159`, consistent with `128 + SIGSYS`.

## Result

The static syscall probe confirms that some modern Linux syscalls are killed by the app-spawned process context:

| Probe | Syscall number | App-spawned result |
| --- | ---: | --- |
| `rseq` | 334 | `SIGSYS: bad system call` |
| `statx` | 332 | allowed, returns `errno=14` for the intentionally invalid buffer |
| `clone3` | 435 | allowed, returns `errno=22` for intentionally invalid args |
| `pidfd_open` | 434 | allowed, returns fd `3` |
| `openat2` | 437 | `SIGSYS: bad system call` |

This explains why static Linux ELFs can run while a normal dynamic glibc program can still fail: the Android app-spawned child inherits a syscall filter that kills at least `rseq` and `openat2`.

## Prototype

- Probe source: `prototypes/linux-payloads/syscall-probe/main.go`
- Probe binary: `prototypes/linux-payloads/bin/syscall-probe-linux-amd64`
- Packaged probe: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_syscall_probe.so`
- Wrapper source: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- Build/install script: `scripts/build-install-lapk-wrapper-exec-test.ps1`

The probe is a static Linux x86_64 Go ELF. It is installed through the APK native library path and launched by the app process with one syscall name per child process.

## Evidence

The probe is installed as APK code:

```text
Static syscall probe
Path: /data/app/.../lib/x86_64/libarchphene_syscall_probe.so
Exists: true
Length: 2419935
canExecute: true

-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0 2419935 ... libarchphene_syscall_probe.so
```

`rseq` is killed:

```text
Static syscall probe rseq
Exit code: 2
Stdout:
probe=rseq before
Stderr:
SIGSYS: bad system call
...
rax 0x14e
```

`0x14e` is decimal `334`, the x86_64 syscall number for `rseq`.

`openat2` is killed:

```text
Static syscall probe openat2
Exit code: 2
Stdout:
probe=openat2 before
Stderr:
SIGSYS: bad system call
...
rax 0x1b5
```

`0x1b5` is decimal `437`, the x86_64 syscall number for `openat2`.

The other tested syscalls returned normally:

```text
Static syscall probe statx
Exit code: 0
Stdout:
probe=statx before
probe=statx after r1=18446744073709551615 errno=14

Static syscall probe clone3
Exit code: 0
Stdout:
probe=clone3 before
probe=clone3 after r1=18446744073709551615 errno=22

Static syscall probe pidfd_open
Exit code: 0
Stdout:
probe=pidfd_open before
probe=pidfd_open after r1=3 errno=0
```

Saved evidence:

- `artifacts/archphene-wrapper-syscall-probe-window.xml`
- `artifacts/archphene-wrapper-syscall-probe.png`

## Implications

The no-OS-edit bridge can continue, but the compatibility target is now more specific:

1. Static or mostly-static Linux payloads are the easiest path.
2. Dynamic glibc payloads need a syscall compatibility strategy.
3. The generated APK wrapper should include a syscall capability profile per Android/API/device target.
4. Package conversion cannot blindly install arbitrary Arch binaries and expect them to run.
5. For each Linux runtime, the bridge must either avoid blocked syscalls, patch the runtime, or reject the package as unsupported.

For glibc specifically:

- `GLIBC_TUNABLES=glibc.pthread.rseq=0` did not make the dynamic test pass.
- `openat2` is also blocked, so glibc or the dynamic loader may still trip a forbidden syscall during program loading.
- A patched glibc/loader or older glibc baseline may be required for no-OS-edit dynamic Linux app support.

## Next tests

1. Try a musl-linked dynamic payload and packaged musl loader.
2. Build the glibc test against an older glibc baseline.
3. Add a syscall probe for more loader/runtime candidates such as `futex_waitv`, `membarrier`, `io_uring_setup`, and `landlock_create_ruleset`.
4. Add package metadata for required/forbidden syscalls.
5. Convert the wrapper into a reusable generated APK template with runtime profile checks.

## KCalc app-spawned syscall matrix - 2026-07-09

The KCalc APK now packages a static Linux syscall probe as `libarchphene_syscall_probe.so` alongside the real Arch KCalc ELF and the packaged glibc loader. The probe is launched by `org.archphene.linux.kcalc`, so it inherits the same Android app UID, native library directory, app-private storage, and app-process syscall policy as the failing KCalc loader path.

Latest emulator result:

- Runtime extraction: 87 files / 127 MiB into `files/linux-runtime/lib`.
- Wayland filesystem socket probe: exit `0`, JNI-created `wayland-0` socket accepted the Linux client.
- Wayland abstract socket fallback probe: exit `0`.
- Direct KCalc exec: still fails with `error=2`, expected because the ELF interpreter path is not present.
- `libarchphene_ld.so --verify libarchphene_kcalc.so`: exit `0`.
- `libarchphene_ld.so --library-path files/linux-runtime/lib:nativeLibraryDir --list libarchphene_kcalc.so`: exit `159`.

Corrected app-sandbox syscall profile:

| syscall | app-spawned result |
| --- | --- |
| `open`, `openat`, `newfstatat`, `statx`, `faccessat`, `getrandom`, `memfd_create`, `membarrier`, `prlimit64`, `pidfd_open` | allowed |
| `mkdirat`, `unlinkat`, `renameat`, `clone3`, `landlock_create_ruleset` | syscall allowed; test args returned normal Linux errno |
| `openat2`, `mkdir`, `faccessat2`, `set_robust_list`, `rseq`, `io_uring_setup`, `futex_waitv` | `SIGSYS` |

This changes the next milestone: KCalc is no longer blocked by missing Qt/KF6 ELF dependencies. It is blocked by Arch glibc using syscalls Android's app process policy kills. The bridge should patch or configure its glibc profile to avoid `set_robust_list`, `rseq`, `openat2`, and `faccessat2`, and it should continue having Android/bridge code pre-create Linux base directories instead of letting first-run Linux code issue direct `mkdir`.

Evidence artifact: `artifacts/kcalc-syscall-window.xml` and `artifacts/kcalc-syscall-summary.md`.

## Glibc loader patch iteration - 2026-07-09

New evidence from the KCalc APK emulator run:

- The app now prints on-device bytes for the actual loader/libc files it executes, proving the targeted binary patches are present after APK install and runtime extraction.
- Patched bytes confirmed on-device:
  - loader `set_robust_list` site `0x140d8`: `31 c0 ...`
  - loader `rseq` site `0x1416d`: `f7 d8 ...`
  - libc startup/pthread/fork `set_robust_list`/`rseq` sites: patched as expected
  - libc `faccessat2` and `openat2` sites: patched as expected
- Even with those bytes present, `libarchphene_ld.so --list libc.so.6` exits `159` with no stdout/stderr. The failure is therefore below Qt/KF6/KCalc and happens at basic glibc loader/libc startup.
- App-spawned `/system/bin/strace` was tested but cannot produce a trace for this stop on the emulator image: it exits `1` with `Unexpected wait status 0x1f` and an empty trace file.
- `dmesg` is not readable from the unprivileged emulator shell, so kernel seccomp audit details are not available without elevated/root image access.
- Additional app-spawned loader-adjacent syscall probes show `readlinkat`, `setitimer`, `execve` with null args, `uname`, `sched_setaffinity`, `sched_getaffinity`, and `getcpu` are allowed or return ordinary Linux errno. The known killed syscalls remain `openat2`, `faccessat2`, `set_robust_list`, `rseq`, `io_uring_setup`, and `futex_waitv`.
- A diagnostic patch to make libc's exported generic `syscall()` wrapper immediately return `-ENOSYS` was tested and did not change the `--list libc` failure. That experiment was reverted from the working APK files after saving evidence.

Artifacts:

- `artifacts/kcalc-byte-report-window.xml`
- `artifacts/kcalc-strace-window.xml`
- `artifacts/kcalc-loader-probes-window.xml`
- `artifacts/kcalc-generic-syscall-patched-window.xml`
- `artifacts/kcalc-post-generic-revert-window.xml`
- `artifacts/kcalc-syscallscan.txt`

Current conclusion: ad hoc binary patching of stock Arch glibc is not a reliable path to first GUI. The no-OS-edit bridge still looks viable, but it needs a bridge-owned libc/glibc build or source-level loader instrumentation where blocked syscall fallbacks are implemented intentionally and observably. The next milestone should be one of:

1. build a controlled glibc variant for the bridge with Android-app seccomp compatibility patches and progress logging,
2. use a musl-based compatibility/runtime path for early app proofs while keeping glibc support as a separate runtime target,
3. run the same APK on a rooted/userdebug image with usable seccomp audit/modern strace to identify the exact remaining killed inline site.
