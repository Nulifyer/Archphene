#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package GENERATED_LAUNCHER [--artifact-dir PATH]"
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
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/gtk3-save-portal/$serial_slug}"
mkdir -p "$artifact_dir"

token="$(printf '%08x%08x' "$((RANDOM * 65536 + RANDOM))" "$((RANDOM * 65536 + RANDOM))")"
payload="Archphene_GTK3_SaveFile_$token"
document_name="archphene-gtk3-save-$token.txt"
target="/sdcard/Download/$document_name"
expected_sha256="$(printf %s "$payload" | sha256sum | awk '{print $1}')"

cleanup() {
  archphene_adb_run shell rm -f "$target" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

open_save_as() {
  archphene_adb_run shell input keycombination 113 59 47 >/dev/null
  archphene_wait_ui \
    'text="(?:SAVE|Save)"[^>]*enabled="true"' \
    "gtk3-save-portal-$serial_slug" 20
}

set_document_name() {
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" \
    'resource-id="android:id/title"[^>]*class="android\.widget\.EditText"' \
    'document name'
  archphene_adb_run shell input keycombination 113 29 >/dev/null
  archphene_adb_run shell input text "$document_name" >/dev/null
}

archphene_adb_run shell pm path "$package" >/dev/null
archphene_adb_run shell test ! -e "$target" ||
  archphene_die "refusing to overwrite existing Android document: $target"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Started manager-owned Linux process session=' 30 \
  'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
sleep 1
archphene_adb_run shell input text "$payload" >/dev/null

open_save_as
archphene_adb_run exec-out screencap -p >"$artifact_dir/cancel-ready.png"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_log \
  'Portal document request ended session=[1-9][0-9]* request=[1-9][0-9]* result=2' \
  20 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell test ! -e "$target" ||
  archphene_die "cancelled GTK3 Save As created an Android file"
archphene_adb_run shell pidof "$package" >/dev/null ||
  archphene_die "launcher exited after cancelling GTK3 Save As"

open_save_as
set_document_name
archphene_adb_run exec-out screencap -p >"$artifact_dir/save-ready.png"
archphene_tap_ui_pattern \
  "$(archphene_capture_ui "gtk3-save-portal-confirm-$serial_slug")" \
  'text="(?:SAVE|Save)"[^>]*enabled="true"' Save
archphene_wait_log \
  'Mirrored Linux SaveFile session=[1-9][0-9]* bytes=[1-9][0-9]*' \
  30 'ArchphenePortal:I AndroidRuntime:E *:S' >/dev/null

actual_sha256="$(
  archphene_adb_run shell sha256sum "$target" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$actual_sha256" == "$expected_sha256" ]] ||
  archphene_die "GTK3 Save As destination did not receive the exact payload"
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"

logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E AndroidRuntime:E libc:F '*:S' \
    2>/dev/null || true
)"
[[ "$logs" == *'SaveFile completed'* ]] ||
  archphene_die "GTK3 SaveFile did not complete through the desktop portal"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "GTK3 Save As emitted a fatal event: $logs"

trap - EXIT
cleanup
archphene_note "Stock GTK3 Save As passed on $serial"
archphene_note "  Cancellation remained live and the Android destination was byte-exact"
archphene_note "  Full-device screenshots: $artifact_dir/{cancel-ready,save-ready,complete}.png"
