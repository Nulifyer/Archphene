#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
sdk="$(archphene_android_sdk)"
ndk_bin="$sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin"
clang="$ndk_bin/aarch64-linux-android35-clang"
readelf="$ndk_bin/llvm-readelf"
archphene_require_file "$clang"
archphene_require_file "$readelf"
app="$ARCHPHENE_ROOT/prototypes/mousepad-android-app"
output="$ARCHPHENE_ROOT/tooling/build/arm64-bridge-readiness"
include="$app/wayland-include"
mkdir -p "$output"
jni="$output/libarchphene_wayland_jni.so"
client="$output/libarchphene_wayland_client_android.so"
frame="$output/archphene_frame_client"
"$clang" -shared -fPIC -O2 -Wall -Wextra -o "$jni" "$app/wayland_socket_jni.c"
"$clang" -shared -fPIC -O2 -Wall -Wextra -I "$include" -Wl,-soname,libarchphene_wayland_client_android.so -o "$client" "$app/archphene_wayland_client_android.c"
"$clang" -fPIE -pie -O2 -Wall -Wextra -o "$frame" "$app/archphene_frame_client.c"
printf '%-42s %12s %s\n' FILE BYTES SHA256
for file in "$jni" "$client" "$frame"; do
  "$readelf" -h "$file" | grep -F 'Machine:' | grep -F AArch64 >/dev/null || archphene_die "ARM64 ELF verification failed for $file"
  printf '%-42s %12s %s\n' "$(basename "$file")" "$(stat -c %s "$file")" "$(archphene_sha256_file "$file")"
done
archphene_note "ARM64 bridge readiness passed for Android-owned native components."
archphene_note "Run test-arm64-physical-regression.sh for the complete AArch64 application/device gate."

