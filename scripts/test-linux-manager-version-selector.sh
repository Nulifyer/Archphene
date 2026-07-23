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
preferences=shared_prefs/linux-app-manager-state.xml
backup=cache/version-selector-state.xml
had_preferences=false
archphene_adb_run shell am force-stop "$package"
if archphene_adb_run shell run-as "$package" cp "$preferences" "$backup" \
    >/dev/null 2>&1; then
  had_preferences=true
fi
restore() {
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
trap restore EXIT

archphene_adb_run shell am start -W -n "$package/.MainActivity" >/dev/null
archphene_wait_ui 'content-desc="Filter and sort apps"' version-home
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Filter and sort apps"' filter
archphene_wait_ui 'text="Filter and sorting"' version-filter-dialog
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="(?:All apps|Updates available|Pinned versions)"' current-filter
archphene_wait_ui 'text="All apps"' version-filter-options
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="All apps"' all-apps
archphene_wait_ui 'text="APPLY"' version-filter-apply
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="APPLY"' apply
archphene_wait_ui 'text="KCalc"' version-all-apps
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="KCalc"' 'KCalc detail'

archphene_wait_ui 'content-desc="Version selector, [0-9]+ versions"' \
  version-detail 20
read -r version_count current_version <<<"$(python3 -c '
import re, sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
spinner = next((node for node in root.iter("node")
                if node.attrib.get("class") == "android.widget.Spinner"), None)
if spinner is None:
    raise SystemExit("version selector is missing")
match = re.fullmatch(r"Version selector, (\d+) versions",
                     spinner.attrib.get("content-desc", ""))
if match is None:
    raise SystemExit("version selector count is missing")
selected = next((node.attrib.get("text", "") for node in spinner.iter("node")
                 if node.attrib.get("text")), "")
if not selected:
    raise SystemExit("selected version is missing")
print(match.group(1), selected)
' <<<"$ARCHPHENE_UI")"
escaped_current="$(python3 -c \
  'import re,sys; print(re.escape(sys.argv[1]))' "$current_version")"
if archphene_regex_contains "$ARCHPHENE_UI" \
    'text="Pin selected version"[^>]*checked="true"'; then
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Pin selected version"[^>]*checked="true"' 'checked pin'
  archphene_wait_ui \
    'text="Pin selected version"[^>]*checked="false"' version-unpinned
fi
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Check for update"' 'check for update'
archphene_wait_ui 'content-desc="Version selector, [0-9]+ versions"' \
  version-after-check 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Version selector, [0-9]+ versions"' 'version selector'
if ((version_count > 1)); then
  selected_version=26.04.0-1
  escaped_selected=26\\.04\\.0-1
  archphene_wait_ui 'text="26\.04\.0-1"' version-options 20
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="26\.04\.0-1"' 'archived version'
  archphene_wait_ui 'text="Archived version; compatibility not verified"' \
    version-selected
  lane='archived-version'
else
  selected_version="$current_version"
  escaped_selected="$escaped_current"
  archphene_wait_ui "text=\"$escaped_current\"" version-options 20
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    "text=\"$escaped_current\"" 'current version'
  archphene_wait_ui \
    'text="(?:Installed version|Current repository version)"' version-selected
  lane='current-only'
fi
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Pin selected version"' 'pin selected version'
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Apps"' 'back to apps'
archphene_wait_ui \
  "content-desc=\"Pinned to $escaped_selected\\. glibc-[^\"]*\"" \
  version-pinned-list
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="KCalc"' 'pinned KCalc'
archphene_wait_ui 'text="Pin selected version"' version-pinned-detail
if ((version_count > 1)); then
  archphene_wait_ui \
    "content-desc=\"$escaped_current, newer version available\"" \
    newer-version-indicator
fi
archphene_regex_contains "$ARCHPHENE_UI" \
  'text="Pin selected version"[^>]*checked="true"' \
  || archphene_die 'pin switch did not remain enabled'
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Pin selected version"[^>]*checked="true"' 'enabled pin switch'
archphene_wait_ui \
  'text="Pin selected version"[^>]*checked="false"' version-unpinned-final

restore
trap - EXIT
archphene_note "Version selector passed on $serial ($lane, $version_count versions): selected $selected_version, pin/list state, and exact manager state restoration."
