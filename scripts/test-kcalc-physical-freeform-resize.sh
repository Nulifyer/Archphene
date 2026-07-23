#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--package PACKAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die '--serial is required'

archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
was_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  was_running=true
fi
tmp="$(archphene_mktemp_dir kcalc-freeform)"
restore() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$was_running" == true ]]; then
    archphene_adb_run shell am start -n "$activity" >/dev/null 2>&1 || true
  fi
}
trap restore EXIT

capture_state() {
  local name="$1" ui_path raw_path
  ui_path="$tmp/$name.xml"
  raw_path="$tmp/$name.raw"
  archphene_capture_ui "kcalc-freeform-$name" >"$ui_path"
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
if right - left < 200 or bottom - top < 200:
    raise SystemExit("KCalc viewport is too small to use")
print(left, top, right, bottom)
' "$raw_path" "$ui_path"
}

archphene_adb_run shell input keyevent WAKEUP
archphene_adb_run shell wm dismiss-keyguard >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W --windowingMode 5 -n "$activity" >/dev/null
sleep 8

android_pid="$(archphene_android_pid "$package")"
linux_pid="$(archphene_linux_loader_pid "$android_pid")"
activities="$(archphene_adb_run shell dumpsys activity activities)"
task="$(python3 -c '
import re, sys
package = sys.argv[1]
match = re.search(
    r"Task\{[^\r\n]*#(\d+)[^\r\n]*" + re.escape(package)
    + r"[^\r\n]*mode=freeform", sys.stdin.read())
print(match.group(1) if match else "")
' "$package" <<<"$activities")"
[[ -n "$android_pid" && -n "$linux_pid" && -n "$task" ]] \
  || archphene_die "could not identify freeform KCalc app=$android_pid Linux=$linux_pid task=$task"

archphene_adb_run shell am task resize "$task" 80 180 920 1500
sleep 4
read -r small_left small_top small_right small_bottom \
  <<<"$(capture_state small)"
small_android="$(archphene_android_pid "$package")"
small_linux="$(archphene_linux_loader_pid "$small_android")"

archphene_adb_run shell am task resize "$task" 0 0 1026 2200
sleep 4
read -r large_left large_top large_right large_bottom \
  <<<"$(capture_state restored)"
large_android="$(archphene_android_pid "$package")"
large_linux="$(archphene_linux_loader_pid "$large_android")"

[[ "$android_pid" == "$small_android" && "$android_pid" == "$large_android" \
  && "$linux_pid" == "$small_linux" && "$linux_pid" == "$large_linux" ]] \
  || archphene_die "freeform resize restarted KCalc: Android $android_pid/$small_android/$large_android, Linux $linux_pid/$small_linux/$large_linux"
small_area=$(((small_right - small_left) * (small_bottom - small_top)))
large_area=$(((large_right - large_left) * (large_bottom - large_top)))
((large_area > small_area)) \
  || archphene_die "KCalc viewport did not grow: $small_area -> $large_area"

log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
! archphene_regex_contains "$log" \
  'FATAL EXCEPTION|Runtime GUI exit=(?!0)|protocol error|InvalidGrab|UnconfiguredBuffer|native dispatch failed' \
  || archphene_die 'freeform resize produced a runtime or compositor failure'

restore
trap - EXIT
archphene_note "KCalc physical freeform resize passed on $serial without clearing app data: stable Android PID $android_pid and Linux PID $linux_pid; viewport $((small_right - small_left))x$((small_bottom - small_top)) -> $((large_right - large_left))x$((large_bottom - large_top)); prior running state restored."
