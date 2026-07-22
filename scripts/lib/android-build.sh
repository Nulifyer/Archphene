#!/usr/bin/env bash

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

archphene_init_android_build() {
  ARCHPHENE_SDK="$(archphene_android_sdk)"
  ARCHPHENE_BUILD_TOOLS_VERSION="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
  ARCHPHENE_BT="$ARCHPHENE_SDK/build-tools/$ARCHPHENE_BUILD_TOOLS_VERSION"
  ARCHPHENE_ANDROID_JAR="$ARCHPHENE_SDK/platforms/android-36/android.jar"
  ARCHPHENE_NDK_BIN="$ARCHPHENE_SDK/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin"
  for tool in aapt2 d8 zipalign apksigner; do archphene_require_file "$ARCHPHENE_BT/$tool"; done
  archphene_require_file "$ARCHPHENE_ANDROID_JAR"
  archphene_require_command javac
  archphene_require_command jar
}

# Build and sign an APK from an already prepared app tree.
# Arguments: app out manifest ABI version-code version-name keystore apk-name
archphene_package_android_app() {
  local app="$1" out="$2" manifest="$3" abi="$4" version_code="$5" version_name="$6" key="$7" apk_name="$8"
  rm -rf "$out"
  mkdir -p "$out"/{compiled,gen,classes,dex,stage/lib/"$abi",stage/assets}
  "$ARCHPHENE_BT/aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"
  "$ARCHPHENE_BT/aapt2" link -o "$out/unsigned.apk" -I "$ARCHPHENE_ANDROID_JAR" \
    --version-code "$version_code" --version-name "$version_name" --manifest "$manifest" \
    --java "$out/gen" "$out/compiled/res.zip"
  mapfile -d '' java_files < <(find "$app/src" "$ARCHPHENE_ROOT/prototypes/shared-android-bridge/src" -type f -name '*.java' -print0)
  javac --release 17 -classpath "$ARCHPHENE_ANDROID_JAR" -d "$out/classes" "${java_files[@]}"
  mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
  "$ARCHPHENE_BT/d8" --lib "$ARCHPHENE_ANDROID_JAR" --min-api 23 --output "$out/dex" "${class_files[@]}"
  cp "$out/dex/classes.dex" "$out/stage/classes.dex"
  if [[ -d "$app/lib/$abi" ]]; then cp -a "$app/lib/$abi/." "$out/stage/lib/$abi/"; fi
  if [[ -d "$app/assets" ]]; then cp -a "$app/assets/." "$out/stage/assets/"; fi
  (cd "$out/stage" && mapfile -d '' entries < <(find . -type f -print0) && jar uf ../unsigned.apk "${entries[@]}")
  "$ARCHPHENE_BT/zipalign" -P 16 -f 4 "$out/unsigned.apk" "$out/aligned.apk"
  "$ARCHPHENE_BT/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$out/$apk_name" "$out/aligned.apk"
  "$ARCHPHENE_BT/apksigner" verify --verbose "$out/$apk_name"
}
