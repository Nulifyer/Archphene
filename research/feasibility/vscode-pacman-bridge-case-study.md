# VS Code pacman package bridge case study

Date: 2026-07-09

Goal: make the Arch `code` pacman package install and run on GrapheneOS as a real Android app, with Android app identity, permissions, lifecycle, storage, notifications, and desktop-mode behavior.

## Executive summary

The Arch package named `code` is the open source Code-OSS build of Visual Studio Code. As of this research pass, Arch packages `code 1.127.0-1` in `extra`, built on 2026-07-06, installed size 691.7 MB, and flagged out-of-date on 2026-07-08. The package depends directly on `electron42`, `libsecret`, `libx11`, `libxkbfile`, and `ripgrep`, with build dependencies including Git, jq, Node.js LTS Krypton, npm, Python, and zip.

VS Code is not a normal GTK app. It is an Electron app: Chromium renderer processes plus a Node/Electron main process, VS Code extension host processes, a PTY host, shared/utility processes, child processes for tools, and large JavaScript/TypeScript application payloads.

Making it usable on GrapheneOS without a VM is possible, but VS Code should be treated as a bridge v2/v3 target, not the first prototype. It requires a runtime bridge that is at least good enough for Electron/Chromium.

## What the Arch package contains

Current Arch package facts:

- Package: `code`
- Version: `1.127.0-1`
- Repo: `extra`
- Architecture: `x86_64`
- Description: "The Open Source build of Visual Studio Code (vscode) editor"
- Upstream: `https://github.com/microsoft/vscode`
- License: MIT
- Provides: `vscode`
- Package size: 116.2 MB
- Installed size: 691.7 MB
- Direct runtime dependencies listed by Arch:
  - `electron42`
  - `libsecret`
  - `libx11`
  - `libxkbfile`
  - `ripgrep`

Important file layout from Arch's package file list:

```text
usr/bin/code
usr/bin/code-oss
usr/lib/code/
usr/lib/code/code.mjs
usr/lib/code/extensions/
usr/lib/code/node_modules/
usr/lib/code/out/
usr/share/applications/code-url-handler.desktop
usr/share/icons/hicolor/scalable/apps/com.visualstudio.code.oss.svg
usr/share/bash-completion/completions/code
usr/share/bash-completion/completions/code-oss
```

The file list reports 22,419 files and 5,130 directories. That matters because this is not a small binary wrapping problem. The package includes large built-in extension trees, Node modules, webview assets, Monaco/editor code, search workers, language support, and Copilot extension content in the Arch build observed.

## What `electron42` adds

The `electron42` Arch package is also large:

- Package: `electron42`
- Version observed on package page: `42.6.0-1`
- Installed size: 329.3 MB
- Runtime dependencies listed by Arch include:
  - `glibc`
  - `gtk3`
  - `fontconfig`
  - `freetype2`
  - `harfbuzz`
  - `libdrm`
  - `libevent`
  - `libffi`
  - `libjpeg-turbo`
  - `libpulse`
  - `libxml2`
  - `libxslt`
  - `nss`
  - `opus`
  - `zlib`
  - `minizip`
  - and codec/compression/media libraries

Relevant `electron42` files include:

```text
usr/bin/electron42
usr/lib/electron42/electron
usr/lib/electron42/chrome-sandbox
usr/lib/electron42/chrome_crashpad_handler
usr/lib/electron42/libEGL.so
usr/lib/electron42/libGLESv2.so
usr/lib/electron42/libffmpeg.so
usr/lib/electron42/libvulkan.so.1
usr/lib/electron42/resources/default_app.asar
usr/lib/electron42/resources.pak
usr/lib/electron42/locales/*.pak
```

This means a GrapheneOS bridge for `code` is primarily an Electron/Chromium bridge.

## Architecture implied by VS Code source

VS Code's upstream repository is Code-OSS under MIT. The GitHub repository states that Visual Studio Code is a Microsoft distribution of Code-OSS with Microsoft-specific customizations.

Relevant source-level behavior:

- `src/vs/code/electron-main/main.ts` is the Electron main entry point for the desktop app.
- It imports Electron `app` and `dialog`.
- It creates services including file service, user data profiles, policy service, request service, protocol service, signing service, tunnel service, lifecycle service, logging, and theme service.
- It claims a single-instance IPC handle through `nodeIPCServe(environmentMainService.mainIPCHandle)`.
- If another instance exists, it connects through `nodeIPCConnect(...)` and forwards launch arguments to the running instance.
- It registers a file provider for normal disk files and a user-data provider.
- On Linux, it references `XDG_RUNTIME_DIR` for IPC path handling and Linux policy file paths.

