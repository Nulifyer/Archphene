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

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "folder-mirror-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

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
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$initial_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    restore_section || true
  fi
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
initial_ui="$(archphene_capture_ui "folder-mirror-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "folder-mirror-onboarding-$serial"
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
archphene_open_manager_section Files "folder-mirror-preflight-files-$serial"
archphene_wait_ui_exact_text \
  "No Android folder connected" "folder-mirror-preflight-empty-$serial" 20
if archphene_adb_run shell test -e "$remote"; then
  archphene_die "refusing to replace pre-existing Android fixture path: $remote"
fi
archphene_adb_run shell mkdir "$remote" "$remote/src" "$remote/.git" >/dev/null
archphene_adb_run shell sh -c \
  "'printf root-$token > $remote/main.txt; printf nested-$token > $remote/src/nested.txt; printf git-$token > $remote/.git/config; : > $remote/empty.bin; truncate -s 134217728 $remote/cancel.bin'"
wait_receiver "$action_prepare" "Stale native staging fixture prepared"

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
archphene_wait_ui 'text="(?:HISTORY|History)"[^>]*enabled="true"' \
  "folder-sync-history-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:HISTORY|History)"[^>]*enabled="true"' "Sync history"
archphene_wait_ui 'text="Project sync history"' \
  "folder-sync-history-dialog-$serial" 15
archphene_regex_contains "$ARCHPHENE_UI" 'text=".*Completed.*Synced 0 change' ||
  archphene_die "project sync history omitted the durable completed result"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-history.png"
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="OK"' "Close sync history"

sync_button() {
  archphene_wait_ui 'text="(?:SYNC|Sync|RETRY|Retry)"[^>]*enabled="true"' \
    "folder-sync-action-$serial" 20
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="(?:SYNC|Sync|RETRY|Retry)"[^>]*enabled="true"' "Sync folder"
}

set_sync_hold() {
  local phase="$1"
  local hold_millis="${2:-20000}"
  archphene_adb_run logcat -c
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action_hold_sync" \
    --es token "$token" --es phase "$phase" --el holdMillis "$hold_millis" >/dev/null
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
  archphene_wait_ui 'text="(?:HISTORY|History)"[^>]*enabled="true"' \
    "folder-sync-restart-history-$serial" 15
}

local_project="files/arch-root/home/archphene/Projects/$folder"
archphene_adb_run shell truncate -s 268435456 "$remote/sync-cancel.bin"
archphene_wait_ui 'text="(?:SYNC|Sync)"[^>]*enabled="true"' \
  "folder-sync-native-cancel-$serial" 20
sync_cancel_ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$sync_cancel_ui" 'text="(?:SYNC|Sync)"[^>]*enabled="true"' "Start large sync"
sleep 0.2
archphene_tap_ui_pattern \
  "$sync_cancel_ui" 'text="(?:SYNC|Sync)"[^>]*enabled="true"' "Cancel large sync"
archphene_wait_ui_exact_text \
  "Project synchronization cancelled" \
  "folder-sync-native-cancelled-$serial" 20
archphene_adb_run shell run-as "$package" test ! -e \
  "$local_project/sync-cancel.bin" ||
  archphene_die "cancelled synchronization published a partial Linux file"
archphene_adb_run shell rm "$remote/sync-cancel.bin"
sync_button
archphene_wait_ui_unwrapped \
  "Synced 0 change\\(s\\): 0 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-native-cancel-clean-$serial" 30

archphene_adb_run shell run-as "$package" truncate -s 268435456 \
  "$local_project/sync-cancel-linux.bin"
archphene_wait_ui 'text="(?:SYNC|Sync)"[^>]*enabled="true"' \
  "folder-sync-native-linux-cancel-$serial" 20
sync_cancel_ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$sync_cancel_ui" 'text="(?:SYNC|Sync)"[^>]*enabled="true"' "Start large Linux sync"
sleep 0.2
archphene_tap_ui_pattern \
  "$sync_cancel_ui" 'text="(?:SYNC|Sync)"[^>]*enabled="true"' "Cancel large Linux sync"
archphene_wait_ui_exact_text \
  "Project synchronization cancelled" \
  "folder-sync-native-linux-cancelled-$serial" 20
