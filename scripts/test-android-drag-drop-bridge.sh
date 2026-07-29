#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

abi=x86_64
serial=emulator-5554
while (($#)); do
  case "$1" in
    --android-abi) abi="${2:?missing value for --android-abi}"; shift 2 ;;
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--android-abi x86_64|arm64-v8a] [--serial SERIAL]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_validate_choice "$abi" ABI x86_64 arm64-v8a
archphene_test_init "$serial"
compositor_package=org.archphene.compositorprobe
receiver_package=org.archphene.urigrantprobe
apk="$ARCHPHENE_ROOT/prototypes/native-compositor-probe/out-$abi/archphene-compositor-probe.apk"
receiver_apk="$ARCHPHENE_ROOT/prototypes/uri-grant-receiver-probe/out-$abi/archphene-uri-grant-receiver-probe.apk"
archphene_require_file "$apk"
archphene_require_file "$receiver_apk"

safe_serial="${serial//[^A-Za-z0-9._-]/_}"
output_dir="$ARCHPHENE_ROOT/tooling/build/android-drag-drop/$safe_serial"
mkdir -p "$output_dir"
backup_dir="$(mktemp -d)"
compositor_was_installed=false
receiver_was_installed=false
compositor_was_running=false

installed_apk_path() {
  archphene_adb_run shell pm path "$1" 2>/dev/null |
    sed -n 's/^package://p' |
    head -n1 |
    tr -d '\r'
}

backup_installed_apk() {
  local package="$1" destination="$2" path
  path="$(installed_apk_path "$package")"
  [[ -n "$path" ]] || return 1
  archphene_adb_run exec-out cat "$path" >"$destination"
}

if backup_installed_apk "$compositor_package" "$backup_dir/compositor.apk"; then
  compositor_was_installed=true
fi
if backup_installed_apk "$receiver_package" "$backup_dir/receiver.apk"; then
  receiver_was_installed=true
fi
if archphene_android_pid "$compositor_package" >/dev/null 2>&1; then
  compositor_was_running=true
fi
cleanup() {
  set +e
  archphene_adb_run shell am force-stop "$compositor_package" >/dev/null 2>&1
  if [[ "$compositor_was_installed" == true ]]; then
    archphene_adb_run install -r -d "$backup_dir/compositor.apk" >/dev/null 2>&1
  else
    archphene_adb_run uninstall "$compositor_package" >/dev/null 2>&1
  fi
  if [[ "$receiver_was_installed" == true ]]; then
    archphene_adb_run install -r -d "$backup_dir/receiver.apk" >/dev/null 2>&1
  else
    archphene_adb_run uninstall "$receiver_package" >/dev/null 2>&1
  fi
  if [[ "$compositor_was_running" == true ]]; then
    archphene_adb_run shell monkey -p "$compositor_package" \
      -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  else
    archphene_adb_run shell am force-stop "$compositor_package" >/dev/null 2>&1
  fi
  archphene_adb_run shell am force-stop "$receiver_package" >/dev/null 2>&1
  rm -rf "$backup_dir"
}
trap cleanup EXIT

wait_probe() {
  local passed="$1" failed="$2" label="$3" log
  log="$(
    archphene_wait_log "$passed|$failed" 30 \
      'ArchpheneCompositorProbe:I *:S'
  )"
  [[ "$log" == *"$passed"* ]] ||
    archphene_die "$label reported failure: $log"
}

wait_uri_read() {
  local should_pass="$1" expected rejected log
  if [[ "$should_pass" == true ]]; then
    expected='URI read passed ARCHPHENE_OUTBOUND_URI_GRANT'
    rejected='URI read denied'
  else
    expected='URI read denied'
    rejected='URI read passed'
  fi
  log="$(
    archphene_wait_log "$expected|$rejected" 15 \
      'ArchpheneUriGrantProbe:I *:S'
  )"
  [[ "$log" == *"$expected"* && "$log" != *"$rejected"* ]] ||
    archphene_die "provider URI result violated grant policy: $log"
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run install -r "$receiver_apk" >/dev/null

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$compositor_package"
archphene_adb_run shell am start \
  -n "$compositor_package/.MainActivity" --ez drag_only true >/dev/null
wait_probe \
  'Native drag-and-drop probe passed' \
  'Native drag-and-drop probe failed' \
  'Wayland drag-and-drop probe'

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$compositor_package"
archphene_adb_run shell am start \
  -n "$compositor_package/.MainActivity" --ez document_drag_only true >/dev/null
wait_probe \
  'Document drag broker probe passed' \
  'Document drag broker probe failed' \
  'document drag broker probe'

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$compositor_package"
archphene_adb_run shell am start \
  -n "$compositor_package/.MainActivity" \
  --ez provider_grant_only true \
  --ez grant_provider_uri false >/dev/null
wait_probe \
  'Provider URI grant probe prepared' \
  'Provider URI grant probe failed' \
  'ungranted provider URI preparation'
wait_uri_read false

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$compositor_package"
archphene_adb_run shell am start \
  -n "$compositor_package/.MainActivity" \
  --ez provider_grant_only true \
  --ez grant_provider_uri true >/dev/null
wait_probe \
  'Provider URI grant probe ready' \
  'Provider URI grant probe failed' \
  'granted provider URI preparation'
wait_uri_read true
archphene_adb_run exec-out screencap -p >"$output_dir/provider-granted.png"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null ||
    true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "drag-and-drop probes emitted a fatal runtime error: $fatal_log"

cleanup
trap - EXIT
archphene_note \
  "Android/Wayland text, document, and grant-scoped drag-and-drop passed on $serial ($abi)."
archphene_note "  Full-device screenshot: $output_dir/provider-granted.png"
