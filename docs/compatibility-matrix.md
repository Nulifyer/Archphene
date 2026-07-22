# Package compatibility matrix

Package search results are candidates, not compatibility claims. A package reaches a supported state only after its complete verified closure passes the applicable Android workflows without application-specific source changes.

## Status vocabulary

| Status | Meaning |
|---|---|
| Validated | The documented workflow passed on named hardware and is kept by a reproducible regression. |
| Partial | A useful path passed, but a required subsystem or hardware lane remains. |
| Blocked | Archphene deliberately rejects the package or environment before unsafe/incompatible execution. |
| Planned | The official package currently resolves, but no support claim is made. |

## Current evidence

| Package | Stack | Status | Validated coverage |
|---|---|---|---|
| `kcalc` | Qt 6/KDE | Partial | Current-source x86_64 package, calculation, input, menu, rotation, cleanup, accessibility, live-theme, content-geometry, and phone/tablet/docked density gates pass. Touch-mode menus/status are readable and bounded. Current-source physical AArch64 and broader KDE coverage remain. |
| `mousepad` | GTK 3 | Partial | Current-source x86_64 document, IME, touch, popup, secondary-window, accessibility, live-theme, content-geometry, and phone/tablet/docked density gates pass. Adwaita now owns complete widget states and Preferences is consistently readable. Current-source physical AArch64 and broader GTK coverage remain. |
| `gnome-text-editor` | GTK 4/libadwaita | Partial | The current-source complete x86_64 closure installs and renders on the API 36 emulator. Android IME entry, a native popup, rotation, and warm resume preserve the draft and Linux process; live system light/dark, manager override, and Material You pixel/config checks pass. A pre-existing, differently signed physical AArch64 wrapper also launches and cold-reopens an existing document. Current-source AArch64 parity, open/save/writeback, bidirectional clipboard, complex composition, and accessibility remain. |
| `kate` | Qt 6/KDE | Partial | The current-source complete x86_64 closure installs and maps the full Kate UI after auxiliary shebang materialization and daemon-descendant supervision were fixed. Android and Linux processes remain stable through 1600x2560 tablet portrait/landscape changes; live system light/dark, manager override, and Material You pixel/config checks pass. A temporary 1920x1080 emulator display renders Kate at that display's bounds and accepts display-targeted pointer/keyboard input. A pre-existing, differently signed physical AArch64 wrapper also maps and accepts Android IME text. Tabs, split views, large documents, dialogs, sessions, secondary Linux windows, save/reopen, destructive lifecycle, and current-source AArch64 parity remain. |
| `foot` | Native Wayland terminal | Partial | Current-source x86_64 maps a clean Bash PTY with visible command output, a 42 px phone font, 126 px touch CSD controls, bounded geometry, density-aware phone/tablet/docked defaults, and same-process Android light/dark switching. Full Unicode/compose, selection/scrollback/lifecycle, and current-source AArch64 cases remain. |
| `supertux` | SDL/Wayland | Partial | A pre-existing, differently signed physical AArch64 wrapper maps and renders the SuperTux title screen and its first-run network prompt. Emulator resolution/signature verification passes. Current-source install parity, sustained rendering, audio focus, controls/pointer capture, pause/resume, and fullscreen remain. |
| `glmark2` | Mesa/Wayland/OpenGL ES | Validated | `glmark2-es2-wayland` completes all scenes through virgl on the x86_64 emulator and Samsung AArch64 device. Final presentation still uses SHM. |
| `snapshot` | GTK 4/GStreamer/PipeWire | Validated | Unmodified camera consumer, Android grant/denial, timestamped frames, and cleanup on x86_64 and physical AArch64. |
| `btop` | Terminal/CLI | Validated | Managed install and execution in Archphene Terminal on physical AArch64; no launcher Activity is created. |
| `tree` | Terminal/CLI | Validated | Managed x86_64 install and fresh-session execution; no launcher Activity is created. |
| `wev` | Native Wayland diagnostic | Validated | Official unmodified x86_64 and AArch64 packages validate pointer motion/buttons, horizontal and vertical wheel axes, touch, keyboard/modifiers/repeat, focus loss/restoration, and graceful close on the emulator and physical Samsung. |
| `wl-clipboard` | Native Wayland clipboard | Validated | Official unmodified x86_64 and AArch64 `wl-copy`/`wl-paste` packages transfer exact plain text in both directions. Android clipboard content is read only when a focused Wayland client requests the offer; Linux publication does not trigger an Android read. |
| `secret-tool` | libsecret/D-Bus | Validated | Secret Service store/read/clear and persistence on x86_64 and physical AArch64. |
| `kwallet-query` | KDE/D-Bus | Partial | Validated on 4 KB x86_64 and through the patched compatibility daemon on physical AArch64; official x86_64 closure is blocked on 16 KB Android. |

