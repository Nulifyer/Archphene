#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
code_package=
manager=org.archphene.app.debug
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --code-package) code_package="${2:?missing value for --code-package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --code-package PACKAGE [--manager PACKAGE] [--artifact-dir PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$code_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--code-package is not a generated Archphene launcher"
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"
archphene_test_init "$serial"

activity="$(archphene_launcher "$code_package")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/code-file-portal/$serial_slug}"
mkdir -p "$artifact_dir"

token="$(printf '%08x%08x' "$((RANDOM * 65536 + RANDOM))" "$((RANDOM * 65536 + RANDOM))")"
open_name_a="archphene-code-open-$token-a.txt"
open_name_b="archphene-code-open-$token-b.txt"
open_name_c="archphene-code-open-$token-c.txt"
save_name="archphene-code-save-$token.txt"
source_path_a="/sdcard/Download/$open_name_a"
source_path_b="/sdcard/Download/$open_name_b"
source_path_c="/sdcard/Download/$open_name_c"
save_path="/sdcard/Download/$save_name"
imported_path_a="files/arch-root/home/archphene/Documents/Android/$open_name_a"
imported_path_b="files/arch-root/home/archphene/Documents/Android/$open_name_b"
imported_path_c="files/arch-root/home/archphene/Documents/Android/$open_name_c"
fixture="$ARCHPHENE_ROOT/rust-toolchain.toml"
expected_hash="$(sha256sum "$fixture" | awk '{print $1}')"
code_config=
code_config_backup="files/arch-root/run/code-file-config-$serial_slug"
code_config_inventory_before=
code_config_backed_up=false
code_session_started=false
code_flags="files/arch-root/home/archphene/.config/code-flags.conf"
code_flags_backup="files/arch-root/run/code-file-flags-$serial_slug"
code_flags_existed=false
code_flags_hash_before=
code_flags_mutated=false
manager_was_running=false
save_staging_path=

if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_was_running=true
fi

code_linux_pgid() {
  local process_tree
  process_tree="$(
    archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
      tr -d '\r'
  )"
  awk '
    $4 ~ /^libarchphene_(pkg_[0-9a-f]+|ld)\.so$/ &&
    ($0 ~ /--argv0 (code|code-oss) / ||
     $0 ~ /\/usr\/lib\/code\/code\.mjs/ ||
     $0 ~ /\/opt\/visual-studio-code\//) {
      print $3
      exit
    }
  ' <<<"$process_tree"
}

stop_code_session() {
  local linux_pgid deadline process_tree
  linux_pgid="$(code_linux_pgid)"
  archphene_adb_run shell am force-stop "$code_package" >/dev/null 2>&1 || true
  if [[ "$linux_pgid" =~ ^[1-9][0-9]*$ ]]; then
    deadline=$((SECONDS + 20))
    while ((SECONDS < deadline)); do
      process_tree="$(
        archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
          tr -d '\r'
      )"
      if ! awk -v pgid="$linux_pgid" \
        '$3 == pgid { found = 1 } END { exit !found }' <<<"$process_tree"; then
        return 0
      fi
      sleep 0.25
    done
    archphene_die "Code Linux process group did not stop before state restoration"
  fi
}

