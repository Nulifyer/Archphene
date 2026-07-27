# Arch Linux compatibility on Android

Updated: 2026-07-27

Archphene presents one conventional shared Arch userspace, but it runs inside
an ordinary Android application sandbox. “Arbitrary package installation”
means packages may join that userspace when their verified binaries and
dependencies can operate within the boundary below. It does not mean the
application has root access or boots a second Linux kernel.

## What behaves like one Arch installation

- Pacman and reviewed AUR packages share `/usr`, `/etc`, `/var`, one package
  database, `/home/archphene`, PATH, libraries, toolchains, and project files.
- A terminal, editor, compiler, debugger, and language server can call one
  another through normal Linux paths and processes.
- User files in the private root have normal POSIX names, modes, symlinks, and
  atomic rename behavior.
- Graphical packages use the shared Wayland, input, IME, clipboard, appearance,
  and Android capability bridges rather than application-specific patches.

## Android remains the operating-system boundary

All Linux processes run as the Archphene Android UID in its SELinux domain.
They share the trust level of programs in a normal single-user Arch account;
packages are not isolated from one another. They cannot gain Android root,
read another app's private data, bypass a denied Android permission, or turn a
Linux pathname into unrestricted shared-storage access.

The device supplies Android's kernel, SELinux policy, seccomp filters, process
lifecycle, memory pressure handling, and hardware drivers. Archphene adapts
compatible userspace behavior, but does not replace or weaken those controls.

## Important differences from a conventional Arch machine

- There is no systemd boot, privileged init process, normal root account,
  kernel-module loading, mount administration, or unrestricted device access.
  Packages that require those facilities need an explicit safe adapter or
  remain unsupported.
- Android may deny Linux syscalls such as namespace creation or newer
  filesystem operations. The generic bridge handles only reviewed equivalents;
  it does not silently emulate privilege.
- Package hooks and install scripts are not generally enabled yet. The current
  manager runs only reviewed maintenance adapters and must fail closed when a
  package requires unsupported mutation semantics.
- Daemons remain manager-supervised Android application processes. Android can
  stop them under lifecycle, battery, reboot, or memory-pressure rules; user
  initiated long work uses a visible foreground service where Android requires
  it.
- `/proc`, `/sys`, and `/dev` expose only information and devices available to
  the app UID. Archphene supplies a bounded compatibility view for safe
  self-process, CPU, terminal, shared-memory, and device paths; it does not
  fabricate inaccessible global telemetry.
- Android files are capabilities, not ordinary host mounts. Linux-owned work
  stays in the private POSIX root; selected Android folders use explicit,
  conflict-safe synchronization.
- Graphics currently target the manager's Wayland compositor. XWayland,
  accelerated Vulkan presentation, dmabuf/HardwareBuffer presentation, complex
  secondary windows, and physical external-display behavior are not release
  claims yet.
- Audio, camera, notifications, printing, URLs, secrets, accessibility, and
  similar Android facilities require explicit bounded brokers and, where
  applicable, Android consent. A Linux package declaration alone grants
  nothing.
- Upstream Arch x86_64 binaries assume 4 KiB ELF alignment. They are supported
  only on compatible Android page-size targets until the separate 16 KiB
  strategy is complete. AArch64 packages use the maintained Arch Linux ARM
  path.

## Compatibility decision

A package is supportable when its complete verified closure can run as an
unprivileged app-UID process using implemented filesystem, process, display,
input, and capability contracts. Archphene should report an actionable
compatibility failure when a required contract is absent. It must not add
package-specific binary patches merely to make a single desktop application
appear supported.

The current validated applications and limitations are recorded in the
[compatibility matrix](compatibility-matrix.md). Storage and uninstall
consequences are documented in the [storage model](storage.md), and trust
boundaries are documented in the [security model](security.md).
