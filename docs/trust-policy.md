# Package and launcher trust policy

Updated: 2026-07-29

This document is the normative trust policy for content that Archphene
downloads, builds, installs, executes, or publishes as an Android launcher.
The detailed mechanisms and current validation evidence remain in the
[security model](security.md) and [project status](project-status.md).

## Scope and shared trust domain

Archphene owns one private Arch root, package database, Linux home, and
toolchain. Installed official and AUR packages intentionally share that Linux
trust domain, as they do in a normal single-user Arch installation. They are
not isolated from one another. Android's application UID, SELinux domain,
permissions, and explicit capability brokers remain the outer boundary.

## Official repository packages

An official package may mutate the shared root only after Archphene has:

- resolved it from a pinned HTTPS repository endpoint with bounded metadata;
- retained the exact repository, name, version, architecture, URL, and size;
- verified its detached signature against the sealed Arch keyring and expected
  architecture signer;
- reverified the archive immediately before the recoverable pacman transaction;
- journaled the intended mutation and checked its package/version result.

Official per-package scriptlets run in pacman's native dependency order from
the verified archives. Only pacman receives the conventional virtual-root
`PATH=/usr/bin:/bin`; commands still resolve inside the Arch root. A scriptlet
failure leaves the durable mutation repairable rather than being reported as a
successful install.

## AUR packages

AUR content is community code, not an official Arch-signed package. Every
package base must be reviewed before its recipe executes. The retained review
binds the RPC result, package base and outputs, selected architecture, cgit
snapshot and commit evidence, PKGBUILD, `.SRCINFO`, local files, sources,
checksums, dependencies, functions, and required install scripts.

Recursive AUR dependencies are represented by one bounded graph of at most 32
package bases and 256 dependency edges. Ambiguous providers require an
explicit selection. Each separately untrusted base builds in a freshly
provisioned private root under the hidden Archphene Builder's distinct Android
UID. The Builder has no Android network permission, receives reviewed inputs
and signed official dependencies only through bounded descriptors, supervises
and reaps the process group, and returns output through a manager-owned
descriptor.

The manager independently reverifies every returned archive, its package
metadata, build information, dependency provenance, and required `.INSTALL`
bytes. All graph outputs join the shared root in one recoverable final
transaction. Completed graph outputs may be restored only through the
content-addressed graph capability and full revalidation; an active recipe
process itself is never resumed after process death.

## Lifecycle scripts, hooks, and maintenance

AUR lifecycle scripts may run only when their exact reviewed script bytes,
package/version, and archive digest match the manager-owned mode-0600
capability outside the recipe-accessible root. Removal rehashes the installed
script and requires the same authorization. Missing, changed, or stale
authorization fails closed.

Arbitrary libalpm hooks do not run. Before every mutation, Archphene builds a
bounded private `HookDir` that disables the root's current hook entries and
rejects unsafe or substituted entries. After a successful official-package
mutation, Archphene runs only fixed, root-contained maintenance adapters for
the supported trust, fontconfig, GDK-Pixbuf, GTK input-module, GIO, GLib
schema, dconf, desktop MIME, and shared MIME subsystems when their installed
commands and data directories exist. Adapter failure retains the mutation
journal for Repair.

## Executables and runtime content

Terminal command requests are bounded, shell-free requests for executable
files resolved under the Arch root's `/usr/bin`. Resolution rejects escapes,
excessive links, missing execute permission, and world-writable files.

Graphical launch requests come from bounded desktop entries. Their structured
`Exec` fields are parsed without a shell, their executable resolves to a
contained non-world-writable file, and discovery attaches unambiguous pacman
ownership. Launcher retention follows explicitly installed source packages;
a surviving filename alone is not installation authority.

Native runtime content shipped by Archphene is bound to the signed manager or
Builder APK through content-addressed manifests, exact ABI selection, sizes,
modes, and SHA-256 digests. Installed Linux package ELF is executed through the
Linux runtime boundary and is never loaded as manager JNI code.

## Android launcher and Builder identity

Generated launchers are deterministic thin APKs containing a bounded
descriptor, generation, capability declaration, label, and icon—not Linux
package content. The manager signs them with its AndroidKeyStore alias
`archphene-shared-launcher-signing-v1`, using RSA-3072 and APK Signature Scheme
v2/v3, then reverifies the single signer and rejects debuggable output.

Every launcher Binder call is authenticated from the kernel-supplied UID. The
manager requires exactly one package for that UID, the deterministic package
shape, the currently installed package metadata, signer equality, descriptor,
generation, template digest, and capability contract. The hidden Builder
likewise requires the manager's matching installed signer and
signature-protected explicit Binder interface.

AndroidKeyStore loss is not silently trusted. A still-installed wrapper with
the old signer remains quarantined until the user confirms its Android
uninstall; Archphene then publishes a newly signed wrapper. Trusted existing
wrappers advance through a higher generation rather than an Android-forbidden
downgrade.

## Fail-closed rule and remaining evidence

Missing or ambiguous trust evidence, unsafe paths or modes, changed bytes,
unavailable signers, unsupported lifecycle semantics, or incomplete recovery
must stop before a new mutation or remain visibly repairable after a journaled
mutation.

This policy describes the implemented boundary. Two narrower validation items
remain open: an exact signed reverse-rollback fixture whose official package
has lifecycle scripts, and a repeatable device proof of interruption between
multiple real AUR graph bases. Their absence is a coverage gap, not permission
to weaken the policy.
