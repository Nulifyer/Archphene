# Wayland compositor reference review

Date: 2026-07-13

## Scope

This review compares the Archphene Android bridge with compositor behavior in:

- Hyprland commit `a51a369fd3f139ed3a66a84cdf0a0e2ce4e7fa55`
- niri commit `0777769e719b7c9b7c980d4ea66288bfbb4da5b3`
- Smithay commit `3021f619e2ae4dab8bfb1e21f3f210923b9b6582`
- KWin commit `41024dc64db542dae3e0d0d4aacd184402dfe33a`
- Mutter commit `c1e931f2c9dd76c0f6f495082d6e0bc9436a485a`
- xdg-shell version 7

The goal is not to embed a desktop environment. Hyprland and niri are references for the compositor-side state machines that the Android-owned bridge must provide.

## Main finding

The bridge must stop treating Wayland as a set of independent request handlers. Input, popup, surface, output, and frame behavior are linked state machines owned by a seat and a surface tree.

The KCalc menu bug demonstrated the first part of this distinction. A physical press creates an implicit pointer grab until release. Separately, `xdg_popup.grab` creates an explicit owner-events grab that remains active for the popup stack.

## Popup and input model

Smithay provides the clearest reference implementation:

- `PopupManager::grab_popup` validates the root, serial, parent, and topmost-popup relationship.
- `PopupGrab` tracks the current popup, previous serial, root surface, and nested popup stack.
- `PopupPointerGrab` allows focus among surfaces owned by the grabbed client.
- A press outside that client's surfaces dismisses the popup stack and restores focus.
- `PopupKeyboardGrab` keeps keyboard focus on the topmost popup and restores the root focus when the grab ends.

niri wires these pieces together in `src/handlers/xdg_shell.rs`: it creates one Smithay popup grab, installs both pointer and keyboard grabs, and retains the resulting grab state.

Hyprland independently tracks popup trees, nested children, mapping, damage, effective input regions, repositioning, scale propagation, and seat focus restoration. Its source also shows why a simple rectangular popup list will not generalize: popup content can live in subsurfaces and input regions need not equal buffer bounds.

## Required bridge changes

### P0: finish xdg popup semantics

- [x] Validate `xdg_popup.grab` against the serial from the triggering input event.
- [x] Track one explicit popup grab per seat, with root surface and ordered popup stack.
- [x] Enforce parent/topmost ordering for nested popups.
- [x] Route pointer motion and buttons to root, parent-popup, and nested-popup surfaces from the owning client while the grab is active, using surface-local coordinates.
- [~] Send `xdg_popup.popup_done` child-first and idempotently, and finish wiring outside press, Back/Escape, Activity focus loss, and invalidated-parent triggers.
- [x] Restore pointer and keyboard focus to the root after dismissal.
- [x] Snapshot `wl_region` add/subtract state at `set_input_region`, latch it on commit, intersect it with surface bounds, and use it for root/parent/nested popup hit testing and fall-through.
- [x] Implement `xdg_popup.reposition` and `repositioned(token)`, persist the applied configure geometry, apply output-bound flip/slide/resize adjustments in protocol order, and reconfigure reactive popups when Android output bounds change.

### P1: real surface trees

- [~] `wl_subcompositor` and `wl_subsurface` objects, parent-relative positioning, stacking, rendering, and input routing are implemented. Finish synchronized/desynchronized pending-state latching.
- Preserve atomic parent commits: synchronized child buffers and position/stack changes must become visible only with the parent commit.
- [~] Compose clipped nested popup SHM buffers in protocol stacking order with ARGB/XRGB source-over blending; subsurface composition remains.
- Apply damage per surface instead of rebuilding a single flattened bitmap after every commit.
- Send output enter/leave and scale updates to every surface in the tree.

### P1: Android text and clipboard integration

