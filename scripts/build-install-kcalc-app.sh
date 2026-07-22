#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-build.sh"
skip_install=false; descriptor_path=; android_abi=; serial=
while (($#)); do case "$1" in --skip-install) skip_install=true; shift;; --descriptor-path) descriptor_path="${2:?}"; shift 2;; --android-abi) android_abi="${2:?}"; shift 2;; --serial) serial="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--skip-install] [--descriptor-path PATH] [--android-abi x86_64|arm64-v8a] [--serial SERIAL]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_require_command jq; archphene_init_android_build
app="$ARCHPHENE_ROOT/prototypes/kcalc-android-app"; out="$app/out"; descriptor_path="${descriptor_path:-$app/archphene-app.json}"; archphene_require_file "$descriptor_path"
schema="$(jq -r .schema "$descriptor_path")"; package="$(jq -r .android.package "$descriptor_path")"; source_arch="$(jq -r .source.architecture "$descriptor_path")"
[[ "$schema" == org.archphene.app.v1 ]] || archphene_die "unsupported Archphene app descriptor schema: $schema"
[[ "$package" == org.archphene.linux.kcalc ]] || archphene_die "this KCalc wrapper requires package org.archphene.linux.kcalc"
android_abi="${android_abi:-$([[ "$source_arch" == aarch64 ]] && echo arm64-v8a || echo x86_64)}"; archphene_validate_choice "$android_abi" 'Android ABI' x86_64 arm64-v8a
linux_arch=x86_64; native_arch=x86_64; native_target=x86_64-linux-android; clang_prefix=x86_64
if [[ "$android_abi" == arm64-v8a ]]; then linux_arch=aarch64; native_arch=aarch64; native_target=aarch64-linux-android; clang_prefix=aarch64; fi
[[ "$source_arch" == "$linux_arch" ]] || archphene_die "descriptor architecture $source_arch does not match Android ABI $android_abi"
version_name="$(jq -r .android.versionName "$descriptor_path")"; version_code="$(jq -r .android.versionCode "$descriptor_path")"; label="$(jq -r .android.label "$descriptor_path")"; metadata_url="$(jq -r .source.metadataUrl "$descriptor_path")"; linux_abi="$(jq -r .runtime.linuxAbi "$descriptor_path")"; payload_rel="$(jq -r .payload.apkLibrary "$descriptor_path")"; payload_hash="$(jq -r .payload.sha256 "$descriptor_path" | tr '[:upper:]' '[:lower:]')"
[[ "$(archphene_sha256_file "$app/$payload_rel")" == "$payload_hash" ]] || archphene_die 'packaged Linux entrypoint does not match descriptor SHA-256'
manifest="$app/AndroidManifest.xml"; build_manifest="$(mktemp "$ARCHPHENE_ROOT/tooling/build/kcalc-manifest.XXXXXX.xml")"; trap 'rm -f "$build_manifest"' EXIT
python3 - "$manifest" "$build_manifest" "$version_name" "$metadata_url" "$linux_abi" <<'PY'
import re,sys
s=open(sys.argv[1]).read()
for name,value in zip(('org.archphene.source.version','org.archphene.source.update_url','org.archphene.runtime.abi'),sys.argv[3:]): s=re.sub(r'(android:name="'+re.escape(name)+r'" android:value=")[^"]+',r'\g<1>'+value,s)
open(sys.argv[2],'w').write(s)
PY
for expected in "$package" "$label" "$version_name" "$metadata_url" "$linux_abi"; do grep -Fq "$expected" "$build_manifest" || archphene_die "Android manifest does not match descriptor value: $expected"; done
"$ARCHPHENE_SCRIPTS_DIR/build-native-compositor-podman.sh" --architecture "$native_arch" --release
lib="$app/lib/$android_abi"; mkdir -p "$lib"; cp "$ARCHPHENE_ROOT/native/archphene-compositor/target/$native_target/release/libarchphene_compositor.so" "$lib/"
clang="$ARCHPHENE_NDK_BIN/${clang_prefix}-linux-android35-clang"; archphene_require_file "$clang"; include="$app/wayland-include"
"$clang" -fPIE -pie -O2 -Wall -Wextra -o "$lib/libarchphene_wayland_socket_probe.so" "$app/wayland_socket_probe.c"
"$clang" -DARCHPHENE_CAPABILITY_PROBE_MAIN -fPIE -pie -O2 -Wall -Wextra -Werror -o "$lib/libarchphene_capability_probe.so" "$ARCHPHENE_ROOT/native/archphene-android-capability/archphene_android.c"
for item in frame_client shm_frame_client wayland_shm_client wayland_evented_client wayland_xdg_client; do "$clang" -fPIE -pie -O2 -Wall -Wextra -o "$lib/libarchphene_${item}.so" "$app/archphene_${item}.c"; done
"$clang" -fPIE -pie -O2 -Wall -Wextra -I "$include" -L "$lib" -Wl,--allow-shlib-undefined -o "$lib/libarchphene_wayland_api_client.so" "$app/archphene_wayland_api_client.c" -l:libwayland-client.so.0
"$clang" -shared -fPIC -O2 -Wall -Wextra -I "$include" -Wl,-soname,libarchphene_wayland_client_android.so -o "$lib/libarchphene_wayland_client_android.so" "$app/archphene_wayland_client_android.c"
for suffix in '' _render _xdg; do "$clang" -fPIE -pie -O2 -Wall -Wextra -I "$include" -L "$lib" -Wl,--allow-shlib-undefined -o "$lib/libarchphene_wayland_android_api${suffix}_client.so" "$app/archphene_wayland_api${suffix}_client.c" -l:libarchphene_wayland_client_android.so; done
if [[ "$android_abi" == x86_64 ]]; then GOOS=linux GOARCH=amd64 CGO_ENABLED=0 GO111MODULE=off GOTELEMETRY=off go build -trimpath -ldflags '-s -w' -o "$lib/libarchphene_syscall_probe.so" "$ARCHPHENE_ROOT/prototypes/linux-payloads/syscall-probe/main.go"; fi
key="$(archphene_ensure_named_debug_keystore archpheneos-kcalc-debug.keystore 'CN=KCalc,O=ArchpheneOS,C=US')"; apk_name=archpheneos-kcalc.apk; [[ "$android_abi" == arm64-v8a ]] && apk_name=archpheneos-kcalc-arm64.apk
archphene_package_android_app "$app" "$out" "$build_manifest" "$android_abi" "$version_code" "$version_name" "$key" "$apk_name"
if [[ "$skip_install" == false ]]; then [[ -n "$serial" ]] || archphene_die '--serial is required when installing'; archphene_init_adb "$serial"; archphene_adb_run install -r "$out/$apk_name"; archphene_adb_run shell am start -n "$package/$package.MainActivity"; fi
archphene_note "KCalc wrapper built: $out/$apk_name"
