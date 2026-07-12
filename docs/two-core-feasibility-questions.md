# Two core feasibility questions

Date: 2026-07-09

Goal: answer whether ArchpheneOS should be an Android app running on GrapheneOS, a GrapheneOS platform fork, or both.

## Question 1: Can a normal Android app install and manage Linux apps on GrapheneOS with no VM?

Answer: partly yes for a prototype, no for the full security model.

A normal user-installed Android app can plausibly:

- download Linux package metadata from pacman/AUR/Git sources
- resolve dependencies off-device or in app-private storage
- build or fetch Linux app payloads
- generate wrapper APKs
- hand those APKs to Android's package installer
- run some Linux binaries inside a bridge under its own app sandbox
- provide an Obtanium-like catalog and update UI

This is enough for a proof-of-concept store and a limited compatibility layer.

However, a normal Android app cannot fully make generic Linux apps behave like first-class Android apps with the same isolation model. The hard parts require platform integration:

- assigning each Linux app a real Android package identity
- assigning each Linux app its own UID, SELinux domain, data directory, lifecycle, and permission surface
- mounting shared runtime modules read-only into per-app namespaces
- enforcing Linux-app access through Android permissions and portal brokers
- integrating Linux app updates with rollback, verified payloads, and downgrade protection
- preventing one Linux app from using another app's bridge, runtime, or data permissions
- making runtime packages reusable without giving them app privileges

Without OS changes, most Linux apps launched by a single bridge app collapse into the bridge app's UID and permission set. That is useful for experimentation but does not meet the goal of "Linux apps as Android apps."

Best stock-GrapheneOS prototype:

```text
LinuxAPK Manager app
  fetches package metadata
  generates per-app wrapper APKs
  bundles runtime initially
  launches Wayland/audio/file bridge inside each wrapper app
  uses Android package installer for explicit installs and updates
```

This can prove packaging, UI, display, input, audio, storage portals, and update flows. It should not claim GrapheneOS-grade isolation until the app model is moved into the OS.

## Question 2: Can we fork GrapheneOS into ArchpheneOS for generic x86 laptops with Arch packages and Android packages?

Answer: theoretically yes, but not as a simple GrapheneOS fork and not with "all the same security" on generic hardware.

There are two separate projects hiding inside this question:

```text
Project A: GrapheneOS-derived Android platform with Linux-app bridge
  realistic on supported Android-class hardware first
  needs PackageManager, SELinux, mount namespace, updater, and framework changes

Project B: GrapheneOS-like generic laptop OS
  much larger project
  needs Android device support, HALs, firmware update pipeline, verified boot, hardware attestation, graphics/audio/input/power integration, and laptop OEM support
```

GrapheneOS explicitly does not aim for broad device support. Its FAQ says broad device support is counter to the project's goals, that most devices are blocked by hardware/firmware security limits, and that GrapheneOS does not support Generic System Image usage because it requires kernel changes and cannot run on a kernel missing required functionality.

Generic x86 laptops are especially hard because they usually lack several properties GrapheneOS treats as core security requirements:

- Android Verified Boot with rollback protection for the OS and firmware
- a device-specific update pipeline for firmware, drivers, kernel, vendor code, and HALs
- StrongBox-style secure element features and hardware key attestation
- Weaver-style throttling for disk encryption secrets
- Android-compatible camera, audio, graphics, sensors, power, Wi-Fi, Bluetooth, and input HALs
- mobile-style A/B updates with automatic rollback across all firmware and OS images
- hardware memory tagging equivalent to ARM MTE
- a controlled vendor support window with prompt monthly security patches

x86 laptops often do have useful security features such as UEFI Secure Boot, TPMs, IOMMUs, SMEP/SMAP, CET/IBT on newer Intel/AMD CPUs, and measured boot options. Those can support a hardened desktop OS, but they are not the same security substrate GrapheneOS uses on modern Pixels.

## Kernel reality

Using "latest stable Linux" and using "Android production kernel" are not the same thing.

As of 2026-07-09, kernel.org lists:

```text
latest stable: 7.1.3
mainline: 7.2-rc2
```

Android production kernels come from Android common kernels / GKI branches, not directly from an arbitrary latest kernel.org stable release. Android common kernels are downstream of kernel.org and include Android-specific patches. The Android 17 compatibility matrix lists supported kernels such as `android17-6.18`, `android16-6.12`, `android15-6.6`, and older supported branches.

Therefore an ArchpheneOS platform should not start by forcing kernel.org `7.1.3` into Android. It should start with the newest Android common kernel branch supported by the Android release being used. A 7.x Android common kernel can become a target when Android provides and tests that branch.

## Recommended roadmap

### Track 1: app-level prototype on stock GrapheneOS

Purpose: prove bridge UX and compatibility quickly.

Deliverables:

- LinuxAPK Manager user app
- generated wrapper APK per Linux app
- bundled glibc/runtime initially
- app-local Wayland server
- Android Surface rendering
- SAF-backed file access
- clipboard bridge
- notification bridge
- limited audio bridge
- update UI that downloads a new wrapper APK and asks the user to install it

Expected security: same as the wrapper APK's Android sandbox, but no shared runtime mounting and no deep OS-managed Linux package identity.

### Track 2: ArchpheneOS platform fork on a supported phone/tablet first

Purpose: make Linux apps first-class Android-managed apps.

Deliverables:

- LinuxAppManager system service
- LinuxRuntimeManager for shared runtime modules
- PackageManager extensions for LAPK/LRPK metadata
- SELinux domains for Linux app classes
- per-app mount namespaces
- read-only runtime module mounts
- portal brokers for files, network, camera, mic, clipboard, notifications, sensors
- updater integration with rollback and refcounted runtime cleanup

Expected security: potentially close to the intended model if every bridge surface is mediated and audited.

### Track 3: generic/laptop ArchpheneOS

Purpose: laptop-class Android/Arch hybrid.

Deliverables:

- chosen reference laptop hardware
- Android device tree and HAL stack
- GKI/Android common kernel support
- firmware and driver update story
- verified boot and rollback story
- TPM/secure element/key attestation design
- desktop windowing and input polish
- Linux package bridge from Track 2

Expected security: can be hardened, but should not claim "same as GrapheneOS" unless the hardware and firmware meet equivalent requirements.

## Bottom line

Question 1 is feasible as a prototype Android app, but the full model needs OS changes.

Question 2 is feasible only as a major platform project. For phones/tablets, fork GrapheneOS/AOSP and add Linux-app platform integration. For generic x86 laptops, treat it as a new Android-based desktop OS with GrapheneOS-inspired hardening, not as a simple GrapheneOS port with equivalent security.

The practical project strategy is both:

```text
Build the app-level bridge first to learn fast.
Move the security boundary into ArchpheneOS platform services once the bridge proves useful.
Target supported Android-class hardware first.
Treat generic x86 laptop support as a later hardware program, not the first milestone.
```

## Source map

- GrapheneOS FAQ: https://grapheneos.org/faq
- Kernel.org: https://www.kernel.org/
- Android common kernels: https://source.android.com/docs/core/architecture/kernel/android-common
- Android GKI: https://source.android.com/docs/core/architecture/kernel/generic-kernel-image
- Arch Linux downloads: https://archlinux.org/download/
- Arch Linux ARM downloads: https://archlinuxarm.org/about/downloads
