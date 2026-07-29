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
fixture="Archphene-Recovery-$token.bin"
fixture_path="files/arch-root/home/archphene/Shared/$fixture"
target_path="/sdcard/Download/$fixture"
fixture_bytes=$((8 * 1024 * 1024))
output_dir="$ARCHPHENE_ROOT/tooling/build/document-export-recovery"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f "$fixture_path" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$target_path" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

begin_export() {
  local name="$1"
  archphene_open_manager_section Files "$name-files-$serial"
  archphene_wait_ui_exact_text "Export" "$name-export-$serial" 15
  read -r export_x export_y <<<"$(
    archphene_ui_node_center "$ARCHPHENE_UI" 'text="Export"' "Export"
  )"
  archphene_tap_text "$ARCHPHENE_UI" "Export"
  archphene_wait_ui_exact_text "Archphene Home" "$name-source-$serial" 20
  archphene_wait_ui_exact_text "Shared" "$name-shared-$serial" 15
  archphene_tap_text "$ARCHPHENE_UI" "Shared"
  archphene_wait_ui_exact_text "$fixture" "$name-fixture-$serial" 15
  archphene_tap_text "$ARCHPHENE_UI" "$fixture"
  archphene_wait_ui_exact_text "$fixture" "$name-target-$serial" 20
  archphene_open_documents_download_root "$ARCHPHENE_UI" "$name-download-$serial"
  archphene_wait_ui \
    'text="(?:SAVE|Save)"[^>]*enabled="true"' "$name-save-$serial" 15
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="(?:SAVE|Save)"[^>]*enabled="true"' "Save"
}

archphene_adb_run shell rm -f "$target_path" >/dev/null
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
head -c "$fixture_bytes" /dev/zero |
  tr '\0' 'R' |
  archphene_adb_run shell run-as "$package" tee "$fixture_path" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" \
  --ei org.archphene.app.extra.DEBUG_DOCUMENT_EXPORT_CHUNK_DELAY_MILLIS 50 \
  >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null

initial_ui="$(archphene_capture_ui "document-export-recovery-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_tap_ui_pattern \
    "$initial_ui" 'text="(?:NOT NOW|Not now)"' "Not now"
fi

begin_export "document-export-cancel"
sleep 1
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-progress.png"
archphene_adb_run shell input tap "$export_x" "$export_y" >/dev/null
sleep 0.3
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-cancelling.png"
archphene_wait_ui_text \
  "Export cancelled" "document-export-cancelled-$serial" 60
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-cancelled.png"
if archphene_adb_run shell test -e "$target_path"; then
  archphene_die "cancelled export kept its incomplete Android target"
fi

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" \
  --ei org.archphene.app.extra.DEBUG_DOCUMENT_EXPORT_CHUNK_DELAY_MILLIS 50 \
  >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
begin_export "document-export-interrupted"
deadline=$((SECONDS + 15))
partial_bytes=0
while ((SECONDS < deadline)); do
  partial_bytes="$(
    archphene_adb_run shell stat -c %s "$target_path" 2>/dev/null |
      tr -d '\r' || true
  )"
  if [[ "$partial_bytes" =~ ^[0-9]+$ ]] &&
      ((partial_bytes > 0 && partial_bytes < fixture_bytes)); then
    break
  fi
  sleep 0.05
done
[[ "$partial_bytes" =~ ^[0-9]+$ ]] &&
  ((partial_bytes > 0 && partial_bytes < fixture_bytes)) ||
  archphene_die "did not observe a partial Android export before timeout"
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell test -e "$target_path" ||
  archphene_die "process death did not leave the intended partial recovery fixture"

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Files "document-export-recovered-files-$serial"
archphene_wait_ui_text \
  "Removed an incomplete Android export" \
  "document-export-recovered-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-recovered.png"
if archphene_adb_run shell test -e "$target_path"; then
  archphene_die "restart recovery kept the incomplete Android target"
fi

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Export recovery emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene export cancellation/recovery passed on $serial"
archphene_note "  Rust exposed bounded progress and cancelled at a chunk boundary"
archphene_note "  Restart removed a real nonempty partial Android destination"
archphene_note \
  "  Full-device screenshots: $output_dir/$serial-{progress,cancelled,recovered}.png"
