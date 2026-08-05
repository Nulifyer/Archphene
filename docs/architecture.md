# Architecture

Updated: 2026-08-05

Archphene is one user-owned Arch Linux environment inside one ordinary Android
application. Pacman and AUR packages intentionally share one filesystem, home,
package database, toolchain, and Linux trust domain. Android remains
responsible for installation confirmation, application identity, lifecycle,
system UI, permissions, and access to files outside Archphene.

## Production model

```text
thin app-shell Activity (separate APK/UID)
          │ authenticated, versioned Binder session
          │ Surface + batched input + Android capability results
          ▼
Archphene manager runtime Service (one Android UID)
          │ owns Linux processes and all mutable Linux state
          ▼
one private Arch root
  /usr  /etc  /var  /opt  /home/archphene  /tmp

Archphene manager ── reviewed read-only inputs / output FD ──▶
hidden Archphene Builder companion (separate Android UID)
          │ no Android network permission; disposable private build root
          └── returned package is re-verified before shared-root installation
```

The app-shell APK is an Android entry point, not a Linux container. The manager
starts and supervises the Linux process under its own UID, inside the same
private Arch root used by Terminal and every other Linux application. Removing
a shell must not remove a pacman package or Linux user data. Removing a
package must reconcile every shell whose desktop entry or executable it
owned.

Every generated app shell also carries a versioned capability declaration.
Rust supplies the exact bounded contract to deterministic APK assembly; Kotlin
embeds and independently verifies it, and both the shell and manager require
the same value before opening an authenticated Binder session. The V4
production contract contains the implemented Wayland, input, IME, plain-text
clipboard, document, bounded HTTP(S) OpenURI, and shell-owned notification
paths. Android 13+ notification consent remains a first-use shell decision.
A separate signed metadata field carries only registry-derived desktop MIME
types whose `Exec` accepts files or URLs. Binder protocol v10 reauthorizes that
exact set before one matching Android `content://` descriptor can be copied
into the shared Arch home and supplied to the stock desktop process.
A correctly signed
shell with an older contract is stale and can be updated through Android
confirmation; a shell with untrusted identity or signer remains quarantined.
Optional or dangerous capabilities must not be advertised until their complete
broker, evidence derivation, and user-review path exist.

This deliberately does not provide package isolation inside Arch. Installing
an Arch or AUR package grants it the same Linux trust level it has in a normal
single-user Arch installation, subject to Android's outer application sandbox.

## Components

### Archphene manager

Rust owns:

- the shared Arch root, pacman database, official package cache, and retained
  AUR review/source cache;
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

Linux process ownership, foreground retention, daemon scope, fixed resource
bounds, close ordering, and crash recovery follow the
[Linux process and lifecycle policy](runtime-lifecycle.md).

### Archphene Builder companion

AUR recipe execution belongs to one hidden companion APK, not to the manager
UID and not to each desktop application's launcher APK. The companion has a
separate ordinary Android UID and private storage, requests no `INTERNET`
permission, publishes no launcher Activity, and accepts only an explicit
signature-permission Binder call from the matching Archphene manager signer.
Reviewed snapshots and source files cross as bounded read-only descriptors.
The current staging path rehashes them on both sides. Builder Rust resets the
reviewed-input directory through no-follow directory descriptors, removes the
legacy Kotlin-owned workspace on upgrade without following substitutions,
streams and hashes each regular descriptor through a fixed buffer, reverifies
all staged bytes, and atomically publishes a canonical Builder-private input
manifest without executing recipe code. Builder output is later treated as
hostile, verified in Rust, and copied through a manager-opened output
descriptor.

Before opening that reusable state on Android, Builder Rust scans the bounded
process table for its unique UID, pairs each other process with its kernel start
time, rechecks that identity before signaling, and fails closed until no prior
same-UID process remains runnable. This handles a recipe that outlives a prior
service without trusting a Builder-writable PID file. The active build path
additionally owns one process group so normal completion, cancellation,
timeout, and service teardown kill and reap its direct child.

