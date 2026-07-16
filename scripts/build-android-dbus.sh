#!/usr/bin/env bash
set -euo pipefail

ARCHITECTURE="${1:-x86_64}"
NDK_VERSION="${NDK_VERSION:-29.0.14206865}"
API_LEVEL="${API_LEVEL:-29}"
DBUS_BUILD_TYPE="${DBUS_BUILD_TYPE:-release}"
DBUS_VERSION="1.16.2"
DBUS_SHA256="0ba2a1a4b16afe7bceb2c07e9ce99a8c2c3508e5dec290dbb643384bd6beb7e2"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOWNLOADS="${DBUS_DOWNLOADS:-$ROOT/tooling/downloads}"
OUTPUT="$ROOT/tooling/build/android-dbus/$ARCHITECTURE"
BUILD_ROOT="${TMPDIR:-/tmp}/archphene-android-dbus-$ARCHITECTURE"
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
    echo "Unsupported Android D-Bus architecture: $ARCHITECTURE" >&2
    exit 2
    ;;
esac

archive="$DOWNLOADS/dbus-$DBUS_VERSION.tar.xz"
mkdir -p "$DOWNLOADS"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 3 --output "$archive.part" \
    "https://dbus.freedesktop.org/releases/dbus/dbus-$DBUS_VERSION.tar.xz"
  mv "$archive.part" "$archive"
fi
echo "$DBUS_SHA256  $archive" | sha256sum --check --status || {
  echo "D-Bus source checksum mismatch: $archive" >&2
  exit 1
}

rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT" "$OUTPUT"
tar -xJf "$archive" -C "$BUILD_ROOT"
source_dir="$BUILD_ROOT/dbus-$DBUS_VERSION"
for patch_file in "$ROOT"/native/archphene-dbus/patches/*.patch; do
  patch -d "$source_dir" -p1 < "$patch_file"
done

cross_file="$BUILD_ROOT/android.ini"
cat > "$cross_file" <<EOF
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

meson setup "$BUILD_ROOT/build" "$source_dir" \
  --cross-file "$cross_file" \
  --wrap-mode=forcefallback \
  --buildtype "$DBUS_BUILD_TYPE" \
  --default-library static \
  -Dmessage_bus=true \
  -Dtools=false \
  -Dtraditional_activation=false \
  -Dsystemd=disabled \
  -Dselinux=disabled \
  -Dapparmor=disabled \
  -Dlibaudit=disabled \
  -Dx11_autolaunch=disabled \
  -Dmodular_tests=disabled \
  -Dinstalled_tests=false \
  -Dxml_docs=disabled \
  -Ddoxygen_docs=disabled \
  -Dducktype_docs=disabled \
  -Dqt_help=disabled \
  -Dstats=false
meson compile -C "$BUILD_ROOT/build" dbus-daemon

cp "$BUILD_ROOT/build/bus/dbus-daemon" "$OUTPUT/dbus-daemon"
common_flags=(
  -fPIE -O2 -Wall -Wextra -Werror
  -DDBUS_STATIC_BUILD
  -I"$source_dir" -I"$source_dir/dbus"
  -I"$BUILD_ROOT/build" -I"$BUILD_ROOT/build/dbus"
  -I"$ROOT/native/archphene-android-capability"
)
"${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang" "${common_flags[@]}" \
  "$ROOT/native/archphene-portal/archphene_portal.c" \
  "$ROOT/native/archphene-android-capability/archphene_android.c" \
  "$BUILD_ROOT/build/dbus/libdbus-1.a" \
  -pie -pthread -llog -o "$OUTPUT/portal-service"
"${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang" "${common_flags[@]}" \
  "$ROOT/native/archphene-portal/archphene_portal_probe.c" \
  "$BUILD_ROOT/build/dbus/libdbus-1.a" \
  -pie -pthread -o "$OUTPUT/portal-probe"
"${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang" \
  -fPIE -O2 -Wall -Wextra -Werror \
  -I"$ROOT/native/archphene-android-capability" \
  "$ROOT/native/archphene-portal/archphene_xdg_open.c" \
  "$ROOT/native/archphene-android-capability/archphene_android.c" \
  -pie -o "$OUTPUT/xdg-open"

for executable in dbus-daemon portal-service portal-probe xdg-open; do
  "${TOOLCHAIN}/bin/llvm-strip" "$OUTPUT/$executable"
  "${TOOLCHAIN}/bin/llvm-readelf" -h "$OUTPUT/$executable" \
    | grep -F 'Type:                              DYN'
  "${TOOLCHAIN}/bin/llvm-readelf" -d "$OUTPUT/$executable" \
    | grep -E 'NEEDED|SONAME'
done
if "${TOOLCHAIN}/bin/llvm-readelf" -d "$OUTPUT/dbus-daemon" | grep -q 'libexpat'; then
  echo "Android D-Bus daemon unexpectedly depends on shared expat" >&2
  exit 1
fi
sha256sum "$OUTPUT/dbus-daemon" "$OUTPUT/portal-service" \
  "$OUTPUT/portal-probe" "$OUTPUT/xdg-open"
