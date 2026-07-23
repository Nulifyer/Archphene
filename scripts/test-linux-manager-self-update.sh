#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

skip_build=false
serial=emulator-5554
from_code=9000
from_name=0.9.0
to_code=10000
to_name=1.0.0
while (($#)); do
  case "$1" in
    --skip-build) skip_build=true; shift ;;
    --serial) serial="${2:?}"; shift 2 ;;
    --from-version-code) from_code="${2:?}"; shift 2 ;;
    --from-version-name) from_name="${2:?}"; shift 2 ;;
    --to-version-code) to_code="${2:?}"; shift 2 ;;
    --to-version-name) to_name="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$from_code" =~ ^[0-9]+$ && "$to_code" =~ ^[0-9]+$ ]] \
  || archphene_die 'version codes must be integers'
((from_code < to_code)) \
  || archphene_die 'from-version-code must be lower than to-version-code'

archphene_test_init "$serial"
[[ "$(archphene_adb_run shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] \
  || archphene_die 'local self-update is a destructive emulator-only gate'

package=org.archpheneos.manager
build_dir="$ARCHPHENE_ROOT/tooling/build/manager-self-update"
output_apk="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/out-linux/archphene.apk"
old_apk="$build_dir/manager-$from_name.apk"
target_apk="$build_dir/manager-$to_name.apk"
mkdir -p "$build_dir"

saved_output=
restore_output() {
  if [[ -n "$saved_output" && -f "$saved_output" ]]; then
    cp "$saved_output" "$output_apk"
    rm -f "$saved_output"
    saved_output=
  fi
}
if [[ "$skip_build" == false ]]; then
  if [[ -f "$output_apk" ]]; then
    saved_output="$(mktemp "$build_dir/original-build-output.XXXXXX.apk")"
    cp "$output_apk" "$saved_output"
  fi
  trap restore_output EXIT
  "$ARCHPHENE_SCRIPTS_DIR/build-install-linux-manager-stub.sh" \
    --skip-install --version-code "$from_code" --version-name "$from_name"
  archphene_require_file "$output_apk"
  cp "$output_apk" "$old_apk"
  "$ARCHPHENE_SCRIPTS_DIR/build-install-linux-manager-stub.sh" \
    --skip-install --version-code "$to_code" --version-name "$to_name"
  archphene_require_file "$output_apk"
  cp "$output_apk" "$target_apk"
  restore_output
  trap - EXIT
fi
archphene_require_file "$old_apk"
archphene_require_file "$target_apk"

sdk="$(archphene_android_sdk)"
aapt2="$(archphene_android_tool "$sdk" build-tools/36.0.0/aapt2)"
apksigner="$(archphene_android_tool "$sdk" build-tools/36.0.0/apksigner)"
apk_signer() {
  "$apksigner" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
    | tr '[:upper:]' '[:lower:]' \
    | head -n1
}
assert_apk_identity() {
  local apk="$1" code="$2" name="$3" badging
  badging="$("$aapt2" dump badging "$apk")"
  archphene_regex_contains "$badging" \
    "package: name='$package'.*versionCode='$code'.*versionName='$name'" \
    || archphene_die "$apk does not identify $package $name ($code)"
}
dump_has_version() {
  local dump="$1" code="$2" name="$3"
  archphene_regex_contains "$dump" "versionCode=$code" \
    && archphene_regex_contains "$dump" "versionName=$name"
}
assert_apk_identity "$old_apk" "$from_code" "$from_name"
assert_apk_identity "$target_apk" "$to_code" "$to_name"
old_signer="$(apk_signer "$old_apk")"
target_signer="$(apk_signer "$target_apk")"
[[ "$old_signer" =~ ^[0-9a-f]{64}$ && "$target_signer" == "$old_signer" ]] \
  || archphene_die 'local self-update APK signatures do not match'

installed_path="$(archphene_adb_run shell pm path "$package" \
  | head -n1 | sed 's/^package://;s/\r$//')"
[[ -n "$installed_path" ]] || archphene_die 'manager is not installed'
original_apk="$build_dir/installed-before.apk"
archphene_adb_run pull "$installed_path" "$original_apk" >/dev/null
original_signer="$(apk_signer "$original_apk")"
[[ "$original_signer" == "$old_signer" ]] \
  || archphene_die 'installed manager signer differs from local update fixtures'
old_appop="$(archphene_adb_run shell appops get "$package" \
  REQUEST_INSTALL_PACKAGES 2>/dev/null \
  | sed -n 's/.*REQUEST_INSTALL_PACKAGES: \([^; ]*\).*/\1/p' \
  | head -n1)"
old_appop="${old_appop:-default}"
restore_device() {
  archphene_adb_run install -r "$original_apk" >/dev/null 2>&1 || true
  archphene_adb_run shell appops set "$package" REQUEST_INSTALL_PACKAGES \
    "$old_appop" >/dev/null 2>&1 || true
  archphene_adb_run shell am start -W -n "$package/.MainActivity" \
    >/dev/null 2>&1 || true
}
trap 'restore_output; restore_device' EXIT

# Downgrade the same signed, debuggable current-source manager in place so its
# real application data survives this local installer fixture.
archphene_adb_run install -r -d "$old_apk" >/dev/null
baseline_dump="$(archphene_adb_run shell dumpsys package "$package")"
dump_has_version "$baseline_dump" "$from_code" "$from_name" \
  || archphene_die 'local baseline downgrade did not install'
before_uid="$(archphene_adb_run shell cmd package list packages -U "$package" \
  | sed -n "s/^package:$package uid:\\([0-9][0-9]*\\).*/\\1/p" \
  | head -n1)"
before_first_install="$(sed -n \
  's/^[[:space:]]*firstInstallTime=//p' <<<"$baseline_dump" | head -n1)"

archphene_adb_run push "$target_apk" \
  /data/local/tmp/manager-self-update.apk >/dev/null
archphene_adb_run shell run-as "$package" cp \
  /data/local/tmp/manager-self-update.apk cache/manager-self-update.apk
archphene_adb_run shell run-as "$package" chmod 600 \
  cache/manager-self-update.apk
target_hash="$(archphene_sha256_file "$target_apk")"
archphene_adb_run shell appops set "$package" REQUEST_INSTALL_PACKAGES allow
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$package/.MainActivity" \
  --es archphene_test_apk_url \
    "file:///data/user/0/$package/cache/manager-self-update.apk" \
  --es archphene_test_apk_sha256 "$target_hash" \
  --es archphene_test_apk_package "$package" >/dev/null

archphene_wait_ui \
  'package="com\.(google\.android\.)?(packageinstaller|permissioncontroller)".*text="Update"' \
  manager-self-update-confirm 60
confirmation="$ARCHPHENE_UI"
archphene_regex_contains "$confirmation" \
  'text="Do you want to update this app\?"' \
  || archphene_die 'Android did not present an update confirmation'
archphene_tap_ui_pattern "$confirmation" 'text="Update"' \
  'Android update confirmation'

deadline=$((SECONDS + 60))
installed_dump=
while ((SECONDS < deadline)); do
  installed_dump="$(archphene_adb_run shell dumpsys package "$package" \
    2>/dev/null || true)"
  if dump_has_version "$installed_dump" "$to_code" "$to_name"; then
    break
  fi
  sleep 1
done
dump_has_version "$installed_dump" "$to_code" "$to_name" \
  || archphene_die "manager self-update did not install $to_name ($to_code)"
after_uid="$(archphene_adb_run shell cmd package list packages -U "$package" \
  | sed -n "s/^package:$package uid:\\([0-9][0-9]*\\).*/\\1/p" \
  | head -n1)"
after_first_install="$(sed -n \
  's/^[[:space:]]*firstInstallTime=//p' <<<"$installed_dump" | head -n1)"
[[ -n "$before_uid" && "$after_uid" == "$before_uid" \
  && -n "$before_first_install" \
  && "$after_first_install" == "$before_first_install" ]] \
  || archphene_die 'manager self-update did not replace the baseline in place'

updated_path="$(archphene_adb_run shell pm path "$package" \
  | head -n1 | sed 's/^package://;s/\r$//')"
updated_apk="$build_dir/installed-$to_name.apk"
archphene_adb_run pull "$updated_path" "$updated_apk" >/dev/null
[[ "$(archphene_sha256_file "$updated_apk")" == "$target_hash" \
  && "$(apk_signer "$updated_apk")" == "$target_signer" ]] \
  || archphene_die 'installed manager bytes or signer differ from the target'

archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$package/.MainActivity" >/dev/null
escaped_to="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$to_name")"
archphene_wait_ui \
  "content-desc=\"[^\"]*Installed $escaped_to[^\"]*\"" \
  manager-self-update-relaunched 30
archphene_regex_contains "$ARCHPHENE_UI" \
  'text="Apps".*text="Search apps".*text="Settings"' \
  || archphene_die 'restarted manager did not reconcile to its normal app list'
! archphene_regex_contains "$ARCHPHENE_UI" \
  'text="Installing update"|text="Update manager"|text="Continue"' \
  || archphene_die 'restarted manager retained the local installer flow'

restore_device
trap - EXIT
archphene_note "Local manager self-update passed on $serial: $from_name -> $to_name, Android confirmation, exact bytes/signer, stable UID/first-install identity, reconciled restart, and original manager restoration."
