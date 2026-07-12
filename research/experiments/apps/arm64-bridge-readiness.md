# ARM64 bridge and KCalc physical-device results

Date: 2026-07-12

## Proven result

Archphene can package and run the unmodified Arch Linux ARM AArch64 KCalc ELF as an ordinary Android application on a stock Samsung Galaxy S22 Ultra (SM-S908U). This is a native process bridge, not a VM, container, chroot, root session, or OS modification.

The tested phone reported:

- Android 15 / API 35;
- `arm64-v8a,armeabi-v7a,armeabi`;
- locked bootloader, green verified boot, and a `user` build;
- Android security patch `2025-07-01`;
- freeform-window-management support;
- 1440x3088 physical display with Android override 1080x2316 at density 450.

The phone is not a GrapheneOS-supported Pixel, so this validates ordinary Android ARM64 behavior and Samsung freeform mode. It does not claim GrapheneOS-specific validation.

## Supply chain and runtime

Official Arch Linux supports x86_64. The AArch64 package closure therefore comes from the separate Arch Linux ARM project and is identified as `distribution: archlinuxarm`, not `archlinux`.

The curated KCalc closure contains 62 signed Arch Linux ARM packages. `test-archlinuxarm-package-signatures.ps1` verifies every detached signature against the published build-key fingerprint:

```text
68B3537F39A313B3E574D06777193F152BDBE6A6
```

The stock Arch Linux ARM glibc loader is killed by Android app seccomp on startup. `build-arm64-glibc-runtime.ps1` now rebuilds glibc from pinned upstream commit `8362e8ce10b24068bacc19552c128dd10e082fd9` with `0001-android-app-seccomp-compat.patch`. The patch uses Android-permitted syscall forms and disables optional registrations blocked by the app seccomp profile. It does not grant Android permissions, alter SELinux, or change the app UID.

The source-rebuildable stripped output used in the passing run was:

| File | Bytes | SHA-256 |
|---|---:|---|
| `ld-linux-aarch64.so.1` | 200640 | `134EA3C02C9EE4197608ABE52FDA8C3979847DDB7963376A46940E2D515A323F` |
| `libc.so.6` | 1651200 | `1087299DB8C44293E74C7ADC6F0FC30CA1F4484D8F7CE61310C835BCA1802E2F` |

## Passing device gates

Run the complete physical gate with an explicit ADB serial:

```powershell
.\scripts\test-arm64-physical-regression.ps1 -Serial RFCT90AEEFA
```

The 2026-07-12 run passed:

- all 62 detached package signatures;
- ARM64 bridge-probe build, APK signature, install, and cold launch;
- GNU/Linux glibc process under the Android package UID and `untrusted_app` SELinux domain;
- AArch64 `uname`, Unix socket transfer, shared mmap, Wayland shim loading, app background/resume;
- real KCalc launch through the patched loader with the Android-owned Wayland compositor;
- native Qt popup menu switching and valid pointer grabs;
- Android-to-Wayland and Wayland-to-Android clipboard transfer without a feedback loop;
- hardware-key input for `1 + 2 =` and a visible display change;
- Samsung freeform resize from 1026x2180 to 840x1300;
- at least three xdg-shell configures/acks across resize while retaining the same Linux child PID.

Evidence is written under `artifacts/` and `tooling/build/`, including the physical bridge UI dump/screenshot and KCalc menu screenshot.

## Architecture contract

The Java compositor, document broker, URI permissions, lifecycle handling, and package discovery are architecture-independent. Native bridge libraries, the glibc compatibility runtime, package closure, and application payload are ABI-specific. Installation and launch must reject a descriptor whose Linux architecture does not match the Android device ABI.

A shared executable glibc cannot be mounted across unrelated ordinary Android app sandboxes without platform support. In the no-OS-edit design, each generated APK carries its verified runtime closure. A manager may deduplicate downloads and coordinate updates, but Android still owns each package UID, data directory, permissions, and SELinux confinement.

## Remaining work

This milestone proves KCalc on one AArch64 Android device. It does not yet provide:

- a production package manager/catalog that continuously resolves and signs arbitrary Arch Linux ARM closures;
- ARM64 Mousepad and brokered Android document workflow validation;
- Vulkan/OpenGL acceleration for demanding Linux applications;
- full accessibility, drag-and-drop, audio, printing, camera, or portal coverage;
- production signing keys, bit-for-bit reproducible glibc builds, reproducible CI images, SBOM generation, or update rollback;
- GrapheneOS-on-Pixel validation;
- generic Android or generic-PC support with GrapheneOS's device-specific security guarantees.

Primary source references:

- [Arch Linux FAQ](https://wiki.archlinux.org/title/Frequently_asked_questions)
- [Arch Linux ARM package signing](https://archlinuxarm.org/about/package-signing)
- [Arch Linux ARM KCalc package](https://archlinuxarm.org/packages/aarch64/kcalc)
- [GNU glibc source and build instructions](https://sourceware.org/glibc/started.html)
