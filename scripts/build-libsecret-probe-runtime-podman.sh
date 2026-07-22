#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace \
  docker.io/library/archlinux:base-devel bash scripts/build-libsecret-probe-runtime.sh

