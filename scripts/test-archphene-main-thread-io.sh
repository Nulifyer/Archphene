#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
output_dir="$ARCHPHENE_ROOT/tooling/build/main-thread-io"
mkdir -p "$output_dir"
raw_log="$output_dir/$serial.log"
screenshot="$output_dir/$serial.png"

initially_running=false
original_section=
if [[ -n "$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')" ]]; then
  initially_running=true
  initial_ui="$(archphene_capture_ui "main-thread-io-original-$serial" 2>/dev/null || true)"
  original_section="$(
    python3 -c '
import sys
import xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    raise SystemExit
for node in root.iter("node"):
    if (
        node.attrib.get("class") == "android.widget.Button"
        and node.attrib.get("selected") == "true"
        and node.attrib.get("text") in {"Packages", "Files", "Terminal", "Settings"}
    ):
        print(node.attrib["text"])
        break
' <<<"$initial_ui"
  )"
fi

cleanup() {
  if [[ -n "$original_section" ]]; then
    local cleanup_ui
    cleanup_ui="$(archphene_capture_ui "main-thread-io-cleanup-$serial" 2>/dev/null || true)"
    if [[ -n "$cleanup_ui" ]]; then
      archphene_tap_text "$cleanup_ui" "$original_section" >/dev/null 2>&1 || true
    fi
  fi
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ -n "$apk" ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi

device_abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$device_abi" in
  x86_64) query=dotnet-sdk ;;
  arm64-v8a) query=btop ;;
  *) archphene_die "unsupported device ABI: $device_abi" ;;
esac

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'StrictMode main-thread I/O and resource-leak diagnostics enabled' \
  15 'ArchpheneStrictMode:V *:S' >/dev/null
archphene_wait_ui 'Pacman ready' "main-thread-io-ready-$serial" 20 >/dev/null
if [[ -z "$original_section" ]]; then
  original_section="$(
    python3 -c '
import sys
import xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter("node"):
    if (
        node.attrib.get("class") == "android.widget.Button"
        and node.attrib.get("selected") == "true"
        and node.attrib.get("text") in {"Packages", "Files", "Terminal", "Settings"}
    ):
        print(node.attrib["text"])
        break
' <<<"$ARCHPHENE_UI"
  )"
fi
[[ -n "$original_section" ]] ||
  archphene_die "could not identify the original manager section"

# Exercise a real main-thread preference write, then restore the exact value.
archphene_open_manager_section Settings "main-thread-io-settings-$serial"
settings_ui="$ARCHPHENE_UI"
readarray -t slider_geometry < <(
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter("node"):
    description = node.attrib.get("content-desc", "")
    if node.attrib.get("class") != "android.widget.SeekBar" or not description.startswith("App scale, "):
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
    if not match:
        raise SystemExit("invalid App scale bounds")
    value = description.removeprefix("App scale, ")
    index = {"Auto": 0, "75%": 1, "100%": 2, "125%": 3, "150%": 4}.get(value)
    if index is None:
        raise SystemExit("invalid App scale value")
    print(index)
    print(match.group(1))
    print(match.group(2))
    print(match.group(3))
    print(match.group(4))
    break
' <<<"$settings_ui"
)
(( ${#slider_geometry[@]} == 5 )) ||
  archphene_die "could not locate the App scale slider"
original_slider="${slider_geometry[0]}"
left="${slider_geometry[1]}"
top="${slider_geometry[2]}"
right="${slider_geometry[3]}"
bottom="${slider_geometry[4]}"
alternate_slider=0
((original_slider == 0)) && alternate_slider=1
slider_x() {
  local index="$1"
  echo $((left + 21 + (right - left - 42) * index / 4))
}
slider_y=$(((top + bottom) / 2))
archphene_adb_run shell input tap "$(slider_x "$alternate_slider")" "$slider_y" >/dev/null
sleep 0.3
archphene_adb_run shell input tap "$(slider_x "$original_slider")" "$slider_y" >/dev/null
sleep 0.3
restored_ui="$(archphene_capture_ui "main-thread-io-settings-restored-$serial")"
expected_slider=("Auto" "75%" "100%" "125%" "150%")
archphene_regex_contains \
  "$restored_ui" \
  "content-desc=\"App scale, ${expected_slider[$original_slider]}\"" ||
  archphene_die "App scale preference did not return to its original value"

# Prove the restored value came back from disk after process death.
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui 'Pacman ready' "main-thread-io-restarted-$serial" 20 >/dev/null
archphene_open_manager_section Settings "main-thread-io-settings-restarted-$serial"
restarted_ui="$ARCHPHENE_UI"
archphene_regex_contains \
  "$restarted_ui" \
  "content-desc=\"App scale, ${expected_slider[$original_slider]}\"" ||
  archphene_die "App scale preference did not survive manager process death"

# Exercise real pacman-backed catalog search and dependency resolution.
archphene_open_manager_section Packages "main-thread-io-packages-$serial"
packages_ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$packages_ui" \
  'class="android.widget.EditText"[^>]*(?:text|hint)="Package name"|text="Package name"[^>]*class="android.widget.EditText"' \
  "Package name"
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$query" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
query_ui="$(archphene_capture_ui "main-thread-io-query-$serial")"
archphene_tap_text "$query_ui" Search
archphene_wait_ui_exact_text "$query" "main-thread-io-result-$serial" 20
archphene_tap_text "$ARCHPHENE_UI" Details
archphene_wait_ui \
  'Dependency closure: [1-9][0-9]* packages' \
  "main-thread-io-resolution-$serial" 20 >/dev/null
archphene_adb_run exec-out screencap -p >"$screenshot"

archphene_adb_run logcat -d -v threadtime \
  StrictMode:D AndroidRuntime:E libc:F '*:S' >"$raw_log"
python3 - "$raw_log" <<'PY'
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
marker = "StrictMode policy violation; "
owned = []
framework = 0
for index, line in enumerate(lines):
    if marker not in line:
        continue
    block = []
    for candidate in lines[index + 1 :]:
        if marker in candidate:
            break
        block.append(candidate)
    if any("\tat org.archphene." in candidate for candidate in block):
        owned.append("\n".join([line, *block]))
    else:
        framework += 1
fatal = [
    line
    for line in lines
    if "FATAL EXCEPTION" in line or "Fatal signal" in line
]
if owned:
    print("\n\n".join(owned), file=sys.stderr)
    raise SystemExit(f"{len(owned)} Archphene StrictMode violation(s)")
if fatal:
    print("\n".join(fatal), file=sys.stderr)
    raise SystemExit(f"{len(fatal)} fatal runtime event(s)")
print(f"framework_only_strictmode={framework}")
PY

trap - EXIT
cleanup
archphene_note "Archphene main-thread I/O gate passed on $serial"
archphene_note "  Cold startup, preference write/restore, package search, and dependency resolution passed"
archphene_note "  Raw log: $raw_log"
archphene_note "  Full-device screenshot: $screenshot"
