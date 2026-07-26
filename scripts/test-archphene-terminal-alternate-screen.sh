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
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-alternate-screen"
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
  archphene_die "alternate-screen regression requires installed bash"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/tput ||
  archphene_die "alternate-screen regression requires installed tput"

enter_shell_line() {
  local line="$1" ui_name="$2"
  archphene_wait_ui 'text="Command, e.g. btop"' \
    "$ui_name-field" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Command, e.g. btop"' 'Linux shell input'
  archphene_adb_run shell input text "${line// /%s}" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_wait_ui 'text="Send"' "$ui_name-send" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Send"' 'send shell input'
}

capture_without() {
  local absent="$1" name="$2" ui
  ui="$(archphene_capture_ui "$name")"
  if archphene_regex_contains "$ui" "$absent"; then
    archphene_die "UI unexpectedly retained pattern: $absent"
  fi
  ARCHPHENE_UI="$ui"
}

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "alternate-terminal-$serial"
archphene_wait_ui 'text="Start shell"' "alternate-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui 'archphene:~\$' "alternate-prompt-$serial" 20

enter_shell_line "echo primary-screen-marker" "alternate-primary-$serial"
archphene_wait_ui 'primary-screen-marker' "alternate-primary-output-$serial" 15
enter_shell_line "tput smcup" "alternate-enter-$serial"
sleep 1
capture_without 'primary-screen-marker' "alternate-cleared-$serial"

enter_shell_line "echo alternate-screen-marker" "alternate-content-$serial"
archphene_wait_ui 'alternate-screen-marker' "alternate-content-output-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-alternate.png"

enter_shell_line "tput rmcup" "alternate-leave-$serial"
archphene_wait_ui 'primary-screen-marker' "alternate-restored-$serial" 15
capture_without 'alternate-screen-marker' "alternate-discarded-$serial"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-restored.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "alternate-screen regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene alternate-screen regression passed on $serial"
archphene_note "  Primary preservation, alternate clearing/content, and restoration passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-alternate.png"
archphene_note "                           $output_dir/$serial-restored.png"
