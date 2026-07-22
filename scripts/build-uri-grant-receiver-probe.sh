#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
abi="${ANDROID_ABI:?ANDROID_ABI is required}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
bt="$sdk/build-tools/$build_tools_version"
platform="$sdk/platforms/android-36/android.jar"
app="$root/prototypes/uri-grant-receiver-probe"
out="$app/out-$abi"

rm -rf "$out"
mkdir -p "$out"/{gen,classes,dex}
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$platform" \
  --manifest "$app/AndroidManifest.xml" --java "$out/gen"
mapfile -d '' java_files < <(find "$app/src" "$out/gen" -type f -name '*.java' -print0)
javac --release 17 -classpath "$platform" -d "$out/classes" "${java_files[@]}"
mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
"$bt/d8" --lib "$platform" --min-api 23 --output "$out/dex" "${class_files[@]}"
(cd "$out/dex" && jar uf "$out/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --out "$out/archphene-uri-grant-receiver-probe.apk" "$out/aligned.apk"
"$bt/apksigner" verify --verbose "$out/archphene-uri-grant-receiver-probe.apk"
echo "URI grant receiver probe APK: $out/archphene-uri-grant-receiver-probe.apk"