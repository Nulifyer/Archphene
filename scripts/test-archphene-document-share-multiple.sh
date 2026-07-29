#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
token="$(printf '%08x' "$((RANDOM * 65536 + RANDOM))")"
fixture_a="Archphene-Multi-A-$token.txt"
fixture_b="Archphene-Multi-B-$token.txt"
fixture_dir="files/arch-root/home/archphene/Shared"
document_uri_a="content://$package.documents/document/home%2FShared%2F$fixture_a"
document_uri_b="content://$package.documents/document/home%2FShared%2F$fixture_b"
output_dir="$ARCHPHENE_ROOT/tooling/build/document-share-multiple"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f \
    "$fixture_dir/$fixture_a" "$fixture_dir/$fixture_b" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.intentresolver \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
printf 'Archphene multi-share proof A %s\n' "$token" |
  archphene_adb_run shell run-as "$package" tee "$fixture_dir/$fixture_a" >/dev/null
printf 'Archphene multi-share proof B %s\n' "$token" |
  archphene_adb_run shell run-as "$package" tee "$fixture_dir/$fixture_b" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null

initial_ui="$(archphene_capture_ui "document-multi-share-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_tap_ui_pattern \
    "$initial_ui" 'text="(?:NOT NOW|Not now)"' "Not now"
fi
archphene_open_manager_section Files "document-multi-share-files-$serial"
archphene_wait_ui_exact_text "Share" "document-multi-share-action-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Share"

archphene_wait_ui_exact_text \
  "Archphene Home" "document-multi-share-picker-$serial" 20
archphene_wait_ui_exact_text "Shared" "document-multi-share-shared-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Shared"
archphene_wait_ui_exact_text \
  "$fixture_a" "document-multi-share-fixture-a-$serial" 15
ui="$ARCHPHENE_UI"
escaped_a="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$fixture_a")"
read -r fixture_a_x fixture_a_y <<<"$(
  archphene_ui_node_center "$ui" "text=\"$escaped_a\"" "$fixture_a"
)"
archphene_adb_run shell input swipe \
  "$fixture_a_x" "$fixture_a_y" "$fixture_a_x" "$fixture_a_y" 800

archphene_wait_ui_exact_text \
  "$fixture_b" "document-multi-share-fixture-b-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "$fixture_b"
archphene_wait_ui \
  '(?:text|content-desc)="(?:SELECT|Select|OPEN|Open)"[^>]*enabled="true"' \
  "document-multi-share-select-$serial" 15
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-selection.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  '(?:text|content-desc)="(?:SELECT|Select|OPEN|Open)"[^>]*enabled="true"' \
  "Select files"

deadline=$((SECONDS + 20))
activities=
while ((SECONDS < deadline)); do
  activities="$(archphene_adb_run shell dumpsys activity activities)"
  if [[ "$activities" == *ChooserActivity* &&
        "$activities" == *"$document_uri_a"* &&
        "$activities" == *"$document_uri_b"* &&
        "$activities" == *"clip={text/plain"* ]]; then
    break
  fi
  sleep 0.5
done
[[ "$activities" == *ChooserActivity* ]] ||
  archphene_die "Android share chooser did not open"
[[ "$activities" == *"$document_uri_a"* &&
    "$activities" == *"$document_uri_b"* ]] ||
  archphene_die "share chooser did not receive both exact Archphene URIs"
[[ "$activities" == *"clip={text/plain"* ]] ||
  archphene_die "share chooser did not receive the expected common MIME clip"
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
for document_uri in "$document_uri_a" "$document_uri_b"; do
  [[ "$chooser_record" == *"readUriPermissions"* &&
      "$chooser_record" == *"$document_uri"* ]] ||
    archphene_die "share chooser did not receive a read grant for $document_uri"
done
[[ "$chooser_record" != *"writeUriPermissions"* ]] ||
  archphene_die "share chooser unexpectedly received a write URI grant"
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-chooser.png"
archphene_wait_log 'Android document handoff persisted' 15 >/dev/null

archphene_adb_run shell am force-stop com.android.intentresolver >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Files "document-multi-share-restart-files-$serial"
archphene_wait_ui_exact_text \
  "Opened Android sharing for 2 Linux files" \
  "document-multi-share-restart-status-$serial" 15
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-restart.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Multi-document share emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene multi-document share passed on $serial"
archphene_note "  DocumentsUI selected two exact Shared fixtures"
archphene_note "  Android chooser received two read-only text/plain content URIs"
archphene_note "  Multi-file sharing handoff status survived manager restart"
archphene_note \
  "  Full-device screenshots: $output_dir/$serial-{selection,chooser,restart}.png"