## Release-gate representatives

These packages cover distinct bridge contracts. They are deliberately smaller than the expanded research backlog.

| Lane | Package | Required result | Current status |
|---|---|---|---|
| Raw Wayland input | `wev` | Pointer, touch, keyboard, modifiers, repeat, focus, and graceful close remain correct. | Validated |
| Wayland clipboard | `wl-clipboard` | Plain-text ownership transfer in both directions without unsolicited Android clipboard reads. | Validated |
| GTK 4/libadwaita | `gnome-text-editor` | IME, adaptive layout, open/save, popups, accessibility, and lifecycle. | Partial: current x86_64 install/render, basic IME, popup, resize, and warm lifecycle pass; an older physical AArch64 build launches and cold-reopens a document. Full document, clipboard, composition, accessibility, and current-source AArch64 cases remain. |
| Qt complex UI | `kate` | Tabs, split views, sessions, dialogs, secondary windows, and large text. | Partial: current x86_64 maps, survives tablet rotation, and accepts input on a real temporary 1920x1080 emulator display; an older physical AArch64 build maps and accepts IME input. Full editor, window, document, and lifecycle workflows remain. |
| Native Wayland terminal | `foot` | PTY, readable density-aware UI, Unicode, hardware keyboard, scrolling, selection, and clipboard. | Partial: current x86_64 PTY, visual density, live theme, generic input, and clipboard protocol gates pass. Foot-specific Unicode/selection/scrollback/lifecycle and current-source AArch64 coverage remain. |
| SDL | `supertux` | Sustained rendering, audio focus, controller/pointer capture, pause/resume, and fullscreen. | Partial: an older physical AArch64 build renders the title screen and first-run modal; current-source parity and package-specific interaction/lifecycle cases remain. |
| Electron | `code` | Multiprocess runtime, PTY, file watching, project tree, IME, GPU, dialogs, and extension host. | Blocked: the real 36-package closure resolves, verifies, extracts, and classifies, then fails closed before wrapper creation because the generic pack model does not yet publish package-owned `/usr/lib/code` data or dependency executables such as `electron42`. |
| Rust-native | `zed` | GPU UI, project tree, language servers, PTY, dialogs, clipboard, and multiwindow behavior. | Planned |
| X11 compatibility | `xorg-xwayland` + `xterm` | Rootless XWayland startup, input, selection, clipboard, resize, and teardown. | Planned |
| Vulkan | `vulkan-tools` | Device enumeration and `vkcube-wayland` presentation through an Android-backed path. | Loader/CLI packaging validated; Android-backed ICD and presentation planned |
| Heavy documents | `libreoffice-fresh` | Open/save, locking, autosave, fonts, printing, accessibility, and multiple windows. | Planned |
| Browser | `firefox` | WebRender, tabs, downloads/uploads, media, WebRTC permissions, credentials, notifications, and intents. | Planned |
| Complex GPU UI | `blender` | Continuous viewport rendering, custom UI input, dialogs, popups, and lifecycle. | Planned |

All listed names resolved in official Arch repositories on July 19, 2026. Repository availability is rechecked by CI or before a release; a dated lookup is not a support guarantee.

## Device lanes

1. **x86_64 4 KB emulator:** complete package-manager and bridge regression.
2. **AArch64 physical Android:** ABI, vendor GPU, touch, permissions, documents, and lifecycle.
3. **x86_64 16 KB emulator:** Archphene-owned code today; rebuilt package-universe tests only after every ELF closure is aligned.
4. **Phone/tablet/docked:** portrait, landscape, IME, font scale, freeform windows, and external-display density.
5. **Release hardware:** physical x86_64 Android and a supported GrapheneOS Pixel remain mandatory unvalidated gates.

## Package classification

The manager classifies the resolved closure, not the search query alone:

- a package with a valid graphical `.desktop` entry becomes a generated Android launcher app;
- an executable package without a graphical desktop entry remains Terminal/CLI managed;
- libraries, services, data, and dependencies remain managed closure members and never appear in the app drawer;
- packages for another CPU ABI are hidden or rejected; Archphene does not silently emulate them;
- `any` is accepted only for data-only packages after extraction confirms there is no mismatched native ELF.

One package failure must produce a package-scoped diagnostic and must not cancel unrelated resolve/download jobs. Wrapper signing and Android PackageInstaller confirmation remain serialized; bounded preparation work may run concurrently.

The expanded non-normative candidate list is in `research/references/package-compatibility-candidates.md`.
