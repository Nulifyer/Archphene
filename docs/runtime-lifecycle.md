# Linux process and lifecycle policy

Updated: 2026-07-29

This document defines how Archphene supervises Linux applications, commands,
shells, build work, and helper processes within Android's application
lifecycle. It is a product contract, not a promise that an ordinary Android
application can keep processes alive after Android kills or force-stops its UID.

## Ownership and process groups

The Archphene manager owns every Linux process in the shared Arch root.
Generated launcher APKs supply an authenticated Android surface and input; they
never own or fork the Linux application.

Each graphical application, terminal session, and bounded batch command starts
in a dedicated process group. The manager retains the group after its original
leader exits so ordinary child processes such as editor extension hosts,
language servers, debuggers, terminals, and GUI helpers remain part of the
session. Process handles are generation-checked and stored in fixed registries:
at most 16 graphical sessions and four manager PTYs.

Linux applications may start normal descendants, but arbitrary package daemons
do not receive boot-time or system-service semantics. A daemon belongs to the
user-started session or bounded operation that created it and must not escape
that lifecycle group. Any future shared background service requires an
explicit Archphene adapter, visible user control, bounded capability policy,
and Android foreground-service treatment; installing a package alone never
enables one.

## Foreground and background behavior

While a graphical Linux process, shared shell, package operation, command, or
file operation is active, the manager runs as a low-priority Android foreground
service with a visible Linux-session/work notification. Graphical process
handles are kept in a fixed 16-slot array, and the notification remains until
the final handle closes. This makes backgrounded VS Code and its descendants
eligible for Android's supported user-visible long-running execution model.

Pressing Home or temporarily losing the launcher surface detaches presentation,
clipboard, and input while retaining the authenticated session and Linux
process. Returning reattaches a current surface. Back, explicit close, launcher
Binder death, manager shutdown, or the last owning session's removal closes the
Linux process group.

This improves retention but does not override Android. Force stop, reboot, an
OS kill, or device policy can end the complete Archphene UID. Archphene does not
silently restart interactive applications or commands after such an event.
Relaunch starts a new process against the same persistent Linux home and
installed system.

## Resource policy

Archphene applies bounded application-level resources rather than pretending
to provide per-package containers:

- 16 graphical sessions and four manager PTYs;
- bounded command names, arguments, request bytes, Wayland display names,
  input queues, clipboard data, and direct buffers;
- fixed 16 KiB graphical log tails, bounded batch logs and diagnostics, and
  nonblocking incremental drains;
- bounded process-tree inspection of at most 8,192 `/proc` entries and 512
  retained descendants;
- timeouts for noninteractive direct and batch work, with interactive terminal
  and graphical work controlled by their owning sessions;
- bounded build parallelism derived for the device rather than unbounded
  `make`, Cargo, CMake, or Ninja workers.

All installed Linux programs intentionally share one Android UID and Linux
trust domain, so Android cannot honestly enforce independent per-package
memory, CPU, network, or filesystem quotas. Android's UID cgroups, memory
pressure, thermal policy, and process lifecycle remain the outer resource
authority. Archphene applies backpressure or an actionable busy/failure result
when its fixed internal limits are reached.

## Close and shutdown

A normal graphical close first sends the Wayland toplevel close request so the
application can save state. The manager then waits up to 750 ms, sends
`SIGTERM`, waits another 750 ms, and finally kills the process group. Terminal
shutdown additionally captures a bounded descendant tree with PID start times
before killing, preventing a foreground job in another process group from
being mistaken for a reused PID or left behind.

Service destruction immediately invalidates public native handles, disconnects
network work, requests cancellation, wakes blocked PTYs, interrupts workers,
and drains distinct worker threads for one shared three-second deadline off the
Android main thread. Rust lifecycle state then advances through stopping and
stopped before the runtime and all remaining fixed registries are dropped.

## Crash and mutation recovery

Interactive process memory is ephemeral. User files, package state, settings,
launcher registry, and independently completed content-addressed outputs are
persistent.

Package and launcher mutations cross external state boundaries, so they use
durable intent/phase journals. Startup distinguishes interruption before a
mutation from interruption during one; the former can be retried and the latter
requires the visible Repair or Roll back path. AUR recipe processes are never
resumed, but a completely published verified output or graph prefix may be
reattached only after full revalidation. File synchronization and export use
their own bounded recovery records and remove or reconcile incomplete state.

The service is `START_NOT_STICKY` and Archphene has no boot receiver for Linux
processes. Recovery is explicit when the user next opens Archphene or a
generated launcher.
