#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
version_code="${VERSION_CODE:-10000}"
version_name="${VERSION_NAME:-1.0.0}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"

if [[ -z "$sdk" || ! -d "$sdk" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to an Android SDK" >&2
  exit 1
fi

bt="$sdk/build-tools/$build_tools_version"
platform="$sdk/platforms/android-36/android.jar"
apksigner_jar="$bt/lib/apksigner.jar"
for required in aapt2 d8 zipalign apksigner; do
  [[ -x "$bt/$required" ]] || {
    echo "missing Android build tool: $bt/$required" >&2
    exit 1
  }
done
[[ -f "$platform" && -f "$apksigner_jar" ]] || {
  echo "Android platform 36 or apksigner library is missing" >&2
  exit 1
}

build_qt_template() {
  local app="$root/prototypes/kcalc-android-app"
  local out="$root/tooling/build/wrapper-templates/qt"
  local placeholder="org.archphene.linux.p00000000000000000000000000000000"
  rm -rf "$out"
  mkdir -p "$out"/{compiled,gen,classes,dex,stage/lib/x86_64,stage/assets}

  sed \
    -e "s/package=\"org.archphene.linux.kcalc\"/package=\"$placeholder\"/" \
    -e 's/android:debuggable="true"/android:debuggable="false"/' \
    "$app/AndroidManifest.xml" > "$out/AndroidManifest.xml"
  "$bt/aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"
  "$bt/aapt2" link -o "$out/unsigned.apk" -I "$platform" \
    --manifest "$out/AndroidManifest.xml" --java "$out/gen" \
    "$out/compiled/res.zip"

  mapfile -d '' java_files < <(find "$app/src" "$root/prototypes/shared-android-bridge/src" -type f -name '*.java' -print0)
  javac --release 17 -classpath "$platform" -d "$out/classes" "${java_files[@]}"
  mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
  "$bt/d8" --lib "$platform" --min-api 23 --output "$out/dex" "${class_files[@]}"

  cp "$out/dex/classes.dex" "$out/stage/classes.dex"
  cp "$app/assets/fonts.conf" "$out/stage/assets/fonts.conf"
  cp "$app/assets/kcalc.PKGINFO" "$out/stage/assets/kcalc.PKGINFO"
  (cd "$root/prebuilt/qt-bridge" && sha256sum --check SHA256SUMS)
  cp "$root"/prebuilt/qt-bridge/x86_64/*.so "$out/stage/lib/x86_64/"
  compositor="$root/native/archphene-compositor/target/x86_64-linux-android/release/libarchphene_compositor.so"
  [[ -f "$compositor" ]] || { echo "missing shared compositor: $compositor" >&2; exit 1; }
  cp "$compositor" "$out/stage/lib/x86_64/libarchphene_compositor.so"
  (
    cd "$out/stage"
    mapfile -d '' entries < <(find . -type f -print0)
    jar uf ../unsigned.apk "${entries[@]}"
  )
  "$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/qt-wrapper-template.apk"
}

build_qt_template

app="$root/prototypes/linux-app-manager-stub"
out="$app/out-linux"
rm -rf "$out"
mkdir -p "$out"/{compiled,gen,classes,dex,package-runtime/lib/x86_64,package-runtime/assets/package-runtime}

sed \
  -e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$version_code\"/" \
  -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$version_name\"/" \
  -e 's/android:debuggable="true"/android:debuggable="false"/' \
  "$app/AndroidManifest.xml" > "$out/AndroidManifest.xml"

runtime_prefix="$root"
if [[ -d "$root/tooling/build/ci-package-runtime/tooling" ]]; then
  runtime_prefix="$root/tooling/build/ci-package-runtime"
  (cd "$runtime_prefix" && sha256sum --check --quiet SHA256SUMS) || {
    echo "Package runtime artifact verification failed" >&2
    exit 1
  }
fi
runtime="$runtime_prefix/tooling/downloads/arch-runtime-pacman-x86_64"
runtime_root="$runtime/runtime-root"
resolved="$runtime/elf-needed-resolved.tsv"
keyrings="$runtime_prefix/tooling/downloads/arch-runtime-archlinux-keyring-x86_64/runtime-root/usr/share/pacman/keyrings"
glibc="$runtime_prefix/tooling/build/glibc-archphene-runtime-x86_64"
template="$root/tooling/build/wrapper-templates/qt/qt-wrapper-template.apk"
for required in "$runtime_root" "$resolved" "$keyrings" "$glibc" "$template"; do
  [[ -e "$required" ]] || {
    echo "package runtime input missing: $required" >&2
    exit 1
  }
done

package_assets="$out/package-runtime/assets/package-runtime"
package_libs="$out/package-runtime/lib/x86_64"
cp "$root/prototypes/linux-app-manager-stub/assets/payload-hello-linux-amd64" \
  "$package_libs/libarchphene_runtime_probe.so"
cp "$root/prototypes/linux-app-manager-stub/assets/payload-hello-dynamic-amd64" \
  "$package_libs/libarchphene_dynamic_probe.so"
for name in archlinux.gpg archlinux-revoked archlinux-trusted; do
  cp "$keyrings/$name" "$package_assets/$name"
done
cp "$template" "$package_assets/qt-wrapper-template.apk"
cp "$runtime_root/usr/bin/pacman" "$package_libs/libarchphene_pacman.so"
cp "$runtime_root/usr/bin/gpg" "$package_libs/libarchphene_gpg.so"
cp "$runtime_root/usr/bin/gpgv" "$package_libs/libarchphene_gpgv.so"
cp "$runtime_root/usr/bin/bsdtar" "$package_libs/libarchphene_bsdtar.so"
while IFS=$'\t' read -r name relative; do
  [[ -n "$relative" ]] || continue
  cp "$runtime_root/$relative" "$package_libs/$name" || exit 1
done < "$resolved"
cp "$glibc/ld-linux-x86-64.so.2" "$package_libs/libarchphene_ld.so"
cp "$glibc/libc.so.6" "$package_libs/libarchphene_runtime_libc.so"
find "$glibc" -maxdepth 1 -type f \
  ! -name 'source-commit.txt' ! -name 'runtime-manifest.tsv' \
  ! -name 'ld-linux-x86-64.so.2' -exec cp {} "$package_libs/" \;
catalog="$package_assets/runtime-modules.tsv"
printf '# org.archphene.runtime-modules.v1\n' > "$catalog"
add_runtime_module() {
  local role="$1" file="$2" library="$3" link_name="$4" hash size
  [[ -f "$file" ]] || { echo "runtime module missing: $file" >&2; exit 1; }
  hash="$(sha256sum "$file" | cut -d ' ' -f 1)"
  size="$(stat -c '%s' "$file")"
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$role" "$hash" "$size" "$library" "$link_name" >> "$catalog"
}
add_runtime_module static-probe "$package_libs/libarchphene_runtime_probe.so" \
  libarchphene_runtime_probe.so program
add_runtime_module dynamic-probe "$package_libs/libarchphene_dynamic_probe.so" \
  libarchphene_dynamic_probe.so program
add_runtime_module glibc-loader "$package_libs/libarchphene_ld.so" \
  libarchphene_ld.so ld-linux-x86-64.so.2
add_runtime_module glibc-libc "$package_libs/libarchphene_runtime_libc.so" \
  libarchphene_runtime_libc.so libc.so.6

"$bt/aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$platform" \
  --version-code "$version_code" --version-name "$version_name" \
  --manifest "$out/AndroidManifest.xml" --java "$out/gen" -A "$app/assets" \
  "$out/compiled/res.zip"

mapfile -d '' java_files < <(find "$app/src" "$out/gen" -type f -name '*.java' -print0)
javac --release 17 -classpath "$platform:$apksigner_jar" \
  -d "$out/classes" "${java_files[@]}"
mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
"$bt/d8" --lib "$platform" --min-api 23 --output "$out/dex" \
  "${class_files[@]}" "$apksigner_jar"
(
  cd "$out/dex"
  jar uf ../unsigned.apk classes.dex
)
(
  cd "$out/package-runtime"
  mapfile -d '' entries < <(find . -type f -print0)
  jar uf ../unsigned.apk "${entries[@]}"
)

"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
if [[ -z "${KEYSTORE_PATH:-}" || -z "${KEYSTORE_PASSWORD:-}" \
    || -z "${KEY_ALIAS:-}" || -z "${KEY_PASSWORD:-}" ]]; then
  echo "KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD are required" >&2
  exit 1
fi
"$bt/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --out "$out/archphene.apk" "$out/aligned.apk"
"$bt/apksigner" verify --verbose --print-certs "$out/archphene.apk"
echo "Linux-built Archphene APK: $out/archphene.apk"
