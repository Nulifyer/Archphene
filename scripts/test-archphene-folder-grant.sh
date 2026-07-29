#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.FolderGrantTestReceiver"
token="$(printf '%08x' "$((RANDOM * 65536 + RANDOM))")"
first_folder="Archphene-Grant-$token-A"
second_folder="Archphene-Grant-$token-B"
first_path="/sdcard/Download/$first_folder"
second_path="/sdcard/Download/$second_folder"
action_verify=org.archphene.app.debug.action.VERIFY_FOLDER_GRANT
action_verify_read_only=org.archphene.app.debug.action.VERIFY_FOLDER_GRANT_READ_ONLY
action_verify_absent=org.archphene.app.debug.action.VERIFY_FOLDER_GRANT_ABSENT
action_downgrade=org.archphene.app.debug.action.DOWNGRADE_FOLDER_GRANT
action_revoke=org.archphene.app.debug.action.REVOKE_FOLDER_GRANT
action_clean=org.archphene.app.debug.action.CLEAN_FOLDER_GRANT
output_dir="$ARCHPHENE_ROOT/tooling/build/folder-grant"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "folder-grant-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

cleanup() {
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action_clean" \
    --es token "$token" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rmdir "$first_path" >/dev/null 2>&1 || true
  archphene_adb_run shell rmdir "$second_path" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$initial_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    restore_section || true
  fi
}
trap cleanup EXIT

select_folder() {
  local folder="$1" phase="$2" ui
  archphene_wait_ui 'package="com\.(google\.)?android\.documentsui"' \
    "folder-grant-picker-$phase-$serial" 20
  ui="$ARCHPHENE_UI"
  if ! {
    archphene_regex_contains "$ui" "text=\"$folder\"" &&
      archphene_regex_contains \
        "$ui" 'text="(?:USE THIS FOLDER|Use this folder)"[^>]*enabled="true"'
  }; then
    if archphene_regex_contains "$ui" "text=\"$folder\""; then
      archphene_tap_text "$ui" "$folder"
    else
      archphene_open_documents_download_root \
        "$ui" "folder-grant-root-$phase-$serial"
      archphene_wait_ui_exact_text "$folder" "folder-grant-download-$phase-$serial" 15
      archphene_tap_text "$ARCHPHENE_UI" "$folder"
    fi
    archphene_wait_ui \
      'text="(?:USE THIS FOLDER|Use this folder)"[^>]*enabled="true"' \
      "folder-grant-selected-$phase-$serial" 15
    ui="$ARCHPHENE_UI"
  fi
  archphene_tap_ui_pattern \
    "$ui" 'text="(?:USE THIS FOLDER|Use this folder)"' "Use this folder"
  archphene_wait_ui 'text="(?:ALLOW|Allow)"' \
    "folder-grant-confirm-$phase-$serial" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:ALLOW|Allow)"' "Allow"
}

tap_folder_action() {
  local phase="$1"
  archphene_open_manager_section Files "folder-grant-files-$phase-$serial"
  archphene_wait_ui 'text="(?:CONNECT|CHANGE|Connect|Change)"' \
    "folder-grant-action-$phase-$serial" 20
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="(?:CONNECT|CHANGE|Connect|Change)"' "folder action"
}

verify_grant() {
  local phase="$1"
  archphene_adb_run logcat -c
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify" \
    --es token "$token" >/dev/null
  archphene_wait_log \
    "Folder grant $action_verify passed token=$token" \
    15 'ArchpheneFolderGrantTest:V AndroidRuntime:E *:S' >/dev/null
  archphene_note "  Persisted read/write grant verified: $phase"
}

archphene_adb_run shell mkdir "$first_path" >/dev/null
archphene_adb_run shell mkdir "$second_path" >/dev/null
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am force-stop com.google.android.documentsui >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop com.android.documentsui >/dev/null 2>&1 || true
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null

initial_ui="$(archphene_capture_ui "folder-grant-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "folder-grant-onboarding-$serial"
  initial_ui="$ARCHPHENE_UI"
fi
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$initial_ui"
)"
archphene_open_manager_section Files "folder-grant-preflight-files-$serial"
archphene_wait_ui_exact_text \
  "No Android folder connected" "folder-grant-preflight-empty-$serial" 20

tap_folder_action first
select_folder "$first_folder" first
archphene_wait_ui_unwrapped \
  "Android folder: $first_folder.*read/write" \
  "folder-grant-first-$serial" 25
verify_grant first

tap_folder_action replacement
select_folder "$second_folder" replacement
archphene_wait_ui_unwrapped \
  "Android folder: $second_folder.*read/write" \
  "folder-grant-replacement-$serial" 25
verify_grant replacement

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_open_manager_section Files "folder-grant-files-restart-$serial"
archphene_wait_ui_unwrapped \
  "Android folder: $second_folder.*read/write" \
  "folder-grant-restart-$serial" 25
verify_grant restart
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-connected.png"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_revoke" \
  --es token "$token" >/dev/null
archphene_wait_log \
  "Folder grant $action_revoke passed token=$token" \
  15 'ArchpheneFolderGrantTest:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_open_manager_section Files "folder-grant-files-revoked-$serial"
archphene_wait_ui_unwrapped \
  "Access to $second_folder was revoked.*Connect it again" \
  "folder-grant-revoked-$serial" 25
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-revoked.png"

tap_folder_action reconnect
select_folder "$second_folder" reconnect
archphene_wait_ui_unwrapped \
  "Android folder: $second_folder.*read/write" \
  "folder-grant-reconnected-$serial" 25
verify_grant reconnect

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_downgrade" \
  --es token "$token" >/dev/null
archphene_wait_log \
  "Folder grant $action_downgrade passed token=$token" \
  15 'ArchpheneFolderGrantTest:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_open_manager_section Files "folder-grant-files-read-only-$serial"
archphene_wait_ui_unwrapped \
  "Android folder: $second_folder.*read-only" \
  "folder-grant-read-only-$serial" 25
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify_read_only" \
  --es token "$token" >/dev/null
archphene_wait_log \
  "Folder grant $action_verify_read_only passed token=$token" \
  15 'ArchpheneFolderGrantTest:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-read-only.png"

archphene_wait_ui 'text="(?:REMOVE|Remove)"[^>]*enabled="true"' \
  "folder-grant-disconnect-$serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:REMOVE|Remove)"[^>]*enabled="true"' "Remove folder"
archphene_wait_ui_exact_text \
  "No Android folder connected" \
  "folder-grant-disconnected-$serial" 20
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify_absent" \
  --es token "$token" >/dev/null
archphene_wait_log \
  "Folder grant $action_verify_absent passed token=$token" \
  15 'ArchpheneFolderGrantTest:V AndroidRuntime:E *:S' >/dev/null

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Folder grant emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene folder grant passed on $serial"
archphene_note \
  "  Connect, replace, restart persistence, revocation, reconnect, read-only, and disconnect passed"
archphene_note \
  "  Full-device screenshots: $output_dir/$serial-{connected,revoked,read-only}.png"
