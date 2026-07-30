# Android capability broker

Archphene Linux processes run under the manager's Android UID and lifecycle;
each generated wrapper retains its own Android app identity, UI, and permission
state. A Linux syscall cannot grant an Android permission. Operations that need
wrapper-owned Android services therefore cross an explicit, capability-gated
manager broker and an authenticated Binder callback to the wrapper Activity.

## Current production launcher contract

Generated launchers publish the exact V4 capability set
`wayland,input,ime,clipboard,documents,open-uri,notifications`. The manager owns
the shared Linux process and one private D-Bus/broker pair per authenticated
launcher session. The thin wrapper owns Android UI, permission prompts, and
the resulting Android identity; it does not contain Linux packages or user
files.

The current notification path accepts the standard XDG Notification and
freedesktop.org Notifications calls from unmodified Linux applications. The
manager validates bounded IDs, titles, and bodies, then sends a versioned
same-session Binder callback to the authenticated wrapper. The wrapper keeps
at most 32 pending notifications in a fixed array. On Android 13 and newer,
the first notification requests `POST_NOTIFICATIONS` only while that wrapper
Activity is resumed. Consent posts the queued notification without requiring
the Linux application to retry; denial discards the queue and is not
re-prompted. Granting permission later in Android settings takes effect on the
next notification.

The wrapper APK owns the resulting Android notification, channel, app label,
and content intent. Tapping it returns to that wrapper's existing task.
Portal and classic IDs are separate notification tags, replacement is
idempotent, and withdrawal removes both pending and already-posted entries.
Every generated graphical launcher declares this standard desktop capability;
Android's runtime dialog remains the explicit user review for the only
permission involved.

Exact AArch64 Samsung and x86_64 emulator gates cover first-use consent,
portal/classic post, wrapper package attribution, notification-shade visuals,
content-intent routing, withdrawal, and fatal logs. The reusable gate is
`scripts/test-launcher-notifications.sh`.

Inbound Android document/share intent filters are not part of V4 yet. They
remain pending because a generated manifest must declare only MIME types that
the verified desktop entry can actually open, and the received URI must cross
SAF without exposing Android grants or paths to Linux.

## Native client protocol surface

The shared bridge starts a randomized abstract Unix socket for each wrapper launch and exports its name as `ARCHPHENE_ANDROID_BROKER`. The glibc runtime pack contains ABI-matched `libarchphene_android.so` clients for x86_64 and AArch64.

The native client library contains protocol entry points for:

- `archphene_android_open_uri`: opens an ordinary host-bearing HTTP or HTTPS URI through Android `ACTION_VIEW`;
- `archphene_android_notify`: requests Android 13+ notification permission on first use and posts a bounded wrapper-owned notification;
- `archphene_android_withdraw_notification`: removes a notification created by that wrapper;
- `archphene_android_print_pdf`: transfers one rendered PDF file descriptor to Android's system print UI;
- `archphene_android_request_camera` and `archphene_android_check_camera`: request and report Android `CAMERA` permission without bypassing denial;
- `archphene_android_capture_camera_jpeg`: captures one bounded Camera2 JPEG to a caller-supplied regular file descriptor after consent;
- `archphene_android_publish_accessibility_tree`, `archphene_android_accessibility_event`, and `archphene_android_take_accessibility_action`: publish bounded app semantics and return Android click, focus, edit, and scroll actions to Linux;
- `archphene_android_store_secret`, `archphene_android_read_secret`, `archphene_android_delete_secret`, and `archphene_android_list_secrets`: manage a wrapper-private encrypted secret collection through regular file descriptors.

