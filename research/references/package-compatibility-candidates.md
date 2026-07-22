# Expanded package compatibility candidates

Research backlog; package availability and versions must be revalidated before use.

Target: **arbitrary, well-behaved Arch desktop apps feel native on Android**, beyond apps fitting current bridge.

Long-term suite covers native Wayland, X11/XWayland, GTK 3, GTK 4/libadwaita, Qt 6/KDE, SDL, Electron, browsers, OpenGL, Vulkan, multimedia, accessibility, portals, background work, and complex multiwindow apps. Package names were rechecked against official Arch repository metadata on July 19, 2026; this list remains non-normative research.

## First execution wave (July 22, 2026)

The useful order is breadth-first by runtime contract, not package size:

1. Keep `kcalc` and `mousepad` as the known-good Qt 6 and GTK 3 controls.
2. Exercise `gnome-text-editor`, `kate`, and `foot` as the first complete install/launch wave. They add GTK 4/libadwaita, a substantially larger KDE/Qt closure, and a toolkit-independent native-Wayland terminal without starting with browser-sized closures.
3. Run `gtk4-demos`, `supertux`, and `vulkan-tools` as targeted widget, SDL, and GPU-frontier probes after the foundational failures are understood.
4. Defer Firefox, Code, Krita, Blender, and LibreOffice until the smaller representatives pass; their larger closures make root-cause isolation and emulator storage management worse.

The API 36 x86_64 emulator currently resolves all six first- and second-wave package names through libalpm and verifies their target archive signatures. Complete-closure execution produced these narrower results:

| Candidate | Result | Next test that matters |
|---|---|---|
| `gnome-text-editor` | Partial: current-source x86_64 wrapper install/render, Android IME entry, popup composition, rotation, and warm background/resume pass without a Linux-process restart. A pre-existing physical AArch64 wrapper launches and cold-reopens an existing document. | Android document open/save/writeback, both clipboard directions, compose/non-Latin input, accessibility inspection, cold recreation, and current-source AArch64 parity. |
| `kate` | Partial: current-source x86_64 full closure and wrapper installation pass. Accepting the manager-validated auxiliary shebang command reaches Wayland; the primary then exits `0` before mapping while child processes remain. A pre-existing physical AArch64 wrapper maps Kate and accepts Android IME text in a new document. | Reconcile the x86_64 process handoff and build/lane divergence, then test tabs, split views, large text, dialogs, sessions, secondary windows, and lifecycle. |
| `foot` | Partial: current-source x86_64 native Wayland mapping/rendering passes, but the PTY child shell fails to link `libc.so.6`, with UTF-8 locale and monospace-font warnings. A pre-existing physical AArch64 wrapper provides a live `sh` PTY and executes a typed `echo` command. | Repair x86_64 child execution/runtime data, establish current-source AArch64 parity, then test Unicode, modifiers, repeat, scrolling, selection, clipboard, resize, and lifecycle. |
| `gtk4-demos` | Resolution/signature probe passes. | Use only for GTK widget/rendering cases not already covered by Text Editor. |
| `supertux` | Emulator resolution/signature probe passes. A pre-existing physical AArch64 wrapper maps and renders the title screen plus first-run network prompt, with candidate-PID-scoped log verification. | Establish current-source install parity, then test sustained rendering, audio focus, controls/pointer capture, pause/resume, and fullscreen. |
| `vulkan-tools` | Resolution/signature probe passes. | Keep as a deliberate negative/frontier test until Android-backed ICD enumeration and presentation exist. |

An install/launch smoke is not a compatibility pass. A package advances to **Validated** only after the package-specific workflows below pass on the documented device lanes.

The physical wrappers in this wave were not rebuilt or replaced: the attached phone's manager and generated apps use a different development signing lineage from the current checkout. Those results are useful AArch64 controls, but they do not establish current-source parity. Replacing them requires the original signing key or the user's explicit approval to reset signer-bound app data.

# Recommended Archphene compatibility matrix

## 1. Protocol and bridge diagnostics

Diagnostic tools isolate failed layers; not necessarily showcase apps.

