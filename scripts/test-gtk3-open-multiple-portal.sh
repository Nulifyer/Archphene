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
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/gtk3-open-multiple-portal/$serial_slug}"
mkdir -p "$artifact_dir"
token="$(printf '%08x%08x' "$((RANDOM * 65536 + RANDOM))" "$((RANDOM * 65536 + RANDOM))")"
name_a="archphene-multi-$token-a.txt"
name_b="archphene-multi-$token-b.txt"
source_a="/sdcard/Download/$name_a"
source_b="/sdcard/Download/$name_b"
import_a="files/arch-root/home/archphene/Documents/Android/$name_a"
import_b="files/arch-root/home/archphene/Documents/Android/$name_b"
payload_a="Archphene_GTK3_Multi_A_$token"
payload_b="Archphene_GTK3_Multi_B_$token"
sha_a="$(printf %s "$payload_a" | sha256sum | awk '{print $1}')"
sha_b="$(printf %s "$payload_b" | sha256sum | awk '{print $1}')"

cleanup() {
  archphene_adb_run shell rm -f "$source_a" "$source_b" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -f "$import_a" "$import_b" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

long_press_ui_pattern() {
  local ui="$1" pattern="$2" label="$3" center x y
  center="$(archphene_ui_node_center "$ui" "$pattern" "$label")"
  read -r x y <<<"$center"
  archphene_adb_run shell input swipe "$x" "$y" "$x" "$y" 900 >/dev/null
}

archphene_adb_run shell pm path "$package" >/dev/null
for path in "$source_a" "$source_b"; do
  archphene_adb_run shell test ! -e "$path" ||
    archphene_die "refusing to overwrite existing Android fixture: $path"
done
encoded_a="$(printf %s "$payload_a" | base64 -w0)"
encoded_b="$(printf %s "$payload_b" | base64 -w0)"
archphene_adb_run shell mkdir -p /sdcard/Download
archphene_adb_run shell sh -c \
  "'printf %s $encoded_a | base64 -d > $source_a'"
archphene_adb_run shell sh -c \
  "'printf %s $encoded_b | base64 -d > $source_b'"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Started manager-owned Linux process session=' 30 \
  'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
sleep 1
archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_O
archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "gtk3-open-multiple-cancel-picker-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/cancel-ready.png"
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_wait_log \
  'Portal document request ended session=[1-9][0-9]* request=[1-9][0-9]* result=2' \
  20 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell pidof "$package" >/dev/null ||
  archphene_die "launcher exited after cancelling GTK3 multi-file Open"
for path in "$import_a" "$import_b"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$path" ||
    archphene_die "cancelled multi-file Open created a Linux import"
done

archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_O
archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "gtk3-open-multiple-picker-$serial_slug" 20
archphene_open_documents_download_root "$ARCHPHENE_UI" \
  "gtk3-open-multiple-downloads-$serial_slug"
archphene_wait_ui 'content-desc="Search"' \
  "gtk3-open-multiple-downloads-ready-$serial_slug" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'content-desc="Search"' Search
sleep 0.3
archphene_adb_run shell input text "$token"
archphene_wait_ui \
  "text=\"$name_a\"[^>]*resource-id=\"android:id/title\"" \
  "gtk3-open-multiple-result-a-$serial_slug" 20
archphene_regex_contains \
  "$ARCHPHENE_UI" \
  "text=\"$name_b\"[^>]*resource-id=\"android:id/title\"" ||
  archphene_die "DocumentsUI did not show both multi-file fixtures"

long_press_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$name_a\"[^>]*resource-id=\"android:id/title\"" \
  "$name_a result"
archphene_wait_ui \
  "text=\"$name_a\"[^>]*resource-id=\"android:id/title\"[^>]*selected=\"true\"" \
  "gtk3-open-multiple-first-selected-$serial_slug" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$name_b\"[^>]*resource-id=\"android:id/title\"" \
  "$name_b result"
archphene_wait_ui \
  "text=\"$name_b\"[^>]*resource-id=\"android:id/title\"[^>]*selected=\"true\"" \
  "gtk3-open-multiple-both-selected-$serial_slug" 15
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker-ready.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  'text="(?:Open|Select)"[^>]*enabled="true"' \
  "Select documents"

archphene_wait_log \
  'OpenFile completed path=.* broker=0 response=0 emitted=1' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  'Portal document request ended session=[1-9][0-9]* request=[1-9][0-9]* result=1' \
  20 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null

for path in "$import_a" "$import_b"; do
  archphene_adb_run shell run-as "$manager" test -f "$path" ||
    archphene_die "multi-file Open did not publish $path"
done
actual_a="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$import_a" |
    awk '{print $1}' | tr -d '\r'
)"
actual_b="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$import_b" |
    awk '{print $1}' | tr -d '\r'
)"
[[ "$actual_a" == "$sha_a" && "$actual_b" == "$sha_b" ]] ||
  archphene_die "multi-file Open imports were not byte-exact"

archphene_wait_ui \
  "package=\"$package\"" \
  "gtk3-open-multiple-complete-$serial_slug" 20
sleep 0.5
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"
gtk_diagnostic="$(
  archphene_adb_run shell run-as "$manager" cat \
    files/arch-root/home/archphene/.cache/archphene-gtk-settings.log |
    tr -d '\r'
)"
archphene_regex_contains \
  "$gtk_diagnostic" \
  'Application consumed portal (files|filenames|URIs) through GTK' ||
  archphene_die "GTK application did not consume the complete portal selection"

logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E AndroidRuntime:E libc:F '*:S' \
    2>/dev/null || true
)"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "GTK3 multi-file Open emitted a fatal event: $logs"

trap - EXIT
cleanup
archphene_note "Stock GTK3 multi-file Open passed on $serial"
archphene_note "  Cancellation stayed live; both Android selections imported exactly and appeared together in the Linux application"
archphene_note "  Full-device screenshots: $artifact_dir/{cancel-ready,picker-ready,complete}.png"
