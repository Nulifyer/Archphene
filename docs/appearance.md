# Linux application appearance

Archphene maps Android appearance policy into each Linux app at launch. The policy is authenticated through the manager runtime provider, so arbitrary Android apps cannot change another wrapper's theme.

## User controls

Settings exposes separate controls for:

- Android manager theme: system, dark, or light
- Linux app theme: follow Android, dark, or light
- Linux app geometry scale: automatic or an explicit percentage
- Linux app text scale
- Material You semantic colors on Android 12 and newer

Manager preference changes apply on the next Linux app launch. A running Qt 6 app set to follow Android changes between the system light and dark palettes without restarting its Linux process. Automatic geometry scale is 150% on phones, 125% on tablets, and 100% on desktop-sized displays. Phone text choices are constrained to 100%, 110%, and 120% so standard menus remain usable in a narrow viewport. Larger tablet and desktop choices remain available.

The manager keeps initial focus on the page rather than its search field, so a previously visible IME cannot compress the app list on launch. Search still opens the keyboard on explicit focus. Phone, tablet, and docked-display checks cover 1080x2400, 1280x1920, and 1920x1080 layouts.

## Toolkit integration

Qt 6 apps load the `archphene` QPA platform-theme and widget-style plugins. They supply the application palette, color-scheme hint, proportional and fixed fonts, mobile-sized text editors, style choice, and icon-theme hints. The bridge writes role-based Window, View, Button, Selection, and Tooltip colors rather than recoloring individual applications. The platform theme synchronizes its `QSettings` view of `kdeglobals` on a 500 ms event-loop timer. Before dispatching `ApplicationPaletteChange`, it asks an optional KF6Config helper to reparse the default `kdeglobals` `KSharedConfig`, matching KDE's platform-integration ordering. This refreshes KDE custom-painted widgets and preserves application state without forcing palettes onto individual widgets. Pure Qt applications still load the platform theme without a KDE Frameworks dependency. The platform-theme plugin uses Qt private QPA interfaces and must be rebuilt against the exact Qt minor version in the runtime closure.

GTK 3 apps receive equivalent dark/light selection and runtime data paths through generated `settings.ini` and `gtk.css` files. A shared native GTK module follows KDE's color-reload design: it applies `GtkSettings` inside the running process, replaces the generated CSS provider at user priority, and invalidates existing widget style contexts. The generated palette carries Material You semantic colors when enabled while Adwaita retains GTK widget behavior and metrics. The bridge keeps Wayland buffers at the native Android viewport size while scaling toolkit fonts, touch targets, and scrollbars from the same geometry and text policy used for Qt.

## Mobile metric calibration

Automatic mode targets Android's 16 sp body text and 48 dp minimum interactive
height. The bridge converts Android scaledDensity into the Qt point size after
accounting for QT_SCALE_FACTOR; GTK receives the equivalent physical-pixel font
and control metrics while GDK_SCALE=1 preserves native Wayland buffer geometry.
The user text percentage multiplies the 16 sp baseline, including Android's system
font scale.

This split follows the toolkit contracts instead of patching applications:

- [Qt High DPI](https://doc.qt.io/qt-6/highdpi.html) defines widget geometry in
  device-independent pixels and maps it through a device pixel ratio.
- [Qt QFont](https://doc.qt.io/qt-6/qfont.html) recommends point sizes for
  device-independent text.
- [GTK 3 CSS properties](https://docs.gtk.org/gtk3/css-properties.html) define
  inherited font sizing and icon transforms.
- [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)
  specifies a 48 dp minimum touch target.

GTK Adwaita removes borders from CSD menus because a normal desktop compositor
frames their popup surfaces. Archphene restores an outline and shadow at the
toolkit boundary until the native compositor provides equivalent server-side
popup decoration.

## Rebuild the Qt plugin

The checked-in x86_64 and AArch64 plugins are reproducible in the pinned Linux container:

```bash
./scripts/build-qt-platform-theme-podman.sh --rebuild-image
```

The script rejects a Qt private-ABI mismatch, cross-compiles the ARM plugins against the checksum-pinned official Arch Linux ARM Qt package, verifies both ELF architectures, and regenerates the exact-ABI manifests and shared checksum catalog. Runtime visual validation must cover light and dark palettes, menus, secondary windows, status labels, and portrait/landscape layouts.

KCalc is the Qt metric reference application. The x86_64 emulator and an AArch64 Samsung phone have both passed light/dark and portrait/landscape checks at the phone default of 150% geometry scale. Status indicators such as `NORM` retain font-relative frame padding instead of using KCalc's exact one-line fixed height, which prevents clipping with Android-sized text.


The AArch64 plugin was validated on a Samsung SM-S908U at 1080x2316. A manager-generated KCalc wrapper followed Android light and dark modes in both directions without changing its Android or Linux PID, committed exact 1080x2202 portrait and 2316x978 landscape buffers, and rendered the full `NORM` status label in every tested layout. `scripts/test-kcalc-live-theme.sh` measures the rendered app pixels so Android chrome changes alone cannot satisfy the release check.