# Mousepad Arch Package Bridge Test

Date: 2026-07-09

Goal: use Mousepad as the first lightweight GTK text-editor target for the no-VM Android/Graphene bridge.

## Why Mousepad

Mousepad is a strong companion target to KCalc:

- one launchable GUI binary: `usr/bin/mousepad`
- small package payload: about 450.5 KB compressed and 2.2 MB installed on Arch x86_64
- real GTK3/GtkSourceView editor UI
- naturally exercises file open/save behavior
- should not need network, camera, mic, contacts, or broad storage permissions
- simpler than GIMP, LibreOffice, VS Code, or Zed

The current Arch package page lists Mousepad `0.7.0-1` in `extra/x86_64`, description `Simple text editor for Xfce`, with build date `2026-03-02`, signature date `2026-03-02`, and last update `2026-03-04`.

The Xfce documentation says Mousepad is a simple text editor for Xfce and aims to be an easy-to-use fast editor for quickly editing text files, not a large development environment.

## Arch Package Shape

Arch package metadata lists these runtime dependencies:

```text
desktop-file-utils
gtksourceview4
hicolor-icon-theme
```

Optional runtime dependencies listed by Arch:

```text
gspell - spell checking plugin
libxfce4ui - shortcuts editor plugin
```

Build-only dependencies listed by Arch:

```text
git
glib2-devel
gspell
libxfce4ui
meson
polkit
xfce4-dev-tools
```

The soname list includes:

```text
libc.so.6
libgdk-3.so.0
libgio-2.0.so.0
libglib-2.0.so.0
libgobject-2.0.so.0
libgtk-3.so.0
libgtksourceview-4.so.0
libm.so.6
libpango-1.0.so.0
libgmodule-2.0.so.0
libmousepad.so.0
libxfce4kbd-private-3.so.0
libgspell-1.so.3
```

The file list includes:

```text
usr/bin/mousepad
usr/lib/libmousepad.so
usr/lib/mousepad/plugins/libmousepad-plugin-gspell.so
usr/lib/mousepad/plugins/libmousepad-plugin-shortcuts.so
usr/share/applications/org.xfce.mousepad.desktop
usr/share/applications/org.xfce.mousepad-settings.desktop
usr/share/glib-2.0/schemas/org.xfce.mousepad.gschema.xml
usr/share/glib-2.0/schemas/org.xfce.mousepad.plugins.gspell.gschema.xml
usr/share/icons/hicolor/*/apps/org.xfce.mousepad.*
usr/share/metainfo/org.xfce.mousepad.appdata.xml
usr/share/polkit-1/actions/org.xfce.mousepad.policy
usr/share/locale/*/LC_MESSAGES/mousepad.mo
```

## What This Test Should Prove

Mousepad should test the first real editor workflow:

1. Launch a real Arch GTK3 GUI app.
2. Render a GTK window through the Wayland-to-Android Surface bridge.
3. Type text through Android keyboard/input translation.
4. Save a user-visible text file through Android `ACTION_CREATE_DOCUMENT` or a persisted tree grant.
5. Open a user-selected text file through Android `ACTION_OPEN_DOCUMENT`.
6. Store preferences in app-private config/cache without storage prompts.
7. Use clipboard only through foreground clipboard mediation.
8. Request no Android dangerous permissions by default.

## Storage Policy For Mousepad

Mousepad is the first target where user-visible storage matters.

Expected path policy:

```text
/home/user/.config/Mousepad/*
/home/user/.config/dconf/*
/home/user/.cache/*
/home/user/.local/share/*
/tmp
/run/user/<uid>
  -> generated app private data
  -> no Android storage prompt

Open File
  -> ACTION_OPEN_DOCUMENT
  -> Android content URI read

Save As / New document save
  -> ACTION_CREATE_DOCUMENT
  -> Android content URI write

Edit file inside project folder
  -> ACTION_OPEN_DOCUMENT_TREE grant
  -> persisted tree read/write
```

The Xfce docs note that Mousepad settings can be exposed through GSettings/dconf, and printing settings use `~/.config/Mousepad/mousepadrc`. Keybindings can be generated under `~/.config/Mousepad/accels.scm`. Those are app-private config files for the bridge.

The bridge should pre-create private home directories. The earlier emulator test showed direct `mkdir` from a Linux payload can hit Android's app-spawned syscall filter and exit with `SIGSYS`, so Mousepad should not be trusted to create all base XDG directories itself on first launch.

## Runtime Modules

Mousepad should be packaged as one launchable LAPK plus shared read-only runtime modules:

```text
LAPK: org.archphene.linux.mousepad
  payload: usr/bin/mousepad, libmousepad, plugins, app data files
  Android permissions: none dangerous by default
  capabilities:
    gui.wayland
    input.pointer
    input.keyboard
    clipboard.foreground
    storage.app_private_home
    storage.open_document
    storage.create_document
    storage.open_tree.optional

LRPK: org.archphene.runtime.glibc-x86_64
LRPK: org.archphene.runtime.gtk3-x86_64
LRPK: org.archphene.runtime.gtksourceview4-x86_64
LRPK: org.archphene.runtime.glib-gio-x86_64
LRPK: org.archphene.runtime.fontconfig-pango-cairo-x86_64
LRPK: org.archphene.runtime.icons-hicolor-x86_64
LRPK: org.archphene.runtime.xfce-mousepad-optional-x86_64
```

