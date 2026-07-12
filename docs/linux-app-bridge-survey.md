# Linux app bridge survey

Date: 2026-07-09

Scope: review representative Arch Linux desktop apps for an ArchpheneOS-style bridge where a Linux package is installed as a real Android app with Android package identity, permissions, lifecycle, storage controls, and GrapheneOS-style hardening.

Apps reviewed:

- KCalc
- Mousepad
- LibreOffice
- Firefox
- btop
- GIMP
- Kdenlive
- Blender

## Executive summary

These apps separate into five bridge classes:

| App | Class | Bridge readiness | Main blocker |
| --- | --- | --- | --- |
| KCalc | small Qt/KDE GUI calculator | best first real GUI target | needs Qt6/KF6 runtime plus Wayland-to-Android Surface bridge |
| Mousepad | small GTK text editor | best first editor/file-portal target | needs GTK3/GtkSourceView runtime plus Wayland and storage portals |
| btop | terminal/TUI/system monitor | easiest to launch, hard to make semantically useful | Android does not expose whole-system `/proc`/device stats to ordinary apps |
| GIMP | GTK image editor | good early serious GUI target | plug-ins, scripting, file portals, color/input/tablet support |
| LibreOffice | large office suite | feasible after GTK/Qt/file/print portals | macros, Java/Python/UNO, printing, huge dependency and file-format surface |
| Zed | native editor, from prior study | best serious developer editor target | Wayland fidelity, Vulkan/GPUI, terminal/tools |
| Firefox | browser engine | technically possible, security-sensitive | browser/content sandboxing, WebRTC, extensions, duplicate browser attack surface |
| Kdenlive | Qt/KDE video editor | later multimedia target | MLT/FFmpeg, codecs, render jobs, audio/video/screen capture |
| Blender | 3D/DCC suite | later GPU workstation target | OpenGL/Vulkan/GPU compute, Python add-ons, huge rendering/device surface |

The best order for bridge prototyping is:

```text
1. current bridge payload tests
2. KCalc as first real Qt/KDE GUI app
3. Mousepad as first real editor/file-portal app
4. simple CLI/TUI app in terminal wrapper
5. GIMP
6. LibreOffice
7. Zed
8. Kdenlive
9. Blender
10. Firefox only after browser sandboxing is deliberately designed
```

Firefox is not last because it is impossible. It is last because GrapheneOS already has a hardened browser/WebView model, and adding a second desktop browser engine as a Linux app needs a very clear security story before it should be a product target.

KCalc is now the named first GUI target. It is small and should not need broad storage, networking, or Android dangerous permissions, but it still forces the bridge to support a real glibc + Qt6 + KDE Frameworks 6 GUI stack.

Mousepad is the named first editor/file-portal target. It is also small, uses GTK3/GtkSourceView instead of Qt/KDE, and naturally exercises Android open/save/project-folder storage mediation.

## Shared bridge requirements

All GUI apps need:

- stable Android package identity
- per-app UID
- per-app SELinux or `linux_app` domain
- app-private mount namespace
- app-local glibc/runtime dependency root
- Android lifecycle management
- Wayland/X11 bridge to Android Surface
- clipboard bridge
- file picker/document portal
- notifications portal
- font/config/theme handling
- network permission enforcement
- seccomp profile
- dynamic code/tool execution policy

Apps with shells, plug-ins, renderers, macros, extensions, or add-ons also need:

- child process broker
- PTY broker where applicable
- explicit executable-code policy
- per-plug-in or per-extension trust model
- app-private tool/download directories
- no access to Android host `/`
- no raw device nodes by default

Apps with media/GPU workloads need:

- audio output broker
- microphone broker
- camera broker if capture is enabled
- screen capture through MediaProjection
- hardware decode/encode broker or software fallback
- Android GPU-safe OpenGL/Vulkan path

## Package fact matrix

Current Arch package facts observed:

