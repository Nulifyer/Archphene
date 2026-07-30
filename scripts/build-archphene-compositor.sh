#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command podman

image=localhost/archphene-android-native:ndk29-rust1.88
archphene_podman_image_exists "$image" || archphene_die \
  "missing existing build image $image; this script will not download or install it"

target_dir="$ARCHPHENE_ROOT/tooling/build/compositor-next"
jni_dir="$ARCHPHENE_ROOT/android/app/build/generated/compositorJniLibs"

podman run --rm --network=none \
  -v "$ARCHPHENE_ROOT:/workspace" \
  -v archphene-cargo-registry:/opt/cargo/registry \
  -w /workspace \
  -e CARGO_TARGET_DIR=/workspace/tooling/build/compositor-next \
  -e RUSTUP_TOOLCHAIN=1.88.0 \
  "$image" \
  bash -lc 'cargo build --release --locked --offline -p archphene-compositor --target x86_64-linux-android &&
    cargo build --release --locked --offline -p archphene-compositor --target aarch64-linux-android'

install -Dm755 \
  "$target_dir/x86_64-linux-android/release/libarchphene_compositor.so" \
  "$jni_dir/x86_64/libarchphene_compositor.so"
install -Dm755 \
  "$target_dir/aarch64-linux-android/release/libarchphene_compositor.so" \
  "$jni_dir/arm64-v8a/libarchphene_compositor.so"

archphene_note "Rust compositor JNI libraries: $jni_dir"
