# 16 KB x86_64 runtime

Archphene has two separate 16 KB concerns:

1. The Android APK and Archphene-owned native code must load on a 16 KB Android system.
2. Every ELF object in a Linux package closure must also be compatible with that page size.

The first concern is validated. The patched glibc 2.43 loader and runtime are built with 64 KB `PT_LOAD` alignment and a 16 KB common page size. A 64 KB-aligned dynamic probe executes through that loader inside the manager Android UID on the API 36 16 KB x86_64 emulator.

The second concern is not solved by replacing only glibc. Official Arch x86_64 packages currently contain 4 KB-aligned ELF objects. One incompatible executable or late-loaded shared object can fail after an otherwise successful launch. Archphene therefore keeps package transactions disabled on 16 KB x86_64 Android.

## Repository requirements

A supported 16 KB x86_64 package source must be a separate Archphene repository universe with these invariants:

- rebuild every native package in each published dependency closure from pinned Arch PKGBUILD sources in a clean chroot;
- link every executable, shared object, plugin, and late-loaded module with at least 16 KB `PT_LOAD` alignment;
- reject the package when any ELF machine, alignment, offset, or virtual-address constraint is incompatible;
- generate repository databases only from packages that passed the ELF audit;
- sign packages and repository metadata with a dedicated offline Archphene package key;
- embed that public key and the exact repository host/path policy in the manager release;
- never fall back to or mix official 4 KB Arch x86_64 native packages into the rebuilt universe;
- preserve source commit, PKGBUILD, patch, toolchain, package signature, and output checksum provenance.

Data-only packages marked `any` may be reused only after extraction proves that they contain no ELF objects. Architecture labels alone are not sufficient evidence.

The production gate can be removed only after the manager resolves, verifies, installs, launches, updates, and removes a representative GUI closure on a 16 KB x86_64 device, including a late `dlopen()` module.

## Reproduction

Build the patched runtime and probe in Linux/Podman, then execute it in the disposable emulator:

```bash
CONTAINER_CLI=podman SKIP_CHOWN=1 JOBS=8 scripts/build-ci-package-runtime.sh
scripts/build-16kb-glibc-probe.sh
```

```bash
scripts/test-16kb-glibc-runtime.sh --serial emulator-5556
```

The test requires a debuggable Archphene manager installed on the target. It verifies `getconf PAGE_SIZE`, copies the runtime through `run-as`, executes the aligned probe under the app UID, checks exact output, and removes test state.
