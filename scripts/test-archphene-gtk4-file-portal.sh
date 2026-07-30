#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
manager=org.archphene.app.debug
wrapper=org.archphene.linux.p46204b29816e2006b6f4a02b6c452e56
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --wrapper) wrapper="${2:?missing value for --wrapper}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--manager PACKAGE] [--wrapper PACKAGE] [--artifact-dir PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$wrapper" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--wrapper is not a generated Archphene launcher"
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"
archphene_test_init "$serial"

activity="$(archphene_launcher "$wrapper")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/gtk4-file-portal/$serial_slug}"
mkdir -p "$artifact_dir"

token="$(printf '%08x%08x' "$((RANDOM * 65536 + RANDOM))" "$((RANDOM * 65536 + RANDOM))")"
name="archphene-gtk4-open-$token.key"
android_path="/sdcard/Download/$name"
linux_path="files/arch-root/home/archphene/Documents/Android/$name"
fixture="$ARCHPHENE_ROOT/rust-toolchain.toml"
expected_hash="$(sha256sum "$fixture" | awk '{print $1}')"
manager_was_running=false
wrapper_started=false
private_state_before=

if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_was_running=true
fi

private_state_inventory() {
  archphene_adb_run shell \
    "run-as $manager sh -c 'cd files/arch-root/home/archphene && for path in .gnupg .local/share/keyrings .config/dconf/user; do if [ -f \"\$path\" ]; then sha256sum \"\$path\"; elif [ -d \"\$path\" ]; then find \"\$path\" -type f -exec sha256sum {} \\; | sort; fi; done'" |
    tr -d '\r'
}

seahorse_linux_pid() {
  archphene_adb_run shell ps -A -o PID,NAME,ARGS |
    tr -d '\r' |
    awk '
      $2 ~ /^libarchphene_(pkg_[0-9a-f]+|ld)\.so$/ &&
      ($0 ~ /--argv0 seahorse / || $0 ~ /\/usr\/bin\/seahorse([[:space:]]|$)/) {
        print $1
        exit
      }
    '
}

cleanup() {
  local status=$?
  if [[ "$wrapper_started" == true ]]; then
    archphene_adb_run shell am force-stop "$wrapper" >/dev/null 2>&1 || true
    wrapper_started=false
  fi
  archphene_adb_run shell rm -f "$android_path" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -f "$linux_path" \
    >/dev/null 2>&1 || true
  if [[ "$manager_was_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
  return "$status"
}
trap cleanup EXIT

archphene_require_file "$fixture"
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$wrapper" >/dev/null ||
  archphene_die "GTK 4 launcher is not installed: $wrapper"
! archphene_android_pid "$wrapper" >/dev/null 2>&1 ||
  archphene_die "GTK 4 launcher must be stopped before the state-preserving gate"
[[ -z "$(seahorse_linux_pid)" ]] ||
  archphene_die "Seahorse Linux process must be stopped before the gate"
archphene_adb_run shell test ! -e "$android_path" ||
  archphene_die "refusing to overwrite Android fixture: $android_path"
archphene_adb_run shell run-as "$manager" test ! -e "$linux_path" ||
  archphene_die "refusing to overwrite Linux import: $linux_path"

private_state_before="$(private_state_inventory)"
archphene_adb_run push "$fixture" "$android_path" >/dev/null
archphene_adb_run logcat -c
wrapper_started=true
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui 'text="Add new items"' "gtk4-file-main-$serial_slug" 60
[[ "$(seahorse_linux_pid)" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "Seahorse Linux process did not start"
archphene_adb_run exec-out screencap -p >"$artifact_dir/main.png"

ui="$(archphene_capture_ui "gtk4-file-main-action-$serial_slug")"
archphene_tap_ui_pattern "$ui" 'text="Add new items"' "Add new items"
sleep 0.5
# The GTK popover does not export its menu rows through current AT-SPI, but
# standard keyboard navigation reaches its final "Import from file..." item.
archphene_adb_run shell input keyevent KEYCODE_MOVE_END >/dev/null
archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "gtk4-file-picker-$serial_slug" 30

picker_ui="$(archphene_capture_ui "gtk4-file-picker-current-$serial_slug")"
archphene_open_documents_download_root \
  "$picker_ui" "gtk4-file-downloads-$serial_slug"
archphene_wait_ui 'content-desc="Search"' \
  "gtk4-file-downloads-ready-$serial_slug" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'content-desc="Search"' Search
sleep 0.3
archphene_adb_run shell input text "$token" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui \
  "text=\"$name\"[^>]*resource-id=\"android:id/title\"[^>]*enabled=\"true\"" \
  "gtk4-file-result-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$name\"[^>]*resource-id=\"android:id/title\"[^>]*enabled=\"true\"" \
  "$name"

archphene_wait_log \
  'OpenFile completed path=.* broker=0 response=0 emitted=1' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_wait_ui \
  'text="Data to be imported"' \
  "gtk4-file-import-result-$serial_slug" 30
actual_hash="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$linux_path" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$actual_hash" == "$expected_hash" ]] ||
  archphene_die "GTK 4 Open import was not byte-exact"
archphene_adb_run exec-out screencap -p >"$artifact_dir/result.png"
archphene_android_pid "$wrapper" >/dev/null ||
  archphene_die "GTK 4 wrapper exited during file portal validation"
[[ "$(seahorse_linux_pid)" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "Seahorse exited during file portal validation"
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E ArchpheneRuntime:E \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "GTK 4 file portal emitted a fatal event: $logs"

trap - EXIT
cleanup
[[ "$(private_state_inventory)" == "$private_state_before" ]] ||
  archphene_die "GTK 4 file gate changed keyring or dconf state"
archphene_adb_run shell test ! -e "$android_path" ||
  archphene_die "GTK 4 file gate left its Android fixture"
archphene_adb_run shell run-as "$manager" test ! -e "$linux_path" ||
  archphene_die "GTK 4 file gate left its Linux import"
if [[ "$manager_was_running" == true ]]; then
  archphene_android_pid "$manager" >/dev/null ||
    archphene_die "GTK 4 file gate did not restore the running manager"
else
  ! archphene_android_pid "$manager" >/dev/null 2>&1 ||
    archphene_die "GTK 4 file gate left the manager running"
fi

archphene_note "GTK 4 DocumentsUI Open passed on $serial"
archphene_note "  Unmodified Seahorse selected the MIME-filtered Android file and received one byte-exact Linux import"
archphene_note "  Keyring/dconf state, manager lifecycle, and all fixtures were restored"
archphene_note "  Full-device screenshots: $artifact_dir/{main,picker,result}.png"
