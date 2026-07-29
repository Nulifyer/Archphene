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
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
output_dir="$ARCHPHENE_ROOT/tooling/build/manager-wide-layout"
mkdir -p "$output_dir"
initially_running=false
original_section=
if archphene_adb_run shell pidof "$package" >/dev/null 2>&1; then
  initially_running=true
fi

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
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
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
  if [[ -n "$original_section" ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    local ui
    ui="$(archphene_capture_ui "manager-wide-restore-$serial" 2>/dev/null || true)"
    if archphene_regex_contains \
      "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
      archphene_tap_ui_pattern \
        "$ui" \
        "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
        "$original_section" >/dev/null 2>&1 || true
    fi
  fi
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

assert_restored() {
  [[ "$(archphene_adb_run shell wm size | tr -d '\r')" == "$initial_size_output" ]] ||
    archphene_die "wide-layout gate did not restore the exact display size"
  [[ "$(archphene_adb_run shell wm density | tr -d '\r')" == "$initial_density_output" ]] ||
    archphene_die "wide-layout gate did not restore the exact display density"
  [[ "$(
    archphene_adb_run shell settings get system accelerometer_rotation | tr -d '\r'
  )" == "$initial_accelerometer_rotation" ]] ||
    archphene_die "wide-layout gate did not restore automatic rotation"
  [[ "$(
    archphene_adb_run shell settings get system user_rotation | tr -d '\r'
  )" == "$initial_user_rotation" ]] ||
    archphene_die "wide-layout gate did not restore user rotation"

  if [[ -n "$original_section" ]]; then
    if [[ "$initially_running" == false ]]; then
      archphene_adb_run shell am start -W -n "$activity" >/dev/null
    fi
    local restored_ui
    restored_ui="$(archphene_capture_ui "manager-wide-restored-$serial")"
    archphene_regex_contains \
      "$restored_ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"[^>]*selected=\"true\"" ||
      archphene_die "wide-layout gate did not restore manager section $original_section"
    if [[ "$initially_running" == false ]]; then
      archphene_adb_run shell am force-stop "$package" >/dev/null
    fi
  fi
  if [[ "$initially_running" == true ]]; then
    archphene_adb_run shell pidof "$package" >/dev/null ||
      archphene_die "wide-layout gate did not restore the running manager"
  else
    ! archphene_adb_run shell pidof "$package" >/dev/null 2>&1 ||
      archphene_die "wide-layout gate left the manager running"
  fi
}

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

def bounds(text, class_name):
    for node in root.iter("node"):
        if node.attrib.get("text") != text or node.attrib.get("class") != class_name:
            continue
        return tuple(map(int, re.findall(r"\d+", node.attrib["bounds"])))
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

review_actions = [
    center("Search", "android.widget.Button"),
    center("Details", "android.widget.Button"),
    center("AUR", "android.widget.Button"),
]
mutation_actions = [
    center("Install", "android.widget.Button"),
    center("Remove", "android.widget.Button"),
]
if max(point[1] for point in review_actions) - min(point[1] for point in review_actions) > 8:
    raise SystemExit("package review actions are not in one row")
if max(point[1] for point in mutation_actions) - min(point[1] for point in mutation_actions) > 8:
    raise SystemExit("package mutation actions are not in one row")
if mutation_actions[0][1] <= review_actions[0][1] + 48:
    raise SystemExit("package mutation actions are not in a distinct second row")
for label in ("Search", "Details", "AUR", "Install", "Remove"):
    left, top, right, bottom = bounds(label, "android.widget.Button")
    if right - left < 96 or bottom - top < 64:
        raise SystemExit(f"package action touch target is too small: {label}")
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

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell wm size 1600x2560 >/dev/null
archphene_adb_run shell wm density 240 >/dev/null
archphene_adb_run shell settings put system accelerometer_rotation 0 >/dev/null
archphene_adb_run shell settings put system user_rotation 0 >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
initial_ui="$(archphene_capture_ui "manager-wide-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_die \
    "manager onboarding is incomplete; complete it before the state-preserving wide-layout gate"
fi
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal", "Settings"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$initial_ui"
)"

archphene_open_manager_section Packages "manager-wide-packages-section-$serial"
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
assert_restored
archphene_note "Archphene wide manager layout passed on $serial"
archphene_note "  Navigation rail, two-pane packages, side-by-side files, and rotation passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{tablet-packages,tablet-files,tablet-terminal,external-display-terminal,external-display-packages}.png"