VS Code's terminal source:

- `src/vs/platform/terminal/node/ptyHostMain.ts` starts a PTY host.
- It creates a child-process or utility-process IPC server.
- It registers channels including logger, heartbeat, and `TerminalIpcChannels.PtyHost`.
- It creates `PtyService`, which is the actual PTY-backed integrated terminal service.

VS Code IPC source:

- `src/vs/base/parts/ipc/node/ipc.net.ts` implements Node socket/WebSocket socket handling.
- It uses Node `net`, HTTP upgrade to WebSocket, compression, socket chunking, and `XDG_RUNTIME_DIR`.

Electron architecture:

- Electron apps have a main process and renderer processes.
- The main process creates and manages windows through `BrowserWindow`.
- Renderer processes display the UI.
- Electron can spawn utility/child processes.
- Electron exposes native desktop APIs such as dialogs, menus, notifications, clipboard, power APIs, and shell integration.

For the bridge, this means `code` needs all of the following to work:

```text
Electron main process
  -> Android lifecycle bridge
  -> Wayland/X display bridge
  -> Node fs/net/process support
  -> Unix socket IPC support
  -> user data directories

Chromium renderer processes
  -> Wayland/X window backend
  -> GPU/software renderer
  -> Chromium sandbox
  -> clipboard/input/IME

VS Code extension host processes
  -> Node runtime
  -> filesystem/workspace access
  -> network if extensions need it
  -> extension storage

PTY host
  -> /dev/ptmx-style PTY
  -> shell/tool process spawning
  -> terminal rendering in VS Code UI

Search/git/tools
  -> ripgrep
  -> git optional but practically required
  -> language servers/debuggers from extensions
```

## Package ingestion plan

For x86 laptops/tablets running Android-x86, the Arch `x86_64` package can be used as the source package shape.

For a GrapheneOS phone, official Arch `code` is not directly usable because it is `x86_64`. A phone target is likely `aarch64`. There are two options:

- build Code-OSS and Electron for Linux `aarch64` from source using the Arch packaging recipe as a model.
- use an Arch Linux ARM style package source if available and trusted, then adapt it.

The packager must not run pacman inside GrapheneOS. Instead:

```text
resolve code + electron42 + runtime dependency closure
  -> verify package signatures / source hashes
  -> extract payloads into staging
  -> strip host-only packaging metadata
  -> patch launch paths
  -> patch ELF interpreter/rpath as needed
  -> generate Android package descriptor
  -> build APK
  -> sign APK
```

The generated package identity should be stable:

```text
Android package: org.archphene.codeoss
App label: Code - OSS
Version: 1.127.0-1.archphene.N
Signing key: per-app stable key held by LinuxAPK Store
```

## APK shape

For `code`, the APK cannot be tiny. A day-one APK would likely contain:

```text
AndroidManifest.xml
classes.dex
res/mipmap*/com.visualstudio.code.oss.*
assets/archphene/descriptor.json
assets/desktop/code.desktop
assets/code/usr-lib-code/...
assets/code/usr-share/...
lib/arm64-v8a/archphene_launcher.so
lib/arm64-v8a/archphene_bridge.so
lib/arm64-v8a/electron
lib/arm64-v8a/chrome_crashpad_handler
lib/arm64-v8a/chrome-sandbox
lib/arm64-v8a/ld-linux-aarch64.so.1
lib/arm64-v8a/libc.so.6
lib/arm64-v8a/libgtk-3.so...
lib/arm64-v8a/libripgrep_exec.so or assets/bin/rg
```

Better long-term:

```text
org.archphene.runtime.electron42
  Electron, Chromium assets, glibc runtime, GTK/font/media libs, bridge services

org.archphene.codeoss
  Code-OSS payload, app descriptor, icon, launch metadata, app-specific permissions
```

But the shared runtime must not become a permission bypass. If the runtime performs network, file, clipboard, or notification work, it must preserve caller identity and enforce policy per app.

## Android manifest and permissions

Initial generated manifest:

