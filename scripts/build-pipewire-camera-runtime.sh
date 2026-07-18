#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
architecture="${1:?architecture is required}"
source_dir="$root/tooling/external/pipewire"
expected_commit=b741e0c74f5436f0c925f7741140db0efd32cf4e
[[ -f "$source_dir/meson.build" ]] || {
  echo "PipeWire source is missing: $source_dir" >&2
  exit 1
}
[[ "$(git -c safe.directory="$source_dir" -C "$source_dir" rev-parse HEAD)" == "$expected_commit" ]] || {
  echo "PipeWire source must be tag 1.6.8 at $expected_commit" >&2
  exit 1
}

build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
out="$root/tooling/build/pipewire-camera/$architecture"
rm -rf "$out"
mkdir -p "$out/pipewire-0.3" "$out/spa-0.2/support" \
  "$out/spa-0.2/videoconvert"

case "$architecture" in
  x86_64)
    compiler=gcc
    readelf=readelf
    machine="Advanced Micro Devices X86-64"
    cross_arguments=()
    ;;
  aarch64)
    compiler=aarch64-linux-gnu-gcc
    readelf=aarch64-linux-gnu-readelf
    machine=AArch64
    cross="$build/aarch64.ini"
    cat > "$cross" <<'EOF'
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'aarch64-linux-gnu-pkg-config'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true
EOF
    cross_arguments=(--cross-file "$cross")
    ;;
  *) echo "Unsupported PipeWire architecture: $architecture" >&2; exit 1 ;;
esac

LDFLAGS="-Wl,-z,max-page-size=65536" meson setup "$build/meson" "$source_dir" \
  --prefix=/usr --buildtype=release --auto-features=disabled \
  -Ddbus=disabled -Dflatpak=disabled -Dexamples=disabled -Dtests=disabled \
  -Dpipewire-jack=disabled -Dpipewire-v4l2=disabled \
  -Dspa-plugins=enabled -Dsupport=enabled -Dsession-managers=[] \
  "${cross_arguments[@]}"
ninja -C "$build/meson" \
  src/daemon/pipewire \
  src/pipewire/libpipewire-0.3.so.0.1608.0 \
  src/modules/libpipewire-module-protocol-native.so \
  src/modules/libpipewire-module-access.so \
  src/modules/libpipewire-module-metadata.so \
  src/modules/libpipewire-module-client-node.so \
  src/modules/libpipewire-module-adapter.so \
  src/modules/libpipewire-module-link-factory.so \
  spa/plugins/support/libspa-support.so \
  spa/plugins/videoconvert/libspa-videoconvert.so

include=(
  -I"$root/native/archphene-android-capability"
  -I"$source_dir/src"
  -I"$source_dir/spa/include"
  -I"$build/meson/src"
  -I"$build/meson/spa/include"
)
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -pthread \
  -Wl,-z,max-page-size=65536 "${include[@]}" \
  "$root/native/archphene-pipewire-camera/archphene_pipewire_camera.c" \
  "$root/native/archphene-android-capability/archphene_android.c" \
  -L"$build/meson/src/pipewire" -l:libpipewire-0.3.so.0.1608.0 \
  -o "$out/archphene-pipewire-camera"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror \
  -Wl,-z,max-page-size=65536 "${include[@]}" \
  "$root/native/archphene-pipewire-camera/archphene_camera_policy.c" \
  -L"$build/meson/src/pipewire" -l:libpipewire-0.3.so.0.1608.0 \
  -o "$out/archphene-pipewire-policy"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror \
  -Wl,-z,max-page-size=65536 \
  "$root/native/archphene-pipewire-camera/archphene_runtime_supervisor.c" \
  -o "$out/archphene-runtime-supervisor"

cp "$build/meson/src/daemon/pipewire" "$out/archphene-pipewire"
cp "$build/meson/src/pipewire/libpipewire-0.3.so.0.1608.0" \
  "$out/libpipewire-0.3.so.0"
cp "$build/meson/src/modules/libpipewire-module-protocol-native.so" \
  "$out/pipewire-0.3/"
cp "$build/meson/src/modules/libpipewire-module-access.so" \
  "$out/pipewire-0.3/"
cp "$build/meson/src/modules/libpipewire-module-metadata.so" \
  "$out/pipewire-0.3/"
cp "$build/meson/src/modules/libpipewire-module-client-node.so" \
  "$out/pipewire-0.3/"
cp "$build/meson/src/modules/libpipewire-module-adapter.so" \
  "$out/pipewire-0.3/"
cp "$build/meson/src/modules/libpipewire-module-link-factory.so" \
  "$out/pipewire-0.3/"
cp "$build/meson/spa/plugins/support/libspa-support.so" \
  "$out/spa-0.2/support/"
cp "$build/meson/spa/plugins/videoconvert/libspa-videoconvert.so" \
  "$out/spa-0.2/videoconvert/"

while IFS= read -r -d '' file; do
  "$readelf" -h "$file" | grep -F "$machine" >/dev/null
  alignments="$("$readelf" -lW "$file" | awk '/ LOAD / { print $NF }' | sort -u)"
  [[ "$alignments" == "0x10000" ]] || {
    echo "Unexpected ELF alignment for $file: $alignments" >&2
    exit 1
  }
done < <(find "$out" -type f ! -name PACKAGE_VERSIONS ! -name SHA256SUMS -print0)

printf 'pipewire\t1.6.8\t%s\n' "$expected_commit" > "$out/PACKAGE_VERSIONS"
(
  cd "$out"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum
) > "$out/SHA256SUMS"
echo "Archphene minimal PipeWire camera runtime: $out"
