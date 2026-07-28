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
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-queries"
mkdir -p "$output_dir"
restore_notification=false

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$restore_notification" == true ]]; then
    archphene_adb_run shell pm revoke "$package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "terminal-query regression requires installed bash"
package_dump="$(archphene_adb_run shell dumpsys package "$package")"
if ! archphene_regex_contains "$package_dump" \
    'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  archphene_adb_run shell pm grant "$package" \
    android.permission.POST_NOTIFICATIONS
  restore_notification=true
fi

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "terminal-query-section-$serial"
archphene_wait_ui 'text="Start shell"' "terminal-query-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows".*text="Shared shell ready"' \
  "terminal-query-prompt-$serial" 20

archphene_adb_run shell am broadcast \
  -a org.archphene.app.debug.action.RUN_TERMINAL_QUERY \
  -n "$package/org.archphene.app.TerminalQueryTestReceiver" >/dev/null
archphene_wait_log 'Submitted terminal query probe' 15 \
  'ArchpheneTerminalQueryProbe:I *:S' >/dev/null
archphene_wait_ui 'terminal-query-pass' "terminal-query-result-$serial" 15
if archphene_regex_contains "$ARCHPHENE_UI" 'terminal-query-fail'; then
  archphene_die "terminal query probe received an incorrect reply"
fi

archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal-query regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal-query regression passed on $serial"
archphene_note "  DA, DSR/CPR, size, modes/margins, soft reset, and palette round trips passed"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
