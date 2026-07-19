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
| `kcalc` | Qt 6/KDE | Validated | Manager resolve/verify/wrap/sign/install/update/uninstall, app-drawer launch, input, menus, scaling, secondary windows, accessibility, and process cleanup on x86_64; generated wrapper launch and same-process bidirectional Android light/dark palette switching on physical AArch64. |
| `mousepad` | GTK 3 | Validated | Menus, dialogs, IME, Android document import/edit/save/writeback/cold reopen, conflict preservation, and DocumentsProvider access on x86_64 and physical AArch64. |
| `glmark2` | Mesa/Wayland/OpenGL ES | Validated | `glmark2-es2-wayland` completes all scenes through virgl on the x86_64 emulator and Samsung AArch64 device. Final presentation still uses SHM. |
| `snapshot` | GTK 4/GStreamer/PipeWire | Validated | Unmodified camera consumer, Android grant/denial, timestamped frames, and cleanup on x86_64 and physical AArch64. |
| `btop` | Terminal/CLI | Validated | Managed install and execution in Archphene Terminal on physical AArch64; no launcher Activity is created. |
| `tree` | Terminal/CLI | Validated | Managed x86_64 install and fresh-session execution; no launcher Activity is created. |
| `wev` | Native Wayland diagnostic | Partial | Official x86_64 package and dependency closure resolve through libalpm and the target archive signature verifies under the manager UID. Input-protocol execution remains planned. |
| `secret-tool` | libsecret/D-Bus | Validated | Secret Service store/read/clear and persistence on x86_64 and physical AArch64. |
| `kwallet-query` | KDE/D-Bus | Partial | Validated on 4 KB x86_64 and through the patched compatibility daemon on physical AArch64; official x86_64 closure is blocked on 16 KB Android. |

## Release-gate representatives

These packages cover distinct bridge contracts. They are deliberately smaller than the expanded research backlog.

| Lane | Package | Required result | Current status |
|---|---|---|---|
| Raw Wayland input | `wev` | Pointer, touch, keyboard, modifiers, repeat, focus, and rotation remain correct. | Partial |
| Wayland clipboard | `wl-clipboard` | Text and MIME ownership transfer in both directions without unsolicited Android clipboard reads. | Planned |
| GTK 4/libadwaita | `gnome-text-editor` | IME, adaptive layout, open/save, popups, accessibility, and lifecycle. | Planned |
| Qt complex UI | `kate` | Tabs, split views, sessions, dialogs, secondary windows, and large text. | Planned |
| Native Wayland terminal | `foot` | PTY, Unicode, hardware keyboard, scrolling, selection, and clipboard. | Planned |
| SDL | `supertux` | Sustained rendering, audio focus, controller/pointer capture, pause/resume, and fullscreen. | Planned |
| Electron | `code` | Multiprocess runtime, PTY, file watching, project tree, IME, GPU, dialogs, and extension host. | Planned |
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
