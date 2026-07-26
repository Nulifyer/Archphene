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
| `kcalc` | Qt 6/KDE | Partial | Current-source x86_64 package, calculation, input, menu, rotation, cleanup, accessibility, live-theme, content-geometry, and phone/tablet/docked density gates pass. The current-source Samsung wrapper repeats calculation, menu/contrast, rotation, descriptor lifecycle, live theme, and manager light/dark/Material You policy. Broader KDE and physical external-display coverage remain. |
| `mousepad` | GTK 3 | Partial | Current-source x86_64 document, IME, touch, popup, secondary-window, accessibility, live-theme, content-geometry, and phone/tablet/docked density gates pass. The current-source Samsung wrapper repeats accessibility, IME/touch, Preferences checkbox/close interaction, live theme, Material You checked-state pixels, host cleanup, and document restart/conflict/writeback. Adwaita owns complete base widget states; broader GTK and physical external-display coverage remain. |
| `gnome-text-editor` | GTK 4/libadwaita | Validated | Current-source x86_64 and manager-owned AArch64 wrappers pass SAF import/edit/writeback/cold reopen, DocumentsUI provider discovery, stable-process live light/dark and cold dark launch, state-preserving Android accessibility for tabs/menus/Preferences, Android paste, non-Latin/emoji composing and committed InputConnection text, Linux selection/copy, exact Android clipboard readback, and graceful close with prior session/clipboard restoration. |
| `kate` | Qt 6/KDE | Partial | The current-source complete x86_64 closure installs and maps the full Kate UI after auxiliary shebang materialization and daemon-descendant supervision were fixed. Android and Linux processes remain stable through 1600x2560 tablet portrait/landscape changes; live system light/dark, manager override, and Material You pixel/config checks pass. A temporary 1920x1080 emulator display renders Kate at that display's bounds and accepts display-targeted pointer/keyboard input. A pre-existing, differently signed physical AArch64 wrapper also maps and accepts Android IME text. Tabs, split views, large documents, dialogs, sessions, secondary Linux windows, save/reopen, destructive lifecycle, and current-source AArch64 parity remain. |
| `foot` | Native Wayland terminal | Validated | Current-source x86_64 emulator and AArch64 Samsung wrappers pass clean Bash PTY launch, readable density-aware light/dark visuals, UTF-8 preedit/commit, bidirectional clipboard, exact mouse selection, visible scrollback, live resize with stable processes, graceful close, force-stop, and cold relaunch. Generic `wev` covers physical hardware modifiers/repeat. Unpublished host commands such as `clear` fail closed; optional dependency-command publication is not part of this Foot claim. |
| `supertux` | SDL/Wayland | Partial | Current manager-generated x86_64 and AArch64 wrappers retain the bounded verified runtime-loaded SDL3/graphics closure and pass accelerated animated rendering, PulseAudio playback, keyboard and finger activation, HOME/resume, live tablet resize, sustained GPU quality, and warmed lifecycle/FD gates. The shared bridge applies automatic landscape-sensor presentation to default-display SDL2/SDL3 apps, matching upstream SDL phone behavior; full-device captures prove software-cursor hotspots and rendered controls align with real taps on emulator and Samsung. Real level/gameplay controls, pointer capture, audio interruption/focus, and fullscreen/window-mode transitions remain. |
| `glmark2` | Mesa/Wayland/OpenGL ES | Validated | `glmark2-es2-wayland` completes all scenes through virgl on the x86_64 emulator and Samsung AArch64 device. Final presentation still uses SHM. |
| `snapshot` | GTK 4/GStreamer/PipeWire | Validated | Unmodified camera consumer, Android grant/denial, timestamped frames, and cleanup on x86_64 and physical AArch64. |
| `btop` | Terminal/CLI | Validated | Managed install and execution in Archphene Terminal on physical AArch64; no launcher Activity is created. |
| `dotnet-sdk` | Terminal/dev toolchain | Partial | The current official x86_64 closure installs through the manager on a clean 12 GiB API 36 emulator. `dotnet --info`, MVC creation, clean-HOME NuGet initialization with mode-0600 config, restore, build, Kestrel background lifetime, and the generated site in Android Chrome pass through the shared root. Code-integrated debugging and physical AArch64 package availability remain. |
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
| GTK 4/libadwaita | `gnome-text-editor` | IME, adaptive layout, open/save, popups, accessibility, and lifecycle. | Validated on current-source x86_64 and manager-owned AArch64: SAF edit/writeback/cold reopen, provider browse, live/cold theme, semantic tabs/menus/Preferences, Android paste, complex UTF-8 preedit/commit, Linux copy, exact clipboard readback, graceful close, and state restoration pass. |
| Qt complex UI | `kate` | Tabs, split views, sessions, dialogs, secondary windows, and large text. | Partial: current x86_64 maps, survives tablet rotation, and accepts input on a real temporary 1920x1080 emulator display; an older physical AArch64 build maps and accepts IME input. Full editor, window, document, and lifecycle workflows remain. |
| Native Wayland terminal | `foot` | PTY, readable density-aware UI, Unicode, hardware keyboard, scrolling, selection, and clipboard. | Validated on current-source x86_64 emulator and physical AArch64: focused Foot workflows plus generic `wev`/`wl-clipboard` protocol gates pass. Optional dependency-command publication remains a generic product-policy question. |
| SDL | `supertux` | Sustained rendering, audio focus, controller/pointer capture, pause/resume, and fullscreen. | Partial: an older physical AArch64 build renders the title screen/modal. Current x86_64 installs but fails closed because the verified SDL3 dependency is loaded through `dlopen` and omitted from the reduced pack; generic dynamic-library retention and interaction/lifecycle cases remain. |
| Electron | `code` | Multiprocess runtime, PTY, file watching, project tree, IME, GPU, dialogs, and extension host. | Partial on physical AArch64: current `visual-studio-code-bin` installs through the AUR workflow and its stock launcher reaches a stable Ozone/Wayland workbench. Generic `/proc`, raw `statx`, private temp/shared memory, absolute `dlopen`, `/proc/self/exe`, descendant-environment, logical-directory watch, and managed `forkpty` bridges keep shared/watcher/extension-host services and a job-control-capable integrated Bash alive. Phone/IME resize is crisp, visible touch targets align, and force-stop reaps both Code and its shell. The current device gate still uses reviewed test flags `--no-sandbox --disable-dev-shm-usage --disable-gpu`; generic sandbox policy, accelerated rendering, network/editor/extensions, and complete x86_64 gates remain. |
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
