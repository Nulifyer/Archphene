# Zed pacman package bridge case study

Date: 2026-07-09

Goal: make the Arch `zed` pacman package install and run on GrapheneOS as a real Android app, with Android app identity, permissions, lifecycle, storage, notifications, account secrets, terminal access, Git, language tooling, and desktop-mode behavior.

## Executive summary

Zed is a better early target than VS Code in some ways and a harder target in others.

It is better because it is not Electron. There is no bundled Chromium/V8 browser runtime, no Electron `BrowserWindow`, no browser renderer sandbox to preserve, and no VS Code-style Node extension host as the core UI architecture. Zed is a native Rust application using GPUI, with a smaller Arch package than VS Code.

It is harder because Zed's Linux UI stack expects native Wayland/X11 behavior and a Vulkan-capable GPU path. The Arch package explicitly depends on Wayland, X11/XCB, Vulkan loader/tools, a Vulkan driver, font/config libraries, audio libraries, SQLite, Git, Node.js, npm, curl, netcat, and GLib/GIO. Zed's own Linux documentation says the upstream Linux build works best on systems with a Vulkan-compatible GPU and system-wide glibc.

For an ArchpheneOS bridge, Zed should be treated as the best serious editor target after proving simpler GTK/Qt/SDL apps. It is likely easier to secure than VS Code because the UI runtime is Rust-native instead of Electron/Chromium, but it still needs a strong bridge for display, GPU, filesystem, PTY, Git, language servers, AI/network access, account login, and extension/tool execution.

## What the Arch package contains

Current Arch package facts from `extra/x86_64/zed`:

- Package: `zed`
- Version: `1.10.0-1`
- Repo: `extra`
- Architecture: `x86_64`
- Description: "A high-performance, multiplayer code editor from the creators of Atom and Tree-sitter"
- Upstream: `https://zed.dev`
- License: AGPL-3.0-or-later, Apache-2.0, GPL-3.0-or-later
- Replaces: `zed-editor`
- Package size: 80.8 MB
- Installed size: 316.5 MB
- Build date observed: 2026-07-08 15:08 UTC
- Last updated observed: 2026-07-08 17:17 UTC

Important runtime dependencies listed by Arch:

- `alsa-lib`
- `curl`
- `fontconfig`
- `git`
- `glib2`
- `glibc`
- `libgcc`
- `libstdc++`
- `libx11`
- `libxcb`
- `libxkbcommon`
- `libxkbcommon-x11`
- `sqlite`
- `zstd`
- `netcat`
- `nodejs >=18`
- `npm`
- `vulkan-driver`
- `vulkan-icd-loader`
- `vulkan-tools`
- `wayland`
- optional `org.freedesktop.secrets` provider such as `gnome-keyring`, `keepassxc`, `kwallet`, or `oo7`

Important make dependencies listed by Arch:

- `cargo`
- `cargo-about`
- `clang`
- `cmake`
- `protobuf`
- `vulkan-headers`
- `vulkan-validation-layers`

The Arch file list is very small compared to VS Code:

```text
usr/bin/zeditor
usr/lib/zed/zed-editor
usr/share/applications/dev.zed.Zed.desktop
usr/share/icons/hicolor/512x512/apps/zed.png
usr/share/metainfo/dev.zed.Zed.metainfo.xml
```

The file list reports only 5 files and 11 directories. Most of the app payload is in one main native binary plus shared system dependencies.

## What Zed's upstream Linux packaging implies

Zed's Linux documentation says the official upstream build works best on systems with:

- a Vulkan-compatible GPU
- system-wide glibc
- x86_64 glibc >= 2.31
- aarch64 glibc >= 2.35

The same page offers official tarballs for both Linux `x86_64` and Linux `aarch64`.

That is important for GrapheneOS phones. Unlike Arch's `zed` package page, which is `x86_64`, upstream already treats Linux `aarch64` as a supported binary target. For an ARM phone, a bridge packager should prefer a Linux `aarch64` Zed build path rather than trying to translate the Arch `x86_64` package.

## Source-level architecture

The Zed repository is a large Rust workspace. The upstream repository describes Zed as a high-performance, multiplayer code editor from the creators of Atom and Tree-sitter.

The main desktop entrypoint in `crates/zed/src/main.rs` imports and initializes a large set of internal crates:

- `gpui`
- `gpui_platform`
- `gpui_tokio`
- `client`
- `collab_ui`
- `db`
- `editor`
- `extension`
- `fs`
- `git`
- `git_ui`
- `language`
- `node_runtime`
- `project`
- `remote`
- `reqwest_client`
- `session`
- `settings`
- `theme`
- `workspace`

