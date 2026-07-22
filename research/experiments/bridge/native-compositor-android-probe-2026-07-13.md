# Native compositor Android probe - 2026-07-13

## Scope

This experiment validates the first shared native Wayland compositor slices without using either application-specific Java compositor.

The native library uses wayland-server 0.31.13 with its pure-Rust backend. Android creates a socket pair and transfers ownership of the server file descriptor through JNI.

## Protocol sequence

1. Create the native display and advertise wl_compositor version 6, wl_shm version 1, xdg_wm_base version 6, wl_seat version 9, wl_data_device_manager version 3, wp_viewporter version 1, wp_fractional_scale_manager_v1 version 1, and zwp_pointer_gestures_v1 version 3.
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
18. Commit wl_surface rotate-90 plus scale-2 state without attaching a new buffer, verify inverse-transformed 1x2 Android pixels and logical dimensions, then commit normal plus scale-1 and verify the retained 4x2 source is restored.
19. Keep each wl_surface.frame callback pending through the following wl_display.sync, submit the Android bitmap, wait for a real Choreographer frame timestamp, then emit and verify the exact wl_callback.done timestamp and resource deletion.
20. Accumulate wl_surface.damage and damage_buffer independently, inverse-transform and scale buffer damage, cache synchronized child damage until parent commit, clip translated root bounds, map them to the Android viewport, and drain damage atomically with presentation.
21. Bind wl_seat, require the Archphene name plus pointer/keyboard/touch capabilities, and create a wl_pointer for the client owning the mapped xdg surface.
22. Publish the Android frame center, inject a system-level ADB tap and mouse-wheel event there, queue the real MotionEvent objects onto the compositor thread, and assert wl_pointer enter, BTN_LEFT press/release, v9 wheel source/value120/relative-direction/continuous axes, leave, frame boundaries, coordinates, timestamps, and increasing serials.
23. Release wl_pointer and wl_seat and confirm the pointer live count returns to zero.
24. Retain the mapped xdg_toplevel through clipboard and text-input tests, then destroy the role object before xdg_surface, wl_surface, and xdg_wm_base and confirm every live count returns to zero.
25. While a popup owns focus, resolve the root xdg_toplevel, send a resize configure, acknowledge it, stage xdg_surface window geometry, and apply it only with the parent wl_surface.commit without stealing popup focus.
26. Bind wl_data_device_manager, create a text source and seat data device, advertise plain-text MIME types, set selection with a bounded, same-client real keyboard serial, verify the server-created offer and selection events, clear selection, observe source cancellation, and destroy all data resources without leaks.
27. With the bridge focused, request the Linux text selection through wl_data_source.send, receive the source pipe through JNI, verify the exact bytes, and publish them through Android ClipboardManager.
28. Announce an Android text offer without reading clipboard content, assert both descriptor queues remain empty, then issue wl_data_offer.receive, read ClipboardManager only after that request, and verify exact bytes through the client destination pipe.
29. Withdraw the Android offer on focus loss and close all queued descriptors.
30. Bind zwp_text_input_manager_v3 version 1, create a seat text-input, and verify enter targets the real keyboard-focused mapped wl_surface without activating IME.
31. Stage enable, surrounding UTF-8 text, content type, and cursor rectangle requests; commit them atomically and verify Android-facing state and one show transition.
32. Emit preedit_string, commit_string, and delete_surrounding_text followed by matching done serials, then commit disable and verify one hide transition.
33. Destroy text-input, clipboard, seat, and xdg resources without leaks, and decode any wl_display.error deterministically.
34. On an isolated client/core, crop a 4x2 buffer to 2x2 with wp_viewport, scale it to 4x4, verify exact Android pixels, reset viewport state, and receive fractional preferred scale in 120ths.
35. Inject a system-level Android swipe and validate wl_touch down/motion/up/frame coordinates, contact IDs, timestamps, serials, and teardown.
36. Validate wl_pointer cursor enter-serial ownership, permanent cursor role, 2x2 SHM cursor bitmap transfer into Android PointerIcon, hotspot adjustment via wl_surface.offset, application-frame isolation, and null-surface hiding.
37. Dispatch a synthetic two-pointer Android MotionEvent sequence through the Activity listener and assert zwp_pointer_gesture_swipe_v1, pinch_v1, and hold_v1 begin/update/end/cancel wire payloads.
38. Create a real Android InputConnection on the compositor view, map committed content hints/purpose and UTF-8 surrounding byte offsets into EditorInfo/UTF-16 selections, route non-ASCII preedit/commit/delete/editor-action events, reject malformed UTF-8 at JNI, and prove ClipboardManager content remains unread until wl_data_offer.receive.

