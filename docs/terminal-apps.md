# Terminal applications

Archphene provides a first-party **Archphene Terminal** companion for command-line and TUI packages. It is a native Android terminal surface with a real PTY, not a VM and not a desktop terminal running through Wayland. Users install only the Archphene manager; the manager embeds the same-release-signed Terminal APK and installs or updates it through Android PackageInstaller when Terminal is first opened.

## Architecture

- The manager and Terminal have separate Android package names and UIDs. Linux child processes cannot read or modify manager-private package state.
- A signature-protected manager activity accepts `pacman` requests from the companion. The exported manager launcher ignores terminal request extras.
- A read-only content provider exposes only runtime packs recorded as Terminal-managed and only to a companion signed with the manager release certificate.
- Terminal copies each command, library, and data archive into its own sandbox, verifies the manager-declared size and SHA-256, rejects malformed catalogs and command collisions, and marks materialized runtime files read-only. The glibc loader is verified against the same catalog but executes from Terminal's APK-owned native library directory because modern Android forbids executing downloaded code from writable app data.
- The Apache-2.0 Termux `terminal-emulator` and `terminal-view` modules provide VT/ANSI rendering, hardware-key input, Android IME input, selection, and resize handling.
- Universal development builds carry both PTY libraries and APK-owned glibc loaders; release manager APKs embed a single-ABI Terminal companion matching x86_64 or arm64-v8a. Android selected arm64-v8a on the Samsung Galaxy S22 Ultra, launched a real PTY shell, accepted input, exposed its home through DocumentsUI, preserved the shell through rotation, and executed managed `btop 1.4.7`. The 16 KB x86_64 emulator returns a correlated compatibility error because upstream Arch x86_64 glibc is currently 4 KB-only.
- A Bionic JNI PTY host starts the generated shell launcher. It uses `/system/bin/sh` until a verified Arch Bash runtime is installed, then selects managed Bash on the next session. Each shell owns a process group and requests a parent-death signal from Android's app process.
- Each Arch command runs through the patched glibc loader with only its resolved library closure and package data root.
- Home, `.config`, and `.cache` persist under the Terminal UID. GUI wrappers remain separate Android UIDs.
- CLI packages do not create launcher APKs. A package with a usable `.desktop` entry is handled as a GUI wrapper; CLI/TUI packages are exposed in Terminal; dependency-only packages create neither launcher nor command.

Installing btop does not implicitly install curl. Commands become available only when their source package is explicitly installed. Multiple commands from one source package are exposed together. Explicitly installed dependency-only packages contribute their verified libraries and toolkit data to managed commands without creating commands or Android launchers. On the x86_64 emulator, installing `vulkan-swrast` independently makes the existing `vulkaninfo` command enumerate llvmpipe; confirmed removal withdraws the ICD from the next prepared Terminal environment.

Installing `bash` through Archphene publishes its verified runtime closure to Terminal and makes it the default user shell for subsequent sessions. The launcher scopes the patched glibc loader, library path, path bridge, and `C.UTF-8` locale root to managed commands; it clears those variables before invoking Android's Bionic utilities. Fish remains a possible future opt-in shell rather than a release dependency.

## Pacman compatibility

`pacman` in Terminal is an Archphene facade. It never mutates manager state directly.

| Command | Behavior |
| --- | --- |
| `pacman -Q`, `-Qi`, `-Qs` | Query the locally managed Terminal package set. |
| `pacman -Ss <name>` | Search official repositories in Archphene and return the compatible result count. |
| `pacman -S <name>` | Resolve one exact compatible package, run the normal verified manager transaction, and stream its phase/result. |
| `pacman -R <name>` | Request confirmation, remove one Terminal package, and return success or cancellation. |
| `pacman -Syu` | Start an installed-package update check; pinned versions remain unchanged. |
| `archphene-import [home-directory]` | Choose an Android document and copy it into visible Terminal home storage. The default destination is the home Downloads directory. |
| `archphene-export <home-file>` | Choose an Android save location and copy a visible Terminal home file through a scoped URI grant. |
| `archphene-project add <alias>` | Choose an Android folder once, persist its read/write grant, and create `$HOME/Projects/<alias>`. |
| `archphene-project sync <alias>` | Synchronize the local POSIX mirror and its granted Android folder without another prompt. |
| `archphene-project list`, `path <alias>` | List mappings and revoked-grant state, or print an active project's stable Terminal path. |
| `archphene-project remove <alias>` | Release the persisted grant and remove the mapping while retaining local files. |

