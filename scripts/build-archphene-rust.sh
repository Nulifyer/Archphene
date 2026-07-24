#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command podman

image=localhost/archphene-android-native:ndk29-rust1.88
archphene_podman_image_exists "$image" || archphene_die \
  "missing existing build image $image; this script will not download or install it"

target_dir="$ARCHPHENE_ROOT/tooling/build/rust-next"
jni_dir="$ARCHPHENE_ROOT/android/app/build/generated/jniLibs"

podman run --rm --network=none \
  -v "$ARCHPHENE_ROOT:/workspace" \
  -v archphene-cargo-registry:/opt/cargo/registry \
  -w /workspace \
  -e CARGO_TARGET_DIR=/workspace/tooling/build/rust-next \
  "$image" \
  bash -lc 'cargo build --release --locked --offline -p archphene-android --target x86_64-linux-android &&
    cargo build --release --locked --offline -p archphene-android --target aarch64-linux-android'

install -Dm755 \
  "$target_dir/x86_64-linux-android/release/libarchphene_android.so" \
  "$jni_dir/x86_64/libarchphene_android.so"
install -Dm755 \
  "$target_dir/aarch64-linux-android/release/libarchphene_android.so" \
  "$jni_dir/arm64-v8a/libarchphene_android.so"

archphene_note "Rust JNI libraries: $jni_dir"