The manager resolves the official build environment as one bounded transaction
containing `base-devel` plus the reviewed recipe's runtime `depends`,
`makedepends`, and `checkdepends`. It uses current repository catalogs with an
empty ephemeral local database rather than the shared root's installed-package
state. The current Code AArch64 plan is 250 packages and 321,419,288 archive
bytes. The
manager downloads every exact archive and detached signature, Rust verifies
the pinned signer and package identity, retains the exact resolution, and
reverifies the full closure before reporting it. The resulting canonical
manifest binds every archive and detached-signature digest to its exact
resolution entry and supplies one overall SHA-256 for approval and Builder
provenance. The manager reopens descriptors only while that exact closure
remains retained, sends at most eight archive/signature pairs per Binder call,
and the Builder rehashes every pair through fixed buffers before atomically
publishing the same manifest under no-follow directory-FD-owned storage.
Builder Rust then scans every XZ/Zstandard package before mutation, provisions
a fresh private Arch root, rehashes each archive immediately before extraction,
rejects path escapes and unsupported entry types, and copies package hard links
because Android app storage denies `link(2)`. After a filesystem sync it
atomically publishes a closure-bound root manifest. It also derives a
Builder-private pacman local index from each already signed package's exact
`.PKGINFO`, including the versioned ALPM database marker; this lets unmodified
makepkg record the real build closure without borrowing shared-root state. The
current Samsung Code gate provisions all 250 packages, 66,878 verified archive
entries, and 1,744,478,772 expanded bytes before the separately authorized
shared-root install.

The Builder APK also carries only the content-addressed patched loader,
loader dependencies, and path bridge needed to enter that root; it does not
duplicate the manager's full package runtime. A generated manifest binds each
native filename to its complete SHA-256 and size. Builder Rust checks the
manifest, filename digest prefix, full digest, exact size, safe mode, and
native-library path containment before publishing a fresh alias directory
inside the root. The physical Samsung gate uses that runtime to execute the
exact reviewed Code recipe with the root's unmodified
`makepkg (pacman) 7.1.0` as the Builder UID. The generic bridge supplies the
Linux filesystem, identity, fakeroot, SysV IPC, optional Landlock, and
fortified canonical-path behavior required by the stock Arch tools; no Code
source patch is carried.

This boundary is required because the tested stock Samsung kernel denies user,
mount, and network namespace creation to the manager app. Android's
`isolatedProcess` UID removes network and direct manager-data access, but also
cannot create a conventional build workspace through a granted directory
descriptor. The companion's private directory supplies that workspace while
retaining a distinct UID and SELinux `untrusted_app` domain.

The live Samsung boundary probe verifies signer continuity, distinct UIDs, no
network permission or launcher entry, private workspace writes, denial of
manager-private paths, descriptor output, and denial of direct manager access
to builder-private state. It also proves failed partial-root recovery, a
published root containing executable `bash`, `makepkg`, and `fakeroot`, stale
same-UID cleanup, explicit Build approval, bounded process-group execution,
live logs, cancellation plumbing, and normal reap. The current Code output has
exact name/version/AArch64 metadata, a 1,079,048,720-byte installed size, and a
`.BUILDINFO` set equal to the 250-package signed closure. Builder Rust enumerates
the output with no-follow directory descriptors, rejects substitutions, links,
unexpected archives, unsafe tar entries, and mismatched `.PKGINFO` or
`.BUILDINFO`, then copies only the selected archive through a manager-owned
descriptor while hashing it. The manager independently applies the same
verifier to the copied archive and retained closure. It installs signed
official runtime dependencies, atomically retains the verified AUR archive by
SHA-256, and commits that local archive through pacman with exact plan/version
postconditions. Every required split output's effective `.install` file is
retained from the reviewed snapshot and must match the independently verified
archive's `.INSTALL` bytes exactly. Rust authorizes those AUR lifecycle scripts
with a mode-0600 package/version/archive/script capability outside the
recipe-accessible shared root, rechecks the installed script before removal,
and prunes authorization with package state. Verified official-package
scriptlets run in pacman's native lifecycle order, arbitrary libalpm hooks are
disabled through a bounded private `HookDir`, and only fixed root-contained
maintenance adapters run afterward. The physical Samsung
gate completed current `visual-studio-code-bin` install, process-death
reattachment, authorized removal, and capability pruning.

### Thin app-shell application

Each graphical desktop entry can receive one deterministic Android package
identity and app-shell Activity. An app shell contains only:

- the generic Archphene launch client;
- a stable descriptor identifier and generation;
- label, icon, MIME intents, and declared Android capabilities;
- no Arch package closure, Linux home, package database, or executable.

