# Source-built glibc Android app-domain results

Date: 2026-07-10

## Validated milestone

A bridge-owned glibc 2.43 runtime built from Arch's pinned source now runs inside the Android application domain without OS changes, root, or a VM.

Emulator results:

- Legacy `access(2)` is killed by Android seccomp (`SIGSYS`, syscall 21).
- `faccessat(AT_FDCWD, ...)` is allowed, so bridge glibc implements `access()` through `faccessat`.
- Loader `--list libc.so.6`: exit `0`.
- Loader `--list` for real Arch KCalc: exit `0`, resolving 82 objects.
- Trace-mode relocation validation with `LD_WARN=1`: exit `0`, no unresolved KCalc symbols.
- Direct KCalc execution reaches normal-mode relocation of the real executable.

The runtime keeps Android's application UID, SELinux domain, filesystem sandbox, and seccomp policy. The patches avoid forbidden syscall numbers; they do not bypass Android permissions.

## Core compatibility patch

`patches/glibc/0001-android-app-seccomp-compat.patch` contains source-level changes for:

- `set_robust_list`
- `rseq`
- `openat2`
- `faccessat2`
- legacy `mkdir`
- legacy `access`
- post-fork robust-list registration
- the loader-internal x86 CPU-feature IFUNC

The patch dry-runs successfully against a clean archive of glibc commit `fdf10644d6ee345c7b5277c3fa009c1bedb92d60`.

## Bridge runner correction

The Android process runner now drains stdout and stderr concurrently. This prevents desktop applications or loader diagnostics from deadlocking when a pipe fills.

The glibc probe now distinguishes:

1. true loader `--list` for libc;
2. true loader `--list` for KCalc;
3. direct KCalc startup with Qt plugin diagnostics.

## Current boundary

The loader-internal `__x86_cpu_features` IRELATIVE resolver aborted in Android after CPU state had already been initialized. The compatibility build replaces only this internal IFUNC with an ordinary wrapper; application and library IFUNC dispatch remains enabled.

After that change, direct KCalc still exits `134` immediately after the final executable IFUNC relocations. Dependency mapping, version checks, TLS allocation, security initialization, architecture checks, libc relocation, and all 82 DSO relocations complete first.

The next iteration is to mark the post-main-relocation and TLS-install boundaries, then either patch the exact failing loader operation or advance to Qt/Wayland startup.

## Evidence

- `artifacts/kcalc-access-syscall-probe.txt`
- `artifacts/kcalc-source-glibc-access-ifunc-v1-report.txt`
- `tooling/build/glibc-archphene-runtime-x86_64/runtime-manifest.tsv`
