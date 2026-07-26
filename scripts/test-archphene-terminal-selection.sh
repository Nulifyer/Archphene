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
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-selection-test"
device_temporary="/data/local/tmp/archphene-terminal-selection-test-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-selection-test
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-selection"
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
archphene_require_file "$fixture"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "terminal selection regression requires installed bash"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/tput ||
  archphene_die "terminal selection regression requires installed tput"

archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"

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

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "terminal-selection-section-$serial"
archphene_wait_ui 'text="Start shell"' "terminal-selection-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui 'archphene:~\$' "terminal-selection-prompt-$serial" 20

enter_shell_line \
  "bash /usr/bin/archphene-terminal-selection-test" \
  "terminal-selection-fixture-$serial"
archphene_wait_ui 'COPY-SELECTION-MARKER' "terminal-selection-ready-$serial" 20

read -r selection_x selection_y <<<"$(
  python3 -c '
import re, sys
match = re.search(
    r"content-desc=\"Linux terminal, ([0-9]+) columns by ([0-9]+) rows\""
    r"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",
    sys.stdin.read(),
)
if match is None:
    raise SystemExit("Could not find terminal geometry")
columns, rows, x1, y1, x2, y2 = map(int, match.groups())
cell_width = (x2 - x1 - 16) / columns
cell_height = (y2 - y1 - 16) / rows
print(round(x1 + 8 + 10 * cell_width), round(y1 + 8 + cell_height / 2))
' <<<"$ARCHPHENE_UI"
)"
archphene_adb_run shell input motionevent DOWN "$selection_x" "$selection_y" >/dev/null
sleep 0.8
archphene_adb_run shell input motionevent UP "$selection_x" "$selection_y" >/dev/null
archphene_wait_ui 'text="Copy"' "terminal-selection-menu-$serial" 10
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-menu.png"
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Copy"' 'copy action'

archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  "terminal-selection-refocus-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell input text x >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'archphene:~\$' "terminal-selection-exit-$serial" 15

enter_shell_line "read -r REPLY" "terminal-selection-read-$serial"
archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  "terminal-selection-paste-focus-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell input keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V >/dev/null
archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
enter_shell_line "declare -p REPLY" "terminal-selection-declare-$serial"
archphene_wait_ui_unwrapped 'REPLY="COPY-SELECTION-MARKER"' \
  "terminal-selection-result-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-result.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal selection regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal selection/copy regression passed on $serial"
archphene_note "  Long-press word selection, Copy, and exact clipboard paste passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{menu,result}.png"
