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
receiver="$package/org.archphene.app.DocumentsProviderTestReceiver"
authority="$package.documents"
token="$(printf '%08x' "$((RANDOM * 65536 + RANDOM))")"
source_name="Android-$token.txt"
collision_name="Android-$token (2).txt"
source_id="home/Archphene-Import-Source-$token/$source_name"
source_uri="content://$authority/document/${source_id//\//%2F}"
action_create=org.archphene.app.debug.action.CREATE_DOCUMENT_IMPORT_SOURCE
action_verify=org.archphene.app.debug.action.VERIFY_DOCUMENT_IMPORTS
action_clean=org.archphene.app.debug.action.CLEAN_DOCUMENT_IMPORT_SOURCE
output_dir="$ARCHPHENE_ROOT/tooling/build/document-import"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action_clean" \
    --es token "$token" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null

archphene_adb_run shell am broadcast -n "$receiver" -a "$action_create" \
  --es token "$token" >/dev/null
archphene_wait_log "Document import source ready token=$token" \
  15 'ArchpheneDocumentsTest:V *:S' >/dev/null

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -t text/plain -d "$source_uri" -f 1 >/dev/null
first_log="$(archphene_wait_log \
  "Android document imported name=$source_name bytes=[1-9][0-9]*" \
  30 'ArchpheneRuntime:V AndroidRuntime:E *:S')"
[[ "$first_log" != *"Android document import failed"* ]] ||
  archphene_die "ACTION_VIEW import failed: $first_log"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  -a android.intent.action.SEND -c android.intent.category.DEFAULT \
  -t text/plain --eu android.intent.extra.STREAM "$source_uri" -f 1 >/dev/null
second_log="$(archphene_wait_log \
  "Android document imported name=Android-$token \\(2\\)\\.txt bytes=[1-9][0-9]*" \
  30 'ArchpheneRuntime:V AndroidRuntime:E *:S')"
[[ "$second_log" != *"Android document import failed"* ]] ||
  archphene_die "ACTION_SEND import failed: $second_log"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify" \
  --es token "$token" >/dev/null
archphene_wait_log "Document imports verified token=$token" \
  15 'ArchpheneDocumentsTest:V AndroidRuntime:E *:S' >/dev/null

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui_unwrapped \
  "Imported Android-$token \\(2\\)\\.txt .* to ~/Downloads" \
  "document-import-restart-$serial" 25
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify" \
  --es token "$token" >/dev/null
archphene_wait_log "Document imports verified token=$token" \
  15 'ArchpheneDocumentsTest:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

manager_ui="$ARCHPHENE_UI"
archphene_tap_text "$manager_ui" "IMPORT FILE"
archphene_wait_ui 'package="com\.(google\.)?android\.documentsui"' \
  "document-import-picker-$serial" 20
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui_exact_text "IMPORT FILE" "document-import-return-$serial" 15

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Document import emitted a fatal runtime error: $fatal_log"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_clean" \
  --es token "$token" >/dev/null
archphene_wait_log "Document import cleanup passed token=$token" \
  15 'ArchpheneDocumentsTest:V *:S' >/dev/null

trap - EXIT
cleanup
archphene_note "Archphene document import passed on $serial"
archphene_note "  ACTION_VIEW and ACTION_SEND streamed exact bytes into ~/Downloads"
archphene_note "  Collision-safe naming and process-restart status passed"
archphene_note "  Android system picker launched from the manager"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
