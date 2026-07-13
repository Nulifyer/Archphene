#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

glibc_commit="fdf10644d6ee345c7b5277c3fa009c1bedb92d60"
jobs="${JOBS:-2}"
glibc_out="tooling/build/glibc-archphene-runtime-x86_64"
stage="tooling/build/ci-package-runtime"
container_cli="${CONTAINER_CLI:-docker}"

mkdir -p tooling/build
build_root="$(mktemp -d "$root/tooling/build/ci-runtime.XXXXXX")"
trap 'rm -rf "$build_root"' EXIT
container_out="$build_root/linux-runtime"
mkdir -p "$container_out"

container_volume="$container_out"
patch_volume="$root/patches/glibc"
msys_arg_excl=""
if command -v cygpath >/dev/null 2>&1 && [[ "$container_cli" == *podman* ]]; then
  container_volume="$(cygpath -w "$container_out")"
  patch_volume="$(cygpath -w "$patch_volume")"
  msys_arg_excl="*"
fi

MSYS2_ARG_CONV_EXCL="$msys_arg_excl" $container_cli run --rm \
  -e HOST_UID="$(id -u)" \
  -e HOST_GID="$(id -g)" \
  -e SKIP_CHOWN="${SKIP_CHOWN:-0}" \
  -e GLIBC_COMMIT="$glibc_commit" \
  -e JOBS="$jobs" \
  -v "$container_volume:/out" \
  -v "$patch_volume:/archphene-patches:ro" \
  archlinux:base-devel bash -c '
set -euo pipefail
pacman-key --init
pacman-key --populate archlinux
pacman -Syu --noconfirm --needed \
  archlinux-keyring bison gawk git gnupg libarchive pacman python texinfo
mkdir -p /out/runtime-root/usr/bin /out/runtime-root/usr/lib /out/keyrings

declare -A provided resolved seen
while IFS= read -r -d "" file; do
  name="$(basename "$file")"
  [[ -v "provided[$name]" ]] || provided["$name"]="$file"
done < <(find /usr/lib -maxdepth 1 \( -type f -o -type l \) -print0)

queue=(/usr/bin/pacman /usr/bin/gpg /usr/bin/gpgv /usr/bin/bsdtar)
for ((index=0; index<${#queue[@]}; index++)); do
  object="${queue[$index]}"
  canonical="$(readlink -f "$object")"
  [[ -v "seen[$canonical]" ]] && continue
  seen["$canonical"]=1
  while IFS= read -r needed; do
    [[ -n "$needed" ]] || continue
    if [[ -v "provided[$needed]" ]]; then
      target="${provided[$needed]}"
      resolved["$needed"]="$target"
      queue+=("$target")
    fi
  done < <(readelf -d "$canonical" 2>/dev/null |
    sed -n "s/.*Shared library: \[\([^]]*\)\].*/\1/p")
done

for name in pacman gpg gpgv bsdtar; do
  cp -L "/usr/bin/$name" "/out/runtime-root/usr/bin/$name"
done
: > /out/elf-needed-resolved.tsv
while IFS= read -r name; do
  cp -L "${resolved[$name]}" "/out/runtime-root/usr/lib/$name"
  printf "%s\tusr/lib/%s\n" "$name" "$name" >> /out/elf-needed-resolved.tsv
done < <(printf "%s\n" "${!resolved[@]}" | sort)
cp -a /usr/share/pacman/keyrings/. /out/keyrings/
pacman -Q archlinux-keyring gnupg libarchive pacman > /out/package-versions.txt

source=/tmp/glibc-source
obj=/tmp/glibc-obj
install=/tmp/glibc-install
git -c advice.detachedHead=false clone --filter=blob:none --no-checkout \
  https://sourceware.org/git/glibc.git "$source"
git -C "$source" fetch --depth 1 origin "$GLIBC_COMMIT"
git -C "$source" checkout --detach "$GLIBC_COMMIT"
git -C "$source" apply /archphene-patches/0001-android-app-seccomp-compat.patch
mkdir "$obj"
(
  cd "$obj"
  CPPFLAGS="-DARCHPHENE_ANDROID_APP_COMPAT=1" "$source/configure" \
    --prefix=/usr --disable-werror --enable-kernel=5.10
  make -s -j"$JOBS"
  make -s install DESTDIR="$install"
)

mkdir -p /out/glibc
runtime_files=(
  ld-linux-x86-64.so.2 libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0
  librt.so.1 libresolv.so.2 libutil.so.1 libanl.so.1 libnss_dns.so.2
  libnss_files.so.2
)
for name in "${runtime_files[@]}"; do
  source_file="$(find "$install" \( -type f -o -type l \) \
    -name "$name" -print -quit)"
  [[ -n "$source_file" ]] || {
    echo "missing patched glibc file: $name" >&2
    exit 1
  }
  cp -L "$source_file" "/out/glibc/$name"
done
printf "%s\n" "$GLIBC_COMMIT" > /out/glibc/source-commit.txt

if [[ "$SKIP_CHOWN" != "1" ]]; then
  chown -R "$HOST_UID:$HOST_GID" /out
fi
'

rm -rf "$glibc_out"
cp -a "$container_out/glibc" "$glibc_out"

rm -rf "$stage"
pacman_stage="$stage/tooling/downloads/arch-runtime-pacman-x86_64"
keyring_stage="$stage/tooling/downloads/arch-runtime-archlinux-keyring-x86_64"
mkdir -p "$pacman_stage" \
  "$keyring_stage/runtime-root/usr/share/pacman/keyrings" \
  "$stage/tooling/build"
cp -a "$container_out/runtime-root" "$pacman_stage/"
cp "$container_out/elf-needed-resolved.tsv" "$pacman_stage/"
cp "$container_out/package-versions.txt" "$pacman_stage/"
cp -a "$container_out/keyrings/." \
  "$keyring_stage/runtime-root/usr/share/pacman/keyrings/"
cp -a "$glibc_out" "$stage/tooling/build/"

(cd "$stage" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum) \
  > "$stage/SHA256SUMS"
echo "Prepared verified package runtime artifact at $stage"
