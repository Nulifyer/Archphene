#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
clean_data=false
reuse_active_session=false
timeout=45
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    --clean-data) clean_data=true; shift ;;
    --reuse-active-session) reuse_active_session=true; shift ;;
    --timeout-seconds) timeout="${2:?missing value for --timeout-seconds}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package GENERATED_LAUNCHER [--manager PACKAGE] [--artifact-dir PATH] [--clean-data] [--reuse-active-session] [--timeout-seconds SECONDS]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package must be a generated Archphene launcher"
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 20 && timeout <= 180)) ||
  archphene_die "--timeout-seconds must be 20..180"
archphene_test_init "$serial"

serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/launcher-notifications/$serial_slug}"
mkdir -p "$artifact_dir"

cleanup() {
  archphene_adb_run shell cmd statusbar collapse >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$clean_data" == true ]]; then
    # Generated wrappers hold only Android integration preferences and
    # channels. Linux packages and user files remain under the manager UID.
    archphene_adb_run shell pm clear "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

package_dump="$(archphene_adb_run shell dumpsys package "$package")"
archphene_regex_contains "$package_dump" \
  '(?m)^\s*android\.permission\.POST_NOTIFICATIONS\s*$' ||
  archphene_die "generated launcher does not declare notification permission"
if [[ "$clean_data" == true ]]; then
  archphene_adb_run shell pm clear "$package" >/dev/null
else
  archphene_regex_contains "$package_dump" \
    'android\.permission\.POST_NOTIFICATIONS: granted=true' ||
    archphene_die \
      "notification permission is unresolved; rerun with --clean-data to test first-use consent"
fi

activity="$(archphene_launcher "$package")"
if [[ "$reuse_active_session" == false ]]; then
  archphene_adb_run logcat -c
fi
archphene_adb_run shell am start -W -n "$activity" >/dev/null
if [[ "$reuse_active_session" == false ]]; then
  archphene_wait_log \
    'Private desktop portal ready session=' "$timeout" \
    'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
fi

bus=
deadline=$((SECONDS + timeout))
while ((SECONDS < deadline)); do
  bus="$(
    archphene_adb_run shell run-as "$manager" find cache -name bus -type s -print \
      2>/dev/null | tr -d '\r'
  )"
  [[ "$bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] && break
  sleep 0.2
done
[[ "$bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] ||
  archphene_die "expected exactly one private launcher bus, received: $bus"

manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
native_dir="$(
  sed -n 's/.*legacyNativeLibraryDir=//p' <<<"$manager_dump" |
    head -n 1 | tr -d '\r'
)"
abi="$(
  sed -n 's/.*primaryCpuAbi=//p' <<<"$manager_dump" |
    head -n 1 | tr -d '\r'
)"
case "$abi" in
  arm64-v8a) abi_directory=arm64 ;;
  x86_64) abi_directory=x86_64 ;;
  *) archphene_die "unsupported manager ABI: $abi" ;;
esac
[[ "$native_dir" =~ ^/data/app/[A-Za-z0-9_~+./=-]+/lib$ ]] ||
  archphene_die "manager native library directory is invalid"

address="unix:path=/data/user/0/$manager/$bus"
probe="$native_dir/$abi_directory/libarchphene_portal_probe.so"
invoke_probe() {
  local arguments="$1"
  local command
  command="run-as $manager sh -c 'DBUS_SESSION_BUS_ADDRESS=$address \"$probe\" $arguments'"
  archphene_adb_run shell "$command" | tr -d '\r'
}

notify_output="$(invoke_probe notify)"
archphene_regex_contains "$notify_output" \
  'PASS portal notification accepted' &&
  archphene_regex_contains "$notify_output" \
    'PASS classic notification accepted id=[1-9][0-9]*' ||
  archphene_die "desktop notification adapters failed: $notify_output"
classic_id="$(
  sed -n 's/.*PASS classic notification accepted id=\([1-9][0-9]*\).*/\1/p' \
    <<<"$notify_output"
)"

if [[ "$clean_data" == true ]]; then
  permission_pattern='resource-id="com\.android\.permissioncontroller:id/permission_allow_button"'
  archphene_wait_ui "$permission_pattern" launcher-notification-permission "$timeout"
  archphene_adb_run exec-out screencap -p >"$artifact_dir/permission.png"
  permission_ui="$(archphene_capture_ui launcher-notification-permission-tap)"
  archphene_tap_ui_pattern "$permission_ui" "$permission_pattern" "notification Allow"
fi

deadline=$((SECONDS + timeout))
active=
while ((SECONDS < deadline)); do
  active="$(
    archphene_adb_run shell dumpsys notification --noredact |
      sed -n '/^  Notification List:/,/^  Notification attention state:/p'
  )"
  if [[ "$active" == *"pkg=$package"* &&
    "$active" == *"Archphene classic probe"* &&
    "$active" == *"Archphene portal probe"* ]]; then
    break
  fi
  sleep 0.2
done
[[ "$active" == *"pkg=$package"* &&
  "$active" == *"Archphene classic probe"* &&
  "$active" == *"Archphene portal probe"* ]] ||
  archphene_die "wrapper-owned Android notifications were not posted"

archphene_adb_run shell input keyevent KEYCODE_HOME
archphene_adb_run shell cmd statusbar expand-notifications
sleep 1
archphene_adb_run exec-out screencap -p >"$artifact_dir/shade.png"
shade_ui="$(archphene_capture_ui launcher-notification-shade)"
archphene_tap_ui_pattern \
  "$shade_ui" 'text="Archphene (?:classic|portal) probe"' "Linux notification"
sleep 0.5
top="$(archphene_adb_run shell dumpsys activity activities)"
archphene_regex_contains "$top" \
  "topResumedActivity=.*${package//./\\.}/org\\.archphene\\.launcher\\.LauncherActivity" ||
  archphene_die "notification content intent did not reopen its generated launcher"

withdraw_output="$(invoke_probe "withdraw $classic_id")"
[[ "$withdraw_output" == *"PASS notification withdrawal"* ]] ||
  archphene_die "desktop notification withdrawal failed: $withdraw_output"
sleep 0.5
active="$(
  archphene_adb_run shell dumpsys notification --noredact |
    sed -n '/^  Notification List:/,/^  Notification attention state:/p'
)"
[[ "$active" != *"pkg=$package"* ]] ||
  archphene_die "withdrawn wrapper notification remains active"

fatal="$(
  archphene_adb_run logcat -d -v brief \
    'AndroidRuntime:E' 'libc:F' 'ArchphenePortal:E' 'ArchpheneLauncherSession:E' '*:S'
)"
[[ "$fatal" != *"FATAL EXCEPTION"* && "$fatal" != *"Fatal signal"* ]] ||
  archphene_die "fatal launcher notification log detected: $fatal"

archphene_note \
  "Generated-launcher notifications passed on $serial ($abi): first-use consent=$clean_data, portal/classic post, wrapper attribution, content intent, withdrawal, full-device evidence."
