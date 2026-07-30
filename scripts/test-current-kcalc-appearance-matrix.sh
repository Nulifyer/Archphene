#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archphene.app.debug
package=org.archphene.linux.p70b9ee91a45dfb9dc38f5721bcfabbcc
wide=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --manager) manager="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --wide) wide=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--manager PACKAGE] [--package PACKAGE] [--wide]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
if [[ "$wide" == true && "$serial" != emulator-* ]]; then
  archphene_die "--wide changes display overrides and is restricted to an emulator"
fi

archphene_test_init "$serial"
archphene_adb_run shell pm path "$manager" >/dev/null \
  || archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$package" >/dev/null \
  || archphene_die "KCalc wrapper is not installed: $package"
if [[ -n "$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')" ]]; then
  archphene_die "refusing to replace an active KCalc session: $package"
fi

safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/kcalc-appearance-matrix"
mkdir -p "$artifact_dir"
ui_path=/sdcard/archphene-kcalc-appearance.xml
initially_running=false
archphene_adb_run shell pidof "$manager" >/dev/null 2>&1 && initially_running=true
initial_size="$(archphene_adb_run shell wm size | tr -d '\r')"
initial_density="$(archphene_adb_run shell wm density | tr -d '\r')"
initial_size_override="$(sed -n 's/^Override size: //p' <<<"$initial_size")"
initial_density_override="$(sed -n 's/^Override density: //p' <<<"$initial_density")"

read_preference() {
  local key="$1"
  local preferences
  preferences="$(
    archphene_adb_run shell run-as "$manager" \
      cat shared_prefs/linux_appearance.xml 2>/dev/null || true
  )"
  python3 -c '
import sys
import xml.etree.ElementTree as ET
key = sys.argv[1]
text = sys.stdin.read().strip()
if not text:
    print(0)
    raise SystemExit
root = ET.fromstring(text)
node = next((item for item in root if item.attrib.get("name") == key), None)
print(node.attrib.get("value", "0") if node is not None else "0")
' "$key" <<<"$preferences"
}

original_font="$(read_preference font_percent)"
original_controls="$(read_preference control_visual_dp)"
original_section=

dump_ui() {
  archphene_adb_run shell uiautomator dump "$ui_path" >/dev/null 2>&1
  archphene_adb_run exec-out cat "$ui_path"
}

selected_section() {
  python3 -c '
import sys
import xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter("node"):
    if (node.attrib.get("class") == "android.widget.Button"
            and node.attrib.get("selected") == "true"
            and node.attrib.get("text")):
        print(node.attrib["text"])
        break
'
}

node_center() {
  local kind="$1" value="$2"
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET
kind, value = sys.argv[1:3]
root = ET.fromstring(sys.stdin.read())
node = next((
    item for item in root.iter("node")
    if (kind == "text" and item.attrib.get("text") == value)
    or (kind == "description"
        and item.attrib.get("content-desc", "").startswith(value))
), None)
if node is None:
    raise SystemExit(1)
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                     node.attrib.get("bounds", ""))
if match is None:
    raise SystemExit(1)
left, top, right, bottom = map(int, match.groups())
print((left + right) // 2, (top + bottom) // 2)
' "$kind" "$value"
}

slider_tap() {
  local description="$1" index="$2" maximum="$3"
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET
description, index, maximum = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
root = ET.fromstring(sys.stdin.read())
node = next((item for item in root.iter("node")
             if item.attrib.get("class") == "android.widget.SeekBar"
             and item.attrib.get("content-desc", "").startswith(description)), None)
if node is None:
    raise SystemExit(1)
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                     node.attrib.get("bounds", ""))
if match is None:
    raise SystemExit(1)
left, top, right, bottom = map(int, match.groups())
fraction = 0.03 if maximum == 0 else 0.03 + 0.94 * index / maximum
print(round(left + (right - left) * fraction), (top + bottom) // 2)
' "$description" "$index" "$maximum"
}

open_settings() {
  local ui action x y
  archphene_adb_run shell am start -W \
    -n "$manager/org.archphene.app.MainActivity" >/dev/null
  for _ in {1..12}; do
    sleep .35
    ui="$(dump_ui)"
    if [[ "$ui" == *'content-desc="Color scheme,'* ||
        "$ui" == *'content-desc="App text,'* ||
        "$ui" == *'content-desc="App controls,'* ]]; then
      printf '%s' "$ui"
      return 0
    fi
    action="$(node_center text Settings <<<"$ui" 2>/dev/null || true)"
    if [[ -n "$action" ]]; then
      read -r x y <<<"$action"
      archphene_adb_run shell input tap "$x" "$y" >/dev/null
    fi
  done
  archphene_die "Linux appearance settings did not become visible"
}

