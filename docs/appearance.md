# Linux application appearance

Archphene maps Android appearance policy into each Linux app at launch. The policy is authenticated through the manager runtime provider, so arbitrary Android apps cannot change another wrapper's theme.

## User controls

Settings exposes separate controls for:

- Android manager theme: system, dark, or light
- Linux app theme: follow Android, dark, or light
- Linux app geometry scale: automatic or an explicit percentage
- Linux app text scale: automatic or 100–200% of Android's font baseline
- Linux app visible-control size: automatic, 18 dp, 20 dp, or 22 dp
- Material You semantic colors on Android 12 and newer

Manager preference changes apply on the next Linux app launch. A running Qt 6,
GTK 3, GTK 4/libadwaita, or adapted Foot app set to follow Android changes between the system
light and dark palettes without restarting its Linux process. An explicit Linux
light or dark choice overrides the opposite Android mode. Material You keeps the
same resolved light/dark policy while substituting Android semantic colors.
Automatic geometry scale is 150% on phones, 125% on tablets, and 100% on
desktop-sized displays. Automatic text uses Android's current font scale at the
16 sp baseline; explicit choices multiply it from 100% through 200%.
Automatic visible controls are 20 dp on phones and tablets and 18 dp on
desktop-sized or external displays. Interaction targets remain independent:
48 dp on phones, 40 dp on tablets, and 32 dp on desktop-sized displays. Users
may override visible control size independently from text and geometry scale.

The manager keeps initial focus on the page rather than its search field, so a previously visible IME cannot compress the app list on launch. Search still opens the keyboard on explicit focus. Phone, tablet, and docked-display checks cover 1080x2400, 1280x1920, and 1920x1080 layouts.

## Toolkit integration

Qt 6 apps load the `archphene` QPA platform-theme and widget-style plugins. They supply the application palette, color-scheme hint, proportional and fixed fonts, mobile-sized text editors, style choice, and icon-theme hints. The bridge writes role-based Window, View, Button, Selection, and Tooltip colors rather than recoloring individual applications. The platform theme synchronizes its `QSettings` view of `kdeglobals` on a 500 ms event-loop timer. Before dispatching `ApplicationPaletteChange`, it asks an optional KF6Config helper to reparse the default `kdeglobals` `KSharedConfig`, matching KDE's platform-integration ordering. A deferred first refresh also reapplies the resolved palette after KDE startup code has initialized. This refreshes KDE custom-painted widgets and preserves application state without forcing palettes onto individual widgets. Pure Qt applications still load the platform theme without a KDE Frameworks dependency. The platform-theme plugin uses Qt private QPA interfaces and must be rebuilt against the exact Qt minor version in the runtime closure.

GTK 3 and GTK 4 apps receive equivalent dark/light selection and runtime data paths through generated `settings.ini` and `gtk.css` files. A shared native GTK settings bridge applies `GtkSettings` inside the running process and replaces the generated CSS provider at user priority. GTK 3 loads it as a normal GTK module and invalidates existing widget style contexts. GTK 4 preloads the same bridge, installs its provider for the default display, and updates libadwaita's color-scheme policy. Adwaita owns complete widget foreground/background and state colors; generated CSS supplies fonts, density metrics, popup decoration, and Material You semantic accent names. Archphene must not combine partial application colors with an unrelated GTK theme. The bridge keeps Wayland buffers at the native Android viewport size while scaling toolkit fonts, control targets, and scrollbars from the same geometry, text, and density policy used for Qt.

The focused theme regressions capture raw Android screencaps and compare only
the central Linux application surface, excluding system bars and outer edges.
They also assert generated toolkit configuration, bridge diagnostics, and stable
Android/Linux process IDs. Those checks prove theme propagation, not complete
visual quality. The release gate in [Linux visual quality](linux-visual-quality.md)
adds semantic bounds/state, contrast, clipping, and human-reviewable artifacts.

## Mobile metric calibration

Automatic mode targets Android's 16 sp body text. Visible controls use 18, 20,
or 22 dp while minimum interactive height remains 32, 40, or 48 dp. The bridge
converts Android scaledDensity into the Qt point size after
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

Toolkit-independent Wayland applications do not share a universal theme API.
Archphene therefore uses bounded application adapters where an upstream app has
a stable configuration contract. Foot receives an Archphene-managed include
with pixel-sized terminal text, light/dark and Material You selection colors,
padding, and density-sized client decorations. The normal Foot configuration
file contains the include once and remains available for user overrides below
it. On a live system-theme change, the isolated runtime supervisor sends Foot's
supported dark/light signal only to the exact target PID recorded at fork time;
it never broadcasts the signal to the process group containing the child shell.
Other direct-Wayland applications require their own reviewed adapter or
must rely solely on compositor output scale.

## Rebuild the Qt plugin

Rebuild the checked-in x86_64 and AArch64 plugins in the pinned Linux container:

```bash
./scripts/build-qt-platform-theme-podman.sh --rebuild-image
```

The script rejects a Qt private-ABI mismatch, cross-compiles the ARM plugins against the checksum-pinned official Arch Linux ARM Qt package, verifies both ELF architectures, and regenerates the exact-ABI manifests and shared checksum catalog. Runtime visual validation must cover light and dark palettes, menus, secondary windows, status labels, and portrait/landscape layouts. The current platform-theme and style changes are rebuilt and checksum-verified for x86_64 and AArch64; current-source physical AArch64 visual repetition remains blocked by the connected device's older development signing lineage.

The GTK settings bridge is rebuilt with
`scripts/build-gtk3-settings-podman.sh`. Its checked-in x86_64 and AArch64
binaries contain the GTK 3/GTK 4 implementation. The configured clean build
container currently lacks the GLib development headers, so restoring that
declared container dependency and rerunning the clean build is an open
reproducibility gate rather than a runtime support claim.

KCalc is the Qt metric reference application. A focused July 22 audit found that
the prior checks did not assert menu target size or status-area placement. The
Qt style now derives menu, field, button, scrollbar, and status metrics from the
control-density policy. The rebuilt x86_64 emulator matrices now pass; current-source
physical AArch64 repetition remains open because the attached device has a different
development signing lineage.


The AArch64 plugin was validated on a Samsung SM-S908U at 1080x2316. A manager-generated KCalc wrapper followed Android light and dark modes in both directions without changing its Android or Linux PID, committed exact 1080x2202 portrait and 2316x978 landscape buffers, and rendered the full `NORM` status label in every tested layout. `scripts/test-kcalc-live-theme.sh` measures the rendered app pixels so Android chrome changes alone cannot satisfy the release check.