| Package | Version | Arch | Package size | Installed size | Dependency count | File count |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| `libreoffice-fresh` | `26.2.4-3` | `x86_64` | 147.2 MB | 421.5 MB | 192 | 11,658 files / 813 dirs |
| `firefox` | `152.0.5-1` | `x86_64` | 81.8 MB | 286.4 MB | 57 | 53 files / 47 dirs |
| `btop` | `1.4.7-1` | `x86_64` | 597.6 KB | 1.7 MB | 7 | 47 files / 16 dirs |
| `gimp` | `3.2.4-1` | `x86_64` | 23.5 MB | 152.5 MB | 115 | 5,932 files / 605 dirs |
| `kdenlive` | `26.04.3-1` | `x86_64` | 21.4 MB | 90.0 MB | 51 | 915 files / 198 dirs |
| `blender` | `17:5.1.2-1` | `x86_64` | 177.6 MB | 378.3 MB | 92 | 3,186 files / 396 dirs |

The file count is not the whole complexity story. Firefox has a low file count because much of the application is packed into large runtime archives and libraries such as `omni.ja`, `libxul.so`, `libmozsandbox.so`, media libraries, and test helpers. LibreOffice and GIMP expose much more of their surface as individual files.

## btop

### Package shape

Arch package:

- `btop 1.4.7-1`
- description: monitor of system resources, bpytop ported to C++
- installed size: 1.7 MB
- dependencies: `glibc`, `libgcc`, `libstdc++`, icon/theme assets, optional `rocm-smi-lib` for AMD GPU support
- main executable: `usr/bin/btop`

### Bridge behavior

`btop` is the easiest app to launch and one of the hardest to make truthful.

It only needs:

- terminal emulator
- pseudo-terminal
- app-local HOME/config
- C/C++ runtime

But its job is to inspect:

- CPU load
- memory
- disks
- network
- processes
- temperatures/fans/GPU where available

On Android/GrapheneOS, an ordinary app should not get broad visibility into the entire system or other apps. Giving Linux `btop` raw `/proc`, `/sys`, disk, network, or GPU telemetry would violate the Android app sandbox expectation.

### Recommended design

Day-one:

```text
btop APK -> Android terminal Activity -> PTY -> btop
```

But expose only:

- app-local process tree
- app-visible cgroup memory/CPU where permitted
- synthetic network counters for the app UID
- no per-process list outside the app sandbox

Better OS-supported version:

```text
btop -> synthetic /proc + stats broker -> Android system stats APIs
```

The broker should expose "what the user is allowed to know," not raw Linux host internals.

### Verdict

Best first CLI/TUI proof, but not a proof that arbitrary system-monitoring Linux tools can keep their full semantics.

## GIMP

### Package shape

Arch package:

- `gimp 3.2.4-1`
- description: GNU Image Manipulation Program
- installed size: 152.5 MB
- dependencies include GTK3, Cairo, Pango, GEGL, babl, gdk-pixbuf, libjpeg, libpng, libtiff, libwebp, libjxl, OpenEXR, poppler, Python Cairo/GObject, MyPaint brushes, GLib/GIO, X11 libraries
- file list includes `usr/bin/gimp`, `gimp-console`, `gimptool`, Script-Fu interpreter, config files, headers, plug-in resources

### Bridge behavior

GIMP is a good early serious GUI target because it is a conventional GTK app with real productivity value. It needs:

- GTK3 over Wayland/Xwayland
- image file import/export through SAF/document portal
- clipboard image bridge
- font discovery
- color profile handling
- high memory limits for large images
- optional stylus/tablet pressure bridge
- optional print/scanner portals

The hard security issue is plug-ins and scripting. GIMP can run external plug-ins and scripts. The Arch package includes Python-related dependencies and Script-Fu support. Third-party plug-ins can become arbitrary native code or interpreter code.

### Recommended design

Day-one:

