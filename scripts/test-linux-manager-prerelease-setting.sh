#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
package=org.archpheneos.manager
open_settings() {
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run shell am start -W -n "$package/.MainActivity" >/dev/null
  archphene_wait_ui 'text="Settings"' manager-prerelease-home
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Settings"' Settings
  archphene_wait_ui 'text="Allow pre-release versions"' \
    manager-prerelease-settings
  SWITCH_UI="$ARCHPHENE_UI"
}
switch_info() {
  python3 -c '
import re, sys
text = sys.stdin.read()
label = text.find("text=\"Allow pre-release versions\"")
if label < 0:
    raise SystemExit("pre-release setting label is missing")
match = re.search(
    r"class=\"android\.widget\.Switch\"[^>]*"
    r"checked=\"(true|false)\"[^>]*"
    r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",
    text[label:label + 2200])
if match is None:
    raise SystemExit("pre-release setting switch is missing")
checked, left, top, right, bottom = match.groups()
print(checked, (int(left) + int(right)) // 2,
      (int(top) + int(bottom)) // 2)
' <<<"$1"
}
read_switch() {
  read -r SWITCH_STATE SWITCH_X SWITCH_Y <<<"$(switch_info "$SWITCH_UI")"
}
toggle_switch() {
  archphene_adb_run shell input tap "$SWITCH_X" "$SWITCH_Y"
  sleep .5
  SWITCH_UI="$(archphene_capture_ui manager-prerelease-toggled)"
  read_switch
}

open_settings
read_switch
original_state="$SWITCH_STATE"
if [[ "$SWITCH_STATE" == true ]]; then
  toggle_switch
fi
[[ "$SWITCH_STATE" == false ]] \
  || archphene_die 'could not disable pre-release versions'
toggle_switch
[[ "$SWITCH_STATE" == true ]] \
  || archphene_die 'could not enable pre-release versions'

open_settings
read_switch
[[ "$SWITCH_STATE" == true ]] \
  || archphene_die 'pre-release setting did not persist across manager restart'
if [[ "$original_state" == false ]]; then
  toggle_switch
fi
[[ "$SWITCH_STATE" == "$original_state" ]] \
  || archphene_die 'pre-release setting was not restored'

archphene_note "Pre-release setting passed on $serial: disable/enable, restart persistence, and original state $original_state restored."
