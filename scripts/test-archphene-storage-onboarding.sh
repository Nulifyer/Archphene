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
output_dir="$ARCHPHENE_ROOT/tooling/build/storage-onboarding"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

launch_clean() {
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run shell pm clear "$package" >/dev/null
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
  archphene_wait_ui_exact_text \
    "Connect Android files?" "storage-onboarding-prompt-$serial" 20
  archphene_regex_contains "$ARCHPHENE_UI" \
    'text="Archphene keeps Linux packages, projects, and tools in private app storage[^"]+initial snapshot, not live synchronization[^"]+does not require broad access to all files' ||
    archphene_die "storage onboarding did not explain the private/shared model"
  archphene_regex_contains "$ARCHPHENE_UI" 'text="(?:CHOOSE FOLDER|Choose folder)"' ||
    archphene_die "storage onboarding did not offer folder selection"
  archphene_regex_contains "$ARCHPHENE_UI" 'text="(?:NOT NOW|Not now)"' ||
    archphene_die "storage onboarding did not offer a skip action"
}

assert_onboarding_absent_after_restart() {
  local phase="$1"
  local phase_slug="${phase// /-}"
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_open_manager_section \
    Files "storage-onboarding-restart-files-$phase_slug-$serial"
  archphene_wait_ui_exact_text \
    "No Android folder connected" "storage-onboarding-restart-$phase_slug-$serial" 20
  if archphene_regex_contains "$ARCHPHENE_UI" 'text="Connect Android files\?"'; then
    archphene_die "storage onboarding repeated after $phase"
  fi
}

archphene_adb_run install -r "$apk" >/dev/null

launch_clean
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-prompt.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:NOT NOW|Not now)"' "Not now"
archphene_open_manager_section Files "storage-onboarding-skipped-files-$serial"
archphene_wait_ui_exact_text \
  "No Android folder connected" "storage-onboarding-skipped-$serial" 15
assert_onboarding_absent_after_restart "skip"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-skipped.png"

launch_clean
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:CHOOSE FOLDER|Choose folder)"' "Choose folder"
archphene_wait_ui 'package="com\.(google\.)?android\.documentsui"' \
  "storage-onboarding-picker-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-picker.png"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_open_manager_section Files "storage-onboarding-picker-files-$serial"
archphene_wait_ui_exact_text \
  "No Android folder connected" "storage-onboarding-picker-cancel-$serial" 20
assert_onboarding_absent_after_restart "folder-picker cancellation"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Storage onboarding emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene storage onboarding passed on $serial"
archphene_note "  Explanation, Not now, Choose folder, picker cancellation, and no-repeat restart passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{prompt,skipped,picker}.png"
