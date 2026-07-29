#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
code_package=
skip_manager_install=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --code-package) code_package="${2:?missing value for --code-package}"; shift 2 ;;
    --skip-manager-install) skip_manager_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --code-package PACKAGE [--skip-manager-install]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$code_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--code-package is not a generated Archphene launcher"
archphene_require_file "$apk"
archphene_test_init "$serial"

manager=org.archphene.app.debug
manager_activity="$manager/org.archphene.app.MainActivity"
output_dir="$ARCHPHENE_ROOT/tooling/build/code-phone-workspace"
mkdir -p "$output_dir"
original_scale_index=
restore_pending=false

scale_slider() {
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET

root = ET.fromstring(sys.stdin.read())
for node in root.iter("node"):
    description = node.attrib.get("content-desc", "")
    if (
        node.attrib.get("class") != "android.widget.SeekBar"
        or not description.startswith("App scale, ")
    ):
        continue
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
    if bounds is None:
        raise SystemExit("invalid App scale bounds")
    value = description.removeprefix("App scale, ")
    index = {"Auto": 0, "75%": 1, "100%": 2, "125%": 3, "150%": 4}.get(value)
    if index is None:
        raise SystemExit("invalid App scale value")
    print(index, *bounds.groups())
    raise SystemExit
raise SystemExit("App scale slider is missing")
'
}

open_settings() {
  archphene_adb_run shell am force-stop "$manager" >/dev/null
  archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
  archphene_wait_ui 'text="Packages"' "code-phone-manager-$serial" 20
  archphene_open_manager_section Settings "code-phone-settings-$serial"
  archphene_wait_ui 'content-desc="App scale, [^"]+"' \
    "code-phone-slider-$serial" 15
}

set_scale_index() {
  local index="$1"
  local left top right bottom x y
  read -r _ left top right bottom <<<"$(scale_slider <<<"$ARCHPHENE_UI")"
  x=$((left + 21 + (right - left - 42) * index / 4))
  y=$(((top + bottom) / 2))
  archphene_adb_run shell input tap "$x" "$y" >/dev/null
  local label
  case "$index" in
    0) label=Auto ;;
    1) label=75% ;;
    2) label=100% ;;
    3) label=125% ;;
    4) label=150% ;;
    *) archphene_die "invalid App scale index: $index" ;;
  esac
  archphene_wait_ui "content-desc=\"App scale, $label\"" \
    "code-phone-scale-$serial-$index" 10
}

cleanup() {
  archphene_adb_run shell am force-stop "$code_package" >/dev/null 2>&1 || true
  if [[ "$restore_pending" == true && -n "$original_scale_index" ]]; then
    (
      open_settings
      set_scale_index "$original_scale_index"
    ) >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$skip_manager_install" == false ]]; then
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$code_package" >/dev/null ||
  archphene_die "Code launcher is not installed: $code_package"

open_settings
read -r original_scale_index _ <<<"$(scale_slider <<<"$ARCHPHENE_UI")"
restore_pending=true
set_scale_index 1
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-setting-75.png"

# Recreate the manager before launch so this is a durable user preference, not
# a transient in-memory test override.
open_settings
read -r persisted_scale_index _ <<<"$(scale_slider <<<"$ARCHPHENE_UI")"
[[ "$persisted_scale_index" == 1 ]] ||
  archphene_die "75% App scale did not persist across manager restart"

code_activity="$(archphene_launcher "$code_package")"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run shell am force-stop "$code_package" >/dev/null
archphene_adb_run shell am start -W -n "$code_activity" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected' 30 'ArchpheneLauncherSession:I *:S' \
  >/dev/null
archphene_wait_log \
  'Presented Linux frame.*selected=(1080x[0-9]+) surface=\1 original=\1 logical=576x[0-9]+.*output=576x[0-9]+' \
  45 'ArchpheneLauncherSession:I *:S' >/dev/null
sleep 8

# Open the application's standard integrated-terminal panel so the capture
# measures a real multi-pane desktop workspace rather than an empty splash.
archphene_adb_run shell input keycombination 113 59 68 >/dev/null
sleep 5
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-code-75.png"

appearance_log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneLauncherSession:I '*:S' 2>/dev/null
)"
archphene_regex_contains "$appearance_log" \
  'Resolved launcher appearance.*geometry=75.*controls=20dp' ||
  archphene_die "Code did not use the selected generic phone-workspace policy"
fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Code phone-workspace regression emitted a fatal runtime error: $fatal_log"

archphene_adb_run shell am force-stop "$code_package" >/dev/null
open_settings
set_scale_index "$original_scale_index"
open_settings
read -r restored_scale_index _ <<<"$(scale_slider <<<"$ARCHPHENE_UI")"
[[ "$restored_scale_index" == "$original_scale_index" ]] ||
  archphene_die "original App scale did not persist after restoration"
restore_pending=false

archphene_note "Code generic phone workspace passed on $serial"
archphene_note "  App scale persisted at 75% and rendered a 576-logical-pixel workspace"
archphene_note "  Original App scale index $original_scale_index restored"
archphene_note "  Full-device screenshots: $output_dir/$serial-setting-75.png and $output_dir/$serial-code-75.png"
