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

## Compatibility boundary

The C ABI is a bridge primitive, not transparent compatibility for unmodified applications. Applications already using XDG Desktop Portal expect D-Bus interfaces such as [OpenURI](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.OpenURI.html) and [Notification](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Notification.html). Archphene still needs a private session bus and portal frontend/backend adapter that translates those standard calls to this broker. Toolkit-specific fallbacks may also be needed for applications that bypass portals.

Audio, printing, camera, drag-and-drop, accessibility, secrets/keyrings, richer notification actions, and non-HTTP URI policies remain unimplemented. Each must define an Android permission and lifecycle policy before receiving a broker command.

## Native client

The public header is `native/archphene-android-capability/archphene_android.h`. Callers link `libarchphene_android.so` and use the exported environment rather than discovering or hard-coding the socket. The wire protocol is private to Archphene and may only be used inside the wrapper UID.
