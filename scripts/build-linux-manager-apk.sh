#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
version_code="${VERSION_CODE:-10000}"
version_name="${VERSION_NAME:-1.0.0}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
app_debuggable="${DEBUGGABLE:-false}"
[[ "$app_debuggable" == "true" || "$app_debuggable" == "false" ]] || {
  echo "DEBUGGABLE must be true or false" >&2; exit 1;
}
artifact_abi="${ARCHPHENE_ABI:-universal}"
case "$artifact_abi" in
  universal|x86_64|arm64-v8a) ;;
  *) echo "ARCHPHENE_ABI must be universal, x86_64, or arm64-v8a" >&2; exit 1 ;;
esac

prune_native_abis() {
  local directory="$1"
  case "$artifact_abi" in
    x86_64) rm -rf "$directory/arm64-v8a" ;;
    arm64-v8a) rm -rf "$directory/x86_64" ;;
  esac
}

command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }

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

build_qt_templates() {
  local app="$root/prototypes/kcalc-android-app"
  local out="$root/tooling/build/wrapper-templates/qt"
  local placeholder="org.archphene.linux.p00000000000000000000000000000000"
  local fixed_authority="org.archphene.linux.kcalc.documents"
  local placeholder_authority="$placeholder.documents"
  rm -rf "$out"
  mkdir -p "$out"/{compiled,gen,classes,dex,stage/lib/x86_64,stage/lib/arm64-v8a,stage/assets}

  sed \
    -e "s/package=\"org.archphene.linux.kcalc\"/package=\"$placeholder\"/" \
    -e "s/$fixed_authority/$placeholder_authority/g" \
    -e "s/android:debuggable=\"true\"/android:debuggable=\"$app_debuggable\"/" \
    -e 's/@drawable\/kcalc_icon/@drawable\/linux_app_icon_png/g' \
    "$app/AndroidManifest.xml" > "$out/base-manifest.xml"
  "$bt/aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"
  local profile permission_profile
  for profile in generic document; do
    for permission_profile in none audio-input camera audio-input-camera; do
      local permission_args=()
      [[ "$permission_profile" == *audio-input* ]] && permission_args+=(--permission audio-input)
      [[ "$permission_profile" == *camera* ]] && permission_args+=(--permission camera)
      python3 "$root/scripts/render-wrapper-manifest.py" --profile "$profile" \
        "${permission_args[@]}" "$out/base-manifest.xml" \
        "$out/$profile-$permission_profile-manifest.xml"
      "$bt/aapt2" link -o "$out/unsigned-$profile-$permission_profile.apk" \
        -I "$platform" --manifest "$out/$profile-$permission_profile-manifest.xml" \
        --java "$out/gen" "$out/compiled/res.zip"
      python3 -c 'import sys, zipfile; archive=zipfile.ZipFile(sys.argv[1]); open(sys.argv[2], "wb").write(archive.read("AndroidManifest.xml"))' \
        "$out/unsigned-$profile-$permission_profile.apk" \
        "$out/qt-$profile-manifest-$permission_profile.bin"
    done
  done

  mapfile -d '' java_files < <(find "$app/src" "$root/prototypes/shared-android-bridge/src" -type f -name '*.java' -print0)
  javac --release 17 -classpath "$platform" -d "$out/classes" "${java_files[@]}"
  mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
  "$bt/d8" --lib "$platform" --min-api 23 --output "$out/dex" "${class_files[@]}"

  cp "$out/dex/classes.dex" "$out/stage/classes.dex"
  cp "$app/assets/fonts.conf" "$out/stage/assets/fonts.conf"
  cp "$app/assets/kcalc.PKGINFO" "$out/stage/assets/kcalc.PKGINFO"
  (cd "$root/prebuilt/qt-bridge" && sha256sum --check SHA256SUMS)
  cp "$root"/prebuilt/qt-bridge/x86_64/*.so "$out/stage/lib/x86_64/"
  cp "$root"/prebuilt/qt-bridge/arm64-v8a/*.so "$out/stage/lib/arm64-v8a/"
  compositor="$root/native/archphene-compositor/target/x86_64-linux-android/release/libarchphene_compositor.so"
  [[ -f "$compositor" ]] || { echo "missing shared compositor: $compositor" >&2; exit 1; }
  cp "$compositor" "$out/stage/lib/x86_64/libarchphene_compositor.so"
  gpu_helper="$root/tooling/build/android-gpu/x86_64/virgl_test_server_android"
  [[ -f "$gpu_helper" ]] || { echo "missing Android GPU helper: $gpu_helper" >&2; exit 1; }
  cp "$gpu_helper" "$out/stage/lib/x86_64/libarchphene_virgl_server.so"
  cp "$root/tooling/build/android-dbus/x86_64/dbus-daemon" \
    "$out/stage/lib/x86_64/libarchphene_dbus_daemon.so"
  cp "$root/tooling/build/android-dbus/x86_64/portal-service" \
    "$out/stage/lib/x86_64/libarchphene_portal_service.so"
  cp "$root/tooling/build/android-dbus/x86_64/xdg-open" \
    "$out/stage/lib/x86_64/libarchphene_xdg_open.so"
  if [[ "$app_debuggable" == "true" ]]; then
    cp "$root/tooling/build/android-dbus/x86_64/portal-probe" \
      "$out/stage/lib/x86_64/libarchphene_portal_probe.so"
  fi
  arm64_compositor="$root/native/archphene-compositor/target/aarch64-linux-android/release/libarchphene_compositor.so"
  [[ -f "$arm64_compositor" ]] || { echo "missing arm64 shared compositor: $arm64_compositor" >&2; exit 1; }
  cp "$arm64_compositor" "$out/stage/lib/arm64-v8a/libarchphene_compositor.so"
  arm64_gpu_helper="$root/tooling/build/android-gpu/aarch64/virgl_test_server_android"
  [[ -f "$arm64_gpu_helper" ]] || { echo "missing arm64 Android GPU helper: $arm64_gpu_helper" >&2; exit 1; }
  cp "$arm64_gpu_helper" "$out/stage/lib/arm64-v8a/libarchphene_virgl_server.so"
  cp "$root/tooling/build/android-dbus/aarch64/dbus-daemon" \
    "$out/stage/lib/arm64-v8a/libarchphene_dbus_daemon.so"
  cp "$root/tooling/build/android-dbus/aarch64/portal-service" \
    "$out/stage/lib/arm64-v8a/libarchphene_portal_service.so"
  cp "$root/tooling/build/android-dbus/aarch64/xdg-open" \
    "$out/stage/lib/arm64-v8a/libarchphene_xdg_open.so"
  if [[ "$app_debuggable" == "true" ]]; then
    cp "$root/tooling/build/android-dbus/aarch64/portal-probe" \
      "$out/stage/lib/arm64-v8a/libarchphene_portal_probe.so"
  fi
  prune_native_abis "$out/stage/lib"
  (
    cd "$out/stage"
    mapfile -d '' entries < <(find . -type f -print0)
    jar uf "../unsigned-generic-none.apk" "${entries[@]}"
  )
  "$bt/zipalign" -P 16 -f 4 "$out/unsigned-generic-none.apk" "$out/qt-wrapper-template.apk"

  local generic_manifest document_manifest variant_manifest
  generic_manifest="$("$bt/aapt2" dump xmltree "$out/qt-wrapper-template.apk" --file AndroidManifest.xml)"
  document_manifest="$("$bt/aapt2" dump xmltree "$out/unsigned-document-none.apk" --file AndroidManifest.xml)"
  if [[ "$generic_manifest" != *"$placeholder_authority"* \
      || "$generic_manifest" == *"$fixed_authority"* \
      || "$generic_manifest" == *"android.intent.action.VIEW"* \
      || "$generic_manifest" == *"android.intent.action.EDIT"* ]]; then
    echo "compiled generic wrapper template has invalid document metadata" >&2
    exit 1
  fi
  if [[ "$document_manifest" != *"application/x-archphene-mime-00"* \
      || "$document_manifest" != *"application/x-archphene-mime-15"* \
      || "$document_manifest" != *"android.intent.action.VIEW"* \
      || "$document_manifest" != *"android.intent.action.EDIT"* ]]; then
    echo "compiled document wrapper template has invalid MIME slots" >&2
    exit 1
  fi
  for profile in generic document; do
    for permission_profile in none audio-input camera audio-input-camera; do
      variant_manifest="$("$bt/aapt2" dump xmltree \
        "$out/unsigned-$profile-$permission_profile.apk" --file AndroidManifest.xml)"
      if [[ "$permission_profile" == *audio-input* ]]; then
        [[ "$variant_manifest" == *"android.permission.RECORD_AUDIO"* ]] || {
          echo "wrapper manifest is missing microphone permission: $profile/$permission_profile" >&2; exit 1;
        }
      elif [[ "$variant_manifest" == *"android.permission.RECORD_AUDIO"* ]]; then
        echo "wrapper manifest overdeclares microphone permission: $profile/$permission_profile" >&2; exit 1
      fi
      if [[ "$permission_profile" == *camera* ]]; then
        [[ "$variant_manifest" == *"android.permission.CAMERA"* ]] || {
          echo "wrapper manifest is missing camera permission: $profile/$permission_profile" >&2; exit 1;
        }
      elif [[ "$variant_manifest" == *"android.permission.CAMERA"* ]]; then
        echo "wrapper manifest overdeclares camera permission: $profile/$permission_profile" >&2; exit 1
      fi
    done
  done
}

terminal_only="${ARCHPHENE_TERMINAL_ONLY:-false}"
[[ "$terminal_only" == "true" || "$terminal_only" == "false" ]] || {
  echo "ARCHPHENE_TERMINAL_ONLY must be true or false" >&2; exit 1;
}
[[ "$terminal_only" == "true" ]] || build_qt_templates

build_terminal_app() {
  local loader_x86="$1" loader_arm64="$2"
  local app="$root/prototypes/archphene-terminal-app"
  local out="${ARCHPHENE_TERMINAL_OUT:-$app/out-linux}"
  local native_x86="$root/native/archphene-terminal/out/x86_64/libtermux.so"
  local native_arm64="$root/native/archphene-terminal/out/aarch64/libtermux.so"
  [[ -f "$native_x86" && -f "$native_arm64" && -f "$loader_x86" && -f "$loader_arm64" ]] || {
    echo "Terminal PTY and glibc loader libraries are required" >&2; exit 1;
  }
  rm -rf "$out"
  mkdir -p "$out"/{compiled,gen,classes,dex,stage/lib/x86_64,stage/lib/arm64-v8a}

  sed \
    -e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$version_code\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$version_name\"/" \
    -e "s/android:debuggable=\"true\"/android:debuggable=\"$app_debuggable\"/" \
    "$app/AndroidManifest.xml" > "$out/AndroidManifest.xml"
  "$bt/aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"
  "$bt/aapt2" link -o "$out/unsigned.apk" -I "$platform" \
    --version-code "$version_code" --version-name "$version_name" \
    --manifest "$out/AndroidManifest.xml" --java "$out/gen" \
    "$out/compiled/res.zip"

  mapfile -d '' java_files < <(find "$app/src" \
    "$root/third_party/termux-terminal/src" "$out/gen" \
    -type f -name '*.java' -print0)
  javac --release 17 -classpath "$platform" -d "$out/classes" "${java_files[@]}"
  mapfile -d '' class_files < <(find "$out/classes" -type f -name '*.class' -print0)
  "$bt/d8" --lib "$platform" --min-api 23 --output "$out/dex" "${class_files[@]}"
  cp "$out/dex/classes.dex" "$out/stage/classes.dex"
  cp "$native_x86" "$out/stage/lib/x86_64/libtermux.so"
  cp "$native_arm64" "$out/stage/lib/arm64-v8a/libtermux.so"
  cp "$loader_x86" "$out/stage/lib/x86_64/libarchphene_ld.so"
  cp "$loader_arm64" "$out/stage/lib/arm64-v8a/libarchphene_ld.so"
  prune_native_abis "$out/stage/lib"
  (
    cd "$out/stage"
    mapfile -d '' entries < <(find . -type f -print0)
    jar uf ../unsigned.apk "${entries[@]}"
  )
  "$bt/zipalign" -P 16 -f 4 "$out/unsigned.apk" "$out/aligned.apk"
  "$bt/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
    --out "$out/archphene-terminal.apk" "$out/aligned.apk"
  "$bt/apksigner" verify --verbose --print-certs "$out/archphene-terminal.apk"
}

if [[ "$terminal_only" == "true" ]]; then
  build_terminal_app \
    "$root/tooling/build/ci-package-runtime/tooling/build/glibc-archphene-runtime-x86_64/ld-linux-x86-64.so.2" \
    "$root/tooling/build/ci-package-runtime-arm64/tooling/build/glibc-archphene-runtime-aarch64/ld-linux-aarch64.so.1"
  exit 0
fi

app="$root/prototypes/linux-app-manager-stub"
out="$app/out-linux"
rm -rf "$out"
mkdir -p "$out"/{compiled,gen,classes,dex,package-runtime/lib/x86_64,package-runtime/lib/arm64-v8a,package-runtime/assets/package-runtime}

sed \
  -e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$version_code\"/" \
  -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$version_name\"/" \
  -e "s/android:debuggable=\"true\"/android:debuggable=\"$app_debuggable\"/" \
  "$app/AndroidManifest.xml" > "$out/AndroidManifest.xml"

verify_runtime_artifact() {
  local prefix="$1" label="$2"
  [[ -f "$prefix/SHA256SUMS" ]] || {
    echo "$label runtime artifact is missing: $prefix" >&2
    exit 1
  }
  (cd "$prefix" && sha256sum --check --quiet SHA256SUMS) || {
    echo "$label runtime artifact verification failed" >&2
    exit 1
  }
}

x86_prefix="$root/tooling/build/ci-package-runtime"
arm_prefix="$root/tooling/build/ci-package-runtime-arm64"
verify_runtime_artifact "$x86_prefix" "x86_64 package"
verify_runtime_artifact "$arm_prefix" "AArch64 package"

x86_runtime="$x86_prefix/tooling/downloads/arch-runtime-pacman-x86_64"
x86_root="$x86_runtime/runtime-root"
x86_resolved="$x86_runtime/elf-needed-resolved.tsv"
x86_keyrings="$x86_prefix/tooling/downloads/arch-runtime-archlinux-keyring-x86_64/runtime-root/usr/share/pacman/keyrings"
x86_glibc="$x86_prefix/tooling/build/glibc-archphene-runtime-x86_64"
arm_runtime="$arm_prefix/tooling/downloads/arch-runtime-pacman-aarch64"
arm_root="$arm_runtime/runtime-root"
arm_resolved="$arm_runtime/elf-needed-resolved.tsv"
arm_keyrings="$arm_prefix/tooling/downloads/arch-runtime-archlinuxarm-keyring-aarch64/runtime-root/usr/share/pacman/keyrings"
arm_glibc="$arm_prefix/tooling/build/glibc-archphene-runtime-aarch64"
arm_path_bridge="$arm_prefix/tooling/build/archphene-path-bridge-aarch64/libarchphene_path_bridge.so"
x86_android_client="$root/tooling/build/android-capability/x86_64/libarchphene_android.so"
arm_android_client="$root/tooling/build/android-capability/aarch64/libarchphene_android.so"
x86_audio="$root/tooling/build/android-pulse/x86_64/out"
arm_audio="$root/tooling/build/android-pulse/aarch64/out"
x86_pipewire="$root/tooling/build/pipewire-camera/x86_64"
arm_pipewire="$root/tooling/build/pipewire-camera/aarch64"
template="$root/tooling/build/wrapper-templates/qt/qt-wrapper-template.apk"
manifest_variants=()
for profile in generic document; do
  for permission_profile in none audio-input camera audio-input-camera; do
    manifest_variants+=("$root/tooling/build/wrapper-templates/qt/qt-$profile-manifest-$permission_profile.bin")
  done
done
for required in "$x86_root" "$x86_resolved" "$x86_keyrings" "$x86_glibc" \
    "$arm_root" "$arm_resolved" "$arm_keyrings" "$arm_glibc" "$arm_path_bridge" \
    "$x86_android_client" "$arm_android_client" \
    "$x86_audio/SHA256SUMS" "$arm_audio/SHA256SUMS" \
    "$x86_pipewire/SHA256SUMS" "$arm_pipewire/SHA256SUMS" \
    "$template" "${manifest_variants[@]}"; do
  [[ -e "$required" ]] || {
    echo "package runtime input missing: $required" >&2
    exit 1
  }
done

build_terminal_app "$x86_glibc/ld-linux-x86-64.so.2" "$arm_glibc/ld-linux-aarch64.so.1"
terminal_apk="$root/prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
terminal_badging="$("$bt/aapt2" dump badging "$terminal_apk")"
grep -F "versionCode='$version_code'" <<<"$terminal_badging" >/dev/null || {
  echo "Terminal versionCode does not match manager release" >&2; exit 1;
}
grep -F "versionName='$version_name'" <<<"$terminal_badging" >/dev/null || {
  echo "Terminal versionName does not match manager release" >&2; exit 1;
}
terminal_native="$(grep '^native-code:' <<<"$terminal_badging" || true)"
case "$artifact_abi" in
  x86_64)
    [[ "$terminal_native" == "native-code: 'x86_64'" ]] || terminal_native=""
    ;;
  arm64-v8a)
    [[ "$terminal_native" == "native-code: 'arm64-v8a'" ]] || terminal_native=""
    ;;
  universal)
    [[ "$terminal_native" == *"'x86_64'"*
        && "$terminal_native" == *"'arm64-v8a'"* ]] || terminal_native=""
    ;;
esac
[[ -n "$terminal_native" ]] || {
  echo "Terminal native ABI set does not match manager artifact $artifact_abi" >&2
  exit 1
}

package_assets="$out/package-runtime/assets/package-runtime"
x86_libs="$out/package-runtime/lib/x86_64"
arm_libs="$out/package-runtime/lib/arm64-v8a"
verify_audio_payload() {
  local source="$1" destination="$2"
  (cd "$source" && sha256sum --check --quiet SHA256SUMS) || {
    echo "Android audio payload verification failed: $source" >&2
    exit 1
  }
  cp "$source"/*.so "$destination/"
}
verify_audio_payload "$x86_audio" "$x86_libs"
verify_audio_payload "$arm_audio" "$arm_libs"

copy_pipewire_payload() {
  local source="$1" destination="$2"
  (cd "$source" && sha256sum --check --quiet SHA256SUMS) || {
    echo "Private PipeWire runtime verification failed: $source" >&2
    exit 1
  }
  cp "$source/libpipewire-0.3.so.0" "$destination/libarchphene_pipewire_client.so"
  cp "$source/archphene-pipewire" "$destination/libarchphene_pipewire_daemon.so"
  cp "$source/archphene-pipewire-camera" "$destination/libarchphene_pipewire_camera.so"
  cp "$source/archphene-pipewire-policy" "$destination/libarchphene_pipewire_policy.so"
  cp "$source/archphene-runtime-supervisor" "$destination/libarchphene_pipewire_supervisor.so"
  cp "$source/pipewire-0.3/libpipewire-module-protocol-native.so" "$destination/libarchphene_pw_module_protocol_native.so"
  cp "$source/pipewire-0.3/libpipewire-module-access.so" "$destination/libarchphene_pw_module_access.so"
  cp "$source/pipewire-0.3/libpipewire-module-metadata.so" "$destination/libarchphene_pw_module_metadata.so"
  cp "$source/pipewire-0.3/libpipewire-module-client-node.so" "$destination/libarchphene_pw_module_client_node.so"
  cp "$source/pipewire-0.3/libpipewire-module-adapter.so" "$destination/libarchphene_pw_module_adapter.so"
  cp "$source/pipewire-0.3/libpipewire-module-link-factory.so" "$destination/libarchphene_pw_module_link_factory.so"
  cp "$source/spa-0.2/support/libspa-support.so" "$destination/libarchphene_spa_support.so"
  cp "$source/spa-0.2/videoconvert/libspa-videoconvert.so" "$destination/libarchphene_spa_videoconvert.so"
}
copy_pipewire_payload "$x86_pipewire" "$x86_libs"
copy_pipewire_payload "$arm_pipewire" "$arm_libs"

arm64_compositor="$root/native/archphene-compositor/target/aarch64-linux-android/release/libarchphene_compositor.so"
[[ -f "$arm64_compositor" ]] || {
  echo "missing aarch64 compositor: $arm64_compositor" >&2
  exit 1
}
cp "$arm64_compositor" "$arm_libs/libarchphene_compositor.so"
cp "$terminal_apk" "$package_assets/archphene-terminal.apk"

gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
  -o "$x86_libs/libarchphene_path_bridge.so" \
  "$root/native/archphene-glibc-path-bridge/path_bridge.c" -ldl
cp "$arm_path_bridge" "$arm_libs/libarchphene_path_bridge.so"
cp "$x86_android_client" "$x86_libs/libarchphene_android.so"
cp "$arm_android_client" "$arm_libs/libarchphene_android.so"

(cd "$root/prebuilt/gtk3-compat" && sha256sum --check SHA256SUMS)
cp "$root"/prebuilt/gtk3-compat/x86_64/*.so "$x86_libs/"
cp "$root"/prebuilt/gtk3-compat/aarch64/*.so "$arm_libs/"
cp "$root/prototypes/linux-app-manager-stub/assets/payload-hello-linux-amd64" \
  "$x86_libs/libarchphene_runtime_probe.so"
cp "$root/prototypes/linux-app-manager-stub/assets/payload-hello-dynamic-amd64" \
  "$x86_libs/libarchphene_dynamic_probe.so"
cp "$root/prototypes/linux-app-manager-stub/assets/payload-hello-transitive-amd64" \
  "$x86_libs/libarchphene_transitive_probe.so"
cp "$root/prototypes/linux-app-manager-stub/assets/payload-runtime-dependency-amd64" \
  "$x86_libs/libarchphene_probe_dependency.so"

cp "$x86_keyrings/archlinux.gpg" "$package_assets/archlinux-x86_64.gpg"
cp "$x86_keyrings/archlinux-revoked" "$package_assets/archlinux-x86_64-revoked"
cp "$x86_keyrings/archlinux-trusted" "$package_assets/archlinux-x86_64-trusted"
cp "$arm_keyrings/archlinuxarm.gpg" "$package_assets/archlinuxarm-aarch64.gpg"
cp "$arm_keyrings/archlinuxarm-revoked" "$package_assets/archlinuxarm-aarch64-revoked"
cp "$arm_keyrings/archlinuxarm-trusted" "$package_assets/archlinuxarm-aarch64-trusted"
cp "$template" "$package_assets/qt-wrapper-template.apk"
for manifest_variant in "${manifest_variants[@]}"; do
  cp "$manifest_variant" "$package_assets/$(basename "$manifest_variant")"
done

copy_package_tools() {
  local runtime_root="$1" resolved="$2" destination="$3"
  cp "$runtime_root/usr/bin/pacman" "$destination/libarchphene_pacman.so"
  cp "$runtime_root/usr/bin/gpg" "$destination/libarchphene_gpg.so"
  cp "$runtime_root/usr/bin/gpgv" "$destination/libarchphene_gpgv.so"
  cp "$runtime_root/usr/bin/bsdtar" "$destination/libarchphene_bsdtar.so"
  while IFS=$'\t' read -r name relative; do
    [[ -n "$relative" ]] || continue
    cp "$runtime_root/$relative" "$destination/$name" || exit 1
  done < "$resolved"
}
copy_package_tools "$x86_root" "$x86_resolved" "$x86_libs"
copy_package_tools "$arm_root" "$arm_resolved" "$arm_libs"

copy_glibc_runtime() {
  local runtime="$1" loader="$2" destination="$3"
  cp "$runtime/$loader" "$destination/libarchphene_ld.so"
  cp "$runtime/libc.so.6" "$destination/libarchphene_runtime_libc.so"
  find "$runtime" -maxdepth 1 -type f \
    ! -name 'source-commit.txt' ! -name 'runtime-manifest.tsv' \
    ! -name "$loader" -exec cp {} "$destination/" \;
}
copy_glibc_runtime "$x86_glibc" ld-linux-x86-64.so.2 "$x86_libs"
copy_glibc_runtime "$arm_glibc" ld-linux-aarch64.so.1 "$arm_libs"

stage_manager_native_catalog() {
  local architecture="$1" libraries="$2" catalog logical identity packaged hash size count=0
  catalog="$package_assets/manager-native-$architecture.tsv"
  printf '# org.archphene.manager-native.v1\n' > "$catalog"
  while IFS= read -r -d '' source; do
    logical="$(basename "$source")"
    [[ "$logical" =~ ^[A-Za-z0-9@._+-]{1,128}$ ]] || {
      echo "unsafe manager runtime library name: $logical" >&2
      exit 1
    }
    identity="$(printf '%s' "$logical" | sha256sum | cut -d ' ' -f 1)"
    packaged="libarchphene_manager_${identity:0:24}.so"
    [[ ! -e "$libraries/$packaged" ]] || {
      echo "manager runtime package alias collision: $packaged" >&2
      exit 1
    }
    hash="$(sha256sum "$source" | cut -d ' ' -f 1)"
    size="$(stat -c '%s' "$source")"
    mv "$source" "$libraries/$packaged"
    printf '%s\t%s\t%s\t%s\n' \
      "$logical" "$packaged" "$hash" "$size" >> "$catalog"
    count=$((count + 1))
  done < <(find "$libraries" -maxdepth 1 -type f ! -name '*.so' -print0 | sort -z)
  [[ "$count" -gt 0 ]] || {
    echo "manager runtime catalog is empty for $architecture" >&2
    exit 1
  }
}
stage_manager_native_catalog x86_64 "$x86_libs"
stage_manager_native_catalog aarch64 "$arm_libs"

add_runtime_module() {
  local catalog="$1" role="$2" file="$3" library="$4" link_name="$5" hash size
  [[ -f "$file" ]] || { echo "runtime module missing: $file" >&2; exit 1; }
  hash="$(sha256sum "$file" | cut -d ' ' -f 1)"
  size="$(stat -c '%s' "$file")"
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$role" "$hash" "$size" "$library" "$link_name" >> "$catalog"
}

x86_catalog="$package_assets/runtime-modules-x86_64.tsv"
printf '# org.archphene.runtime-modules.v1\n' > "$x86_catalog"
add_runtime_module "$x86_catalog" static-probe \
  "$x86_libs/libarchphene_runtime_probe.so" libarchphene_runtime_probe.so program
add_runtime_module "$x86_catalog" dynamic-probe \
  "$x86_libs/libarchphene_dynamic_probe.so" libarchphene_dynamic_probe.so program
add_runtime_module "$x86_catalog" transitive-probe \
  "$x86_libs/libarchphene_transitive_probe.so" libarchphene_transitive_probe.so program
add_runtime_module "$x86_catalog" transitive-probe-library \
  "$x86_libs/libarchphene_probe_dependency.so" \
  libarchphene_probe_dependency.so libarchphene_probe_dependency.so
add_runtime_module "$x86_catalog" glibc-loader "$x86_libs/libarchphene_ld.so" \
  libarchphene_ld.so ld-linux-x86-64.so.2
add_runtime_module "$x86_catalog" glibc-libc \
  "$x86_libs/libarchphene_runtime_libc.so" libarchphene_runtime_libc.so libc.so.6

arm_catalog="$package_assets/runtime-modules-aarch64.tsv"
printf '# org.archphene.runtime-modules.v1\n' > "$arm_catalog"
add_runtime_module "$arm_catalog" glibc-loader "$arm_libs/libarchphene_ld.so" \
  libarchphene_ld.so ld-linux-aarch64.so.1
add_runtime_module "$arm_catalog" glibc-libc \
  "$arm_libs/libarchphene_runtime_libc.so" libarchphene_runtime_libc.so libc.so.6
prune_native_abis "$out/package-runtime/lib"
case "$artifact_abi" in
  x86_64)
    rm -f "$package_assets"/archlinuxarm-aarch64-* \
      "$package_assets/runtime-modules-aarch64.tsv" \
      "$package_assets/manager-native-aarch64.tsv"
    ;;
  arm64-v8a)
    rm -f "$package_assets"/archlinux-x86_64-* \
      "$package_assets/runtime-modules-x86_64.tsv" \
      "$package_assets/manager-native-x86_64.tsv"
    ;;
esac
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

"$bt/zipalign" -P 16 -f 4 "$out/unsigned.apk" "$out/aligned.apk"
if [[ -z "${KEYSTORE_PATH:-}" || -z "${KEYSTORE_PASSWORD:-}" \
    || -z "${KEY_ALIAS:-}" || -z "${KEY_PASSWORD:-}" ]]; then
  echo "KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD are required" >&2
  exit 1
fi
"$bt/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --out "$out/archphene.apk" "$out/aligned.apk"
"$bt/apksigner" verify --verbose --print-certs "$out/archphene.apk"
echo "Linux-built Archphene APK ($artifact_abi): $out/archphene.apk"
echo "Linux-built Terminal APK: $root/prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
