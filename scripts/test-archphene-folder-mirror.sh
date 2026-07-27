#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.FolderGrantTestReceiver"
token="$(printf '%08x' "$((RANDOM * 65536 + RANDOM))")"
folder="Archphene-Mirror-$token"
remote="/sdcard/Download/$folder"
action_prepare=org.archphene.app.debug.action.PREPARE_FOLDER_MIRROR
action_verify=org.archphene.app.debug.action.VERIFY_FOLDER_MIRROR
action_verify_absent=org.archphene.app.debug.action.VERIFY_FOLDER_MIRROR_ABSENT
action_clean_mirror=org.archphene.app.debug.action.CLEAN_FOLDER_MIRROR
action_clean_grant=org.archphene.app.debug.action.CLEAN_FOLDER_GRANT
action_hold_sync=org.archphene.app.debug.action.HOLD_PROJECT_SYNC
output_dir="$ARCHPHENE_ROOT/tooling/build/folder-mirror"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action_clean_grant" \
    --es token "$token" >/dev/null 2>&1 || true
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action_clean_mirror" \
    --es token "$token" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -r "$remote" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_receiver() {
  local action="$1" label="$2"
  archphene_adb_run logcat -c
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action" \
    --es token "$token" >/dev/null
  archphene_wait_log \
    "Folder grant $action passed token=$token" \
    15 'ArchpheneFolderGrantTest:V AndroidRuntime:E *:S' >/dev/null
  archphene_note "  $label"
}

select_folder() {
  local ui
  archphene_wait_ui 'package="com\.(google\.)?android\.documentsui"' \
    "folder-mirror-picker-$serial" 20
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
        "$ui" "folder-mirror-root-$serial"
      archphene_wait_ui_exact_text "$folder" "folder-mirror-download-$serial" 15
      archphene_tap_text "$ARCHPHENE_UI" "$folder"
    fi
    archphene_wait_ui \
      'text="(?:USE THIS FOLDER|Use this folder)"[^>]*enabled="true"' \
      "folder-mirror-selected-$serial" 15
    ui="$ARCHPHENE_UI"
  fi
  archphene_tap_ui_pattern \
    "$ui" 'text="(?:USE THIS FOLDER|Use this folder)"' "Use this folder"
  archphene_wait_ui 'text="(?:ALLOW|Allow)"' \
    "folder-mirror-confirm-$serial" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:ALLOW|Allow)"' "Allow"
}

archphene_adb_run shell mkdir "$remote" "$remote/src" "$remote/.git" >/dev/null
archphene_adb_run shell sh -c \
  "'printf root-$token > $remote/main.txt; printf nested-$token > $remote/src/nested.txt; printf git-$token > $remote/.git/config; : > $remote/empty.bin; truncate -s 134217728 $remote/cancel.bin'"
archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am force-stop com.google.android.documentsui >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop com.android.documentsui >/dev/null 2>&1 || true
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null
wait_receiver "$action_prepare" "Stale native staging fixture prepared"

archphene_open_manager_section Files "folder-mirror-files-$serial"
archphene_wait_ui 'text="(?:CONNECT|CHANGE|Connect|Change)"' \
  "folder-mirror-connect-$serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:CONNECT|CHANGE|Connect|Change)"' "Connect folder"
select_folder
archphene_wait_ui_unwrapped \
  "Android folder: $folder.*read/write" \
  "folder-mirror-connected-$serial" 25

archphene_wait_ui 'text="(?:MIRROR|Mirror)"[^>]*enabled="true"' \
  "folder-mirror-action-$serial" 20
mirror_ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$mirror_ui" 'text="(?:MIRROR|Mirror)"[^>]*enabled="true"' "Mirror folder"
# The operation may complete between UI-automation polls on fast storage.
# The action remains enabled and switches semantics synchronously, so tap the
# same verified control bounds immediately to exercise cancellation reliably.
archphene_tap_ui_pattern \
  "$mirror_ui" 'text="(?:MIRROR|Mirror)"[^>]*enabled="true"' "Cancel mirror"
archphene_wait_ui_exact_text \
  "Project mirror cancelled" \
  "folder-mirror-cancelled-$serial" 20