The optional Xfce/gspell pieces can initially be disabled if they complicate launch. The first pass should prioritize core text editing and file portal behavior.

## Launch Environment

Initial environment:

```text
HOME=/data/user/0/org.archphene.linux.mousepad/files/linux-home
XDG_CACHE_HOME=$HOME/.cache
XDG_CONFIG_HOME=$HOME/.config
XDG_DATA_HOME=$HOME/.local/share
XDG_STATE_HOME=$HOME/.local/state
XDG_RUNTIME_DIR=/run/user/app
TMPDIR=/data/user/0/org.archphene.linux.mousepad/cache/linux-tmp
GDK_BACKEND=wayland
WAYLAND_DISPLAY=wayland-0
LANG=C.UTF-8
LC_ALL=C.UTF-8
```

The bridge must provide:

- app-local Wayland socket
- GTK/GDK Wayland support
- fontconfig/Pango/Cairo stack
- GLib/GIO and GSettings schema lookup
- app-private writable config/cache paths
- file portal adapters for GTK file chooser operations

## Expected Android Permissions

Default Mousepad launch should request no dangerous Android permissions.

Capability mapping:

| Mousepad behavior | Android bridge behavior |
| --- | --- |
| Draw editor window | Wayland-to-Android Surface bridge |
| Mouse/touch/keyboard input | Android input events -> Wayland seat |
| Copy/paste text | foreground clipboard bridge |
| Save preferences | app-private home, no prompt |
| Open file | `ACTION_OPEN_DOCUMENT` or path broker to granted tree |
| Save new file | `ACTION_CREATE_DOCUMENT` or path broker to granted tree |
| Background write inside project folder | persisted `ACTION_OPEN_DOCUMENT_TREE` grant |
| Network | not expected; disabled by default |
| Notifications | not expected; block or no-op unless observed |
| Polkit/admin edit | not supported in first milestone |

## Test Phases

### Phase 1: Package metadata ingest

Inputs:

```text
package: mousepad
repo: extra
arch: x86_64 first, then aarch64 when available through Arch Linux ARM or custom build
```

Expected output:

```text
mousepad.lapk.json
resolved dependency graph
runtime module list
usr/bin/mousepad entrypoint
no dangerous Android permissions
```

### Phase 2: ELF/runtime preflight

Extract `usr/bin/mousepad` and verify:

- ELF interpreter path
- required sonames
- GTK/GDK/GIO/GSettings dependencies
- plugin loading behavior
- whether startup hits app-spawned `SIGSYS` before GUI

### Phase 3: Headless or fake-display launch probe

Try to separate loader/runtime issues from GUI bridge issues with a controlled display environment.

Useful variants:

```text
GDK_BACKEND=wayland
GDK_BACKEND=x11 disabled for first pass
```

If Mousepad refuses to start without a display, that is acceptable; the failure still tells us the next blocker is the Wayland bridge.

### Phase 4: Minimal Wayland GUI

Run Mousepad against the first Archphene app-local Wayland bridge.

Pass criteria:

- Android Activity opens
- Mousepad window appears
- typing text works
- selection/caret movement works
- app exits cleanly

### Phase 5: Storage portal integration

Pass criteria:

- new text document can be saved through `ACTION_CREATE_DOCUMENT`
- existing text document can be opened through `ACTION_OPEN_DOCUMENT`
- project folder grant allows background write/read without repeat prompts
- app preferences persist in app-private config
- no broad storage permission is granted

## Why Mousepad Matters After KCalc

KCalc is the smallest real GUI proof for Qt/KDE. Mousepad is the smallest useful editor proof for GTK/file workflows.

If KCalc works but Mousepad fails, the likely missing pieces are GTK/GIO/file portal integration rather than general GUI rendering.

If Mousepad works, the next editor targets become much more realistic:

1. a terminal editor wrapper
2. a larger GTK app such as GIMP
3. Zed or VS Code after the path broker and process/tool execution policies mature

## Current Blockers

The current prototype does not yet have the pieces needed to run Mousepad end to end:

- no Wayland-to-Android Surface bridge yet
- no GTK3/GtkSourceView runtime module yet
- no glibc syscall compatibility layer yet
- no path broker for GTK file chooser operations yet
- no package resolver that converts Arch packages into LAPK/LRPK modules yet

Mousepad should become the first editor milestone once KCalc or a minimal GTK window can draw through the Wayland bridge.

## Source Map

- Arch package page: https://archlinux.org/packages/extra/x86_64/mousepad/
- Arch package file list: https://archlinux.org/packages/extra/x86_64/mousepad/files/
- Arch package soname list: https://archlinux.org/packages/extra/x86_64/mousepad/sonames/
- Xfce Mousepad documentation: https://docs.xfce.org/apps/mousepad/start
