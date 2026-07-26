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
mkdir -p "$output_dir"

cleanup() {
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

enter_shell_line() {
  local line="$1" ui_name="$2"
  archphene_wait_ui 'text="Linux command, for example btop"' \
    "$ui_name-field" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Linux command, for example btop"' 'Linux shell input'
  archphene_adb_run shell input text "${line// /%s}" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_wait_ui 'text="Send"' "$ui_name-send" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Send"' 'send shell input'
}

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

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "input-mode-terminal-$serial"
archphene_wait_ui 'text="Start shell"' "input-mode-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui 'archphene:~\$' "input-mode-prompt-$serial" 20

enter_shell_line "read -n 3" "input-mode-normal-read-$serial"
send_hardware_up "input-mode-normal-up-$serial"
enter_shell_line "declare -p REPLY" "input-mode-normal-declare-$serial"
archphene_wait_ui 'REPLY=\$.*E\[A' "input-mode-normal-result-$serial" 15

enter_shell_line "tput smkx" "input-mode-enable-$serial"
enter_shell_line "read -n 3" "input-mode-application-read-$serial"
send_hardware_up "input-mode-application-up-$serial"
enter_shell_line "declare -p REPLY" "input-mode-application-declare-$serial"
archphene_wait_ui 'REPLY=\$.*EOA' "input-mode-application-result-$serial" 15
enter_shell_line "tput rmkx" "input-mode-disable-$serial"

sleep 0.5
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "input-mode regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal input-mode regression passed on $serial"
archphene_note "  Normal CSI and application SS3 hardware cursor sequences passed"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
