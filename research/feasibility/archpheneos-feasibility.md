# ArchpheneOS feasibility report

Date: 2026-07-08

Goal: one installable system that feels like GrapheneOS for security and Android/APK support, while exposing Arch repositories and desktop Linux applications on ARM or x86 hardware.

## Bottom line

The practical design is not a single merged Android/GNU userspace. The practical design is a GrapheneOS/AOSP-derived host with Arch running as an isolated guest userspace, preferably a VM on devices with Android Virtualization Framework support.

The reverse design, Arch as host with Android in Waydroid, is much easier on desktops and Linux phones, but it does not preserve GrapheneOS' security model or update chain. It gives useful APK compatibility, not GrapheneOS.

The truly fused design, where Android/GrapheneOS and Arch packages share one root filesystem and one process policy, is technically possible only as a long-running research OS. It would break the security and update properties that make GrapheneOS valuable.

## Current upstream facts

- kernel.org currently lists 7.1.3 as the latest stable Linux release, 7.2-rc2 as mainline, and 7.0.14 as EOL. Longterm kernels are still 6.18, 6.12, 6.6, 6.1, 5.15, and 5.10.
- Arch Linux currently packages `linux 7.1.3.arch1-1` for x86_64.
- Arch Linux official packages target x86-64. ARM support comes from Arch Linux ARM, a separate port providing ARMv7 hard-float and ARMv8 AArch64 packages for selected ARM boards.
- GrapheneOS' current development branch is `17`, based on Android 17. Its build docs list Arch Linux as a supported host OS for building GrapheneOS, but that is the build machine, not the target OS.
- GrapheneOS device requirements currently call for Android GKI support on Linux 6.1, 6.6, or 6.12. GrapheneOS source lists Pixel kernel prebuilts and kernel common repositories for 6.1, 6.6, and 6.12, not 7.x.
- GrapheneOS explicitly says it does not support use as a Generic System Image because it requires kernel functionality and device support that generic images cannot provide.
- Android's application model is kernel-backed UID isolation, SELinux, seccomp, verified boot, rollback protection, and framework permissions. GrapheneOS hardens that model further.

## Why a shared root OS is the wrong target

Android and Arch are both Linux-kernel systems, but above the kernel they are different operating systems:

- Android uses Bionic libc, Binder, Zygote, ART, APK/APEX packaging, Android init, per-app UIDs, Android SELinux policy, Android permissions, and verified system/vendor/product partitions.
- Arch uses glibc, systemd, pacman/libalpm, FHS-style mutable root filesystems, desktop session services, D-Bus, Wayland/X11, and a conventional GNU userspace.
- GrapheneOS' update trust model depends on signed images, verified boot, rollback protection, and carefully controlled system components.
- Pacman assumes it can own and mutate the root filesystem. That conflicts with Android's read-only verified partitions and GrapheneOS' anti-persistence model.
- Letting Arch packages install host services beside Android services would greatly expand attack surface and make SELinux policy nearly impossible to keep GrapheneOS-grade.

The merged-root version would quickly become "an Android-flavored Linux distro" rather than "GrapheneOS with Arch apps."

## Candidate designs

### Design A: GrapheneOS host plus Arch protected VM

This is the best long-term design.

Host:

- GrapheneOS/AOSP-derived system.
- APKs run natively in the Android app sandbox.
- GrapheneOS OTA/update model remains the security anchor.
- Android Verified Boot and GrapheneOS signing keys remain authoritative.

Guest:

- Arch ARM or Arch x86_64 rootfs in a VM.
- `pacman` runs inside the guest.
- Desktop apps render through a Wayland bridge or virtio-gpu/virtio-wayland path.
- Files move through explicit shared folders or Android document-provider style grants.
- Network, clipboard, microphone, camera, filesystem, and GPU access need explicit bridges.

Isolation choice:

- On ARM phones/tablets with AVF/pKVM support, use Android Virtualization Framework as the preferred boundary.
- On x86 desktop/laptop targets, use crosvm/KVM/QEMU-style VM isolation.
- Avoid privileged chroot for production security.

Pros:

- Keeps Android/GrapheneOS security properties intact.
- Arch packages are real Arch packages, not repackaged APKs.
- Works conceptually for ARM and x86.
- Similar high-level product shape to ChromeOS: Android apps plus Linux apps, with the extra constraint that the Android side should be GrapheneOS-like.

Cons:

- Big UI/product engineering task.
- Need GPU/display/input/audio/file integration.
- Need per-device virtualization support.
- Phone UX for desktop apps will be mixed unless apps adapt to touch/small screen.
- GrapheneOS upstream may reject broad host changes that increase attack surface.

