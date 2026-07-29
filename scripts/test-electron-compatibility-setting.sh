#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--artifact-dir DIRECTORY]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
if [[ -z "$artifact_dir" ]]; then
  artifact_dir="$(archphene_mktemp_dir "electron-setting-$serial")"
fi
mkdir -p "$artifact_dir"

open_setting() {
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_wait_ui 'text="Packages"' electron-setting-home 20
  archphene_open_manager_section Settings electron-setting-settings
  archphene_wait_ui 'text="Linux app appearance"' electron-setting-appearance 15
  local attempt
  for attempt in 1 2 3 4; do
    if [[ "$ARCHPHENE_UI" == *'text="Reduced-isolation Electron mode"'* ]]; then
      SETTING_UI="$ARCHPHENE_UI"
      return
    fi
    archphene_adb_run shell input swipe 500 1500 500 500 350 >/dev/null
    sleep .5
    SETTING_UI="$(archphene_capture_ui electron-setting-scrolled)"
    ARCHPHENE_UI="$SETTING_UI"
  done
  archphene_die 'Electron compatibility setting is not reachable'
}

switch_info() {
  python3 -c '
import re, sys
from xml.etree import ElementTree

root = ElementTree.fromstring(sys.stdin.read())
for node in root.iter("node"):
    if (node.attrib.get("class") == "android.widget.Switch"
            and node.attrib.get("text") == "Reduced-isolation Electron mode"):
        values = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
        print(node.attrib.get("checked", "false"),
              (values[0] + values[2]) // 2,
              (values[1] + values[3]) // 2)
        raise SystemExit
raise SystemExit("Electron compatibility switch is missing")
' <<<"$1"
}

read_switch() {
  read -r SWITCH_STATE SWITCH_X SWITCH_Y <<<"$(switch_info "$SETTING_UI")"
}

tap_switch() {
  archphene_adb_run shell input tap "$SWITCH_X" "$SWITCH_Y" >/dev/null
}

wait_switch_state() {
  local expected="$1" deadline=$((SECONDS + 10))
  while ((SECONDS < deadline)); do
    SETTING_UI="$(archphene_capture_ui "electron-setting-$expected" 2>/dev/null || true)"
    read_switch
    [[ "$SWITCH_STATE" == "$expected" ]] && return
    sleep .3
  done
  archphene_die "Electron compatibility switch did not become $expected"
}

reveal_setting_card() {
  archphene_adb_run shell input swipe 500 1700 500 850 300 >/dev/null
  sleep .5
  SETTING_UI="$(archphene_capture_ui electron-setting-card)"
  read_switch
}

enable_setting() {
  tap_switch
  archphene_wait_ui 'text="Use reduced Electron isolation\?"' \
    electron-setting-warning 10
  archphene_adb_run exec-out screencap -p >"$artifact_dir/warning.png"
  [[ "$ARCHPHENE_UI" == *'text="ENABLE"'* || "$ARCHPHENE_UI" == *'text="Enable"'* ]] \
    || archphene_die 'Electron compatibility warning has no explicit Enable action'
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:ENABLE|Enable)"' Enable
  wait_switch_state true
}

disable_setting() {
  tap_switch
  wait_switch_state false
}

open_setting
read_switch
original_state="$SWITCH_STATE"
if [[ "$SWITCH_STATE" == true ]]; then
  disable_setting
fi

# A normal tap must never enable the reduced-isolation mode before the warning
# has been reviewed.
tap_switch
archphene_wait_ui 'text="Use reduced Electron isolation\?"' \
  electron-setting-cancel-warning 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:CANCEL|Cancel)"' Cancel
sleep .5
SETTING_UI="$(archphene_capture_ui electron-setting-cancelled)"
read_switch
[[ "$SWITCH_STATE" == false ]] \
  || archphene_die 'cancelling the warning enabled reduced-isolation mode'

enable_setting
reveal_setting_card
archphene_adb_run exec-out screencap -p >"$artifact_dir/enabled.png"

open_setting
read_switch
[[ "$SWITCH_STATE" == true ]] \
  || archphene_die 'Electron compatibility consent did not persist across restart'
reveal_setting_card
archphene_adb_run exec-out screencap -p >"$artifact_dir/restarted.png"

disable_setting
if [[ "$original_state" == true ]]; then
  enable_setting
fi
[[ "$SWITCH_STATE" == "$original_state" ]] \
  || archphene_die "Electron compatibility setting was not restored to $original_state"

archphene_note "Electron compatibility consent passed on $serial: warning, Cancel, explicit Enable, restart persistence, and original state $original_state restored. Evidence: $artifact_dir"