An entry point is production-available only when it is named in the current
launcher contract and has a complete manager-to-wrapper broker. Unsupported
requests fail closed. The Android intent behavior follows the platform's
[common intent guidance](https://developer.android.com/guide/components/intents-common).
Runtime notification permission follows the
[Android notification permission model](https://developer.android.com/develop/ui/views/notifications/notification-permission).

## Security properties

- Broker peer credentials must report the manager's exact UID. The separate
  Binder boundary authenticates the generated wrapper UID, signer, descriptor,
  and generation; cross-UID callers are rejected before dispatch.
- Wrapper metadata must match the exact current capability contract before it
  can bind. Optional future permissions remain unavailable until their
  production broker and review policy are complete.
- Requests and every field have fixed size limits and strict UTF-8 validation.
- URL opening rejects non-HTTP schemes, missing hosts, user information, and control characters.
- Android remains the permission authority. Notification delivery is
  fire-and-forget at the desktop protocol boundary; the wrapper queues through
  consent and drops on denial rather than bypassing or repeatedly prompting.
- Socket names are random per process and logged only by debuggable wrappers.
- Runtime-pack publication hashes the ABI-specific client as an immutable module.

The current production regressions cover authenticated Binder calls,
first-use notification permission UI, notification post and withdrawal,
HTTPS dispatch, unsafe-URI rejection, and cross-UID denial.

## Standard desktop adapters

Each generated wrapper starts an app-private D-Bus session daemon under its existing Android UID. The socket is stored in the wrapper cache, accepts EXTERNAL authentication, and is inaccessible outside the Android sandbox. It is not a system bus and does not connect different wrappers.

The wrapper also starts one frontend that owns `org.freedesktop.portal.Desktop` and `org.freedesktop.Notifications`. It currently implements:

- XDG OpenURI `OpenURI`, `SchemeSupported`, `version`, and request responses;
- XDG Notification `AddNotification`, `RemoveNotification`, `SupportedOptions`, and `version`;
- XDG Print `PreparePrint`, `Print`, `version`, and asynchronous request responses;
- freedesktop.org notification `GetCapabilities`, `GetServerInformation`, `Notify`, and `CloseNotification`;
- `xdg-open` as a fallback for applications that do not call the portal directly.

`DBUS_SESSION_BUS_ADDRESS`, `GIO_USE_PORTALS=1`, and `NOTIFY_FORCE_PORTAL=1` are exported to the unmodified Linux process. A manifest-verified Android-native `xdg-open` adapter is published through the shared runtime command directory and, unless the user already owns that name, the conventional `/usr/local/bin/xdg-open` boundary. The glibc path bridge recognizes only that verified adapter, removes incompatible preload variables, and executes it directly instead of passing a Bionic executable to the glibc loader. The adapter uses the private D-Bus OpenURI portal; the untrusted Linux process never receives the Android broker address. Android target-SDK executable restrictions are therefore preserved while ordinary shells and applications can invoke stock `xdg-open`.

The first standard notification remains queued while Android displays `POST_NOTIFICATIONS`; consent posts it without requiring the Linux application to retry. Denial discards the bounded queue and remains authoritative. The queue holds at most 32 notification IDs.

Dual-ABI builds pass ELF dependency checks. A manager-generated KCalc wrapper passes portal discovery, HTTP(S)-only scheme policy, portal and classic notification permission/post/withdraw, portal OpenURI, and `xdg-open` on the x86_64 emulator. The private-bus contract and first-use notification lifecycle also pass on a physical AArch64 Samsung device. Current Samsung VS Code additionally invokes unmodified `xdg-open` from its integrated Bash and visibly opens a live .NET MVC service in Brave.

## Printing

Wrappers whose verified ELF closure contains a CUPS client declare `printing`. The [XDG Print portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Print.html) accepts an already-rendered regular PDF descriptor, transfers it over the same-UID capability socket, validates the file type, `%PDF-` header, and a 256 MiB size limit, and stages it only in the wrapper's private cache. Android's `PrintManager` remains the policy and destination authority, including the system Save as PDF destination. Up to four print jobs may be active in one wrapper; completion or cancellation removes the private staging file, and stale files are removed when the bridge next starts.

Android cannot present `PrintManager` before it has a rendered document adapter. Archphene therefore returns bounded default settings and a token from `PreparePrint`, then presents the authoritative Android dialog during `Print`; token-bearing calls do not bypass that dialog. This preserves unmodified toolkit sequencing but differs from desktop portals that present their dialog during `PreparePrint`.

The x86_64 emulator and physical AArch64 Samsung regressions validate `PreparePrint`, descriptor transfer, a rendered one-page Android preview, Save as PDF discovery, cancellation cleanup, and rejection of non-PDF and non-regular descriptors without opening the print UI. They also validate wrapper upgrades with stale helper symlinks. Printing requires no Android runtime permission. Availability is still determined by the device's Android printing feature and installed print services.

## Audio input and output

Wrappers whose verified ELF closure contains a Pulse client declare `audio-output`. The shared bridge then starts a PulseAudio native-protocol server inside that wrapper's Android UID, exports its private Unix socket through `PULSE_SERVER`, and renders a stereo 48 kHz sink through Android AAudio. OpenSL ES is the fallback when AAudio cannot initialize. The socket is under app-private cache storage and is never shared across wrapper UIDs.

The manager embeds one checksum-verified Bionic server payload per supported ABI and copies it only into audio-enabled wrappers. The Linux application continues using its unmodified glibc Pulse client. On the x86_64 emulator, an on-device conversion of the official Arch `pavucontrol` package detects GTK4 and Pulse, generates an `audio-output` wrapper, launches the private AAudio sink, authenticates the Linux client, creates pavucontrol's monitor stream, and renders the live Volume Control GUI. Direct server playback also passes on the x86_64 emulator and physical AArch64 Samsung device.

Speaker playback needs no runtime permission. Microphone input is disabled by default and is enabled per wrapper in the manager. Rebuilding an eligible Pulse wrapper adds the separate `audio-input` capability. A Bionic helper then exposes a private mono 48 kHz PCM16 Pulse source and monitors only streams attached to that source. The first attached Linux recording stream sends `REQUEST_AUDIO_INPUT`; the Android Activity requests `RECORD_AUDIO`, and the helper starts AAudio capture only after Android reports a grant. Denial is not repeatedly prompted, and users can change the permission later in Android app settings. Disabling the manager setting removes the bridge capability on the next wrapper rebuild, although an Android permission already granted to that package remains granted until the user revokes it.

The permission dialog grant and denial paths pass on the x86_64 emulator. An unmodified Pulse `pacat` client on a physical AArch64 Samsung captured 480,000 bytes in five seconds, including 356,437 nonzero bytes, after consent. The same test produced Android-mandated silence while the device-wide microphone privacy switch was enabled. Wrapper force-stop removes the Pulse server, input helper, and Linux client process tree.

Capability-scoped drag-and-drop maps Android `DragEvent` motion/drop/cancel lifecycle to standard Wayland data devices in both directions. Plain text is bounded to 8 MiB. Android URI clips accept at most 32 files, retain the temporary drag grant for the document session, import through the existing conflict-safe broker, and expose only local `file://` paths under `Documents/Android` to Linux. Linux `text/uri-list` sources are bounded to 1 MiB of metadata and may export at most 32 canonical, non-dot files under the visible Linux home; Android receives wrapper-provider content URIs with exact temporary read grants. Actions remain copy-only. Protocol transfer, import/writeback, external denial without a grant, granted reads, cancellation, and cleanup pass on the x86_64 emulator and physical AArch64.

## Camera

A wrapper declaring `camera` can request and inspect Android `CAMERA` permission through the same broker. After consent, a glibc caller supplies one regular output descriptor, requested maximum dimensions up to 8192 pixels, and front/back preference. Camera2 selects the preferred lens when present, bounds JPEG payloads to 32 MiB, closes the camera/session/ImageReader after each request, and returns the actual dimensions and byte count. Android remains the permission authority; denial is persisted and is not repeatedly prompted.

The x86_64 emulator and physical AArch64 Samsung regressions validate the real permission dialog, capture before-consent rejection, 1280x720 JPEG capture and signature/byte checks, invalid-dimension rejection, denial, and no automatic reprompt. The private XDG Camera adapter also returns a bounded PipeWire remote backed by Camera2. An official unmodified Arch Snapshot package passes Android grant and denial paths, timestamped frame delivery, process cleanup, and runtime-pack lease cleanup on both architectures.

## Accessibility

A wrapper declaring `accessibility` can publish up to 1024 virtual nodes from a regular descriptor containing at most 1 MiB of validated JSON. The tree requires bounded IDs, acyclic parent links, a 16384-pixel maximum logical viewport, validated roles/text/states, and positive bounds. The compositor view exposes those nodes through `AccessibilityNodeProvider`, scales Linux logical bounds into the current Android viewport, preserves accessibility and input focus, and emits framework content, text, focus, selection, click, and window events.

Android actions are returned to Linux through a bounded 64-entry queue. Click, focus, set-text, and forward/backward scroll are supported; set-text payloads are UTF-8 bounded to 1024 characters, and Linux polling is capped at 250 milliseconds so it cannot monopolize the shared capability dispatcher. Publishing an invalid tree leaves the last valid model intact. Accessibility does not require or justify Android's powerful accessibility-service permission: production wrappers expose only their own UI semantics. The independently built test fixture uses a test-only service solely to verify what Android receives.

The private AT-SPI2 adapter translates standard toolkit D-Bus object trees and actions into this transport while retaining semantic ownership across secondary windows. Strict regressions validate normalized framework bounds, focus, edits, menus, dialogs, reverse actions, and parent restoration through unmodified KCalc/Qt and Mousepad/GTK on x86_64 and physical AArch64.

## Secrets and keyrings

A wrapper declaring `secrets` receives a private encrypted collection. Secret identifiers, labels, and string attributes are validated and bounded; secret bytes enter and leave only through regular file descriptors. Records are named by a SHA-256 digest of the identifier, encrypted in full with AES-256-GCM, authenticated against the record filename, and written atomically. The non-exportable key is generated in Android Keystore with randomized encryption and unlocked-device enforcement where supported. The store is limited to 256 records, 64 KiB per secret, 8 KiB of attributes, and a 1 MiB metadata index. No Android runtime permission or accessibility-service privilege is involved because the collection remains inside the wrapper UID.

The private session bus now owns `org.freedesktop.secrets` only for wrappers declaring `secrets`. Its [freedesktop.org Secret Service](https://specifications.freedesktop.org/secret-service/latest/) adapter exposes one always-unlocked login collection, sender-bound plain and `dh-ietf1024-sha256-aes128-cbc-pkcs7` sessions, default/login aliases, bounded search, create/replace, get/set, delete, bulk retrieval, properties, content types, zero-length values, and standard item-change signals. Secret bytes cross the Android boundary only through private regular descriptors; closed or disconnected D-Bus clients lose their session keys.

The 4 KB and 16 KB x86_64 emulators and physical AArch64 Samsung regressions validate the direct encrypted API and Secret Service wire contract, including session lifecycle, create, search, exact readback, overwrite, replacement, zero-length values, deletion, persistence across process death, malformed and oversized rejection, ciphertext plaintext absence, stale-broker rejection, and absence of secret values from Android logs. On the 4 KB x86_64 emulator, unmodified Arch `secret-tool`, the KWallet D-Bus API, and Arch `kwallet-query` pass encrypted store/read/update/clear, daemon-restart persistence, and cleanup. On physical AArch64, official Arch Linux ARM `secret-tool`, the patched compatibility `kwalletd6`, and official `kwallet-query` pass the same encrypted store/read/clear and restart-persistence flow. The upstream Arch x86_64 client closure is skipped on 16 KB Android because its ELF files are 4 KB-aligned.

## Remaining adapters

Richer notification actions, non-HTTP URI policies, and other desktop portals remain incomplete.

## Native client

The public header is `native/archphene-android-capability/archphene_android.h`. Callers link `libarchphene_android.so` and use the exported environment rather than discovering or hard-coding the socket. The wire protocol is private to Archphene and may only be used inside the wrapper UID.
