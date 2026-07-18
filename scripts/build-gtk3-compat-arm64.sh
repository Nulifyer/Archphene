#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -m)" != "aarch64" ]]; then
  echo "GTK3 compatibility payload must be built on AArch64" >&2
  exit 1
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="${1:-$root/tooling/build/gtk3-compat/aarch64}"
work="${ARCHPHENE_GTK_BUILD_ROOT:-/tmp/archphene-gtk3-compat}"

gdk_commit=7764d81b04ac56cff9ee9d421862701c77d73e92
rsvg_commit=92a0616013fabc2fd4d8aa9586f18920a5585a3b
pacman_retry() {
  local attempt
  for attempt in 1 2 3 4; do
    if pacman --disable-sandbox "$@"; then
      return 0
    fi
    if (( attempt < 4 )); then
      echo "pacman transaction failed on attempt $attempt; retrying" >&2
      sleep $((attempt * 5))
    fi
  done
  return 1
}

rm -rf "$work"
mkdir -p "$work" "$output"

pacman-key --init
pacman-key --populate archlinuxarm
pacman_retry -Syu --noconfirm
pacman_retry -S --needed --noconfirm \
  base-devel cairo cargo-c dav1d fontconfig freetype2 gi-docgen git glib2-devel \
  gobject-introspection harfbuzz libjpeg-turbo libpng libtiff libxml2 llvm \
  meson pango python-docutils rust shared-mime-info vala

useradd --create-home --shell /bin/bash archphene-builder
chown -R archphene-builder:archphene-builder "$work"

runuser -u archphene-builder -- git clone https://aur.archlinux.org/gdk-pixbuf2-noglycin.git "$work/gdk-pixbuf2-noglycin"
runuser -u archphene-builder -- git -C "$work/gdk-pixbuf2-noglycin" checkout "$gdk_commit"
sed -i 's/^arch=(x86_64)$/arch=(aarch64)/' "$work/gdk-pixbuf2-noglycin/PKGBUILD"
runuser -u archphene-builder -- bash -lc "cd '$work/gdk-pixbuf2-noglycin' && makepkg --cleanbuild --clean --nodeps --noconfirm"

gdk_package="$(find "$work/gdk-pixbuf2-noglycin" -maxdepth 1 -type f \
  -name 'gdk-pixbuf2-noglycin-[0-9]*-aarch64.pkg.tar.*' -print -quit)"
[[ -n "$gdk_package" ]] || { echo "gdk-pixbuf2 no-Glycin package was not produced" >&2; exit 1; }
# The compatibility package conflicts with and provides gdk-pixbuf2. Pacman's
# noninteractive default declines that replacement, so make it explicit in this
# disposable build root and immediately restore the provider.
pacman_retry -Rdd --noconfirm gdk-pixbuf2
pacman_retry -U --noconfirm "$gdk_package"
pacman -Q gdk-pixbuf2-noglycin >/dev/null

runuser -u archphene-builder -- git clone https://aur.archlinux.org/librsvg-noglycin.git "$work/librsvg-noglycin"
runuser -u archphene-builder -- git -C "$work/librsvg-noglycin" checkout "$rsvg_commit"
sed -i 's/^arch=(x86_64)$/arch=(aarch64)/' "$work/librsvg-noglycin/PKGBUILD"
runuser -u archphene-builder -- bash -lc "cd '$work/librsvg-noglycin' && makepkg --cleanbuild --clean --nodeps --noconfirm"

rsvg_package="$(find "$work/librsvg-noglycin" -maxdepth 1 -type f \
  -name 'librsvg-noglycin-[0-9]*-aarch64.pkg.tar.*' -print -quit)"
[[ -n "$rsvg_package" ]] || { echo "librsvg no-Glycin package was not produced" >&2; exit 1; }

extract="$work/extract"
mkdir -p "$extract/gdk" "$extract/rsvg"
bsdtar -xf "$gdk_package" -C "$extract/gdk"
bsdtar -xf "$rsvg_package" -C "$extract/rsvg"

gdk_library="$(find "$extract/gdk/usr/lib" -maxdepth 1 -type f -name 'libgdk_pixbuf-2.0.so.0.*' -print -quit)"
rsvg_library="$(find "$extract/rsvg/usr/lib" -maxdepth 1 -type f -name 'librsvg-2.so.2.*' -print -quit)"
svg_loader="$(find "$extract/rsvg/usr/lib/gdk-pixbuf-2.0" -type f -name 'libpixbufloader_svg.so' -print -quit)"

for required in "$gdk_library" "$rsvg_library" "$svg_loader"; do
  [[ -f "$required" ]] || { echo "required GTK3 compatibility library is missing: $required" >&2; exit 1; }
done

install -m755 "$gdk_library" "$output/libarchphene_gtk3_pixbuf.so"
install -m755 "$rsvg_library" "$output/libarchphene_gtk3_rsvg.so"
install -m755 "$svg_loader" "$output/libarchphene_gtk3_pixbufloader_svg.so"

for library in "$output"/*.so; do
  readelf -h "$library" | grep -F 'Machine:' | grep -F 'AArch64' >/dev/null
  readelf -d "$library" | grep -F '(SONAME)' >/dev/null || true
done

cat > "$output/PROVENANCE.txt" <<EOF
Architecture: aarch64
gdk-pixbuf2-noglycin AUR commit: $gdk_commit
librsvg-noglycin AUR commit: $rsvg_commit
Built natively in the official Arch Linux ARM aarch64 userspace.
EOF
(cd "$output" && sha256sum *.so > SHA256SUMS)

echo "Built verified GTK3 compatibility payload in $output"
