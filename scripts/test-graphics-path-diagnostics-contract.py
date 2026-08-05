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
    "native/archphene-compositor/src/lib.rs",
    'c"ASurfaceTransaction_setBufferWithRelease"',
    "surface_buffer_released",
    "android_surface_transaction_set_buffer(",
    "android_surface_transaction_set_on_complete(",
    "release_aware_buffers_available",
    "probe_release_aware_surface",
)
require(
    "android/app/src/main/kotlin/org/archphene/app/launcher/LauncherSessionService.kt",
    '"Surface release mode="',
    '"API36 per-buffer callback"',
    '"legacy transaction completion"',
)
require(
    "prototypes/native-compositor-probe/src/org/archphene/compositorprobe/MainActivity.java",
    "nativeReleaseAwareSurfaceProbe",
    '"Surface release probe="',
    '"API36 per-buffer callback"',
    '"legacy fallback"',
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
    "last_fence: [u64; MAX_PRESENT_RESOURCES]",
    "last_release: [u64; MAX_PRESENT_RESOURCES]",
    "fence_sequence: get_u64(frame, 48)",
    "hello_received: bool",
    "if !self.hello_received",
    "DuplicateResource",
    "StaleFence",
    "OutstandingFence",
    "GpuPresentMessage::DropResource",
    "rejects_cross_session_generation_token_and_trailing_fields",
)
require(
    "native/archphene-compositor/src/gpu_surface_identity.rs",
    "MAX_GPU_SURFACE_BINDINGS: usize = 32",
    "MAX_GPU_SURFACE_RESOURCES: usize = 3",
    "DuplicateSurface",
    "StaleFence",
    "standard_buffer_updated",
    "present_resource",
    "identity.fence_sequence != resource.presented_fence_sequence",
    "helper_replacement_and_resource_release_clear_committed_identity",
)
require(
    "native/archphene-compositor/protocols/archphene-gpu-present-v1.xml",
    'interface name="org_archphene_gpu_present_manager_v1" version="1"',
    'interface name="org_archphene_gpu_surface_v1" version="1"',
    'request name="set_resource"',
    'arg name="helper_generation" type="uint"',
    'arg name="fence_sequence_hi" type="uint"',
    "already authenticated APHB Present frame",
)
require(
    "native/archphene-compositor/src/gpu_surface_protocol.rs",
    "generate_server_code!",
    "generate_client_code!",
    '"./protocols/archphene-gpu-present-v1.xml"',
)
require(
    "native/archphene-compositor/src/lib.rs",
    "enable_gpu_surface_identity",
    "apply_gpu_present_frame",
    "GpuPresentMessage::Hello",
    "GpuPresentMessage::Resource",
    "GpuPresentMessage::Present",
    "GpuPresentMessage::Release",
    "GPU release frames are manager-to-helper only",
    "private_gpu_identity_latches_on_the_exact_surface_commit",
    "binding.set_resource(9, 44, 1, 2)",
    "binding.clear()",
)
require(
    "native/android-gpu-helper/patches/0008-archphene-present-channel.patch",
    '"archphene-present-socket"',
    '"archphene-session-id"',
    '"archphene-helper-generation"',
    '"archphene-present-token"',
    "archphene_parse_u32",
    "archphene_parse_token",
    "server.archphene_present_socket_name[0] != '/'",
    'memcpy(frame, "APHB", 4)',
    "vtest_server_open_archphene_present_socket();",
)
require(
    "native/archphene-compositor/src/gpu_present_transport.rs",
    "MAX_UNIX_SOCKET_PATH_BYTES: usize = 103",
    "libc::SO_PEERCRED",
    "peer_uid(&stream)? != self.expected_uid",
    "GPU_PRESENT_FRAME_BYTES",
    "disconnect_discards_partial_frame_before_replacement",
    "metadata.dev() == self.identity.device",
    "metadata.ino() == self.identity.inode",
)
require(
    "native/archphene-compositor/src/lib.rs",
    "enable_gpu_present_endpoint",
    "MAX_FRAMES_PER_DISPATCH: usize = 4",
    "GPU resource handles and fences are not connected",
    "scoped_gpu_endpoint_accepts_only_connected_control_frames",
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
