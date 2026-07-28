#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
output_dir="$ARCHPHENE_ROOT/tooling/build/manager-navigation"
mkdir -p "$output_dir"

initial_accelerometer_rotation="$(
  archphene_adb_run shell settings get system accelerometer_rotation | tr -d '\r'
)"
initial_user_rotation="$(
  archphene_adb_run shell settings get system user_rotation | tr -d '\r'
)"
[[ "$initial_accelerometer_rotation" =~ ^[01]$ ]] ||
  archphene_die "unexpected accelerometer rotation setting: $initial_accelerometer_rotation"
[[ "$initial_user_rotation" =~ ^[0-3]$ ]] ||
  archphene_die "unexpected user rotation setting: $initial_user_rotation"

cleanup() {
  archphene_adb_run shell settings put system accelerometer_rotation \
    "$initial_accelerometer_rotation" >/dev/null 2>&1 || true
  archphene_adb_run shell settings put system user_rotation \
    "$initial_user_rotation" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_skip_storage_onboarding "manager-navigation-onboarding-$serial"

archphene_wait_ui \
  'text="Packages"[^>]*class="android\.widget\.Button"[^>]*selected="true"' \
  "manager-navigation-packages-default-$serial" 15
archphene_wait_ui \
  'text="Installed"[^>]*class="android\.widget\.Button"[^>]*selected="true"' \
  "manager-navigation-installed-default-$serial" 15
archphene_wait_ui_exact_text \
  "No Linux packages installed" \
  "manager-navigation-packages-content-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-packages.png"

archphene_open_manager_section Files "manager-navigation-files-$serial"
archphene_wait_ui_exact_text \
  "Android and Linux files" "manager-navigation-files-title-$serial" 15
archphene_wait_ui_exact_text \
  "No Android folder connected" "manager-navigation-files-status-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-files.png"

archphene_open_manager_section Terminal "manager-navigation-terminal-$serial"
archphene_wait_ui_exact_text \
  "Shared Linux terminal" "manager-navigation-terminal-title-$serial" 15
archphene_wait_ui_exact_text \
  "Start shell" \
  "manager-navigation-terminal-start-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-terminal.png"

if [[ "$initial_user_rotation" == 0 || "$initial_user_rotation" == 2 ]]; then
  test_rotation=1
else
  test_rotation=0
fi
archphene_adb_run shell settings put system accelerometer_rotation 0 >/dev/null
archphene_adb_run shell settings put system user_rotation "$test_rotation" >/dev/null
archphene_wait_ui \
  'text="Terminal"[^>]*class="android\.widget\.Button"[^>]*selected="true"' \
  "manager-navigation-terminal-rotated-$serial" 20
archphene_wait_ui_exact_text \
  "Shared Linux terminal" "manager-navigation-terminal-rotated-content-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-terminal-rotated.png"

archphene_adb_run shell settings put system accelerometer_rotation \
  "$initial_accelerometer_rotation" >/dev/null
archphene_adb_run shell settings put system user_rotation \
  "$initial_user_rotation" >/dev/null
archphene_wait_ui \
  'text="Terminal"[^>]*class="android\.widget\.Button"[^>]*selected="true"' \
  "manager-navigation-terminal-restored-$serial" 20

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Manager navigation emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene manager navigation passed on $serial"
archphene_note "  Packages, Files, Terminal, and rotation persistence passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{packages,files,terminal,terminal-rotated}.png"
