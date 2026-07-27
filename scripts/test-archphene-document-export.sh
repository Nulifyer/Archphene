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
fixture="Archphene-Export-$token.txt"
fixture_path="files/arch-root/home/archphene/Shared/$fixture"
target_path="/sdcard/Download/$fixture"
payload="Archphene descriptor export proof $token"
expected_sha256="$(printf '%s' "$payload" | sha256sum | awk '{print $1}')"
output_dir="$ARCHPHENE_ROOT/tooling/build/document-export"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f "$fixture_path" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$target_path" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell rm -f "$target_path" >/dev/null
archphene_adb_run install -r "$apk" >/dev/null
printf '%s' "$payload" |
  archphene_adb_run shell run-as "$package" tee "$fixture_path" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null

initial_ui="$(archphene_capture_ui "document-export-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_tap_ui_pattern \
    "$initial_ui" 'text="(?:NOT NOW|Not now)"' "Not now"
fi
archphene_open_manager_section Files "document-export-files-$serial"
archphene_wait_ui_exact_text "Export" "document-export-action-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-files.png"
archphene_tap_text "$ARCHPHENE_UI" "Export"

archphene_wait_ui_exact_text \
  "Archphene Home" "document-export-source-picker-$serial" 20
archphene_wait_ui_exact_text "Shared" "document-export-shared-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Shared"
archphene_wait_ui_exact_text "$fixture" "document-export-fixture-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "$fixture"

archphene_wait_ui_exact_text "$fixture" "document-export-target-picker-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-target-picker.png"
cancelled_ui=
for attempt in 1 2 3; do
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  sleep 0.5
  cancelled_ui="$(
    archphene_capture_ui "document-export-cancelled-$attempt-$serial" \
      2>/dev/null || true
  )"
  if archphene_regex_contains \
    "$cancelled_ui" \
    'text="Files"[^>]*class="android\.widget\.Button"[^>]*selected="true"'; then
    break
  fi
done
archphene_wait_ui \
  'text="Files"[^>]*class="android\.widget\.Button"[^>]*selected="true"' \
  "document-export-cancelled-$serial" 20
archphene_wait_ui_exact_text "Export" "document-export-retry-$serial" 15
if archphene_adb_run shell test -e "$target_path"; then
  archphene_die "cancelled export unexpectedly created an Android target"
fi
archphene_tap_text "$ARCHPHENE_UI" "Export"
archphene_wait_ui_exact_text \
  "Archphene Home" "document-export-source-retry-$serial" 20
archphene_wait_ui_exact_text "Shared" "document-export-shared-retry-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Shared"
archphene_wait_ui_exact_text "$fixture" "document-export-fixture-retry-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "$fixture"
archphene_wait_ui_exact_text "$fixture" "document-export-target-retry-$serial" 20
archphene_open_documents_download_root \
  "$ARCHPHENE_UI" "document-export-download-$serial"
archphene_wait_ui \
  'text="(?:SAVE|Save)"[^>]*enabled="true"' \
  "document-export-save-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-download-target.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:SAVE|Save)"[^>]*enabled="true"' "Save"

archphene_wait_log \
  "Linux document exported name=$fixture bytes=${#payload}" 30 >/dev/null
archphene_open_manager_section Files "document-export-complete-files-$serial"
archphene_wait_ui_text \
  "Saved a copy of $fixture" "document-export-complete-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-complete.png"

linux_sha256="$(
  archphene_adb_run shell run-as "$package" sha256sum "$fixture_path" |
    awk '{print $1}' | tr -d '\r'
)"
android_sha256="$(
  archphene_adb_run shell sha256sum "$target_path" |
    awk '{print $1}' | tr -d '\r'
)"
[[ "$linux_sha256" == "$expected_sha256" ]] ||
  archphene_die "Linux source changed during export"
[[ "$android_sha256" == "$expected_sha256" ]] ||
  archphene_die "Android export does not match the Linux source"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Files "document-export-restart-files-$serial"
archphene_wait_ui_text \
  "Saved a copy of $fixture" "document-export-restart-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-restart.png"

raw_log="$output_dir/$serial-log.txt"
archphene_adb_run logcat -d -v threadtime \
  StrictMode:D AndroidRuntime:E libc:F '*:S' >"$raw_log"
python3 - "$raw_log" <<'PY'
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
marker = "StrictMode policy violation; "
owned = []
for index, line in enumerate(lines):
    if marker not in line:
        continue
    block = []
    for candidate in lines[index + 1 :]:
        if marker in candidate:
            break
        block.append(candidate)
    if any("\tat org.archphene." in candidate for candidate in block):
        owned.append("\n".join([line, *block]))
fatal = [
    line
    for line in lines
    if "FATAL EXCEPTION" in line or "Fatal signal" in line
]
if owned:
    print("\n\n".join(owned), file=sys.stderr)
    raise SystemExit(f"{len(owned)} Archphene StrictMode violation(s)")
if fatal:
    print("\n".join(fatal), file=sys.stderr)
    raise SystemExit(f"{len(fatal)} fatal runtime event(s)")
PY

trap - EXIT
cleanup
archphene_note "Archphene document export passed on $serial"
archphene_note "  Rust copied the exact Linux source descriptor into Android Downloads"
archphene_note \
  "  Source preservation, durable completion, cleanup, StrictMode, and fatal logs passed"
archphene_note \
  "  Full-device screenshots: $output_dir/$serial-{files,target-picker,download-target,complete,restart}.png"
