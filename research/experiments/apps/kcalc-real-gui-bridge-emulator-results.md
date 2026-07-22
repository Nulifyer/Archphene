# Real Arch KCalc Android GUI bridge results

Date: 2026-07-10

## Validated result

The standard Android emulator now runs the unmodified Arch Linux KCalc 26.04.3 executable as a normal launcher app without a VM and without Android OS changes.

The APK is org.archphene.linux.kcalc. KCalc runs as a child of that Android app, with the app UID, SELinux domain, seccomp policy, private data directory, and Android lifecycle retained.

The KCalc ELF packaged in the APK is byte-for-byte identical to the Pacman payload:

- APK lib/x86_64/libarchphene_kcalc.so SHA-256: DA0C475F4EC44B6F8E5B493099C35D87DEEDA194584EAAC10A027948C1643B78
- stock Arch usr/bin/kcalc SHA-256: DA0C475F4EC44B6F8E5B493099C35D87DEEDA194584EAAC10A027948C1643B78

The temporary six-byte KCalc startup patch is no longer required.

## Runtime path

The app launches KCalc through the source-built glibc 2.43 runtime. Compatibility changes substitute Android-allowed syscall variants inside glibc; they do not disable Android seccomp, SELinux, or permission enforcement.

Qt uses its real Wayland platform and xdg-shell plugins. The Android-owned compositor bridge currently implements the protocol subset exercised by KCalc:

- wl_registry
- wl_shm, multiple pools, buffer offsets, and SCM_RIGHTS descriptor queuing
- wl_compositor and multiple surfaces
- wl_surface attach, damage, frame, commit, and buffer release
- xdg_wm_base, xdg_surface, xdg_toplevel, xdg_positioner, and xdg_popup
- wl_output
- wl_seat, wl_pointer, and wl_keyboard

The current inset-adjusted emulator surface is 1080x2205 with stride 4320. Android displays the committed KCalc buffers without stretching.

The bridge derives `QT_FONT_DPI` from Android display density. On the current 420 dpi emulator (`density=2.625`), Qt receives 252 dpi, so menu labels, the expression display, and button text use Android-appropriate physical sizing while the Wayland buffer remains at the real 1080x2205 surface size.

Wayland `ARGB8888` and `XRGB8888` buffers are decoded in their little-endian memory layout. The unused X byte in `XRGB8888` is forced opaque; this prevents popup-triggered repaints from turning the main window transparent.

## Interaction evidence

Validated on the running emulator:

