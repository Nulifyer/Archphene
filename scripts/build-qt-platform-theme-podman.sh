#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

rebuild_image=false
jobs=2
while (($#)); do
  case "$1" in
    --rebuild-image) rebuild_image=true; shift ;;
    --jobs) jobs="${2:?missing value for --jobs}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--rebuild-image] [--jobs 1..16]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$jobs" =~ ^[0-9]+$ ]] && ((jobs >= 1 && jobs <= 16)) || archphene_die "jobs must be from 1 to 16"
image=localhost/archphene-qt-platform-theme:qt6.11.1
expected_qt=6.11.1
needs_image=false
if [[ "$rebuild_image" == true ]] || ! archphene_podman_image_exists "$image"; then
  needs_image=true
elif ! podman run --rm "$image" bash -lc 'command -v aarch64-linux-gnu-g++' >/dev/null; then
  needs_image=true
fi
if [[ "$needs_image" == true ]]; then
  podman build -f "$ARCHPHENE_ROOT/containers/qt-platform-theme.Containerfile" \
    -t "$image" "$ARCHPHENE_ROOT/containers"
fi
qt_version="$(podman run --rm "$image" pkg-config --modversion Qt6Core)"
[[ "$qt_version" == "$expected_qt" ]] || archphene_die \
  "Qt private ABI mismatch: expected $expected_qt, got $qt_version"
command="set -euo pipefail
rm -rf /tmp/archphene-qt-platform-theme
mkdir -p /tmp/archphene-qt-platform-theme/{platform,style,kde-config}
cd /tmp/archphene-qt-platform-theme/platform
qmake6 /workspace/native/archphene-qt-platform-theme/archphene-qt-platform-theme.pro
make -j$jobs
install -Dm755 libarchphene_qt_platform_theme.so /workspace/prebuilt/qt-bridge/x86_64/libarchphene_qt_platform_theme.so
cd /tmp/archphene-qt-platform-theme/style
qmake6 /workspace/native/archphene-qt-platform-theme/archphene-qt-style.pro
make -j$jobs
install -Dm755 libarchphene_qt_style.so /workspace/prebuilt/qt-bridge/x86_64/libarchphene_qt_style.so
cd /tmp/archphene-qt-platform-theme/kde-config
qmake6 /workspace/native/archphene-qt-platform-theme/archphene-kde-config.pro
make -j$jobs
install -Dm755 libarchphene_kde_config.so /workspace/prebuilt/qt-bridge/x86_64/libarchphene_kde_config.so"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$image" bash -lc "$command"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$image" \
  bash scripts/build-qt-platform-theme-arm64.sh

python3 - "$ARCHPHENE_ROOT/prebuilt/qt-bridge" "$expected_qt" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
qt = sys.argv[2]
def entries(directory):
    return [
        {"name": path.name, "bytes": path.stat().st_size,
         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        for path in sorted(directory.glob("*.so"))
    ]
x86 = entries(root / "x86_64")
arm = entries(root / "arm64-v8a")
manifest = {
    "schema": "org.archphene.prebuilt-bridge.v1",
    "architecture": "x86_64",
    "qtVersion": qt,
    "purpose": "Qt Wayland Android bridge template",
    "files": x86,
    "additionalArchitectures": [{"architecture": "arm64-v8a", "files": arm}],
}
arm_manifest = {
    "schema": manifest["schema"], "architecture": "arm64-v8a",
    "qtVersion": qt, "purpose": manifest["purpose"], "files": arm,
}
(root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
(root / "manifest-arm64-v8a.json").write_text(json.dumps(arm_manifest, indent=2) + "\n")
lines = [f'{entry["sha256"]}  x86_64/{entry["name"]}' for entry in x86]
lines += [f'{entry["sha256"]}  arm64-v8a/{entry["name"]}' for entry in arm]
(root / "SHA256SUMS").write_text("\n".join(lines) + "\n")
PY
archphene_note "Qt $qt_version appearance plugins built for x86_64 and arm64-v8a."

