#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

architecture=x86_64
while (($#)); do
  case "$1" in
    --architecture) architecture="${2:?missing value for --architecture}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--architecture x86_64|aarch64]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_validate_choice "$architecture" architecture x86_64 aarch64
source_dir="$ARCHPHENE_ROOT/tooling/external/pipewire"
expected=b741e0c74f5436f0c925f7741140db0efd32cf4e
upstream=https://gitlab.freedesktop.org/pipewire/pipewire.git
if [[ ! -d "$source_dir/.git" ]]; then
  [[ ! -e "$source_dir" ]] || archphene_die "unmanaged PipeWire source exists: $source_dir"
  mkdir -p "$source_dir"
  git init -q "$source_dir"
  git -C "$source_dir" remote add origin "$upstream"
fi
origin="$(git -C "$source_dir" remote get-url origin)"
[[ "$origin" == "$upstream" ]] || archphene_die "PipeWire source origin is not official: $origin"
current="$(git -C "$source_dir" rev-parse HEAD 2>/dev/null || true)"
if [[ "$current" != "$expected" ]]; then
  [[ -z "$(git -C "$source_dir" status --porcelain)" ]] || \
    archphene_die "PipeWire source has local changes and cannot select $expected"
  git -C "$source_dir" fetch --depth=1 origin "$expected"
  git -C "$source_dir" checkout --detach FETCH_HEAD
fi
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$expected" ]] || archphene_die "PipeWire revision verification failed"
image=localhost/archphene-arm-runtime-builder:latest
if ! archphene_podman_image_exists "$image"; then
  podman build -f "$ARCHPHENE_ROOT/containers/arm-runtime-builder.Containerfile" -t "$image" "$ARCHPHENE_ROOT"
fi
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$image" bash -lc \
  "pacman -Sy --noconfirm --needed meson >/dev/null && bash scripts/build-pipewire-camera-runtime.sh $architecture"

