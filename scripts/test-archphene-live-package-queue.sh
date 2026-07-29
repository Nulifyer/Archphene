#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
clean_data=false
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --clean-data) clean_data=true; shift ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk] --clean-data"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$clean_data" == true ]] ||
  archphene_die "--clean-data is required because this gate clears Archphene app data"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.PackageSearchTestReceiver"
action=org.archphene.app.debug.action.SEED_PACKAGE_SEARCH
output_dir="$ARCHPHENE_ROOT/tooling/build/live-package-queue"
mkdir -p "$output_dir"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "queue-$serial_slug" \
  --ei worker-hold-ms 5000 >/dev/null
archphene_wait_log \
  "Seeded package search token=queue-$serial_slug" 20 \
  "ArchphenePackageSearchProbe:V *:S" >/dev/null

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_skip_storage_onboarding "live-package-queue-onboarding-$serial"
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$ui" 'text="Package name"[^>]*class="android.widget.EditText"' "Package name"
archphene_adb_run shell input text dotnet
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
ui="$(archphene_capture_ui "live-package-queue-query-$serial")"
archphene_tap_ui_pattern \
  "$ui" 'text="(?:SEARCH|Search)"[^>]*class="android.widget.Button"' Search
archphene_wait_ui_exact_text \
  "3 official packages match dotnet" "live-package-queue-count-$serial" 20
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$ui" 'text="dotnet-sdk"[^>]*class="android.widget.TextView"' "dotnet-sdk"
archphene_wait_ui_text \
  "extra/dotnet-sdk 10.0.100.sdk100-1" \
  "live-package-queue-resolution-$serial" 20
archphene_wait_ui \
  'text="(?:INSTALL|Install)"[^>]*class="android.widget.Button"[^>]*enabled="true"' \
  "live-package-queue-install-ready-$serial" 15
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$ui" 'text="Search results"[^>]*class="android.widget.Button"' "Search results"
archphene_wait_ui_exact_text \
  "3 official packages match dotnet" "live-package-queue-results-$serial" 15
archphene_regex_contains \
  "$ARCHPHENE_UI" \
  'text="(?:INSTALL|Install)"[^>]*class="android.widget.Button"[^>]*enabled="false"' ||
  archphene_die "Install remained enabled after restoring the broad search query"
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$ui" 'text="dotnet-sdk"[^>]*class="android.widget.TextView"' "dotnet-sdk"
archphene_wait_ui_text \
  "extra/dotnet-sdk 10.0.100.sdk100-1" \
  "live-package-queue-reresolution-$serial" 20
archphene_wait_ui \
  'text="(?:INSTALL|Install)"[^>]*class="android.widget.Button"[^>]*enabled="true"' \
  "live-package-queue-reinstall-ready-$serial" 15
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$ui" 'text="(?:INSTALL|Install)"[^>]*class="android.widget.Button"[^>]*enabled="true"' Install

archphene_wait_ui_exact_text \
  "Install · Queued · 0%" "live-package-queue-queued-$serial" 3
queued_ui="$ARCHPHENE_UI"
queued_count="$(
  python3 -c \
    'import sys; print(sys.stdin.read().count("text=\"Install · Queued · 0%\""))' \
    <<<"$queued_ui"
)"
((queued_count >= 2)) ||
  archphene_die "Queued was not rendered in both recent activity and the matching package row"
archphene_regex_contains \
  "$queued_ui" \
  'text="dotnet-sdk".*text="10\.0\.100\.sdk100-1".*text="The \.NET SDK".*text="Install · Queued · 0%"' ||
  archphene_die "dotnet-sdk search row did not carry its live Queued state"
archphene_regex_contains \
  "$queued_ui" \
  'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"[^>]*enabled="true"' ||
  archphene_die "Queued package operation was not cancellable"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-queued.png"

archphene_tap_ui_pattern \
  "$queued_ui" \
  'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"[^>]*enabled="true"' \
  Cancel
archphene_wait_ui_exact_text \
  "Install · Cancelled · 0%" "live-package-queue-cancelled-$serial" 15
archphene_wait_log 'Cancelled package operation for dotnet-sdk' 15 >/dev/null

cache_contents="$(
  archphene_adb_run exec-out run-as "$package" find \
    files/arch-root/var/cache/pacman/pkg -mindepth 1 -maxdepth 1 -type f |
    tr -d '\r'
)"
[[ -z "$cache_contents" ]] ||
  archphene_die "cancelled Queued operation created package cache files: $cache_contents"
installed_entry="$(
  archphene_adb_run exec-out run-as "$package" find \
    files/arch-root/var/lib/pacman/local -mindepth 1 -maxdepth 1 -type d \
    -name 'dotnet-sdk-[0-9]*' | tr -d '\r'
)"
[[ -z "$installed_entry" ]] ||
  archphene_die "cancelled Queued operation installed dotnet-sdk"
archphene_adb_run shell run-as "$package" test ! -e \
  files/arch-root/run/package-install-reasons-v1 ||
  archphene_die "cancelled Queued operation reached the package commit boundary"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-cancelled.png"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
ui="$(archphene_capture_ui "live-package-queue-restored-input-$serial")"
archphene_tap_ui_pattern \
  "$ui" 'text="Package name"[^>]*class="android.widget.EditText"' "Package name"
archphene_adb_run shell input text dotnet-sdk
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui_exact_text \
  "Install · Cancelled · 0%" "live-package-queue-restored-$serial" 20
archphene_wait_ui_exact_text \
  "Cancelled before package mutation" "live-package-queue-restored-message-$serial" 15
archphene_wait_ui \
  'text="(?:REVIEW|Review)"[^>]*class="android.widget.Button"[^>]*enabled="true"' \
  "live-package-queue-review-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-restored.png"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "live package queue emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene live package queue passed on $serial"
archphene_note "  Matching search row rendered durable Queued before worker advance"
archphene_note "  Cancelled before network/cache/mutation and restored exact terminal state"
archphene_note "  Full-device screenshots: $output_dir/$serial-{queued,cancelled,restored}.png"
