#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
version=6.28.0
archive="$root/tooling/downloads/kwallet-v${version}.tar.gz"
archive_sha256=dc1faec6e84c66c0eaccba5602771e2b3358ca9a0f9296b3715d07225aea204a
patch_dir="$root/native/archphene-kwallet/patches"
work="$root/tooling/build/kwallet-compat-work"
out="$root/tooling/build/kwallet-compat/x86_64"

mkdir -p "$(dirname "$archive")" "$(dirname "$out")"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 3 \
    -o "$archive" \
    "https://invent.kde.org/frameworks/kwallet/-/archive/v${version}/kwallet-v${version}.tar.gz"
fi
printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum --check --status || {
  echo "KWallet source archive checksum mismatch" >&2
  exit 1
}
compgen -G "$patch_dir/*.patch" >/dev/null || { echo "KWallet compatibility patches are missing" >&2; exit 1; }

pacman -Syu --noconfirm --needed \
  cmake extra-cmake-modules kwallet ninja patch >/dev/null
installed_kwallet="$(pacman -Q kwallet | awk '{print $2}')"
[[ "$installed_kwallet" == "$version"-* ]] || {
  echo "Expected Arch kwallet ${version}, found ${installed_kwallet}" >&2
  exit 1
}

rm -rf "$work" "$out"
mkdir -p "$work" "$out"
tar -xf "$archive" -C "$work"
source_dir="$work/kwallet-v${version}"
for patch_file in "$patch_dir"/*.patch; do
  patch --directory="$source_dir" --strip=1 --input="$patch_file"
done

cmake -S "$source_dir" -B "$work/build" -G Ninja \
  -DBUILD_KSECRETD=OFF \
  -DBUILD_KWALLETD=ON \
  -DBUILD_KWALLET_QUERY=OFF \
  -DBUILD_TESTING=OFF \
  -DWITH_X11=OFF \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$work/build" --target kwalletd6

binary="$(find "$work/build" -type f -name kwalletd6 -perm -0100 -print -quit)"
[[ -n "$binary" ]] || { echo "Built kwalletd6 executable was not found" >&2; exit 1; }
install -m 0755 "$binary" "$out/kwalletd6"
cat > "$out/SOURCE" <<EOF
KWallet version: ${version}
Source: https://invent.kde.org/frameworks/kwallet/-/archive/v${version}/kwallet-v${version}.tar.gz
Source SHA-256: ${archive_sha256}
Patches: native/archphene-kwallet/patches/*.patch
Patch SHA-256:
$(sha256sum "$patch_dir"/*.patch)
EOF
sha256sum "$out/kwalletd6" "$out/SOURCE" > "$out/SHA256SUMS"
echo "Archphene KWallet compatibility daemon: $out/kwalletd6"
