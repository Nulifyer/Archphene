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
| `kcalc` | Qt 6/KDE | Partial | Current-source x86_64 package, calculation, input, menu, rotation, cleanup, accessibility, live-theme, content-geometry, and phone/tablet/docked density gates pass. The current-source Samsung wrapper repeats calculation, every primary menu, About/Close, bounded Android semantics, rotation, descriptor lifecycle, live theme, and manager light/dark/Material You policy. Broader KDE and physical external-display coverage remain. |
| `qt5ct` | Qt 5 diagnostic | Partial | Clean x86_64 installation proves the manager automatically includes the signed dependency-owned `qt5-wayland` platform companion; a migrated Samsung root was repaired through the same UI. Stock Qt 5 then connects and renders through current generated launchers on both exact ABIs, publishing repeated bounded 24×24 legacy arrow cursor surfaces through Binder v7; Samsung additionally proves a pointing-hand hotspot, rapid shape changes, semantic tab actions, and stable-process portrait/landscape reflow whose visible OK/Cancel/Apply buttons retain aligned 43 px-high Android bounds. `qt5ct` intentionally expects its own platform-theme setting and is cursor/runtime evidence, not a general Qt 5 appearance claim. |
| `mousepad` | GTK 3 | Partial | Current-source x86_64 document, IME, touch, popup, secondary-window, accessibility, live-theme, content-geometry, and phone/tablet/docked density gates pass. The current-source Samsung wrapper repeats accessibility, IME/touch, Preferences checkbox/close interaction, aligned 80×73 px semantic Close targets, live theme, Material You checked-state pixels, host cleanup, and document restart/conflict/writeback. The same generated launcher passes the XDG folder-portal contract, exact recursive import, collision, cancellation, and full-device visual gates on both ABIs; an installed GTK application with its own folder-selection workflow remains pending. Adwaita owns complete base widget states; broader GTK and physical external-display coverage remain. |
| `gnome-text-editor` | GTK 4/libadwaita | Validated | Current-source x86_64 and manager-owned AArch64 wrappers pass SAF import/edit/writeback/cold reopen, DocumentsUI provider discovery, stable-process live light/dark and cold dark launch, state-preserving Android accessibility for tabs/menus/Preferences, Android paste, non-Latin/emoji composing and committed InputConnection text, Linux selection/copy, exact Android clipboard readback, and graceful close with prior session/clipboard restoration. |
| `kate` | Qt 6/KDE | Partial | The current-source complete x86_64 closure installs and maps the full Kate UI after auxiliary shebang materialization and daemon-descendant supervision were fixed. Android and Linux processes remain stable through 1600x2560 tablet portrait/landscape changes; live system light/dark, manager override, and Material You pixel/config checks pass. A temporary 1920x1080 emulator display renders Kate at that display's bounds and accepts display-targeted pointer/keyboard input. A pre-existing, differently signed physical AArch64 wrapper also maps and accepts Android IME text. Tabs, split views, large documents, dialogs, sessions, secondary Linux windows, save/reopen, destructive lifecycle, and current-source AArch64 parity remain. |
| `foot` | Native Wayland terminal | Validated | Current-source x86_64 emulator and AArch64 Samsung installs pass signed closure review, three-launcher user approval, stale-signer recovery, one-time Android source permission, executable/broker/publication review, and clean app-drawer launch. The bounded installed-ELF dependency review and separately persisted process-map observation both report Native Wayland on real launches; the UI retains launcher coverage and static/observed provenance. The wrappers also pass clean Bash PTY launch, readable density-aware light/dark visuals, UTF-8 preedit/commit, bidirectional clipboard, exact mouse selection, visible scrollback, live resize with stable processes, graceful close, force-stop, and cold relaunch. The dual-ABI launch gate caught and fixed a generic GTK-settings preload that previously assumed GLib symbols existed in a native-Wayland process. Generic `wev` covers physical hardware modifiers/repeat. Unpublished host commands such as `clear` fail closed; optional dependency-command publication is not part of this Foot claim. |
| `supertux` | SDL/Wayland | Partial | Current manager-generated x86_64 and AArch64 wrappers retain the bounded verified runtime-loaded SDL3/graphics/audio closure and pass accelerated animated rendering, real contributed-level movement/jump, PulseAudio playback with serialized sink suspension and Android focus abandon/reacquire, keyboard navigation, generic finger pointer/button delivery, HOME/resume, live tablet resize, and stable process checks. Binder protocol v19 distinguishes absent, explicit-empty, and strong editor evidence, so the generated launcher prevents an implicit SDL gameplay IME while preserving touched empty editors and explicit long-press requests. It also applies automatic landscape-sensor presentation, maps input through the compositor's logical extent, and restores exact prior state. Full-device evidence passes on the emulator and Samsung. Pointer capture and explicit fullscreen/window-mode transitions remain. |
| `glmark2` | Mesa/Wayland/OpenGL ES | Validated | `glmark2-es2-wayland` completes all scenes through virgl on the x86_64 emulator and Samsung AArch64 device. Final presentation still uses SHM. |
| `snapshot` | GTK 4/GStreamer/PipeWire | Validated | Current official unmodified package installs through the manager and receives an exact V9 camera wrapper. First-use Android permission, private XDG Camera/PipeWire startup, live 640×480 Camera2 delivery, GTK4/Cairo presentation, fatal-free cleanup, and full-device zero-magenta pixels pass on x86_64 and physical AArch64; the emulator's virtual scene spans luma 0–253. Earlier isolated coverage retains denial/no-reprompt, JPEG, invalid-dimension, and timestamp diagnostics. |
| `seahorse` | GTK 4/libadwaita/libsecret | Validated | The current official package is discovered despite its desktop file declaring more than Android's retained 16 MIME handlers, publishes an exact V8 `secrets` wrapper, and renders its stock empty-collection UI in full-device light/dark captures. Its unmodified Android task also moves phone→temporary 1920x1080 display→phone at explicit 125% geometry without restarting the manager, wrapper, or Linux process; logical output reconverges from 346x706 to 1024x506 and back. Unmodified `secret-tool` in the same shared Arch root passes private Secret Service create/read/clear and launcher-restart persistence on the x86_64 emulator and physical AArch64 Samsung; the state-preserving gate restores zero test records. |
| `btop` | Terminal/CLI | Partial | Managed install and `btop --version` execution pass in Archphene Terminal on x86_64 and physical AArch64; no launcher Activity is created. The bounded kernel view hides Android-wide PIDs, but stock Android denies `/proc/stat`, so full-screen device monitoring remains unavailable rather than receiving fabricated telemetry. |
| `dotnet-sdk` | Terminal/dev toolchain | Partial | The current official x86_64 closure installs through the manager on a clean 12 GiB API 36 emulator. Physical Samsung AArch64 also reviews, builds, independently verifies, and atomically installs all six required `dotnet-core-bin` AUR outputs. Both lanes pass `dotnet --info`, MVC creation, restore/build/run, Kestrel background lifetime, and the generated site in Android's browser; x86_64 additionally passes clean-HOME NuGet initialization and execution from Code's integrated terminal. C# language-service and debugger/breakpoint validation remain. |
| `tree` | Terminal/CLI | Validated | Managed x86_64 install and fresh-session execution; no launcher Activity is created. |
| `wev` | Native Wayland diagnostic | Validated | Official unmodified x86_64 and AArch64 packages validate pointer motion/buttons, horizontal and vertical wheel axes, touch, keyboard/modifiers/repeat, focus loss/restoration, and graceful close on the emulator and physical Samsung. |
| `wl-clipboard` | Native Wayland clipboard | Validated | Official unmodified x86_64 and AArch64 `wl-copy`/`wl-paste` packages transfer exact plain text in both directions. Binder protocol v16 additionally carries bounded Android `text/html` with a mandatory plain fallback and format-tagged Wayland requests; current Code consumes that exact fallback on both ABIs. A package-installed Linux HTML producer and binary formats remain pending. |
| `secret-tool` | libsecret/D-Bus | Validated | The unmodified Arch binary runs through the current generated Seahorse wrapper's private session and passes Secret Service store/read/clear plus launcher-process restart persistence on x86_64 and physical AArch64. The same gate covers the direct descriptor contract, native D-Bus semantics, bounds, cross-UID denial, plaintext-log absence, and exact cleanup. |
| `kwallet-query` | KDE/D-Bus | Partial | Validated on 4 KB x86_64 and through the patched compatibility daemon on physical AArch64; official x86_64 closure is blocked on 16 KB Android. |