Recommended first prototype:

1. Use a supported Pixel as the GrapheneOS target.
2. Build GrapheneOS `17` from source for that device.
3. Add a privileged system component that can launch a minimal Linux VM image.
4. Boot a tiny Arch ARM rootfs in the VM.
5. Expose a terminal first.
6. Then expose one Wayland GUI app.
7. Add policy around shared folders and network.

### Design B: GrapheneOS host plus unprivileged Arch userspace app

This is the fastest prototype, but it is not the final security design.

Host:

- Stock or custom GrapheneOS.

Guest:

- Arch rootfs under an Android app using proot/user-mode tricks.
- Terminal access and maybe X/Wayland-over-app rendering.

Pros:

- No root required.
- Can prototype package management and UX quickly.
- Low blast radius.

Cons:

- Not equivalent to real Arch. Kernel features, systemd, containers, udev, FUSE, low-level desktop integration, and hardware access will be limited.
- Performance and compatibility suffer.
- Not a clean security story for desktop apps.

Use this only to validate product UX: "Do I like installing Arch packages on a GrapheneOS phone?"

### Design C: Arch host plus Waydroid / Android container

This is the best desktop/Linux-first design, but it is not GrapheneOS.

Host:

- Arch Linux x86_64 or Arch Linux ARM.
- Linux 7.x kernel possible on x86_64 today.
- Wayland desktop session.

Android layer:

- Waydroid boots a full Android system in a Linux container using namespaces.
- Waydroid images are LineageOS-based by default.
- APKs run near-native for the same CPU architecture.

Pros:

- Feasible now.
- Uses Arch repos directly.
- Desktop app support is first-class.
- Good for laptops/tablets/SBCs.

Cons:

- Containerized Android is not GrapheneOS.
- Android app sandbox and verified boot are not equivalent to a GrapheneOS phone.
- Mobile hardware support is Linux-phone quality, not Android OEM quality.
- Proprietary phone firmware/camera/modem support remains a major blocker.
- Building a GrapheneOS-derived Waydroid image would still not inherit GrapheneOS device security assumptions.

Recommended use:

- Build this as the desktop proof-of-concept for "Arch apps and APKs side by side."
- Do not market it as GrapheneOS-grade security.

### Design D: Arch host plus GrapheneOS VM

This is viable for development and desktop demos.

Host:

- Arch x86_64 with KVM.

Guest:

- GrapheneOS emulator/generic build or Cuttlefish-like Android VM.

Pros:

- Useful for development.
- Can test GrapheneOS framework changes.
- Keeps Arch as the main desktop.

Cons:

- Not useful for phone installation.
- Not native device GrapheneOS.
- Android app compatibility depends on VM/device profile.

### Design E: True fused OS

This is not recommended.

It would mean porting pacman/libalpm, Arch packaging assumptions, systemd services, and a GNU desktop stack into Android while preserving GrapheneOS verified partitions, SELinux policy, OTA model, hardened malloc/libc/runtime/kernel work, and Android app compatibility.

Expected result:

- Years of work.
- Constant merge conflicts with AOSP/GrapheneOS and Arch.
- Fragile security claims.
- Hard to pass Android compatibility expectations.
- Likely impossible to keep GrapheneOS-grade updates across many devices.

## Phone installation reality

The phone target is where the constraints are strictest.

GrapheneOS is not broad-device Android. It selects devices based on hardware and firmware security. It requires timely firmware, driver, HAL and vendor updates, verified boot with rollback protection, secure element support, StrongBox/Weaver/key attestation, isolated radios and peripherals, USB controller controls, GKI support, modern ARM security features, and maintainable device support.

Arch-like broad hardware support and GrapheneOS-like hardware security pull in opposite directions. Arch can boot on many machines because it accepts a broad range of hardware states. GrapheneOS intentionally rejects most hardware because unsupported firmware and device code destroy the security story.

For a real phone:

- Start with a currently supported GrapheneOS device, not an arbitrary phone.
- Do not require Linux 7.x on the phone. Use the Android GKI LTS branch supported by the device and GrapheneOS.
- Put Arch in a VM or contained guest.
- Keep host partitions and OTA updates GrapheneOS-style.

For a Linux-first phone:

- Start with postmarketOS/Arch Linux ARM style device support plus Waydroid.
- Expect weaker Android app compatibility and much weaker GrapheneOS-style security.
- Expect camera, modem, VoLTE, GPU, suspend, power management, and firmware update gaps depending on device.

## Kernel strategy

Desktop/laptop x86_64:

- Arch host can use Linux 7.1.3 today.
- Android compatibility should be via Waydroid or VM.

ARM boards:

- Arch Linux ARM can run on selected ARMv8 boards.
- Linux 7.x depends on board support and firmware.
- Android support via Waydroid may work if binder/ashmem/binderfs and Wayland/GPU pieces are available.

GrapheneOS phones:

- Follow GrapheneOS-supported Android GKI LTS branches.
- Do not force Linux 7.x until Android GKI, vendor modules, firmware support, and GrapheneOS device support move there.
- Security updates matter more than the major kernel number.

## Recommended project plan

### Phase 0: Decision

Pick one primary goal:

- GrapheneOS-grade phone: GrapheneOS host plus Arch VM.
- Arch desktop with APKs: Arch host plus Waydroid.

Do not try to maximize both in the first prototype.

### Phase 1: Desktop proof-of-concept

Build an Arch x86_64 image with:

- Linux 7.x kernel from Arch.
- Wayland desktop.
- Waydroid.
- Hardened defaults: full disk encryption, secure boot where possible, firewall defaults, unprivileged user, apparmor/SELinux exploration, hardened_malloc experiments where feasible.

Deliverable:

- Installable Arch profile proving Linux apps and APKs side by side.

### Phase 2: GrapheneOS build proof

Build GrapheneOS `17` from source for one supported Pixel.

Deliverable:

- Reproducible local build notes.
- Signed development image.
- No Arch integration yet.

### Phase 3: Arch rootfs guest

Create minimal Arch ARM rootfs:

- pacman works.
- networking works.
- terminal works.
- shared folder works.

Start with unprivileged app/proot if AVF integration is too slow, but treat that as a temporary prototype.

### Phase 4: Secure VM integration

Move Arch rootfs into VM:

- AVF/pKVM on supported ARM Android devices.
- crosvm/KVM on x86.
- Add Android app/service front-end to launch, pause, resume, update, backup and delete guest environments.

### Phase 5: GUI integration

Add:

- Wayland bridge or remote compositor.
- Audio bridge.
- Clipboard bridge.
- File picker/shared directory.
- Per-VM network controls.
- Touch and keyboard mapping.
- Resource controls.

### Phase 6: Security review

Threat model:

- Malicious APK attacking Arch guest.
- Malicious Arch package attacking Android host.
- Compromised renderer/display bridge.
- Shared folder confused-deputy attacks.
- Clipboard exfiltration.
- GPU escape and driver attack surface.
- Package supply-chain attacks from Arch/AUR.

Rules:

- No host root for Arch packages.
- No pacman writes to Android host partitions.
- No direct host device nodes unless explicitly mediated.
- No AUR by default.
- Treat the Arch guest as less trusted than the Android host.

## Source map

GrapheneOS:

- Source overview: https://grapheneos.org/source
- Build guide: https://grapheneos.org/build
- FAQ / device support: https://grapheneos.org/faq
- Features and hardening: https://grapheneos.org/features
- Main manifest: https://github.com/GrapheneOS/platform_manifest
- Kernel repos: https://github.com/GrapheneOS/kernel_common-6.1, https://github.com/GrapheneOS/kernel_common-6.6, https://github.com/GrapheneOS/kernel_common-6.12

Android:

- GKI: https://source.android.com/docs/core/architecture/kernel/generic-kernel-image
- AVF: https://source.android.com/docs/core/virtualization
- App sandbox: https://source.android.com/docs/security/app-sandbox
- SELinux: https://source.android.com/docs/security/features/selinux
- Verified boot: https://source.android.com/docs/security/features/verifiedboot

Arch:

- Arch homepage / architecture statement: https://archlinux.org/
- Current linux package: https://archlinux.org/packages/core/x86_64/linux/
- Arch Linux ARM: https://archlinuxarm.org/
- Arch Linux ARM platforms: https://archlinuxarm.org/platforms

Android-on-Linux:

- Waydroid docs: https://docs.waydro.id/
- Waydroid install notes: https://docs.waydro.id/usage/install-on-desktops
- Waydroid-only sessions: https://docs.waydro.id/faq/setting-up-waydroid-only-sessions

Comparable architecture:

- Linux on ChromeOS: https://chromeos.dev/en/linux
- Android development on ChromeOS: https://chromeos.dev/en/android-environment

Mobile Linux:

- postmarketOS overview: https://postmarketos.org/

## Suggested next artifact

Create two tracks in this workspace:

- `research/tracks/arch-host-waydroid/`: desktop proof-of-concept scripts and install profile.
- `research/tracks/graphene-host-arch-vm/`: GrapheneOS build notes, Arch rootfs builder, and VM integration notes.

The first track is runnable sooner. The second track is the real route to the stated phone/security goal.
