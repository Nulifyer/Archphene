# Linux application appearance

Archphene maps Android appearance policy into each Linux app at launch. The policy is authenticated through the manager runtime provider, so arbitrary Android apps cannot change another wrapper's theme.

## User controls

Settings exposes separate controls for:

- Android manager theme: system, dark, or light
- Linux app theme: follow Android, dark, or light
- Linux app geometry scale: automatic or an explicit percentage
- Linux app text scale
- Material You semantic colors on Android 12 and newer

Changes apply on the next Linux app launch. Automatic geometry scale is 150% on phones, 125% on tablets, and 100% on desktop-sized displays. Phone text choices are constrained to 100%, 110%, and 120% so standard menus remain usable in a narrow viewport. Larger tablet and desktop choices remain available.

## Toolkit integration

Qt 6 apps load the `archphene` QPA platform-theme and widget-style plugins. They supply the application palette, color-scheme hint, proportional and fixed fonts, mobile-sized text editors, style choice, and icon-theme hints. The bridge writes role-based Window, View, Button, Selection, and Tooltip colors rather than recoloring individual applications. The platform-theme plugin uses Qt private QPA interfaces and must be rebuilt against the exact Qt minor version in the runtime closure.

GTK 3 apps receive equivalent dark/light selection and runtime data paths through generated GTK settings.ini and gtk.css files. The bridge keeps Wayland buffers at the native Android viewport size while scaling toolkit fonts, touch targets, and scrollbars from the same geometry and text policy used for Qt. A native GTK settings broker remains future work; current GTK support uses the toolkit's Adwaita themes.

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

The checked-in x86_64 plugin is reproducible in the pinned Linux container:

```powershell
./scripts/build-qt-platform-theme-podman.ps1 -RebuildImage
```

The script rejects a Qt private-ABI mismatch and regenerates the prebuilt manifest and checksums. Runtime visual validation must cover light and dark palettes, menus, secondary windows, and portrait/landscape layouts.