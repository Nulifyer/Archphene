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
kcalc=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
initial_filter=all
initial_background=false
changed_filter=false
changed_background=false

choice_from_ui() {
  python3 -c '
import re, sys
text = sys.stdin.read()
match = re.search(r"text=\"(All apps|Updates available|Pinned versions)\"", text)
print(match.group(1) if match else "")
' <<<"$1"
}

background_from_ui() {
  python3 -c '
import sys
from xml.etree import ElementTree

root = ElementTree.fromstring(sys.stdin.read())
for node in root.iter("node"):
    children = list(node)
    if any(child.attrib.get("text") == "Background update checks"
           for child in node.iter("node")):
        for child in children:
            if child.attrib.get("class") == "android.widget.Switch":
                print(child.attrib.get("checked", "false"))
                raise SystemExit
raise SystemExit("Could not find Background update checks switch")
' <<<"$1"
}

tap_background_switch() {
  local ui="$1" center x y
  center="$(python3 -c '
import re, sys
from xml.etree import ElementTree

root = ElementTree.fromstring(sys.stdin.read())
for node in root.iter("node"):
    descendants = list(node.iter("node"))
    if not any(child.attrib.get("text") == "Background update checks"
               for child in descendants):
        continue
    for child in list(node):
        if child.attrib.get("class") != "android.widget.Switch":
            continue
        values = list(map(int, re.findall(r"\d+", child.attrib["bounds"])))
        print((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
        raise SystemExit
raise SystemExit("Could not find Background update checks switch")
' <<<"$ui")"
  read -r x y <<<"$center"
  archphene_adb_run shell input tap "$x" "$y" >/dev/null
}

select_filter() {
  local ui="$1" choice="$2" tag="$3" current
  current="$(choice_from_ui "$ui")"
  [[ -n "$current" ]] || archphene_die "could not identify the current app filter"
  if [[ "$current" != "$choice" ]]; then
    archphene_tap_text "$ui" "$current"
    archphene_wait_ui_exact_text "$choice" "$tag-options" 10
    archphene_tap_text "$ARCHPHENE_UI" "$choice"
  fi
}

open_filter_dialog() {
  local ui="$1" tag="$2"
  archphene_tap_ui_pattern "$ui" 'content-desc="Filter and sort apps"' \
    "filter and sort apps"
  archphene_wait_ui_exact_text 'Filter and sorting' "$tag-dialog" 10
}

set_filter() {
  local choice="$1" tag="$2" ui
  archphene_wait_ui_exact_text 'Apps' "$tag-apps" 10
  ui="$ARCHPHENE_UI"
  open_filter_dialog "$ui" "$tag"
  ui="$ARCHPHENE_UI"
  select_filter "$ui" "$choice" "$tag"
  archphene_wait_ui_exact_text 'APPLY' "$tag-apply" 10
  archphene_tap_text "$ARCHPHENE_UI" 'APPLY'
}

restore_state() {
  set +e
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" >/dev/null 2>&1
  if [[ "$changed_background" == true ]]; then
    ui="$(archphene_capture_ui manager-obtainium-cleanup 2>/dev/null)"
    if [[ "$ui" != *'text="Background update checks"'* ]]; then
      archphene_tap_text "$ui" Settings >/dev/null 2>&1
      archphene_wait_ui_exact_text 'Background update checks' \
        manager-obtainium-cleanup-settings 10
      ui="$ARCHPHENE_UI"
    fi
    tap_background_switch "$ui" >/dev/null 2>&1
  fi
  if [[ "$changed_filter" == true ]]; then
    ui="$(archphene_capture_ui manager-obtainium-cleanup-filter 2>/dev/null)"
    if [[ "$ui" == *'text="Background update checks"'* ]]; then
      archphene_tap_text "$ui" Apps >/dev/null 2>&1
      archphene_wait_ui_exact_text Apps manager-obtainium-cleanup-apps 10
    fi
    case "$initial_filter" in
      updates) set_filter 'Updates available' manager-obtainium-restore ;;
      pinned) set_filter 'Pinned versions' manager-obtainium-restore ;;
    esac
  fi
}
trap restore_state EXIT

archphene_adb_run shell pm grant "$manager" android.permission.POST_NOTIFICATIONS \
  >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -n "$manager/.MainActivity" >/dev/null
archphene_wait_ui_exact_text Apps manager-obtainium-home 20
ui="$ARCHPHENE_UI"

open_filter_dialog "$ui" manager-obtainium-initial
ui="$ARCHPHENE_UI"
case "$(choice_from_ui "$ui")" in
  'All apps') initial_filter=all ;;
  'Updates available') initial_filter=updates ;;
  'Pinned versions') initial_filter=pinned ;;
  *) archphene_die "could not preserve the initial app filter" ;;
esac
if [[ "$initial_filter" != all ]]; then
  select_filter "$ui" 'All apps' manager-obtainium-reset
  archphene_wait_ui_exact_text APPLY manager-obtainium-reset-apply 10
  archphene_tap_text "$ARCHPHENE_UI" APPLY
  changed_filter=true
else
  archphene_tap_text "$ui" CANCEL
fi

archphene_wait_ui_exact_text KCalc manager-obtainium-all-apps 20
ui="$ARCHPHENE_UI"
for evidence in \
    'text="Search apps"' \
    'text="KCalc"' \
    'text="Mousepad"' \
    'text="Apps"' \
    'text="Settings"' \
    'content-desc="Filter and sort apps"'; do
  archphene_regex_contains "$ui" "$evidence" \
    || archphene_die "manager home is missing: $evidence"
