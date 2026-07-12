# VM validation plan

Date: 2026-07-09

Goal: define what parts of the ArchpheneOS generic laptop idea can be tested in a VM on this Windows host.

## Direct answer

Yes, but only part of question 2 can be tested in a VM.

A VM can test:

- GrapheneOS/AOSP userspace changes
- x86_64 emulator boot
- Android PackageManager integration concepts
- generated Linux wrapper APKs
- LinuxAppManager service behavior
- LinuxRuntimeManager metadata and dependency resolution
- per-app launch flow
- Wayland-to-Android display bridge prototypes
- file, clipboard, notification, and audio bridge behavior
- updater UX and package metadata flow

A VM cannot fully validate:

- Pixel/GrapheneOS-equivalent hardware security
- real Android Verified Boot with device rollback fuses
- StrongBox secure element behavior
- Weaver disk unlock throttling
- hardware key attestation
- real firmware, modem, Wi-Fi, Bluetooth, GPU, camera, and storage isolation
- real laptop suspend/resume, power, thermal, touchpad, display, camera, and audio hardware integration
- whether generic x86 laptop hardware can honestly meet GrapheneOS-grade requirements

Therefore, VM testing is useful for platform development and compatibility, but not for proving "same security as GrapheneOS."

## Host findings

Observed from this workspace:

- `adb` is installed on PATH.
- `podman` is installed on PATH.
- `qemu-system-x86_64` is not installed on PATH.
- `repo` is not installed on PATH.
- WSL exists, but this sandbox cannot enumerate distros due access restrictions.
- Podman machine lookup is blocked by this sandbox due access restrictions on the WSL machine lockfile.

This means the host likely has enough pieces to connect to Android virtual devices, but not enough currently visible tooling to build and launch GrapheneOS/AOSP directly from this shell.

User-provided host profile:

```text
Host: SDESK
OS: Windows 11 Pro 23H2 x86_64
CPU: AMD Ryzen 7 3700X, 8 cores / 16 threads
GPU: NVIDIA GeForce RTX 5080, 15.60 GiB VRAM
RAM: 31.91 GiB
Disk C: 930.10 GiB total, 541.58 GiB free
Disk G: 3.64 TiB total, 1.80 TiB free
Shell: PowerShell 7.6.3
Terminal: Visual Studio Code 1.127.0
```

This is strong enough for Android emulator testing and borderline-but-usable for GrapheneOS/AOSP builds. GrapheneOS recommends 32 GiB memory or more for full OS builds, and this host is effectively at that threshold. The safest approach is to put source/build output on the larger `G:` drive and avoid parallel jobs high enough to exhaust memory.

## Best VM routes

### Route A: GrapheneOS SDK emulator target

This is the best first test for ArchpheneOS platform changes.

GrapheneOS documents an SDK emulator target:

```text
sdk_phone64_x86_64
```

The same docs state emulator targets do not receive full monthly security updates, do not provide all baseline security features, and are intended for development usage. That is acceptable for testing Linux app platform plumbing.

Use this route to test:

- framework changes
- PackageManager changes
- service startup
- SELinux policy experiments at emulator level
- Android app install/update behavior
- wrapper APK behavior
- Linux bridge process model

Limits:

- not a laptop hardware target
- not a verified proof of GrapheneOS security
- not representative of real GPU/camera/audio firmware

### Route B: AOSP Cuttlefish

Cuttlefish is Android's virtual-device path for AOSP.

Official AOSP docs say Cuttlefish is a virtual device dependent on host virtualization and instruct checking KVM availability. Cuttlefish is strongest on a Linux host with KVM. On a Windows host, the practical route is usually a Linux machine, a Linux VM with nested virtualization, or WSL2 if KVM exposure works.

Use this route to test:

- AOSP/GrapheneOS-derived system images
- virtual Android devices with adb
- repeated boot/test cycles
- Cuttlefish x86_64 and arm64 targets
- WebRTC UI access

