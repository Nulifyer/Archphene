# Native compositor Android probe - 2026-07-13

## Scope

This experiment validates the first shared native Wayland compositor slices without using either application-specific Java compositor.

The native library uses wayland-server 0.31.13 with its pure-Rust backend. Android creates a socket pair and transfers ownership of the server file descriptor through JNI.

## Protocol sequence

1. Create the native display and advertise wl_compositor version 6 and wl_shm version 1.
2. Adopt an Android socket file descriptor as a Wayland client.
3. Request wl_registry and complete wl_display.sync.
4. Find and bind wl_compositor.
5. Find and bind wl_shm; receive ARGB8888 and XRGB8888 format events.
6. Create wl_surface and confirm native live-surface count is one.
7. Destroy wl_surface and confirm native live-surface count returns to zero.
8. Create a 32-byte memfd, fill it with deterministic pixels, and transfer it with wl_shm.create_pool through SCM_RIGHTS.
9. Create a 4x2 ARGB8888 wl_buffer, validate offset/size/stride bounds, and confirm the received pixels sum to 528.
10. Destroy the buffer and pool and confirm both native resource counts return to zero.
11. Decode and report any wl_display.error deterministically.

## Build boundary

Rust, Java, Android resources, DEX generation, APK packaging, alignment, signing, and signature verification run in Podman Linux containers.

Windows performs only ADB device selection, APK installation, launch, and logcat result collection.

## Results

| Target | Device | Result |
|---|---|---|
| x86_64 | Android 16 emulator, emulator-5554 | Passed SHM FD transfer, checked pool/buffer and pixel reads, resource destruction, and surface lifecycle |
| arm64-v8a | Samsung Galaxy S22 Ultra, RFCT90AEEFA | Passed SHM FD transfer, checked pool/buffer and pixel reads, resource destruction, and surface lifecycle |

The arm64 result is emitted through a structured logcat marker, so it remains observable when Samsung System UI covers the Activity with the lock screen.

## Commands

    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi x86_64
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi x86_64 -Serial emulator-5554
    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi arm64-v8a
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi arm64-v8a -Serial RFCT90AEEFA

## Boundary

This does not yet render an application. SHM buffer attachment, surface pending/committed state, damage, frame callbacks, xdg-shell, seats/input, popups, clipboard, text input, scaling, and Android presentation remain to be migrated before KCalc or Mousepad can use the shared core.