```text
package="org.archphene.codeoss"
uses-permission android.permission.INTERNET
uses-permission android.permission.POST_NOTIFICATIONS
uses-permission android.permission.RECORD_AUDIO optional/conditional
uses-feature android.hardware.camera required=false
uses-feature android.hardware.microphone required=false
activity .CodeActivity launcher, resizable, desktop-mode friendly
provider .CodeDocumentsProvider optional later
service .BridgeService foreground only while app is running
```

Permission mapping:

| VS Code feature | Android-visible control | Bridge behavior |
| --- | --- | --- |
| Extension downloads, GitHub, webviews, settings sync | Network permission | Linux sockets run under `org.archphene.codeoss` UID; optional network broker later |
| Workspace open | Storage Access Framework | User chooses folder; bridge exposes it as synthetic workspace path |
| Save file | Storage Access Framework | Write back through URI grants or sync bridge |
| Notifications | Notification permission | libnotify/Electron notification -> Android NotificationManager |
| Clipboard | Android clipboard policy | Wayland/Chromium clipboard -> Android clipboard when foreground |
| Integrated terminal | No Android runtime permission, but high risk | PTY broker launches shell/tools in app-private tool root |
| Camera/mic in webviews/extensions | Camera/mic runtime permissions | Chromium media capture -> Android Camera/AudioRecord brokers |
| Git credential storage | no direct keychain permission | libsecret bridge -> Android Keystore/Credential Manager-backed secret store |
| Open external URL | Android intent resolver | Electron shell/openExternal -> Android ACTION_VIEW |
| Screen capture from extensions/webviews | MediaProjection prompt | portal request only; do not grant by default |

Avoid `MANAGE_EXTERNAL_STORAGE`. VS Code must not receive whole-device filesystem access by default.

## Bridge contracts VS Code needs

### Display

Electron's Linux backend normally uses X11 or Wayland through Chromium/Ozone and GTK integration.

Day-one target:

```text
Code/Electron -> Wayland socket -> Archphene compositor -> Android Surface
```

Bridge must provide:

- `WAYLAND_DISPLAY`
- `XDG_RUNTIME_DIR`
- monitor scale/density mapping
- keyboard/mouse/touch input
- IME/text input
- drag/drop later
- clipboard
- resize/maximize/minimize signals mapped to Android task/window state

Fallback:

- Xwayland inside the app namespace for components that require X11.

Risk:

- X11 compatibility weakens client isolation. For VS Code this may be acceptable inside one package, but it should not be shared across apps.

### GPU

Electron ships `libEGL.so`, `libGLESv2.so`, Vulkan/SwiftShader assets, and Chromium GPU process logic.

Day-one target:

- use software rendering/SwiftShader first.
- then add Android hardware buffer/EGL integration.

Do not expose raw `/dev/dri` or DRM/KMS to the app.

### D-Bus and portals

VS Code/Electron and GTK may expect session bus behavior.

Bridge should provide:

- app-local `DBUS_SESSION_BUS_ADDRESS`
- `org.freedesktop.portal.Desktop`
- notification portal
- file chooser portal
- settings portal for theme/color scheme
- open URI portal
- secret-service bridge for libsecret

Do not expose a broad global Linux session bus.

### Filesystem and workspace

VS Code's core value is editing project folders. Android scoped storage and VS Code's path-based workspace model do not line up cleanly.

Three tiers:

#### Tier 1: import workspace

User picks a folder. Bridge copies files into:

```text
/data/data/org.archphene.codeoss/files/workspaces/<workspace-id>/
```

VS Code edits the copy. User exports/syncs back.

Pros:

- easiest
- strong Android storage compatibility
- works without FUSE

Cons:

- not transparent
- sync conflict risk

#### Tier 2: document tree proxy

User grants a Storage Access Framework tree. Bridge exposes synthetic paths:

```text
/home/app/workspaces/<name>/
```

Reads/writes go through URI grants.

Pros:

- app sees path-like workspace
- user keeps Android folder control

Cons:

- file watching is hard
- random access and rename semantics may be incomplete

#### Tier 3: OS-supported FUSE/document mount

Platform service mounts granted document trees into the Linux app namespace.

Pros:

- best VS Code compatibility
- path-based tools work

Cons:

- requires OS support
- must preserve Android permission semantics

For VS Code, tier 3 is the real target. Tier 1 is acceptable for a proof.

### File watching

VS Code and extensions rely on watching files. Linux apps expect inotify.

Bridge options:

