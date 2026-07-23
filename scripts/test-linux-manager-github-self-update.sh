#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
from_version=0.9.0
from_version_code=9000
to_version=1.0.1
rebuild_baseline=false
published_v100=false
prepare_only=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --from-version) from_version="${2:?}"; shift 2 ;;
    --from-version-code) from_version_code="${2:?}"; shift 2 ;;
    --to-version) to_version="${2:?}"; shift 2 ;;
    --rebuild-baseline) rebuild_baseline=true; shift ;;
    --published-v100-migration) published_v100=true; shift ;;
    --prepare-baseline-only) prepare_only=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--from-version VERSION] [--from-version-code CODE] [--to-version VERSION] [--rebuild-baseline] [--published-v100-migration] [--prepare-baseline-only]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ "$from_version_code" =~ ^[0-9]+$ ]] \
  || archphene_die "from-version-code must be an integer"
[[ "$prepare_only" == false || "$published_v100" == true ]] \
  || archphene_die "--prepare-baseline-only requires --published-v100-migration"

archphene_test_init "$serial"
device_abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$device_abi" in
  x86_64) artifact_abi=x86_64 ;;
  arm64-v8a) artifact_abi=arm64-v8a ;;
  *) archphene_die "unsupported Android ABI for self-update: $device_abi" ;;
esac

build_dir="$ARCHPHENE_ROOT/tooling/build/manager-github-self-update"
mkdir -p "$build_dir"
baseline="$build_dir/Archphene-$artifact_abi-$from_version-production.apk"
if [[ "$published_v100" == true ]]; then
  [[ "$device_abi" == x86_64 ]] \
    || archphene_die "published v1.0.0 migration is x86_64-only, not $device_abi"
  from_version=1.0.0
  from_version_code=1000000002
  to_version=1.0.1
  baseline="$build_dir/Archphene-1.0.0-production.apk"
  download_dir="$build_dir/v1.0.0"
  mkdir -p "$download_dir"
  if [[ "$rebuild_baseline" == true || ! -f "$baseline" ]]; then
    archphene_require_command gh
    gh release download v1.0.0 \
      --repo Nulifyer/Archphene \
      --clobber \
      --pattern 'Archphene-1.0.0.apk*' \
      --dir "$download_dir"
    published_apk="$download_dir/Archphene-1.0.0.apk"
    checksum_file="$download_dir/Archphene-1.0.0.apk.sha256"
    archphene_require_file "$published_apk"
    archphene_require_file "$checksum_file"
    read -r expected_hash expected_name <"$checksum_file"
    expected_name="${expected_name#\\*}"
    [[ "$expected_hash" =~ ^[0-9a-f]{64}$
        && "$expected_name" == Archphene-1.0.0.apk
        && "$(archphene_sha256_file "$published_apk")" == "$expected_hash" ]] \
      || archphene_die "published v1.0.0 checksum verification failed"
    cp "$published_apk" "$baseline"
  fi
elif [[ "$rebuild_baseline" == true || ! -f "$baseline" ]]; then
  current_apk="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/out-linux/archphene.apk"
  saved_current=
  if [[ -f "$current_apk" ]]; then
    saved_current="$(mktemp "$build_dir/current-manager.XXXXXX.apk")"
    cp "$current_apk" "$saved_current"
  fi
  restore_current() {
    if [[ -n "$saved_current" && -f "$saved_current" ]]; then
      cp "$saved_current" "$current_apk"
      rm -f "$saved_current"
    fi
  }
  trap restore_current EXIT
  "$ARCHPHENE_SCRIPTS_DIR/build-manager-podman.sh" \
    --skip-runtime \
    --release-build \
    --artifact-abi "$artifact_abi" \
    --version-code "$from_version_code" \
    --version-name "$from_version"
  archphene_require_file "$current_apk"
  cp "$current_apk" "$baseline"
  restore_current
  trap - EXIT
fi

archphene_require_file "$baseline"
sdk="$(archphene_android_sdk)"
aapt2="$(archphene_android_tool "$sdk" build-tools/36.0.0/aapt2)"
apksigner="$(archphene_android_tool "$sdk" build-tools/36.0.0/apksigner)"
badging="$("$aapt2" dump badging "$baseline")"
archphene_regex_contains "$badging" \
  "package: name='org\\.archpheneos\\.manager'.*versionCode='$from_version_code'.*versionName='$from_version'" \
  || archphene_die "baseline package/version identity does not match $from_version"
[[ "$badging" == *"native-code: '$artifact_abi'"* ]] \
  || archphene_die "baseline is not the exact $artifact_abi release artifact"
baseline_signer="$("$apksigner" verify --print-certs "$baseline" 2>/dev/null \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
  | tr '[:upper:]' '[:lower:]' \
  | head -n1)"
[[ "$baseline_signer" =~ ^[0-9a-f]{64}$ ]] \
  || archphene_die "baseline APK signature verification failed"

