# Terminal applications

Archphene provides a first-party **Archphene Terminal** companion for command-line and TUI packages. It is a native Android terminal surface with a real PTY, not a VM and not a desktop terminal running through Wayland. Users install only the Archphene manager; the manager embeds the same-release-signed Terminal APK and installs or updates it through Android PackageInstaller when Terminal is first opened.

## Architecture

- The manager and Terminal have separate Android package names and UIDs. Linux child processes cannot read or modify manager-private package state.
- A signature-protected manager activity accepts `pacman` requests from the companion. The exported manager launcher ignores terminal request extras.
- A read-only content provider exposes only runtime packs recorded as Terminal-managed and only to a companion signed with the manager release certificate.
- Terminal copies each command, loader, library, and data archive into its own sandbox, verifies the manager-declared size and SHA-256, rejects malformed catalogs and command collisions, and marks materialized runtime files read-only.
- The Apache-2.0 Termux `terminal-emulator` and `terminal-view` modules provide VT/ANSI rendering, hardware-key input, Android IME input, selection, and resize handling.
- A Bionic JNI PTY host starts `/system/bin/sh` and owns a process group for the activity-scoped session.
- Each Arch command runs through the patched glibc loader with only its resolved library closure and package data root.
- Home, `.config`, and `.cache` persist under the Terminal UID. GUI wrappers remain separate Android UIDs.
- CLI packages do not create launcher APKs. A package with a usable `.desktop` entry is handled as a GUI wrapper; CLI/TUI packages are exposed in Terminal; dependency-only packages create neither launcher nor command.

Installing btop does not implicitly install curl. Commands become available only when their source package is explicitly installed. Multiple commands from one source package are exposed together.

## Pacman compatibility

`pacman` in Terminal is an Archphene facade. It never mutates manager state directly.

| Command | Behavior |
| --- | --- |
| `pacman -Q`, `-Qi`, `-Qs` | Query the locally managed Terminal package set. |
| `pacman -Ss <name>` | Open a prefilled official-repository search in Archphene. |
| `pacman -S <name>` | Open the package for user review and installation. |
| `pacman -R <name>` | Open the installed package removal screen. |
| `pacman -Syu` | Check installed packages; pinned versions remain unchanged. |

Resolve, signature verification, runtime-pack construction, and errors continue through the manager job model and visible per-package progress. One package failure does not stop independent jobs.

## Android capability boundary

The Terminal companion uses an ordinary Android application UID. It does not receive root or platform-signature permissions.

- Android denies ordinary apps access to files such as `/proc/stat`. btop reaches its real Linux collector but cannot display unrestricted device-wide telemetry without a privileged broker, root/ADB delegation, or OS support.
- Archphene does not synthesize misleading telemetry or bypass Android policy. A future broker may expose metrics available through public Android APIs and must label the restricted view.
- Android permissions are requested only by explicit bridge actions. A Linux syscall cannot directly trigger an Android runtime permission prompt.
- User-visible files cross Storage Access Framework or document-provider grants. App-private background files remain in the Terminal sandbox.
- Persistent background sessions require a foreground service and notification. The current session is terminated as a process group when its activity is destroyed.

## Remaining terminal work

1. Add multiple sessions/tabs and a foreground-service lifecycle.
2. Add brokered Documents/Downloads commands and explicit permission UX.
3. Allow an installed Arch shell such as bash to replace the Bionic bootstrap shell.
4. Stream manager job progress back to the invoking terminal command.
5. Add capability metadata for packages requiring restricted `/proc`, devices, sockets, or Android bridge APIs.
6. Build the companion and runtime for arm64 and validate on the physical Samsung test device.

Kitty is not the default frontend because it is itself a GPU-accelerated Wayland application and would add compositor and GPU dependencies before displaying a shell or TUI.

## References

- [Termux application and terminal modules](https://github.com/termux/termux-app)
- [Android native ABIs](https://developer.android.com/ndk/guides/abis)
- [Android background execution limits](https://developer.android.com/topic/performance/power/power-details)
- [Arch Linux package database](https://archlinux.org/packages/)
- [btop source](https://github.com/aristocratos/btop)