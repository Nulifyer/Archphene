# Linux visual quality gate

Linux applications are release-ready only when their toolkit, Wayland surfaces,
Android host window, and input model agree on color, geometry, and density. A
successful launch or a changed screenshot is not a visual-quality pass.

## Ownership model

- The compositor owns output size, logical coordinates, safe bounds, popup
  constraint, parent/child placement, and buffer scaling.
- Qt and GTK own widget rendering, palette state, disabled-state contrast,
  layout, and text measurement. Archphene supplies policy through supported
  toolkit integration points instead of restyling arbitrary widget trees.
- The manager owns user policy: light/dark/system, Material You accent colors,
  geometry scale, text scale, and control density.
- Applications retain their own adaptive layouts. App-specific CSS and fixed
  widget dimensions are not acceptable bridge fixes.

This follows Qt's [high-DPI model](https://doc.qt.io/qt-6/highdpi.html), GTK's
[settings](https://docs.gtk.org/gtk4/class.Settings.html) and
[CSS-provider](https://docs.gtk.org/gtk4/class.CssProvider.html) contracts,
KDE's [GTK configuration bridge](https://github.com/KDE/kde-gtk-config), and
KDE's role-based [KColorScheme](https://github.com/KDE/kcolorscheme). KDE only
generates a complete GTK color theme when it controls the matching theme; it
does not overlay partial foreground and background rules onto an unrelated
theme. Archphene follows the same boundary.

Hyprland and niri both treat scale as output policy, not application CSS.
Hyprland exposes per-monitor scale and niri derives logical output size from
physical size divided by scale. Archphene's target is the same model: advertise
the correct Wayland output scale and logical size, then use toolkit settings for
font and control preferences. `QT_SCALE_FACTOR` remains a compatibility shim
until the compositor's fractional-output path is exposed end to end.

## Density profiles

Control density is independent from text and geometry scale:

| Setting | Visible size | Corresponding minimum target | Intended input |
|---|---:|---:|---|
| 18 dp | 18 dp | 32 dp | mouse and keyboard |
| 20 dp | 20 dp | 40 dp | mixed pointer and touch |
| 22 dp | 22 dp | 48 dp | touch-first |

Auto uses 20 dp visible controls with 48 dp interaction targets on phones,
20/40 dp on tablets, and 18/32 dp on desktop-sized or external displays.

An explicit setting overrides automatic selection on the next app start. Live
Android configuration changes rewrite GTK metrics and Qt's shared appearance
configuration; existing layouts must re-query those metrics during relayout.

## Required evidence

Every visual run produces an inspectable directory containing PNG and raw
screencap frames, scoped logcat, generated toolkit configuration, device/display
properties, package versions, and a manifest naming the exact app state. Exact
pixel goldens are advisory because fonts and GPU drivers vary. Release assertions
must instead prove:

1. foreground/background contrast is at least 4.5:1 for normal text and 3:1 for
   large text, controls, focus indicators, and meaningful icons;
2. enabled controls are visible, disabled controls remain distinguishable, and
   focus/selection/hover states do not erase labels;
3. menu rows, title controls, buttons, fields, scrollbars, and status areas meet
   the selected density target without overlapping text;
4. primary, popup, tooltip, and secondary-window geometry remains within the
   safe Linux viewport, with an accessible scrolling path when content cannot
   fit;
5. light/dark and Material You changes alter Linux-app pixels without changing
   the Android or Linux process;
6. rotation, phone/tablet/docked transitions, IME visibility, background/resume,
   and dialog open/close preserve state and input focus.

`uiautomator` can time out waiting for an idle frame while the compositor is
continuously producing buffers. It must not be used as the sole proof of Linux
semantics or bounds. The test AccessibilityService/AT-SPI lane supplies semantic
roles, names, enabled state, and bounds; screenshots supply rendered contrast
and clipping evidence. A visual claim fails closed when either side is missing.

## Regression applications

The always-run sequence is deliberately layered:

| Application | Pipeline | Mandatory states |
|---|---|---|
| KCalc | Qt 6 Widgets/KDE, Wayland | main, every menu edge, disabled menu rows, mode/status area, settings dialog |
| Mousepad | GTK 3/CSD, Wayland | editor, long menu, Preferences tabs and fields, disabled rows, close target |
| GNOME Text Editor | GTK 4/libadwaita | adaptive header, document menu, preferences/about, IME-visible layout |
| Foot | direct Wayland/shm | shell text, Unicode, selection, scrollback, resize, clipboard, focus |
| GLMark2 ES2 Wayland | EGL/GLES/virgl | sustained frames, resize, pause/resume, helper-loss fallback |
| Vulkan tools | Vulkan loader/WSI frontier | enumeration now; `vkcube-wayland` presentation before claiming Vulkan GUI support |
| Kate | Qt/KDE complex desktop | menus, tabs, split views, project tree, dialogs, document lifecycle |
| Code | Electron/Ozone Wayland | multiprocess startup, editor/tree/terminal, IME, GPU process, dialogs, lifecycle |

KCalc and Mousepad block the shared Qt/GTK bridge. Foot distinguishes compositor
and input regressions from toolkit regressions. GPU probes distinguish software
widget rendering from accelerated presentation. Kate and Code are daily-use
acceptance applications, not substitutes for the smaller diagnostic cases.

## Display matrix

Run light and dark on phone portrait/landscape, tablet portrait/landscape, and a
real or emulator secondary 1920x1080 display. Exercise automatic plus the three
explicit density profiles. At minimum, review touch on phone, comfortable on
tablet, and compact on the external display. Repeat the core KCalc, Mousepad,
and Foot cases on current-source x86_64 and AArch64 builds. Vulkan presentation,
16 KB x86_64, physical x86_64, and supported GrapheneOS remain separate named
release lanes and must not be inferred from the normal emulator.

## Current audit result (July 22, 2026)

The current x86_64 emulator build passes the representative fail-closed gates:

- KCalc and Mousepad pass live light/dark, all automatic phone/tablet/docked
  profiles, all explicit phone density profiles, semantic accessibility trees,
  popup/content geometry, calculation, Preferences, IME, touch, and document
  workflows.
- Foot passes the same density matrix plus a focused 42 px terminal-font,
  126 px touch-CSD, PTY output, contrast, bounded-frame, and stable-process gate.
  Its running process follows Android light/dark through an exact-target
  supervisor signal without restarting Foot or Bash.
- GLMark2 produces distinct, nonblank moving virpipe frames. Vulkan remains a
  named frontier: llvmpipe enumeration is proven, Android-backed presentation is
  not.
- Kate survives tablet portrait/landscape and renders complete, readable editor
  UI on a real temporary 1920x1080 emulator display with targeted text input.

Current-source physical AArch64 repetition remains blocked by development-signing
continuity on the attached Samsung. Its installed control fixtures remain useful,
but are not evidence for this checkout's wrapper bytes.
