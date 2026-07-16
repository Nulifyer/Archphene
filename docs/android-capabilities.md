# Android capability broker

Archphene Linux processes keep the Android UID, SELinux domain, lifecycle, and permission state of their wrapper APK. A Linux syscall cannot grant an Android permission. Operations that need Android services therefore cross an explicit, capability-gated broker owned by the wrapper Activity.

## Validated APIs

The shared bridge starts a randomized abstract Unix socket for each wrapper launch and exports its name as `ARCHPHENE_ANDROID_BROKER`. The glibc runtime pack contains ABI-matched `libarchphene_android.so` clients for x86_64 and AArch64.

Protocol version 1 currently supports:

- `archphene_android_open_uri`: opens an ordinary host-bearing HTTP or HTTPS URI through Android `ACTION_VIEW`;
- `archphene_android_notify`: requests Android 13+ notification permission on first use and posts a bounded wrapper-owned notification;
- `archphene_android_withdraw_notification`: removes a notification created by that wrapper.

The Android intent behavior follows the platform's [common intent guidance](https://developer.android.com/guide/components/intents-common). Runtime notification permission follows the [Android notification permission model](https://developer.android.com/develop/ui/views/notifications/notification-permission).

## Security properties

- Android peer credentials must report the wrapper's exact UID. Cross-UID callers are rejected before dispatch.
- Wrapper metadata must declare `open-uri` or `notifications`; undeclared operations fail closed.
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
- freedesktop.org notification `GetCapabilities`, `GetServerInformation`, `Notify`, and `CloseNotification`;
- `xdg-open` as a fallback for applications that do not call the portal directly.

`DBUS_SESSION_BUS_ADDRESS`, `GIO_USE_PORTALS=1`, `NOTIFY_FORCE_PORTAL=1`, and the private `xdg-open` directory are exported to the unmodified Linux process. Android target-SDK executable restrictions are preserved: daemon and adapter executables run directly from the APK native-library directory, while only an app-private symlink is created for `xdg-open`.

The first standard notification remains queued while Android displays `POST_NOTIFICATIONS`; consent posts it without requiring the Linux application to retry. Denial discards the bounded queue and remains authoritative. The queue holds at most 32 notification IDs.

Dual-ABI builds pass ELF dependency checks. A manager-generated KCalc wrapper passes portal discovery, HTTP(S)-only scheme policy, portal and classic notification permission/post/withdraw, portal OpenURI, and `xdg-open` on the x86_64 emulator. The private-bus contract and first-use notification lifecycle also pass on a physical AArch64 Samsung device.

## Audio output

Wrappers whose verified ELF closure contains a Pulse client declare `audio-output`. The shared bridge then starts a PulseAudio native-protocol server inside that wrapper's Android UID, exports its private Unix socket through `PULSE_SERVER`, and renders a stereo 48 kHz sink through Android AAudio. OpenSL ES is the fallback when AAudio cannot initialize. The socket is under app-private cache storage and is never shared across wrapper UIDs.

The manager embeds one checksum-verified Bionic server payload per supported ABI and copies it only into audio-enabled wrappers. The Linux application continues using its unmodified glibc Pulse client. On the x86_64 emulator, an on-device conversion of the official Arch `pavucontrol` package detects GTK4 and Pulse, generates an `audio-output` wrapper, launches the private AAudio sink, authenticates the Linux client, creates pavucontrol's monitor stream, and renders the live Volume Control GUI. Direct server playback also passes on the x86_64 emulator and physical AArch64 Samsung device.

Speaker playback needs no runtime permission. Microphone capture is deliberately absent: it requires a separate input capability and an explicit `RECORD_AUDIO` request at the point of use. Printing, camera, drag-and-drop, accessibility, secrets/keyrings, richer notification actions, non-HTTP URI policies, and other desktop portals remain unimplemented. Each must define an Android permission and lifecycle policy before receiving a broker command.

## Native client

The public header is `native/archphene-android-capability/archphene_android.h`. Callers link `libarchphene_android.so` and use the exported environment rather than discovering or hard-coding the socket. The wire protocol is private to Archphene and may only be used inside the wrapper UID.
