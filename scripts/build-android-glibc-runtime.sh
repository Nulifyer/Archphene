#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
version=2.43
archive="$root/tooling/downloads/glibc-${version}.tar.xz"
archive_sha256=d9c86c6b5dbddb43a3e08270c5844fc5177d19442cf5b8df4be7c07cd5fa3831
patch_file="$root/patches/glibc/0001-android-app-seccomp-compat.patch"
machine="$(uname -m)"
case "$machine" in
  x86_64)
    architecture=x86_64
    loader=ld-linux-x86-64.so.2
    expected_machine='Advanced Micro Devices X86-64'
    ;;
  aarch64)
    architecture=aarch64
    loader=ld-linux-aarch64.so.1
    expected_machine=AArch64
    ;;
  *)
    echo "Unsupported glibc build architecture: $machine" >&2
    exit 1
    ;;
esac
work="$root/tooling/build/glibc-archphene-work-$architecture"
out="$root/tooling/build/glibc-archphene-runtime-$architecture"

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
pacman_retry -S --noconfirm --needed base-devel bison curl gawk python texinfo

mkdir -p "$(dirname "$archive")" "$(dirname "$out")"
if [[ ! -f "$archive" ]]; then
  curl --proto =https --tlsv1.2 --fail --location --retry 3 \
    --output "$archive" \
    "https://ftp.gnu.org/gnu/glibc/glibc-${version}.tar.xz"
fi
printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum --check --status || {
  echo "glibc source archive checksum mismatch" >&2
  exit 1
}
[[ -f "$patch_file" ]] || {
  echo "Archphene glibc compatibility patch is missing" >&2
  exit 1
}

rm -rf "$work" "$out"
mkdir -p "$work/source" "$work/build" "$work/install" "$out"
tar -xJf "$archive" --strip-components=1 -C "$work/source"
patch --directory="$work/source" --strip=1 --input="$patch_file"

build_triplet="$("$work/source/scripts/config.guess")"
configure_environment=(
  "CPPFLAGS=-DARCHPHENE_ANDROID_APP_COMPAT=1"
)
(
  cd "$work/build"
  env "${configure_environment[@]}" "$work/source/configure" \
    --prefix=/usr \
    --build="$build_triplet" \
    --disable-werror \
    --enable-kernel=5.10
  cat > configparms <<EOF
LDFLAGS.so += -Wl,-z,max-page-size=65536 -Wl,-z,common-page-size=16384
LDFLAGS-rtld += -Wl,-z,max-page-size=65536 -Wl,-z,common-page-size=16384
EOF
  make -s -j"${JOBS:-2}"
  make -s install DESTDIR="$work/install"
)

runtime_files=(
  "$loader" libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0
  librt.so.1 libresolv.so.2 libutil.so.1 libanl.so.1
  libnss_dns.so.2 libnss_files.so.2
)
for name in "${runtime_files[@]}"; do
  source_file="$(find "$work/install" \( -type f -o -type l \) \
    -name "$name" -print -quit)"
  [[ -n "$source_file" ]] || {
    echo "missing patched glibc runtime file: $name" >&2
    exit 1
  }
  cp -L "$source_file" "$out/$name"
  readelf -h "$out/$name" | grep -F 'Machine:' | grep -F "$expected_machine" >/dev/null
  while read -r alignment; do
    (( alignment >= 0x4000 )) || {
      echo "$architecture glibc LOAD alignment is below 16 KB: $name $alignment" >&2
      exit 1
    }
  done < <(readelf -lW "$out/$name" | awk '/ LOAD / { print $NF }')
done

cat > "$out/source-commit.txt" <<EOF
glibc-${version}+sha256.${archive_sha256}
Patch SHA-256: $(sha256sum "$patch_file" | cut -d ' ' -f 1)
Architecture: ${architecture}
EOF
(
  cd "$out"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum
) > "$out/SHA256SUMS"
echo "Archphene Android-compatible glibc runtime: $out"
