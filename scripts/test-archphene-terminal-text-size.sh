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
preferences=shared_prefs/terminal_display.xml
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-text-size"
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f "$preferences" \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell run-as "$package" rm -f "$preferences"

start_shell() {
  local name="$1"
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_open_manager_section Terminal "$name-terminal"
  archphene_wait_ui 'text="START SHELL"' "$name-start" 20
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="START SHELL"' 'start shell'
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$name-terminal" 20
}

open_text_menu() {
  local name="$1" center x y
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$name-terminal" 15
  center="$(archphene_ui_node_center "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface')"
  read -r x y <<<"$center"
  archphene_adb_run shell input motionevent DOWN "$x" "$y" >/dev/null
  sleep 0.8
  archphene_adb_run shell input motionevent UP "$x" "$y" >/dev/null
}

focus_terminal() {
  local name="$1"
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$name-terminal" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface'
}

wait_preference() {
  local expected="$1" deadline value
  deadline=$((SECONDS + 10))
  while ((SECONDS < deadline)); do
    value="$(archphene_adb_run shell run-as "$package" cat "$preferences" \
      2>/dev/null || true)"
    [[ "$value" == *"value=\"$expected\""* ]] && return 0
    sleep 0.3
  done
  archphene_die "terminal text-size preference did not persist value $expected"
}

terminal_columns() {
  python3 -c '
import re, sys
match = re.search(r"content-desc=\"Linux terminal, (\d+) columns by \d+ rows\"", sys.stdin.read())
if match is None:
    raise SystemExit("terminal dimensions missing from UI")
print(match.group(1))
'
}

wait_automatic_preference() {
  local deadline value
  deadline=$((SECONDS + 10))
  while ((SECONDS < deadline)); do
    value="$(archphene_adb_run shell run-as "$package" cat "$preferences" \
      2>/dev/null || true)"
    [[ "$value" != *'name="text_sp"'* ]] && return 0
    sleep 0.3
  done
  archphene_die "terminal text-size preference did not reset to automatic"
}

archphene_adb_run logcat -c
start_shell "terminal-text-auto-$serial"
auto_columns="$(terminal_columns <<<"$ARCHPHENE_UI")"
open_text_menu "terminal-text-auto-$serial"
archphene_wait_ui 'text="Text size: Auto \(16 sp\)"' \
  "terminal-text-auto-menu-$serial" 10
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-auto.png"
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Text larger"' 'larger terminal text'

focus_terminal "terminal-text-keyboard-$serial"
for _ in 1 2 3; do
  archphene_adb_run shell input keycombination \
    KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_EQUALS >/dev/null
done
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
open_text_menu "terminal-text-twenty-$serial"
twenty_columns="$(terminal_columns <<<"$ARCHPHENE_UI")"
archphene_wait_ui 'text="Text size: 20 sp \(reset to Auto\)"' \
  "terminal-text-twenty-menu-$serial" 10
((twenty_columns < auto_columns)) ||
  archphene_die "20sp terminal did not reduce columns from Auto"
wait_preference 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-20sp.png"

sleep 1
start_shell "terminal-text-persisted-$serial"
open_text_menu "terminal-text-persisted-$serial"
persisted_columns="$(terminal_columns <<<"$ARCHPHENE_UI")"
archphene_wait_ui 'text="Text size: 20 sp \(reset to Auto\)"' \
  "terminal-text-persisted-menu-$serial" 10
((persisted_columns == twenty_columns)) ||
  archphene_die "persisted 20sp terminal dimensions changed after restart"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-persisted.png"
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Text size: 20 sp \(reset to Auto\)"' \
  'reset terminal text to automatic'

focus_terminal "terminal-text-smaller-$serial"
archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_MINUS >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
open_text_menu "terminal-text-smaller-$serial"
archphene_wait_ui 'text="Text size: 15 sp \(reset to Auto\)"' \
  "terminal-text-smaller-menu-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Text size: 15 sp \(reset to Auto\)"' \
  'reset terminal text to automatic'
open_text_menu "terminal-text-reset-$serial"
reset_columns="$(terminal_columns <<<"$ARCHPHENE_UI")"
archphene_wait_ui 'text="Text size: Auto \(16 sp\)"' \
  "terminal-text-reset-menu-$serial" 10
((reset_columns == auto_columns)) ||
  archphene_die "automatic terminal dimensions were not restored"
wait_automatic_preference
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-reset.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal text-size regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal text-size regression passed on $serial"
archphene_note "  Auto, touch controls, keyboard zoom, persistence, and reset passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-auto.png"
archphene_note "                           $output_dir/$serial-20sp.png"
archphene_note "                           $output_dir/$serial-persisted.png"
archphene_note "                           $output_dir/$serial-reset.png"