Imports use collision-safe names and publish only after a bounded copy completes. Exports require one visible regular file. Both operations reject dot-directories and paths outside Terminal home; neither requests broad storage access.

Android's Storage Access Framework exposes document URIs, not a mountable POSIX directory. Project mappings therefore use a bridge-managed local mirror. Explicit sync supports ordinary background Linux file access, nested directories, dotfiles, and process-restart persistence. It bounds each side to 10,000 entries and 2 GiB, rejects symlinks and path escapes, preserves simultaneous Android edits as content-hash-suffixed `.android-conflict-*` files, and defers deletions instead of risking silent data loss. External changes become visible after sync; this is not a live FUSE mount.

Each shell request has a bounded unique identifier and atomically published request/response files, so tabbed sessions cannot overwrite each other. The manager correlates install jobs with that identifier and streams resolve, download, install, complete, cancellation, and error states through a provider protected by the release-signature permission and an explicit manager UID/package check. Correlation survives manager job recovery. One package failure does not stop independent jobs.

## Android capability boundary

The Terminal companion uses an ordinary Android application UID. It does not receive root or platform-signature permissions.

- Android denies ordinary apps access to files such as `/proc/stat`. btop reaches its real Linux collector but cannot display unrestricted device-wide telemetry without a privileged broker, root/ADB delegation, or OS support.
- Archphene does not synthesize misleading telemetry or bypass Android policy. A future broker may expose metrics available through public Android APIs and must label the restricted view.
- Android permissions are requested only by explicit bridge actions. A Linux syscall cannot directly trigger an Android runtime permission prompt.
- The Terminal UID exposes visible files in its Linux home as **Archphene Home** through an Android `DocumentsProvider`. Android Files, document pickers, and share targets can receive URI grants without broad storage permission.
- Dotfiles such as `.config` and `.cache`, runtime packs, and manager state remain hidden and app-private. Linux commands can use visible home paths without a prompt; Android apps reach them only through Android's document grant model.
- External Downloads and arbitrary project folders are not raw paths in the sandbox. Import/export and persisted tree grants require explicit Storage Access Framework bridge actions.
- Up to eight tabbed sessions are owned by a `specialUse` foreground service with a visible stop action. Closing the Activity preserves PTYs; closing a tab kills only its process group; stopping the notification service or losing the Android app process removes all sessions.
- Handled phone, tablet, docked-display, density, font-scale, and dark/light configuration changes relayout in place without replacing the PTY. The emulator matrix preserved one app PID and shell PID across 1080x2400, 2400x1080, 1280x1920, 1920x1280, and 1920x1080 viewports.

## Remaining terminal work

1. Add capability metadata for packages requiring restricted `/proc`, devices, sockets, or Android bridge APIs.
2. Evaluate an opt-in automatic project-sync policy with explicit conflict and battery/network controls.
3. Evaluate optional user-selectable shells after the Bash lifecycle and package compatibility matrix are stable.

Kitty is not the default frontend because it is itself a GPU-accelerated Wayland application and would add compositor and GPU dependencies before displaying a shell or TUI.

## References

- [Termux application and terminal modules](https://github.com/termux/termux-app)
- [Android native ABIs](https://developer.android.com/ndk/guides/abis)
- [Android background execution limits](https://developer.android.com/topic/performance/power/power-details)
- [Android shared documents and persisted directory access](https://developer.android.com/training/data-storage/shared/documents-files)
- [Arch Linux package database](https://archlinux.org/packages/)
- [btop source](https://github.com/aristocratos/btop)
