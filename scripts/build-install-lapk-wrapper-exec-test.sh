#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
sdk="$(archphene_android_sdk)"
aapt2="$(archphene_android_tool "$sdk" build-tools/36.0.0/aapt2)"
d8="$(archphene_android_tool "$sdk" build-tools/36.0.0/d8)"
zipalign="$(archphene_android_tool "$sdk" build-tools/36.0.0/zipalign)"
apksigner="$(archphene_android_tool "$sdk" build-tools/36.0.0/apksigner)"
platform="$sdk/platforms/android-36/android.jar"
app="$ARCHPHENE_ROOT/prototypes/lapk-wrapper-exec-test"
out="$app/out"
rm -rf -- "$out"
mkdir -p "$out"/{compiled,gen,classes,dex}
"$aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"
"$aapt2" link -o "$out/unsigned.apk" -I "$platform" --manifest "$app/AndroidManifest.xml" --java "$out/gen" "$out/compiled/res.zip"
mapfile -d '' java_files < <(find "$app/src" -type f -name '*.java' -print0)
javac --release 17 -classpath "$platform" -d "$out/classes" "${java_files[@]}"
mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
"$d8" --min-api 23 --output "$out/dex" "${class_files[@]}"
(cd "$out/dex" && jar uf ../unsigned.apk classes.dex)
(cd "$app" && mapfile -d '' native_files < <(find lib/x86_64 -type f -print0) && jar uf out/unsigned.apk "${native_files[@]}")
key="$(archphene_ensure_named_debug_keystore archpheneos-wrapper-debug.keystore 'CN=ArchpheneOS Wrapper,O=ArchpheneOS,C=US')"
"$zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android \
  --out "$out/archpheneos-lapk-wrapper-exec-test.apk" "$out/aligned.apk"
"$apksigner" verify --verbose "$out/archpheneos-lapk-wrapper-exec-test.apk"
archphene_init_adb ""
archphene_adb_run install -r "$out/archpheneos-lapk-wrapper-exec-test.apk"
archphene_adb_run shell monkey -p org.archpheneos.wrapper.exec -c android.intent.category.LAUNCHER 1

