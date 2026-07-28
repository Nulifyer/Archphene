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
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/portal-chooser-filters/$serial_slug}"
mkdir -p "$artifact_dir"
token="$(printf '%08x' "$((RANDOM * 65536 + RANDOM))")"
folder="ArchpheneFilterFixture-$token"
source_dir="files/arch-root/home/archphene/$folder"
text_name="filtered-$token.txt"
json_name="filtered-$token.json"
image_name="excluded-$token.png"
text_path="$source_dir/$text_name"
json_path="$source_dir/$json_name"
image_path="$source_dir/$image_name"
imported_path="files/arch-root/home/archphene/Documents/Android/$text_name"
probe_output=cache/portal-filter-probe.out
payload="Archphene_filtered_$token"
expected_sha256="$(printf %s "$payload" | sha256sum | awk '{print $1}')"

cleanup() {
  archphene_adb_run shell run-as "$manager" sh -c \
    "'rm -rf \"$source_dir\"; rm -f \"$imported_path\" \"$probe_output\"'" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell run-as "$manager" test ! -e "$source_dir" ||
  archphene_die "refusing to overwrite chooser fixture: $source_dir"
archphene_adb_run shell run-as "$manager" test ! -e "$imported_path" ||
  archphene_die "refusing to overwrite Linux chooser result: $imported_path"
payload_base64="$(printf %s "$payload" | base64 -w0)"
archphene_adb_run shell run-as "$manager" mkdir -p "$source_dir"
archphene_adb_run shell run-as "$manager" sh -c \
  "'printf %s $payload_base64 | base64 -d > \"$text_path\"; printf %s eyJvayI6dHJ1ZX0K | base64 -d > \"$json_path\"; printf %s iVBORw0KGgo= | base64 -d > \"$image_path\"'"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private desktop portal ready session=' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_prepare_portal_probe "$manager"
archphene_run_portal_probe "$manager" open-filtered "$probe_output"

archphene_wait_log \
  'Opening Android document chooser baseMime=\*/\* mimeCount=2' 20 \
  'ArchpheneLauncher:I AndroidRuntime:E *:S' >/dev/null
archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "portal-filter-picker-$serial_slug" 20
archphene_open_documents_archphene_home_root \
  "$ARCHPHENE_UI" "portal-filter-home-$serial_slug"
archphene_wait_ui_exact_text "$folder" "portal-filter-folder-$serial_slug" 20
archphene_tap_text "$ARCHPHENE_UI" "$folder"
archphene_wait_ui_exact_text "$text_name" "portal-filter-text-$serial_slug" 20

ui="$ARCHPHENE_UI"
[[ "$ui" == *"text=\"$json_name\""* ]] ||
  archphene_die "JSON file allowed by the chooser filter is not visible"
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker-filtered.png"
if [[ "$ui" == *"text=\"$image_name\""* ]]; then
  archphene_regex_contains \
    "$ui" "text=\"$image_name\"[^>]*enabled=\"false\"" ||
    archphene_die "PNG file excluded by the chooser filter remains selectable"
fi
archphene_tap_text "$ui" "$text_name"

archphene_wait_log \
  'OpenFile completed path=.* broker=0 response=0 emitted=1' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell run-as "$manager" test -f "$imported_path" ||
  archphene_die "filtered chooser did not publish the selected document"
actual_sha256="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$imported_path" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$actual_sha256" == "$expected_sha256" ]] ||
  archphene_die "filtered chooser changed the selected document"
probe_result="$(
  archphene_adb_run shell run-as "$manager" cat "$probe_output" |
    tr -d '\r'
)"
[[ "$probe_result" == "PASS portal filtered document selected" ]] ||
  archphene_die "filtered portal probe failed: $probe_result"

archphene_wait_ui "package=\"$package\"" "portal-filter-complete-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncher:E ArchpheneLauncherSession:E \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "filtered chooser emitted a fatal event: $logs"

trap - EXIT
cleanup
archphene_note "Portal chooser MIME filters passed on $serial"
archphene_note "  text/plain and application/json remained selectable; image/png was disabled or hidden"
archphene_note "  Full-device screenshots: $artifact_dir/{picker-filtered,complete}.png"
