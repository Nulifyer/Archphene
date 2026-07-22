#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

architecture=x86_64
rebuild_image=false
release=false
while (($#)); do
  case "$1" in
    --architecture) architecture="${2:?missing value for --architecture}"; shift 2 ;;
    --rebuild-image) rebuild_image=true; shift ;;
    --release) release=true; shift ;;
    -h|--help)
      echo "usage: $0 [--architecture x86_64|aarch64] [--rebuild-image] [--release]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_validate_choice "$architecture" architecture x86_64 aarch64
archphene_require_command podman

image=localhost/archphene-android-native:ndk29-rust1.88
target=x86_64-linux-android
[[ "$architecture" == aarch64 ]] && target=aarch64-linux-android
if [[ "$rebuild_image" == true ]] || ! archphene_podman_image_exists "$image"; then
  podman build -f "$ARCHPHENE_ROOT/containers/android-native.Containerfile" -t "$image" "$ARCHPHENE_ROOT"
fi

cargo_args=(build --target "$target")
[[ -f "$ARCHPHENE_ROOT/native/archphene-compositor/Cargo.lock" ]] && cargo_args+=(--locked)
profile=debug
if [[ "$release" == true ]]; then
  cargo_args+=(--release)
  profile=release
fi
printf -v command ' %q' "${cargo_args[@]}"
podman run --rm \
  -v "$ARCHPHENE_ROOT:/workspace" \
  -v archphene-cargo-registry:/opt/cargo/registry \
  -w /workspace/native/archphene-compositor \
  "$image" bash -lc "cargo${command}"

library="$ARCHPHENE_ROOT/native/archphene-compositor/target/$target/$profile/libarchphene_compositor.so"
archphene_require_file "$library"
archphene_note "Native compositor library: $library"

