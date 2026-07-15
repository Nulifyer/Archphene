#!/usr/bin/env bash
set -euo pipefail

ARCHITECTURE="${1:-x86_64}"
NDK_VERSION="${NDK_VERSION:-29.0.14206865}"
API_LEVEL="${API_LEVEL:-29}"
GPU_BUILD_TYPE="${GPU_BUILD_TYPE:-release}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOWNLOADS="${GPU_DOWNLOADS:-$ROOT/tooling/downloads}"
OUTPUT="$ROOT/tooling/build/android-gpu/$ARCHITECTURE"
BUILD_ROOT="${TMPDIR:-/tmp}/archphene-android-gpu-$ARCHITECTURE"
TOOLCHAIN="${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64"

case "$ARCHITECTURE" in
  x86_64)
    TARGET="x86_64-linux-android"
    CPU_FAMILY="x86_64"
    CPU="x86_64"
    ;;
  aarch64)
    TARGET="aarch64-linux-android"
    CPU_FAMILY="aarch64"
    CPU="aarch64"
    ;;
  *)
    echo "Unsupported Android GPU helper architecture: $ARCHITECTURE" >&2
    exit 2
    ;;
esac

VIRGL_ARCHIVE="$DOWNLOADS/virglrenderer-1.3.0.tar.gz"
EPOXY_ARCHIVE="$DOWNLOADS/libepoxy-1.5.10.tar.gz"
mkdir -p "$DOWNLOADS"

download_archive() {
  local destination="$1"
  local url="$2"
  if [[ ! -f "$destination" ]]; then
    local temporary="${destination}.part"
    rm -f "$temporary"
    curl --fail --location --retry 3 --output "$temporary" "$url"
    mv "$temporary" "$destination"
  fi
}

download_archive "$VIRGL_ARCHIVE" \
  "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-1.3.0/virglrenderer-virglrenderer-1.3.0.tar.gz"
download_archive "$EPOXY_ARCHIVE" \
  "https://github.com/anholt/libepoxy/archive/refs/tags/1.5.10.tar.gz"
test "$(sha256sum "$VIRGL_ARCHIVE" | cut -d' ' -f1)" = \
  "56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce"
test "$(sha256sum "$EPOXY_ARCHIVE" | cut -d' ' -f1)" = \
  "a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15"

rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT" "$OUTPUT"
tar -xzf "$VIRGL_ARCHIVE" -C "$BUILD_ROOT"
tar -xzf "$EPOXY_ARCHIVE" -C "$BUILD_ROOT"
VIRGL_SOURCE="$BUILD_ROOT/virglrenderer-virglrenderer-1.3.0"
EPOXY_SOURCE="$BUILD_ROOT/libepoxy-1.5.10"

for patch_file in "$ROOT"/native/android-gpu-helper/patches/*.patch; do
  patch -d "$VIRGL_SOURCE" -p1 < "$patch_file"
done

CROSS_FILE="$BUILD_ROOT/android.ini"
cat > "$CROSS_FILE" <<EOF
[binaries]
c = '${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang'
cpp = '${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang++'
ar = '${TOOLCHAIN}/bin/llvm-ar'
strip = '${TOOLCHAIN}/bin/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = '${CPU_FAMILY}'
cpu = '${CPU}'
endian = 'little'

[properties]
needs_exe_wrapper = true
EOF

PREFIX="$BUILD_ROOT/prefix"
meson setup "$BUILD_ROOT/epoxy-build" "$EPOXY_SOURCE" \
  --cross-file "$CROSS_FILE" \
  --prefix "$PREFIX" --libdir lib --buildtype "$GPU_BUILD_TYPE" \
  -Ddefault_library=static -Degl=yes -Dglx=no -Dx11=false -Dtests=false
meson compile -C "$BUILD_ROOT/epoxy-build"
meson install -C "$BUILD_ROOT/epoxy-build"

export PKG_CONFIG_DIR=
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
meson setup "$BUILD_ROOT/virgl-build" "$VIRGL_SOURCE" \
  --cross-file "$CROSS_FILE" \
  --prefix "$PREFIX" --libdir lib --buildtype "$GPU_BUILD_TYPE" \
  -Ddefault_library=static -Dplatforms=egl \
  -Dvenus=false -Dtests=false
meson compile -C "$BUILD_ROOT/virgl-build"

cp "$BUILD_ROOT/virgl-build/vtest/virgl_test_server" \
  "$OUTPUT/virgl_test_server_android"
"${TOOLCHAIN}/bin/llvm-strip" "$OUTPUT/virgl_test_server_android"
sha256sum "$OUTPUT/virgl_test_server_android"
"${TOOLCHAIN}/bin/llvm-readelf" -d "$OUTPUT/virgl_test_server_android" \
  | grep -E 'NEEDED|SONAME'
