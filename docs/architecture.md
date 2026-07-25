# Architecture

Updated: 2026-07-25

Archphene is one user-owned Arch Linux environment inside one ordinary Android
application. Pacman and AUR packages intentionally share one filesystem, home,
package database, toolchain, and Linux trust domain. Android remains
responsible for installation confirmation, application identity, lifecycle,
system UI, permissions, and access to files outside Archphene.

## Production model

```text
thin launcher Activity (separate APK/UID)
          │ authenticated, versioned Binder session
          │ Surface + batched input + Android capability results
          ▼
Archphene manager runtime Service (one Android UID)
          │ owns Linux processes and all mutable Linux state
          ▼
one private Arch root
  /usr  /etc  /var  /opt  /home/archphene  /tmp
```

The launcher APK is an Android entry point, not a Linux container. The manager
starts and supervises the Linux process under its own UID, inside the same
private Arch root used by Terminal and every other Linux application. Removing
a launcher must not remove a pacman package or Linux user data. Removing a
package must reconcile every launcher whose desktop entry or executable it
owned.

This deliberately does not provide package isolation inside Arch. Installing
an Arch or AUR package grants it the same Linux trust level it has in a normal
single-user Arch installation, subject to Android's outer application sandbox.

## Components

### Archphene manager

Rust owns:

- the shared Arch root, pacman database, package cache, and AUR build area;
- bounded package resolution, verification, mutation, and durable jobs;
- desktop-entry discovery and launcher descriptors;
- Linux process groups, Wayland sessions, terminal state, storage
  synchronization, and teardown;
- compositor, input, rendering, and capability wire state.

Kotlin owns:

- Activities, Services, notifications, and lifecycle;
- PackageInstaller and Android installation confirmation;
- Storage Access Framework, DocumentsProvider, permissions, IME, accessibility,
  and Android system UI;
- the authenticated cross-APK launcher session and Android `Surface` handoff.

Blocking work stays off Android's main thread. JNI remains coarse-grained and
passes descriptors, direct buffers, native windows, and bounded snapshots
instead of object graphs or rendered bitmaps.

### Thin launcher application

Each graphical desktop entry can receive one deterministic Android package
identity and launcher Activity. A wrapper contains only:

- the generic Archphene launcher client;
- a stable descriptor identifier and generation;
- label, icon, MIME intents, and declared Android capabilities;
- no Arch package closure, Linux home, package database, or executable.

The manager assembles wrappers from a reproducible precompiled template, signs
them with one persistent non-exportable Android Keystore identity, verifies the
generated APK, and hands it to PackageInstaller. Android confirmation remains
mandatory. Updates retain package name and signer; a desktop-entry identity
change is explicit rather than silently retargeting an installed launcher.

The wrapper signing certificate is intentionally different from the manager's
release certificate. The exported launcher Service therefore cannot rely on a
static signature permission. During every Binder transaction it resolves the
calling UID, requires exactly the registered generated package, verifies the
installed signer against the manager's wrapper-signing certificate, and checks
the bounded launcher descriptor. Package-name strings supplied by callers are
never authentication.

Discovery snapshots are reconciled only when complete. The Rust-owned launcher
registry derives desktop ownership from pacman's local file database and stores
the complete structured launch request, stable full descriptor identity,
deterministic Android package name, and desired/published/pending generations.
It is checksum-protected, mode 0600, atomically replaced, and fails closed on
symlinks, corruption, duplicate desktop IDs, or truncated discovery. Build,
PackageInstaller, and removal transitions are persisted before handoff; after
process death, Kotlin verifies the installed package signer and embedded
generation through PackageManager before Rust adopts, retries, or removes it.

### Cross-process launcher session

The internal manager Activity continues to use a non-exported local Binder.
Generated wrappers use a separate exported, versioned Binder protocol with a
small fixed transaction surface:

1. authenticate caller UID, package signer, descriptor, and protocol version;
2. acquire a generation-checked session with a Binder death token;
3. attach or replace an Android `Surface` and exact display metrics;
4. submit bounded input batches and receive coarse state/damage revisions;
5. broker explicit Android capability requests and results;
6. stop or detach the session.

The manager converts the supplied `Surface` to an `ANativeWindow`; the Linux
process never crosses into the wrapper UID. Binder death, wrapper force-stop,
or an explicit close detaches the surface and applies the documented
background/termination policy to the manager-owned process group.

The first implementation gate must prove caller rejection, real cross-process
Surface presentation, touch/key coordinates, rotation/rebind, wrapper death,
and descendant cleanup before the protocol is used for generated launchers.

### Wayland and graphics bridge

Each launched Linux application receives a private Wayland socket and a
manager-owned compositor session. The bridge maps Wayland surfaces, popups,
dialogs, input, clipboard, IME, output changes, and Android window geometry to
the attached launcher Surface.

OpenGL ES acceleration uses a manager-owned virglrenderer helper and Android
EGL/GLES, with bounded software fallback. The current final Wayland
presentation path remains shared-memory based. Android HardwareBuffer/dmabuf
and Vulkan presentation remain separate gates.

### Android capabilities

Linux clients connect only to a random manager-owned session endpoint. The
descriptor declares allowed capabilities, and the manager associates every
request with the authenticated launcher session.

Capabilities that must be attributed to the visible launcher—such as a picker,
permission prompt, notification, share sheet, or camera surface—are requested
through the wrapper Activity and returned over Binder. Capabilities owned by
Archphene as a whole—such as shared package storage and synchronized project
state—remain in the manager. The design must not pretend Android can enforce
per-Linux-package network or filesystem isolation when every Linux process
intentionally shares the manager UID and Arch root.

### Storage

Linux-owned state lives only in the manager's private shared root. Visible
files under `/home/archphene` are exposed through the manager DocumentsProvider.
Selected Android documents and folders cross the boundary through SAF and
bounded synchronization. Thin launchers do not own a second Linux home.

See [Linux home and Android storage](storage.md).

## Trust boundaries

1. Repository metadata and official packages require pinned endpoints and
   package-signature verification before mutation.
2. AUR builds require explicit source/build review and run unprivileged, but
   their installed results join the shared Linux trust domain.
3. Generated APK contents are deterministic and bounded; package, signer,
   capabilities, and descriptor identity are verified before PackageInstaller.
4. Every launcher Binder call is authenticated from the kernel-supplied UID,
   installed signer, and manager registry.
5. Linux paths and file descriptors never become caller-selected Android host
   paths.
6. Process groups, Binder death, and Service lifecycle provide deterministic
   cleanup without claiming that packages are isolated from one another.

See [Security model](security.md).

## Historical prototype

The legacy Java prototype proved on-device wrapper assembly, Android Keystore
signing, PackageInstaller handoff, caller-authenticated runtime-pack delivery,
and broad Qt/GTK/Wayland capability bridges. It ran Linux payloads under each
wrapper UID using immutable manager-owned runtime packs.

That evidence remains valuable, but the per-wrapper runtime-pack execution
model is not the approved production architecture because it creates separate
Linux homes and prevents packages from behaving like one normal Arch
installation. Prototype source and historical results remain under
`prototypes/` and `research/`; they are reference material, not greenfield
feature-completion claims.
