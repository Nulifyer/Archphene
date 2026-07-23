#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--serial SERIAL]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
manager=org.archpheneos.manager
initial_filter='All apps'
changed_filter=false

filter_choice() {
  python3 -c '
import re, sys
match = re.search(r"text=\"(All apps|Updates available|Pinned versions)\"",
                  sys.stdin.read())
print(match.group(1) if match else "")
' <<<"$1"
}

select_filter_in_dialog() {
  local ui="$1" choice="$2" current
  current="$(filter_choice "$ui")"
  [[ -n "$current" ]] || archphene_die "could not identify current app filter"
  if [[ "$current" != "$choice" ]]; then
    archphene_tap_text "$ui" "$current"
    archphene_wait_ui "text=\"$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' \
      "$choice")\"" update-filter-options 10
    archphene_tap_text "$ARCHPHENE_UI" "$choice"
  fi
}

restore_filter() {
  [[ "$changed_filter" == true ]] || return 0
  set +e
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" >/dev/null 2>&1
  archphene_wait_ui 'content-desc="Filter and sort apps"' update-filter-cleanup 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'content-desc="Filter and sort apps"' 'filter and sort apps'
  archphene_wait_ui 'text="Filter and sorting"' update-filter-cleanup-dialog 10
  select_filter_in_dialog "$ARCHPHENE_UI" "$initial_filter"
  archphene_wait_ui 'text="APPLY"' update-filter-cleanup-apply 10
  archphene_tap_text "$ARCHPHENE_UI" APPLY
}
trap restore_filter EXIT

archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -S -W -n "$manager/.MainActivity" >/dev/null
archphene_wait_ui 'content-desc="Filter and sort apps"' update-home 20
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern "$ui" \
  'content-desc="Filter and sort apps"' 'filter and sort apps'
archphene_wait_ui 'text="Filter and sorting"' update-filter-dialog 10
ui="$ARCHPHENE_UI"
initial_filter="$(filter_choice "$ui")"
[[ -n "$initial_filter" ]] || archphene_die "could not preserve current app filter"
if [[ "$initial_filter" != 'All apps' ]]; then
  select_filter_in_dialog "$ui" 'All apps'
  archphene_wait_ui 'text="APPLY"' update-filter-apply 10
  archphene_tap_text "$ARCHPHENE_UI" APPLY
  changed_filter=true
else
  archphene_tap_text "$ui" CANCEL
fi

archphene_wait_ui 'class="android\.widget\.EditText"' update-search 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'class="android\.widget\.EditText"' 'app search field'
archphene_adb_run shell input text kcalc
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_wait_ui 'text="KCalc"' update-kcalc 15
ui="$ARCHPHENE_UI"
pattern='content-desc="(?:Check KCalc for updates\.[^"]*|KCalc [^"]+\. Check again)"'
archphene_tap_ui_pattern "$ui" "$pattern" "KCalc update-check control"

deadline=$((SECONDS + 60))
result=
while ((SECONDS < deadline)); do
  sleep 1
  result="$(archphene_capture_ui archphene-update-after)"
  [[ "$result" == *'content-desc="KCalc 26.04.3-1 is up to date. Check again"'* ]] && break
done
[[ "$result" == *'content-desc="KCalc 26.04.3-1 is up to date. Check again"'* ]] \
  || archphene_die "configured repository comparison did not return KCalc 26.04.3-1"
archphene_note "Manager verified KCalc 26.04.3-1 is current via the configured signed repository metadata on $serial."
