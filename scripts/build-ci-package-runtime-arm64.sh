#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

container_cli="${CONTAINER_CLI:-podman}"
keyring_commit="09fcb20da1bd9504bed249da1bc0d08f86e8bd56"
glibc_version="2.43"
glibc_sha256="d9c86c6b5dbddb43a3e08270c5844fc5177d19442cf5b8df4be7c07cd5fa3831"
build_key="68B3537F39A313B3E574D06777193F152BDBE6A6"
mirror="https://ca.us.mirror.archlinuxarm.org"
jobs="${JOBS:-2}"
stage="tooling/build/ci-package-runtime-arm64"
builder_image="archphene-arm-runtime-builder:latest"


mkdir -p tooling/build
work="$(mktemp -d "$root/tooling/build/ci-runtime-arm64.XXXXXX")"
trap 'rm -rf "$work"' EXIT
container_out="$work/linux-runtime"
mkdir -p "$container_out"

container_volume="$container_out"
patch_volume="$root/patches/glibc"
path_bridge_volume="$root/native/archphene-glibc-path-bridge"

"$container_cli" build \
  -f containers/arm-runtime-builder.Containerfile -t "$builder_image" .

"$container_cli" run --rm \
  -e HOST_UID="$(id -u)" \
  -e HOST_GID="$(id -g)" \
  -e SKIP_CHOWN="${SKIP_CHOWN:-0}" \
  -e KEYRING_COMMIT="$keyring_commit" \
  -e GLIBC_VERSION="$glibc_version" \
  -e GLIBC_SHA256="$glibc_sha256" \
  -e BUILD_KEY="$build_key" \
  -e MIRROR="$mirror" \
  -e JOBS="$jobs" \
  -v "$container_volume:/out" \
  -v "$patch_volume:/archphene-patches:ro" \
  -v "$path_bridge_volume:/archphene-path-bridge:ro" \
  -v archphene-arm-package-cache:/package-cache \
  "$builder_image" bash -c '
set -euo pipefail
keyring_source=/tmp/archlinuxarm-pkgbuilds
git clone --filter=blob:none --no-checkout \
  https://github.com/archlinuxarm/PKGBUILDs.git "$keyring_source"
git -C "$keyring_source" sparse-checkout set core/archlinuxarm-keyring
git -C "$keyring_source" checkout --detach "$KEYRING_COMMIT"
[[ "$(git -C "$keyring_source" rev-parse HEAD)" == "$KEYRING_COMMIT" ]]
keyring="$keyring_source/core/archlinuxarm-keyring"
[[ -s "$keyring/archlinuxarm.gpg" ]]
[[ -f "$keyring/archlinuxarm-revoked" ]]
[[ -s "$keyring/archlinuxarm-trusted" ]]
grep -Eq "^${BUILD_KEY}:" "$keyring/archlinuxarm-trusted"
gpg --batch --show-keys --with-colons "$keyring/archlinuxarm.gpg" \
  | grep "^fpr:" | cut -d: -f10 | tr "[:lower:]" "[:upper:]" \
  | grep -Fx "$BUILD_KEY"

cat > /tmp/pacman-arm.conf <<EOF
[options]
Architecture = aarch64
SigLevel = Never
LocalFileSigLevel = Never

[core]
Server = $MIRROR/aarch64/core

[extra]
Server = $MIRROR/aarch64/extra
EOF
mkdir -p /tmp/arm/{root,db/sync,cache,packages,expanded}
pacman --config /tmp/pacman-arm.conf --root /tmp/arm/root \
  --dbpath /tmp/arm/db --cachedir /tmp/arm/cache -Sy
pacman --config /tmp/pacman-arm.conf --root /tmp/arm/root \
  --dbpath /tmp/arm/db --cachedir /tmp/arm/cache -Sp \
  --print-format "%n|%v|%r|%l|%f" \
  pacman gnupg libarchive archlinuxarm-keyring > /tmp/arm/transaction.tsv