This APK is the primary Android representation of a graphical Linux
application. A normal `MAIN`/`LAUNCHER` Activity gives the application a real
app-drawer and launcher-search entry, stable icon and package identity, its own
task/window, Android lifecycle, intent routing, and compatibility with desktop
launcher surfaces such as DeX. An app widget cannot replace that contract:
widgets are optional, user-placed home-screen content and do not become
applications in the app drawer. Archphene may later offer shortcuts or widgets
that route to an installed wrapper, but they are convenience surfaces for the
same descriptor and never own a Linux process or package state.

The app-shell cost is deliberately bounded. Current production Mousepad and btop
shells are approximately 91 KiB and 87 KiB respectively on both maintained
ABIs. They contain Android metadata and the generic client only; every shell
still binds to the one manager-owned Arch root and manager-owned process
registry.

The manager assembles app shells from a reproducible precompiled template, signs
them with one persistent non-exportable Android Keystore identity, verifies the
generated APK, and hands it to PackageInstaller. Android confirmation remains
mandatory. Updates retain package name and signer; a desktop-entry identity
change is explicit rather than silently retargeting an installed shell.
The current implementation uses a minimized release template and patches only
bounded binary-manifest placeholders. It strips template signatures, preserves
the reviewed entry set and content digests, signs with one RSA-3072 APK v3
identity, and verifies the signer, package, version, label, manager,
descriptor, generation, and archive contents. PackageInstaller receives the
APK only while its streamed SHA-256 still matches the verified output.

Because generated package names are dynamic, they cannot be declared through a
finite Android `<queries>` list. The manager therefore declares
`QUERY_ALL_PACKAGES` for its app-store role and uses the visibility only to
reconcile its registry identities. It asks the user to allow app-shell
installation before claiming publication work, then retains Android's
per-shell install and uninstall confirmations. Interrupted installer
sessions are abandoned and requeued on manager restart; installed packages are
adopted only after signer and embedded metadata verification. A conflicting or
untrusted package is quarantined rather than replaced or silently removed.
The manager exposes that failure for explicit review. Retry first persists a
removal state, asks Android for uninstall confirmation, and publishes the
trusted replacement only after Android confirms that the deterministic package
identity is vacant. If registry state is lost but the installed wrapper still
has the trusted signer and a higher Android version, reconciliation advances
the desired generation above it rather than attempting a downgrade.

The app-shell signing certificate is intentionally different from the manager's
release certificate. The exported launcher Service therefore cannot rely on a
static signature permission. During every Binder transaction it resolves the
calling UID, requires exactly the registered generated package, verifies the
installed signer against the manager's app-shell signing certificate, and checks
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

### Cross-process app-shell session

The internal manager Activity continues to use a non-exported local Binder.
Generated app shells use a separate exported, versioned Binder protocol with a
small fixed transaction surface:

1. authenticate caller UID, package signer, descriptor, and protocol version;
2. acquire a generation-checked session with a Binder death token;
3. attach or replace an Android `Surface` and exact display metrics;
4. submit bounded input batches and receive coarse state/damage revisions;
5. broker explicit Android capability requests and results;
6. stop or detach the session.

Protocol v20 separates the manager-owned Linux application session from its
Android task attachments. One root session owns the private Wayland socket,
Linux process group, brokers, and compositor. Up to eight authenticated Android
window sessions each own one bounded token and `Surface`; the compositor caps
the retained aggregate at 33,554,432 pixels. The primary task uses the compact
composited output. On an adaptive window, an external display, or a sufficiently
large pointer-driven window, independent unparented toplevels receive internal
document Activities. Parent-owned popups and transient surfaces remain composed
with their toplevel. Recents are bounded to eight window tasks, and stable
component-plus-data document intents reuse an existing task after Activity
process death.

The identities and lifetimes are:

| Identity | Stable key | Lifetime |
|---|---|---|
| Linux application | Signed descriptor ID, generated Android package, signer, and generation | Survives Activity recreation, Home, and display movement. Package removal or a changed descriptor identity ends the shell identity. |
| Linux session | Manager root-session ID, private Wayland socket, and process handle | Survives Surface replacement, Home, and a 15-second app-shell process reconnect grace. Manager death, Linux process exit, or final explicit close ends it. |
| Android task | Main app-shell intent, or `archphene-window://<package>/<toplevel>` for an independent window | Survives Activity process death and remains in bounded Recents. Force-stop prevents reconnect until the user launches the shell again; shell removal removes its tasks. |
| Linux toplevel | Wayland protocol object ID within one Linux session | Survives Activity and Surface replacement while the Linux session remains alive. It is never reused as authority outside that session. |
| Imported document | Validated `content://` request plus the manager-owned imported path | The imported file remains in the shared Arch home. Its launch association belongs to the Linux session and does not authorize another task or package. |
| Surface attachment | Authenticated Binder session ID used as the native window token | Belongs to one Activity instance. Configuration replacement, Home, Activity death, or task close detaches it without transferring Linux process ownership. |

