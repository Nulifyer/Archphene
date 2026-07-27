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
archphene_test_init "$serial"

activity="$(archphene_launcher "$package")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/gtk3-open-portal/$serial_slug}"
mkdir -p "$artifact_dir"
token="$(printf '%08x%08x' "$((RANDOM * 65536 + RANDOM))" "$((RANDOM * 65536 + RANDOM))")"
name="archphene-gtk3-open-$token.txt"
source_path="/sdcard/Download/$name"
imported_path="files/arch-root/home/archphene/Documents/Android/$name"
collision_name="${name%.txt} (2).txt"
collision_path="files/arch-root/home/archphene/Documents/Android/$collision_name"
source_payload="Archphene_GTK3_OpenFile_$token"
expected_sha256="$(printf %s "$source_payload" | sha256sum | awk '{print $1}')"

cleanup() {
  archphene_adb_run shell rm -f "$source_path" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" sh -c \
    "'rm -f \"$imported_path\" \"$collision_path\"'" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

launch_picker() {
  archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_O
  archphene_wait_ui \
    'package="com\.(google\.)?android\.documentsui"' \
    "gtk3-open-portal-picker-$serial_slug" 20
}

open_picker() {
  launch_picker
  archphene_open_documents_download_root "$ARCHPHENE_UI" \
    "gtk3-open-portal-downloads-$serial_slug"
  archphene_wait_ui 'content-desc="Search"' \
    "gtk3-open-portal-downloads-ready-$serial_slug" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'content-desc="Search"' Search
  sleep 0.3
  archphene_adb_run shell input text "$name"
  archphene_wait_ui \
    "text=\"$name\"[^>]*resource-id=\"android:id/title\"" \
    "gtk3-open-portal-result-$serial_slug" 20
}

archphene_adb_run shell pm path "$package" >/dev/null
archphene_adb_run shell test ! -e "$source_path" ||
  archphene_die "refusing to overwrite existing Android document: $source_path"
payload_base64="$(printf %s "$source_payload" | base64 -w0)"
archphene_adb_run shell mkdir -p /sdcard/Download
archphene_adb_run shell sh -c \
  "'printf %s $payload_base64 | base64 -d > $source_path'"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Started manager-owned Linux process session=' 30 \
  'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
sleep 1

launch_picker
archphene_adb_run exec-out screencap -p >"$artifact_dir/cancel-ready.png"
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_wait_log \
  'Portal document request ended session=[1-9][0-9]* request=[1-9][0-9]* result=2' \
  20 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell pidof "$package" >/dev/null ||
  archphene_die "launcher exited after cancelling GTK3 OpenFile"
archphene_adb_run shell run-as "$manager" test ! -e "$imported_path" ||
  archphene_die "cancelled OpenFile created a Linux import"

open_picker
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker-ready.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$name\"[^>]*resource-id=\"android:id/title\"" \
  "$name result"

archphene_wait_log \
  'OpenFile completed path=.* broker=0 response=0 emitted=1' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  'Portal document request ended session=[1-9][0-9]* request=[1-9][0-9]* result=1' \
  20 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null

archphene_adb_run shell run-as "$manager" test -f "$imported_path" ||
  archphene_die "OpenFile did not publish the selected display name under ~/Documents/Android"
imported_sha256="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$imported_path" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$imported_sha256" == "$expected_sha256" ]] ||
  archphene_die "OpenFile imported document was not byte-exact"

archphene_wait_ui \
  "package=\"$package\"" \
  "gtk3-open-portal-complete-$serial_slug" 20
sleep 0.5
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"

open_picker
archphene_adb_run logcat -c
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$name\"[^>]*resource-id=\"android:id/title\"" \
  "$name result"
archphene_wait_log \
  'OpenFile completed path=.* broker=0 response=0 emitted=1' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell run-as "$manager" sh -c \
  "'test -f \"$collision_path\"'" ||
  archphene_die "a repeated OpenFile did not preserve the existing import"
collision_sha256="$(
  archphene_adb_run shell run-as "$manager" sh -c \
    "'sha256sum \"$collision_path\"'" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$collision_sha256" == "$expected_sha256" ]] ||
  archphene_die "collision-numbered OpenFile import was not byte-exact"
archphene_wait_ui \
  "package=\"$package\"" \
  "gtk3-open-portal-collision-complete-$serial_slug" 20
sleep 0.5
archphene_adb_run exec-out screencap -p >"$artifact_dir/collision-complete.png"

logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E AndroidRuntime:E libc:F '*:S' \
    2>/dev/null || true
)"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "GTK3 OpenFile emitted a fatal event: $logs"

trap - EXIT
cleanup
archphene_note "Stock GTK3 OpenFile passed on $serial"
archphene_note "  Cancellation stayed live; repeated imports preserved both exact files and URI/grant data stayed outside Linux"
archphene_note "  Full-device screenshots: $artifact_dir/{cancel-ready,picker-ready,complete,collision-complete}.png"