if [[ "$prepare_only" == true ]]; then
  archphene_note \
    "Published v1.0.0 migration baseline verified: $baseline ($artifact_abi, signer $baseline_signer)"
  exit 0
fi

package=org.archpheneos.manager
archphene_adb_run uninstall "$package" >/dev/null 2>&1 || true
archphene_adb_run install "$baseline" >/dev/null
archphene_adb_run shell appops set "$package" REQUEST_INSTALL_PACKAGES allow
archphene_adb_run shell am start -W -n "$package/.MainActivity" >/dev/null

startup_ui="$(archphene_capture_ui archphene-github-startup)"
if archphene_regex_contains "$startup_ui" \
  'text="This app isn.t 16 KB compatible"'; then
  archphene_tap_ui_pattern "$startup_ui" 'text="OK"' \
    "Android 16 KB compatibility warning"
fi

escaped_from="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$from_version")"
escaped_to="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$to_version")"
archphene_wait_ui \
  "Installed $escaped_from\\. Not checked" archphene-github-baseline 45
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Check Archphene for updates[^"]*"' "update check"
archphene_wait_ui \
  "Archphene update $escaped_to available" archphene-github-discovered 120
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Archphene"' \
  "Archphene details"
archphene_wait_ui \
  'resource-id="android:id/text1"' archphene-github-details 30
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'resource-id="android:id/text1"' "version selector"
archphene_wait_ui \
  "text=\"$escaped_to\"" archphene-github-versions 30
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  "text=\"$escaped_to\"" "release version"
archphene_wait_ui \
  'text="Install selected version"' archphene-github-selected 30
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Install selected version"' "install release"

archphene_wait_ui \
  'package="com\.(google\.android\.)?(packageinstaller|permissioncontroller)".*text="Update"' \
  archphene-github-system-confirm 90
confirmation="$ARCHPHENE_UI"
archphene_regex_contains "$confirmation" \
  'text="Do you want to update this app\?"' \
  || archphene_die "Android did not present an update confirmation"
before_dump="$(archphene_adb_run shell dumpsys package "$package")"
before_uid="$(archphene_adb_run shell cmd package list packages -U "$package" \
  | sed -n "s/^package:$package uid:\\([0-9][0-9]*\\).*/\\1/p" \
  | head -n1)"
before_first_install="$(sed -n \
  's/^[[:space:]]*firstInstallTime=//p' <<<"$before_dump" | head -n1)"
archphene_tap_ui_pattern "$confirmation" 'text="Update"' \
  "Android update confirmation"

deadline=$((SECONDS + 60))
after_dump=
while ((SECONDS < deadline)); do
  after_dump="$(archphene_adb_run shell dumpsys package "$package" 2>/dev/null || true)"
  archphene_regex_contains "$after_dump" \
    "versionName=$escaped_to" && break
  sleep 1
done
archphene_regex_contains "$after_dump" "versionName=$escaped_to" \
  || archphene_die "GitHub release $to_version was not installed"
after_path="$(archphene_adb_run shell pm path "$package" \
  | head -n1 | sed 's/^package://;s/\r$//')"
updated_apk="$build_dir/installed-$artifact_abi-$to_version.apk"
archphene_adb_run pull "$after_path" "$updated_apk" >/dev/null
updated_signer="$("$apksigner" verify --print-certs "$updated_apk" 2>/dev/null \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
  | tr '[:upper:]' '[:lower:]' \
  | head -n1)"
after_uid="$(archphene_adb_run shell cmd package list packages -U "$package" \
  | sed -n "s/^package:$package uid:\\([0-9][0-9]*\\).*/\\1/p" \
  | head -n1)"
after_first_install="$(sed -n \
  's/^[[:space:]]*firstInstallTime=//p' <<<"$after_dump" | head -n1)"
[[ "$updated_signer" == "$baseline_signer" ]] \
  || archphene_die "GitHub update signer differs from the production baseline"
[[ -n "$before_uid" && "$after_uid" == "$before_uid"
    && -n "$before_first_install"
    && "$after_first_install" == "$before_first_install" ]] \
  || archphene_die "manager update did not replace the baseline in place"

archphene_adb_run shell am start -W -n "$package/.MainActivity" >/dev/null
archphene_wait_ui \
  "Archphene $escaped_to is up to date" archphene-github-reconciled 60
! archphene_regex_contains "$ARCHPHENE_UI" \
  "update $escaped_to available" \
  || archphene_die "manager retained stale update state after replacement"

mode="exact-ABI release"
[[ "$published_v100" == false ]] || mode="published v1.0.0 migration"
archphene_note \
  "Live GitHub self-update passed on $serial ($mode): $from_version -> $to_version, exact $artifact_abi asset, production signer, Android confirmation, stable UID, and reconciled restart verified."
