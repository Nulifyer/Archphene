# Terminal applications

Archphene includes a native Android Terminal surface in the manager. It runs a
real PTY-backed shell in the same private Arch Linux environment used by
graphical applications. It is not a VM, a Wayland desktop terminal, or a
separate Android companion APK.

## Architecture

- The manager owns the PTY, shell process group, terminal parser, scrollback,
  selection, clipboard protocol, and teardown under its ordinary Android UID.
- Terminal and graphical Linux applications share one Arch root, package
  database, home, configuration, and Linux trust domain. They do not duplicate
  package closures or user files.
- Rust starts only bounded, contained executable paths, owns process groups and
  descriptors, and publishes terminal damage through reusable direct buffers.
  Kotlin renders the native Android surface and handles touch, hardware keys,
  IME, clipboard, accessibility, configuration changes, and foreground-service
  lifecycle.
- Terminal remains unavailable until Bash or another supported shell is
  installed through the verified package path. New sessions can then select it
  from the bounded shell list.
- CLI and TUI packages can expose reviewed commands in Terminal. They do not
  create Android app-shell packages. A graphical desktop entry remains subject
  to app-shell compatibility review and explicit **Add to Android** publication.
- Terminal text uses the checksum-pinned no-ligature JetBrains Mono Nerd Font
  face and packages its OFL-1.1 license. It does not depend on an OEM-specific
  Android `monospace` alias.

The release manager APK contains the exact-ABI PTY/runtime components and font.
There is no Terminal APK to install, sign, update, or grant access separately.
The historical `v1.0.1` prototype used an isolated Terminal companion; that
architecture is retained only as release history.

## Interaction and rendering

The Terminal uses a compact two-line prompt on portrait displays. The active
prompt places the current directory above a separate `$` input line and
abbreviates intermediate directories while keeping the final directory
readable. OSC 133 markers let completed commands retain only the submitted
command and output. IME-only height changes preserve prompt state.

Text follows Android's scaled 16sp baseline in Auto mode. Pinch zoom, the
long-press menu, and hardware shortcuts select a bounded 10sp–32sp size;
`Ctrl+0` restores Auto. The renderer preserves combining text, CJK width,
regional-indicator flags, emoji modifiers, ZWJ families, indexed colors, direct
RGB, style attributes, and wide-cell invariants across resize and scrollback.

Input supports Android IME preedit/commit, hardware modifiers and function keys,
bracketed paste, touch selection with draggable endpoints, scrollback, mouse
reporting, and accessibility navigation. Terminal protocol parsing and replies
are bounded; malformed or overlong escape sequences fail closed.

## Packages, files, and projects

Package mutations remain manager-owned durable jobs. Use the Packages page for
official or reviewed AUR installation, update, repair, and removal. Newly
installed verified commands become available to subsequent Terminal sessions
through the same shared root; existing processes are not silently restarted.

The Files page and Android Storage Access Framework provide the supported
external-file boundary:

- **Archphene Home** exposes ordinary visible home files through the
  manager-owned `DocumentsProvider` while keeping dotfiles and runtime state
  private.
- Import and Export copy bounded regular files through explicit Android URI
  grants.
- Connected project folders use explicit persisted tree grants and a private
  POSIX mirror under `$HOME/Projects`. Synchronization is explicit,
  checksum-based, bounded, and conflict-preserving; it is not a FUSE mount.
- Open and Share hand one validated regular file to Android through a scoped
  read-only URI grant.

These bridges do not grant Linux processes raw access to Android storage paths.

## Android capability boundary

Terminal processes remain inside the manager's ordinary Android UID and SELinux
sandbox. They receive no root, platform signature, mount, device-node, or broad
storage privilege.

- The path bridge exposes reliable self-process, CPU-topology, safe-device, and
  private shared-memory paths while filtering Android-wide process visibility.
- Android-denied global telemetry such as `/proc/stat` remains unavailable.
  Archphene does not synthesize misleading host data or bypass Android policy.
- Android permissions are requested only by explicit manager bridge actions. A
  Linux syscall cannot directly trigger a runtime-permission prompt.
- Home, Activity recreation, rotation, and supported configuration changes
  retain the foreground shell. Stopping the Terminal session or manager
  foreground service terminates and reaps its owned process group.

## Validated boundary

Current exact-ABI manager builds pass PTY startup, Bash selection, Unicode IME,
hardware input, selection, clipboard and bracketed paste, scrollback, resize,
font scaling, terminal protocol replies, Home/resume, graceful stop, process
reaping, fatal-log checks, and full-device visual inspection on the API 36
x86_64 emulator and Android 15 AArch64 Samsung. This evidence does not establish
GrapheneOS, physical x86_64, DeX, or unrestricted Linux host telemetry support.

## References

- [Android native ABIs](https://developer.android.com/ndk/guides/abis)
- [Android background execution limits](https://developer.android.com/topic/performance/power/power-details)
- [Android shared documents](https://developer.android.com/training/data-storage/shared/documents-files)
- [Arch Linux package database](https://archlinux.org/packages/)
