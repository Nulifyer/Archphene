#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

image=docker.io/library/archlinux:base-devel
while (($#)); do
  case "$1" in
    --image) image="${2:?missing value for --image}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--image IMAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_require_command podman
archphene_podman_image_exists "$image" ||
  archphene_die "missing local container image: $image"

podman run --rm --pull=never \
  -v "$ARCHPHENE_ROOT:/workspace" -w /workspace \
  "$image" bash -lc '
set -euo pipefail
pacman -Sy --noconfirm --needed pipewire pkgconf >/dev/null
bash scripts/build-pipewire-camera-producer.sh
bash scripts/test-pipewire-camera-producer.sh
'
archphene_note "Containerized PipeWire camera producer gate passed."
