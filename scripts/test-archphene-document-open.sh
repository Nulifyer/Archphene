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
token="$(printf '%08x' "$((RANDOM * 65536 + RANDOM))")"
fixture="Archphene-Open-$token.txt"
fixture_path="files/arch-root/home/archphene/Shared/$fixture"
document_uri="content://$package.documents/document/home%2FShared%2F$fixture"
output_dir="$ARCHPHENE_ROOT/tooling/build/document-open"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f "$fixture_path" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.intentresolver \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
printf 'Archphene outbound open proof %s\n' "$token" |
  archphene_adb_run shell run-as "$package" tee "$fixture_path" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null

initial_ui="$(archphene_capture_ui "document-open-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_tap_ui_pattern \
    "$initial_ui" 'text="(?:NOT NOW|Not now)"' "Not now"
fi
archphene_open_manager_section Files "document-open-files-$serial"
archphene_wait_ui_exact_text "Open" "document-open-action-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Open"

archphene_wait_ui_exact_text \
  "Archphene Home" "document-open-picker-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-picker.png"
archphene_wait_ui_exact_text "Shared" "document-open-shared-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Shared"
archphene_wait_ui_exact_text "$fixture" "document-open-fixture-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "$fixture"

deadline=$((SECONDS + 20))
activities=
while ((SECONDS < deadline)); do
  activities="$(archphene_adb_run shell dumpsys activity activities)"
  if [[ "$activities" == *ChooserActivity* &&
        "$activities" == *"$document_uri"* &&
        "$activities" == *"clip={text/plain"* ]]; then
    break
  fi
  sleep 0.5
done
printf '%s\n' "$activities" >"$output_dir/$serial-activities.txt"
[[ "$activities" == *ChooserActivity* ]] ||
  archphene_die "Android open chooser did not open"
[[ "$activities" == *"$document_uri"* ]] ||
  archphene_die "open chooser did not receive the exact Archphene URI"
[[ "$activities" == *"clip={text/plain"* ]] ||
  archphene_die "open chooser did not receive the expected MIME clip"
chooser_record="$(
  python3 -c '
import sys
lines = sys.stdin.read().splitlines()
capturing = False
for line in lines:
    if line.lstrip().startswith("* Hist"):
        if capturing:
            break
        capturing = "ChooserActivityLauncher" in line
    if capturing:
        print(line)
' <<<"$activities"
)"
[[ "$chooser_record" == *"readUriPermissions"* &&
    "$chooser_record" == *"$document_uri"* ]] ||
  archphene_die "open chooser did not receive a read URI grant"
[[ "$chooser_record" != *"writeUriPermissions"* ]] ||
  archphene_die "open chooser unexpectedly received a write URI grant"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-chooser.png"
archphene_wait_log 'Android document handoff persisted' 15 >/dev/null

archphene_adb_run shell am force-stop com.android.intentresolver >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Files "document-open-restart-files-$serial"
archphene_wait_ui_exact_text \
  "Offered $fixture to Android viewers" \
  "document-open-restart-status-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-restart.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --ez org.archphene.app.extra.DEBUG_DOCUMENT_HANDOFF_FAILURE true >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Files "document-open-failure-files-$serial"
archphene_wait_ui_exact_text "Open" "document-open-failure-action-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Open"
archphene_wait_ui_exact_text \
  "Archphene Home" "document-open-failure-picker-$serial" 20
archphene_wait_ui_exact_text "Shared" "document-open-failure-shared-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Shared"
archphene_wait_ui_exact_text \
  "$fixture" "document-open-failure-fixture-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "$fixture"
archphene_wait_log 'Android document handoff failure persisted' 15 >/dev/null
archphene_wait_ui_exact_text \
  "Could not offer $fixture to Android viewers" \
  "document-open-failure-status-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-failure.png"
failure_activities="$(archphene_adb_run shell dumpsys activity activities)"
[[ "$failure_activities" != *ChooserActivity* ]] ||
  archphene_die "forced open failure unexpectedly launched Android's chooser"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Files "document-open-failure-restart-files-$serial"
archphene_wait_ui_exact_text \
  "Could not offer $fixture to Android viewers" \
  "document-open-failure-restart-status-$serial" 15
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-failure-restart.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Document open emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene document open passed on $serial"
archphene_note "  Picker opened at Archphene Home and selected the exact Shared fixture"
archphene_note "  Android chooser received one read-only text/plain ACTION_VIEW URI"
archphene_note "  Chooser handoff and forced failure statuses survived manager restart"
archphene_note \
  "  Full-device screenshots: $output_dir/$serial-{picker,chooser,restart,failure,failure-restart}.png"