- app-private workspace
- no broad storage
- no scanner
- no print
- disable third-party executable plug-ins
- allow built-in filters and safe bundled plug-ins
- use software rendering unless GL path is easy

Later:

- per-plug-in trust
- plug-in install prompt
- dynamic code execution toggle
- scanner through Android document/camera/import flow, not raw SANE device access
- print through Android Print Framework

### Verdict

Strong early GUI target after the generic GTK bridge works. More useful than a toy app, much less dangerous than Firefox/Kdenlive/Blender.

## LibreOffice

### Package shape

Arch package:

- `libreoffice-fresh 26.2.4-3`
- description: LibreOffice branch with new features and enhancements
- installed size: 421.5 MB
- dependencies include Cairo, DBus, fontconfig, freetype, GLib, GPGME, HarfBuzz/ICU, Hunspell, LibreOffice document import libraries, CUPS, libepoxy/libGL, NSS, Poppler, Python, XMLSec, xdg-utils, optional Java runtime/environment, optional GTK/Qt integration, optional GStreamer multimedia support
- provides Writer, Calc, Draw, Impress, Math, Base, `soffice`, `unopkg`
- file list reports 11,658 files and 813 directories

### Bridge behavior

LibreOffice is a full desktop suite, not one app. It needs:

- multiple launcher entry points
- document open/save portals
- file associations
- print framework bridge
- font and locale integration
- spellcheck dictionaries
- clipboard with rich text/images
- drag/drop
- dialogs
- optional multimedia playback in Impress
- optional database connectors
- optional Java support
- macro/script security

The bridge must decide whether `org.archphene.libreoffice` contains the whole suite, or whether Writer/Calc/etc. appear as separate launchable activities backed by the same package UID.

### Recommended design

Android package:

```text
org.archphene.libreoffice
  activities:
    WriterActivity
    CalcActivity
    ImpressActivity
    DrawActivity
    BaseActivity optional
```

Permission profile:

- no network by default unless user enables remote documents/templates/update checks
- no broad storage
- document picker only
- print through Android Print Framework
- macro execution disabled or warned by default
- Java disabled by default

### Security notes

LibreOffice document formats and import filters are a large parser attack surface. Macros, Python, Java, and UNO automation are the executable-code problem. Treat documents as untrusted input and macros as dynamic code execution.

### Verdict

Feasible, valuable, and a good "desktop productivity" milestone after GIMP. It is heavy, but its device integration needs are mostly portals, printing, fonts, files, and macro policy rather than raw GPU/device access.

## Firefox

### Package shape

Arch package:

- `firefox 152.0.5-1`
- description: Fast, Private & Safe Web Browser
- installed size: 286.4 MB
- dependencies include GTK3, DBus, FFmpeg, fontconfig, freetype, GLib, libpulse, NSS/NSPR, Pango, X11/XCB, libxcomposite, libxdamage, libxrandr, libxss, libxt, optional libnotify, optional xdg-desktop-portal for Wayland screensharing
- file list includes `usr/lib/firefox/firefox`, `firefox-bin`, `libxul.so`, `libmozsandbox.so`, `libmozwayland.so`, `libmozgtk.so`, media libraries, `glxtest`, `vulkantest`, `crashreporter`, `omni.ja`

### Bridge behavior

Firefox is not just another GTK app. It is a browser engine, JavaScript engine, media stack, networking stack, extension platform, content sandbox, site isolation model, profile database, credential store, WebRTC client, and update/security pipeline.

Bridge needs:

- GTK/Wayland
- browser/content process sandboxing
- seccomp/user namespace story compatible with Android/GrapheneOS
- audio output
- camera/microphone portals
- screen capture through MediaProjection
- notification portal
- file picker/download portal
- credential/secret storage
- hardware video decode or software fallback
- WebExtension policy
- crash reporting policy

### Product concern

