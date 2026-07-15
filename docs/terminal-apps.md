# Terminal applications

The current prototype provides one first-party **Archphene Terminal** launcher inside the manager APK. It is a native Android terminal surface, not a VM and not a desktop terminal running through Wayland. Before public release, process execution must move to a separately signed Terminal package and Android UID so Linux children cannot modify manager-private state.

## Current architecture

- The Apache-2.0 Termux `terminal-emulator` and `terminal-view` modules provide VT/ANSI rendering, hardware-key input, Android IME input, selection, and resize handling.
- A Bionic JNI PTY host starts `/system/bin/sh` and owns a process group for the session.
- Verified Arch command packs are materialized as immutable, pack-specific command and library views under the manager UID.
- Each command runs through the patched Arch glibc loader with only its resolved library closure and data root.
- Home, `.config`, and `.cache` are retained in the shared Terminal sandbox. GUI wrappers remain separate Android UIDs.
- The launcher is a second Android activity and app-drawer entry in the Archphene APK. CLI packages do not create launcher APKs.

Installing btop does not implicitly install curl. Commands become available only when their source package is explicitly installed. Multiple commands from one source package are exposed together. Conflicting command names are rejected rather than silently selecting one package.

## Pacman compatibility

`pacman` in Terminal is an Archphene facade. It never mutates the manager package database directly.

| Command | Behavior |
| --- | --- |
| `pacman -Q`, `-Qi`, `-Qs` | Query the locally managed Terminal package set. |
| `pacman -Ss <name>` | Open a prefilled official-repository search in Archphene. |
| `pacman -S <name>` | Open the package for user review and installation. |
| `pacman -R <name>` | Open the installed package removal screen. |
| `pacman -Syu` | Check installed packages; pinned versions remain unchanged. |

Resolve, signature verification, runtime-pack construction, and errors continue through the manager job model and its visible per-package progress. Linux children cannot bypass Android install confirmation or runtime permissions.

## Android capability boundary

The Terminal app uses an ordinary Android application UID. It does not receive root or platform-signature permissions.

- Android denies an ordinary app access to system-wide files such as `/proc/stat`. A system monitor such as btop reaches its real collector but cannot display unrestricted system CPU/process telemetry without a privileged broker, root/ADB delegation, or OS support.
- Archphene does not synthesize misleading system telemetry and does not bypass this policy. A future broker may expose metrics available through public Android APIs and must label that restricted view explicitly.
- Android permissions are requested only by explicit bridge actions. A Linux syscall does not directly trigger an Android permission prompt.
- User-visible files cross Storage Access Framework or document-provider grants. App-private background files remain in the Terminal sandbox.
- Persistent background sessions still require a foreground service and notification; the current activity-scoped session is terminated as a process group when its activity is destroyed.

## Remaining terminal work

1. Move PTY execution and Terminal home into a separately signed Terminal APK/UID with read-only runtime grants from the manager.
2. Add multiple sessions/tabs and a foreground-service lifecycle.
3. Add brokered Documents/Downloads commands and explicit permission UX.
4. Add shell-package selection so an installed Arch `bash` can replace the Bionic bootstrap shell.
5. Stream manager job progress back into the invoking terminal command.
6. Add capability metadata for packages that require privileged `/proc`, devices, sockets, or Android bridge APIs.
7. Build and release the PTY/runtime for arm64 and validate on a physical GrapheneOS-compatible device.

Kitty is not the default frontend because it is itself a GPU-accelerated Wayland application and would add compositor and GPU dependencies before it could display a shell or TUI.

## References

- [Termux application and terminal modules](https://github.com/termux/termux-app)
- [Android native ABIs](https://developer.android.com/ndk/guides/abis)
- [Android background execution limits](https://developer.android.com/topic/performance/power/power-details)
- [Arch Linux package database](https://archlinux.org/packages/)
- [btop source](https://github.com/aristocratos/btop)