archphene_adb_run shell test ! -e "$remote/sync-cancel-linux.bin" ||
  archphene_die "cancelled synchronization published a partial Android file"
archphene_adb_run shell run-as "$package" rm "$local_project/sync-cancel-linux.bin"
sync_button
archphene_wait_ui_unwrapped \
  "Synced 0 change\\(s\\): 0 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-native-linux-cancel-clean-$serial" 30
archphene_note "  Native Android and Linux fingerprint loops cancel without partial publication"

archphene_adb_run shell sh -c \
  "'printf android-edit-$token > $remote/main.txt; printf android-new-$token > $remote/android-new.txt'"
archphene_adb_run shell run-as "$package" sh -c \
  "'printf linux-edit-$token > $local_project/src/nested.txt; printf linux-new-$token > $local_project/linux-new.txt; printf linux-new-two-$token > $local_project/linux-new-two.txt'"
set_sync_hold backed-up 5000
sync_button
archphene_wait_log \
  'Project sync test holding phase=backed-up' \
  20 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
archphene_wait_ui_unwrapped \
  "Pushing to Android [1-5] of 5 · [0-5] pulled · [0-5] pushed · 0 conflict\\(s\\)" \
  "folder-sync-live-progress-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-live-progress.png"
archphene_wait_ui_unwrapped \
  "Synced 5 change\\(s\\): 2 pulled, 3 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-bidirectional-$serial" 40
[[ "$(archphene_adb_run shell cat "$remote/src/nested.txt" | tr -d '\r')" == \
  "linux-edit-$token" ]] || archphene_die "Linux edit was not pushed to Android"
[[ "$(archphene_adb_run shell cat "$remote/linux-new.txt" | tr -d '\r')" == \
  "linux-new-$token" ]] || archphene_die "new Linux file was not pushed to Android"
[[ "$(archphene_adb_run shell cat "$remote/linux-new-two.txt" | tr -d '\r')" == \
  "linux-new-two-$token" ]] || archphene_die "second Linux file was not pushed to Android"
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

archphene_adb_run shell mkdir "$remote/android-directory"
archphene_adb_run shell sh -c \
  "'printf android-directory-$token > $remote/android-directory/inside.txt'"
sync_button
archphene_wait_ui_unwrapped \
  "Synced 2 change\\(s\\): 2 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-android-directory-pull-$serial" 30
archphene_adb_run shell run-as "$package" test -f \
  "$local_project/android-directory/inside.txt" ||
  archphene_die "Android directory was not pulled into Linux"
archphene_adb_run shell rm -r "$remote/android-directory"
sync_button
archphene_wait_ui_unwrapped \
  "Synced 2 change\\(s\\): 2 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-android-directory-delete-$serial" 30
archphene_adb_run shell run-as "$package" test ! -e \
  "$local_project/android-directory" ||
  archphene_die "Android directory deletion was not propagated to Linux"
archphene_note "  Android directory additions and deletions propagated recursively"

