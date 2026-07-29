#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
clean_data=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --clean-data) clean_data=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --clean-data"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$clean_data" == true ]] ||
  archphene_die "--clean-data is required because this gate clears Archphene app data"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.PackageJobTestReceiver"
action=org.archphene.app.debug.action.SEED_PACKAGE_JOB
output_dir="$ARCHPHENE_ROOT/tooling/build/package-background"
mkdir -p "$output_dir"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
token="background-$serial_slug"

filter_package_name() {
  local name="$1" fixture="$2"
  archphene_wait_ui 'class="android.widget.EditText"' "$fixture-field" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'class="android.widget.EditText"' 'package name'
  archphene_adb_run shell input keycombination 113 29 >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
  archphene_adb_run shell input text "$name" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
}

cleanup() {
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
  --es token "$token" \
  --es package background-cache \
  --es state failed \
  --es operation install \
  --es failure storage \
  --ez cache-fixture true \
  --ei cache-entries 4095 \
  --ei cache-hold-ms 5000 >/dev/null
archphene_wait_log \
  "Seeded package job state=failed token=$token" 90 \
  "ArchphenePackageJobProbe:V *:S" >/dev/null

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 30 >/dev/null
archphene_skip_storage_onboarding "package-background-onboarding-$serial"
filter_package_name background-cache "package-background-$serial"
archphene_wait_ui_exact_text \
  "background-cache" "package-background-name-$serial" 20
archphene_wait_ui_exact_text \
  "Clear cache" "package-background-action-$serial" 20

archphene_adb_run logcat -c
archphene_tap_text "$ARCHPHENE_UI" "Clear cache"
archphene_adb_run shell input keyevent KEYCODE_APP_SWITCH
archphene_wait_ui \
  'content-desc="Archphene"' "package-background-recents-$serial" 4
read -r swipe_x swipe_start_y <<<"$(
  archphene_ui_node_center \
    "$ARCHPHENE_UI" 'content-desc="Archphene"' "Archphene recent task"
)"
if [[ "$ARCHPHENE_UI" == *'package="com.sec.android.app.launcher"'* ]]; then
  archphene_adb_run shell input swipe \
    "$swipe_x" "$swipe_start_y" "$swipe_x" 100 1000
else
  archphene_adb_run shell input swipe \
    "$swipe_x" "$swipe_start_y" "$swipe_x" 0 300
fi
archphene_wait_log \
  'Task removed; keeping active runtime work' 20 >/dev/null
archphene_wait_log \
  'Cleared 4193792 unrelated package-cache bytes while retaining 0 closure package\(s\)' \
  60 >/dev/null
archphene_wait_log \
  'Shared Rust runtime stopped' 30 >/dev/null

runtime_log="$(
  archphene_adb_run logcat -d -v brief -s ArchpheneRuntime:V '*:S' |
    tr -d '\r'
)"
foreground_line="$(grep -nF 'Foreground runtime notification active' <<<"$runtime_log" |
  head -n1 | cut -d: -f1)"
keep_line="$(grep -nF 'Task removed; keeping active runtime work' <<<"$runtime_log" |
  head -n1 | cut -d: -f1)"
clear_line="$(
  grep -nF \
    'Cleared 4193792 unrelated package-cache bytes while retaining 0 closure package(s)' \
    <<<"$runtime_log" |
    head -n1 |
    cut -d: -f1
)"
stop_line="$(grep -nF 'Shared Rust runtime stopped' <<<"$runtime_log" |
  tail -n1 | cut -d: -f1)"
[[ -n "$foreground_line" && -n "$keep_line" && -n "$clear_line" && -n "$stop_line" ]] ||
  archphene_die "background package lifecycle evidence is incomplete"
((foreground_line < keep_line && keep_line < clear_line && clear_line < stop_line)) ||
  archphene_die "runtime stopped before background package work completed"

remaining="$(
  archphene_adb_run shell run-as "$package" \
    ls files/arch-root/var/cache/pacman/pkg |
    tr -d '\r'
)"
[[ -z "$remaining" ]] ||
  archphene_die "background package cleanup left entries behind: $remaining"

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 30 >/dev/null
filter_package_name background-cache "package-background-restored-$serial"
archphene_wait_ui_exact_text \
  "Freed 4 MiB of unrelated downloads and retained this package's verified closure. Review before retrying." \
  "package-background-restored-$serial" 30
archphene_wait_ui_exact_text \
  "Review" "package-background-review-$serial" 20
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-restored.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
next_token="$token-next"
archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "$next_token" \
  --es package background-cache \
  --es state failed \
  --es operation install \
  --es failure storage \
  --ez cache-fixture true >/dev/null
archphene_wait_log \
  "Seeded package job state=failed token=$next_token" 30 \
  "ArchphenePackageJobProbe:V *:S" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
filter_package_name background-cache "package-background-next-$serial"
archphene_wait_ui_exact_text \
  "Clear cache" "package-background-new-job-$serial" 30
archphene_tap_text "$ARCHPHENE_UI" "Clear cache"
archphene_wait_ui_exact_text \
  "Freed 3.5 KiB of unrelated downloads and retained this package's verified closure. Review before retrying." \
  "package-background-new-job-cleaned-$serial" 30
remaining="$(
  archphene_adb_run shell run-as "$package" \
    ls files/arch-root/var/cache/pacman/pkg |
    tr -d '\r'
)"
[[ -z "$remaining" ]] ||
  archphene_die "second package cleanup left entries behind: $remaining"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Background package work emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene background package work passed on $serial"
archphene_note "  Task dismissal retained cleanup, stopped only after completion, restored its result, and isolated a new identical job"
archphene_note "  Full-device screenshot: $output_dir/$serial-restored.png"
