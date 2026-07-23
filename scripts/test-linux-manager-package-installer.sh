#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archpheneos.manager
kcalc=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --manager) manager="${2:?}"; shift 2 ;;
    --package) kcalc="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--manager PACKAGE] [--package KCALC_PACKAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
sdk="$(archphene_android_sdk)"
apksigner="$(archphene_android_tool "$sdk" build-tools/36.0.0/apksigner)"
build_dir="$ARCHPHENE_ROOT/tooling/build"
mkdir -p "$build_dir"
safe_serial="$(sed 's/[^A-Za-z0-9._-]/_/g' <<<"$serial")"
candidate="$build_dir/package-installer-current-kcalc-$safe_serial.apk"
installed_after="$build_dir/package-installer-updated-kcalc-$safe_serial.apk"
remote=/data/local/tmp/archpheneos-kcalc-update.apk
private="cache/archpheneos-kcalc-update.apk"

package_apk_path() {
  archphene_adb_run shell pm path "$1" \
    | head -n1 \
    | sed 's/^package://;s/\r$//'
}

package_dump() {
  archphene_adb_run shell dumpsys package "$1"
}

package_uid() {
  archphene_adb_run shell cmd package list packages -U "$1" \
    | sed -n "s/^package:$1 uid:\\([0-9][0-9]*\\).*/\\1/p" \
    | head -n1
}

package_value() {
  local dump="$1" name="$2"
  sed -n "s/^[[:space:]]*$name=\\([^[:space:]]*\\).*/\\1/p" <<<"$dump" \
    | head -n1
}

apk_signer() {
  "$apksigner" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
    | tr '[:upper:]' '[:lower:]' \
    | head -n1
}

original_appop="$(archphene_adb_run shell appops get \
  "$manager" REQUEST_INSTALL_PACKAGES 2>/dev/null \
  | sed -n 's/^[^:]*: \\([^; ]*\\).*/\\1/p' \
  | head -n1)"
case "$original_appop" in
  allow|deny|ignore|default|foreground|ask) ;;
  *) original_appop=default ;;
esac

cleanup() {
  archphene_adb_run shell appops set \
    "$manager" REQUEST_INSTALL_PACKAGES "$original_appop" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$remote" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -f "$private" >/dev/null 2>&1 || true
  rm -f "$candidate" "$installed_after"
}
trap cleanup EXIT

before_path="$(package_apk_path "$kcalc")"
[[ -n "$before_path" ]] \
  || archphene_die "installed manager-generated KCalc is unavailable: $kcalc"
archphene_adb_run pull "$before_path" "$candidate" >/dev/null
candidate_hash="$(archphene_sha256_file "$candidate")"
candidate_signer="$(apk_signer "$candidate")"
[[ "$candidate_signer" =~ ^[0-9a-f]{64}$ ]] \
  || archphene_die "could not read the installed KCalc signing certificate"

before_dump="$(package_dump "$kcalc")"
before_version_code="$(package_value "$before_dump" versionCode)"
before_version_name="$(package_value "$before_dump" versionName)"
before_first_install="$(package_value "$before_dump" firstInstallTime)"
before_uid="$(package_uid "$kcalc")"
[[ -n "$before_version_code" && -n "$before_version_name"
    && -n "$before_first_install" && -n "$before_uid" ]] \
  || archphene_die "installed KCalc identity metadata is incomplete"

archphene_adb_run push "$candidate" "$remote" >/dev/null
archphene_adb_run shell run-as "$manager" cp "$remote" "$private"
archphene_adb_run shell run-as "$manager" chmod 600 "$private"
archphene_adb_run shell appops set "$manager" REQUEST_INSTALL_PACKAGES allow
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -f 0x20000000 \
  -n "$manager/.MainActivity" \
  --es archphene_test_apk_url \
    "file:///data/user/0/$manager/$private" \
  --es archphene_test_apk_sha256 "$candidate_hash" \
  --es archphene_test_apk_package "$kcalc" >/dev/null

archphene_wait_ui 'text="Update"' package-installer-confirm 30
confirmation="$ARCHPHENE_UI"
archphene_regex_contains "$confirmation" \
  'package="com\.(google\.android\.)?(packageinstaller|permissioncontroller)"' \
  || archphene_die "Android PackageInstaller did not own the update confirmation"
[[ "$confirmation" == *'text="KCalc"'* ]] \
  || archphene_die "KCalc update confirmation did not identify the application"
archphene_tap_ui_pattern "$confirmation" 'text="Update"' \
  "Android KCalc update confirmation"
archphene_wait_ui_exact_text \
  'Android package update installed' package-installer-result 30

after_path="$(package_apk_path "$kcalc")"
[[ -n "$after_path" ]] || archphene_die "KCalc disappeared after replacement"
archphene_adb_run pull "$after_path" "$installed_after" >/dev/null
after_hash="$(archphene_sha256_file "$installed_after")"
after_signer="$(apk_signer "$installed_after")"
after_dump="$(package_dump "$kcalc")"
after_version_code="$(package_value "$after_dump" versionCode)"
after_version_name="$(package_value "$after_dump" versionName)"
after_first_install="$(package_value "$after_dump" firstInstallTime)"
after_uid="$(package_uid "$kcalc")"

[[ "$after_hash" == "$candidate_hash" ]] \
  || archphene_die "Android installed APK bytes differ from the verified candidate"
[[ "$after_signer" == "$candidate_signer" ]] \
  || archphene_die "KCalc signing identity changed during replacement"
[[ "$after_version_code" == "$before_version_code"
    && "$after_version_name" == "$before_version_name" ]] \
  || archphene_die "KCalc version changed during same-version update"
[[ "$after_uid" == "$before_uid"
    && "$after_first_install" == "$before_first_install" ]] \
  || archphene_die "KCalc was reinstalled instead of replaced in place"

"$ARCHPHENE_SCRIPTS_DIR/test-kcalc-calculation.sh" \
  --serial "$serial" --package "$kcalc"
archphene_note \
  "Manager PackageInstaller update passed on $serial: Android confirmation, exact APK/signer, stable UID/install identity, and KCalc health verified."
