#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--skip-install]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-nested-command-script"
device_temporary="/data/local/tmp/archphene-nested-command-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-nested-command-test
nested_directory=files/arch-root/usr/lib/archphene-bridge-test
nested_executable="$nested_directory/nested-bash"
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/nested-command-regression"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f \
    "$installed_fixture" "$nested_executable" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rmdir "$nested_directory" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_temporary" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_require_file "$fixture"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "nested command regression requires installed bash"
if archphene_adb_run shell run-as "$package" test -e "$nested_directory"; then
  archphene_die "nested command fixture path already exists: $nested_directory"
fi

archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" mkdir "$nested_directory"
archphene_adb_run shell run-as "$package" cp \
  files/arch-root/usr/bin/bash "$nested_executable"
archphene_adb_run shell run-as "$package" chmod 755 "$nested_executable"
archphene_adb_run shell run-as "$package" cp \
  "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "archphene-nested-command-terminal-$serial"
archphene_wait_ui 'text="Linux command, for example btop"' \
  "archphene-nested-command-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Linux command, for example btop"' 'Linux command'
archphene_adb_run shell input text archphene-nested-command-test >/dev/null
archphene_wait_ui 'text="archphene-nested-command-test"' \
  "archphene-nested-command-entered-$serial" 10
entered_ui="$ARCHPHENE_UI"
if ! archphene_regex_contains "$entered_ui" 'text="Run"'; then
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
fi
archphene_wait_ui 'text="Run"' \
  "archphene-nested-command-keyboard-dismissed-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Run"' 'run nested Linux command'
archphene_wait_ui 'text="Exited 0[^"]*GNU bash, version' \
  "archphene-nested-command-complete-$serial" 45
archphene_wait_log 'Linux command archphene-nested-command-test exited 0' \
  15 >/dev/null
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "nested command regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene nested package executable regression passed on $serial"
archphene_note "  /usr/bin script launched a package-owned /usr/lib ELF"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
