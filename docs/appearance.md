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

## Rebuild the Qt plugin

The checked-in x86_64 plugin is reproducible in the pinned Linux container:

```powershell
./scripts/build-qt-platform-theme-podman.ps1 -RebuildImage
```

The script rejects a Qt private-ABI mismatch and regenerates the prebuilt manifest and checksums. Runtime visual validation must cover light and dark palettes, menus, secondary windows, and portrait/landscape layouts.