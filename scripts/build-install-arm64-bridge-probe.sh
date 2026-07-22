#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-build.sh"
serial=; skip_install=false
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --skip-install) skip_install=true; shift;; -h|--help) echo "usage: $0 --serial SERIAL [--skip-install]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
[[ -n "$serial" ]] || archphene_die '--serial is required'; archphene_init_adb "$serial"; [[ "$(archphene_adb_run get-state 2>/dev/null || true)" == device ]] || archphene_die "ADB device $serial is not authorized and online"
abis="$(archphene_adb_run shell getprop ro.product.cpu.abilist | tr -d '\r')"; [[ ",$abis," == *,arm64-v8a,* ]] || archphene_die "device $serial does not advertise arm64-v8a: $abis"
archphene_init_android_build; app="$ARCHPHENE_ROOT/prototypes/arm64-bridge-probe"; out="$app/out"; rm -rf "$out"; mkdir -p "$out"/{compiled,gen,classes,dex,apk/lib/arm64-v8a,glibc-root}; lib="$out/apk/lib/arm64-v8a"
clang="$ARCHPHENE_NDK_BIN/aarch64-linux-android35-clang"; compiler="$ARCHPHENE_NDK_BIN/clang"; linker="$ARCHPHENE_NDK_BIN/ld.lld"; include="$ARCHPHENE_ROOT/prototypes/mousepad-android-app/wayland-include"
"$clang" -shared -fPIC -O2 -Wall -Wextra -o "$lib/libarchphene_arm64_probe.so" "$app/arm64_probe.c" -ldl
"$clang" -shared -fPIC -O2 -Wall -Wextra -I "$include" -Wl,-soname,libarchphene_wayland_client_android.so -o "$lib/libarchphene_wayland_client_android.so" "$ARCHPHENE_ROOT/prototypes/mousepad-android-app/archphene_wayland_client_android.c"
glibc_pkg="$ARCHPHENE_ROOT/tooling/downloads/archlinuxarm-aarch64/glibc-2.43+r22+g8362e8ce10b2-2-aarch64.pkg.tar.xz"; archphene_require_file "$glibc_pkg"
tar -xf "$glibc_pkg" -C "$out/glibc-root" usr/include usr/lib/Scrt1.o usr/lib/crti.o usr/lib/crtn.o usr/lib/libc.so.6 usr/lib/libc_nonshared.a usr/lib/ld-linux-aarch64.so.1
g="$out/glibc-root"; "$compiler" --target=aarch64-linux-gnu --sysroot="$g" -fPIE -fno-stack-protector -O2 -Wall -Wextra -c "$app/glibc_probe.c" -o "$out/glibc_probe.o"
"$linker" -pie -dynamic-linker ./ld-linux-aarch64.so.1 -o "$lib/libarchphene_glibc_probe.so" "$g/usr/lib/Scrt1.o" "$g/usr/lib/crti.o" "$out/glibc_probe.o" "$g/usr/lib/libc.so.6" "$g/usr/lib/libc_nonshared.a" "$g/usr/lib/ld-linux-aarch64.so.1" "$g/usr/lib/crtn.o"
compat="$ARCHPHENE_ROOT/tooling/build/glibc-archphene-runtime-aarch64"; archphene_require_file "$compat/ld-linux-aarch64.so.1"; archphene_require_file "$compat/libc.so.6"; cp "$compat/ld-linux-aarch64.so.1" "$lib/libarchphene_glibc_loader.so"; cp "$compat/libc.so.6" "$lib/libarchphene_glibc_libc.so"
"$ARCHPHENE_BT/aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"; "$ARCHPHENE_BT/aapt2" link -o "$out/unsigned.apk" -I "$ARCHPHENE_ANDROID_JAR" --manifest "$app/AndroidManifest.xml" --java "$out/gen" "$out/compiled/res.zip"
mapfile -d '' java_files < <(find "$app/src" "$ARCHPHENE_ROOT/prototypes/shared-android-bridge/src" -name '*.java' -type f -print0); javac --release 17 -classpath "$ARCHPHENE_ANDROID_JAR" -d "$out/classes" "${java_files[@]}"; mapfile -d '' class_files < <(find "$out/classes" -name '*.class' -type f -print0); "$ARCHPHENE_BT/d8" --lib "$ARCHPHENE_ANDROID_JAR" --min-api 23 --output "$out/dex" "${class_files[@]}"
cp "$out/dex/classes.dex" "$out/apk/classes.dex"; (cd "$out/apk" && mapfile -d '' entries < <(find . -type f -print0) && jar uf ../unsigned.apk "${entries[@]}")
key="$(archphene_ensure_named_debug_keystore archpheneos-arm64-probe.keystore 'CN=Archphene ARM64 Probe,O=ArchpheneOS,C=US')"; apk="$out/archphene-arm64-bridge-probe.apk"; "$ARCHPHENE_BT/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"; "$ARCHPHENE_BT/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$apk" "$out/aligned.apk"; "$ARCHPHENE_BT/apksigner" verify --verbose "$apk"
if [[ "$skip_install" == false ]]; then archphene_adb_run install -r "$apk"; archphene_adb_run shell am start -W -n org.archphene.bridgeprobe/.MainActivity; fi
archphene_note "ARM64 bridge probe built for $serial ($abis): $apk"
