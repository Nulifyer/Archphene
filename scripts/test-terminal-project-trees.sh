#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
terminal_apk=
alias_name="archphene-test-$$"
folder="archphene-project-tree-test-$$"
skip_install=false
preserve_app_data=true
fixture_owned=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --terminal-apk) terminal_apk="${2:?}"; shift 2 ;;
    --alias) alias_name="${2:?}"; shift 2 ;;
    --folder) folder="${2:?}"; shift 2 ;;
    --skip-install) skip_install=true; shift ;;
    --clean-data) preserve_app_data=false; shift ;;
    # Retained as a compatibility alias now that preservation is the default.
    --preserve-app-data) preserve_app_data=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--skip-install] [--clean-data] [--alias NAME] [--folder NAME]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ "$alias_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] \
  || archphene_die '--alias must be a safe 1-64 character project alias'
[[ "$folder" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] \
  || archphene_die '--folder must be a safe 1-64 character directory name'

archphene_test_init "$serial"
package=org.archpheneos.terminal
terminal_apk="${terminal_apk:-$ARCHPHENE_ROOT/prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk}"
remote="/sdcard/Download/$folder"
local_root="files/terminal/home/Projects/$alias_name"
state_root="files/terminal/project-state/$alias_name"
ui_name=archphene-project-ui

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$terminal_apk"
  archphene_adb_run install -r "$terminal_apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null 2>&1 \
  || archphene_die 'Terminal is not installed'

get_ui() {
  archphene_adb_run shell rm -f "/sdcard/$ui_name.xml" >/dev/null 2>&1 || true
  archphene_capture_ui "$ui_name" 2>/dev/null || true
}

tap_pattern() {
  local pattern="$1" description="$2" seconds="${3:-15}"
  local deadline=$((SECONDS + seconds)) ui
  while ((SECONDS < deadline)); do
    ui="$(get_ui)"
    if archphene_regex_contains "$ui" "$pattern"; then
      archphene_tap_ui_pattern "$ui" "$pattern" "$description"
      sleep .8
      return 0
    fi
    sleep .5
  done
  archphene_die "could not find $description"
}

tap_exact_text() {
  local value="$1" description="$2" resource_id="${3:-}" escaped pattern
  escaped="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$value")"
  pattern="text=\"$escaped\""
  if [[ -n "$resource_id" ]]; then
    resource_id="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$resource_id")"
    pattern+="[^>]*resource-id=\"$resource_id\""
  fi
  tap_pattern "$pattern" "$description"
}

dismiss_startup_dialogs() {
  local ui iteration
  for iteration in 0 1 2 3; do
    ui="$(get_ui)"
    if archphene_regex_contains "$ui" \
        'text="OK"[^>]*package="android"[^>]*clickable="true"'; then
      archphene_tap_ui_pattern "$ui" \
        'text="OK"[^>]*package="android"[^>]*clickable="true"' \
        'page-size compatibility notice'
      sleep .8
      continue
    fi
    if archphene_regex_contains "$ui" \
        'resource-id="[^"]*:id/permission_allow_button"'; then
      archphene_tap_ui_pattern "$ui" \
        'resource-id="[^"]*:id/permission_allow_button"' \
        'notification permission'
      sleep .8
      continue
    fi
    break
  done
}

send_terminal_command() {
  local command="$1"
  archphene_adb_run shell input text "${command// /%s}" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
}

read_local() {
  archphene_adb_run shell run-as "$package" cat "$local_root/$1" 2>/dev/null || true
}

read_remote() {
  archphene_adb_run shell cat "$remote/$1" 2>/dev/null || true
}

active_uri_permissions() {
  archphene_adb_run shell dumpsys activity permissions \
    | sed '/Uri Permission History:/,$d'
}

assert_equal() {
  local expected="$1" actual="$2" description="$3"
  [[ "$actual" == "$expected" ]] \
    || archphene_die "$description mismatch: expected '$expected', got '$actual'"
}

wait_for_value() {
  local expected="$1" reader="$2" path="$3" description="$4" seconds="${5:-20}"
  local deadline=$((SECONDS + seconds)) actual
  while ((SECONDS < deadline)); do
    actual="$($reader "$path")"
    [[ "$actual" == "$expected" ]] && return 0
    sleep .5
  done
  archphene_die "timed out waiting for $description"
}

conflict_files() {
  archphene_adb_run shell run-as "$package" sh -c \
    "'ls $local_root/android.txt.android-conflict-* 2>/dev/null'" || true
}

conflict_count() {
  local matches
  matches="$(conflict_files)"
  grep -c . <<<"$matches" || true
}

