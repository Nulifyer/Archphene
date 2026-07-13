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
7. Create a 40-byte memfd, fill it with deterministic pixels, and transfer it with wl_shm.create_pool through SCM_RIGHTS.
8. Create a 4x2 XRGB8888 wl_buffer with a padded 24-byte stride, validate its bounds, and confirm the pool sample sums to 820.
9. Attach and damage the buffer, request a frame callback, and commit the surface.
10. Copy only the two 16-byte pixel rows into a tight frame and confirm its dimensions are 4x2 and checksum is 656.
11. Confirm wl_buffer.release and wl_callback.done arrive before the sync marker.
12. Lock an Android ARGB_8888 bitmap through libjnigraphics, convert Wayland BGRX bytes to Android RGBA, and assert pixels 0xff030201 and 0xff1b1a19.
13. Display the bitmap in the Activity and verify the nonblank frame visually on both targets.
14. Destroy the buffer, pool, and surface and confirm all native resource counts return to zero.
15. Decode and report any wl_display.error deterministically.

## Build boundary

Rust, Java, Android resources, DEX generation, APK packaging, alignment, signing, and signature verification run in Podman Linux containers.

Windows performs only ADB device selection, APK installation, launch, and logcat result collection.

## Results

| Target | Device | Result |
|---|---|---|
| x86_64 | Android 16 emulator, emulator-5554 | Passed padded-stride frame commit, XRGB conversion, exact Android bitmap pixels, visible presentation, and lifecycle |
| arm64-v8a | Samsung Galaxy S22 Ultra, RFCT90AEEFA | Passed padded-stride frame commit, XRGB conversion, exact Android bitmap pixels, visible presentation, and lifecycle |

The arm64 result is emitted through a structured logcat marker, so it remains observable when Samsung System UI covers the Activity with the lock screen.

## Commands

    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi x86_64
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi x86_64 -Serial emulator-5554
    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi arm64-v8a
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi arm64-v8a -Serial RFCT90AEEFA

## Boundary

This renders a protocol test frame, not an application. The probe fires frame callbacks after the native copy; production callbacks must be paced by Android Choreographer/presentation. The raw JNI handle is single-threaded probe infrastructure and requires a serialized/thread-safe service boundary. Complete damage regions, buffer transform/scale state, xdg-shell roles, seats/input, popups, clipboard, text input, continuous Android presentation, and wrapper integration remain before KCalc or Mousepad can use the shared core.
