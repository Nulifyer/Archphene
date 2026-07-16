#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
abi="${ANDROID_ABI:?ANDROID_ABI is required}"
bt="$sdk/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
platform="$sdk/platforms/android-36/android.jar"
app="$root/prototypes/accessibility-capability-probe"
out="$app/out-$abi"
toolchain="$sdk/ndk/${NDK_VERSION:-29.0.14206865}/toolchains/llvm/prebuilt/linux-x86_64"
case "$abi" in
  x86_64) target=x86_64-linux-android ;;
  arm64-v8a) target=aarch64-linux-android ;;
  *) echo "unsupported Android ABI: $abi" >&2; exit 1 ;;
esac
rm -rf "$out"
mkdir -p "$out"/{gen,classes,dex,compiled,package/lib/$abi}
"$bt/aapt2" compile --dir "$app/res" -o "$out/compiled"
mapfile -d '' resource_files < <(find "$out/compiled" -type f -name '*.flat' -print0)
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$platform" \
  --manifest "$app/AndroidManifest.xml" --java "$out/gen" "${resource_files[@]}"
javac --release 17 -classpath "$platform" -d "$out/classes" \
  "$app/src/org/archphene/bridge/AccessibilityProbeActivity.java" \
  "$app/src/org/archphene/bridge/ProbeAccessibilityService.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidCameraIntegration.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/ArchpheneAccessibilityBridge.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidCapabilityBroker.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/AndroidPdfPrintAdapter.java" \
  "$root/prototypes/shared-android-bridge/src/org/archphene/bridge/BridgeCapabilities.java"
mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
"$bt/d8" --lib "$platform" --min-api 23 --output "$out/dex" "${class_files[@]}"
"$toolchain/bin/${target}29-clang" -DARCHPHENE_CAPABILITY_PROBE_MAIN \
  -fPIE -pie -O2 -Wall -Wextra -Werror \
  "$root/native/archphene-android-capability/archphene_android.c" \
  -o "$out/package/lib/$abi/libarchphene_accessibility_probe.so"
(cd "$out/dex" && jar uf "$out/unsigned.apk" classes.dex)
(cd "$out/package" && jar uf "$out/unsigned.apk" "lib/$abi/libarchphene_accessibility_probe.so")
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --out "$out/archphene-accessibility-probe.apk" "$out/aligned.apk"
"$bt/apksigner" verify --verbose "$out/archphene-accessibility-probe.apk"
echo "Accessibility capability probe APK: $out/archphene-accessibility-probe.apk"
