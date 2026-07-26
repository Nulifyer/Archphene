#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command podman

image=localhost/archphene-android-native:ndk29-rust1.88
archphene_podman_image_exists "$image" || archphene_die \
  "missing existing build image $image; this script will not download or install it"

target_dir="$ARCHPHENE_ROOT/tooling/build/rust-next"
manager_jni_dir="$ARCHPHENE_ROOT/android/app/build/generated/jniLibs"
builder_jni_dir="$ARCHPHENE_ROOT/android/builder/build/generated/jniLibs"

podman run --rm --network=none \
  -v "$ARCHPHENE_ROOT:/workspace" \
  -v archphene-cargo-registry:/opt/cargo/registry \
  -w /workspace \
  -e CARGO_TARGET_DIR=/workspace/tooling/build/rust-next \
  "$image" \
  bash -lc 'ndk_bin=/opt/android-sdk-linux/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin
    export CC_x86_64_linux_android="$ndk_bin/x86_64-linux-android29-clang"
    export CXX_x86_64_linux_android="$ndk_bin/x86_64-linux-android29-clang++"
    export AR_x86_64_linux_android="$ndk_bin/llvm-ar"
    export CC_aarch64_linux_android="$ndk_bin/aarch64-linux-android29-clang"
    export CXX_aarch64_linux_android="$ndk_bin/aarch64-linux-android29-clang++"
    export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
    cargo build --release --locked --offline -p archphene-android --target x86_64-linux-android &&
    cargo build --release --locked --offline -p archphene-builder --target x86_64-linux-android &&
    cargo build --release --locked --offline -p archphene-android --target aarch64-linux-android &&
    cargo build --release --locked --offline -p archphene-builder --target aarch64-linux-android'

install -Dm755 \
  "$target_dir/x86_64-linux-android/release/libarchphene_android.so" \
  "$manager_jni_dir/x86_64/libarchphene_android.so"
install -Dm755 \
  "$target_dir/aarch64-linux-android/release/libarchphene_android.so" \
  "$manager_jni_dir/arm64-v8a/libarchphene_android.so"
install -Dm755 \
  "$target_dir/x86_64-linux-android/release/libarchphene_builder.so" \
  "$builder_jni_dir/x86_64/libarchphene_builder.so"
install -Dm755 \
  "$target_dir/aarch64-linux-android/release/libarchphene_builder.so" \
  "$builder_jni_dir/arm64-v8a/libarchphene_builder.so"

archphene_note "Manager Rust JNI libraries: $manager_jni_dir"
archphene_note "Builder Rust JNI libraries: $builder_jni_dir"