gnupg=/tmp/arm/gnupg
mkdir -m 700 "$gnupg"
gpg --homedir "$gnupg" --batch --import "$keyring/archlinuxarm.gpg"
while IFS="|" read -r name version repository url filename; do
  [[ "$repository" =~ ^(core|extra)$ ]]
  [[ "$url" == "$MIRROR/aarch64/$repository/"* ]]
  [[ "$filename" =~ -(aarch64|any)\.pkg\.tar\.(xz|zst)$ ]]
  package="/package-cache/$filename"
  verified=false
  for attempt in 1 2; do
    if [[ ! -s "$package" || ! -s "$package.sig" ]]; then
      curl --proto =https --tlsv1.2 --fail --location --retry 3 \
        --silent --show-error --output "$package" "$url"
      curl --proto =https --tlsv1.2 --fail --location --retry 3 \
        --silent --show-error --output "$package.sig" "$url.sig"
    fi
    status="$(gpgv --homedir "$gnupg" --keyring "$gnupg/pubring.kbx" \
      --status-fd 1 "$package.sig" "$package" 2>&1)" || status=""
    signer="$(printf "%s\n" "$status" | sed -n \
      "s/^\[GNUPG:\] VALIDSIG \([0-9A-Fa-f]*\) .*/\U\1/p" | head -1)"
    if [[ "$signer" == "$BUILD_KEY" ]]; then
      verified=true
      break
    fi
    rm -f "$package" "$package.sig"
  done
  [[ "$verified" == true ]] || {
    echo "ARM package signature verification failed for $filename" >&2
    exit 1
  }
  bsdtar -xf "$package" -C /tmp/arm/expanded
done < /tmp/arm/transaction.tsv

for binary in pacman gpg gpgv bsdtar; do
  [[ -x "/tmp/arm/expanded/usr/bin/$binary" ]] || {
    echo "missing AArch64 runtime binary: $binary" >&2; exit 1;
  }
  machine="$(readelf -h "/tmp/arm/expanded/usr/bin/$binary" | sed -n "s/.*Machine:[[:space:]]*//p")"
  [[ "$machine" == "AArch64" ]] || {
    echo "$binary has unexpected ELF machine: $machine" >&2; exit 1;
  }
done

mkdir -p /out/runtime-root/usr/{bin,lib} /out/keyrings
declare -A provided resolved seen
while IFS= read -r -d "" file; do
  name="$(basename "$file")"
  [[ -v "provided[$name]" ]] || provided["$name"]="$file"
done < <(find /tmp/arm/expanded/usr/lib /tmp/arm/expanded/lib \
  -maxdepth 1 \( -type f -o -type l \) -print0 2>/dev/null)

queue=(/tmp/arm/expanded/usr/bin/pacman /tmp/arm/expanded/usr/bin/gpg \
  /tmp/arm/expanded/usr/bin/gpgv /tmp/arm/expanded/usr/bin/bsdtar)
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
    else
      echo "unresolved AArch64 runtime library: $needed (from $canonical)" >&2
      exit 1
    fi
  done < <(readelf -d "$canonical" 2>/dev/null \
    | sed -n "s/.*Shared library: \[\([^]]*\)\].*/\1/p")
done

for name in pacman gpg gpgv bsdtar; do
  cp -L "/tmp/arm/expanded/usr/bin/$name" "/out/runtime-root/usr/bin/$name"
done
: > /out/elf-needed-resolved.tsv
while IFS= read -r name; do
  cp -L "${resolved[$name]}" "/out/runtime-root/usr/lib/$name"
  printf "%s\tusr/lib/%s\n" "$name" "$name" >> /out/elf-needed-resolved.tsv
done < <(printf "%s\n" "${!resolved[@]}" | sort)
cp "$keyring/archlinuxarm.gpg" "$keyring/archlinuxarm-revoked" \
  "$keyring/archlinuxarm-trusted" /out/keyrings/
