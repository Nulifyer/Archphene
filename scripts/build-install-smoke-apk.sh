#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
sdk="$(archphene_android_sdk)"
bt="$sdk/build-tools/36.0.0"
aapt2="$(archphene_android_tool "$sdk" build-tools/36.0.0/aapt2)"
d8="$(archphene_android_tool "$sdk" build-tools/36.0.0/d8)"
zipalign="$(archphene_android_tool "$sdk" build-tools/36.0.0/zipalign)"
apksigner="$(archphene_android_tool "$sdk" build-tools/36.0.0/apksigner)"
platform="$sdk/platforms/android-36/android.jar"
archphene_require_file "$platform"
for command in javac jar keytool; do archphene_require_command "$command"; done
app="$ARCHPHENE_ROOT/prototypes/android-smoke-apk"
out="$app/out"
rm -rf -- "$out"
mkdir -p "$out"/{compiled,gen,classes,dex}
"$aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"
"$aapt2" link -o "$out/unsigned.apk" -I "$platform" --manifest "$app/AndroidManifest.xml" --java "$out/gen" "$out/compiled/res.zip"
mapfile -d '' java_files < <(find "$app/src" -type f -name '*.java' -print0)
javac --release 17 -classpath "$platform" -d "$out/classes" "${java_files[@]}"
"$d8" --min-api 23 --output "$out/dex" "$out/classes/com/archpheneos/smoke/MainActivity.class"
(cd "$out/dex" && jar uf ../unsigned.apk classes.dex)
key="$out/debug.keystore"
keytool -genkeypair -noprompt -keystore "$key" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=ArchpheneOS Smoke,O=ArchpheneOS,C=US"
"$zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android \
  --out "$out/archpheneos-smoke.apk" "$out/aligned.apk"
"$apksigner" verify --verbose "$out/archpheneos-smoke.apk"
archphene_init_adb ""
archphene_adb_run install -r "$out/archpheneos-smoke.apk"
archphene_adb_run shell monkey -p com.archpheneos.smoke -c android.intent.category.LAUNCHER 1