| Pacman package     | Executable/test                   | What it should validate                                           |
| ------------------ | --------------------------------- | ----------------------------------------------------------------- |
| `weston`           | `weston-simple-shm`               | Basic SHM buffers, frame callbacks, damage and resize             |
| `weston`           | `weston-simple-egl`               | EGL surfaces, GPU buffers and frame pacing                        |
| `weston`           | `weston-simple-vulkan`            | Vulkan WSI and GPU presentation                                   |
| `wev`              | `wev`                             | Pointer, keyboard, touch, modifiers, repeat and focus events      |
| `wl-clipboard`     | `wl-copy`, `wl-paste`             | Clipboard MIME types, ownership and Android/Linux synchronization |
| `gtk4-demos`       | `gtk4-demo`                       | Broad GTK 4 widget and rendering coverage                         |
| `libadwaita-demos` | demo app                          | Adaptive layouts, mobile-sized windows and modern GNOME widgets   |
| `mesa-utils`       | `eglgears`, `es2gears`, `eglinfo` | EGL/GLES initialization and renderer identification               |
| `glmark2`          | `glmark2-es2-wayland`             | Sustained GPU rendering and performance                           |
| `vulkan-tools`     | `vkcube-wayland`, `vulkaninfo`    | Vulkan device enumeration and presentation                        |
| `xorg-xeyes`       | `xeyes`                           | Minimal X11/XWayland pointer and shaped-window test               |
| `xterm`            | `xterm`                           | Legacy X11 text, keyboard, selection and clipboard                |
| `xorg-xwayland`    | infrastructure                    | Compatibility target for applications without native Wayland      |

`wev` inspects Wayland input; `wl-clipboard` exposes Wayland copy/paste operations; `mesa-utils` supplies EGL, GLES, and GL diagnostics; `vulkan-tools` supplies Vulkan utilities. ([Arch Linux][2])

```bash
sudo pacman -S \
  weston wev wl-clipboard \
  gtk4-demos libadwaita-demos \
  mesa-utils glmark2 vulkan-tools \
  xorg-xwayland xorg-xeyes xterm
```

## 2. Core everyday UX

**Always-tested regression suite**: common interaction failures, no unusual hardware.

| Package             | Stack            | Primary coverage                                        |
| ------------------- | ---------------- | ------------------------------------------------------- |
| `kcalc`             | Qt 6/KDE         | Basic Qt control case                                   |
| `mousepad`          | GTK 3            | Basic GTK 3 control case                                |
| `gnome-text-editor` | GTK 4/libadwaita | IME, selection, adaptive UI, open/save                  |
| `kate`              | Qt 6/KDE         | Tabs, split views, menus, sessions, large text          |
| `foot`              | Native Wayland   | PTY, keyboard, Unicode, scrolling and clipboard         |
| `gnome-characters`  | GTK 4            | Emoji, Unicode, search and clipboard                    |
| `keepassxc`         | Qt               | Secure clipboard, timers, modal dialogs and file access |
| `gnome-clocks`      | GTK 4/libadwaita | Timers, alarms, notifications and lifecycle             |
| `baobab`            | GTK 4            | Large dynamic tree/model views and background scanning  |
| `gnome-calculator`  | GTK 4            | Compact adaptive application baseline                   |

`foot` separates native Wayland/PTTY behavior from GTK or Qt issues. GTK 4 also tests EGL/Vulkan-capable rendering, portals, accessibility, and adaptive layouts. ([Arch Linux][3])

```bash
sudo pacman -S \
  kcalc mousepad gnome-text-editor kate foot \
  gnome-characters keepassxc gnome-clocks baobab gnome-calculator
```

### Required tests

For every text-capable app:

* Android soft keyboard show/hide
* Hardware keyboard and modifier combinations
* Dead keys and compose sequences
* Emoji and non-Latin text
* Selection handles and long-press
* Cut, copy and paste in both directions
* Undo/redo
* Keyboard repeat
* Ctrl, Alt, Super and function keys
* App background/resume without losing unsaved state

## 3. Files, documents and Android Storage Access Framework

Test direct paths, recent-document APIs, KIO/GIO, temporary files, locking, autosave, and multi-file projects; not only file managers.

