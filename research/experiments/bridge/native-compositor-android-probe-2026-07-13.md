# Native compositor Android probe - 2026-07-13

## Scope

This experiment validates the first shared native Wayland compositor slices without using either application-specific Java compositor.

The native library uses wayland-server 0.31.13 with its pure-Rust backend. Android creates a socket pair and transfers ownership of the server file descriptor through JNI.

## Protocol sequence

1. Create the native display and advertise wl_compositor version 6, wl_shm version 1, xdg_wm_base version 6, and wl_seat version 7.
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
14. Destroy the first buffer, pool, and surface and confirm their native resource counts return to zero.
15. Bind xdg_wm_base, create a fresh wl_surface, xdg_surface, and xdg_toplevel, then perform the required initial bufferless wl_surface.commit.
16. Receive xdg_toplevel.configure followed by xdg_surface.configure, require a nonzero serial, and acknowledge that exact serial.
17. Transfer a second padded SHM buffer, commit it only after configure acknowledgement, and revalidate release, callback, 4x2 dimensions, and checksum 656.
18. Bind wl_seat, require the Archphene name and pointer-only capability, and create a wl_pointer for the client owning the mapped xdg surface.
19. Inject a native pointer sequence and assert exact wire ordering and payloads: enter at 10x20, frame, motion to 11x21, frame, BTN_LEFT press/frame, and release/frame with increasing serials.
20. Release wl_pointer and wl_seat and confirm the pointer live count returns to zero.
21. Destroy the role object before xdg_surface, then destroy wl_surface and xdg_wm_base; confirm every xdg, surface, pool, and buffer live count returns to zero.
22. Decode and report any wl_display.error deterministically.

## Build boundary

Rust, Java, Android resources, DEX generation, APK packaging, alignment, signing, and signature verification run in Podman Linux containers.

Windows performs only ADB device selection, APK installation, launch, and logcat result collection.

## Results

| Target | Device | Result |
|---|---|---|
| x86_64 | Android 16 emulator, emulator-5554 | Passed frame/pixel presentation, xdg configure/ack, focused pointer enter/motion/BTN_LEFT/frame wire events, and ordered teardown |
| arm64-v8a | Samsung Galaxy S22 Ultra, RFCT90AEEFA | Passed frame/pixel presentation, xdg configure/ack, focused pointer enter/motion/BTN_LEFT/frame wire events, and ordered teardown |

The arm64 result is emitted through a structured logcat marker, so it remains observable when Samsung System UI covers the Activity with the lock screen.

## Commands

    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi x86_64
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi x86_64 -Serial emulator-5554
    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi arm64-v8a
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi arm64-v8a -Serial RFCT90AEEFA

## Boundary

This renders a protocol test frame and injects a synthetic pointer sequence; it does not yet route Android MotionEvent objects or run an application. Frame callbacks fire after the native copy and must be paced by Android Choreographer/presentation. The raw JNI handle is single-threaded probe infrastructure and requires a serialized service boundary. Keyboard/XKB keymap and focus, touch, scroll axes, cursor roles, input regions, grabs, Android event routing, complete damage/transform/scale state, configure queues, mapped/unmapped xdg state, popups/positioners, clipboard, text input, continuous presentation, and wrapper integration remain.
