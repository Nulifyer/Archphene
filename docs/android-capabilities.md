# Android capability broker

Archphene Linux processes keep the Android UID, SELinux domain, lifecycle, and permission state of their wrapper APK. A Linux syscall cannot grant an Android permission. Operations that need Android services therefore cross an explicit, capability-gated broker owned by the wrapper Activity.

## Validated APIs

The shared bridge starts a randomized abstract Unix socket for each wrapper launch and exports its name as `ARCHPHENE_ANDROID_BROKER`. The glibc runtime pack contains ABI-matched `libarchphene_android.so` clients for x86_64 and AArch64.

Protocol version 1 currently supports:

- `archphene_android_open_uri`: opens an ordinary host-bearing HTTP or HTTPS URI through Android `ACTION_VIEW`;
- `archphene_android_notify`: requests Android 13+ notification permission on first use and posts a bounded wrapper-owned notification;
- `archphene_android_withdraw_notification`: removes a notification created by that wrapper;
- `archphene_android_print_pdf`: transfers one rendered PDF file descriptor to Android's system print UI;
- `archphene_android_request_camera` and `archphene_android_check_camera`: request and report Android `CAMERA` permission without bypassing denial;
- `archphene_android_capture_camera_jpeg`: captures one bounded Camera2 JPEG to a caller-supplied regular file descriptor after consent;
- `archphene_android_publish_accessibility_tree`, `archphene_android_accessibility_event`, and `archphene_android_take_accessibility_action`: publish bounded app semantics and return Android click, focus, edit, and scroll actions to Linux;
- `archphene_android_store_secret`, `archphene_android_read_secret`, `archphene_android_delete_secret`, and `archphene_android_list_secrets`: manage a wrapper-private encrypted secret collection through regular file descriptors.

The Android intent behavior follows the platform's [common intent guidance](https://developer.android.com/guide/components/intents-common). Runtime notification permission follows the [Android notification permission model](https://developer.android.com/develop/ui/views/notifications/notification-permission).

## Security properties

- Android peer credentials must report the wrapper's exact UID. Cross-UID callers are rejected before dispatch.
- Wrapper metadata must declare `open-uri`, `notifications`, `printing`, `audio-input`, `camera`, `accessibility`, or `secrets`; undeclared operations fail closed.
- Requests and every field have fixed size limits and strict UTF-8 validation.
- URL opening rejects non-HTTP schemes, missing hosts, user information, and control characters.
- Android remains the permission authority. The broker requests a permission in wrapper UI and reports requested or denied state to Linux; it does not bypass denial.
- Socket names are random per process and logged only by debuggable wrappers.
- Runtime-pack publication hashes the ABI-specific client as an immutable module.

The emulator regression covers same-UID calls, first-use permission UI, notification post and withdrawal, HTTPS dispatch, unsafe-URI rejection, and cross-UID denial. A manager-built KCalc runtime pack was also inspected to prove that its manifest contains the exact `libarchphene_android.so` build hash.

## Standard desktop adapters

Each generated wrapper starts an app-private D-Bus session daemon under its existing Android UID. The socket is stored in the wrapper cache, accepts EXTERNAL authentication, and is inaccessible outside the Android sandbox. It is not a system bus and does not connect different wrappers.

The wrapper also starts one frontend that owns `org.freedesktop.portal.Desktop` and `org.freedesktop.Notifications`. It currently implements:

- XDG OpenURI `OpenURI`, `SchemeSupported`, `version`, and request responses;
- XDG Notification `AddNotification`, `RemoveNotification`, `SupportedOptions`, and `version`;
- XDG Print `PreparePrint`, `Print`, `version`, and asynchronous request responses;
- freedesktop.org notification `GetCapabilities`, `GetServerInformation`, `Notify`, and `CloseNotification`;
- `xdg-open` as a fallback for applications that do not call the portal directly.

`DBUS_SESSION_BUS_ADDRESS`, `GIO_USE_PORTALS=1`, `NOTIFY_FORCE_PORTAL=1`, and the private `xdg-open` directory are exported to the unmodified Linux process. Android target-SDK executable restrictions are preserved: daemon and adapter executables run directly from the APK native-library directory, while only an app-private symlink is created for `xdg-open`.