## Release-gate representatives

These packages cover distinct bridge contracts. They are deliberately smaller than the expanded research backlog.

| Lane | Package | Required result | Current status |
|---|---|---|---|
| Raw Wayland input | `wev` | Pointer, touch, keyboard, modifiers, repeat, focus, and graceful close remain correct. | Validated |
| Wayland clipboard | `wl-clipboard`, Code | Plain-text ownership transfer in both directions without unsolicited Android clipboard reads; bounded Android HTML plus exact plain fallback through production Code. | Validated |
| GTK 4/libadwaita | `gnome-text-editor` | IME, adaptive layout, open/save, popups, accessibility, and lifecycle. | Validated on current-source x86_64 and manager-owned AArch64: SAF edit/writeback/cold reopen, provider browse, live/cold theme, semantic tabs/menus/Preferences, Android paste, complex UTF-8 preedit/commit, Linux copy, exact clipboard readback, graceful close, and state restoration pass. |
| Qt complex UI | `kate` | Tabs, split views, sessions, dialogs, secondary windows, and large text. | Partial: current x86_64 maps, survives tablet rotation, and accepts input on a real temporary 1920x1080 emulator display; an older physical AArch64 build maps and accepts IME input. Full editor, window, document, and lifecycle workflows remain. |
| Native Wayland terminal | `foot` | PTY, readable density-aware UI, Unicode, hardware keyboard, scrolling, selection, and clipboard. | Validated on current-source x86_64 emulator and physical AArch64 under the manager-owned shared-root/session architecture: bounded Unicode IME, real wrapper pointer selection, bidirectional authenticated clipboard, scrollback, live resize, close/cleanup, and cold relaunch pass with inspected full-device evidence. Generic `wev`/`wl-clipboard` protocol gates also pass. Optional dependency-command publication remains a generic product-policy question. |
| SDL | `supertux` | Sustained rendering, audio focus, controller/pointer capture, pause/resume, and fullscreen. | Partial on current-source x86_64 and AArch64: real gameplay, generic pointer/button input, keyboard controls without an implicit IME, Pulse playback and focus handoff, Home/resume, live tablet resize, stable processes, and exact state restoration pass. Pointer capture and explicit fullscreen/window-mode transitions remain. |
| Electron | `code` | Multiprocess runtime, PTY, file watching, project tree, IME, GPU, dialogs, and extension host. | Partial on current-source x86_64 and physical AArch64: stock Code reaches a stable Ozone/Wayland workbench with shared/watcher/extension-host services and a job-control-capable integrated Bash. Phone/IME resize is crisp, full-device close/relaunch passes, and x86_64 completes the .NET MVC terminal workflow. Private-root DNS/TLS, extension search/install, native GTK file selection, YAML editing, and Red Hat YAML activation/providers pass on both ABIs through the shared runtime. Physical AArch64 additionally passes live Light→Dark→Light appearance with stable processes, exact profile restoration, and inspected full-device frames; the x86_64 appearance repeat awaits a non-active Code session. The current gate uses `--no-sandbox --disable-dev-shm-usage`; `--disable-gpu` is no longer needed, although Chromium still reports software GL. Generic sandbox policy, acceleration, broader editor/clipboard/lifecycle behavior, and the C# debugger remain. |
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
- `any` is accepted only for data-only packages after the signed-archive review confirms there is no native ELF.

Before Linux package mutation, every present signed archive in the freshly
resolved closure is streamed through the bounded package review. It checks
runtime ELF ABI and actual Android page-size alignment, architecture-any native
content, valid relocatable-library versus loadable ELF type, and command
formats. Blockers identify the exact closure package. A passing result is only
**Bridge eligible**: package-specific runtime and launcher workflows still
determine Validated, Partial, or Blocked status. When the signed closure is not
cached, the manager says **Not analyzed** rather than guessing.
Unchanged results are cached only under a bounded content address covering the
exact resolution, device ABI/page size, immutable verification trust identity,
and all archive/signature bytes; any changed input forces a miss. Package
mutation continues to reverify signatures.

One package failure must produce a package-scoped diagnostic and must not cancel unrelated resolve/download jobs. Wrapper signing and Android PackageInstaller confirmation remain serialized; bounded preparation work may run concurrently.

The expanded non-normative candidate list is in `research/references/package-compatibility-candidates.md`.