code_config_inventory() {
  archphene_adb_run shell \
    "run-as $manager sh -c 'cd \"$code_config\" && find . -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

cleanup() {
  local status=$?
  if [[ "$code_session_started" == true ]]; then
    stop_code_session || status=$?
    code_session_started=false
  fi
  archphene_adb_run shell rm -f \
    "$source_path_a" "$source_path_b" "$source_path_c" "$save_path" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -f \
    "$imported_path_a" "$imported_path_b" "$imported_path_c" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell \
    "run-as $manager sh -c 'find files/arch-root/home/archphene/.cache/archphene/portal-save -type f -name \"$save_name\" -delete 2>/dev/null || true'" \
    >/dev/null 2>&1 || true
  if [[ "$code_config_backed_up" == true ]]; then
    archphene_adb_run shell \
      "run-as $manager sh -c 'rm -rf -- \"$code_config\" && mv -- \"$code_config_backup\" \"$code_config\"'" \
      >/dev/null 2>&1 || true
    code_config_backed_up=false
  fi
  if [[ "$code_flags_mutated" == true ]]; then
    if [[ "$code_flags_existed" == true ]]; then
      archphene_adb_run shell run-as "$manager" mv -f \
        "$code_flags_backup" "$code_flags" >/dev/null 2>&1 || true
    else
      archphene_adb_run shell run-as "$manager" rm -f \
        "$code_flags" "$code_flags_backup" >/dev/null 2>&1 || true
    fi
    code_flags_mutated=false
  fi
  if [[ "$manager_was_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
  return "$status"
}
trap cleanup EXIT

open_downloads() {
  local name="$1"
  archphene_open_documents_download_root "$ARCHPHENE_UI" "$name-downloads"
  archphene_wait_ui 'content-desc="Search"' "$name-downloads-ready" 15
}

long_press_ui_pattern() {
  local ui="$1" pattern="$2" label="$3" center x y
  center="$(archphene_ui_node_center "$ui" "$pattern" "$label")"
  read -r x y <<<"$center"
  archphene_adb_run shell input swipe "$x" "$y" "$x" "$y" 900 >/dev/null
}

archphene_require_file "$fixture"
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$code_package" >/dev/null ||
  archphene_die "Code launcher is not installed: $code_package"
[[ -z "$(code_linux_pgid)" ]] ||
  archphene_die "refusing to replace an active Code session"
for path in "$source_path_a" "$source_path_b" "$source_path_c" "$save_path"; do
  archphene_adb_run shell test ! -e "$path" ||
    archphene_die "refusing to overwrite Android document: $path"
done
for path in "$imported_path_a" "$imported_path_b" "$imported_path_c"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$path" ||
    archphene_die "refusing to overwrite Linux import: $path"
done

code_configs="$(
  archphene_adb_run exec-out run-as "$manager" find \
    files/arch-root/home/archphene/.config -mindepth 1 -maxdepth 1 -type d \
    -print 2>/dev/null |
    tr -d '\r' |
    sed -n '\#/\(Code\|Code - OSS\)$#p'
)"
[[ "$(sed '/^$/d' <<<"$code_configs" | wc -l)" == 1 ]] ||
  archphene_die "could not identify one exact Code configuration directory"
code_config="$(sed -n '1p' <<<"$code_configs")"
archphene_adb_run shell run-as "$manager" test ! -e "$code_config_backup" ||
  archphene_die "Code configuration backup path already exists"
archphene_adb_run shell run-as "$manager" test ! -e "$code_flags_backup" ||
  archphene_die "Code flags backup path already exists"
archphene_adb_run shell \
  "run-as $manager sh -c 'cp -a -- \"$code_config\" \"$code_config_backup\"'"
code_config_backed_up=true
code_config_inventory_before="$(code_config_inventory)"
if archphene_adb_run shell run-as "$manager" test -e "$code_flags"; then
  code_flags_existed=true
  code_flags_hash_before="$(
    archphene_adb_run shell run-as "$manager" sha256sum "$code_flags" |
      awk '{print $1}' |
      tr -d '\r'
  )"
  archphene_adb_run shell run-as "$manager" cp -a \
    "$code_flags" "$code_flags_backup"
  code_flags_mutated=true
  archphene_adb_run shell \
    "run-as $manager sh -c 'printf \"\\n--disable-workspace-trust\\n\" >> \"$code_flags\"'"
else
  code_flags_mutated=true
  archphene_adb_run shell \
    "run-as $manager sh -c 'printf \"--disable-workspace-trust\\n\" > \"$code_flags\"'"
fi

archphene_adb_run push "$fixture" "$source_path_a" >/dev/null
archphene_adb_run push "$fixture" "$source_path_b" >/dev/null
archphene_adb_run push "$fixture" "$source_path_c" >/dev/null
archphene_adb_run logcat -c
code_session_started=true
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected' 45 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' \
  >/dev/null
archphene_wait_log \
  'Presented Linux frame' 60 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' \
  >/dev/null
process_tree="$(
  archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
    tr -d '\r'
)"
awk '
  $4 ~ /^libarchphene_(pkg_[0-9a-f]+|ld)\.so$/ &&
  ($0 ~ /--argv0 (code|code-oss) / ||
   $0 ~ /\/usr\/lib\/code\/code\.mjs/ ||
   $0 ~ /\/opt\/visual-studio-code\//) &&
  $0 ~ /--disable-workspace-trust/ {
    found = 1
  }
  END { exit !found }
' <<<"$process_tree" ||
  archphene_die "Code did not consume the temporary workspace-trust flag"
archphene_wait_ui "package=\"$code_package\"" \
  "code-file-workbench-$serial_slug" 60
sleep 5

archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_O \
  >/dev/null
archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "code-file-open-picker-$serial_slug" 30
open_downloads "code-file-open-$serial_slug"
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'content-desc="Search"' Search
sleep 0.3
archphene_adb_run shell input text "$token" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui \
  "text=\"$open_name_a\"[^>]*resource-id=\"android:id/title\"" \
  "code-file-open-result-a-$serial_slug" 20
archphene_regex_contains \
  "$ARCHPHENE_UI" \
  "text=\"$open_name_b\"[^>]*resource-id=\"android:id/title\"" ||
  archphene_die "DocumentsUI did not show the second Code Open fixture"
archphene_regex_contains \
  "$ARCHPHENE_UI" \
  "text=\"$open_name_c\"[^>]*resource-id=\"android:id/title\"" ||
  archphene_die "DocumentsUI did not show the third Code Open fixture"
archphene_adb_run exec-out screencap -p >"$artifact_dir/single-open-picker.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$open_name_a\"[^>]*resource-id=\"android:id/title\"" \
  "$open_name_a result"

archphene_wait_log \
  'OpenFile completed path=.* broker=0 response=0 emitted=1' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
sleep 3
archphene_adb_run exec-out screencap -p >"$artifact_dir/single-open-complete.png"
actual_hash="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$imported_path_a" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$actual_hash" == "$expected_hash" ]] ||
  archphene_die "Code single-file Open import was not byte-exact"
single_logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E ArchpheneRuntime:E \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$single_logs" != *'FATAL EXCEPTION'* && "$single_logs" != *'Fatal signal'* ]] ||
  archphene_die "Code single-file Open emitted a fatal event: $single_logs"

archphene_adb_run logcat -c
archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_O \
  >/dev/null
archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "code-multiple-file-open-picker-$serial_slug" 30
open_downloads "code-multiple-file-open-$serial_slug"
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'content-desc="Search"' Search
sleep 0.3
archphene_adb_run shell input text "$token" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui \
  "text=\"$open_name_b\"[^>]*resource-id=\"android:id/title\"" \
  "code-multiple-file-open-result-b-$serial_slug" 20
long_press_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$open_name_b\"[^>]*resource-id=\"android:id/title\"" \
  "$open_name_b result"
archphene_wait_ui \
  "text=\"$open_name_b\"[^>]*resource-id=\"android:id/title\"[^>]*selected=\"true\"" \
  "code-file-open-selected-b-$serial_slug" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$open_name_c\"[^>]*resource-id=\"android:id/title\"" \
  "$open_name_c result"
sleep 0.5
selection_ui="$(
  archphene_capture_ui "code-file-open-selection-c-$serial_slug" \
    2>/dev/null || true
)"
if ! archphene_regex_contains \
  "$selection_ui" \
  "text=\"$open_name_c\"[^>]*resource-id=\"android:id/title\"[^>]*selected=\"true\""; then
  archphene_regex_contains \
    "$selection_ui" \
    "text=\"$open_name_c\"[^>]*resource-id=\"android:id/title\"" ||
    archphene_die "DocumentsUI lost the third Code Open fixture"
  archphene_tap_ui_pattern \
    "$selection_ui" \
    "text=\"$open_name_c\"[^>]*resource-id=\"android:id/title\"" \
    "$open_name_c retry"
