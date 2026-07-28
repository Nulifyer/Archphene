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
mkdir -p "$ARCHPHENE_ROOT/prebuilt/gtk3-compat/x86_64"
gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
  $(pkg-config --cflags glib-2.0 gobject-2.0 gmodule-2.0 gio-2.0) \
  -o "$ARCHPHENE_ROOT/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so" \
  "$ARCHPHENE_ROOT/native/archphene-gtk3-settings/archphene_gtk3_settings.c" \
  $(pkg-config --libs glib-2.0 gobject-2.0 gmodule-2.0 gio-2.0)
strip --strip-unneeded \
  "$ARCHPHENE_ROOT/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so"
readelf -h "$ARCHPHENE_ROOT/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so" |
  grep -F "Advanced Micro Devices X86-64"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$image" bash -lc '
set -euo pipefail
source_file=/workspace/native/archphene-gtk3-settings/archphene_gtk3_settings.c
glib_archive=/tmp/glib2-2.88.2-1-aarch64.pkg.tar.xz
glib_root=/tmp/archphene-aarch64-glib
curl -fsSL \
  https://ca.us.mirror.archlinuxarm.org/aarch64/core/glib2-2.88.2-1-aarch64.pkg.tar.xz \
  -o "$glib_archive"
echo "662ee8c1c9546b10e394cac1d25205417b76580fab5d51524c5377e10024b34c  $glib_archive" |
  sha256sum -c -
mkdir -p "$glib_root"
bsdtar -xf "$glib_archive" -C "$glib_root" \
  "usr/lib/libgio-2.0.so*" \
  "usr/lib/libgobject-2.0.so*" \
  "usr/lib/libgmodule-2.0.so*" \
  "usr/lib/libglib-2.0.so*"
mkdir -p /workspace/prebuilt/gtk3-compat/aarch64
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
  $(pkg-config --cflags glib-2.0 gobject-2.0 gmodule-2.0 gio-2.0) \
  -o /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so \
  "$source_file" \
  -L"$glib_root/usr/lib" -Wl,-rpath-link,"$glib_root/usr/lib" \
  -lgio-2.0 -lgobject-2.0 -lgmodule-2.0 -lglib-2.0
aarch64-linux-gnu-strip --strip-unneeded /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so
aarch64-linux-gnu-readelf -h /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so | grep -F AArch64
aarch64-linux-gnu-readelf -d /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so |
  grep -F "Shared library: [libgio-2.0.so.0]"
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
