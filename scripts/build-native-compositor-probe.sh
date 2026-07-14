#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
abi="${ANDROID_ABI:?ANDROID_ABI is required}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
bt="$sdk/build-tools/$build_tools_version"
platform="$sdk/platforms/android-36/android.jar"
app="$root/prototypes/native-compositor-probe"
out="$app/out-$abi"

case "$abi" in
  x86_64) target=x86_64-linux-android ;;
  arm64-v8a) target=aarch64-linux-android ;;
  *) echo "unsupported Android ABI: $abi" >&2; exit 1 ;;
esac

library="$root/native/archphene-compositor/target/$target/release/libarchphene_compositor.so"
[[ -f "$library" ]] || { echo "missing compositor library: $library" >&2; exit 1; }
rm -rf "$out"
mkdir -p "$out"/{gen,classes,dex,package/lib/$abi}

"$bt/aapt2" link -o "$out/unsigned.apk" -I "$platform" \
  --manifest "$app/AndroidManifest.xml" --java "$out/gen"
mapfile -d '' java_files < <(find "$app/src" "$root/prototypes/shared-android-bridge/src" "$out/gen" -type f -name '*.java' -print0)
javac --release 17 -classpath "$platform" -d "$out/classes" "${java_files[@]}"
mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
"$bt/d8" --lib "$platform" --min-api 23 --output "$out/dex" "${class_files[@]}"
cp "$library" "$out/package/lib/$abi/libarchphene_compositor.so"
(cd "$out/dex" && jar uf "$out/unsigned.apk" classes.dex)
(cd "$out/package" && jar uf "$out/unsigned.apk" "lib/$abi/libarchphene_compositor.so")
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --out "$out/archphene-compositor-probe.apk" "$out/aligned.apk"
"$bt/apksigner" verify --verbose "$out/archphene-compositor-probe.apk"
echo "Native compositor probe APK: $out/archphene-compositor-probe.apk"