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
output_dir="$ARCHPHENE_ROOT/tooling/build/manager-theme"
mkdir -p "$output_dir"

initial_mode="$(
  archphene_adb_run shell cmd uimode night |
    sed -n 's/^Night mode: //p' |
    tr -d '\r'
)"
[[ "$initial_mode" =~ ^(yes|no|auto|custom_schedule|custom_bedtime)$ ]] ||
  archphene_die "could not determine the original Android night mode: $initial_mode"

cleanup() {
  archphene_adb_run shell cmd uimode night "$initial_mode" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

capture_mode() {
  local mode="$1" label="$2"
  archphene_adb_run shell cmd uimode night "$mode" >/dev/null
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_wait_ui_exact_text \
    "Archphene is ready" "manager-theme-$label-$serial" 20
  archphene_regex_contains "$ARCHPHENE_UI" \
    'content-desc="Rust runtime [0-9]+.*Pacman ready' ||
    archphene_die "$label manager did not retain the runtime diagnostics gate"
  archphene_regex_contains "$ARCHPHENE_UI" 'text="Package catalog [^"]+"' ||
    archphene_die "$label manager did not render package catalog status"
  archphene_regex_contains "$ARCHPHENE_UI" \
    'text="(?:No Android folder connected|Android folder: [^"]+)"' ||
    archphene_die "$label manager did not render Android folder status"
  archphene_adb_run exec-out screencap -p >"$output_dir/$serial-$label.png"
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_skip_storage_onboarding "manager-theme-onboarding-$serial"

capture_mode no light
capture_mode yes dark

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Manager theme gate emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene manager light/dark theme gate passed on $serial"
archphene_note "  Full-device screenshots: $output_dir/$serial-{light,dark}.png"
