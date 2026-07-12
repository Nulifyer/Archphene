# KCalc Arch Package Bridge Test

Date: 2026-07-09

Goal: use KCalc as the first real Arch GUI application target for the no-VM Android/Graphene bridge.

## Why KCalc

KCalc is a good first desktop-app target because the app behavior is simple but the runtime is real:

- one launchable GUI binary: `usr/bin/kcalc`
- small package payload: about 728 KB compressed and 3 MB installed on Arch x86_64
- no document/project workflow needed for the first test
- no Android dangerous permissions should be needed by default
- real Qt6/KDE Frameworks stack, so it proves more than a toy GUI

The current Arch package page lists KCalc `26.04.3-1` in `extra/x86_64`, description `Scientific Calculator`, with build date `2026-06-30`, signature date `2026-07-02`, and last update `2026-07-02`.

KDE's app page describes KCalc as a scientific calculator with trigonometric functions, logic/statistics, previous-result stack, configurable precision, copy/paste, configurable display colors/fonts, and key bindings.

## Arch Package Shape

Arch package metadata lists these direct runtime dependencies:

```text
glibc
gmp
kcolorscheme
kconfig
kconfigwidgets
kcoreaddons
kcrash
kguiaddons
ki18n
kiconthemes
knotifications
kwidgetsaddons
kxmlgui
libmpc
libstdc++
mpfr
qt6-base
```

Build-only dependencies listed by Arch:

```text
extra-cmake-modules
kdoctools
```

The soname list includes:

```text
libc.so.6
libgmp.so.10
libmpfr.so.6
libm.so.6
libstdc++.so.6
libKF6ColorScheme.so.6
libKF6ConfigCore.so.6
libKF6ConfigGui.so.6
libKF6ConfigWidgets.so.6
libKF6CoreAddons.so.6
libKF6Crash.so.6
libKF6GuiAddons.so.6
libKF6I18n.so.6
libKF6Notifications.so.6
libKF6WidgetsAddons.so.6
libKF6XmlGui.so.6
libQt6Core.so.6
libQt6Gui.so.6
libQt6Widgets.so.6
libQt6Xml.so.6
libmpc.so.3
libKF6IconThemes.so.6
```

The file list includes:

```text
usr/bin/kcalc
usr/share/applications/org.kde.kcalc.desktop
usr/share/config.kcfg/kcalc.kcfg
usr/share/kconf_update/kcalcrc.upd
usr/share/kglobalaccel/org.kde.kcalc.desktop
usr/share/metainfo/org.kde.kcalc.appdata.xml
usr/share/locale/*/LC_MESSAGES/kcalc.mo
usr/share/doc/HTML/*/kcalc/*
```

## What This Test Should Prove

KCalc should be the first real GUI milestone after the current storage/permission bridge tests.

The test should prove:

1. Arch package ingestion can resolve a real package and its runtime modules.
2. The wrapper can launch a glibc/Qt/KDE app through a Linux runtime module.
3. A minimal Wayland-to-Android Surface bridge can show a real Qt6 Widgets window.
4. Keyboard, mouse, and touch input can operate the calculator buttons and display.
5. App-private settings/config work without user prompts.
6. Clipboard access is mediated as a foreground Android clipboard capability.
7. No broad storage permission is needed.
8. No Android dangerous permissions are requested by default.

## Storage Policy For KCalc

KCalc is a clean test for app-private storage because it should not need user-visible documents.

Expected path policy:

```text
/home/user/.config/kcalcrc
/home/user/.config/KDE/*
/home/user/.cache/*
/home/user/.local/state/*
/tmp
/run/user/<uid>
  -> generated app private data
  -> no Android storage prompt

Open/Save project/document paths
  -> not needed for initial KCalc test

Clipboard
  -> Android foreground clipboard bridge, not filesystem storage
```

The bridge should pre-create the private home directories. The earlier emulator test showed direct `mkdir` from a Linux payload can hit Android's app-spawned syscall filter and exit with `SIGSYS`, so the KCalc launch path should not depend on KCalc creating the base XDG directories itself.

## Runtime Modules

KCalc should be packaged as one launchable LAPK plus shared read-only runtime modules:

```text
LAPK: org.archphene.linux.kcalc
  payload: usr/bin/kcalc and app data files
  Android permissions: none dangerous by default
  capabilities: gui.wayland, input.pointer, input.keyboard, clipboard.foreground, app_private_home

LRPK: org.archphene.runtime.glibc-x86_64
LRPK: org.archphene.runtime.qt6-base-x86_64
LRPK: org.archphene.runtime.kf6-core-x86_64
LRPK: org.archphene.runtime.kf6-widgets-x86_64
LRPK: org.archphene.runtime.kf6-icons-i18n-x86_64
LRPK: org.archphene.runtime.math-libs-x86_64
```