done

if archphene_regex_contains "$ui" 'content-desc="KCalc [^"]* is up to date\. Check again"'; then
  archphene_tap_ui_pattern "$ui" \
    'content-desc="KCalc [^"]* is up to date\. Check again"' 'KCalc update check'
else
  archphene_tap_ui_pattern "$ui" \
    'content-desc="(?:Check KCalc for updates|KCalc update check failed)[^"]*"' \
    'KCalc update check'
fi
archphene_wait_ui 'content-desc="KCalc [^"]* is up to date\. Check again"' \
  manager-obtainium-update 30
checked_description="$(python3 -c '
import re, sys
match = re.search(r"content-desc=\"(KCalc [^\"]* is up to date\. Check again)\"",
                  sys.stdin.read())
print(match.group(1) if match else "")
' <<<"$ARCHPHENE_UI")"
[[ -n "$checked_description" ]] || archphene_die "KCalc update result was not exposed"

archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -n "$manager/.MainActivity" >/dev/null
archphene_wait_ui "content-desc=\"$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' \
  "$checked_description")\"" manager-obtainium-persisted 20
ui="$ARCHPHENE_UI"

archphene_tap_text "$ui" KCalc
archphene_wait_ui_exact_text 'Package source' manager-obtainium-detail 20
detail="$ARCHPHENE_UI"
for evidence in \
    'text="Package source"' \
    'text="extra/kcalc"' \
    'text="Runtime"' \
    "text=\"$kcalc\"" \
    'text="Installed package"' \
    'text="Available"' \
    'text="Install version"' \
    'text="Pin selected version"' \
    'text="Launch"' \
    'text="Check for update"'; do
  archphene_regex_contains "$detail" "$evidence" \
    || archphene_die "package detail is missing: $evidence"
done

archphene_adb_run shell input swipe 540 1700 540 650 500
archphene_wait_ui_exact_text 'Uninstall app' manager-obtainium-detail-actions 10
detail="$ARCHPHENE_UI"
for evidence in 'text="Android app settings"' 'text="Uninstall app"'; do
  archphene_regex_contains "$detail" "$evidence" \
    || archphene_die "package detail action is missing: $evidence"
done
archphene_tap_text "$detail" 'Android app settings'
archphene_wait_ui 'package="com\.android\.settings"' manager-android-app-settings 20
[[ "$ARCHPHENE_UI" == *'text="KCalc"'* ]] \
  || archphene_die 'Android app settings did not open KCalc'
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_wait_ui_exact_text 'Package source' manager-obtainium-return 20
ui="$ARCHPHENE_UI"

archphene_tap_text "$ui" Settings
archphene_wait_ui_exact_text 'Background update checks' manager-obtainium-settings 20
settings="$ARCHPHENE_UI"
initial_background="$(background_from_ui "$settings")"
if [[ "$initial_background" == false ]]; then
  tap_background_switch "$settings"
  changed_background=true
  archphene_wait_ui 'text="Background update checks"' manager-obtainium-enabled 10
  [[ "$(background_from_ui "$ARCHPHENE_UI")" == true ]] \
    || archphene_die "background update switch did not become checked"
fi
jobs="$(archphene_adb_run shell dumpsys jobscheduler)"
archphene_regex_contains "$jobs" \
  'org\.archpheneos\.manager(?:/|\.)(?:LinuxAppManagerService|org\.archpheneos\.manager\.LinuxAppManagerService)' \
  || archphene_die "background update JobService was not scheduled"

archphene_tap_text "$ARCHPHENE_UI" Apps
archphene_wait_ui 'text="Search apps"' manager-obtainium-search 10
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern "$ui" 'text=""[^>]*resource-id=""[^>]*class="android\.widget\.EditText"|class="android\.widget\.EditText"' \
  'app search field'
archphene_adb_run shell input text mousepad
archphene_wait_ui_exact_text Mousepad manager-obtainium-filtered 10
filtered="$ARCHPHENE_UI"
[[ "$filtered" != *'text="KCalc"'* ]] \
  || archphene_die "search did not isolate Mousepad"

archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A
archphene_adb_run shell input keyevent KEYCODE_DEL
archphene_wait_ui_exact_text Archphene manager-obtainium-search-cleared 10
cleared="$ARCHPHENE_UI"
archphene_regex_contains "$cleared" \
  'text="Search apps"[^>]*class="android\.widget\.EditText"|class="android\.widget\.EditText"[^>]*text="Search apps"' \
  || archphene_die "app search query did not clear"
set_filter 'Updates available' manager-obtainium-updates-only
sleep 1
updates_ui="$(archphene_capture_ui manager-obtainium-updates-filter)"
if [[ "$updates_ui" != *'text="No updates available."'* ]]; then
  archphene_regex_contains "$updates_ui" \
    'content-desc="[^"]+(?: update [^"]+ available\. Installed |Newer version [^"]+ available)[^"]*"' \
    || archphene_die "updates-only filter exposed a row without update evidence"
fi
[[ "$updates_ui" != *'text="KCalc"'* && "$updates_ui" != *'text="Mousepad"'* ]] \
  || archphene_die "updates-only filter retained an app verified as current"
set_filter 'All apps' manager-obtainium-all-restored
archphene_wait_ui_exact_text Archphene manager-obtainium-final 10

archphene_note "Manager workflow passed on $serial: update checks persist; detail and Android settings actions work; background jobs schedule; search and update filters are exact."
