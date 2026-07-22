#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

architecture=x86_64
rebuild_image=false
while (($#)); do
  case "$1" in
    --architecture) architecture="${2:?missing value for --architecture}"; shift 2 ;;
    --rebuild-image) rebuild_image=true; shift ;;
    -h|--help) echo "usage: $0 [--architecture x86_64|aarch64] [--rebuild-image]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_validate_choice "$architecture" architecture x86_64 aarch64
archphene_require_command podman
image=localhost/archphene-android-native:ndk29-rust1.88
if [[ "$rebuild_image" == true ]] || ! archphene_podman_image_exists "$image"; then
  podman build -f "$ARCHPHENE_ROOT/containers/android-native.Containerfile" -t "$image" "$ARCHPHENE_ROOT"
fi
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$image" \
  bash scripts/build-android-gpu-helper.sh "$architecture"
archphene_require_file "$ARCHPHENE_ROOT/tooling/build/android-gpu/$architecture/virgl_test_server_android"
archphene_note "Android GPU helper built for $architecture."