GrapheneOS already ships a hardened Chromium-based browser/WebView model. A Linux Firefox APK duplicates a massive browser attack surface and introduces a second browser sandbox design. That may be acceptable for user choice, but it is not a good early milestone for proving Linux app support.

### Recommended design

Do not start with full Firefox.

If Firefox is required:

- keep it as its own Android package UID
- disable auto-update in favor of APK package updates
- wire every WebRTC/camera/mic/screen request through Android runtime permissions
- preserve Firefox content sandbox; do not run with sandbox disabled
- make downloads use Android file picker/saf paths
- keep profile app-private
- make extension installs visible as a major trust boundary

### Verdict

Technically possible only with serious OS/runtime support. Bad early target. It should come after the bridge can preserve complex multi-process sandboxes.

## Kdenlive

### Package shape

Arch package:

- `kdenlive 26.04.3-1`
- description: non-linear video editor for Linux using the MLT video framework
- installed size: 90.0 MB
- dependencies include FFmpeg, frei0r plug-ins, MLT, OpenTimelineIO, Qt6 Base/Declarative/Multimedia/NetworkAuth/SVG, many KDE Frameworks, KIO, KNotifications, KNewStuff, Solid, Purpose
- optional dependencies include OpenCV for motion tracking, Whisper/Vosk speech-to-text, screen capture tools, image format plug-ins, VR360 effects
- file list includes `usr/bin/kdenlive`, `usr/bin/kdenlive_render`, Qt6 plugins and QML assets

MLT documentation describes a plugin/service architecture with producers, filters, transitions, consumers, scripting bindings, XML formats, OpenGL support, and Melt command-line playback/rendering.

### Bridge behavior

Kdenlive needs a full multimedia bridge:

- Qt6/KDE UI over Wayland/Xwayland
- file tree access to large media folders
- FFmpeg codecs
- audio output
- microphone/audio recording
- video preview surfaces
- render jobs as background/foreground services
- notification on render completion
- optional screen capture via MediaProjection
- optional camera/import integration
- optional hardware encode/decode
- large temporary/cache storage

### Security notes

Video files are parser-heavy untrusted inputs. Effects and plug-ins expand the executable surface. Render jobs can run long and generate very large outputs. Screen/audio capture must go through Android prompts.

### Recommended design

Day-one:

- software decode/encode
- app-private imported project folder
- no screen capture
- no hardware encoder
- no arbitrary render scripts
- render job foreground service with notification

Later:

- document-tree/FUSE mount for media libraries
- Android MediaCodec broker for decode/encode
- MediaProjection for screen capture
- microphone permission for audio record
- per-plug-in policy

### Verdict

Good later multimedia milestone. Not a day-one app because it forces media, long-running jobs, codec policy, and large workspace handling all at once.

## Blender

### Package shape

Arch package:

- `blender 17:5.1.2-1`
- description: fully integrated 3D graphics creation suite
- installed size: 378.3 MB
- dependencies include FFmpeg, OpenImageIO, OpenColorIO, OpenEXR, OpenVDB, OpenSubdiv, Embree, oneTBB, Python, NumPy, OpenXR, OpenAL, SDL2, GLEW, libepoxy, X11, libxkbcommon, USD, materialx, Intel oneAPI runtime libs, Level Zero loader
- optional dependencies include CUDA, HIP/ROCm, Intel compute runtime, libdecor for Wayland support
- file list includes `usr/bin/blender`, `usr/bin/blender-softwaregl`, `usr/bin/blender-thumbnailer`, Cycles oneAPI kernel library, many bundled data/assets

### Bridge behavior

Blender is a workstation-class app. It needs:

- high-performance OpenGL/GPU path
- optional Vulkan/modern GPU paths depending on build/runtime
- large memory limits
- high-frequency pointer/keyboard input
- 3D mouse/tablet support later
- file access to huge asset directories
- Python scripting/add-ons
- background render jobs
- audio output
- video/audio import/export via FFmpeg
- optional OpenXR
- optional GPU compute backends

### Security notes