That main file builds the application through `gpui_platform::current_platform(false)` and `Application::with_platform(...)` or `Application::new_inaccessible(...)`, then runs Zed through GPUI.

The same entrypoint imports `ExtensionHostProxy`, `NodeRuntime`, Git hosting registries, real filesystem access, workspace/session state, crash handling, settings watchers, prompt/AI-related stores, and remote connection options. This makes Zed a native app, but not a simple native app.

## GPUI Linux surface

Zed's Linux GPUI layer has first-class Wayland support. The Wayland client source imports and binds:

- `wayland_client`
- `calloop`
- `calloop_wayland_source`
- `wl_compositor`
- `wl_seat`
- `wl_keyboard`
- `wl_pointer`
- `wl_data_device`
- `wl_output`
- `wl_shm`
- `xdg_wm_base`
- `xdg_surface`
- `xdg_toplevel`
- text input protocol
- fractional scaling
- xdg activation
- server-side decoration protocol
- primary selection
- data device and clipboard paths
- XKB keyboard handling

The same source constructs `BladeContext::new()` as the GPU context and explicitly returns false for Wayland screen capture support in the observed source path.

For the bridge, this means Zed needs a real Wayland-compatible server endpoint. It is not enough to fake a framebuffer. The bridge must implement enough compositor behavior for:

- surface creation
- buffer attach/commit
- output scale and geometry
- keyboard, mouse, scroll, touch
- IME/text input
- clipboard and primary selection
- file drag/drop later
- window activation
- resize/configure events
- fractional scaling
- theme/appearance events

## Package ingestion plan

For x86 Android-like laptops/tablets:

```text
resolve zed + dependency closure
  -> verify package signatures/source hashes
  -> extract usr/lib/zed/zed-editor and desktop metadata
  -> collect runtime shared libraries not provided by Android
  -> patch interpreter/rpath as needed
  -> generate Android package descriptor
  -> build APK
  -> sign APK
```

For GrapheneOS phones/tablets:

```text
build or fetch trusted Linux aarch64 Zed
  -> pair with aarch64 glibc runtime and deps
  -> include bridge launcher and policy descriptor
  -> package as APK
  -> sign APK
```

Do not run host pacman inside GrapheneOS. Pacman belongs in the builder/resolver side, not in the target device's base OS.

Suggested package identity:

```text
Android package: org.archphene.zed
App label: Zed
Version: 1.10.0-1.archphene.N
Signing key: per-app stable key held by LinuxAPK Store
```

## APK shape

Initial self-contained APK:

```text
AndroidManifest.xml
classes.dex
res/mipmap*/zed.png
assets/archphene/descriptor.json
assets/desktop/dev.zed.Zed.desktop
assets/zed/usr-lib-zed/zed-editor
assets/zed/runtime/libc.so.6
assets/zed/runtime/libstdc++.so.6
assets/zed/runtime/libgcc_s.so.1
assets/zed/runtime/libglib-2.0.so.*
assets/zed/runtime/libgio-2.0.so.*
assets/zed/runtime/libfontconfig.so.*
assets/zed/runtime/libsqlite3.so.*
assets/zed/runtime/libwayland-client.so.*
assets/zed/runtime/libxkbcommon.so.*
assets/zed/runtime/bin/git
assets/zed/runtime/bin/node
assets/zed/runtime/bin/npm
assets/zed/runtime/bin/nc
lib/arm64-v8a/archphene_launcher.so
lib/arm64-v8a/archphene_bridge.so
```

Better long-term shape:

```text
org.archphene.runtime.native-rust-gpui
  glibc runtime, GPUI support libs, Wayland bridge client helpers,
  Vulkan/graphics abstraction, font/audio/portal shims

org.archphene.zed
  zed-editor binary, app descriptor, icon, desktop metadata,
  Zed-specific permissions and policy

org.archphene.runtime.dev-tools
  git, node, npm, common shells/tools, language-server helper policy
```

The shared runtime must preserve caller identity. It cannot become a privileged broker that lets one Linux app use another app's permissions.

## Android manifest and permissions

Recommended initial manifest:

```text
package="org.archphene.zed"
uses-permission android.permission.INTERNET
uses-permission android.permission.POST_NOTIFICATIONS
uses-permission android.permission.RECORD_AUDIO optional/conditional
uses-feature android.hardware.camera required=false
uses-feature android.hardware.microphone required=false
activity .ZedActivity launcher, resizable, desktop-mode friendly
provider .ZedDocumentsProvider optional later
service .BridgeService foreground only while app is running
```

