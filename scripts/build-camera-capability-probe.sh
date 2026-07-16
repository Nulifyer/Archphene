#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
abi="${ANDROID_ABI:?ANDROID_ABI is required}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
bt="$sdk/build-tools/$build_tools_version"
platform="$sdk/platforms/android-36/android.jar"
app="$root/prototypes/camera-capability-probe"
out="$app/out-$abi"
ndk_version="${NDK_VERSION:-29.0.14206865}"
toolchain="$sdk/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64"

case "$abi" in
  x86_64) target=x86_64-linux-android ;;
  arm64-v8a) target=aarch64-linux-android ;;
  *) echo "unsupported Android ABI: $abi" >&2; exit 1 ;;
esac

rm -rf "$out"
mkdir -p "$out"/{gen,classes,dex,package/lib/$abi}
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$platform" \
  --manifest "$app/AndroidManifest.xml" --java "$out/gen"
javac --release 17 -classpath "$platform" -d "$out/classes" \
  "$app/src/org/archphene/bridge/CameraProbeActivity.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidCameraIntegration.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidDesktopIntegration.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/ArchpheneAccessibilityBridge.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidSecretStore.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidCapabilityBroker.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidPdfPrintAdapter.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/BridgeCapabilities.java"
mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
"$bt/d8" --lib "$platform" --min-api 23 --output "$out/dex" "${class_files[@]}"
"$toolchain/bin/${target}29-clang" -DARCHPHENE_CAPABILITY_PROBE_MAIN \
  -fPIE -pie -O2 -Wall -Wextra -Werror \
  "$root/native/archphene-android-capability/archphene_android.c" \
  -o "$out/package/lib/$abi/libarchphene_camera_probe.so"
"$toolchain/bin/${target}29-clang" \
  -fPIE -pie -O2 -Wall -Wextra -Werror -pthread \
  -I"$root/native/archphene-android-capability" \
  "$root/native/archphene-android-capability/archphene_camera_stream_probe.c" \
  "$root/native/archphene-android-capability/archphene_android.c" \
  -o "$out/package/lib/$abi/libarchphene_camera_stream_probe.so"
"$toolchain/bin/$target"29-clang \
  -fPIE -pie -O2 -Wall -Wextra -Werror \
  "$root/native/archphene-portal/archphene_pipewire_socket_probe.c" \
  -o "$out/package/lib/$abi/libarchphene_pipewire_socket_probe.so"
if [[ "$abi" == "arm64-v8a" ]]; then dbus_arch=aarch64; else dbus_arch=x86_64; fi
dbus_out="$root/tooling/build/android-dbus/$dbus_arch"
for mapping in \
  "dbus-daemon:libarchphene_dbus_daemon.so" \
  "portal-service:libarchphene_portal_service.so" \
  "portal-probe:libarchphene_portal_probe.so" \
  "xdg-open:libarchphene_xdg_open.so"; do
  source_name="$(printf '%s' "$mapping" | cut -d: -f1)"
  target_name="$(printf '%s' "$mapping" | cut -d: -f2)"
  [[ -f "$dbus_out/$source_name" ]] || {
    echo "missing Android D-Bus helper: $dbus_out/$source_name" >&2
    exit 1
  }
  cp "$dbus_out/$source_name" "$out/package/lib/$abi/$target_name"
done
(cd "$out/dex" && jar uf "$out/unsigned.apk" classes.dex)
(cd "$out/package" && jar uf "$out/unsigned.apk" lib/"$abi"/*.so)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --out "$out/archphene-camera-probe.apk" "$out/aligned.apk"
"$bt/apksigner" verify --verbose "$out/archphene-camera-probe.apk"
echo "Camera capability probe APK: $out/archphene-camera-probe.apk"