| Package             | Why it matters                                                 |
| ------------------- | -------------------------------------------------------------- |
| `thunar`            | GTK 3/GIO file operations and context menus                    |
| `nautilus`          | GTK 4, thumbnails, search, drag-and-drop and modern GIO        |
| `dolphin`           | Qt/KDE KIO, tabs, split views, previews and remote URLs        |
| `file-roller`       | GTK archive extraction and destination selection               |
| `ark`               | KDE archive handling and drag/drop                             |
| `evince`            | GTK PDF rendering, search, links and printing                  |
| `okular`            | Qt PDF annotations, forms, text selection and multiple formats |
| `libreoffice-fresh` | Complex documents, autosave, file locking and many dialogs     |
| `imv`               | Lightweight image loading and scaling                          |
| `loupe`             | Modern GTK 4 image viewer and gestures                         |
| `simple-scan`       | Document acquisition, multipage workflows and PDF output       |

Thunar, Dolphin, and Nautilus cover three file stacks. Okular covers Qt documents and annotations; LibreOffice stresses locking, autosave, print dialogs, fonts, and secondary windows. ([Arch Linux][4])

```bash
sudo pacman -S \
  thunar nautilus dolphin \
  file-roller ark \
  evince okular libreoffice-fresh \
  imv loupe simple-scan
```

### Required workflows

Each relevant app should support:

1. Android **Open with Archphene app**
2. Android share-to-app
3. Open through the Android document picker
4. Save As to a selected document-tree location
5. Reopen through recent documents
6. Atomic replacement of an existing document
7. Multiple concurrently open documents
8. Files with spaces, Unicode and very long names
9. Revoked Android URI permission
10. Source document deleted or moved while the app is suspended

## 4. Multiwindow, dialogs, popups and complex layouts

Bridge stress tests beyond single rectangular mobile apps.

| Package             | Stress points                                                      |
| ------------------- | ------------------------------------------------------------------ |
| `gimp`              | Floating docks, transient windows, tooltips and canvas interaction |
| `inkscape`          | Complex menus, palettes, SVG canvas, zoom and text tools           |
| `krita`             | Docking, stylus input, pressure, large canvases and OpenGL         |
| `libreoffice-fresh` | Multiple document windows, modal dialogs and print preview         |
| `blender`           | Custom UI, multiple editor regions, popups and GPU viewport        |
| `freecad`           | Qt/OpenGL viewport, property grids and many workbenches            |
| `kdenlive`          | Docking, timeline, media preview and background rendering          |
| `obs-studio`        | Multiple sources, previews, capture portals and settings dialogs   |

Krita combines Qt 6, Wayland, formats, plugins, tablet input, and GPU canvas. Blender combines custom UI, continuous GPU rendering, keyboard-heavy input, and optional native Wayland decorations. ([Arch Linux][5])

```bash
sudo pacman -S \
  gimp inkscape krita libreoffice-fresh \
  blender freecad kdenlive obs-studio
```

These should test:

* Parent/child toplevel relationships
* Modal versus modeless dialogs
* Menus extending outside the app’s original bounds
* Tooltips and hover
* Detached docks and palettes
* Phone compositing versus Android freeform windows
* Rotation while dialogs are open
* Moving windows between Android displays
* Density changes without restarting the Linux process
* State restoration after Android destroys and recreates the Activity

## 5. Audio, video, camera and timing

| Package                | Coverage                                                        |
| ---------------------- | --------------------------------------------------------------- |
| `mpv`                  | Minimal video playback, hardware decode and frame timing        |
| `vlc`                  | Complex playback UI, network streams, subtitles and playlists   |
| `pavucontrol`          | Playback/capture controls and live audio device changes         |
| `helvum`               | PipeWire graph manipulation                                     |
| `audacity`             | Low-latency playback, recording, waveform rendering and seeking |
| `gnome-sound-recorder` | Simple Android microphone permission workflow                   |
| `snapshot`             | Camera permission, preview, still capture and video             |
| `obs-studio`           | Camera, microphone, display capture and encoding                |
| `kdenlive`             | Audio/video preview and export                                  |
| `celluloid`            | GTK frontend around mpv                                         |

Snapshot combines GTK 4, GStreamer, and PipeWire media. Helvum diagnoses brokered Android audio routing through a GTK PipeWire patchbay. ([Arch Linux][6])

```bash
sudo pacman -S \
  mpv vlc pavucontrol helvum audacity \
  gnome-sound-recorder snapshot celluloid \
  obs-studio kdenlive
```

The expected end state should include:

