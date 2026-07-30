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
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/code-folder-portal/$serial_slug}"
mkdir -p "$artifact_dir"

folder="ArchpheneCodePortal-$serial_slug"
source_path="/sdcard/Download/$folder"
imported_path="files/arch-root/home/archphene/Projects/$folder"
fixture="$ARCHPHENE_ROOT/rust-toolchain.toml"
expected_hash="$(sha256sum "$fixture" | awk '{print $1}')"
code_config=
code_config_backup="files/arch-root/run/code-folder-config-$serial_slug"
code_config_inventory_before=
code_config_backed_up=false
manager_was_running=false

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
  stop_code_session
  archphene_adb_run shell rm -rf "$source_path" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -rf "$imported_path" \
    >/dev/null 2>&1 || true
  if [[ "$code_config_backed_up" == true ]]; then
    archphene_adb_run shell \
      "run-as $manager sh -c 'rm -rf -- \"$code_config\" && mv -- \"$code_config_backup\" \"$code_config\"'" \
      >/dev/null 2>&1 || true
    code_config_backed_up=false
  fi
  if [[ "$manager_was_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

archphene_require_file "$fixture"
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$code_package" >/dev/null ||
  archphene_die "Code launcher is not installed: $code_package"
[[ -z "$(code_linux_pgid)" ]] ||
  archphene_die "refusing to replace an active Code session"
archphene_adb_run shell test ! -e "$source_path" ||
  archphene_die "refusing to overwrite Android folder: $source_path"
archphene_adb_run shell run-as "$manager" test ! -e "$imported_path" ||
  archphene_die "refusing to overwrite Linux project: $imported_path"

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
archphene_adb_run shell \
  "run-as $manager sh -c 'cp -a -- \"$code_config\" \"$code_config_backup\"'"
code_config_backed_up=true
code_config_inventory_before="$(code_config_inventory)"

archphene_adb_run shell mkdir -p "$source_path"
archphene_adb_run push "$fixture" "$source_path/portal.txt" >/dev/null

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected' 45 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' \
  >/dev/null
archphene_wait_log \
  'Presented Linux frame' 60 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' \
  >/dev/null
archphene_wait_ui "package=\"$code_package\"" \
  "code-folder-workbench-$serial_slug" 60
sleep 5

# Code's standard "Open Folder..." chord. The resulting XDG portal request must
# be handled by Android DocumentsUI rather than Code's desktop GTK fallback.
archphene_adb_run shell input keycombination 113 39 >/dev/null
sleep 0.5
archphene_adb_run shell input keycombination 113 43 >/dev/null
archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "code-folder-picker-$serial_slug" 30
archphene_open_documents_download_root \
  "$ARCHPHENE_UI" "code-folder-downloads-$serial_slug"
archphene_wait_ui_exact_text "$folder" "code-folder-result-$serial_slug" 20
archphene_tap_text "$ARCHPHENE_UI" "$folder"
archphene_wait_ui \
  'text="USE THIS FOLDER"[^>]*enabled="true"' \
  "code-folder-use-$serial_slug" 15
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker.png"
archphene_tap_text "$ARCHPHENE_UI" "USE THIS FOLDER"
archphene_wait_ui_exact_text "ALLOW" "code-folder-allow-$serial_slug" 15
archphene_tap_text "$ARCHPHENE_UI" "ALLOW"

archphene_wait_ui "$folder" "code-folder-open-$serial_slug" 60
archphene_adb_run exec-out screencap -p >"$artifact_dir/code-open.png"
archphene_android_pid "$code_package" >/dev/null ||
  archphene_die "Code wrapper exited after opening the selected folder"
[[ "$(code_linux_pgid)" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "Code Linux process group is not live"
actual_hash="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$imported_path/portal.txt" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$actual_hash" == "$expected_hash" ]] ||
  archphene_die "Code folder portal changed the imported file"
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E ArchpheneRuntime:E \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "Code folder portal emitted a fatal event: $logs"

trap - EXIT
cleanup
[[ "$(code_config_inventory)" == "$code_config_inventory_before" ]] ||
  archphene_die "Code folder gate did not restore the exact Code configuration"
archphene_adb_run shell run-as "$manager" test ! -e "$code_config_backup" ||
  archphene_die "Code folder gate left its configuration backup"
archphene_adb_run shell run-as "$manager" test ! -e "$imported_path" ||
  archphene_die "Code folder gate left its imported project"
archphene_adb_run shell test ! -e "$source_path" ||
  archphene_die "Code folder gate left its Android source"
if [[ "$manager_was_running" == true ]]; then
  archphene_android_pid "$manager" >/dev/null ||
    archphene_die "Code folder gate did not restore the running manager"
else
  ! archphene_android_pid "$manager" >/dev/null 2>&1 ||
    archphene_die "Code folder gate left the manager running"
fi

archphene_note "Code folder portal passed on $serial"
archphene_note "  Android DocumentsUI imported exact bytes and Code opened the logical Linux project"
archphene_note "  Code configuration and manager lifecycle were restored"
archphene_note "  Full-device screenshots: $artifact_dir/{picker,code-open}.png"
