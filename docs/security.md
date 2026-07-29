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

### Electron reduced-isolation compatibility

Chromium normally creates an additional Linux sandbox between its browser and
renderer processes. Stock Android devices do not expose the namespace
operations that Chromium needs to create that inner sandbox from Archphene.
Archphene therefore provides a default-off compatibility setting that may add
`--no-sandbox` and `--disable-dev-shm-usage` on the next launch.

Enabling the setting requires an explicit warning and confirmation. The policy
is generic: it is applied only when the exact launcher has static installed-ELF
or retained supervised-process evidence of Chromium topology. Package names and
application labels are not used, unrelated Linux applications receive no
flags, and existing flags are not duplicated. Consent is captured once for a
launcher session so a settings change does not alter an already running
process tree.

The Linux application remains inside Archphene's Android UID and SELinux
sandbox, but its Chromium renderer processes are not separately isolated from
the rest of that shared Archphene trust domain. This is a compatibility
tradeoff, not equivalent Chromium sandboxing.

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
content, missing local sources, local-source checksum mismatches, and any
missing install script required by the selected split-package closure. It
records both the exact snapshot SHA-256 and the AUR cgit
commit advertised in the snapshot's PAX header, and reports every selected
source without treating that advertised commit as an official Arch signature.
Common package-base `provides` declarations are accepted as inherited
`.SRCINFO` metadata; if a dependency tries to use one across multiple split
outputs, the ambiguous provider still fails closed.

The manager now fetches the exact AUR RPC endpoint and only the snapshot path
accepted by Rust, over Android HTTPS without ambient credentials. A versioned,
bounded binary JNI result drives an on-device review showing the community
trust warning, shared trust domain, maintainer, cgit commit, snapshot digest,
architecture-selected sources and checksums, dependencies, visible build
functions, every required output's install script, and exact PKGBUILD. Review
does not enable the official-package Install action or mutate pacman state.

For supported direct-HTTPS sources, Rust derives one bounded cache filename and
the expected SHA-256 from the retained review. Android follows at most five
HTTPS-only redirects without ambient package credentials and streams into a
Rust-owned private-cache descriptor. Rust then re-reads the complete file,
enforces per-source and aggregate limits, and verifies SHA-256 before an atomic
cache promotion. Cached bytes are rehashed before reuse; a mismatched cache
entry is deleted and downloaded again. Snapshot-local files remain covered by
the snapshot review. Insecure, unsupported, or `SKIP` remote sources fail
closed until their separate verification mechanism exists.

The reviewed recipe's complete runtime `depends`, `makedepends`, and
`checkdepends` are normalized only by removing validated version operators,
then resolved together with the official `base-devel` package in one bounded
pacman plan. An unavailable official target fails closed; recursive AUR
dependencies are not yet accepted. Resolution uses an ephemeral manager-owned
database containing the current sync catalogs and no local installed-package
state; resolving against the shared root would incorrectly omit packages an
empty Builder root needs. On the current Samsung AArch64 catalogs, the Code
candidate resolves to 250 official packages and 321,419,288 archive bytes.

The manager downloads each exact archive and detached signature into its
bounded package cache. Rust requires the pinned architecture signer and exact
package name, version, and architecture metadata, retains the original
resolution bytes, then independently reverifies every member before the UI
calls the closure verified. The ephemeral resolution database is removed after
each plan. The successful Samsung gate reverified all 250 members before
descriptor handoff to the Builder. Rust also
publishes a bounded canonical closure manifest containing each exact
repository/name/version/URL, archive size and SHA-256, and detached-signature
size and SHA-256. Kotlin requires it to match the retained resolution
entry-for-entry and displays the whole-manifest SHA-256; package descriptors
can be reopened only while that verified retained closure remains present.

Community build execution uses one hidden Archphene Builder companion APK,
not the manager UID and not one builder per installed Linux application. The
companion is signed by the same release identity as the manager, has a distinct
ordinary Android UID and private storage, requests no Android network
permission, publishes no launcher Activity, and exposes only an explicit
signature-permission Binder service. The manager now atomically retains the
exact Rust-reviewed snapshot, rehashes it and each verified remote source, then
passes them as bounded read-only regular file descriptors. The Builder
independently bounds and hashes every descriptor before atomically publishing a
canonical input manifest in its private storage. This mutation is now entirely
Rust-owned: no-follow directory descriptors remove hostile prior state and the
legacy Kotlin workspace, fixed-buffer copies verify exact lengths and digests,
and finish rehashes every staged file before publication. Host tests cover
symlink substitution and post-stage tampering; the Samsung upgrade gate removes
the old 211 MiB tree and republishes the exact inputs. Verified package output
returns through a manager-opened descriptor.

