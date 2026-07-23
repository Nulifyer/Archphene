#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
android_sdk=
skip_build=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --android-sdk) android_sdk="${2:?}"; shift 2 ;;
    --skip-build) skip_build=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--android-sdk PATH] [--skip-build]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -z "$android_sdk" ]] || export ANDROID_SDK_ROOT="$android_sdk"
if [[ "$skip_build" == false ]]; then
  build_args=(--include-package-runtime --serial "$serial")
  [[ -z "$android_sdk" ]] || build_args+=(--android-sdk "$android_sdk")
  "$ARCHPHENE_SCRIPTS_DIR/build-install-linux-manager-stub.sh" "${build_args[@]}"
fi

archphene_test_init "$serial"
manager=org.archpheneos.manager
verify_dir=files/package-runtime/verify

cleanup() {
  archphene_adb_run shell run-as "$manager" rm -rf "$verify_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT

launch_runtime_hook() {
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" \
    --ez archphene_test_package_runtime true "$@" >/dev/null
}

manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in
  x86_64) native_abi=x86_64 ;;
  arm64-v8a) native_abi=arm64 ;;
  *) archphene_die "unsupported package-runtime test ABI: $abi" ;;
esac
archphene_regex_contains "$manager_dump" "primaryCpuAbi=$abi" \
  || archphene_die "installed manager primary ABI does not match the device"
base_apk="$(archphene_adb_run shell pm path "$manager" \
  | sed -n 's/^package://p' | head -n1 | tr -d '\r')"
[[ "$base_apk" == */base.apk ]] || archphene_die "manager base APK path was not reported"
native_dir="${base_apk%/base.apk}/lib/$native_abi"
archphene_adb_run shell ls "$native_dir/libarchphene_pacman.so" >/dev/null \
  || archphene_die "installed manager pacman runtime is missing"

launch_runtime_hook
archphene_wait_ui 'text="Package runtime exit 0' package-runtime-pacman 20
runtime_ui="$ARCHPHENE_UI"
archphene_regex_contains "$runtime_ui" 'text="[^"]*Pacman v[0-9][^"]*"' \
  || archphene_die "manager did not execute its bundled pacman runtime"

launch_runtime_hook --es archphene_test_resolve_package kcalc
archphene_wait_ui 'text="Resolved kcalc' package-runtime-resolve 60
resolved_ui="$ARCHPHENE_UI"
archphene_regex_contains "$resolved_ui" \
  'text="Resolved kcalc&#10;[1-9][0-9]* packages through libalpm"' \
  || archphene_die "manager did not resolve KCalc through libalpm"

launch_runtime_hook \
  --es archphene_test_resolve_package kcalc \
  --ez archphene_test_download_target true
archphene_wait_ui 'text="Downloaded and verified kcalc' package-runtime-download 90
download_ui="$ARCHPHENE_UI"
download_signer="$(python3 -c '
import re, sys
match = re.search(r"Signer ([0-9A-F]{40})", sys.stdin.read())
print(match.group(1) if match else "")
' <<<"$download_ui")"
[[ -n "$download_signer" ]] \
  || archphene_die "verified KCalc download did not expose a signer fingerprint"

cached="$(archphene_adb_run shell run-as "$manager" \
  find files/package-runtime/downloads -maxdepth 1 -type f)"
package_path="$(python3 -c '
import re, sys
for line in sys.stdin.read().splitlines():
    if re.search(r"/kcalc-[^/]+-(?:x86_64|aarch64)\.pkg\.tar\.(?:zst|xz)$", line):
        print(line)
        break
' <<<"$cached")"
[[ -n "$package_path" ]] || archphene_die "verified KCalc package cache is missing"
signature_path="$package_path.sig"
[[ "$cached" == *"$signature_path"* ]] \
  || archphene_die "verified KCalc detached signature cache is missing"

archphene_adb_run shell run-as "$manager" mkdir -p "$verify_dir"
archphene_adb_run shell run-as "$manager" cp "$package_path" "$verify_dir/package.pkg"
archphene_adb_run shell run-as "$manager" cp "$signature_path" "$verify_dir/package.pkg.sig"
private_root="/data/user/0/$manager"

launch_runtime_hook \
  --es archphene_test_package_file "$private_root/$verify_dir/package.pkg" \
  --es archphene_test_signature_file "$private_root/$verify_dir/package.pkg.sig"
archphene_wait_ui 'text="Verified Arch package' package-runtime-detached 30
detached_ui="$ARCHPHENE_UI"
detached_signer="$(python3 -c '
import re, sys
match = re.search(r"Signer ([0-9A-F]{40})", sys.stdin.read())
print(match.group(1) if match else "")
' <<<"$detached_ui")"
[[ "$detached_signer" == "$download_signer" ]] \
  || archphene_die "detached verification signer differs from verified download"

archphene_adb_run shell run-as "$manager" cp \
  "$verify_dir/package.pkg" "$verify_dir/tampered.pkg"
archphene_adb_run shell run-as "$manager" sh -c \
  "'printf tampered >> $verify_dir/tampered.pkg'"
archphene_adb_run logcat -c
launch_runtime_hook \
  --es archphene_test_package_file "$private_root/$verify_dir/tampered.pkg" \
  --es archphene_test_signature_file "$private_root/$verify_dir/package.pkg.sig"
tampered_log="$(archphene_wait_log \
  'Package runtime test failed: java\.lang\.SecurityException: Arch package signature verification failed' \
  30 'ArchphenePackages:V AndroidRuntime:E *:S')"
[[ "$tampered_log" == *'BADSIG'* && "$tampered_log" != *'verified kcalc signer='* ]] \
  || archphene_die "tampered package rejection lacked exact GPG failure evidence"

archphene_note "Manager package runtime passed on $serial ($abi, $native_dir): pacman execution, libalpm resolution, trusted download, exact detached signer $download_signer, and tamper rejection."