- app-private imported workspace: use real Linux inotify on app-private files.
- SAF-backed workspace: emulate change events from Android document provider signals where possible.
- OS FUSE mount: implement inotify-like events in the mounted view.

Without good file watching, large projects and language tooling feel unreliable.

### Integrated terminal

The integrated terminal is a blocker for "usable laptop app."

VS Code's PTY host source shows it starts a PTY host process and registers IPC channels for terminal services. The bridge needs:

- PTY device access or PTY emulation.
- shell executable.
- app-private `PATH`.
- optional packaged tools: `git`, `bash`, `sh`, `node`, `python`, `rg`.
- signal handling.
- process groups/jobs good enough for shells.
- terminal subprocesses confined to the app package UID and workspace grants.

Do not let the terminal become a generic escape hatch to Android host `/`.

Recommended day-one:

```text
VS Code terminal -> pty host -> app-local busybox/bash -> app-private tool root
```

Later:

```text
terminal command broker checks workspace/file/network grants
```

### Git

VS Code source control is only useful if Git works.

Package `git` into the app/tool runtime or declare a dependency on an Archphene developer-tools runtime.

Credential flow:

```text
git credential helper
  -> bridge helper
  -> Android Keystore/Credential Manager backed secret storage
```

SSH flow:

- day one: app-private SSH keys only.
- later: Android-backed key agent and per-key user approval.

### Search

Arch `code` depends on `ripgrep`. This is good: package `rg` with the app or runtime.

Search runs over granted/imported workspace paths only.

### Extensions

VS Code extensions are the second biggest security issue after Electron itself.

Extensions can:

- execute Node.js in extension host.
- spawn tools.
- access workspace files.
- make network requests.
- install native helper binaries.
- run language servers and debug adapters.

For GrapheneOS-style controls, extension privileges must be mediated.

Day-one policy:

- extensions run under the same app UID as Code-OSS.
- extensions inherit the Code app permissions.
- extension install/download requires network permission.
- native extension helper downloads are blocked unless the package declares dynamic-code/developer mode.

Better policy:

- extension host has sub-identities.
- each extension has a manifest-derived permission profile.
- workspace trust is enforced.
- native extension binaries are packaged as separate APK/LAPK components or installed into non-executable data unless explicitly allowed.

This is a large project by itself.

## Electron/Chromium sandbox problem

This is the most important security blocker.

Arch `electron42` includes `chrome-sandbox`. On normal Linux, Chromium/Electron sandboxing commonly relies on user namespaces and/or a setuid sandbox helper depending on build/config/runtime.

On GrapheneOS:

- setuid helpers are not acceptable.
- raw host user namespaces may be unavailable or intentionally restricted.
- `--no-sandbox` would make the day-one demo easier but is not acceptable for a security-oriented product.

Therefore VS Code needs one of:

1. OS support for Chromium/Electron sandbox primitives inside the app UID.
2. a patched Electron build using Android-compatible process isolation primitives.
3. a dedicated `linux_app` SELinux domain plus seccomp/cgroup/isolation policy strong enough to replace the desktop Linux assumptions.

Recommendation:

- do not ship VS Code bridge support until Chromium/Electron sandboxing is understood and enforced.
- a development-only prototype may use a reduced sandbox to prove display/files, but it must be labeled insecure.

## How Android lifecycle maps to VS Code

Android events:

```text
launcher tap
task resize
external display attach/detach
app background
app foreground
force stop
uninstall
permission revoke
low memory
```

Bridge behavior:

- foreground: resume compositor, timers, file watchers, pty host.
- background: keep running only if user has active terminal/task, otherwise throttle.
- force stop: terminate Electron main process and child tree.
- uninstall: remove app-private user data and workspace cache; Android does this for app data, but imported/exported files remain where user placed them.
- permission revoke: portal calls fail; Electron/VS Code surfaces error; do not keep stale file descriptors where possible.
- low memory: ask VS Code to close background windows/processes, then kill child processes if needed.

## Day-one runnable plan for VS Code

### v0: prove process and UI only

- Build/use Linux `aarch64` Code-OSS + Electron.
- Package Electron + Code-OSS into one APK.
- Use software rendering.
- Start an Android Activity with Surface.
- Start app-local Wayland compositor.
- Launch Electron with `WAYLAND_DISPLAY`.
- Use app-private HOME and XDG paths.
- Disable marketplace/extension installs.
- No integrated terminal.
- Open only app-private sample workspace.