for window in live recovery; do
  archphene_adb_run shell mkdir "$remote/delete-window-$window"
  archphene_adb_run shell sh -c \
    "'printf delete-window-$window-$token > $remote/delete-window-$window/source.txt'"
  sync_button
  archphene_wait_ui_unwrapped \
    "Synced 2 change\\(s\\): 2 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
    "folder-sync-delete-window-baseline-$window-$serial" 30
  archphene_adb_run shell run-as "$package" rm \
    "$local_project/delete-window-$window/source.txt"
  set_sync_hold committed 7000
  archphene_adb_run logcat -c
  sync_button
  archphene_wait_log \
    'Project sync test holding phase=committed' \
    15 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
  backup_path="$(
    archphene_adb_run shell sh -c \
      "'ls -1 $remote/delete-window-$window/Archphene-delete-*'" |
      tr -d '\r'
  )"
  [[ -n "$backup_path" && "$backup_path" != *$'\n'* ]] ||
    archphene_die "committed deletion backup was not unique"
  backup_name="${backup_path##*/}"
  archphene_adb_run shell sh -c \
    "'printf changed-after-commit-$window-$token > $backup_path'"
  if [[ "$window" == recovery ]]; then
    archphene_adb_run shell am force-stop "$package"
    restart_manager_files
    sync_button
    expected_changes=1
  else
    expected_changes=2
  fi
  archphene_wait_ui_unwrapped \
    "Synced $expected_changes change\\(s\\): 1 pulled, $((expected_changes - 1)) pushed, 1 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
    "folder-sync-delete-window-retained-$window-$serial" 40
  archphene_adb_run shell test ! -e "$remote/delete-window-$window/source.txt" ||
    archphene_die "deleted source reappeared after its backup changed"
  [[ "$(archphene_adb_run shell cat "$backup_path" | tr -d '\r')" == \
    "changed-after-commit-$window-$token" ]] ||
    archphene_die "changed committed deletion backup was removed"
  [[ "$(
    archphene_adb_run shell run-as "$package" cat \
      "$local_project/delete-window-$window/$backup_name" |
      tr -d '\r'
  )" == "changed-after-commit-$window-$token" ]] ||
    archphene_die "changed committed deletion backup was not preserved in Linux"
  archphene_adb_run shell run-as "$package" test ! -e files/project-sync-journal-v1 ||
    archphene_die "changed deletion synchronization journal remains"
done
archphene_note "  Provider edits after deletion commit are retained as explicit conflicts"

archphene_adb_run shell run-as "$package" rm \
  "$local_project/linux-new.txt" "$local_project/linux-new-two.txt"
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
  "Synced 2 change\\(s\\): 0 pulled, 2 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-linux-delete-recovered-$serial" 40
archphene_adb_run shell test ! -e "$remote/linux-new.txt" ||
  archphene_die "Linux deletion was not committed to Android"
archphene_adb_run shell test ! -e "$remote/linux-new-two.txt" ||
  archphene_die "second Linux deletion was not committed to Android"
archphene_adb_run shell run-as "$package" test ! -e files/project-sync-journal-v1 ||
  archphene_die "pre-commit synchronization journal remains"
archphene_note "  Pre-commit process death restored then safely recommitted Android deletion"

archphene_adb_run shell run-as "$package" rm -r "$local_project/src"
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
  "Synced 1 change\\(s\\): 0 pulled, 1 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-postcommit-recovered-$serial" 40
archphene_adb_run shell test ! -e "$remote/src" ||
  archphene_die "committed file or enclosing directory was restored"
archphene_adb_run shell run-as "$package" test ! -e files/project-sync-journal-v1 ||
  archphene_die "post-commit synchronization journal remains"
archphene_note "  Post-commit death finalized the file backup and enclosing directory deletion"

archphene_adb_run shell run-as "$package" sh -c \
  "'printf linux-push-recovery-$token > $local_project/main.txt'"
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
  "folder-sync-push-precommit-recovered-$serial" 40
[[ "$(archphene_adb_run shell cat "$remote/main.txt" | tr -d '\r')" == \
  "linux-push-recovery-$token" ]] ||
  archphene_die "pre-publication file replacement recovery lost the Linux update"
archphene_note "  Pre-publication process death restored and safely retried file replacement"

archphene_adb_run shell run-as "$package" sh -c \
  "'printf linux-published-$token > $local_project/main.txt'"
set_sync_hold published
archphene_adb_run logcat -c
sync_button
archphene_wait_log \
  'Project sync test holding phase=published' \
  25 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell am force-stop "$package"
restart_manager_files
sync_button
archphene_wait_ui_unwrapped \
  "Synced 0 change\\(s\\): 0 pulled, 0 pushed, 0 conflict\\(s\\), 0 deletion\\(s\\) deferred" \
  "folder-sync-push-published-recovered-$serial" 40
[[ "$(archphene_adb_run shell cat "$remote/main.txt" | tr -d '\r')" == \
  "linux-published-$token" ]] ||
  archphene_die "published Android file replacement was rolled back"
archphene_adb_run shell run-as "$package" test ! -e files/project-sync-journal-v1 ||
  archphene_die "published replacement synchronization journal remains"
archphene_note "  Published replacement survived process death and its backup was finalized"

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
archphene_note "  Cancel/retry, recursive snapshot, live directional progress, stable baseline, bidirectional additions/edits/deletes, recoverable Android mutation, conflict preservation, restart, and retained local project passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{connected,live-progress,detached}.png"
