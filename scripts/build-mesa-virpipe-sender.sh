#!/usr/bin/env bash
set -euo pipefail

ARCHITECTURE="${1:-$(uname -m)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOWNLOADS="${MESA_DOWNLOADS:-$ROOT/tooling/downloads}"
OUTPUT="${MESA_OUTPUT:-$ROOT/tooling/build/mesa-virpipe-sender/$ARCHITECTURE}"
BUILD_ROOT="${TMPDIR:-/tmp}/archphene-mesa-virpipe-$ARCHITECTURE"
MESA_VERSION="26.1.5"
MESA_ARCHIVE="$DOWNLOADS/mesa-$MESA_VERSION.tar.xz"
MESA_SHA256="79e421c7ce18cd9e790b8375920325779f10798630bf30e0b22f1a21c8617122"

case "$ARCHITECTURE" in
  x86_64|aarch64) ;;
  *)
    echo "Unsupported Mesa virpipe sender architecture: $ARCHITECTURE" >&2
    exit 2
    ;;
esac
if [[ "$(uname -m)" != "$ARCHITECTURE" && -z "${MESA_CROSS_FILE:-}" ]]; then
  echo "Mesa virpipe sender must be built in a native $ARCHITECTURE environment" >&2
  exit 2
fi

mkdir -p "$DOWNLOADS"
if [[ ! -f "$MESA_ARCHIVE" ]]; then
  temporary="${MESA_ARCHIVE}.part"
  rm -f "$temporary"
  curl --fail --location --retry 3 --output "$temporary" \
    "https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz"
  mv "$temporary" "$MESA_ARCHIVE"
fi
test "$(sha256sum "$MESA_ARCHIVE" | cut -d' ' -f1)" = "$MESA_SHA256"

rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT/source" "$OUTPUT"
tar -xJf "$MESA_ARCHIVE" --strip-components=1 -C "$BUILD_ROOT/source"
for patch_file in "$ROOT"/native/mesa-virpipe-sender/patches/*.patch; do
  patch -d "$BUILD_ROOT/source" -p1 < "$patch_file"
done

cross_args=()
if [[ -n "${MESA_CROSS_FILE:-}" ]]; then
  cross_args=(--cross-file "$MESA_CROSS_FILE")
fi
meson setup "$BUILD_ROOT/build" "$BUILD_ROOT/source" \
  "${cross_args[@]}" \
  --prefix /usr --libdir lib --buildtype release \
  -Dgallium-drivers=virgl,softpipe \
  -Dvulkan-drivers= \
  -Dplatforms=wayland \
  -Dglx=disabled \
  -Degl=enabled \
  -Dgbm=disabled \
  -Dllvm=disabled \
  -Dshared-glapi=enabled \
  -Dvideo-codecs= \
  -Dbuild-tests=false
meson compile -C "$BUILD_ROOT/build"
DESTDIR="$OUTPUT" meson install -C "$BUILD_ROOT/build"

mkdir -p "$OUTPUT/usr/lib/dri"
ln -sfn ../libgallium-26.1.5.so "$OUTPUT/usr/lib/dri/swrast_dri.so"
test -f "$OUTPUT/usr/lib/libEGL.so.1.0.0"
test -f "$OUTPUT/usr/lib/dri/swrast_dri.so"
sha256sum "$OUTPUT/usr/lib/libEGL.so.1.0.0" \
  "$OUTPUT/usr/lib/dri/swrast_dri.so"
