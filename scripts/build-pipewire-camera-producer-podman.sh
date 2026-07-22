#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace \
  docker.io/library/archlinux:base-devel bash -lc \
  "pacman -Sy --noconfirm --needed pipewire pkgconf >/dev/null && bash scripts/build-pipewire-camera-producer.sh"

