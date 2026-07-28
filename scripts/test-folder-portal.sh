#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package GENERATED_LAUNCHER [--manager PACKAGE] [--artifact-dir PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package must be a generated Archphene launcher"
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"
archphene_test_init "$serial"

activity="$(archphene_launcher "$package")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/folder-portal/$serial_slug}"
mkdir -p "$artifact_dir"

folder=ArchphenePortalTree
source_path="/sdcard/Download/$folder"
imported_path="files/arch-root/home/archphene/Projects/$folder"
collision_path="files/arch-root/home/archphene/Projects/$folder (2)"
third_path="files/arch-root/home/archphene/Projects/$folder (3)"
probe_output=cache/portal-folder-probe.out

cleanup() {
  archphene_adb_run shell rm -rf "$source_path" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" sh -c \
    "'rm -rf \"$imported_path\" \"$collision_path\" \"$third_path\"; rm -f \"$probe_output\"'" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell test ! -e "$source_path" ||
  archphene_die "refusing to overwrite existing Android folder: $source_path"
archphene_adb_run shell run-as "$manager" test ! -e "$imported_path" ||
  archphene_die "refusing to overwrite existing Linux project: $imported_path"
archphene_adb_run shell mkdir -p "$source_path/nested" "$source_path/.git"
archphene_adb_run push "$ARCHPHENE_ROOT/Cargo.toml" "$source_path/root-Cargo.toml" \
  >/dev/null
archphene_adb_run push "$ARCHPHENE_ROOT/todo.md" "$source_path/nested/todo.md" \
  >/dev/null
archphene_adb_run push \
  "$ARCHPHENE_ROOT/rust-toolchain.toml" "$source_path/.git/config" >/dev/null
archphene_adb_run shell touch "$source_path/empty.txt"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private desktop portal ready session=' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_prepare_portal_probe "$manager"

run_probe() {
  local output="$1"
  archphene_run_portal_directory_probe "$manager" "$output"
}

choose_fixture() {
  local capture="$1"
  archphene_wait_ui \
    'package="com\.(google\.)?android\.documentsui"' \
    "folder-portal-picker-$serial_slug" 20
  archphene_open_documents_download_root \
    "$ARCHPHENE_UI" "folder-portal-downloads-$serial_slug"
  archphene_wait_ui_exact_text "$folder" "folder-portal-result-$serial_slug" 15
  archphene_tap_text "$ARCHPHENE_UI" "$folder"
  archphene_wait_ui \
    'text="USE THIS FOLDER"[^>]*enabled="true"' \
    "folder-portal-use-$serial_slug" 15
  archphene_adb_run exec-out screencap -p >"$artifact_dir/$capture"
  archphene_tap_text "$ARCHPHENE_UI" "USE THIS FOLDER"
  archphene_wait_ui_exact_text "ALLOW" "folder-portal-allow-$serial_slug" 15
  archphene_tap_text "$ARCHPHENE_UI" "ALLOW"
}

verify_project() {
  local path="$1"
  local relative source expected actual
  for relative in root-Cargo.toml nested/todo.md .git/config; do
    case "$relative" in
      root-Cargo.toml) source="$ARCHPHENE_ROOT/Cargo.toml" ;;
      nested/todo.md) source="$ARCHPHENE_ROOT/todo.md" ;;
      .git/config) source="$ARCHPHENE_ROOT/rust-toolchain.toml" ;;
      *) archphene_die "unknown folder fixture path: $relative" ;;
    esac
    expected="$(sha256sum "$source" | awk '{print $1}')"
    actual="$(
      archphene_adb_run shell run-as "$manager" sh -c \
        "'sha256sum \"$path/$relative\"'" |
        awk '{print $1}' |
        tr -d '\r'
    )"
    [[ "$actual" == "$expected" ]] ||
      archphene_die "folder portal changed $relative"
  done
  [[ "$(
    archphene_adb_run shell run-as "$manager" sh -c \
      "'stat -c %s \"$path/empty.txt\"'" |
      tr -d '\r'
  )" == 0 ]] || archphene_die "folder portal did not preserve the empty file"
}

run_probe "$probe_output"
choose_fixture picker-ready.png
archphene_wait_log \
  'Portal folder imported name=ArchphenePortalTree entries=6 bytes=[1-9][0-9]*' \
  30 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
verify_project "$imported_path"
probe_result="$(
  archphene_adb_run shell run-as "$manager" cat "$probe_output" |
    tr -d '\r'
)"
[[ "$probe_result" == "PASS portal folder selected" ]] ||
  archphene_die "folder portal probe did not receive its selected URI"

archphene_adb_run logcat -c
run_probe "$probe_output"
choose_fixture collision-picker-ready.png
archphene_wait_log \
  'Portal folder imported name=ArchphenePortalTree \(2\) entries=6 bytes=[1-9][0-9]*' \
  30 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
verify_project "$collision_path"

archphene_adb_run logcat -c
run_probe "$probe_output"
archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "folder-portal-cancel-$serial_slug" 20
for _ in 1 2 3 4; do
  archphene_adb_run shell input keyevent KEYCODE_BACK
  sleep 0.2
  if [[ -n "$(
    archphene_adb_run shell run-as "$manager" cat "$probe_output" 2>/dev/null |
      tr -d '\r'
  )" ]]; then
    break
  fi
done
archphene_wait_log \
  'Portal document request ended session=[1-9][0-9]* request=[1-9][0-9]* result=2' \
  20 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell run-as "$manager" sh -c \
  "'test ! -e \"$third_path\"'" ||
  archphene_die "cancelled folder selection published another project"
archphene_adb_run shell pidof "$package" >/dev/null ||
  archphene_die "launcher exited after cancelling folder selection"

archphene_wait_ui "package=\"$package\"" "folder-portal-complete-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E ArchpheneRuntime:E \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "folder portal emitted a fatal event: $logs"

trap - EXIT
cleanup
archphene_note "Folder portal passed on $serial"
archphene_note "  Nested files, dot directories, empty files, collision numbering, and cancellation are exact"
archphene_note "  Full-device screenshots: $artifact_dir/{picker-ready,collision-picker-ready,complete}.png"
