#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace \
  docker.io/library/archlinux:base-devel bash -lc \
  "pacman -Sy --noconfirm --needed pipewire gstreamer gst-plugin-pipewire gst-plugins-base jq >/dev/null && bash scripts/test-pipewire-camera-runtime.sh"

