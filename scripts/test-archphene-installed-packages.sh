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
receiver="$package/org.archphene.app.InstalledPackagesTestReceiver"
action=org.archphene.app.debug.action.SEED_INSTALLED_PACKAGES
output_dir="$ARCHPHENE_ROOT/tooling/build/installed-packages"
mkdir -p "$output_dir"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
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

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "installed-$serial_slug" >/dev/null
archphene_wait_log \
  "Seeded installed packages token=installed-$serial_slug" 20 \
  "ArchpheneInstalledPackagesProbe:V *:S" >/dev/null

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_skip_storage_onboarding "installed-packages-onboarding-$serial"
archphene_wait_ui_exact_text \
  "67 Linux packages installed" "installed-packages-count-$serial" 20
archphene_wait_ui_exact_text \
  "dotnet-sdk" "installed-packages-first-$serial" 15
archphene_wait_ui_exact_text \
  "10.0.100.sdk100-1" "installed-packages-version-$serial" 15
archphene_wait_ui_exact_text \
  "CLI · Explicit" "installed-packages-reason-$serial" 15
archphene_wait_ui_exact_text \
  "CLI · Dependency" "installed-packages-dependency-$serial" 15
archphene_wait_ui_exact_text \
  "Graphical · CLI · Explicit" "installed-packages-graphical-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-top.png"

archphene_tap_text "$ARCHPHENE_UI" "Search results"
archphene_wait_ui_exact_text \
  "Search the official Arch repositories" "installed-packages-search-mode-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Installed"
archphene_wait_ui_exact_text \
  "67 Linux packages installed" "installed-packages-installed-mode-$serial" 15

archphene_adb_run shell cmd uimode night yes >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui_exact_text \
  "67 Linux packages installed" "installed-packages-dark-$serial" 20
archphene_wait_ui_exact_text \
  "dotnet-sdk" "installed-packages-dark-first-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-dark.png"

read -r screen_width screen_height < <(
  archphene_adb_run shell wm size |
    sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' |
    tail -n1
)
swipe_x=$((screen_width / 2))
swipe_start=$((screen_height * 3 / 4))
swipe_end=$((screen_height / 2))
for _ in {1..18}; do
  archphene_adb_run shell input swipe \
    "$swipe_x" "$swipe_start" "$swipe_x" "$swipe_end" 200
done
archphene_wait_ui_exact_text \
  "fixture-064" "installed-packages-final-page-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-scrolled.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui_exact_text \
  "67 Linux packages installed" "installed-packages-restart-$serial" 20

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Installed package list emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene installed package list passed on $serial"
archphene_note "  Native pagination, verified capability classes, virtualized rows, modes, themes, reasons, and restart passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{top,dark,scrolled}.png"