## Build boundary

Rust, Java, Android resources, DEX generation, APK packaging, alignment, signing, and signature verification run in Podman Linux containers.

Windows performs only ADB device selection, APK installation, launch, and logcat result collection.

## Results

| Target | Device | Result |
|---|---|---|
| x86_64 | Android 16 emulator, emulator-5554 | Passed clipped damage-batched frame/pixel presentation with Choreographer-timestamped deferred callbacks, inverse buffer transform/scale and no-reattach restoration, xdg configure/ack, committed parent window geometry, popup-focus preservation, system-injected Android tap/wheel/touch MotionEvent routing, exact pointer, v9 wheel-axis, cursor, viewporter/fractional-scale, and swipe/pinch/hold wire events, native text-selection lifecycle, bidirectional focused demand-driven Android ClipboardManager pipe transfer, Android InputConnection UTF-8/content/editor-action mapping, text-input v3 focus/state/event sequencing, and ordered teardown |
| arm64-v8a | Samsung Galaxy S22 Ultra, RFCT90AEEFA | Passed clipped damage-batched frame/pixel presentation with Choreographer-timestamped deferred callbacks, inverse buffer transform/scale and no-reattach restoration, xdg configure/ack, committed parent window geometry, popup-focus preservation, system-injected Android tap/wheel/touch MotionEvent routing, wrapped 32-bit timestamps, exact pointer, v9 wheel-axis, cursor, viewporter/fractional-scale, and swipe/pinch/hold wire events, native text-selection lifecycle, bidirectional focused demand-driven Android ClipboardManager pipe transfer, Android InputConnection UTF-8/content/editor-action mapping, text-input v3 focus/state/event sequencing, and ordered teardown |

The arm64 result is emitted through a structured logcat marker, so it remains observable when Samsung System UI covers the Activity with the lock screen.

## References

- [Wayland protocol specification: wl_surface](https://wayland.freedesktop.org/docs/html/apa.html#protocol-spec-wl_surface)
- [Smithay transform geometry reference mirrored in Android](https://android.googlesource.com/platform/external/rust/android-crates-io/+/fd55dc012125592ab72ea3a66c592999c1cf0c0e/crates/smithay/src/utils/geometry.rs)

## Commands

    ./scripts/build-native-compositor-probe-podman.sh --android-abi x86_64
    ./scripts/test-native-compositor-probe.sh --android-abi x86_64 --serial emulator-5554
    ./scripts/build-native-compositor-probe-podman.sh --android-abi arm64-v8a
    ./scripts/test-native-compositor-probe.sh --android-abi arm64-v8a --serial RFCT90AEEFA

## Boundary

This renders protocol test frames and validates Android MotionEvent/hardware-key routing, XKB transfer, configure queues, map/unmap, positioners, live output state, nested popup grabs/dismissal, output-bound popup flip/slide/resize constraints, reactive output and committed-parent-geometry popup reconfiguration, commit-gated popup geometry with pre-ack/post-commit pixel assertions, popup-focus preservation across root commits, committed wl_region add/subtract input snapshots, effective-region root/popup fall-through, synchronized recursive subsurface composition and local-coordinate input routing, root-to-nested-popup pointer/button routing with persisted local coordinates, exact clipped popup SHM composition/restoration pixels, modern scaling, touch, cursor, and pointer gesture paths, but it does not yet run an application through the native core. Frame callbacks remain pending through wl_display.sync and complete with Android Choreographer timestamps after bitmap submission. The probe serializes JNI access through one compositor thread; this must become a reusable service boundary. Extracting the validated lifecycle, clipboard broker, InputConnection, content mapping, and cursor feedback into a continuously scheduled shared Activity/service and integrating unmodified application wrappers remain.
