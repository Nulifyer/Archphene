#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
tmp="$(archphene_mktemp_dir kcalc-calculation)"
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT
kcalc_linux_pid() {
  archphene_adb_run shell ps -A -o PID,PPID,ARGS |
    awk '/--argv0 kcalc / { print $1; exit }'
}
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
sleep 8
before_pid="$(archphene_android_pid "$package")"
before_child="$(kcalc_linux_pid)"
[[ -n "$before_pid" && -n "$before_child" ]] \
  || archphene_die 'KCalc Linux process tree is missing'
tap_control() {
  local text="$1" ui center x y
  ui="$(archphene_capture_ui "kcalc-calculation-${text// /-}")"
  center="$(python3 -c '
import re, sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
node = next((item for item in root.iter("node")
             if item.attrib.get("text") == sys.argv[1]
             and item.attrib.get("clickable") == "true"), None)
if node is None:
    raise SystemExit(f"accessible KCalc control {sys.argv[1]} is missing")
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                     node.attrib.get("bounds", ""))
if match is None:
    raise SystemExit(f"accessible KCalc control {sys.argv[1]} has invalid bounds")
left, top, right, bottom = map(int, match.groups())
print((left + right) // 2, (top + bottom) // 2)
' "$text" <<<"$ui")"
  read -r x y <<<"$center"
  archphene_adb_run shell input tap "$x" "$y" >/dev/null
  sleep .3
}
tap_control 'All clear'
archphene_adb_run exec-out screencap >"$tmp/before.raw"

for control in One Add Two Equals; do
  tap_control "$control"
done
sleep 2
after_pid="$(archphene_android_pid "$package")"
after_child="$(kcalc_linux_pid)"
[[ "$after_pid" == "$before_pid" && "$after_child" == "$before_child" ]] \
  || archphene_die 'KCalc restarted while calculating 1 + 2'
ui="$(archphene_capture_ui kcalc-calculation-after)"
result="$(python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
values = [node.attrib.get("text", "") for node in root.iter("node")
          if node.attrib.get("class") == "android.widget.TextView"
          and node.attrib.get("text") not in ("", "NORM")]
print(values[0] if values else "")
' <<<"$ui")"
[[ "$result" == 3 ]] \
  || archphene_die "KCalc produced '$result' after 1 + 2 =, expected 3"
archphene_adb_run exec-out screencap >"$tmp/after.raw"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$tmp/before.raw" "$tmp/after.raw" \
  --left-percent 0 --right-percent 100 \
  --top-percent 8 --bottom-percent 45 \
  --minimum-difference .01 --minimum-changed-ratio .00001

log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
! archphene_regex_contains "$log" \
  'FATAL EXCEPTION|Runtime GUI exit=(?!0)|protocol error|InvalidGrab|UnconfiguredBuffer|native dispatch failed' \
  || archphene_die 'KCalc calculation triggered a runtime or compositor failure'
archphene_note "KCalc calculation passed on $serial: exact semantic result 3, rendered display change, stable Android PID $before_pid and Linux PID $before_child."