Blender files and add-ons can contain Python scripts. Treat Python execution as dynamic code. GPU drivers and compute APIs are a major attack surface. Cycles GPU backends such as CUDA/HIP/oneAPI do not map cleanly onto Android's ordinary app sandbox.

### Recommended design

Day-one:

- CPU rendering only
- software OpenGL or constrained GLES/OpenGL translation if possible
- app-private project directory
- Python add-ons disabled by default
- no CUDA/HIP/oneAPI
- no raw `/dev/dri`

Real target:

- Android-safe GPU acceleration path
- explicit Python/add-on permission model
- background render service
- document-tree/FUSE mount for assets
- external display/desktop mode polish

### Verdict

Excellent long-term proof that GrapheneOS can be a laptop/workstation OS, but not an early bridge target. It is mostly a GPU/runtime/performance project.

## Overall feasibility ranking

### Tier 0: Bridge smoke tests

- terminal wrapper
- static CLI apps
- simple SDL/GTK/Qt apps

### Tier 1: Early useful targets

- `btop` as a terminal/TUI wrapper with synthetic stats
- GIMP with built-in plug-ins only

### Tier 2: Productivity targets

- LibreOffice Writer/Calc/Impress with file/print/macro policy
- Zed editor without terminal/tools first

### Tier 3: Developer and multimedia targets

- Zed with terminal/Git/language servers
- Kdenlive with software media path

### Tier 4: Workstation/browser targets

- Blender with GPU acceleration and Python policy
- Firefox with preserved browser/content sandbox

## Design implication for ArchpheneOS

The bridge cannot be one mechanism. It needs capability classes:

```text
base-linux-app
  filesystem, lifecycle, Wayland, clipboard, notifications

terminal-app
  PTY, terminal UI, optional synthetic system stats

gtk-qt-desktop-app
  GTK/Qt theme/input/font/file portals

document-app
  rich clipboard, print, macro policy, document import sandbox

developer-app
  PTY, Git, SSH, language servers, dynamic tool execution

media-app
  audio/video codecs, MediaCodec, microphone, screen capture, render jobs

gpu-workstation-app
  OpenGL/Vulkan acceleration, GPU compute policy, large asset mounts

browser-app
  content sandbox, site permissions, WebRTC, extension policy, credential store
```

Each generated APK should declare which capability class it needs. GrapheneOS-style Settings should expose those as user-visible controls rather than hiding them inside the Linux runtime.

## Source map

- Arch `libreoffice-fresh` package: https://archlinux.org/packages/extra/x86_64/libreoffice-fresh/
- Arch `libreoffice-fresh` file list: https://archlinux.org/packages/extra/x86_64/libreoffice-fresh/files/
- Arch `firefox` package: https://archlinux.org/packages/extra/x86_64/firefox/
- Arch `firefox` file list: https://archlinux.org/packages/extra/x86_64/firefox/files/
- Arch `btop` package: https://archlinux.org/packages/extra/x86_64/btop/
- Arch `btop` file list: https://archlinux.org/packages/extra/x86_64/btop/files/
- Arch `gimp` package: https://archlinux.org/packages/extra/x86_64/gimp/
- Arch `gimp` file list: https://archlinux.org/packages/extra/x86_64/gimp/files/
- Arch `kdenlive` package: https://archlinux.org/packages/extra/x86_64/kdenlive/
- Arch `kdenlive` file list: https://archlinux.org/packages/extra/x86_64/kdenlive/files/
- Arch `blender` package: https://archlinux.org/packages/extra/x86_64/blender/
- Arch `blender` file list: https://archlinux.org/packages/extra/x86_64/blender/files/
- MLT documentation: https://www.mltframework.org/docs/
- GrapheneOS features overview: https://grapheneos.org/features
- Android app sandbox: https://source.android.com/docs/security/app-sandbox
- Android runtime permissions: https://developer.android.com/training/permissions/requesting