The manager converts the supplied `Surface` to an `ANativeWindow`; the Linux
process never crosses into the app-shell UID. Binder death starts the bounded
reconnect grace; an explicit close or an expired grace applies the documented
termination policy. App-shell force-stop prevents automatic reconnection; when
the grace expires, the manager closes the Linux process group.

The current implementation proves caller rejection, real cross-process
Surface ownership, cold-runtime retry, app-shell-template invalidation,
full-device light/dark presentation, and app-shell process reconnection on the physical
AArch64 target. Behind that Surface, the manager now owns a private Rust
Wayland compositor, direct `ANativeWindow` SHM presentation, a bounded
generation-checked GUI process registry, process groups, and fixed touch,
five-button pointer, bounded horizontal/vertical axis, and hardware-key
batches. Key records carry press, release, repeat, and Android modifier state.
The fixed six-integer records are checked independently at the Binder and
native boundaries; touch identifiers and coordinates, buttons, axis distance,
key codes, and active touches all have explicit limits. Window focus loss,
Home, detach, and close cancel touches, release held pointer buttons and keys,
clear modifiers, and remove keyboard focus. Home detaches presentation while
retaining the session; resume reattaches it. Back closes its authenticated
window session, while Binder death detaches presentation and starts the bounded
reconnect grace. Process exit polling is kept outside the frame hot
path, drains stdout/stderr into a fixed 16 KiB tail ring, and addresses
remaining descendants before showing a bounded status.

Android text clipboard access stays in the visible app shell rather than the
background manager. The focused shell reads or writes Android
`ClipboardManager` state and sends only a generation-checked, authenticated
text message. The manager accepts at most 16,384 UTF-16 units and 64 KiB of
UTF-8, while Rust exposes standard `wl_data_device` selections and caps pending
pipe descriptors at four per direction. Descriptor reads and writes run on one
dedicated manager worker, are switched to nonblocking mode, and have a
two-second poll deadline. Invalid UTF-8, overflow, stalled peers, stale
revisions, and echoed Android notifications fail without blocking Binder or
the compositor. Linux clipboard changes received during focus loss are held
until the shell is visible again; detach disables the bridge and closes
pending descriptors.
Status does not compete with native rendering for the same `Surface`: the
manager sends authenticated one-way state callbacks and the shell renders an
opaque Android overlay until the first Linux frame, or again after a stop.
Portrait/landscape replacement and Home/resume reattachment have been proven
on the physical Samsung without a retained stretched buffer.

The physical Samsung gate uses current generated app shells and unmodified
package-installed clients. Mousepad proves compact and adaptive multi-task
ownership, process-death restoration, input, IME, accessibility, and lifecycle;
the maintained package matrix covers the other broker and rendering contracts.
Physical external-display and repeated x86_64 multi-task coverage remain open.

### Wayland and graphics bridge

Each launched Linux application receives a private Wayland socket and a
manager-owned compositor session. The bridge maps Wayland surfaces, popups,
dialogs, input, clipboard, IME, output changes, and Android window geometry to
the attached app-shell Surface.

OpenGL ES acceleration uses a manager-owned virglrenderer helper and Android
EGL/GLES, with bounded software fallback. The current final Wayland
presentation path remains shared-memory based. Android `AHardwareBuffer`
composition remains a separate gate. Direct client-dmabuf import is also a
separate, capability-negotiated gate: the current Samsung exposes
`EGL_ANDROID_image_native_buffer` but not `EGL_EXT_image_dma_buf_import`.
Vulkan presentation remains incomplete.

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

OpenURI is deliberately narrower than Android's general intent system. The
private XDG portal accepts bounded `http` and `https` URIs only, rejects
userinfo, invalid hosts and ports, controls, and non-web schemes, then asks the
authenticated visible wrapper to start a browsable Android intent. File and
content exchange remain on the document broker, so OpenURI grants no Android
URI permission.

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

See the [package and launcher trust policy](trust-policy.md) and
[security model](security.md).

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