wait_receiver "$action_verify_absent" "Cancelled mirror staging discarded"
archphene_adb_run shell rm "$remote/cancel.bin" >/dev/null

archphene_wait_ui 'text="(?:MIRROR|Mirror)"[^>]*enabled="true"' \
  "folder-mirror-retry-$serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:MIRROR|Mirror)"[^>]*enabled="true"' "Retry mirror"
archphene_wait_ui_unwrapped \
  "Linux: ~/Projects/$folder" \
  "folder-mirror-complete-$serial" 40
wait_receiver "$action_verify" "Exact recursive mirror content verified"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-connected.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_open_manager_section Files "folder-mirror-files-restart-$serial"
archphene_wait_ui_unwrapped \
  "Linux: ~/Projects/$folder" \
  "folder-mirror-restart-$serial" 25
archphene_wait_ui 'text="(?:SYNC|Sync)"[^>]*enabled="true"' \
  "folder-mirror-sync-$serial" 15
wait_receiver "$action_verify" "Mirror state survived manager restart"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:SYNC|Sync)"[^>]*enabled="true"' "Sync folder"
archphene_wait_ui_unwrapped \
  "Synced 0 change\\(s\\): 0 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-mirror-noop-sync-$serial" 30
wait_receiver "$action_verify" "No-op sync retained exact baseline"

sync_button() {
  archphene_wait_ui 'text="(?:SYNC|Sync)"[^>]*enabled="true"' \
    "folder-sync-action-$serial" 20
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="(?:SYNC|Sync)"[^>]*enabled="true"' "Sync folder"
}

set_sync_hold() {
  local phase="$1"
  archphene_adb_run logcat -c
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action_hold_sync" \
    --es token "$token" --es phase "$phase" --el holdMillis 20000 >/dev/null
  archphene_wait_log \
    "Folder grant $action_hold_sync passed token=$token" \
    15 'ArchpheneFolderGrantTest:V AndroidRuntime:E *:S' >/dev/null
}

restart_manager_files() {
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null
  archphene_open_manager_section Files "folder-sync-restart-files-$serial"
  archphene_wait_ui 'text="(?:SYNC|Sync)"[^>]*enabled="true"' \
    "folder-sync-restart-ready-$serial" 20
}

local_project="files/arch-root/home/archphene/Projects/$folder"
archphene_adb_run shell sh -c \
  "'printf android-edit-$token > $remote/main.txt; printf android-new-$token > $remote/android-new.txt'"
archphene_adb_run shell run-as "$package" sh -c \
  "'printf linux-edit-$token > $local_project/src/nested.txt; printf linux-new-$token > $local_project/linux-new.txt'"
sync_button
archphene_wait_ui_unwrapped \
  "Synced 4 change\\(s\\): 2 pulled, 2 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-bidirectional-$serial" 40
[[ "$(archphene_adb_run shell cat "$remote/src/nested.txt" | tr -d '\r')" == \
  "linux-edit-$token" ]] || archphene_die "Linux edit was not pushed to Android"
[[ "$(archphene_adb_run shell cat "$remote/linux-new.txt" | tr -d '\r')" == \
  "linux-new-$token" ]] || archphene_die "new Linux file was not pushed to Android"
[[ "$(archphene_adb_run shell run-as "$package" cat "$local_project/main.txt" | tr -d '\r')" == \
  "android-edit-$token" ]] || archphene_die "Android edit was not pulled to Linux"
[[ "$(archphene_adb_run shell run-as "$package" cat "$local_project/android-new.txt" | tr -d '\r')" == \
  "android-new-$token" ]] || archphene_die "new Android file was not pulled to Linux"
archphene_note "  Exact bidirectional file edits and additions synchronized"

archphene_adb_run shell rm "$remote/android-new.txt"
sync_button
archphene_wait_ui_unwrapped \
  "Synced 1 change\\(s\\): 1 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-android-delete-$serial" 30
archphene_adb_run shell run-as "$package" test ! -e "$local_project/android-new.txt" ||
  archphene_die "Android deletion was not propagated to Linux"