The first standard notification remains queued while Android displays `POST_NOTIFICATIONS`; consent posts it without requiring the Linux application to retry. Denial discards the bounded queue and remains authoritative. The queue holds at most 32 notification IDs.

Dual-ABI builds pass ELF dependency checks. A manager-generated KCalc wrapper passes portal discovery, HTTP(S)-only scheme policy, portal and classic notification permission/post/withdraw, portal OpenURI, and `xdg-open` on the x86_64 emulator. The private-bus contract and first-use notification lifecycle also pass on a physical AArch64 Samsung device.

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

The x86_64 emulator and physical AArch64 Samsung regressions validate the real permission dialog, capture before-consent rejection, 1280x720 JPEG capture and signature/byte checks, invalid-dimension rejection, denial, and no automatic reprompt. This explicit one-shot API does not yet make unmodified Linux camera applications work: the standard XDG Camera portal must return a PipeWire remote, so a private PipeWire producer backed by Camera2 remains required.

## Accessibility

A wrapper declaring `accessibility` can publish up to 1024 virtual nodes from a regular descriptor containing at most 1 MiB of validated JSON. The tree requires bounded IDs, acyclic parent links, a 16384-pixel maximum logical viewport, validated roles/text/states, and positive bounds. The compositor view exposes those nodes through `AccessibilityNodeProvider`, scales Linux logical bounds into the current Android viewport, preserves accessibility and input focus, and emits framework content, text, focus, selection, click, and window events.

Android actions are returned to Linux through a bounded 64-entry queue. Click, focus, set-text, and forward/backward scroll are supported; set-text payloads are UTF-8 bounded to 1024 characters, and Linux polling is capped at 250 milliseconds so it cannot monopolize the shared capability dispatcher. Publishing an invalid tree leaves the last valid model intact. Accessibility does not require or justify Android's powerful accessibility-service permission: production wrappers expose only their own UI semantics. The independently built test fixture uses a test-only service solely to verify what Android receives.

The x86_64 emulator and physical AArch64 Samsung regressions validate framework enumeration, scaled bounds, events, cyclic-tree rejection with rollback, click and edit action routing, empty queues, and stopped-broker rejection. Unmodified Qt/GTK applications are not complete yet: Archphene still needs a private AT-SPI2 adapter that translates their standard D-Bus object trees and actions into this transport, including secondary-window ownership.

## Secrets and keyrings

A wrapper declaring `secrets` receives a private encrypted collection. Secret identifiers, labels, and string attributes are validated and bounded; secret bytes enter and leave only through regular file descriptors. Records are named by a SHA-256 digest of the identifier, encrypted in full with AES-256-GCM, authenticated against the record filename, and written atomically. The non-exportable key is generated in Android Keystore with randomized encryption and unlocked-device enforcement where supported. The store is limited to 256 records, 64 KiB per secret, 8 KiB of attributes, and a 1 MiB metadata index. No Android runtime permission or accessibility-service privilege is involved because the collection remains inside the wrapper UID.

The x86_64 emulator and physical AArch64 Samsung regressions validate exact readback, ciphertext plaintext absence, metadata, overwrite, persistence across process death, malformed and oversized rejection, deletion, stale-broker rejection, and absence of secret values from Android logs. This explicit API is not yet transparent to unmodified Linux applications. A private implementation of the [freedesktop.org Secret Service API](https://specifications.freedesktop.org/secret-service/latest/) must translate standard session-bus calls onto this store.

## Remaining adapters

The Secret Service D-Bus adapter, AT-SPI2 adapter, streaming XDG Camera/PipeWire, richer notification actions, non-HTTP URI policies, and other desktop portals remain unimplemented.

## Native client

The public header is `native/archphene-android-capability/archphene_android.h`. Callers link `libarchphene_android.so` and use the exported environment rather than discovering or hard-coding the socket. The wire protocol is private to Archphene and may only be used inside the wrapper UID.
