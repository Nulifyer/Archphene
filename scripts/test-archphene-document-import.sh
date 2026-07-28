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
action_send_multiple=org.archphene.app.debug.action.SEND_MULTIPLE_DOCUMENT_IMPORT
action_clean=org.archphene.app.debug.action.CLEAN_DOCUMENT_IMPORT_SOURCE
output_dir="$ARCHPHENE_ROOT/tooling/build/document-import"
debug_import_delay_extra=org.archphene.app.extra.DEBUG_DOCUMENT_IMPORT_CHUNK_DELAY_MILLIS
debug_provider_deadline_extra=org.archphene.app.extra.DEBUG_DOCUMENT_IMPORT_PROVIDER_DEADLINE_MILLIS
test_provider_authority="$package.import-test"
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
multiple_handlers="$(
  archphene_adb_run shell cmd package query-activities --brief \
    -a android.intent.action.SEND_MULTIPLE \
    -c android.intent.category.DEFAULT \
    -t text/plain |
    tr -d '\r'
)"
[[ "$multiple_handlers" == *"$activity"* ]] ||
  archphene_die "manager is not registered for ACTION_SEND_MULTIPLE"
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
initial_ui="$(archphene_capture_ui "document-import-onboarding-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "document-import-onboarding-$serial"
fi

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
archphene_open_manager_section Files "document-import-restart-files-$serial"
archphene_wait_ui_unwrapped \
  "Imported Android-$token \\(2\\)\\.txt .* to ~/Downloads" \
  "document-import-restart-$serial" 25
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify" \
  --es token "$token" >/dev/null
archphene_wait_log "Document imports verified token=$token" \
  15 'ArchpheneDocumentsTest:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_send_multiple" \
  --es token "$token" >/dev/null
archphene_wait_log "Document multi-import dispatched token=$token" \
  15 'ArchpheneDocumentsTest:V AndroidRuntime:E *:S' >/dev/null
multi_log="$(archphene_wait_log \
  "Android document imported name=Android-$token-second\\.txt bytes=[1-9][0-9]* item=2/2" \
  30 'ArchpheneRuntime:V AndroidRuntime:E *:S')"
[[ "$multi_log" == *"name=Android-$token (3).txt"*"item=1/2"* ]] ||
  archphene_die "ACTION_SEND_MULTIPLE did not import the collision-numbered first file"
[[ "$multi_log" != *"item=3/"* && "$multi_log" != *"Android document import failed"* ]] ||
  archphene_die "ACTION_SEND_MULTIPLE was not bounded and deduplicated: $multi_log"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify" \
  --es token "$token" --ez expect_multiple true >/dev/null
archphene_wait_log "Document imports verified token=$token" \
  15 'ArchpheneDocumentsTest:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_ui_unwrapped \
  "Imported 2 documents .* to ~/Downloads" \
  "document-import-multiple-$serial" 25
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-multiple.png"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -t text/plain -d "$source_uri" -f 1 \
  --ei "$debug_import_delay_extra" 50 >/dev/null
archphene_wait_ui_unwrapped \
  "Importing 1 of 1: $source_name .* copied" \
  "document-import-progress-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-progress.png"
archphene_tap_text "$ARCHPHENE_UI" "Cancel import"
archphene_wait_ui_unwrapped \
  "Import cancelled after 0 of 1 documents .* kept" \
  "document-import-cancelled-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-cancelled.png"
cancel_log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneRuntime:V AndroidRuntime:E '*:S' 2>/dev/null || true
)"
[[ "$cancel_log" == *"Android document import cancelled item=1/1"* &&
   "$cancel_log" != *"Android document import failed"* ]] ||
  archphene_die "document import cancellation did not finish cleanly: $cancel_log"
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify" \
  --es token "$token" --ez expect_multiple true >/dev/null
archphene_wait_log "Document imports verified token=$token" \
  15 'ArchpheneDocumentsTest:V AndroidRuntime:E *:S' >/dev/null

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -t text/plain -d "content://$test_provider_authority/ignore-open/$token" -f 1 \
  --ei "$debug_provider_deadline_extra" 500 >/dev/null
ignored_open_log="$(archphene_wait_log \
  "Android provider stopped responding while attempting to open the document; terminating the manager" \
  10 'ArchpheneRuntime:V ArchpheneImportProvider:V AndroidRuntime:E *:S')"
[[ "$ignored_open_log" == *"Ignoring descriptor open cancellation token=$token"* ]] ||
  archphene_die "provider did not exercise ignored open cancellation: $ignored_open_log"
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  [[ -z "$(archphene_adb_run shell pidof "$package" 2>/dev/null || true)" ]] && break
  sleep 0.2
