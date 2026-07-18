#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
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
    echo "Unsupported libsecret probe architecture: $machine" >&2
    exit 1
    ;;
esac
out="$root/tooling/build/libsecret-probe/$architecture"
glibc="$root/tooling/build/glibc-archphene-runtime-$architecture"
kwallet_compat="$root/tooling/build/kwallet-compat/$architecture/kwalletd6"

[[ -f "$glibc/$loader" ]] || {
  echo "Build the $architecture patched glibc runtime before the libsecret probe" >&2
  exit 1
}
[[ -x "$kwallet_compat" ]] || {
  echo "Build the $architecture KWallet compatibility daemon before the libsecret probe" >&2
  exit 1
}

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
pacman_retry -S --noconfirm --needed kwallet libsecret

rm -rf "$out"
mkdir -p "$out/lib"
installed_kwallet="$(pacman -Q kwallet | awk '{print $2}')"
[[ "$installed_kwallet" == 6.28.0-* ]] || {
  echo "Expected Arch kwallet 6.28.0, found ${installed_kwallet}" >&2
  exit 1
}
pacman -Q glib2 glibc kwallet libsecret qt6-base > "$out/PACKAGE_VERSIONS"
cp /usr/bin/secret-tool "$out/secret-tool"
cp /usr/bin/gdbus "$out/gdbus"
cp "$kwallet_compat" "$out/kwalletd6"
cp /usr/bin/kwallet-query "$out/kwallet-query"
qt_platform=/usr/lib/qt6/plugins/platforms/libqminimal.so
[[ -f "$qt_platform" ]] || { echo "Qt minimal platform plugin is missing" >&2; exit 1; }

declare -A provided resolved seen
while IFS= read -r -d "" file; do
  name="$(basename "$file")"
  [[ -v "provided[$name]" ]] || provided["$name"]="$file"
done < <(find /usr/lib -maxdepth 1 \( -type f -o -type l \) -print0)

queue=(/usr/bin/secret-tool /usr/bin/gdbus "$kwallet_compat" /usr/bin/kwallet-query "$qt_platform")
for ((index=0; index<${#queue[@]}; index++)); do
  object="$(readlink -f "${queue[$index]}")"
  [[ -v "seen[$object]" ]] && continue
  seen["$object"]=1
  while IFS= read -r needed; do
    [[ -n "$needed" ]] || continue
    if [[ -v "provided[$needed]" ]]; then
      resolved["$needed"]="${provided[$needed]}"
      queue+=("${provided[$needed]}")
    else
      echo "Unresolved $architecture runtime library: $needed (from $object)" >&2
      exit 1
    fi
  done < <(readelf -d "$object" 2>/dev/null |
    sed -n "s/.*Shared library: \[\([^]]*\)\].*/\1/p")
done

for name in "${!resolved[@]}"; do
  cp -L "${resolved[$name]}" "$out/lib/$name"
done
for file in "$glibc"/*.so.*; do
  cp -L "$file" "$out/lib/$(basename "$file")"
done
cp -L "$glibc/$loader" "$out/lib/$loader"
mkdir -p "$out/qt/plugins/platforms"
cp -L "$qt_platform" "$out/qt/plugins/platforms/libqminimal.so"
printf "%s\n" "${!resolved[@]}" | sort > "$out/needed.txt"

for object in "$out/secret-tool" "$out/gdbus" "$out/kwalletd6" \
    "$out/kwallet-query" "$out/qt/plugins/platforms/libqminimal.so" "$out"/lib/*; do
  readelf -h "$object" | grep -F 'Machine:' | grep -F "$expected_machine" >/dev/null
  if [[ "$architecture" == aarch64 ]]; then
    while read -r alignment; do
      (( alignment >= 0x4000 )) || {
        echo "AArch64 runtime LOAD alignment is below 16 KB: $object $alignment" >&2
        exit 1
      }
    done < <(readelf -lW "$object" | awk '/ LOAD / { print $NF }')
  fi
done
(
  cd "$out"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum
) > "$out/SHA256SUMS"
echo "Arch libsecret and KWallet probe runtime: $out"
