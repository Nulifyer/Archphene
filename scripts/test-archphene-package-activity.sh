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
receiver="$package/org.archphene.app.PackageJobTestReceiver"
action=org.archphene.app.debug.action.SEED_PACKAGE_JOB
output_dir="$ARCHPHENE_ROOT/tooling/build/package-activity"
mkdir -p "$output_dir"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

seed_job() {
  local state="$1" package_name="$2" token="$3"
  archphene_adb_run logcat -c
  archphene_adb_run shell am broadcast \
    -f 0x20 \
    -n "$receiver" \
    -a "$action" \
    --es token "$token" \
    --es package "$package_name" \
    --es state "$state" >/dev/null
  archphene_wait_log \
    "Seeded package job state=$state token=$token" 20 \
    "ArchphenePackageJobProbe:V *:S" >/dev/null
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null

seed_job complete activity-complete "complete-$serial_slug"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_skip_storage_onboarding "package-activity-onboarding-$serial"
archphene_wait_ui_exact_text \
  "activity-complete" "package-activity-complete-name-$serial" 20
archphene_wait_ui_exact_text \
  "Install · Complete · 100%" "package-activity-complete-state-$serial" 15
archphene_wait_ui_exact_text \
  "Installed activity-complete 1.0.0" "package-activity-complete-message-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-complete.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui_exact_text \
  "Install · Complete · 100%" "package-activity-complete-restart-$serial" 20

archphene_adb_run shell am force-stop "$package" >/dev/null
seed_job failed activity-failed "failed-$serial_slug"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui_exact_text \
  "activity-failed" "package-activity-failed-name-$serial" 20
archphene_wait_ui_exact_text \
  "Install · Failed · 0%" "package-activity-failed-state-$serial" 15
archphene_wait_ui_exact_text \
  "Network unavailable; retry is required" \
  "package-activity-failed-message-$serial" 15
archphene_wait_ui_exact_text \
  "Review" "package-activity-failed-review-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-failed.png"

archphene_tap_text "$ARCHPHENE_UI" "Review"
archphene_wait_ui \
  'text="activity-failed"[^>]*class="android.widget.EditText"' \
  "package-activity-review-package-$serial" 15
archphene_wait_ui_text \
  "Package resolution failed:" "package-activity-review-resolution-$serial" 20

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Package activity emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene package activity passed on $serial"
archphene_note "  Durable complete/restart, failed state, and safe Review route passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{complete,failed}.png"
