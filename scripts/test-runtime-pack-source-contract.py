#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSEMBLER = ROOT / (
    "prototypes/linux-app-manager-stub/src/org/archpheneos/manager/"
    "ArchWrapperAssembler.java"
)
LAUNCHER = ROOT / (
    "prototypes/shared-android-bridge/src/org/archphene/bridge/"
    "RuntimeFdLauncher.java"
)
PATH_BRIDGE = ROOT / "native/archphene-glibc-path-bridge/path_bridge.c"
ARM64_PATH_BRIDGE_MAP = ROOT / "native/archphene-glibc-path-bridge/arm64.map"
COMPOSITOR_ACTIVITY = ROOT / (
    "prototypes/shared-android-bridge/src/org/archphene/bridge/"
    "ArchpheneCompositorActivity.java"
)
COMPOSITOR_SESSION = ROOT / (
    "prototypes/shared-android-bridge/src/org/archphene/bridge/"
    "ArchpheneCompositorSession.java"
)


def main() -> None:
    source = ASSEMBLER.read_text()
    required = {
        "RUNTIME_LOADED_LIBRARY_LIMIT = 128":
            "bounded runtime-loaded library discovery",
        "EMBEDDED_LIBRARY_REFERENCE_LIMIT = 512":
            "bounded per-ELF soname scanning without an overly narrow package limit",
        "resolveRuntimeLoadedDependencies(":
            "recursive runtime-loaded dependency closure",
        "runtimeLibraryReferences(source)":
            "selected staged ELF soname inspection",
        "!source.getPath().startsWith(stagedPrefix)":
            "manager-owned library scan exclusion",
        "!candidates.containsKey(name)":
            "verified candidate matching",
        "Ambiguous runtime-loaded ELF dependency":
            "ambiguous soname rejection",
        "Runtime-loaded ELF dependency limit exceeded":
            "retained dynamic dependency bound",
        "Embedded runtime library reference limit exceeded":
            "embedded soname reference bound",
        "Retained verified runtime-loaded library":
            "dynamic dependency diagnostic",
    }
    missing = [description for token, description in required.items()
               if token not in source]
    if missing:
        raise SystemExit("runtime-pack source contract missing: " + ", ".join(missing))
    if "supertux" in source.casefold() or "libsdl3" in source.casefold():
        raise SystemExit("runtime-loaded library support must remain package-generic")
    first_dynamic = source.find("resolveRuntimeLoadedDependencies(",
                                source.find("hasVersionedLibrary(result"))
    graphics = source.find('result.containsKey("libEGL.so.1")', first_dynamic)
    final_dynamic = source.find("resolveRuntimeLoadedDependencies(", graphics)
    if min(first_dynamic, graphics, final_dynamic) < 0 or not (
            first_dynamic < graphics < final_dynamic):
        raise SystemExit("graphics providers must be enclosed by dynamic-library fixpoint passes")

    launcher = LAUNCHER.read_text()
    bridge = PATH_BRIDGE.read_text()
    relocation_required = {
        "runtimeProgramFile(cacheRoot": "private prefixed program view",
        "ARCHPHENE_RUNTIME_PROGRAM_PATH": "trusted program path handoff",
        "Runtime program prefix is outside app-private storage":
            "app-private prefix boundary",
        "Runtime program directory escapes its private prefix":
            "canonical program-directory boundary",
        'strcmp(path, "/proc/self/exe") == 0':
            "managed procfs executable identity",
        "capture_trusted_program_path": "immutable trusted program-path capture",
        "S_IWGRP | S_IWOTH": "writable program-path rejection",
    }
    combined = launcher + bridge
    missing = [description for token, description in relocation_required.items()
               if token not in combined]
    if missing:
        raise SystemExit("runtime relocation source contract missing: "
                         + ", ".join(missing))
    if "supertux" in combined.casefold():
        raise SystemExit("runtime relocation support must remain package-generic")
    arm64_map = ARM64_PATH_BRIDGE_MAP.read_text()
    for symbol in ("readlink;", "readlinkat;", "setfsgid;", "setfsuid;"):
        if symbol not in arm64_map:
            raise SystemExit(
                f"AArch64 path bridge must version {symbol[:-1]} for glibc interposition")
    compositor = COMPOSITOR_ACTIVITY.read_text()
    if 'env.put("SDL_VIDEODRIVER", "wayland")' not in compositor:
        raise SystemExit("direct-Wayland wrappers must select the SDL Wayland backend")
    if 'env.put("SDL_AUDIODRIVER", "pulseaudio")' not in compositor:
        raise SystemExit("direct-Wayland wrappers must select the Android audio bridge")
    if 'env.put("SDL_RENDER_DRIVER"' in compositor:
        raise SystemExit("direct-Wayland wrappers must not disable accelerated SDL renderers")
    if compositor.count('"wayland".equals(toolkit)') < 2 or (
            '|| session.hasPopups() || menuAt || clickableAt' not in compositor):
        raise SystemExit("direct-Wayland short taps must use desktop pointer semantics")
    if ('containsLibraryPrefix(runtimeLibraryNames, "libSDL2-")' not in compositor
            or "setSuppressInitialNativeImeShow" not in compositor):
        raise SystemExit("legacy SDL wrappers must suppress only their implicit startup IME")
    for token in (
            "applyRuntimeOrientationPolicy()",
            "ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE",
            'containsLibraryPrefix(runtimeLibraryNames, "libSDL3")',
            "display.getDisplayId() != Display.DEFAULT_DISPLAY"):
        if token not in compositor:
            raise SystemExit(
                "direct-Wayland SDL wrappers must apply the generic default-display "
                f"orientation policy: missing {token}")
    session = COMPOSITOR_SESSION.read_text()
    for token, description in {
        "RECOVERED_KEY_HOLD_MILLIS": "minimum recovered-key press interval",
        "event.getRepeatCount() != 0": "missing initial key-down detection",
        "recoveredRepeatKeys": "repeat-recovery state",
        "if (inputDiagnostics)": "privacy-gated detailed input diagnostics",
        "KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> 28":
            "keyboard and D-pad activation mapping",
        "POINTER_HOVER_SETTLE_MILLIS": "pointer hover-settle interval",
        "press = hover + POINTER_HOVER_SETTLE_MILLIS":
            "pointer positioning before synthesized click",
    }.items():
        if token not in session:
            raise SystemExit(f"Wayland input bridge missing {description}")
    if ('ApplicationInfo.FLAG_DEBUGGABLE' not in compositor
            or '"archphene_test_input_debug"' not in compositor):
        raise SystemExit("detailed input diagnostics must remain debuggable-only")
    package_runtime = (ROOT / (
        "prototypes/linux-app-manager-stub/src/org/archpheneos/manager/"
        "ArchPackageRuntime.java"
    )).read_text()
    for package in ("wayland", "libxkbcommon", "libpulse"):
        if f'bridgePackages.add("{package}")' not in package_runtime:
            raise SystemExit(f"SDL bridge provider missing: {package}")
    if "supertux" in package_runtime.casefold():
        raise SystemExit("SDL bridge support must remain application-generic")
    print("Runtime-pack dynamic-library source contract passed")


if __name__ == "__main__":
    main()
