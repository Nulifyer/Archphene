#!/usr/bin/env python3

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *markers: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise SystemExit(f"{path}: missing graphics diagnostic markers: {missing}")


def reject(path: str, *markers: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    present = [marker for marker in markers if marker in text]
    if present:
        raise SystemExit(f"{path}: forbidden graphics path markers: {present}")


require(
    "native/archphene-compositor/src/lib.rs",
    "shm_snapshot_count",
    "cpu_conversions",
    "texture_uploads",
    "gpu_compositions",
    "No production path reads GPU output back to the CPU.",
    "GpuRendererState::Unavailable",
    "None => match window.with_locked_rgba",
    "ahb_submissions",
    "surfaceflinger_releases",
    "const GRAPHICS_DIAGNOSTIC_COMPONENTS: usize = 7;",
)
require(
    "native/archphene-compositor/src/android_gpu_renderer.rs",
    "EGL_ANDROID_image_native_buffer",
    "glEGLImageTargetRenderbufferStorageOES",
    "glTexSubImage2D",
    "glDrawArrays",
    "glFinish",
    "const MAX_TARGETS: usize = 15;",
    "pub(crate) fn remove_target",
)
reject(
    "native/archphene-compositor/src/android_gpu_renderer.rs",
    "AHardwareBuffer_lock",
    "glReadPixels",
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
