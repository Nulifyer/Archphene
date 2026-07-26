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

The manager now fetches the exact AUR RPC endpoint and only the snapshot path
accepted by Rust, over Android HTTPS without ambient credentials. A versioned,
bounded binary JNI result drives an on-device review showing the community
trust warning, shared trust domain, maintainer, cgit commit, snapshot digest,
architecture-selected sources and checksums, dependencies, visible build
functions, install script, and exact PKGBUILD. Review does not enable the
official-package Install action or mutate pacman state.

For supported direct-HTTPS sources, Rust derives one bounded cache filename and
the expected SHA-256 from the retained review. Android follows at most five
HTTPS-only redirects without ambient package credentials and streams into a
Rust-owned private-cache descriptor. Rust then re-reads the complete file,
enforces per-source and aggregate limits, and verifies SHA-256 before an atomic
cache promotion. Cached bytes are rehashed before reuse; a mismatched cache
entry is deleted and downloaded again. Snapshot-local files remain covered by
the snapshot review. Insecure, unsupported, or `SKIP` remote sources fail
closed until their separate verification mechanism exists.

The reviewed recipe's complete `makedepends` and `checkdepends` are normalized
only by removing validated version operators, then resolved together with the
official `base-devel` package in one bounded pacman plan. An unavailable
official target fails closed; recursive AUR build dependencies are not yet
accepted. Resolution uses an ephemeral manager-owned database containing the
current sync catalogs and no local installed-package state; resolving against
the shared root would incorrectly omit packages an empty Builder root needs.
On the current Samsung AArch64 catalogs, the Code candidate resolves to 152
official packages and 224,514,136 archive bytes.

The manager downloads each exact archive and detached signature into its
bounded package cache. Rust requires the pinned architecture signer and exact
package name, version, and architecture metadata, retains the original
resolution bytes, then independently reverifies every member before the UI
calls the closure verified. The ephemeral resolution database is removed after
each plan. A successful cache-reuse pass on Samsung reverified all 152 members
without changing the shared pacman database. The closure still must be
reverified immediately before descriptor handoff to the Builder. Rust also
publishes a bounded canonical closure manifest containing each exact
repository/name/version/URL, archive size and SHA-256, and detached-signature
size and SHA-256. Kotlin requires it to match the retained resolution
entry-for-entry and displays the whole-manifest SHA-256; package descriptors
can be reopened only while that verified retained closure remains present.

Community build execution will use one hidden Archphene Builder companion APK,
not the manager UID and not one builder per installed Linux application. The
companion is signed by the same release identity as the manager, has a distinct
ordinary Android UID and private storage, requests no Android network
permission, publishes no launcher Activity, and exposes only an explicit
signature-permission Binder service. The manager now atomically retains the
exact Rust-reviewed snapshot, rehashes it and each verified remote source, then
passes them as bounded read-only regular file descriptors. The Builder
independently bounds and hashes every descriptor before atomically publishing a
canonical input manifest in its private storage. Package output will later
return through a manager-opened descriptor.

This is an Android UID/SELinux build boundary, not a claim that the stock
Samsung provides Linux user namespaces. Live kernel tests reject user, mount,
and network namespace creation. Android's isolated-process mode denied network
and manager storage but could not create a usable descriptor-granted build
workspace. The selected companion model has been proven on Samsung with
different manager/builder UIDs, matching signers, an `untrusted_app` builder
context, private workspace writes, denial of direct manager-data reads,
descriptor-only output, and reciprocal private-storage denial.

The Builder now scans the complete signed official closure before mutation,
rehashes every archive immediately before extraction, rejects unsafe archive
paths/types, and provisions a disposable private Arch root through Rust. Root
reuse is reset with no-follow directory-FD traversal; Android-forbidden hard
links become bounded regular copies. A filesystem sync precedes publication of
a closure-bound root manifest. The physical Samsung recovery gate provisions
152 packages, 48,271 verified archive entries, and 1,221,416,416 expanded
bytes, including executable `bash`, `makepkg`, and `fakeroot`, without changing
the shared pacman database.

The Builder's small packaged execution runtime is independently
content-addressed: Rust validates its manifest, digest-bearing filenames,
complete SHA-256 values, sizes, modes, and native-library path containment
before publishing fresh root-local aliases. The physical gate then executes
only the provisioned root's unmodified `makepkg --version`, producing
`makepkg (pacman) 7.1.0` as the separate Builder UID with no Android network
permission. This proves the loader and path bridge reach the verified root; it
does not authorize community recipe execution.

The current path still does not execute PKGBUILD, resolve a final
installed-size estimate, or install its result. Capability analysis, explicit
build approval, package-output provenance, hostile reviewed-input cleanup,
descendant supervision, bounded log/cancel behavior, and transaction recovery
remain required. Before community code can execute, every Builder-writable
path must be treated as attacker-controlled and all prior descendants must be
killed and reaped before reuse. A successful review or isolated build will not
make the eventual package isolated: once manager verification and installation
complete, its code joins the shared Archphene Linux trust domain.

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
