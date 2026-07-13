# Native compositor Android probe - 2026-07-13

## Scope

This experiment validates the first shared native Wayland compositor slices without using either application-specific Java compositor.

The native library uses wayland-server 0.31.13 with its pure-Rust backend. Android creates a socket pair and transfers ownership of the server file descriptor through JNI.

## Protocol sequence

1. Create the native display and advertise wl_compositor version 6, wl_shm version 1, xdg_wm_base version 6, wl_seat version 7, and wl_data_device_manager version 3.
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
19. Publish the Android frame center, inject a system-level ADB tap there, queue its real MotionEvent objects onto the compositor thread, and assert wl_pointer enter, BTN_LEFT press/release, leave, frame boundaries, coordinates, timestamps, and increasing serials.
20. Release wl_pointer and wl_seat and confirm the pointer live count returns to zero.
21. Destroy the role object before xdg_surface, then destroy wl_surface and xdg_wm_base; confirm every xdg, surface, pool, and buffer live count returns to zero.
22. While a popup owns focus, resolve the root xdg_toplevel, send a resize configure, acknowledge it, stage xdg_surface window geometry, and apply it only with the parent wl_surface.commit without stealing popup focus.
23. Bind wl_data_device_manager, create a text source and seat data device, advertise plain-text MIME types, set selection with a real keyboard serial, verify the server-created offer and selection events, clear selection, observe source cancellation, and destroy all data resources without leaks.
24. Decode and report any wl_display.error deterministically.

## Build boundary

Rust, Java, Android resources, DEX generation, APK packaging, alignment, signing, and signature verification run in Podman Linux containers.

Windows performs only ADB device selection, APK installation, launch, and logcat result collection.

## Results

| Target | Device | Result |
|---|---|---|
| x86_64 | Android 16 emulator, emulator-5554 | Passed frame/pixel presentation, xdg configure/ack, committed parent window geometry, popup-focus preservation, system-injected Android MotionEvent routing, exact pointer wire events, native text-selection lifecycle, and ordered teardown |
| arm64-v8a | Samsung Galaxy S22 Ultra, RFCT90AEEFA | Passed frame/pixel presentation, xdg configure/ack, committed parent window geometry, popup-focus preservation, system-injected Android MotionEvent routing, wrapped 32-bit timestamps, exact pointer wire events, native text-selection lifecycle, and ordered teardown |

The arm64 result is emitted through a structured logcat marker, so it remains observable when Samsung System UI covers the Activity with the lock screen.

## Commands

    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi x86_64
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi x86_64 -Serial emulator-5554
    ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi arm64-v8a
    ./scripts/test-native-compositor-probe.ps1 -AndroidAbi arm64-v8a -Serial RFCT90AEEFA

## Boundary

This renders protocol test frames and validates Android MotionEvent/hardware-key routing, XKB transfer, configure queues, map/unmap, positioners, live output state, nested popup grabs/dismissal, output-bound popup flip/slide/resize constraints, reactive output and committed-parent-geometry popup reconfiguration, commit-gated popup geometry with pre-ack/post-commit pixel assertions, popup-focus preservation across root commits, committed wl_region add/subtract input snapshots, effective-region root/popup fall-through, synchronized recursive subsurface composition and local-coordinate input routing, root-to-nested-popup pointer/button routing with persisted local coordinates, and exact clipped popup SHM composition/restoration pixels, but it does not yet run an application through the native core. Frame callbacks fire after the native copy and must be paced by Android Choreographer/presentation. The probe serializes JNI access through one compositor thread; this must become a reusable service boundary. Touch, scroll axes, cursor roles, damage/transform/scale state, Android ClipboardManager payload brokerage, text input, continuous presentation, and wrapper integration remain.
