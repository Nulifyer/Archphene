#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/tooling/build/pipewire-camera/x86_64"
mkdir -p "$out"
cc -std=c11 -O2 -Wall -Wextra -Werror -pthread   -I"$root/native/archphene-android-capability"   $(pkg-config --cflags libpipewire-0.3)   "$root/native/archphene-pipewire-camera/archphene_pipewire_camera.c"   "$root/native/archphene-android-capability/archphene_android.c"   -o "$out/archphene-pipewire-camera"   $(pkg-config --libs libpipewire-0.3)
readelf -h "$out/archphene-pipewire-camera"   | grep -F "Advanced Micro Devices X86-64" >/dev/null
pacman -Q pipewire libpipewire > "$out/PACKAGE_VERSIONS"
(
  cd "$out"
  sha256sum archphene-pipewire-camera PACKAGE_VERSIONS > SHA256SUMS
)
echo "Archphene PipeWire camera producer: $out/archphene-pipewire-camera"
