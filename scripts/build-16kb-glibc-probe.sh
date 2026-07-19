#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/tooling/build/16kb-probe}"
flags=(-Wl,-z,max-page-size=65536 -Wl,-z,common-page-size=16384)

rm -rf "$out"
mkdir -p "$out"
gcc -O2 "${flags[@]}" -o "$out/runtime-probe-dynamic" \
  "$root/prototypes/linux-app-manager-stub/assets/runtime-probe-dynamic.c"

while read -r alignment; do
  (( alignment >= 0x4000 )) || {
    echo "Probe LOAD alignment is below 16 KB: $alignment" >&2
    exit 1
  }
done < <(readelf -lW "$out/runtime-probe-dynamic" | awk '/ LOAD / { print $NF }')

echo "Built 16 KB-compatible glibc probe at $out/runtime-probe-dynamic"
