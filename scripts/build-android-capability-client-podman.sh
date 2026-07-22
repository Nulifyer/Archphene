#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

rebuild_arm_image=false
while (($#)); do
  case "$1" in
    --rebuild-arm-image) rebuild_arm_image=true; shift ;;
    -h|--help) echo "usage: $0 [--rebuild-arm-image]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_require_command podman
arm_image=localhost/archphene-arm-runtime-builder:latest
if [[ "$rebuild_arm_image" == true ]] || ! archphene_podman_image_exists "$arm_image"; then
  podman build -f "$ARCHPHENE_ROOT/containers/arm-runtime-builder.Containerfile" -t "$arm_image" "$ARCHPHENE_ROOT"
fi

x86_command="mkdir -p tooling/build/android-capability/x86_64 && gcc -shared -fPIC -O2 -Wall -Wextra -Werror -o tooling/build/android-capability/x86_64/libarchphene_android.so native/archphene-android-capability/archphene_android.c && readelf -h tooling/build/android-capability/x86_64/libarchphene_android.so | grep -F 'Advanced Micro Devices X86-64'"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace docker.io/library/archlinux:base-devel sh -lc "$x86_command"
arm_command="mkdir -p tooling/build/android-capability/aarch64 && aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Werror -o tooling/build/android-capability/aarch64/libarchphene_android.so native/archphene-android-capability/archphene_android.c && aarch64-linux-gnu-readelf -h tooling/build/android-capability/aarch64/libarchphene_android.so | grep -F AArch64"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$arm_image" sh -lc "$arm_command"
archphene_note "glibc Android capability clients built for x86_64 and AArch64."