- KCalc appears in the app drawer with a KDE Breeze-derived calculator icon.
- Touch input reaches KCalc through wl_pointer.
- A touch on 7 updates the real KCalc display to 7.
- Android KEYCODE_1 reaches KCalc through wl_keyboard and the bridge XKB keymap.
- Mixed input 1 + 2 = renders expression 1+2 and result 3.
- Cursor surfaces and their small SHM buffers no longer replace the main app surface.
- Repeated SHM pool creation works when Android batches multiple ancillary file descriptors.
- Leaving and relaunching from the app drawer keeps one MainActivity and one KCalc child via singleTask.
- Back/destroy terminates the Activity-owned child process.
- The real File popup renders through xdg_popup and SHM compositing.
- File -> Quit activates through popup-local pointer focus, exits KCalc, finishes the Activity, and returns to the launcher.
- KCalc menu labels and expression text render legibly at Android-derived Qt font DPI without clipping.
- Android touch gestures retain Wayland's implicit pointer grab from press through release, so File, Edit, Settings, and Help popups remain open after a tap.
- Popup anchors are converted from Qt logical coordinates to Android-density pixels, preventing menus from overlapping the menu bar input region.
- Mapped popup buffers remain composited across main-surface repaints and nested hit testing selects the topmost popup.
- A single tap switches directly between File, Edit, Settings, and Help; tapping the active heading closes it.
- Back sends a complete Escape gesture and dismisses the active menu without finishing MainActivity; outside taps also dismiss normally.
- The self-contained XKB map now covers the full US alphabet, arrows, navigation/editing keys, modifiers, and F1-F12.
- Keyboard-only `Alt+F`, Right Arrow to Edit, and Escape dismissal are verified with one retained KCalc process and Activity focus.
- Pointer coordinates are transformed through the `FIT_CENTER` content rectangle, so menu hit testing remains correct with system insets or aspect-fit scaling.
- File remained pixel-identical for five seconds after opening, and a direct tap switched from File to Edit while retaining the same KCalc process.
- Input mapping now reverses `ImageView.FIT_CENTER` using the committed SHM buffer dimensions rather than only the requested configure dimensions. This covers client-side decoration buffers larger than the logical window.
- Popup destruction no longer infers primary-surface destruction from a missing popup record. Only the actual primary `xdg_surface` can clear the toplevel role, so sequential File -> Settings switching cannot promote a popup surface into the Android app window.
- Partial `wl_surface.damage` and `damage_buffer` commits update a retained main-surface bitmap. Qt can rotate between SHM buffers without blanking undamaged calculator content behind menus.
- `scripts/test-kcalc-menu-switch.sh` clears app state, opens File, switches directly to Settings, verifies two native popup roles and valid grabs, rejects popup-to-primary promotion, and checks that the real KCalc child remains alive.
- Android mouse hover exit and wheel input are translated to `wl_pointer.leave` and framed vertical axis events.
- KCalc binds `wl_data_device_manager`, creates a data device/source, advertises both text MIME types, and sets a selection with a recognized recent input serial.
- Android clipboard text is announced to Qt through a server-owned `wl_data_offer`; Qt requests its preferred MIME type and receives the complete payload through its supplied file descriptor.
- Stock KCalc requested and received the seeded expression `6+5` through its normal Ctrl+V path without any KCalc changes.
- Android clipboard callbacks are deduplicated and bridge-originated writes are suppressed, preventing selection feedback loops.
- Android rotation triggers fresh `xdg_toplevel.configure` events from the actual inset-adjusted render surface; KCalc acknowledges each serial and commits a newly sized buffer without restarting.
- The emulator round trip `1080x2205 -> 2400x943 -> 1080x2205` retained one KCalc PID and is covered by `scripts/test-kcalc-live-resize.sh`.
- File -> Quit followed by launcher relaunch leaves one Android app process and exactly one new KCalc child.

Screenshots:

- tooling/build/kcalc-clean-stock-keyboard.png
- tooling/build/kcalc-calculation-1-plus-2.png
- tooling/build/kcalc-app-drawer-icon.png
- tooling/build/kcalc-file-menu-popup-v1.png
- tooling/build/kcalc-density-252.png
- tooling/build/kcalc-density-xrgb-fixed-menu.png
- tooling/build/kcalc-file-stays-native.png
- tooling/build/kcalc-edit-alone.png
- tooling/build/kcalc-settings-stays.png
- tooling/build/kcalc-help-alone.png
- tooling/build/kcalc-p5-file.png
- tooling/build/kcalc-p5-edit.png
- tooling/build/kcalc-p5-settings.png
- tooling/build/kcalc-p5-help.png
- tooling/build/kcalc-final-back-dismiss-v2.png
- tooling/build/kcalc-menu-mapping-open.png
- tooling/build/kcalc-menu-mapping-stable.png
- tooling/build/kcalc-menu-edit-switched.png
- tooling/build/kcalc-menu-switch.png
- tooling/build/kcalc-settings-switch-final.png
- tooling/build/kcalc-show-history.png
- tooling/build/kcalc-paste-6-plus-5-result.png
- tooling/build/kcalc-live-resize-landscape-v2.png
- tooling/build/kcalc-live-resize-portrait-v2.png

## Remaining engineering work

This proves KCalc, not general Linux desktop compatibility. The compositor still needs a broader protocol implementation and tests for deeper nested popup stacks, drag-and-drop, richer keyboard layouts and IME, runtime density changes, accessibility, notifications, audio, GPU buffers, and Android document/permission brokers.

Bidirectional text clipboard transfer is implemented. An unmodified Qt 6.11 probe decoded `ARCHPHENE_ANDROID_TO_WAYLAND` from an Android-backed `wl_data_offer`, then sent the known 25-byte text `ARCHPHENE_CLIPBOARD_PROBE` through `wl_data_source.send` for Android `ClipboardManager` to receive. Stock KCalc also requested a three-byte expression through Ctrl+V. `scripts/test-kcalc-clipboard.sh` verifies both directions and asserts that bridge-originated clipboard writes do not create an offer feedback loop. Rich MIME types, URI/document offers, primary selection, and drag-and-drop remain pending.

The current prototype also ships a curated app-local Qt/KF6/glibc closure. Shared dependency delivery and transactional package updates remain bridge-manager milestones.