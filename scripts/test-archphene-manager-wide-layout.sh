#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
clean_data=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --clean-data) clean_data=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --clean-data"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$clean_data" == true ]] ||
  archphene_die "--clean-data is required because this gate clears Archphene app data"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
output_dir="$ARCHPHENE_ROOT/tooling/build/manager-wide-layout"
mkdir -p "$output_dir"

initial_size_output="$(archphene_adb_run shell wm size | tr -d '\r')"
initial_density_output="$(archphene_adb_run shell wm density | tr -d '\r')"
initial_size_override="$(
  awk -F ': ' '/^Override size:/ {print $2}' <<<"$initial_size_output"
)"
initial_density_override="$(
  awk -F ': ' '/^Override density:/ {print $2}' <<<"$initial_density_output"
)"
initial_accelerometer_rotation="$(
  archphene_adb_run shell settings get system accelerometer_rotation | tr -d '\r'
)"
initial_user_rotation="$(
  archphene_adb_run shell settings get system user_rotation | tr -d '\r'
)"
[[ "$initial_accelerometer_rotation" =~ ^[01]$ ]] ||
  archphene_die "unexpected accelerometer rotation setting: $initial_accelerometer_rotation"
[[ "$initial_user_rotation" =~ ^[0-3]$ ]] ||
  archphene_die "unexpected user rotation setting: $initial_user_rotation"

cleanup() {
  if [[ -n "$initial_size_override" ]]; then
    archphene_adb_run shell wm size "$initial_size_override" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm size reset >/dev/null 2>&1 || true
  fi
  if [[ -n "$initial_density_override" ]]; then
    archphene_adb_run shell wm density "$initial_density_override" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm density reset >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell settings put system accelerometer_rotation \
    "$initial_accelerometer_rotation" >/dev/null 2>&1 || true
  archphene_adb_run shell settings put system user_rotation \
    "$initial_user_rotation" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

assert_package_geometry() {
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET

root = ET.fromstring(sys.stdin.read())

def center(text, class_name):
    for node in root.iter("node"):
        if node.attrib.get("text") != text or node.attrib.get("class") != class_name:
            continue
        values = tuple(map(int, re.findall(r"\d+", node.attrib["bounds"])))
        return ((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
    raise SystemExit(f"missing {class_name} node: {text}")

navigation = [
    center("Packages", "android.widget.Button"),
    center("Files", "android.widget.Button"),
    center("Terminal", "android.widget.Button"),
]
if max(point[0] for point in navigation) - min(point[0] for point in navigation) > 32:
    raise SystemExit("wide manager navigation is not a vertical rail")
if not navigation[0][1] < navigation[1][1] < navigation[2][1]:
    raise SystemExit("wide manager navigation order is incorrect")

search = center("Package name", "android.widget.EditText")
results = center("Package results and activity", "android.widget.TextView")
if results[0] <= search[0] + 240:
    raise SystemExit("wide package results are not in a distinct right pane")
' <<<"$ARCHPHENE_UI"
}

assert_file_geometry() {
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET

root = ET.fromstring(sys.stdin.read())

def center(text):
    for node in root.iter("node"):
        if node.attrib.get("text") != text:
            continue
        values = tuple(map(int, re.findall(r"\d+", node.attrib["bounds"])))
        return ((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
    raise SystemExit(f"missing node: {text}")

file_card = center("Import from Android, or open/export Linux files")
folder_card = center("No Android folder connected")
if folder_card[0] <= file_card[0] + 320:
    raise SystemExit("wide file actions are not side by side")
if abs(folder_card[1] - file_card[1]) > 100:
    raise SystemExit("wide file actions are not aligned")
' <<<"$ARCHPHENE_UI"
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run shell wm size 1600x2560 >/dev/null
archphene_adb_run shell wm density 240 >/dev/null
archphene_adb_run shell settings put system accelerometer_rotation 0 >/dev/null
archphene_adb_run shell settings put system user_rotation 0 >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_skip_storage_onboarding "manager-wide-onboarding-$serial"

archphene_wait_ui_exact_text \
  "Package results and activity" "manager-wide-packages-$serial" 20
assert_package_geometry
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-tablet-packages.png"

archphene_open_manager_section Files "manager-wide-files-$serial"
archphene_wait_ui_exact_text \
  "No Android folder connected" "manager-wide-files-content-$serial" 15
assert_file_geometry
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-tablet-files.png"

archphene_open_manager_section Terminal "manager-wide-terminal-$serial"
archphene_wait_ui_exact_text \
  "Shared Linux terminal" "manager-wide-terminal-content-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-tablet-terminal.png"

archphene_adb_run shell settings put system user_rotation 1 >/dev/null
archphene_wait_ui \
  'text="Terminal"[^>]*class="android\.widget\.Button"[^>]*selected="true"' \
  "manager-wide-terminal-landscape-$serial" 20
archphene_wait_ui_exact_text \
  "Shared Linux terminal" "manager-wide-terminal-landscape-content-$serial" 15
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-external-display-terminal.png"

archphene_open_manager_section Packages "manager-wide-landscape-packages-$serial"
archphene_wait_ui_exact_text \
  "Package results and activity" "manager-wide-landscape-packages-content-$serial" 15
assert_package_geometry
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-external-display-packages.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Wide manager layout emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene wide manager layout passed on $serial"
archphene_note "  Navigation rail, two-pane packages, side-by-side files, and rotation passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{tablet-packages,tablet-files,tablet-terminal,external-display-terminal,external-display-packages}.png"
