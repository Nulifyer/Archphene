# Wayland compositor reference review

Date: 2026-07-11

## Scope

This review compares the Archphene Android bridge with compositor behavior in:

- Hyprland commit `a51a369fd3f139ed3a66a84cdf0a0e2ce4e7fa55`
- niri commit `0777769e719b7c9b7c980d4ea66288bfbb4da5b3`
- Smithay commit `3021f619e2ae4dab8bfb1e21f3f210923b9b6582`
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

- Validate `xdg_popup.grab` against the serial from the triggering input event.
- Track one explicit popup grab per seat, with root surface and ordered popup stack.
- Enforce parent/topmost ordering for nested popups.
- Route pointer events to any surface from the owning client while the grab is active.
- Send `xdg_popup.popup_done` on outside press, Back/Escape, Activity focus loss, or invalidated parent.
- Restore pointer and keyboard focus to the root after dismissal.
- Use each surface's effective input region rather than buffer bounds alone.
- Implement `xdg_popup.reposition` and `repositioned(token)`.

### P1: real surface trees

- Implement `wl_subcompositor` and `wl_subsurface` synchronized/desynchronized commits.
- Preserve parent-relative offsets, stacking above/below siblings, and atomic parent commits.
- Compose nested popup and subsurface trees in protocol stacking order.
- Apply damage per surface instead of rebuilding a single flattened bitmap after every commit.
- Send output enter/leave and scale updates to every surface in the tree.

### P1: Android text and clipboard integration

- Implement `zwp_text_input_v3` and translate its enable, content type, surrounding text, cursor rectangle, preedit, commit, and delete operations to Android IME APIs.
- Implement `wl_data_device_manager` with Android ClipboardManager as a broker.
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

Continue the Java compositor only through P0 because it gives fast emulator feedback. Before broadening to P1/P2, move protocol parsing and state into a native compositor library behind the existing JNI boundary.

Two viable paths are:

1. A small C/C++ `libwayland-server` compositor core. This fits the current JNI/native build path and allows only the protocols Archphene exposes.
2. A Rust Smithay core. This supplies tested seat, popup, surface-tree, selection, text-input, and protocol machinery, but adds a larger Rust/NDK integration and dependency surface.

Smithay is the stronger semantic reference. A small `libwayland-server` core is likely the lower-risk first production implementation for this project. Hyprland and niri should remain test/reference sources, not runtime dependencies.

## Test gates

- Press/release remains on the original surface when a popup maps between events.
- File, Edit, Settings, and Help remain open after a tap.
- Moving and clicking between menu-bar and popup surfaces follows owner-events semantics.
- Outside press sends `popup_done` exactly once and restores root focus.
- Nested submenu dismissal removes only the topmost popup when appropriate.
- Popup reposition tokens are acknowledged.
- Subsurface commits obey sync/desync and stacking rules.
- Scale and input coordinates remain aligned at Android densities 1.0, 2.625, and 3.0.
- Clipboard and IME data cross only their Android broker APIs and remain under the app UID.
