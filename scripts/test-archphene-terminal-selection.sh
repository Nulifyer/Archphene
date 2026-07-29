#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--install-apk]"
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
  archphene_enter_terminal_line "$1" "$2"
}

enter_terminal_line() {
  local line="$1" ui_name="$2"
  archphene_wait_ui \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$ui_name-terminal" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface'
  archphene_adb_run shell input text "${line// /%s}" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
}

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell cmd statusbar collapse >/dev/null 2>&1 || true
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "terminal-selection-section-$serial"
archphene_wait_ui 'text="Start shell"' "terminal-selection-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui '(?:archphene:~|sh-[0-9.]+)\$' \
  "terminal-selection-prompt-$serial" 20

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
print(
    round(x1 + 8 + 10 * cell_width),
    round(y1 + 8 + (rows - 0.5) * cell_height),
)
' <<<"$ARCHPHENE_UI"
)"
archphene_adb_run shell input motionevent DOWN "$selection_x" "$selection_y" >/dev/null
sleep 0.8
archphene_adb_run shell input motionevent UP "$selection_x" "$selection_y" >/dev/null
archphene_wait_ui 'text="Copy"' "terminal-selection-menu-$serial" 10
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-menu.png"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'selected="true"' "terminal-selection-selected-$serial" 10

read -r terminal_x terminal_top terminal_bottom <<<"$(
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
print((x1 + x2) // 2, y1, y2)
' <<<"$ARCHPHENE_UI"
)"
swipe_start=$((terminal_bottom - 180))
swipe_end=$((terminal_top + 180))
for _ in 1 2; do
  archphene_adb_run shell input touchscreen swipe \
    "$terminal_x" "$swipe_start" "$terminal_x" "$swipe_end" 350 >/dev/null
done
archphene_wait_ui 'SELECTION-HISTORY-[0-5][0-9]' \
  "terminal-selection-history-$serial" 15
archphene_regex_contains "$ARCHPHENE_UI" 'COPY-SELECTION-MARKER' &&
  archphene_die "selected live row did not leave the viewport"
archphene_regex_contains "$ARCHPHENE_UI" 'selected="true"' ||
  archphene_die "selection did not remain anchored while viewing history"

for _ in 1 2 3 4 5 6 7 8; do
  archphene_adb_run shell input touchscreen swipe \
    "$terminal_x" "$swipe_end" "$terminal_x" "$swipe_start" 350 >/dev/null
done
archphene_wait_ui 'COPY-SELECTION-MARKER' \
  "terminal-selection-return-$serial" 15
archphene_regex_contains "$ARCHPHENE_UI" 'selected="true"' ||
  archphene_die "selection was lost after returning to its live row"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-handles.png"

archphene_require_command magick
read -r start_x end_x handle_y <<<"$(
  magick "$output_dir/$serial-handles.png" \
    -alpha off -fuzz 2% -fill white -opaque '#7dd3fc' \
    -fill black +opaque white \
    -define connected-components:verbose=true \
    -connected-components 8 null: 2>&1 |
    python3 -c '
import re, sys
handles = []
for line in sys.stdin:
    match = re.search(
        r": ([0-9]+)x([0-9]+)\+([0-9]+)\+([0-9]+) "
        r"([0-9.]+),([0-9.]+) ([0-9.e+]+) srgb\(255,255,255\)",
        line,
    )
    if match is None:
        continue
    width, height, left, top = map(int, match.groups()[:4])
    center_x, center_y, area = map(float, match.groups()[4:])
    if 20 <= width <= 100 and 20 <= height <= 100 and area >= 500:
        handles.append((center_x, center_y))
if len(handles) != 2:
    raise SystemExit(f"expected two rendered selection handles, found {handles!r}")
handles.sort()
print(round(handles[0][0]), round(handles[1][0]), round(handles[1][1]))
'
)"

auto_scroll_y=$((terminal_top - 30))
archphene_adb_run shell input motionevent DOWN "$start_x" "$handle_y" >/dev/null
archphene_adb_run shell input motionevent MOVE "$start_x" "$auto_scroll_y" >/dev/null
sleep 0.8
archphene_adb_run shell input motionevent UP "$start_x" "$auto_scroll_y" >/dev/null
archphene_wait_ui 'text="Copy"' "terminal-selection-autoscroll-menu-$serial" 10
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-autoscroll.png"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null

terminal_middle=$(((terminal_top + terminal_bottom) / 2))
archphene_adb_run shell input tap "$terminal_x" "$terminal_middle" >/dev/null
sleep 0.5
archphene_adb_run shell input tap "$terminal_x" "$terminal_middle" >/dev/null
sleep 0.5
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
for _ in 1 2 3 4 5 6 7 8; do
  archphene_adb_run shell input touchscreen swipe \
    "$terminal_x" "$swipe_end" "$terminal_x" "$swipe_start" 350 >/dev/null
done
archphene_wait_ui 'COPY-SELECTION-MARKER' \
  "terminal-selection-autoscroll-return-$serial" 15
archphene_adb_run shell input motionevent DOWN "$selection_x" "$selection_y" >/dev/null
sleep 0.8
archphene_adb_run shell input motionevent UP "$selection_x" "$selection_y" >/dev/null
archphene_wait_ui 'text="Copy"' "terminal-selection-reselected-menu-$serial" 10
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'selected="true"' "terminal-selection-reselected-$serial" 10
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-handles.png"
read -r _ end_x handle_y <<<"$(
  magick "$output_dir/$serial-handles.png" \
    -alpha off -fuzz 2% -fill white -opaque '#7dd3fc' \
    -fill black +opaque white \
    -define connected-components:verbose=true \
    -connected-components 8 null: 2>&1 |
    python3 -c '
import re, sys
handles = []
for line in sys.stdin:
    match = re.search(
        r": ([0-9]+)x([0-9]+)\+([0-9]+)\+([0-9]+) "
        r"([0-9.]+),([0-9.]+) ([0-9.e+]+) srgb\(255,255,255\)",
        line,
    )
    if match is None:
        continue
    width, height = map(int, match.groups()[:2])
    center_x, center_y, area = map(float, match.groups()[4:])
    if 20 <= width <= 100 and 20 <= height <= 100 and area >= 500:
        handles.append((center_x, center_y))
if len(handles) != 2:
    raise SystemExit(f"expected two rendered selection handles, found {handles!r}")
handles.sort()
print(round(handles[0][0]), round(handles[1][0]), round(handles[1][1]))
'
)"
drag_x=$((end_x + 50))
archphene_adb_run shell input motionevent DOWN "$end_x" "$handle_y" >/dev/null
archphene_adb_run shell input motionevent MOVE "$drag_x" "$handle_y" >/dev/null
archphene_adb_run shell input motionevent UP "$drag_x" "$handle_y" >/dev/null
archphene_wait_ui 'text="Copy"' "terminal-selection-handle-menu-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Copy"' 'copy action'

archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  "terminal-selection-refocus-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell input text x >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui '(?:archphene:~|sh-[0-9.]+)\$' \
  "terminal-selection-exit-$serial" 15

enter_terminal_line "read -r REPLY" "terminal-selection-read-$serial"
archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  "terminal-selection-paste-focus-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell input keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V >/dev/null
archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
enter_terminal_line "declare -p REPLY" "terminal-selection-declare-$serial"
archphene_wait_ui_unwrapped 'REPLY="COPY-SELECTION-MARKER"' \
  "terminal-selection-result-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-result.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal selection regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal selection/copy regression passed on $serial"
archphene_note "  Long-press, history-stable range, autoscroll, handle drag, and exact copy passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{menu,handles,autoscroll,result}.png"