* Audio continuing with the screen off when appropriate
* Android media-session controls
* Correct audio focus behavior
* Bluetooth headset switching
* Microphone permission prompts
* Camera selection through Android
* Hardware video decoding
* Audio/video synchronization
* Pause/resume after phone calls
* Graceful handling when another Android app takes the camera or microphone

## 6. Browsers and web runtimes

Browsers jointly test GPU rendering, text input, clipboard, transfers, audio and video, WebGL, WebRTC, accessibility, and credentials.

| Package            | Coverage                                                           |
| ------------------ | ------------------------------------------------------------------ |
| `firefox`          | Gecko, Wayland, WebRender, portals and browser UX                  |
| `chromium`         | Ozone/Wayland, GPU process, sandbox and WebRTC                     |
| `epiphany`         | GTK 4/WebKitGTK integration                                        |
| `code`             | Electron, multiple processes, PTY and filesystem watching          |
| `transmission-gtk` | Long-running network task, notifications and destination selection |

Chromium combines GTK, D-Bus, PulseAudio, VA-API, Mesa, optional PipeWire sharing, and credentials. Code, Arch Open Source VS Code build, represents Electron apps. ([Arch Linux][7])

```bash
sudo pacman -S \
  firefox chromium epiphany code transmission-gtk
```

### Browser acceptance tests

* WebGL and WebGPU
* Fullscreen video
* Picture-in-picture
* File upload through Android documents
* File download into a user-selected Android folder
* Clipboard read/write permission behavior
* Camera and microphone permissions
* WebRTC call
* Password storage
* Notifications
* URL intents from Android into the browser
* External URL intents from the browser back to Android
* Touch scrolling and pinch zoom
* Selection and context menus
* Multiple tabs surviving process suspension

## 7. Games, SDL and controller input

| Package          | Coverage                                                   |
| ---------------- | ---------------------------------------------------------- |
| `supertuxkart`   | SDL, 3D GPU rendering, fullscreen, audio and controllers   |
| `supertux`       | Simpler SDL/OpenGL game loop                               |
| `dosbox` | Keyboard capture, mouse locking, audio and legacy behavior |

SuperTuxKart combines sustained 3D rendering, audio, fullscreen, and controllers. ([Arch Linux][8])

```bash
sudo pacman -S \
  supertuxkart supertux dosbox
```

The eventual Android mapping should cover:

* Android game controllers
* Mouse pointer capture
* Relative pointer motion
* Fullscreen and immersive mode
* Stable frame pacing
* Screen refresh-rate changes
* Android pause/resume
* Audio focus
* Optional touchscreen controller overlays
* Orientation locking requested by the application wrapper

## 8. Android platform integration

| Package                 | Android-facing feature                                            |
| ----------------------- | ----------------------------------------------------------------- |
| `gnome-clocks`          | Alarms, timers and notifications                                  |
| `system-config-printer` | Android print framework brokering                                 |
| `snapshot`              | Camera permission and media output                                |
| `gnome-sound-recorder`  | Microphone permission                                             |
| `keepassxc`             | Secure storage, biometric-unlock possibility and clipboard expiry |
| `transmission-gtk`      | Foreground service and ongoing notification                       |
| `gnome-maps`            | Android location permission and map rendering                     |
| `geary`                 | Account credentials, notifications, URI handling and attachments  |
| `simple-scan`           | Scanner/document acquisition abstraction                          |
| `gnome-calendar`        | Calendar provider integration as a future stretch goal            |

```bash
sudo pacman -S \
  gnome-clocks system-config-printer snapshot \
  gnome-sound-recorder keepassxc transmission-gtk \
  gnome-maps geary simple-scan gnome-calendar
```

No unrestricted Linux-equivalent host services. Expose explicit Android permission brokers and capabilities for camera, microphone, location, notifications, printing, contacts, and calendar.

## 9. Accessibility

| Package             | Purpose                                     |
| ------------------- | ------------------------------------------- |
| `accerciser`        | Inspect the Linux AT-SPI accessibility tree |
| `orca`              | Screen-reader behavior                      |
| `gtk4-demos`        | Accessible widget coverage                  |
| `libadwaita-demos`  | Modern GNOME accessibility semantics        |
| `firefox`           | Large real-world accessibility tree         |
| `libreoffice-fresh` | Complex document accessibility              |

Accerciser and Orca validate translation from Linux accessibility semantics to Android accessibility node model. ([Arch Linux][9])

