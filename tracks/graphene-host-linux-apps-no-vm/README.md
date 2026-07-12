# GrapheneOS Host + Linux Apps Without VM

Purpose: make generic Linux desktop apps behave like Android-managed apps on a GrapheneOS-derived host without using a VM.

This is a custom platform development track. It requires changes to Android/GrapheneOS internals, not just packages.

## Design center

- Android assigns identity: package name, UID, SELinux category, permissions, storage, lifecycle.
- Linux apps run as host processes, but never as ambient host processes.
- Arch package content is ingested into immutable app/runtime stores.
- Each Linux app launches through a platform-owned spawner.
- Android-side brokers mediate files, network, audio, camera, clipboard, notifications, USB, Bluetooth, and sensors.
- Linux GUI apps render through a Wayland-to-Android Surface bridge.

## First milestones

1. Launch one hardcoded glibc/Wayland Linux app from an Android wrapper.
2. Run it under a distinct UID and SELinux domain.
3. Give it a synthetic root via mount namespace.
4. Show its window in an Android Surface.
5. Add Storage Access Framework file open/save.
6. Add network permission enforcement.
7. Add package ingestion from an Arch package and `.desktop` file.

## Non-goals

- No pacman writes to Android host `/`.
- No raw device-node access by Linux apps.
- No full desktop session as the security boundary.
- No shared runtime UID for app network/filesystem access.
- No VM as the product architecture.

## Main doc

See [no-VM Linux apps as Android apps](../../docs/no-vm-linux-apps-as-android-apps.md).
