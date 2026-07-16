#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/tooling/build/libsecret-probe/x86_64"
glibc="$root/tooling/build/glibc-archphene-runtime-x86_64"
kwallet_compat="$root/tooling/build/kwallet-compat/x86_64/kwalletd6"

[[ -f "$glibc/ld-linux-x86-64.so.2" ]] || {
  echo "Build the x86_64 package runtime before the libsecret probe" >&2
  exit 1
}
[[ -x "$kwallet_compat" ]] || {
  echo "Build the KWallet compatibility daemon before the libsecret probe" >&2
  exit 1
}

rm -rf "$out"
mkdir -p "$out/lib"
pacman -Syu --noconfirm --needed kwallet libsecret >/dev/null
installed_kwallet="$(pacman -Q kwallet | awk '{print $2}')"
[[ "$installed_kwallet" == 6.28.0-* ]] || {
  echo "Expected Arch kwallet 6.28.0, found ${installed_kwallet}" >&2
  exit 1
}
pacman -Q kwallet libsecret glib2 qt6-base > "$out/PACKAGE_VERSIONS"
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
cp -L "$glibc/ld-linux-x86-64.so.2" "$out/lib/ld-linux-x86-64.so.2"
mkdir -p "$out/qt/plugins/platforms"
cp -L "$qt_platform" "$out/qt/plugins/platforms/libqminimal.so"
printf "%s\n" "${!resolved[@]}" | sort > "$out/needed.txt"
(
  cd "$out"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum
) > "$out/SHA256SUMS"
echo "Arch libsecret probe runtime: $out"