Limits:

- likely requires Linux/KVM setup outside this restricted shell
- not currently launchable from this workspace without installing tooling

### Route C: Generic x86 Android laptop VM

This is useful later, but it is not the first move.

Use QEMU/virt-manager/Hyper-V/VMware-style VM only after the Android-side service model exists. This route tests whether an Android desktop image can boot with laptop-like virtual hardware.

Use this route to test:

- booting an Android x86_64 system image
- keyboard/mouse/display behavior
- desktop mode assumptions
- generic installer layout
- x86_64 Linux runtime packages
- app update UX on a larger screen

Limits:

- generic virtual hardware is not a real laptop device port
- HAL coverage will be artificial
- hardware-backed security claims remain unproven

## Recommended local sequence

### Phase 0: prepare host

Needed tooling:

```text
Android platform-tools: present
repo: missing
Linux build environment: not confirmed
Android SDK emulator: not confirmed
QEMU/Cuttlefish: missing/not confirmed
large free disk: not checked
32 GB RAM: not checked from sandbox
```

GrapheneOS build docs list official build hosts as Arch Linux, Debian bookworm, Ubuntu 24.10, and Ubuntu 24.04 LTS, with `repo`, `yarnpkg`, `zip`, and `rsync` as required packages. They also state 32 GiB memory or more and large storage requirements for full OS builds.

### Phase 1: boot stock GrapheneOS emulator target

Goal: prove this host can run a GrapheneOS-derived x86_64 emulator image.

Pass criteria:

- emulator boots
- `adb devices` sees it
- APK install works
- logcat works
- Settings opens
- storage and networking work

### Phase 2: boot a tiny ArchpheneOS-modified emulator image

Goal: prove we can modify framework/system code and run it.

Minimal change:

- add `ArchpheneOS` build marker property
- add placeholder privileged `LinuxAppManager` service or stub app
- boot emulator
- verify property/service exists via `adb shell`

Pass criteria:

- emulator boots after source modification
- system service or privileged app is present
- update/build loop is documented

### Phase 3: app-level Linux bridge inside emulator

Goal: run one Linux binary via wrapper APK.

First payload:

- static `hello`
- then dynamic glibc `hello`
- then `btop` or a simple Wayland demo

Pass criteria:

- wrapper APK installs
- process launches under app UID
- logs prove runtime mount/payload path
- UI or terminal output is visible

### Phase 4: platform-managed Linux app identity

Goal: start testing the real ArchpheneOS model.

Implement:

- Linux app manifest parser
- runtime dependency metadata
- app-private Linux home directory
- read-only runtime payload mounting simulation
- permission/capability declarations

Pass criteria:

- two Linux apps install as separate Android package identities
- each sees its own data
- each sees shared read-only runtime
- removing both marks runtime unused

## What a VM result would prove

Good VM result:

```text
ArchpheneOS platform changes are technically viable in Android userspace.
Linux app packaging/update/launch model is testable.
x86_64 Android runtime can host the bridge.
```

It would not prove:

```text
Generic laptops can match GrapheneOS security.
Latest kernel.org stable can replace Android common kernels safely.
Firmware/HAL update story is solved.
Hardware-backed attestation and verified boot are solved.
```

## Immediate conclusion

Testing question 2 in a VM is the right next step for development, but the VM target should be framed as:

```text
ArchpheneOS x86_64 emulator development target
```

not:

```text
proof that generic x86 laptops can provide GrapheneOS-equivalent security
```

The first practical milestone is to boot the GrapheneOS `sdk_phone64_x86_64` target or an AOSP Cuttlefish x86_64 target, then add a minimal ArchpheneOS platform marker and a stub Linux app manager.

## Source map

- GrapheneOS build docs: https://grapheneos.org/build
- AOSP Cuttlefish get started: https://source.android.com/docs/devices/cuttlefish/get-started