archphene_note "  Android deletion propagated after exact baseline revalidation"

archphene_adb_run shell run-as "$package" rm "$local_project/linux-new.txt"
set_sync_hold backed-up
archphene_adb_run logcat -c
sync_button
archphene_wait_log \
  'Project sync test holding phase=backed-up' \
  25 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell am force-stop "$package"
restart_manager_files
sync_button
archphene_wait_ui_unwrapped \
  "Synced 1 change\\(s\\): 0 pulled, 1 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-linux-delete-recovered-$serial" 40
archphene_adb_run shell test ! -e "$remote/linux-new.txt" ||
  archphene_die "Linux deletion was not committed to Android"
archphene_adb_run shell run-as "$package" test ! -e files/project-sync-journal-v1 ||
  archphene_die "pre-commit synchronization journal remains"
archphene_note "  Pre-commit process death restored then safely recommitted Android deletion"

archphene_adb_run shell run-as "$package" rm "$local_project/src/nested.txt"
set_sync_hold committed
archphene_adb_run logcat -c
sync_button
archphene_wait_log \
  'Project sync test holding phase=committed' \
  25 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell am force-stop "$package"
restart_manager_files
sync_button
archphene_wait_ui_unwrapped \
  "Synced 0 change\\(s\\): 0 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-postcommit-recovered-$serial" 40
archphene_adb_run shell test ! -e "$remote/src/nested.txt" ||
  archphene_die "committed Android deletion backup was restored"
archphene_adb_run shell run-as "$package" test ! -e files/project-sync-journal-v1 ||
  archphene_die "post-commit synchronization journal remains"
archphene_note "  Post-commit process death finalized backup deletion without resurrection"

archphene_adb_run shell sh -c "'printf android-conflict-$token > $remote/main.txt'"
archphene_adb_run shell run-as "$package" sh -c \
  "'printf linux-conflict-$token > $local_project/main.txt'"
sync_button
archphene_wait_ui_unwrapped \
  "Synced 0 change\\(s\\): 0 pulled, 0 pushed, 1 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-conflict-$serial" 30
conflict_files="$(
  archphene_adb_run shell run-as "$package" sh -c \
    "'ls $local_project/main.txt.android-conflict-* 2>/dev/null'" |
    tr -d '\r'
)"
[[ "$(printf '%s\n' "$conflict_files" | sed '/^$/d' | wc -l)" == 1 ]] ||
  archphene_die "expected one deterministic Android conflict copy"
[[ "$(archphene_adb_run shell run-as "$package" cat "$conflict_files" | tr -d '\r')" == \
  "android-conflict-$token" ]] || archphene_die "Android conflict copy differs"
[[ "$(archphene_adb_run shell run-as "$package" cat "$local_project/main.txt" | tr -d '\r')" == \
  "linux-conflict-$token" ]] || archphene_die "Linux conflict side was overwritten"
[[ "$(archphene_adb_run shell cat "$remote/main.txt" | tr -d '\r')" == \
  "android-conflict-$token" ]] || archphene_die "Android conflict side was overwritten"
archphene_note "  Simultaneous edit retained both exact versions"

archphene_wait_ui 'text="(?:REMOVE|Remove)"[^>]*enabled="true"' \
  "folder-mirror-remove-$serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:REMOVE|Remove)"[^>]*enabled="true"' "Remove folder"
archphene_wait_ui_exact_text \
  "No Android folder connected" \
  "folder-mirror-removed-$serial" 20
archphene_adb_run shell run-as "$package" test -d "$local_project" ||
  archphene_die "local Linux project was removed with the Android grant"
[[ "$(archphene_adb_run shell run-as "$package" cat "$local_project/main.txt" | tr -d '\r')" == \
  "linux-conflict-$token" ]] || archphene_die "retained Linux project changed after grant removal"
archphene_note "  Local Linux mirror retained after grant removal"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-detached.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Folder mirror emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene initial folder mirror passed on $serial"
archphene_note "  Cancel/retry, recursive snapshot, stable baseline, bidirectional additions/edits/deletes, recoverable Android mutation, conflict preservation, restart, and retained local project passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{connected,detached}.png"
