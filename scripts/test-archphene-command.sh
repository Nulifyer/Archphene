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
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/command-regression"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/btop ||
  archphene_die "command regression requires installed btop"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "archphene-command-terminal-$serial"
archphene_wait_ui 'text="Command, e.g. btop"' \
  "archphene-command-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Command, e.g. btop"' 'Linux command'
archphene_adb_run shell input text 'btop%s--version' >/dev/null
archphene_wait_ui 'text="btop --version"' "archphene-command-entered-$serial" 10
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="RUN"' "archphene-command-keyboard-dismissed-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="RUN"' 'run Linux command'
archphene_wait_ui 'text="Exited 0[^"]*btop version' \
  "archphene-command-complete-$serial" 45
archphene_wait_log 'Linux command btop exited 0' 15 >/dev/null
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "command regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene shared command regression passed on $serial"
archphene_note "  Installed btop executed through the bounded shared environment"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