Before that Rust session opens reusable storage on Android, it scans a bounded
view of `/proc` for the Builder's unique UID. Every other candidate is paired
with its kernel start time, rechecked immediately before `SIGKILL`, and the
transaction fails closed until no process remains runnable. This avoids
signaling a reused PID and does not rely on a recipe-writable marker. A physical
gate starts an orphaned same-UID process before the Builder service, then proves
the service terminates it while the different-UID manager continues. The
eventual build supervisor must additionally retain, cancel, and reap its live
child process group on every normal and exceptional exit.

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
paths/types, and provisions a disposable private Arch root through Rust. Scan
state advances through at-most-eight-package Binder transactions and returns
cumulative package, entry, and expanded-byte evidence. Root reset is a separate
transition, so cancellation between scan batches aborts the unpublished
session before mutation. Root reuse is reset with no-follow directory-FD
traversal; Android-forbidden hard links become bounded regular copies. A
filesystem sync precedes publication of a closure-bound root manifest. The
current physical Samsung gate scans and provisions 250 packages, 66,877
verified archive entries, and 1,744,486,351 expanded bytes, including
executable `bash`, `makepkg`, and `fakeroot`; a separate run cancels at
200/250 with a visible full-device **Cancel AUR** action.

The Builder's small packaged execution runtime is independently
content-addressed: Rust validates its manifest, digest-bearing filenames,
complete SHA-256 values, sizes, modes, and native-library path containment
before publishing fresh root-local aliases. The physical gate executes the
exact reviewed Code recipe with the provisioned root's unmodified
`makepkg (pacman) 7.1.0` as the separate Builder UID with no Android network
permission. Rust first kills stale same-UID processes, resets the recipe
through no-follow descriptors, then retains a bounded process group with
timeout, capped logs, cancellation, and deterministic reap.

Builder Rust now enumerates the hostile output through no-follow directory
descriptors, rejects substitutions, links, unexpected archives, unsafe tar
entries, and mismatched `.PKGINFO`/`.BUILDINFO`, and copies only the accepted
archive through a manager-owned descriptor while hashing it. The manager
independently applies the same Rust verifier against the retained 250-package
closure, then installs signed official runtime dependencies and the reviewed
local package through one recoverable pacman transaction. Official repository
signatures remain required; only the already verified local AUR archive uses
the generated pacman configuration's optional local-file signature policy.
The exact transaction plan is checked, install reasons are recoverable, and
installed versions are verified afterward. For each required split output,
Rust derives the effective `.install` file from the reviewed `.SRCINFO`,
retains its exact snapshot bytes, and requires the independently copied
Builder archive to contain exactly the same `.INSTALL` bytes—or no `.INSTALL`
when none was declared. Review wire v5 presents all such scripts to the user.
Before mutation, Rust atomically publishes package/version/archive/script
digests in a mode-0600 manager capability file outside the shared Arch root.
Only these reviewed AUR lifecycle scripts may run on install or upgrade.
Removal rehashes the installed pacman-local script and requires the exact
manager capability; missing authorization or changed bytes fail closed.
Successful mutation reconciles stale entries, and removal deletes the final
entry. Official-package scriptlets remain disabled pending their separate
failure-recovery policy; normal pacman hooks still require a generic policy.
The physical Samsung gate completed current `visual-studio-code-bin` install,
manager death, authorized removal, and capability pruning with exact installed
script SHA-256
`c910a24270895767939b23194673d76641432aa107e6e81ca8ad6f7a8fc6e9b7`.
The installed package is not isolated: it joins the shared Archphene Linux
trust domain.

The manager does not treat a process-surviving filename as authority to install
an AUR package. Rust persists one bounded capability manifest atomically beside
mode-0600 content-addressed outputs after checking the exact reviewed identity,
source evidence, signed build closure, and independently verified Builder
metadata. Restore repeats exact review/closure matching, enforces derived
cache-path containment and metadata, rehashes every output, and runs the full
Builder-output verifier against the retained closure. Missing, mismatched,
replaced, truncated, or modified state fails closed. A successful install
revokes the manifest; explicit AUR-data cleanup removes the manifest and
retained outputs.

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
  renumbering persisted v1 states. Real launcher installation results,
  official/AUR package mutations, and exact verified AUR build capabilities
  reconcile across manager death. Active builds themselves are not resumed;
  only fully published, independently reverified outputs can reattach.
- The shared Rust Wayland compositor enforces the currently implemented object, role, configure, buffer, popup, subsurface, input, and teardown contracts on x86_64 and AArch64. It still needs broader protocol coverage, independent security review, and sustained parser fuzzing before it should be treated as a hardened general compositor boundary.
- GrapheneOS-specific hardening has not been validated on a supported Pixel.
- Running on stock Android does not provide GrapheneOS firmware, verified boot policy, exploit mitigations, or security updates.
- No generic Android or laptop target can honestly claim GrapheneOS-equivalent security without device-specific platform work.

Report vulnerabilities through the private process in [SECURITY.md](../SECURITY.md). Detailed historical analysis is preserved in the [implementation gap audit](../research/audits/implementation-gap-audit.md).