select_project_folder() {
  local ui device_root
  tap_pattern 'content-desc="Show roots"' 'document roots button'
  ui="$(get_ui)"
  if archphene_regex_contains "$ui" \
      'text="Downloads"[^>]*resource-id="android:id/title"'; then
    tap_exact_text Downloads 'Android Downloads root' android:id/title
  else
    device_root="$(python3 -c '
import sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    raise SystemExit(1)
excluded = {"Recent", "Images", "Videos", "Audio", "Downloads",
            "Archphene Apps", "Archphene Home"}
for node in root.iter("node"):
    title = node.attrib.get("text", "")
    if (node.attrib.get("resource-id") == "android:id/title"
            and title and title not in excluded):
        print(title)
        break
' <<<"$ui")"
    [[ -n "$device_root" ]] || archphene_die 'could not discover Android storage root'
    tap_exact_text "$device_root" 'Android device storage root' android:id/title
    tap_exact_text Download 'Android Download directory' android:id/title
  fi
  tap_exact_text "$folder" 'project test folder'
  tap_pattern '(?i)text="use this folder"' 'tree selection button'
  tap_pattern '(?i)text="allow"' 'tree permission confirmation'
}

cleanup() {
  if [[ "$preserve_app_data" == true && "$fixture_owned" == true ]]; then
    local preferences deadline
    preferences="$(archphene_adb_run shell run-as "$package" cat \
      shared_prefs/archphene-terminal-projects-v1.xml 2>/dev/null || true)"
    if [[ "$preferences" == *"project.$alias_name"* ]]; then
      archphene_adb_run shell am force-stop com.google.android.documentsui \
        >/dev/null 2>&1 || true
      archphene_adb_run shell am start -W -n "$package/.TerminalActivity" \
        >/dev/null 2>&1 || true
      sleep 1
      send_terminal_command "archphene-project remove $alias_name" \
        >/dev/null 2>&1 || true
      deadline=$((SECONDS + 10))
      while ((SECONDS < deadline)); do
        preferences="$(archphene_adb_run shell run-as "$package" cat \
          shared_prefs/archphene-terminal-projects-v1.xml 2>/dev/null || true)"
        [[ "$preferences" != *"project.$alias_name"* ]] && break
        sleep .5
      done
    fi
    archphene_adb_run shell run-as "$package" rm -rf "$local_root" "$state_root" \
      >/dev/null 2>&1 || true
    archphene_adb_run shell rm -rf "$remote" >/dev/null 2>&1 || true
  elif [[ "$preserve_app_data" == false ]]; then
    archphene_adb_run shell pm clear "$package" >/dev/null 2>&1 || true
    archphene_adb_run shell rm -rf "$remote" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$preserve_app_data" == true ]]; then
  preferences="$(
    archphene_adb_run shell run-as "$package" cat \
      shared_prefs/archphene-terminal-projects-v1.xml 2>/dev/null ||
      true
  )"
  [[ "$preferences" != *"project.$alias_name"* ]] ||
    archphene_die "project alias already exists; choose another --alias: $alias_name"
  archphene_adb_run shell run-as "$package" test ! -e "$local_root" ||
    archphene_die "project home already exists; choose another --alias: $alias_name"
  archphene_adb_run shell run-as "$package" test ! -e "$state_root" ||
    archphene_die "project state already exists; choose another --alias: $alias_name"
  archphene_adb_run shell test ! -e "$remote" ||
    archphene_die "Android test folder already exists; choose another --folder: $folder"
fi
fixture_owned=true
if [[ "$preserve_app_data" == false ]]; then
  archphene_adb_run shell pm clear "$package" >/dev/null
fi
archphene_adb_run shell rm -rf "$remote"
archphene_adb_run shell mkdir -p "$remote/sub"
archphene_adb_run shell sh -c "'printf android-initial > $remote/android.txt'"
archphene_adb_run shell sh -c "'printf nested-initial > $remote/sub/nested.txt'"
archphene_adb_run shell am force-stop com.google.android.documentsui >/dev/null 2>&1 || true
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$package/.TerminalActivity" >/dev/null
sleep 2
dismiss_startup_dialogs

send_terminal_command "archphene-project add $alias_name"
select_project_folder
wait_for_value android-initial read_local android.txt 'initial project pull' 30
assert_equal nested-initial "$(read_local sub/nested.txt)" 'nested initial pull'
active_grants="$(active_uri_permissions)"
[[ "$active_grants" == *"$folder"* && "$active_grants" == *"targetPkg=$package"* ]] \
  || archphene_die 'project tree was not retained as an active persisted Terminal grant'

archphene_adb_run shell run-as "$package" sh -c \
  "'printf local-created > $local_root/local.txt; printf local-updated > $local_root/sub/nested.txt'"
