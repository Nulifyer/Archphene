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
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-input-modes"
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-input-mode-test"
device_temporary="/data/local/tmp/archphene-terminal-input-mode-test-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-input-mode-test
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f "$installed_fixture" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_temporary" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "input-mode regression requires installed bash"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/tput ||
  archphene_die "input-mode regression requires installed tput"
archphene_require_file "$fixture"
archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" \
  cp "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"

send_hardware_up() {
  local ui_name="$1"
  sleep 0.5
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$ui_name-terminal" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface'
  archphene_adb_run shell input keyevent KEYCODE_DPAD_UP >/dev/null
  sleep 0.5
}

send_hardware_combination() {
  local ui_name="$1"
  shift
  sleep 0.5
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$ui_name-terminal" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface'
  archphene_adb_run shell input keycombination "$@" >/dev/null
  sleep 0.5
}

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "input-mode-terminal-$serial"
archphene_wait_ui 'text="Start shell"' "input-mode-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows".*text="Shared shell ready"' \
  "input-mode-prompt-$serial" 20

archphene_wait_ui 'text="Command, e.g. btop"' \
  "input-mode-fixture-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Command, e.g. btop"' 'Linux shell input'
archphene_adb_run shell input text \
  'bash%s/usr/bin/archphene-terminal-input-mode-test' >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Send"' "input-mode-fixture-send-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Send"' 'send shell input'

archphene_wait_ui 'NORMAL-WAIT' "input-mode-normal-wait-$serial" 15
send_hardware_up "input-mode-normal-up-$serial"
archphene_wait_ui 'NORMAL=1B5B41' "input-mode-normal-result-$serial" 15

archphene_wait_ui 'APPLICATION-WAIT' "input-mode-application-wait-$serial" 15
send_hardware_up "input-mode-application-up-$serial"
archphene_wait_ui 'APPLICATION=1B4F41' \
  "input-mode-application-result-$serial" 15

archphene_wait_ui 'CONTROL-UP-WAIT' "input-mode-control-wait-$serial" 15
send_hardware_combination \
  "input-mode-control-up-$serial" KEYCODE_CTRL_LEFT KEYCODE_DPAD_UP
archphene_wait_ui 'CONTROL-UP=1B5B313B3541' \
  "input-mode-control-result-$serial" 15

archphene_wait_ui 'SHIFT-F5-WAIT' "input-mode-shift-function-wait-$serial" 15
send_hardware_combination \
  "input-mode-shift-function-$serial" KEYCODE_SHIFT_LEFT KEYCODE_F5
archphene_wait_ui 'SHIFT-F5=1B5B31353B327E' \
  "input-mode-shift-function-result-$serial" 15

archphene_wait_ui 'terminal-input-mode-ready' \
  "input-mode-ready-$serial" 15
sleep 0.5
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "input-mode regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal input-mode regression passed on $serial"
archphene_note "  Normal CSI and application SS3 hardware cursor sequences passed"
archphene_note "  Modified xterm cursor and function-key sequences passed"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
