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


def function_body(path: str, signature: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"{path}: missing function signature: {signature}")
    opening = text.find("{", start)
    if opening < 0:
        raise SystemExit(f"{path}: missing function body: {signature}")
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[opening : index + 1]
    raise SystemExit(f"{path}: unterminated function body: {signature}")


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
    "MAX_RETAINED_PRESENT_TARGETS",
    "pub(crate) fn remove_target",
)
require(
    "native/archphene-compositor/src/jni_exports/steady_state_allocations.rs",
    "fn warmed_gpu_damage_staging_does_not_allocate()",
    "for _ in 0..1_000",
    "assert_eq!(allocations, 0);",
)
require(
    "native/archphene-compositor/src/gpu_present_protocol.rs",
    'const MAGIC: &[u8; 4] = b"APHB";',
    "pub(crate) const GPU_PRESENT_FRAME_BYTES: usize = 64;",
    "MAX_PRESENT_RESOURCES: usize = 3",
    "helper_generation",
    "token: [u8; 16]",
    "MAX_PRESENT_TOTAL_BYTES",
    "DuplicateResource",
    "StaleFence",
    "rejects_cross_session_generation_token_and_trailing_fields",
)
reject(
    "native/archphene-compositor/src/android_gpu_renderer.rs",
    "AHardwareBuffer_lock",
    "glReadPixels",
)
gpu_render = function_body(
    "native/archphene-compositor/src/android_gpu_renderer.rs",
    "pub(crate) fn render(",
)
for forbidden in ("Vec::", "vec![", "Box::", "String::", ".collect", ".to_vec"):
    if forbidden in gpu_render:
        raise SystemExit(f"GPU render hot path allocates through {forbidden}")

managed_dispatch = function_body(
    "android/app/src/main/kotlin/org/archphene/app/launcher/NativeLauncherCompositor.kt",
    "fun dispatchAndPresent(",
)
for forbidden in ("ByteBuffer.allocate", "ByteArray", "Bitmap", ".copyOf"):
    if forbidden in managed_dispatch:
        raise SystemExit(f"Managed frame dispatch copies through {forbidden}")
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
