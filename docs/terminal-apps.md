# Terminal applications

Terminal packages need a terminal frontend and a Linux process environment. Kitty is not the default solution: it is itself a GPU-accelerated Wayland desktop application and would add compositor/GPU requirements before it can display btop, curl, or a shell.

## Product model

Archphene should provide one first-party **Terminal** Android application with:

- a native Android terminal view and keyboard/IME integration;
- a PTY connected to an Arch glibc shell;
- manager-controlled package installation into that terminal environment;
- private writable home/config/cache state under the Terminal Android UID;
- brokered Android Documents/Downloads access;
- tabs or sessions, process status, and explicit stop controls.

The Termux terminal-view and terminal-emulator modules are useful reference implementations and potential dependencies, subject to a deliberate license and maintenance review. Archphene should not adopt Termux's package repository/runtime because the goal is to execute the same verified Arch package model used by GUI wrappers.

## Package visibility

A command is available only when it is installed in that Terminal environment or included in an installed package's dependency closure. Installing btop does not implicitly install unrelated commands such as curl.

The manager can offer:

1. **Terminal environment packages** for shells, CLI tools, compilers, and TUI programs. These commands share the Terminal Android UID and its granted permissions.
2. **Dedicated terminal launchers** for a user-facing TUI such as btop. The launcher opens the command in the Terminal frontend. A strict-isolation mode can instead generate a separate wrapper/UID and package closure.

The shared Terminal environment is the better default Android experience because pipelines, shells, PATH, dotfiles, and developer tools need to work together. The UI must state that packages installed into it share one Android sandbox. GUI applications remain separate APK identities and UIDs.

## Security and lifecycle

- No root, VM, or Android permission bypass.
- PTY child processes remain descendants of the Terminal app and are terminated through tracked process groups.
- Android permissions are requested by explicit Terminal bridge actions, not by arbitrary Linux syscalls.
- User-visible files cross Storage Access Framework/document-provider grants.
- Background sessions require an Android foreground service and visible notification; normal background limits still apply.
- Package hooks run under a restricted policy and cannot write Android system locations.

## Implementation order

1. Finish the shared native compositor and package-job model.
2. Add a PTY JNI probe using the patched glibc runtime and an Android terminal renderer.
3. Prove shell input, resize, Unicode, colors, alternate screen, mouse reporting, and btop.
4. Add manager package installation into the Terminal environment.
5. Add document brokering, foreground-session lifecycle, tabs, and dedicated TUI launchers.

## References

- [Termux application and terminal modules](https://github.com/termux/termux-app)
- [Termux library separation](https://github.com/termux/termux-app/wiki/Termux-Libraries)
- [Kitty command-line and Wayland behavior](https://sw.kovidgoyal.net/kitty/invocation.html)
- [Android native ABIs](https://developer.android.com/ndk/guides/abis)
- [Android background execution limits](https://developer.android.com/topic/performance/power/power-details)
- [Arch Linux package database](https://archlinux.org/packages/)
