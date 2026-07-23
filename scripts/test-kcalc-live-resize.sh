#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die '--serial is required'

archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
old_accelerometer="$(archphene_adb_run shell settings get system \
  accelerometer_rotation | tr -d '\r')"
old_rotation="$(archphene_adb_run shell settings get system \
  user_rotation | tr -d '\r')"
tmp="$(archphene_mktemp_dir kcalc-rotation)"
restore() {
  archphene_adb_run shell settings put system user_rotation \
    "$old_rotation" >/dev/null 2>&1 || true
  archphene_adb_run shell settings put system accelerometer_rotation \
    "$old_accelerometer" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am start -n "$activity" >/dev/null 2>&1 || true
}
trap restore EXIT

capture_state() {
  local name="$1" ui_path raw_path
  ui_path="$tmp/$name.xml"
  raw_path="$tmp/$name.raw"
  archphene_capture_ui "kcalc-rotation-$name" >"$ui_path"
  archphene_adb_run exec-out screencap >"$raw_path"
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" inspect \
    "$raw_path" >/dev/null
  python3 -c '
import re, struct, sys, xml.etree.ElementTree as ET
raw_path, ui_path = sys.argv[1:]
with open(raw_path, "rb") as stream:
    width, height, _ = struct.unpack("<III", stream.read(12))
root = ET.parse(ui_path).getroot()
node = next((item for item in root.iter("node")
             if item.attrib.get("class") == "android.widget.ImageView"), None)
if node is None:
    raise SystemExit("KCalc viewport is missing")
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                     node.attrib.get("bounds", ""))
if match is None:
    raise SystemExit("KCalc viewport bounds are invalid")
left, top, right, bottom = map(int, match.groups())
if left < 0 or top < 0 or right > width or bottom > height:
    raise SystemExit("KCalc viewport is outside the display")
print(width, height, right - left, bottom - top, top)
' "$raw_path" "$ui_path"
}

tap_accessible_control() {
  local ui_path="$1" text="$2" center x y
  center="$(python3 -c '
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
text = sys.argv[2]
node = next((item for item in root.iter("node")
             if item.attrib.get("text") == text
             and item.attrib.get("clickable") == "true"), None)
if node is None:
    raise SystemExit(1)
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                     node.attrib.get("bounds", ""))
if match is None:
    raise SystemExit(1)
left, top, right, bottom = map(int, match.groups())
print((left + right) // 2, (top + bottom) // 2)
' "$ui_path" "$text")" || return 1
  read -r x y <<<"$center"
  archphene_adb_run shell input tap "$x" "$y" >/dev/null
  sleep .25
}

display_text() {
  python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
values = [item.attrib.get("text", "") for item in root.iter("node")
          if item.attrib.get("class") == "android.widget.TextView"
          and item.attrib.get("text") not in ("", "NORM")]
print(values[0] if values else "")
' "$1"
}

archphene_adb_run shell input keyevent WAKEUP
archphene_adb_run shell wm dismiss-keyguard >/dev/null 2>&1 || true
archphene_adb_run shell settings put system accelerometer_rotation 0
archphene_adb_run shell settings put system user_rotation 0
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
sleep 8

portrait_pid="$(archphene_android_pid "$package")"
portrait_child="$(archphene_linux_loader_pid "$portrait_pid")"
[[ -n "$portrait_pid" && -n "$portrait_child" ]] \
  || archphene_die 'KCalc process tree did not become ready'
read -r portrait_width portrait_height portrait_view_width \
  portrait_view_height portrait_top <<<"$(capture_state portrait-before)"
((portrait_width < portrait_height && portrait_view_width < portrait_view_height)) \
  || archphene_die "invalid portrait geometry: display ${portrait_width}x$portrait_height, viewport ${portrait_view_width}x$portrait_view_height"

archphene_adb_run shell settings put system user_rotation 1
sleep 7
landscape_pid="$(archphene_android_pid "$package")"
landscape_child="$(archphene_linux_loader_pid "$landscape_pid")"
read -r landscape_width landscape_height landscape_view_width \
  landscape_view_height landscape_top <<<"$(capture_state landscape-before)"
((landscape_width > landscape_height \
  && landscape_view_width > landscape_view_height)) \
  || archphene_die "invalid landscape geometry: display ${landscape_width}x$landscape_height, viewport ${landscape_view_width}x$landscape_view_height"

if tap_accessible_control "$tmp/landscape-before.xml" One; then
  for control in Add Two Equals; do
    ui_path="$tmp/landscape-$control.xml"
    archphene_capture_ui "kcalc-rotation-$control" >"$ui_path"
    tap_accessible_control "$ui_path" "$control" \
      || archphene_die "accessible KCalc control $control is missing"
  done
  accessible=true
else
  accessible=false
  for key_code in 8 81 9 70; do
    archphene_adb_run shell input keyevent "$key_code"
    sleep .25
  done
fi
sleep 2
capture_state landscape-after >/dev/null
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$tmp/landscape-before.raw" "$tmp/landscape-after.raw" \
  --left-percent 0 --right-percent 100 \
  --top-percent 8 --bottom-percent 45 \
  --minimum-difference .01 --minimum-changed-ratio .00001
if [[ "$accessible" == true ]]; then
  result="$(display_text "$tmp/landscape-after.xml")"
  [[ "$result" == 3 ]] \
    || archphene_die "landscape KCalc calculation produced '$result', expected 3"
fi

archphene_adb_run shell settings put system user_rotation 0
sleep 7
restored_pid="$(archphene_android_pid "$package")"
restored_child="$(archphene_linux_loader_pid "$restored_pid")"
read -r restored_width restored_height restored_view_width \
  restored_view_height restored_top <<<"$(capture_state portrait-restored)"
[[ "$portrait_pid" == "$landscape_pid" && "$portrait_pid" == "$restored_pid" \
  && "$portrait_child" == "$landscape_child" \
  && "$portrait_child" == "$restored_child" ]] \
  || archphene_die "rotation restarted KCalc: Android $portrait_pid/$landscape_pid/$restored_pid, Linux $portrait_child/$landscape_child/$restored_child"
((restored_width < restored_height \
  && restored_view_width < restored_view_height)) \
  || archphene_die 'KCalc did not restore portrait geometry'

log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
! archphene_regex_contains "$log" \
  'FATAL EXCEPTION|protocol error|InvalidGrab|UnconfiguredBuffer|native dispatch failed' \
  || archphene_die 'rotation produced a runtime or compositor failure'

restore
trap - EXIT
archphene_note "KCalc live rotation passed on $serial: stable Android PID $portrait_pid and Linux PID $portrait_child; portrait ${portrait_view_width}x$portrait_view_height -> landscape ${landscape_view_width}x$landscape_view_height -> portrait ${restored_view_width}x$restored_view_height with calculation input."
