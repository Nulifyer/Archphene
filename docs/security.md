# Security model

Archphene keeps one normal shared Arch installation inside Android's sandbox.
It does not turn pacman into a privileged Android system package manager.

## Preserved Android boundaries

Archphene itself retains:

- one Android UID and SELinux app domain;
- one private shared Arch root and Linux trust domain;
- Android-controlled lifecycle, storage grants, and system UI;
- verified manager and generated-launcher installation transactions.

Thin launcher APKs have separate Android identities so they appear normally in
the launcher and can host Android UI. They do not own separate Linux roots or
run the Linux payload. An authenticated Binder session gives the manager their
Surface and bounded input/capability results; the manager owns and supervises
the Linux process under the shared Archphene UID.

Pacman and AUR packages are therefore not isolated from one another. Any
installed Linux code joins the same trust domain and can access the shared
Linux home and toolchain, just as it can in a normal single-user Arch system.
The outer Android sandbox still blocks other Android applications and host
resources. Files, camera, microphone, location, notifications, USB, and similar
capabilities cross explicit Android-side brokers.

## Package and update trust

Before an APK installation, the manager verifies:

- HTTPS transport;
- expected SHA-256;
- expected package name;
- signer continuity for updates;
- trusted signer identity for initial installs;
- non-decreasing Android version code;
- bounded download size.

Android still presents its system installation confirmation where required.

Production manager releases are built non-debuggable and signed with a dedicated release key. Development builds remain debuggable for emulator automation and must not be distributed as production builds.

### AUR review boundary

AUR metadata and build recipes are community content, not signed official Arch
packages. Before any recipe is eligible to run, Rust now bounds and checks the
AUR v5 response and one cgit snapshot as a single review candidate. The
requested package, package base, version, selected architecture, snapshot path,
`.SRCINFO`, and PKGBUILD must agree. The snapshot reader rejects traversal,
links, special files, duplicate paths, oversized compressed or expanded
content, missing local sources, local-source checksum mismatches, and a missing
install script. It records both the exact snapshot SHA-256 and the AUR cgit
commit advertised in the snapshot's PAX header, and reports every selected
source without treating that advertised commit as an official Arch signature.

The current review core does not execute PKGBUILD, download upstream sources,
or install its result. On-device transport, full source/build/permission/disk
review UI, source verification, an unprivileged disposable build environment,
package-output provenance, and user approval remain required. A successful
review will not make the eventual package isolated: once installed, its code
joins the shared Archphene Linux trust domain.

## Important limitations

- The greenfield manager performs current official-package search, resolution,
  signature verification, shared-root install/remove, terminal execution,
  durable jobs, and Android file integration on x86_64 and AArch64.
- The real manager-owned cross-process launcher Surface protocol, desktop-entry
  registry, wrapper generation/signing, PackageInstaller handoff, and a
  package-installed Foot session now run against the shared AArch64 root.
  Broader application, capability, GPU, external-display, and x86_64
  production-client coverage remain incomplete. Legacy per-wrapper runtime-pack
  results are historical evidence, not substitutes for those production gates.
- Durable jobs already represent package and future launcher phases without
  renumbering persisted v1 states. Real launcher installation results reconcile
  across manager death; AUR build and package-mutation recovery remain pending.
- The shared Rust Wayland compositor enforces the currently implemented object, role, configure, buffer, popup, subsurface, input, and teardown contracts on x86_64 and AArch64. It still needs broader protocol coverage, independent security review, and sustained parser fuzzing before it should be treated as a hardened general compositor boundary.
- GrapheneOS-specific hardening has not been validated on a supported Pixel.
- Running on stock Android does not provide GrapheneOS firmware, verified boot policy, exploit mitigations, or security updates.
- No generic Android or laptop target can honestly claim GrapheneOS-equivalent security without device-specific platform work.

Report vulnerabilities through the private process in [SECURITY.md](../SECURITY.md). Detailed historical analysis is preserved in the [implementation gap audit](../research/audits/implementation-gap-audit.md).