```bash
sudo pacman -S \
  accerciser orca gtk4-demos libadwaita-demos \
  firefox libreoffice-fresh
```

Goal: Android TalkBack meaningfully navigates Linux widgets; Orca speech alone is insufficient.

# The smaller “golden” suite

Use these **15 packages as the canonical compatibility gate** instead of full matrix per commit:

| Package             | Why it belongs                        |
| ------------------- | ------------------------------------- |
| `wev`               | Raw input correctness                 |
| `gtk4-demos`        | GTK 4 protocol/widget coverage        |
| `libadwaita-demos`  | Adaptive mobile UX                    |
| `foot`              | Native Wayland keyboard and terminal  |
| `gnome-text-editor` | GTK 4 text, IME and files             |
| `kate`              | Qt 6 text and complex windowing       |
| `nautilus`          | GTK file workflows and drag/drop      |
| `dolphin`           | KDE/KIO file workflows                |
| `libreoffice-fresh` | Documents, dialogs and printing       |
| `mpv`               | Video, audio and hardware decode      |
| `firefox`           | Browser and integrated subsystem test |
| `code`              | Electron and multiprocess runtime     |
| `krita`             | Stylus, canvas and GPU                |
| `blender`           | Heavy GPU and custom UI               |
| `xterm`             | X11/XWayland compatibility            |

```bash
sudo pacman -S \
  wev gtk4-demos libadwaita-demos \
  foot gnome-text-editor kate \
  nautilus dolphin libreoffice-fresh \
  mpv firefox code krita blender \
  xorg-xwayland xterm
```

# Suggested end-goal milestones

## Milestone A — Desktop application fundamentals

Pass:

* GTK 3
* GTK 4/libadwaita
* Qt 6/KDE
* Native Wayland
* X11 through XWayland
* Android IME and clipboard
* Android document open/save
* Rotation and resizing
* Secondary dialogs

Representative packages: `mousepad`, `gnome-text-editor`, `kate`, `foot`, `xterm`, `nautilus`, `dolphin`.

## Milestone B — Integrated Android application behavior

Pass:

* Notifications
* Background tasks
* URI intents
* Camera
* Microphone
* Audio focus
* Printing
* Android share/open-with
* Credential storage
* Lifecycle restoration

Representative packages: `gnome-clocks`, `snapshot`, `gnome-sound-recorder`, `transmission-gtk`, `keepassxc`, `system-config-printer`.

## Milestone C — Accelerated applications

Pass:

* EGL/GLES
* Vulkan
* dmabuf or equivalent zero-copy buffers
* Hardware video decode
* WebGL/WebGPU
* Correct frame pacing
* Pointer capture
* Stylus pressure

Representative packages: `glmark2`, `vulkan-tools`, `mpv`, `krita`, `firefox`, `chromium`, `supertuxkart`.

## Milestone D — “This is a real desktop application platform”

Pass:

* `libreoffice-fresh`
* `firefox`
* `code`
* `krita`
* `blender`
* `kdenlive`
* `obs-studio`

Then Archphene demonstrates a general-purpose isolated Linux app platform with Android-native lifecycle, security boundaries, and integration; not selected widgets rendering on Android.

[2]: https://archlinux.org/packages/extra/x86_64/wev/ "Arch Linux - wev 1.1.0-1 (x86_64)"
[3]: https://archlinux.org/packages/extra/x86_64/foot/ "Arch Linux - foot 1.27.0-1 (x86_64)"
[4]: https://archlinux.org/packages/extra/x86_64/thunar/ "Arch Linux - thunar 4.20.8-3 (x86_64)"
[5]: https://archlinux.org/packages/extra/x86_64/krita/ "Arch Linux - krita 6.0.2.1-2 (x86_64)"
[6]: https://archlinux.org/packages/extra/x86_64/snapshot/ "Arch Linux - snapshot 50.0-1 (x86_64)"
[7]: https://archlinux.org/packages/extra/x86_64/chromium/ "Arch Linux - chromium 150.0.7871.124-1 (x86_64)"
[8]: https://archlinux.org/packages/extra/x86_64/supertuxkart/ "Arch Linux - supertuxkart 1.5-1 (x86_64)"
[9]: https://archlinux.org/packages/extra/any/accerciser/ "Arch Linux - accerciser 3.48.0-2 (any)"
