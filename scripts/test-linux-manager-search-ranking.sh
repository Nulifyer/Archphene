#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
query=glmark2-es2-wayland
expected_package=glmark2
expected_file=usr/bin/glmark2-es2-wayland
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --query) query="${2:?}"; shift 2 ;;
    --expected-package) expected_package="${2:?}"; shift 2 ;;
    --expected-file) expected_file="${2:-}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--query QUERY] [--expected-package PACKAGE] [--expected-file PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
package=org.archpheneos.manager
preferences=shared_prefs/linux-app-manager-state.xml
backup=cache/search-ranking-state.xml
had_preferences=false
archphene_adb_run shell am force-stop "$package"
if archphene_adb_run shell run-as "$package" cp "$preferences" "$backup" \
    >/dev/null 2>&1; then
  had_preferences=true
fi
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$had_preferences" == true ]]; then
    archphene_adb_run shell run-as "$package" cp "$backup" "$preferences" \
      >/dev/null 2>&1 || true
  else
    archphene_adb_run shell run-as "$package" rm -f "$preferences" \
      >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell run-as "$package" rm -f "$backup" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am start -W -n "$package/.MainActivity" \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell am start -W -n "$package/.MainActivity" >/dev/null
archphene_wait_ui 'content-desc="Filter and sort apps"' ranking-home
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Filter and sort apps"' filter
archphene_wait_ui 'text="Filter and sorting"' ranking-filter-dialog
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="(?:All apps|Updates available|Pinned versions)"' current-filter
archphene_wait_ui 'text="All apps"' ranking-filter-options
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="All apps"' all-apps
archphene_wait_ui 'text="APPLY"' ranking-filter-apply
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="APPLY"' apply
archphene_wait_ui 'content-desc="Add Linux app"' ranking-all-apps
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Add Linux app"' 'Add Linux app'
archphene_wait_ui 'text="Search official Arch packages"' ranking-add
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Search official Arch packages"' search-field
archphene_adb_run shell input text "$query"
sleep 1
ui="$(archphene_capture_ui ranking-query)"
if [[ "$ui" == *'text="Try out your stylus"'* \
    && "$ui" == *'text="Cancel"'* ]]; then
  archphene_tap_ui_pattern "$ui" 'text="Cancel"' \
    'Gboard stylus education Cancel'
  sleep 1
fi
archphene_adb_run shell input keyevent KEYCODE_BACK
sleep 1
ui="$(archphene_capture_ui ranking-query-stable)"
archphene_tap_ui_pattern "$ui" \
  'content-desc="Search package repositories"' search-button

escaped="$(python3 -c \
  'import re,sys; print(re.escape(sys.argv[1]))' "$expected_package")"
archphene_wait_ui "text=\"$escaped  [^\"]+\"" ranking-results 60
ui="$ARCHPHENE_UI"
if [[ -n "$expected_file" ]]; then
  expected="Matched file: /$expected_file"
  [[ "$ui" == *"text=\"$expected\""* ]] \
    || archphene_die "expected executable match was not shown: $expected"
fi
first_package="$(python3 -c '
import re, sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter("node"):
    if node.attrib.get("class") != "android.widget.TextView":
        continue
    match = re.fullmatch(r"([A-Za-z0-9@._+-]+)  \S+", node.attrib.get("text", ""))
    if match:
        print(match.group(1))
        break
' <<<"$ui")"
[[ "$first_package" == "$expected_package" ]] \
  || archphene_die "first ranked result was '$first_package', expected '$expected_package'"

archphene_tap_ui_pattern "$ui" 'text="Apps"' Apps
archphene_wait_ui 'text="Search apps"' ranking-apps
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Search apps"' app-search
archphene_adb_run shell input text 'extra%smousepad'
sleep 1
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_wait_ui \
  'text="pacman \| extra/mousepad"' ranking-installed
ui="$ARCHPHENE_UI"
[[ "$ui" == *'text="Mousepad"'* ]] \
  || archphene_die 'multi-term app search omitted Mousepad'
[[ "$ui" != *'text="KCalc"'* ]] \
  || archphene_die 'multi-term app search retained unrelated KCalc'

cleanup
trap - EXIT
archphene_note "Repository executable discovery and shared search ranking passed on $serial: $expected_package is the first result for '$query', exact matched-file evidence is shown, and multi-term installed-app filtering is precise."