fi
archphene_wait_ui \
  "text=\"$open_name_c\"[^>]*resource-id=\"android:id/title\"[^>]*selected=\"true\"" \
  "code-file-open-selected-c-$serial_slug" 15
archphene_adb_run exec-out screencap -p >"$artifact_dir/open-picker.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:Open|Select)"[^>]*enabled="true"' \
  "Open documents"

archphene_wait_log \
  'OpenFile completed path=.* broker=0 response=0 emitted=1' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
sleep 3
archphene_adb_run exec-out screencap -p >"$artifact_dir/open-complete.png"
for path in "$imported_path_b" "$imported_path_c"; do
  actual_hash="$(
    archphene_adb_run shell run-as "$manager" sha256sum "$path" |
      awk '{print $1}' |
      tr -d '\r'
  )"
  [[ "$actual_hash" == "$expected_hash" ]] ||
    archphene_die "Code Open imported document was not byte-exact: $path"
done
for path in "$source_path_a" "$source_path_b" "$source_path_c"; do
  [[ "$(
    archphene_adb_run shell sha256sum "$path" |
      awk '{print $1}' |
      tr -d '\r'
  )" == "$expected_hash" ]] ||
    archphene_die "Code Open changed the Android source: $path"
done

archphene_adb_run shell input keycombination 113 59 47 >/dev/null
archphene_wait_ui \
  'text="(?:SAVE|Save)"[^>]*enabled="true"' \
  "code-file-save-picker-$serial_slug" 30
archphene_open_documents_download_root \
  "$ARCHPHENE_UI" "code-file-save-downloads-$serial_slug"
archphene_wait_ui \
  'resource-id="android:id/title"[^>]*class="android\.widget\.EditText"' \
  "code-file-save-name-$serial_slug" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  'resource-id="android:id/title"[^>]*class="android\.widget\.EditText"' \
  'document name'
archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A \
  >/dev/null
archphene_adb_run shell input text "$save_name" >/dev/null
archphene_wait_ui_exact_text "$save_name" "code-file-save-name-set-$serial_slug" 15
archphene_adb_run exec-out screencap -p >"$artifact_dir/save-picker.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:SAVE|Save)"[^>]*enabled="true"' Save
archphene_wait_log \
  'Mirrored Linux SaveFile session=[1-9][0-9]* bytes=[1-9][0-9]*' \
  30 'ArchphenePortal:I AndroidRuntime:E *:S' >/dev/null

[[ "$(
  archphene_adb_run shell sha256sum "$save_path" |
    awk '{print $1}' |
    tr -d '\r'
)" == "$expected_hash" ]] ||
  archphene_die "Code Save As destination was not byte-exact"
save_staging_paths="$(
  archphene_adb_run exec-out run-as "$manager" find \
    files/arch-root/home/archphene/.cache/archphene/portal-save \
    -type f -name "$save_name" -print 2>/dev/null |
    tr -d '\r'
)"
[[ "$(sed '/^$/d' <<<"$save_staging_paths" | wc -l)" == 1 ]] ||
  archphene_die "Code Save As did not retain one exact-name Linux staging file"
save_staging_path="$(sed -n '1p' <<<"$save_staging_paths")"
[[ "$save_staging_path" =~ ^files/arch-root/home/archphene/\.cache/archphene/portal-save/[1-9][0-9]*-[0-9a-f]{16}/[1-9][0-9]*-[0-9a-f]{16}/"$save_name"$ ]] ||
  archphene_die "Code Save As exposed an unsafe or opaque Linux path: $save_staging_path"
[[ "$(
  archphene_adb_run shell run-as "$manager" sha256sum "$save_staging_path" |
    awk '{print $1}' |
    tr -d '\r'
)" == "$expected_hash" ]] ||
  archphene_die "Code Save As Linux staging file was not byte-exact"
sleep 2
archphene_adb_run exec-out screencap -p >"$artifact_dir/save-complete.png"
archphene_android_pid "$code_package" >/dev/null ||
  archphene_die "Code wrapper exited during file portal validation"
[[ "$(code_linux_pgid)" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "Code Linux process group is not live"
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E ArchpheneRuntime:E \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" == *'SaveFile completed'* ]] ||
  archphene_die "Code Save As did not complete through the desktop portal"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "Code file portal emitted a fatal event: $logs"

trap - EXIT
cleanup
[[ "$(code_config_inventory)" == "$code_config_inventory_before" ]] ||
  archphene_die "Code file gate did not restore the exact Code configuration"
archphene_adb_run shell run-as "$manager" test ! -e "$code_config_backup" ||
  archphene_die "Code file gate left its configuration backup"
archphene_adb_run shell run-as "$manager" test ! -e "$code_flags_backup" ||
  archphene_die "Code file gate left its flags backup"
if [[ "$code_flags_existed" == true ]]; then
  [[ "$(
    archphene_adb_run shell run-as "$manager" sha256sum "$code_flags" |
      awk '{print $1}' |
      tr -d '\r'
  )" == "$code_flags_hash_before" ]] ||
    archphene_die "Code file gate did not restore the exact Code flags"
else
  archphene_adb_run shell run-as "$manager" test ! -e "$code_flags" ||
    archphene_die "Code file gate left temporary Code flags"
fi
for path in "$source_path_a" "$source_path_b" "$source_path_c" "$save_path"; do
  archphene_adb_run shell test ! -e "$path" ||
    archphene_die "Code file gate left Android test data: $path"
done
for path in "$imported_path_a" "$imported_path_b" "$imported_path_c"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$path" ||
    archphene_die "Code file gate left its Linux import: $path"
done
if [[ "$manager_was_running" == true ]]; then
  archphene_android_pid "$manager" >/dev/null ||
    archphene_die "Code file gate did not restore the running manager"
else
  ! archphene_android_pid "$manager" >/dev/null 2>&1 ||
    archphene_die "Code file gate left the manager running"
fi

archphene_note "Code Open and Save As portals passed on $serial"
archphene_note "  Single-file Open, two-file Open, all three Linux imports, exact-name Linux Save As staging, and the Android destination were byte-exact"
archphene_note "  Code configuration and manager lifecycle were restored"
archphene_note "  Full-device screenshots: $artifact_dir/{single-open-picker,single-open-complete,open-picker,open-complete,save-picker,save-complete}.png"