Permission mapping:

| Zed feature | Android-visible control | Bridge behavior |
| --- | --- | --- |
| Collaboration, account login, updates, AI providers, extension downloads | Network permission | Linux sockets run under `org.archphene.zed` UID; GrapheneOS Network toggle must disable them |
| Project open | Storage Access Framework or Storage Scopes-like OS feature | User grants project tree; bridge exposes synthetic Linux path |
| Save file | SAF/document mount | Write through granted tree or imported workspace |
| Notifications | Notification permission | Portal notification -> Android NotificationManager |
| Clipboard | Android clipboard policy | Wayland clipboard/primary selection -> Android clipboard when foreground |
| Terminal/tasks/agent tools | No Android runtime permission, but high risk | PTY broker launches app-local shell/tools only |
| Git | No special Android permission | Git binary runs in app namespace; credentials through bridge |
| Account secrets | No direct keychain permission | Freedesktop secrets bridge -> Android Keystore/Credential Manager-backed store |
| Audio/calls/collab | Microphone permission if voice/call features enabled | ALSA/libaudio path -> Android AudioRecord broker |
| Open external URL | Android intent resolver | xdg-open/open URI -> Android ACTION_VIEW |
| Screen capture | MediaProjection prompt | Portal only; no default grant |

Avoid `MANAGE_EXTERNAL_STORAGE`. Zed must not receive whole-device filesystem access by default.

## Bridge contracts Zed needs

### Display

Zed's best path is:

```text
Zed/GPUI -> Wayland socket -> Archphene compositor -> Android Surface
```

Required bridge behavior:

- provide `WAYLAND_DISPLAY`
- provide `XDG_RUNTIME_DIR`
- implement enough xdg-shell for native windows
- map Android task/window resize to Wayland configure events
- map Android density to Wayland output scale/fractional scale
- map keyboard/mouse/touch/scroll to Wayland seat events
- implement text input/IME path
- implement clipboard and primary selection
- support multiple windows later

Compared with VS Code, this is cleaner because the app is not Electron. Compared with simple GTK apps, it is harder because Zed's custom GPUI stack and GPU renderer need the compositor path to be quite correct.

### GPU

GPU is the main technical risk.

Zed expects a Vulkan-capable environment. Arch depends on `vulkan-driver`, `vulkan-icd-loader`, and `vulkan-tools`. Zed's Linux docs say upstream builds work best with a Vulkan-compatible GPU. The observed GPUI Wayland code creates a `BladeContext` GPU context.

Day-one options:

1. Vulkan software path.
   - Use lavapipe/SwiftShader-style software Vulkan.
   - Slow, but safest for initial UI proof.
   - Avoids raw device exposure.

2. Android Vulkan pass-through broker.
   - Expose a controlled Vulkan WSI path mapped to Android surfaces.
   - Harder but realistic for performance.
   - Must preserve Android/GrapheneOS GPU sandboxing and syscall filtering.

3. Patch GPUI backend for Android-native surface rendering.
   - Best long-term if upstreamable or cleanly maintainable.
   - More custom code.

Do not expose raw DRM/KMS or broad `/dev/dri` to the app. That would undermine the Android app security model.

### D-Bus and portals

Zed's source uses `ashpd` for Linux desktop notification behavior in at least the failure path, and the package optionally depends on an `org.freedesktop.secrets` provider for keeping the user logged into their Zed account.

Bridge should provide:

- app-local `DBUS_SESSION_BUS_ADDRESS`
- notification portal
- file chooser/open document portal
- settings/theme portal
- open URI portal
- secret-service bridge
- optional screen-capture portal later

Do not expose a broad global Linux session bus.

### Filesystem and workspaces

Zed is a project editor. It expects normal path semantics and real files.

Tiers:

#### Tier 1: imported workspace

```text
/data/data/org.archphene.zed/files/workspaces/<workspace-id>/
```

User imports/copies a folder. Zed edits the copy. Export/sync writes back.

This is the easiest proof and gives real inotify/file operations.

#### Tier 2: document tree proxy

```text
/home/app/workspaces/<name>/
```

The bridge maps path operations to SAF URI grants.

This is better UX but weaker for file watching and rename semantics.

#### Tier 3: OS-supported FUSE/document mount

The platform mounts granted document trees into the Linux app namespace.

This is the real target for Zed. Language servers, Git, terminal tools, file search, and project indexing all work better when the workspace is path-based.