done
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Files "document-import-ignored-open-files-$serial"
archphene_wait_ui_unwrapped \
  "The previous document transfer was interrupted. Choose it again." \
  "document-import-ignored-open-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-ignored-open.png"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -t text/plain -d "content://$test_provider_authority/stall-open/$token" -f 1 \
  --ei "$debug_provider_deadline_extra" 500 >/dev/null
archphene_wait_ui_unwrapped \
  "Import failed: Android provider timed out while attempting to open the document" \
  "document-import-open-timeout-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-open-timeout.png"
open_timeout_log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneRuntime:V ArchpheneImportProvider:V AndroidRuntime:E '*:S' 2>/dev/null || true
)"
[[ "$open_timeout_log" == *"Stalling descriptor open token=$token"* &&
   "$open_timeout_log" == *"Android provider timed out while attempting to open the document"* ]] ||
  archphene_die "document provider open timeout was not bounded: $open_timeout_log"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -t text/plain -d "content://$test_provider_authority/stall-read/$token" -f 1 \
  --ei "$debug_provider_deadline_extra" 500 >/dev/null
archphene_wait_ui_unwrapped \
  "Import failed: Android provider stopped sending document data" \
  "document-import-read-timeout-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-read-timeout.png"
read_timeout_log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneRuntime:V ArchpheneImportProvider:V AndroidRuntime:E '*:S' 2>/dev/null || true
)"
[[ "$read_timeout_log" == *"Stalling descriptor read token=$token"* &&
   "$read_timeout_log" == *"Android provider stopped sending document data"* ]] ||
  archphene_die "document provider read timeout was not bounded: $read_timeout_log"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -t text/plain -d "content://$test_provider_authority/paced-read/$token" -f 1 \
  --ei "$debug_provider_deadline_extra" 500 >/dev/null
archphene_wait_log \
  "Android document imported name=Provider-paced-$token\\.txt bytes=[1-9][0-9]*" \
  15 'ArchpheneRuntime:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_ui_unwrapped \
  "Imported Provider-paced-$token\\.txt .* to ~/Downloads" \
  "document-import-paced-read-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-paced-read.png"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -t text/plain -d "content://$test_provider_authority/normal/$token" -f 1 \
  --ei "$debug_provider_deadline_extra" 500 >/dev/null
archphene_wait_log \
  "Android document imported name=Provider-$token\\.txt bytes=[1-9][0-9]*" \
  15 'ArchpheneRuntime:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_verify" \
  --es token "$token" --ez expect_multiple true \
  --ez expect_provider_deadlines true >/dev/null
archphene_wait_log "Document imports verified token=$token" \
  15 'ArchpheneDocumentsTest:V AndroidRuntime:E *:S' >/dev/null

manager_ui="$ARCHPHENE_UI"
archphene_tap_text "$manager_ui" "Import"
archphene_wait_ui 'package="com\.(google\.)?android\.documentsui"' \
  "document-import-picker-$serial" 20
for attempt in 1 2 3 4; do
  picker_ui="$(archphene_capture_ui "document-import-picker-return-$serial-$attempt")"
  if archphene_regex_contains "$picker_ui" 'text="Import"'; then
    break
  fi
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  sleep 0.5
done
archphene_wait_ui 'text="Import"' "document-import-return-$serial" 30

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
archphene_note "  ACTION_SEND_MULTIPLE imported two ordered, deduplicated documents"
archphene_note "  Live byte progress and chunk-boundary cancellation left no file"
archphene_note "  Provider open/read deadlines failed cleanly and paced reads succeeded"
archphene_note "  A provider ignoring open cancellation triggered bounded process recovery"
archphene_note "  Collision-safe naming and process-restart status passed"
archphene_note "  Android system picker launched from the manager"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
archphene_note "  Multiple-import screenshot: $output_dir/$serial-multiple.png"
archphene_note "  Progress screenshot: $output_dir/$serial-progress.png"
archphene_note "  Cancellation screenshot: $output_dir/$serial-cancelled.png"
archphene_note "  Provider-open timeout screenshot: $output_dir/$serial-open-timeout.png"
archphene_note "  Ignored-open recovery screenshot: $output_dir/$serial-ignored-open.png"
archphene_note "  Provider-read timeout screenshot: $output_dir/$serial-read-timeout.png"
archphene_note "  Paced-read screenshot: $output_dir/$serial-paced-read.png"
