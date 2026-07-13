# Native compositor Android probe - 2026-07-13

## Scope

This experiment validates the first shared native Wayland compositor slices without using either application-specific Java compositor.

The native library uses wayland-server 0.31.13 with its pure-Rust backend. Android creates a socket pair and transfers ownership of the server file descriptor through JNI.

## Protocol sequence

1. Create the native display and advertise wl_compositor version 6.
2. Adopt an Android socket file descriptor as a Wayland client.
3. Request wl_registry and complete wl_display.sync.
4. Find and bind wl_compositor.
5. Create wl_surface and confirm native live-surface count is one.
6. Destroy wl_surface and confirm native live-surface count returns to zero.
7. Decode and report any wl_display.error deterministically.

## Build boundary

Rust, Java, Android resources, DEX generation, APK packaging, alignment, signing, and signature verification run in Podman Linux containers.

Windows performs only ADB device selection, APK installation, launch, and logcat result collection.

## Results

| Target | Device | Result |
|---|---|---|
| x86_64 | Android 16 emulator, emulator-5554 | Passed registry, compositor bind, and surface create/destroy |
| arm64-v8a | Samsung Galaxy S22 Ultra, RFCT90AEEFA | Passed registry, compositor bind, and surface create/destroy |

The arm64 result is emitted through a structured logcat marker, so it remains observable when Samsung System UI covers the Activity with the lock screen.

## Commands

    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi x86_64
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi x86_64 -Serial emulator-5554
    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi arm64-v8a
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi arm64-v8a -Serial RFCT90AEEFA

## Boundary

This does not yet render an application. SHM pools/buffers, surface pending/committed state, damage, frame callbacks, xdg-shell, seats/input, popups, clipboard, text input, scaling, and Android presentation remain to be migrated before KCalc or Mousepad can use the shared core.
