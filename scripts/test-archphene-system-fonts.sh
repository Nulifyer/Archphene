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
root=files/arch-root
output_dir="$ARCHPHENE_ROOT/tooling/build/system-fonts"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
for command in fc-cache fc-match; do
  archphene_adb_run shell run-as "$package" test -x "$root/usr/bin/$command" ||
    archphene_die "system-font gate requires installed $command"
done

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
if archphene_wait_ui_optional \
    'text="Don’t allow"' "system-font-notification-$serial" 3; then
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="Don’t allow"' 'deny optional debug notification permission'
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
fi
archphene_wait_ui 'text="Archphene is ready"' "system-font-ready-$serial" 30
archphene_open_manager_section Terminal "system-font-terminal-$serial"

archphene_run_debug_linux_command "$package" "fc-cache -v /system/fonts"
archphene_wait_ui \
  'text="Exited 0[^"]*/system/fonts[^"]*[1-9][0-9]* fonts' \
  "system-font-cache-$serial" 30

archphene_run_debug_linux_command "$package" "fc-match DroidSansMono"
archphene_wait_ui \
  'Exited 0[^>]*DroidSansMono\.ttf[^>]*Droid Sans Mono' \
  "system-font-match-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "system-font gate emitted a fatal error: $fatal_log"

archphene_note "Archphene Android system-font gate passed on $serial"
archphene_note "  Fontconfig enumerated /system/fonts and matched Droid Sans Mono"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