### File watching and project indexing

Zed needs reliable file watching for a good editor experience.

Recommended:

- v0: app-private imported workspace with real Linux file events.
- v1: bridge best-effort events for SAF-backed workspaces.
- v2: OS-level document/FUSE mount with file event synthesis.

### Terminal and tasks

Zed has a built-in terminal emulator. Its docs describe multiple terminal instances, custom shells, shell environment variables, virtual environment detection, path hyperlinks, and task integration. By default it uses the system shell from `/etc/passwd` on Unix systems.

For GrapheneOS this must be changed:

```text
Zed terminal -> PTY broker -> app-local shell -> app-local tool root
```

The bridge needs:

- `/dev/ptmx` access or PTY emulation
- `bash`/`sh` or BusyBox
- app-private `/etc/passwd` view or patched shell discovery
- app-private `PATH`
- process group and signal handling
- terminal child process cleanup on Android force-stop
- no access to Android host `/`

Tasks should run under the same app UID and only against granted/imported workspaces.

### Git

Arch declares `git` as a direct dependency. Zed has first-class Git integration. The bridge should package Git into either the Zed APK or a developer-tools runtime.

Credential flow:

```text
git credential helper
  -> archphene-credential-helper
  -> Android Keystore/Credential Manager-backed secret bridge
```

SSH flow:

- day one: app-private SSH keys only.
- later: Android-backed key agent with explicit per-key approval.

The Arch dependency on `netcat` is relevant because Zed's CLI source includes an askpass mode that acts like netcat over a Unix socket for SSH/Git password authentication. The bridge should keep that IPC app-local.

### Node.js and npm

Arch declares `nodejs >=18` and `npm` as runtime dependencies.

Unlike VS Code, this does not mean Zed's UI is a Node/Electron app. It means Zed features and extensions/tooling may need Node/npm for language servers, extension workflows, formatters, or JavaScript ecosystem integration.

Bridge policy:

- Node/npm run under Zed's app UID.
- Network use goes through Zed's Android network permission.
- Global npm installs should be redirected to app-private directories.
- Native npm package builds require an explicit developer-tools capability.
- Downloaded executable tooling should be marked as dynamic code/tool execution and surfaced in Settings.

### Extensions

Zed extensions are Git repositories with an `extension.toml` manifest. Official docs say extensions can provide languages, debuggers, themes, icon themes, snippets, and MCP servers.

Procedural extension code is written in Rust and compiled to WebAssembly. Zed docs say language server, context server, and debugger extensions require custom Rust to function, and that extension code is compiled to WebAssembly.

This is much better for security than VS Code's general Node extension host, but not automatically safe:

- extensions can find/download language servers or tools.
- extensions can interact with user PATH.
- debugger extensions launch debuggers.
- MCP/context server extensions can connect to tools/services.
- downloaded binaries can be native executable code.

Day-one policy:

- allow themes, syntax, snippets.
- allow pure WASM extension code.
- block automatic native binary downloads unless user enables developer-tools mode.
- language servers must be packaged or approved per workspace.
- extension downloads require Network permission.

Better policy:

- per-extension identity inside Zed.
- manifest-derived permissions.
- per-extension network/tool/filesystem prompts.
- explicit approval for native helper binaries.

### AI, collaboration, and account login

Zed is designed around collaboration and AI features. The docs surface collaboration, AI providers, agents, MCP, tool permissions, and agent sandboxing as major parts of the product.

For GrapheneOS-style controls:

- account login tokens go through secret-service bridge to Android-backed storage.
- AI provider calls require Network permission.
- agent tool execution is developer-tools capability, not baseline editor capability.
- MCP servers should run as app-local processes with explicit approval.
- local model integrations should not get broad filesystem/device access.

The bridge can expose these as Android-facing toggles:

```text
Network
Notifications
Workspace files
Terminal/tools
Native helper downloads
AI/agent tool execution
Microphone/calls
Screen capture
```

## Sandbox implications

Zed avoids Electron/Chromium sandbox complexity. That is a major advantage.

However, Zed still needs sandbox decisions for:

- native Rust process
- GPU driver attack surface
- glibc/native shared libraries
- Git subprocesses
- Node/npm subprocesses
- shell/terminal subprocesses
- language servers
- debugger adapters
- extension WASM
- downloaded native tooling
- AI agent tools

GrapheneOS-grade support should use:

- per-app Android UID
- per-app SELinux domain/type or a dedicated `linux_app` domain
- app-private mount namespace
- seccomp profile for Linux apps
- no raw device access
- portal-mediated resource access
- dynamic code/tool execution toggle
- native debugging/ptrace policy
- app lifecycle process tree management

Compared with VS Code, Zed's core editor is a better security base. The risk shifts from browser-engine sandboxing to GPU/native-tooling/tool-execution boundaries.

## Android lifecycle mapping

Android events:

```text
launcher tap
task resize
external display attach/detach
app background
app foreground
force stop
permission revoke
low memory
uninstall
```

Bridge behavior:

- launch: create Android Activity, Surface, Wayland server, app namespace, then start `zed-editor`.
- resize: send Wayland configure/output changes.
- background: keep editor paused; keep terminal/tasks only if user allows foreground service.
- force stop: kill Zed and full subprocess tree.
- permission revoke: close portals, invalidate workspace mounts where possible, return normal Linux errors.
- low memory: ask app to save state, then terminate language servers/terminals before killing main app.
- uninstall: Android removes app-private state; user-owned project files remain.

## Day-one runnable plan for Zed

### v0: render and edit app-private file

- Use Linux `aarch64` Zed build.
- Package Zed and required shared libraries into one APK.
- Use app-private HOME/XDG paths.
- Start Android Activity with Surface.
- Start app-local Wayland compositor.
- Launch Zed with `WAYLAND_DISPLAY`.
- Use software Vulkan first.
- Disable terminal, extension installs, collaboration, AI, and debugger.
- Open app-private sample workspace.

Success: Zed window renders and edits a local sample file.

### v1: useful editor

- Add SAF import/export.
- Add clipboard.
- Add notification portal.
- Add network permission.
- Add Git read/status support in imported workspace.
- Enable syntax/theme/snippet extensions.
- Add reliable app-private file watching.

Success: user can import a project, edit, search, use Git status, and save/export.

### v2: developer-useful

- Add PTY broker.
- Add packaged shell/Git/SSH/basic tools.
- Add credential bridge.
- Add Node/npm in app-private tool root.
- Add language server policy.
- Add document-tree/FUSE workspace access.

Success: user can clone, edit, run tasks, use terminal, run language servers, commit, and push.

### v3: GrapheneOS-grade

- Dedicated SELinux/seccomp profile for Linux apps.
- Enforced mount namespace.
- Per-extension/tool permissions.
- Dynamic native helper toggle.
- GPU path designed around Android/GrapheneOS sandboxing.
- Settings UI exposes Linux app permissions.
- Agent/tool execution is clearly separated from baseline editor permissions.

Success: Zed behaves as a first-class Android desktop-mode app with a defensible security model.

## Feasibility verdict

Zed is more feasible than VS Code as an early serious editor target.

Reasons:

- no Electron
- no bundled Chromium/V8 UI engine
- no core Node renderer/main process model
- smaller package surface
- Rust-native core
- upstream Linux aarch64 builds exist
- extensions are WASM-oriented rather than arbitrary Node-first extension hosts

Main blockers:

- Vulkan/GPUI rendering on Android without exposing unsafe device access
- Wayland compositor fidelity for GPUI
- glibc/native dependency root on Android
- path-based workspace access over Android scoped storage
- terminal/tasks without host escape
- Git/SSH credential handling
- Node/npm and native tool downloads
- AI agent/tool execution permissions

Recommendation: use Zed as the first "real developer editor" milestone after proving the generic bridge with smaller apps. It is a better target than VS Code for showing that the bridge can run a modern editor securely, but it still should not be the first app used to validate the bridge.

## Source map

- Arch `zed` package: https://archlinux.org/packages/extra/x86_64/zed/
- Arch `zed` file list: https://archlinux.org/packages/extra/x86_64/zed/files/
- Zed repository: https://github.com/zed-industries/zed
- Zed Linux install docs: https://zed.dev/docs/linux
- Zed main source: https://raw.githubusercontent.com/zed-industries/zed/main/crates/zed/src/main.rs
- Zed GPUI Wayland source: https://raw.githubusercontent.com/zed-industries/zed/main/crates/gpui/src/platform/linux/wayland/client.rs
- Zed extension development docs: https://zed.dev/docs/extensions/developing-extensions
- Zed terminal docs: https://zed.dev/docs/terminal
- Zed Git docs: https://zed.dev/docs/git
- GrapheneOS features overview: https://grapheneos.org/features
- Android app sandbox: https://source.android.com/docs/security/app-sandbox
- Android runtime permissions: https://developer.android.com/training/permissions/requesting