set_slider() {
  local description="$1" key="$2" value="$3" index="$4" maximum="$5"
  local ui action x y deadline
  ui="$(open_settings)"
  for _ in {1..8}; do
    action="$(slider_tap "$description" "$index" "$maximum" \
      <<<"$ui" 2>/dev/null || true)"
    if [[ -n "$action" ]]; then
      read -r x y <<<"$action"
      archphene_adb_run shell input tap "$x" "$y" >/dev/null
      deadline=$((SECONDS + 10))
      while ((SECONDS < deadline)); do
        [[ "$(read_preference "$key")" == "$value" ]] && return 0
        sleep .2
      done
      archphene_die "$description did not persist $value"
    fi
    archphene_adb_run shell input swipe 540 1900 540 650 300 >/dev/null
    sleep .3
    ui="$(dump_ui)"
  done
  archphene_die "$description slider is missing"
}

font_index() {
  case "$1" in
    0) echo 0 ;;
    100) echo 1 ;;
    110) echo 2 ;;
    120) echo 3 ;;
    130) echo 4 ;;
    140) echo 5 ;;
    150) echo 6 ;;
    160) echo 7 ;;
    170) echo 8 ;;
    180) echo 9 ;;
    190) echo 10 ;;
    200) echo 11 ;;
    *) archphene_die "unsupported saved App text value: $1" ;;
  esac
}

control_index() {
  case "$1" in
    0) echo 0 ;;
    12) echo 1 ;;
    16) echo 2 ;;
    20) echo 3 ;;
    24) echo 4 ;;
    28) echo 5 ;;
    32) echo 6 ;;
    36) echo 7 ;;
    40) echo 8 ;;
    44) echo 9 ;;
    48) echo 10 ;;
    *) archphene_die "unsupported saved App controls value: $1" ;;
  esac
}

restore_display() {
  if [[ -n "$initial_size_override" ]]; then
    archphene_adb_run shell wm size "$initial_size_override" >/dev/null
  else
    archphene_adb_run shell wm size reset >/dev/null
  fi
  if [[ -n "$initial_density_override" ]]; then
    archphene_adb_run shell wm density "$initial_density_override" >/dev/null
  else
    archphene_adb_run shell wm density reset >/dev/null
  fi
}

restore() {
  set +e
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1
  restore_display >/dev/null 2>&1
  set_slider "App text," font_percent "$original_font" \
    "$(font_index "$original_font")" 11 >/dev/null 2>&1
  set_slider "App controls," control_visual_dp "$original_controls" \
    "$(control_index "$original_controls")" 10 >/dev/null 2>&1
  if [[ -n "$original_section" && "$original_section" != Settings ]]; then
    archphene_open_manager_section "$original_section" \
      "kcalc-appearance-restore-$safe_serial" >/dev/null 2>&1
  fi
  archphene_adb_run shell rm -f "$ui_path" >/dev/null 2>&1
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1
  fi
}
trap restore EXIT

archphene_adb_run shell am start -W \
  -n "$manager/org.archphene.app.MainActivity" >/dev/null
sleep .5
original_section="$(selected_section <<<"$(dump_ui)")"
[[ -n "$original_section" ]] || original_section=Packages
open_settings >/dev/null

run_profile() {
  local name="$1" expected_font="$2" expected_controls="$3"
  local profile_dir="$artifact_dir/$name" config
  mkdir -p "$profile_dir"
  "$ARCHPHENE_SCRIPTS_DIR/test-kcalc-menu-switch.sh" \
    --serial "$serial" --manager "$manager" --package "$package" \
    --artifact-dir "$profile_dir"
  "$ARCHPHENE_SCRIPTS_DIR/test-kcalc-calculation.sh" \
    --serial "$serial" --package "$package"
  config="$(<"$profile_dir/kdeglobals")"
  grep -Fxq "ControlVisualSize=$expected_controls" <<<"$config" \
    || archphene_die "$name did not resolve ${expected_controls}dp controls"
  grep -Eq "^font=[^,]+,$expected_font," <<<"$config" \
    || archphene_die "$name did not resolve ${expected_font}pt text"
}

set_slider "App text," font_percent 200 11 11
set_slider "App controls," control_visual_dp 48 10 10
run_profile phone-200pct-48dp 24 48

set_slider "App text," font_percent 0 0 11
set_slider "App controls," control_visual_dp 0 0 10
run_profile phone-auto 12 20

if [[ "$wide" == true ]]; then
  archphene_adb_run shell wm size 1600x2560 >/dev/null
  archphene_adb_run shell wm density 240 >/dev/null
  sleep 1
  "$ARCHPHENE_SCRIPTS_DIR/test-kcalc-menu-switch.sh" \
    --serial "$serial" --manager "$manager" --package "$package" \
    --artifact-dir "$artifact_dir/tablet-auto"
fi

restore
trap - EXIT
[[ "$(read_preference font_percent)" == "$original_font" ]] \
  || archphene_die "App text preference was not restored"
[[ "$(read_preference control_visual_dp)" == "$original_controls" ]] \
  || archphene_die "App controls preference was not restored"
[[ "$(archphene_adb_run shell wm size | tr -d '\r')" == "$initial_size" ]] \
  || archphene_die "display size was not restored"
[[ "$(archphene_adb_run shell wm density | tr -d '\r')" == "$initial_density" ]] \
  || archphene_die "display density was not restored"
archphene_note "Current KCalc appearance matrix passed and restored state: $artifact_dir"
