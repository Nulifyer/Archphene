#!/usr/bin/env python3

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *markers: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise SystemExit(f"{path}: missing graphics diagnostic markers: {missing}")


require(
    "native/archphene-compositor/src/lib.rs",
    "shm_snapshot_count",
    "cpu_conversions",
    "GPU readback, texture upload, and GPU composition are explicit",
    "ahb_submissions",
    "surfaceflinger_releases",
    "const GRAPHICS_DIAGNOSTIC_COMPONENTS: usize = 7;",
)
require(
    "android/app/src/main/kotlin/org/archphene/app/launcher/NativeLauncherCompositor.kt",
    "const val PRESENTATION_COMPONENTS = 42",
)
require(
    "android/app/src/main/kotlin/org/archphene/app/launcher/LauncherSessionService.kt",
    "graphics=shmSnapshot:",
    "cpuConversion:",
    "gpuReadback:",
    "textureUpload:",
    "gpuComposition:",
    "directAhbSubmit:",
    "surfaceFlingerRelease:",
)

print("Graphics path diagnostics contract passed")