send_terminal_command "archphene-project sync $alias_name"
wait_for_value local-created read_remote local.txt 'local project push' 30
assert_equal local-updated "$(read_remote sub/nested.txt)" 'nested local push'

archphene_adb_run shell sh -c "'printf android-updated > $remote/android.txt'"
send_terminal_command "archphene-project sync $alias_name"
wait_for_value android-updated read_local android.txt 'Android project pull' 30

archphene_adb_run shell run-as "$package" sh -c \
  "'printf local-conflict > $local_root/android.txt'"
archphene_adb_run shell sh -c "'printf android-conflict > $remote/android.txt'"
send_terminal_command "archphene-project sync $alias_name"
deadline=$((SECONDS + 30))
while ((SECONDS < deadline)) && (( $(conflict_count) == 0 )); do sleep .5; done
conflicts_before="$(conflict_count)"
((conflicts_before > 0)) || archphene_die 'timed out waiting for conflict copy'
assert_equal local-conflict "$(read_local android.txt)" 'preserved local conflict side'
assert_equal android-conflict "$(read_remote android.txt)" 'preserved Android conflict side'
send_terminal_command "archphene-project sync $alias_name"
sleep 2
conflicts_after="$(conflict_count)"
((conflicts_after == conflicts_before)) \
  || archphene_die 'repeated sync duplicated an already preserved conflict'

archphene_adb_run shell rm -f "$remote/local.txt"
send_terminal_command "archphene-project sync $alias_name"
sleep 2
send_terminal_command "archphene-project sync $alias_name"
sleep 2
assert_equal local-created "$(read_local local.txt)" 'deferred remote deletion local copy'
[[ -z "$(read_remote local.txt)" ]] \
  || archphene_die 'deferred remote deletion was incorrectly recreated'

archphene_adb_run shell run-as "$package" rm -f "$local_root/sub/nested.txt"
send_terminal_command "archphene-project sync $alias_name"
sleep 2
send_terminal_command "archphene-project sync $alias_name"
sleep 2
[[ -z "$(read_local sub/nested.txt)" ]] \
  || archphene_die 'deferred local deletion was incorrectly restored'
assert_equal local-updated "$(read_remote sub/nested.txt)" \
  'deferred local deletion remote copy'

archphene_adb_run shell run-as "$package" ln -s /sdcard "$local_root/escape-link"
send_terminal_command "archphene-project sync $alias_name"
archphene_wait_log 'symbolic links are not supported' 20 'ArchpheneTerminal:I *:S' >/dev/null
archphene_adb_run shell run-as "$package" rm "$local_root/escape-link"

archphene_adb_run shell sh -c "'printf restart-pull > $remote/restart.txt'"
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$package/.TerminalActivity" >/dev/null
sleep 2
send_terminal_command "archphene-project sync $alias_name"
wait_for_value restart-pull read_local restart.txt 'persisted grant after restart' 30
focused="$(archphene_adb_run shell dumpsys window)"
! archphene_regex_contains "$focused" \
    'mCurrentFocus=.*com\.(?:google\.)?android\.documentsui' \
  || archphene_die 'persisted project sync unexpectedly reopened the document picker'

send_terminal_command "archphene-project remove $alias_name"
deadline=$((SECONDS + 20))
while ((SECONDS < deadline)); do
  preferences="$(archphene_adb_run shell run-as "$package" cat \
    shared_prefs/archphene-terminal-projects-v1.xml 2>/dev/null || true)"
  [[ "$preferences" != *"project.$alias_name"* ]] && break
  sleep .5
done
[[ "${preferences:-}" != *"project.$alias_name"* ]] \
  || archphene_die 'project mapping remained after removal'
assert_equal restart-pull "$(read_local restart.txt)" \
  'local mirror retained after mapping removal'
if archphene_adb_run shell run-as "$package" test -e "$state_root"; then
  archphene_die 'private project manifest remained after removal'
fi
deadline=$((SECONDS + 20))
while ((SECONDS < deadline)); do
  active_grants="$(active_uri_permissions)"
  [[ "$active_grants" != *"$folder"* ]] && break
  sleep .5
done
[[ "${active_grants:-}" != *"$folder"* ]] \
  || archphene_die 'persisted Android tree grant remained after mapping removal'
send_terminal_command "archphene-project sync $alias_name"
archphene_wait_log "unknown project: $alias_name" 20 'ArchpheneTerminal:I *:S' >/dev/null

archphene_note "Terminal persisted project-tree mapping and guarded sync passed on $serial: SAF selection, nested bidirectional sync, conflict idempotence, deferred deletion, symlink rejection, restart persistence, and removal."