- [~] `zwp_text_input_v3` enable/disable state and Android IME retention are implemented and regression-tested for GTK search. Complete surrounding-text, preedit, cursor-rectangle, and delete-surrounding-text fidelity.
- [x] `wl_data_device_manager` text transfer is brokered through Android `ClipboardManager` in both directions with feedback-loop suppression.
- Stream MIME payloads over descriptors; do not copy arbitrary clipboard data into bridge logs or global files.
- Add drag-and-drop only after selection and offer lifecycle behavior is correct.

### P2: scaling and presentation

- Add `wp_viewporter` and `wp_fractional_scale_v1`; retain `QT_FONT_DPI` only as a compatibility fallback.
- Keep Android pixels, Wayland logical coordinates, buffer coordinates, and input coordinates as explicitly typed transforms.
- Implement frame callbacks from Android Choreographer and add presentation feedback later.
- Respect buffer scale, transform, viewport source/destination, and damage-buffer coordinates.

### P2: richer applications

- Add relative pointer, pointer constraints, cursor shape, tablet, and gesture protocols when an application proves they are needed.
- Add dmabuf/EGL rendering after SHM correctness and lifecycle tests are stable.
- Add layer shell only for applications that genuinely require desktop shell surfaces; it is not needed for ordinary Android-wrapped applications.

## Architecture recommendation

Use a Rust native core based on wayland-server's pure-Rust backend behind a narrow JNI boundary. Android owns Activity/window integration and creates the app-local socket; the native core adopts accepted client FDs and owns Wayland display, resource, and protocol state.

This path was selected because it cross-compiles without a device libwayland-server or libffi dependency, while preserving a route to reuse Smithay state-machine components as protocol coverage grows. KWin, Mutter, Hyprland, niri, and Smithay remain behavior and test references rather than runtime dependencies.

Validated bootstrap slices:

- x86_64 and AArch64 Android shared-library builds in the pinned Podman NDK/Rust image;
- Android socket-pair FD adoption into the native display;
- wl_display.sync round trips on emulator and Samsung device;
- wl_registry discovery, wl_compositor, wl_shm, and xdg_wm_base binds, SHM format events and FD transfer, checked padded-stride frame copies, wl_surface commit/release/callback, XRGB-to-Android bitmap conversion, exact pixel checks, visible presentation, and resource lifecycles on both the x86_64 emulator and AArch64 Samsung device;
- xdg_toplevel permanent-role assignment, the client initial bufferless commit, ordered toplevel/surface configure events, exact serial acknowledgement before buffer attachment, post-ack SHM presentation, and role-before-surface teardown on both targets;
- pointer-and-keyboard wl_seat metadata, per-client input resources, mapped-surface focus, Android MotionEvent and hardware-key routing, valid input-serial popup grabs, nested topmost grab stacks, persisted output-constrained popup geometry shared by configure/composition/input, reactive output-bound reconfiguration, and child-first idempotent dismissal/teardown with exact wire checks on both targets.

Migration order is registry/globals, SHM/pools/buffers, surfaces/regions, xdg-shell, seats/input, popups/subsurfaces, clipboard/text input, output/scaling, then GPU presentation.

## Test gates

- Press/release remains on the original surface when a popup maps between events.
- File, Edit, Settings, and Help remain open after a tap.
- [x] Moving and clicking between root, parent-popup, and nested-popup surfaces follows owner-events semantics in the native wire probe. Committed input-region exclusions fall through to the next eligible surface.
- Outside press sends `popup_done` exactly once and restores root focus.
- Nested submenu dismissal removes only the topmost popup when appropriate.
- [x] Popup reposition tokens are acknowledged, and flip/slide/resize plus reactive output-bound changes keep configure, pixels, and input coordinates aligned.
- Subsurface commits obey sync/desync and stacking rules.
- Scale and input coordinates remain aligned at Android densities 1.0, 2.625, and 3.0.
- Clipboard and IME data cross only their Android broker APIs and remain under the app UID.
- [x] Pointer enter, motion, press, release, leave, and frame ordering remains exact with system-injected Android MotionEvent input on emulator and Samsung.
