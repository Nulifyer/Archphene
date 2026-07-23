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
backup=cache/pull-refresh-state.xml
archphene_adb_run shell am force-stop "$package"
had_preferences=false
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
archphene_wait_ui 'content-desc="Filter and sort apps"' pull-refresh-home
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern "$ui" \
  'content-desc="Filter and sort apps"' 'filter and sort'
archphene_wait_ui 'text="Filter and sorting"' pull-refresh-filter
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern "$ui" \
  'text="(?:All apps|Updates available|Pinned versions)"' 'current filter'
archphene_wait_ui 'text="All apps"' pull-refresh-filter-options
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="All apps"' 'all apps'
archphene_wait_ui 'text="APPLY"' pull-refresh-filter-apply
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="APPLY"' apply
archphene_wait_ui 'text="KCalc"' pull-refresh-all-apps
before="$ARCHPHENE_UI"

! archphene_regex_contains "$before" \
  'text="Check all"|text="Check package updates"' \
  || archphene_die 'persistent check-all controls remain'
bounds_of() {
  python3 -c '
import re, sys
match = re.search(r"text=\"KCalc\"[^>]*bounds=\"([^\"]+)\"",
                  sys.stdin.read())
print(match.group(1) if match else "")
' <<<"$1"
}
before_bounds="$(bounds_of "$before")"
[[ -n "$before_bounds" ]] || archphene_die 'could not locate KCalc'

archphene_adb_run shell input swipe 540 900 540 1020 250
sleep 1
short_pull="$(archphene_capture_ui manager-pull-short)"
[[ "$short_pull" != *'Checking KCalc for updates'* \
  && "$(bounds_of "$short_pull")" == "$before_bounds" ]] \
  || archphene_die 'below-threshold pull triggered or displaced the list'

archphene_adb_run shell input swipe 540 900 540 1900 700
deadline=$((SECONDS + 30))
after=
while ((SECONDS < deadline)); do
  sleep 1
  after="$(archphene_capture_ui manager-pull-after)"
  if [[ "$after" == *'KCalc 26.04.3-1 is up to date'* \
      && "$after" == *'Mousepad 0.7.0-1 is up to date'* ]]; then
    break
  fi
done
[[ "$after" == *'KCalc 26.04.3-1 is up to date'* \
  && "$after" == *'Mousepad 0.7.0-1 is up to date'* ]] \
  || archphene_die 'pull-to-refresh did not check every installed Linux app'
[[ "$(bounds_of "$after")" == "$before_bounds" ]] \
  || archphene_die 'app list did not settle at its original position'

restore
trap - EXIT
archphene_note "Linux manager pull-to-refresh passed on $serial: threshold, batch completion, settled position, and original manager state restored."
