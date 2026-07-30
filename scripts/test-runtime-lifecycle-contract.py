#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "docs/runtime-lifecycle.md"


def require(path: Path, *values: str) -> None:
    text = path.read_text(encoding="utf-8")
    for value in values:
        if value not in text:
            raise SystemExit(f"runtime lifecycle contract missing from {path}: {value}")


require(
    POLICY,
    "## Ownership and process groups",
    "## Foreground and background behavior",
    "## Resource policy",
    "## Close and shutdown",
    "## Crash and mutation recovery",
    "at most 16 graphical sessions and four manager PTYs",
    "installing a package alone never",
    "START_NOT_STICKY",
)
require(ROOT / "docs/README.md", "[Linux process and lifecycle policy](runtime-lifecycle.md)")
require(ROOT / "docs/architecture.md", "[Linux process and lifecycle policy](runtime-lifecycle.md)")
require(ROOT / "docs/platform-compatibility.md", "[lifecycle policy](runtime-lifecycle.md)")

process = ROOT / "crates/archphene-process/src/lib.rs"
require(
    process,
    "pub const MAX_PTY_SESSIONS: usize = 4;",
    "pub const MAX_GUI_SESSIONS: usize = 16;",
    "pub const MAX_GUI_LOG_BYTES: usize = 16 * 1024;",
    "const GUI_CLOSE_GRACE: Duration = Duration::from_millis(750);",
    "const GUI_TERMINATE_GRACE: Duration = Duration::from_millis(750);",
    "const MAX_PROCESSES_SCANNED: usize = 8192;",
    "const MAX_DESCENDANT_PROCESSES: usize = 512;",
    "terminate_process_group",
    "signal_process_group(self.process_group, 15)",
)

service = (
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/runtime"
    / "ArchpheneRuntimeService.kt"
)
require(
    service,
    "private val launcherProcessHandles = LongArray(MAX_TRACKED_LAUNCHER_PROCESSES)",
    "private const val MAX_TRACKED_LAUNCHER_PROCESSES = 16",
    "trackLauncherProcess(launcherHandle)",
    "promoteSessionToForeground()",
    "launcherProcessCount > 0 || hasForegroundWork()",
    "launcherProcessCount > 0 ||",
    "return START_NOT_STICKY",
    "private const val RUNTIME_SHUTDOWN_WORKER_WAIT_MILLIS = 3_000L",
    "NativeRuntime.nativeTransition(activeHandle, NativeRuntime.LIFECYCLE_STOPPING)",
    "NativeRuntime.nativeTransition(activeHandle, NativeRuntime.LIFECYCLE_STOPPED)",
)
require(
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/launcher"
    / "LauncherSessionService.kt",
    "override fun binderDied()",
    "removeSession(sessionId)",
    "compositor.requestClose()",
    "runtime?.closeLauncherProcess(linuxHandle)",
)
require(
    ROOT / "android/app/src/main/AndroidManifest.xml",
    'android:foregroundServiceType="specialUse"',
    'android:stopWithTask="false"',
)
require(
    ROOT / "crates/archphene-jobs/src/lib.rs",
    "Interrupted during package mutation; repair is required",
    "Interrupted before package mutation; retry is required",
)

print(
    "Runtime lifecycle contract passed: fixed supervision, GUI foreground "
    "retention, bounded teardown, and explicit recovery policy are present."
)
