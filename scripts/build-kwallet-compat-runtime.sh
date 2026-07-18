#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
version=6.28.0
archive="$root/tooling/downloads/kwallet-v${version}.tar.gz"
archive_sha256=dc1faec6e84c66c0eaccba5602771e2b3358ca9a0f9296b3715d07225aea204a
patch_dir="$root/native/archphene-kwallet/patches"
machine="$(uname -m)"
case "$machine" in
  x86_64)
    architecture=x86_64
    expected_machine='Advanced Micro Devices X86-64'
    ;;
  aarch64)
    architecture=aarch64
    expected_machine=AArch64
    ;;
  *)
    echo "Unsupported KWallet build architecture: $machine" >&2
    exit 1
    ;;
esac
work="$root/tooling/build/kwallet-compat-work-$architecture"
out="$root/tooling/build/kwallet-compat/$architecture"

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

if [[ "$architecture" == aarch64 ]]; then
  pacman-key --init
  pacman-key --populate archlinuxarm
fi
pacman_retry -Syu --noconfirm
pacman_retry -S --noconfirm --needed \
  base-devel cmake curl extra-cmake-modules kwallet ninja patch

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
compgen -G "$patch_dir/*.patch" >/dev/null || {
  echo "KWallet compatibility patches are missing" >&2
  exit 1
}
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

cmake_options=(
  -DBUILD_KSECRETD=OFF
  -DBUILD_KWALLETD=ON
  -DBUILD_KWALLET_QUERY=OFF
  -DBUILD_TESTING=OFF
  -DWITH_X11=OFF
  -DCMAKE_BUILD_TYPE=Release
)
if [[ "$architecture" == aarch64 ]]; then
  cmake_options+=("-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=65536")
fi
cmake -S "$source_dir" -B "$work/build" -G Ninja "${cmake_options[@]}"
cmake --build "$work/build" --target kwalletd6

binary="$(find "$work/build" -type f -name kwalletd6 -perm -0100 -print -quit)"
[[ -n "$binary" ]] || {
  echo "Built kwalletd6 executable was not found" >&2
  exit 1
}
readelf -h "$binary" | grep -F 'Machine:' | grep -F "$expected_machine" >/dev/null
if [[ "$architecture" == aarch64 ]]; then
  while read -r alignment; do
    (( alignment >= 0x4000 )) || {
      echo "AArch64 KWallet LOAD alignment is below 16 KB: $alignment" >&2
      exit 1
    }
  done < <(readelf -lW "$binary" | awk '/ LOAD / { print $NF }')
fi

install -m 0755 "$binary" "$out/kwalletd6"
cat > "$out/SOURCE" <<EOF
KWallet version: ${version}
Architecture: ${architecture}
Source: https://invent.kde.org/frameworks/kwallet/-/archive/v${version}/kwallet-v${version}.tar.gz
Source SHA-256: ${archive_sha256}
Patches: native/archphene-kwallet/patches/*.patch
Patch SHA-256:
$(sha256sum "$patch_dir"/*.patch)
EOF
(
  cd "$out"
  sha256sum kwalletd6 SOURCE > SHA256SUMS
)
echo "Archphene KWallet compatibility daemon: $out/kwalletd6"