Success: Code-OSS UI renders and can edit a local sample file.

### v1: make it editor-useful

- Add Storage Access Framework folder import/export.
- Add notifications.
- Add clipboard.
- Add network permission.
- Enable extension gallery only if source is Open VSX or configured Code-OSS-compatible registry.
- Enable `ripgrep` search over app-private/imported workspace.

Success: user can open a project copy, edit, search, install pure JS extensions, and save/export.

### v2: make it developer-useful

- Add PTY host support.
- Add packaged shell, Git, SSH client, basic toolchain support.
- Add credential bridge to Android-backed secure storage.
- Add file watcher reliability.
- Add document-tree/FUSE style workspace access.

Success: user can clone, edit, search, commit, push, and run project scripts in the integrated terminal.

### v3: make it GrapheneOS-grade

- Dedicated Linux app SELinux domain.
- Per-app mount namespace.
- Chromium/Electron sandbox enforced without setuid.
- Extension permission model.
- Portal-mediated camera/mic/screen capture.
- Settings UI shows Linux app permissions and extension risk.

Success: VS Code behaves like a first-class Android desktop-mode app with defensible security claims.

## Minimal Android permission profile

Recommended default install:

```text
INTERNET: ask/controlled by GrapheneOS network toggle
POST_NOTIFICATIONS: ask on first notification
RECORD_AUDIO: not granted by default
CAMERA: not declared unless webview/media support enabled
Storage: no broad storage permission; use SAF
Bluetooth/USB/Location: not declared by default
```

Developer-mode optional permissions/capabilities:

- execute packaged developer tools.
- install native extension helpers.
- use SSH agent.
- expose local ports for web preview/debugging.
- screen capture for extensions.

## Concrete communication graph

```text
Android Launcher
  -> CodeActivity
    -> BridgeService
      -> Wayland compositor socket
      -> portal D-Bus socket
      -> PulseAudio/PipeWire shim socket
      -> secret-service shim socket
      -> PTY broker socket

Code/Electron main
  -> BrowserWindow / Chromium renderers via Electron
  -> VS Code main IPC via Node unix sockets in XDG_RUNTIME_DIR
  -> extension host via child process IPC
  -> pty host via VS Code terminal IPC
  -> bridge portals for files/notifications/clipboard/secrets

Renderer processes
  -> Wayland compositor
  -> Chromium GPU/software renderer
  -> Electron IPC back to main

Extension host
  -> Node APIs
  -> workspace filesystem view
  -> network under app UID
  -> child_process to language servers/tools

PTY host
  -> app-local shell/tools
  -> app-local PTY
```

## Feasibility verdict

VS Code can be made to run this way, but not as the first app.

The main blockers are:

- Linux `aarch64` Electron/Code-OSS build pipeline.
- Electron/Chromium sandbox without setuid or unsafe `--no-sandbox`.
- Wayland-to-Android Surface bridge stable enough for Chromium.
- path-based workspace access over Android scoped storage.
- integrated terminal without exposing the Android host.
- extension ecosystem risk.

If those are handled, VS Code is exactly the kind of app that proves GrapheneOS can become a laptop OS: it needs display, files, network, notifications, clipboard, terminal, Git, credentials, and extensions.

## Source map

- Arch `code` package: https://archlinux.org/packages/extra/x86_64/code/
- Arch `code` file list: https://archlinux.org/packages/extra/x86_64/code/files/
- Arch `electron42` package: https://archlinux.org/packages/extra/x86_64/electron42/
- Arch `electron42` file list: https://archlinux.org/packages/extra/x86_64/electron42/files/
- VS Code repository: https://github.com/microsoft/vscode
- VS Code Electron main source: https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/code/electron-main/main.ts
- VS Code PTY host source: https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/platform/terminal/node/ptyHostMain.ts
- VS Code IPC socket source: https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/base/parts/ipc/node/ipc.net.ts
- Electron process model: https://www.electronjs.org/docs/latest/tutorial/process-model
- Electron BrowserWindow: https://www.electronjs.org/docs/latest/api/browser-window
- Electron security guide: https://www.electronjs.org/docs/latest/tutorial/security
- GrapheneOS features overview: https://grapheneos.org/features
- Android app sandbox: https://source.android.com/docs/security/app-sandbox
- Android runtime permissions: https://developer.android.com/training/permissions/requesting