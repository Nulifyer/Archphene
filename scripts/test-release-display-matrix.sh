#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --kcalc-package) package="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
[[ "$(archphene_adb_run shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] \
  || archphene_die 'display matrix changes wm size/density and must run on an emulator'

manager=org.archpheneos.manager
activity="$(archphene_launcher "$package")"
old_size="$(archphene_adb_run shell wm size | tr -d '\r')"
old_density="$(archphene_adb_run shell wm density | tr -d '\r')"
old_font="$(archphene_adb_run shell settings get system font_scale | tr -d '\r')"
old_night="$(archphene_adb_run shell cmd uimode night \
  | sed -n 's/^Night mode: //p' | tr -d '\r')"
restore() {
  local override
  override="$(sed -n 's/^Override size: //p' <<<"$old_size")"
  if [[ -n "$override" ]]; then
    archphene_adb_run shell wm size "$override" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm size reset >/dev/null 2>&1 || true
  fi
  override="$(sed -n 's/^Override density: //p' <<<"$old_density")"
  if [[ -n "$override" ]]; then
    archphene_adb_run shell wm density "$override" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm density reset >/dev/null 2>&1 || true
  fi
  if [[ -n "$old_font" && "$old_font" != null ]]; then
    archphene_adb_run shell settings put system font_scale "$old_font" \
      >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell cmd uimode night "${old_night:-auto}" \
    >/dev/null 2>&1 || true
}
trap restore EXIT

assert_node_bounds() {
  local ui="$1" attribute="$2" value="$3" width="$4" height="$5" label="$6"
  python3 -c '
import re, sys, xml.etree.ElementTree as ET
attribute, value, width, height, label = sys.argv[1:]
root = ET.fromstring(sys.stdin.read())
node = next((item for item in root.iter("node")
             if item.attrib.get(attribute) == value), None)
if node is None:
    raise SystemExit(f"{label} is missing from the UI tree")
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                     node.attrib.get("bounds", ""))
if match is None:
    raise SystemExit(f"{label} has invalid bounds")
left, top, right, bottom = map(int, match.groups())
width, height = int(width), int(height)
if left < 0 or top < 0 or right > width or bottom > height:
    raise SystemExit(
        f"{label} is outside {width}x{height}: "
        f"[{left},{top}][{right},{bottom}]")
if right <= left or bottom <= top:
    raise SystemExit(f"{label} has empty bounds")
print(left, top, right, bottom)
' "$attribute" "$value" "$width" "$height" "$label" <<<"$ui"
}

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
sleep 7
initial_pid="$(archphene_android_pid "$package")"
initial_child="$(archphene_linux_loader_pid "$initial_pid")"
[[ -n "$initial_pid" && -n "$initial_child" ]] \
  || archphene_die 'KCalc process tree did not become ready'

profiles=(
  'phone-light 1080 2400 420 1.0 no'
  'phone-landscape-dark 2400 1080 420 1.0 yes'
  'tablet-light 1280 1920 280 1.15 no'
  'tablet-landscape-dark 1920 1280 280 1.15 yes'
  'docked-dark 1920 1080 240 1.0 yes'
)
for row in "${profiles[@]}"; do
  read -r name width height density font night <<<"$row"
  archphene_adb_run shell wm size "${width}x${height}"
  archphene_adb_run shell wm density "$density"
  archphene_adb_run shell settings put system font_scale "$font"
  archphene_adb_run shell cmd uimode night "$night"
  sleep 4

  archphene_adb_run shell am start -W -n "$manager/.MainActivity" >/dev/null
  sleep 2
  manager_ui="$(archphene_capture_ui "matrix-$name-manager")"
  assert_node_bounds "$manager_ui" text Apps \
    "$width" "$height" 'manager Apps tab' >/dev/null
  assert_node_bounds "$manager_ui" text 'Search apps' \
    "$width" "$height" 'manager search control' >/dev/null
  assert_node_bounds "$manager_ui" text Settings \
    "$width" "$height" 'manager Settings tab' >/dev/null
  assert_node_bounds "$manager_ui" content-desc 'Add Linux app' \
    "$width" "$height" 'manager add-app control' >/dev/null

  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  sleep 3
  current_pid="$(archphene_android_pid "$package")"
  current_child="$(archphene_linux_loader_pid "$current_pid")"
  [[ "$current_pid" == "$initial_pid" && "$current_child" == "$initial_child" ]] \
    || archphene_die "$name restarted KCalc: Android $initial_pid/$current_pid, Linux $initial_child/$current_child"
  kcalc_ui="$(archphene_capture_ui "matrix-$name-kcalc")"
  read -r left top right bottom <<<"$(assert_node_bounds \
    "$kcalc_ui" class android.widget.ImageView "$width" "$height" 'KCalc viewport')"
  viewport_width=$((right - left))
  viewport_height=$((bottom - top))
  ((viewport_width * 10 >= width * 8)) \
    || archphene_die "$name KCalc viewport is too narrow: ${viewport_width}px"
  ((viewport_height * 10 >= height * 7)) \
    || archphene_die "$name KCalc viewport is too short: ${viewport_height}px"
  archphene_note "$name passed: ${width}x$height density=$density font=$font night=$night viewport=${viewport_width}x$viewport_height"
done

log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
! archphene_regex_contains "$log" \
  'FATAL EXCEPTION|protocol error|InvalidGrab|UnconfiguredBuffer|native dispatch failed' \
  || archphene_die "display matrix produced a runtime or compositor failure"

# Run in a child process so the live-theme gate's cleanup trap cannot replace
# this matrix's display-restoration trap.
"$ARCHPHENE_SCRIPTS_DIR/test-kcalc-live-theme.sh" \
  --serial "$serial" --package "$package"

restore
trap - EXIT
archphene_note "Release display matrix passed on $serial with stable Android PID $initial_pid and Linux PID $initial_child."
