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
query='bc'
state_preferences=shared_prefs/linux-app-manager-state.xml
tracked_preferences=shared_prefs/linux-app-manager-tracked.xml
state_backup=cache/repository-search-state.xml
tracked_backup=cache/repository-search-tracked.xml
had_state=false
had_tracked=false
archphene_adb_run shell am force-stop "$package"
if archphene_adb_run shell run-as "$package" \
    cp "$state_preferences" "$state_backup" >/dev/null 2>&1; then
  had_state=true
fi
if archphene_adb_run shell run-as "$package" \
    cp "$tracked_preferences" "$tracked_backup" >/dev/null 2>&1; then
  had_tracked=true
fi
restore() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$had_state" == true ]]; then
    archphene_adb_run shell run-as "$package" \
      cp "$state_backup" "$state_preferences" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell run-as "$package" \
      rm -f "$state_preferences" >/dev/null 2>&1 || true
  fi
  if [[ "$had_tracked" == true ]]; then
    archphene_adb_run shell run-as "$package" \
      cp "$tracked_backup" "$tracked_preferences" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell run-as "$package" \
      rm -f "$tracked_preferences" >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell run-as "$package" \
    rm -f "$state_backup" "$tracked_backup" >/dev/null 2>&1 || true
  archphene_adb_run shell am start -W -n "$package/.MainActivity" \
    >/dev/null 2>&1 || true
}
trap restore EXIT

archphene_adb_run shell am start -W -n "$package/.MainActivity" >/dev/null
archphene_wait_ui 'content-desc="Filter and sort apps"' repo-filter-home
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Filter and sort apps"' filter
archphene_wait_ui 'text="Filter and sorting"' repo-filter-dialog
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="(?:All apps|Updates available|Pinned versions)"' current-filter
archphene_wait_ui 'text="All apps"' repo-filter-options
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="All apps"' all-apps
archphene_wait_ui 'text="APPLY"' repo-filter-apply
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="APPLY"' apply
archphene_wait_ui 'content-desc="Add Linux app"' repo-all-apps
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Add Linux app"' 'Add Linux app'

archphene_wait_ui 'text="Search official Arch packages"' repo-add
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Search official Arch packages"' search-field
archphene_adb_run shell input text "$query"
sleep 1
ui="$(archphene_capture_ui repo-query)"
if [[ "$ui" == *'text="Try out your stylus"'* \
    && "$ui" == *'text="Cancel"'* ]]; then
  archphene_tap_ui_pattern "$ui" 'text="Cancel"' \
    'Gboard stylus education Cancel'
  sleep 1
fi
archphene_adb_run shell input keyevent KEYCODE_BACK
sleep 1
ui="$(archphene_capture_ui repo-query-stable)"
archphene_tap_ui_pattern "$ui" \
  'content-desc="Search package repositories"' search-button
archphene_wait_ui 'text="bc  [^"]+"' repo-results 45
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="bc  [^"]+"' bc-result

archphene_wait_ui 'text="Add to apps"' repo-detail
for expected in \
    'text="Repository"' \
    'text="Architecture"' \
    'text="Wrapper: built and signed on this device"'; do
  archphene_regex_contains "$ARCHPHENE_UI" "$expected" \
    || archphene_die "repository detail is missing $expected"
done
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Add to apps"' track
archphene_wait_ui 'text="Search apps"' repo-tracked-home
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Search apps"' app-search
archphene_adb_run shell input text "$query"
sleep 1
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_wait_ui \
  'text="bc"[^>]*class="android\.widget\.TextView"' repo-tracked
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="bc"[^>]*class="android\.widget\.TextView"' tracked
archphene_wait_ui 'text="Remove from apps"' repo-remove
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Remove from apps"' remove
archphene_wait_ui 'content-desc="Add Linux app"' repo-removed

restore
trap - EXIT
archphene_note "Official Arch package search and tracked-package workflow passed on $serial with exact manager state restored."