A production resolver can split these differently, but the test should avoid bundling a separate Qt/KDE stack into every app.

## Launch Environment

Initial environment:

```text
HOME=/data/user/0/org.archphene.linux.kcalc/files/linux-home
XDG_CACHE_HOME=$HOME/.cache
XDG_CONFIG_HOME=$HOME/.config
XDG_DATA_HOME=$HOME/.local/share
XDG_STATE_HOME=$HOME/.local/state
XDG_RUNTIME_DIR=/run/user/app
TMPDIR=/data/user/0/org.archphene.linux.kcalc/cache/linux-tmp
QT_QPA_PLATFORM=wayland
WAYLAND_DISPLAY=wayland-0
LANG=C.UTF-8
LC_ALL=C.UTF-8
```

The bridge must provide:

- `XDG_RUNTIME_DIR` equivalent or mounted runtime dir
- app-local Wayland socket
- fontconfig cache/module
- icon theme paths
- Qt plugin paths
- KDE service/config lookup paths
- app-private writable config/cache paths

## Expected Android Permissions

Default KCalc launch should request no dangerous Android permissions.

Capability mapping:

| KCalc behavior | Android bridge behavior |
| --- | --- |
| Draw window | Wayland-to-Android Surface bridge |
| Mouse/touch/keyboard input | Android input events -> Wayland seat |
| Copy/paste display value | foreground clipboard bridge |
| Save settings | app-private home, no prompt |
| Notifications | not expected; block or no-op unless observed |
| Files | not expected; deny or route through portals if observed |
| Network | not expected; disabled by default for first test |

## Test Phases

### Phase 1: Package metadata ingest

Inputs:

```text
package: kcalc
repo: extra
arch: x86_64 first, then aarch64 when available through Arch Linux ARM or custom build
```

Expected output:

```text
kcalc.lapk.json
resolved dependency graph
runtime module list
usr/bin/kcalc entrypoint
no dangerous Android permissions
```

### Phase 2: ELF/runtime preflight

Extract `usr/bin/kcalc` and verify:

- ELF interpreter path
- required sonames
- runtime module coverage
- Qt plugin requirements
- no missing direct libraries
- whether startup hits app-spawned `SIGSYS` before GUI

Expected likely blocker: glibc/Qt startup may hit the same syscall filter class already seen with glibc, `rseq`, `openat2`, and `mkdir` variants.

### Phase 3: Headless launch probe

Launch with:

```text
QT_QPA_PLATFORM=offscreen
```

or a minimal fake Wayland socket if offscreen is not acceptable.

Goal: separate dynamic loader/runtime failures from GUI compositor failures.

### Phase 4: Minimal Wayland GUI

Run KCalc against the first Archphene app-local Wayland bridge.

Pass criteria:

- Android Activity opens
- KCalc window appears
- 1 + 2 = displays 3
- keyboard input works
- touch/mouse button input works
- app exits cleanly

### Phase 5: Android integration

Pass criteria:

- settings write to app-private `$HOME`
- clipboard copy/paste goes through Android foreground clipboard policy
- no storage prompt appears
- no network permission is granted
- no dangerous Android permissions are requested

## Why This Is Better Than VS Code/Zed For The First GUI Test

KCalc is much smaller and has no editor/project/file watcher/language-server/plugin surface. It still proves the hard display/runtime pieces:

- glibc Arch binary launch
- Qt6 Widgets
- KDE Frameworks 6 libraries
- Wayland window
- input bridge
- clipboard bridge
- app-private config/cache

If KCalc cannot run, VS Code, Zed, LibreOffice, GIMP, Kdenlive, and Blender are not ready as no-VM app targets.

If KCalc runs, the next useful GUI targets are:

1. a simple GTK calculator/text editor
2. a terminal emulator
3. a small Qt file-using app
4. then Zed or VS Code

## Current Blockers

The current prototype does not yet have the pieces needed to run KCalc end to end:

- no Wayland-to-Android Surface bridge yet
- no Qt6/KDE runtime module yet
- no glibc syscall compatibility layer yet
- no path broker for normal Linux paths yet
- no package resolver that converts Arch packages into LAPK/LRPK modules yet

But KCalc is now the right target for the next GUI milestone because it is simple enough to debug and real enough to be meaningful.

## Source Map

- Arch package page: https://archlinux.org/packages/extra/x86_64/kcalc/
- Arch package file list: https://archlinux.org/packages/extra/x86_64/kcalc/files/
- Arch package soname list: https://archlinux.org/packages/extra/x86_64/kcalc/sonames/
- KDE KCalc application page: https://apps.kde.org/kcalc/