cp /tmp/arm/transaction.tsv /out/package-versions.tsv

source=/tmp/glibc-source
obj=/tmp/glibc-obj
install=/tmp/glibc-install
archive=/tmp/glibc.tar.xz
mkdir "$source"
curl --proto =https --tlsv1.2 --fail --location --retry 3 \
  --silent --show-error --output "$archive" \
  "https://ftp.gnu.org/gnu/glibc/glibc-$GLIBC_VERSION.tar.xz"
printf "%s  %s\n" "$GLIBC_SHA256" "$archive" | sha256sum -c -
tar -xJf "$archive" --strip-components=1 -C "$source"
patch -d "$source" -p1 < /archphene-patches/0001-android-app-seccomp-compat.patch
mkdir "$obj"
(
  cd "$obj"
  CPPFLAGS="-DARCHPHENE_ANDROID_APP_COMPAT=1" "$source/configure" \
    --prefix=/usr --build=x86_64-pc-linux-gnu --host=aarch64-linux-gnu \
    --disable-werror --enable-kernel=5.10
  make -s -j"$JOBS"
  make -s install DESTDIR="$install"
)

mkdir -p /out/glibc
runtime_files=(
  ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0
  librt.so.1 libresolv.so.2 libutil.so.1 libanl.so.1 libnss_dns.so.2
  libnss_files.so.2
)
for name in "${runtime_files[@]}"; do
  source_file="$(find "$install" \( -type f -o -type l \) \
    -name "$name" -print -quit)"
  [[ -n "$source_file" ]] || { echo "missing patched ARM glibc file: $name" >&2; exit 1; }
  cp -L "$source_file" "/out/glibc/$name"
  [[ "$(readelf -h "/out/glibc/$name" | sed -n "s/.*Machine:[[:space:]]*//p")" == "AArch64" ]]
done
mkdir -p /out/path-bridge
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
  -o /out/path-bridge/libarchphene_path_bridge.so \
  /archphene-path-bridge/path_bridge.c -ldl
[[ "$(readelf -h /out/path-bridge/libarchphene_path_bridge.so \
  | sed -n "s/.*Machine:[[:space:]]*//p")" == "AArch64" ]]

printf "glibc-%s+sha256.%s\n" "$GLIBC_VERSION" "$GLIBC_SHA256" \
  > /out/glibc/source-commit.txt
printf "%s\n" "$KEYRING_COMMIT" > /out/keyring-source-commit.txt
printf "%s\n" "$BUILD_KEY" > /out/package-signing-fingerprint.txt

if [[ "$SKIP_CHOWN" != "1" ]]; then
  chown -R "$HOST_UID:$HOST_GID" /out
fi
'

rm -rf "$stage"
pacman_stage="$stage/tooling/downloads/arch-runtime-pacman-aarch64"
keyring_stage="$stage/tooling/downloads/arch-runtime-archlinuxarm-keyring-aarch64"
mkdir -p "$pacman_stage" \
  "$keyring_stage/runtime-root/usr/share/pacman/keyrings" \
  "$stage/tooling/build"
cp -a "$container_out/runtime-root" "$pacman_stage/"
cp "$container_out/elf-needed-resolved.tsv" "$pacman_stage/"
cp "$container_out/package-versions.tsv" "$pacman_stage/"
cp -a "$container_out/keyrings/." \
  "$keyring_stage/runtime-root/usr/share/pacman/keyrings/"
cp -a "$container_out/glibc" "$stage/tooling/build/glibc-archphene-runtime-aarch64"
cp -a "$container_out/path-bridge" \
  "$stage/tooling/build/archphene-path-bridge-aarch64"
cp "$container_out/keyring-source-commit.txt" \
  "$container_out/package-signing-fingerprint.txt" "$stage/"

(cd "$stage" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum) \
  > "$stage/SHA256SUMS"
echo "Prepared verified AArch64 package runtime artifact at $stage"
