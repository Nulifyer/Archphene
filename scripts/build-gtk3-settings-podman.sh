#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

rebuild_image=false
while (($#)); do
  case "$1" in
    --rebuild-image) rebuild_image=true; shift ;;
    -h|--help) echo "usage: $0 [--rebuild-image]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
image=localhost/archphene-qt-platform-theme:qt6.11.1
if [[ "$rebuild_image" == true ]] || ! archphene_podman_image_exists "$image"; then
  podman build -f "$ARCHPHENE_ROOT/containers/qt-platform-theme.Containerfile" \
    -t "$image" "$ARCHPHENE_ROOT/containers"
fi
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$image" bash -lc '
set -euo pipefail
source_file=/workspace/native/archphene-gtk3-settings/archphene_gtk3_settings.c
arm_root=/workspace/tooling/downloads/arch-curated-kcalc-aarch64/runtime-root
mkdir -p /workspace/prebuilt/gtk3-compat/{x86_64,aarch64}
gcc -shared -fPIC -O2 -Wall -Wextra -Werror $(pkg-config --cflags glib-2.0 gobject-2.0 gmodule-2.0) \
  -Wl,--allow-shlib-undefined -o /workspace/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so "$source_file"
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
  -I"$arm_root/usr/include/glib-2.0" -I"$arm_root/usr/lib/glib-2.0/include" \
  -Wl,--allow-shlib-undefined -o /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so "$source_file"
strip --strip-unneeded /workspace/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so
aarch64-linux-gnu-strip --strip-unneeded /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so
readelf -h /workspace/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so | grep -F "Advanced Micro Devices X86-64"
aarch64-linux-gnu-readelf -h /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so | grep -F AArch64
'
python3 - "$ARCHPHENE_ROOT/prebuilt/gtk3-compat" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / "manifest.json").read_text())
def entries(directory):
    return [
        {"name": path.name, "bytes": path.stat().st_size,
         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        for path in sorted(directory.glob("*.so"))
    ]
x86 = entries(root / "x86_64")
arm = entries(root / "aarch64")
manifest["files"] = x86
manifest["additionalArchitectures"][0]["files"] = arm
(root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
lines = [f'{entry["sha256"]}  x86_64/{entry["name"]}' for entry in x86]
lines += [f'{entry["sha256"]}  aarch64/{entry["name"]}' for entry in arm]
(root / "SHA256SUMS").write_text("\n".join(lines) + "\n")
PY
archphene_note "GTK 3 live-settings module built for x86_64 and AArch64."

