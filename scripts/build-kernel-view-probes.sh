#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command podman
archphene_require_command readelf

x86_image=docker.io/library/archlinux:base-devel
arm_image=localhost/archphene-arm-runtime-builder:latest
archphene_podman_image_exists "$x86_image" ||
  archphene_die "missing existing build image: $x86_image"
archphene_podman_image_exists "$arm_image" ||
  archphene_die "missing existing build image: $arm_image"

source_root="$ARCHPHENE_ROOT/native/archphene-glibc-path-bridge"
output="$ARCHPHENE_ROOT/tooling/build/kernel-view"
work="$(archphene_mktemp_dir kernel-view-probes)"
cleanup() {
  rm -rf -- "$work"
}
trap cleanup EXIT

podman run --rm --network=none \
  -v "$source_root:/source:ro" \
  -v "$work:/out" \
  "$x86_image" \
  gcc -O2 -Wall -Wextra -Werror \
    -o /out/kernel-view-x86_64 /source/kernel_view_probe.c

podman run --rm --network=none \
  -v "$source_root:/source:ro" \
  -v "$work:/out" \
  "$arm_image" \
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -Werror \
    -o /out/kernel-view-aarch64 /source/kernel_view_probe.c

[[ "$(readelf -h "$work/kernel-view-x86_64" |
  sed -n 's/.*Machine:[[:space:]]*//p')" == "Advanced Micro Devices X86-64" ]] ||
  archphene_die "x86_64 kernel-view probe has the wrong machine"
[[ "$(readelf -h "$work/kernel-view-aarch64" |
  sed -n 's/.*Machine:[[:space:]]*//p')" == "AArch64" ]] ||
  archphene_die "AArch64 kernel-view probe has the wrong machine"

mkdir -p "$output"
install -m755 "$work/kernel-view-x86_64" "$output/kernel-view-x86_64"
install -m755 "$work/kernel-view-aarch64" "$output/kernel-view-aarch64"
archphene_note "Kernel-view probes: $output"
