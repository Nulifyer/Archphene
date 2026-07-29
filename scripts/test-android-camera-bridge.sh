#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

abi=x86_64
serial=emulator-5554
timeout=30
apk=
clean_data=false
while (($#)); do
  case "$1" in
    --android-abi) abi="${2:?}"; shift 2 ;;
    --serial) serial="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --apk) apk="${2:?}"; shift 2 ;;
    --clean-data) clean_data=true; shift ;;
    -h|--help)
      echo "usage: $0 [--android-abi x86_64|arm64-v8a] [--serial SERIAL] [--timeout-seconds N] [--apk PATH] --clean-data"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$clean_data" == true ]] ||
  archphene_die "--clean-data is required because this gate clears camera-probe app data"
archphene_validate_choice "$abi" ABI x86_64 arm64-v8a
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 15 && timeout <= 120)) \
  || archphene_die '--timeout-seconds must be 15..120'
archphene_test_init "$serial"

package=org.archphene.cameraprobe
activity="$package/org.archphene.bridge.CameraProbeActivity"
apk="${apk:-$ARCHPHENE_ROOT/prototypes/camera-capability-probe/out-$abi/archphene-camera-probe.apk}"
permission_pattern='resource-id="[^"]*:id/permission_(allow_[^"]+|deny)_button"'
archphene_require_file "$apk"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f /sdcard/archphene-camera.xml \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

native_path() {
  local leaf="${1:-libarchphene_camera_probe.so}" native subdirectory
  native="$(archphene_adb_run shell dumpsys package "$package" \
    | sed -n 's/.*legacyNativeLibraryDir=\([^[:space:]]*\).*/\1/p' \
    | head -n1 | tr -d '\r')"
  [[ -n "$native" ]] \
    || archphene_die 'camera probe native library directory is unavailable'
  if [[ "$abi" == arm64-v8a ]]; then subdirectory=arm64; else subdirectory=x86_64; fi
  printf '%s/%s/%s' "$native" "$subdirectory" "$leaf"
}

start_probe() {
  local deadline socket logs
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    socket="$(archphene_adb_run shell run-as "$package" \
      cat files/camera-broker-name 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$socket" ]]; then
      printf '%s' "$socket"
      return 0
    fi
    sleep 0.2
  done
  logs="$(archphene_adb_run logcat -d -v brief \
    -s ArchpheneCapabilities:I AndroidRuntime:E '*:S')"
  archphene_die "camera broker did not start: $logs"
}

invoke_probe() {
  local allow_failure="$1" socket="$2"
  shift 2
  local output status native
  native="$(native_path)"
  set +e
  output="$(archphene_adb_run shell run-as "$package" "$native" \
    --socket "@$socket" "$@" 2>&1 | tr -d '\r')"
  status=$?
  set -e
  if [[ "$allow_failure" == false && $status -ne 0 ]]; then
    archphene_die "camera capability request failed: $output"
  fi
  printf '%s' "$output"
}

invoke_portal() {
  local command="$1" bus native output status
  bus="$(archphene_adb_run shell run-as "$package" \
    cat files/camera-bus-address 2>/dev/null | tr -d '\r')"
  [[ -n "$bus" ]] || archphene_die 'camera portal session bus is unavailable'
  native="$(native_path libarchphene_portal_probe.so)"
  set +e
  output="$(archphene_adb_run shell run-as "$package" env -e "$native" \
    "DBUS_SESSION_BUS_ADDRESS=$bus" archphene-portal-probe "$command" \
    2>&1 | tr -d '\r')"
  status=$?
  set -e
  ((status == 0)) || archphene_die "camera portal request failed: $output"
  printf '%s' "$output"
}

get_ui() {
  archphene_adb_run shell rm -f /sdcard/archphene-camera.xml \
    >/dev/null 2>&1 || true
  archphene_capture_ui archphene-camera 2>/dev/null || true
}

wait_permission_prompt() {
  local deadline=$((SECONDS + timeout)) ui
  while ((SECONDS < deadline)); do
    ui="$(get_ui)"
    if archphene_regex_contains "$ui" "$permission_pattern"; then
      printf '%s' "$ui"
      return 0
    fi
    sleep 0.4
  done
  archphene_die 'camera request did not display an Android permission prompt'
}

select_permission() {
  local action="$1" ui="$2" pattern attempt center x y state
  if [[ "$action" == grant ]]; then
    pattern='resource-id="[^"]*:id/(?:permission_allow_(?:foreground_only|one_time)_button|permission_allow_button)"'
  else
    pattern='resource-id="[^"]*:id/permission_deny_button"'
  fi
  for attempt in 0 1 2; do
    ((attempt == 0)) || ui="$(get_ui)"
    if ! archphene_regex_contains "$ui" "$pattern"; then
      return 0
    fi
    center="$(archphene_ui_node_center "$ui" "$pattern" "$action camera permission")"
    read -r x y <<<"$center"
    archphene_adb_run shell input tap "$x" "$y" >/dev/null
    for _ in {1..10}; do
      sleep 0.2
      state="$(invoke_probe true "$socket" check-camera)"
      [[ "$state" == $'ERROR\tPERMISSION_REQUESTED' ]] || return 0
    done
  done
  archphene_die "camera permission action $action did not close the prompt"
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run logcat -c
socket="$(start_probe)"

initial="$(invoke_probe true "$socket" check-camera)"
[[ "$initial" == $'ERROR\tPERMISSION_NOT_REQUESTED' ]] \
  || archphene_die "unexpected initial camera state: $initial"
before="$(invoke_probe true "$socket" capture-camera-jpeg \
  files/before.jpg 1280 720 back)"
[[ "$before" == $'ERROR\tPERMISSION_NOT_REQUESTED' ]] \
  || archphene_die "camera capture bypassed Android permission: $before"
requested="$(invoke_probe true "$socket" request-camera)"
[[ "$requested" == $'ERROR\tPERMISSION_REQUESTED' ]] \
  || archphene_die "camera request did not enter the permission state: $requested"
permission_ui="$(wait_permission_prompt)"
select_permission grant "$permission_ui"
granted="$(invoke_probe false "$socket" check-camera)"
[[ "$granted" == OK ]] || archphene_die "camera permission was not granted: $granted"

portal_contract="$(invoke_portal contract)"
archphene_regex_contains "$portal_contract" 'PASS portal Camera version=1 present=true' \
  || archphene_die "camera portal contract is incomplete: $portal_contract"
portal_access="$(invoke_portal camera-access)"
archphene_regex_contains "$portal_access" 'PASS portal camera access accepted' \
  || archphene_die "camera portal access failed: $portal_access"
portal_remote="$(invoke_portal camera-open)"
archphene_regex_contains "$portal_remote" 'PASS portal PipeWire remote descriptor' \
  || archphene_die "camera portal did not transfer its private remote: $portal_remote"

capture="$(invoke_probe false "$socket" capture-camera-jpeg \
  files/camera-test.jpg 1280 720 back)"
IFS=$'\t' read -r capture_status capture_width capture_height reported_bytes capture_extra \
  <<<"$capture"
[[ "$capture_status" == OK && "$capture_width" == 1280 \
    && "$capture_height" == 720 && "$reported_bytes" =~ ^[0-9]+$ \
    && -z "${capture_extra:-}" ]] \
  || archphene_die "camera capture metadata is invalid: $capture"
actual_bytes="$(archphene_adb_run shell run-as "$package" \
  stat -c %s files/camera-test.jpg | tr -d '\r')"
[[ "$actual_bytes" =~ ^[0-9]+$ && "$reported_bytes" == "$actual_bytes" ]] \
  && ((actual_bytes >= 1024)) \
  || archphene_die "camera JPEG byte count is invalid: reported=$reported_bytes actual=$actual_bytes"
header="$(archphene_adb_run shell run-as "$package" \
  od -An -tx1 -N2 files/camera-test.jpg | xargs)"
[[ "$header" == 'ff d8' ]] || archphene_die "camera output is not a JPEG: $header"

stream_native="$(native_path libarchphene_camera_stream_probe.so)"
set +e
stream="$(archphene_adb_run shell run-as "$package" "$stream_native" \
  --socket "@$socket" 2>&1 | tr -d '\r')"
stream_status=$?
set -e
((stream_status == 0)) \
  && archphene_regex_contains "$stream" 'PASS camera I420 stream frames=3 bytes=460800' \
  || archphene_die "camera I420 stream failed: $stream"
invalid="$(invoke_probe true "$socket" capture-camera-jpeg \
  files/invalid.jpg 0 720 back)"
[[ "$invalid" == 'invalid camera capture arguments' ]] \
  || archphene_die "invalid camera dimensions were accepted: $invalid"
grant_logs="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneCapabilities:I AndroidRuntime:E '*:S')"
[[ "$grant_logs" == *'Captured Android camera JPEG'* \
    && "$grant_logs" != *'FATAL EXCEPTION'* ]] \
  || archphene_die "camera grant/capture path did not complete cleanly: $grant_logs"

archphene_adb_run shell pm clear "$package" >/dev/null
socket="$(start_probe)"
requested="$(invoke_probe true "$socket" request-camera)"
[[ "$requested" == $'ERROR\tPERMISSION_REQUESTED' ]] \
  || archphene_die "camera denial fixture did not request permission: $requested"
permission_ui="$(wait_permission_prompt)"
select_permission deny "$permission_ui"
denied="$(invoke_probe true "$socket" check-camera)"
[[ "$denied" == $'ERROR\tPERMISSION_DENIED' ]] \
  || archphene_die "camera denial was not persisted: $denied"
no_reprompt="$(invoke_probe true "$socket" request-camera)"
[[ "$no_reprompt" == $'ERROR\tPERMISSION_DENIED' ]] \
  || archphene_die "camera denial unexpectedly reprompted: $no_reprompt"
sleep 0.5
ui_after="$(get_ui)"
! archphene_regex_contains "$ui_after" "$permission_pattern" \
  || archphene_die 'camera denial left or reopened the Android permission prompt'
denial_logs="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneCapabilities:I AndroidRuntime:E '*:S')"
[[ "$denial_logs" != *'FATAL EXCEPTION'* ]] \
  || archphene_die "camera denial path crashed: $denial_logs"

cleanup
trap - EXIT
archphene_note "Android camera bridge passed on $serial ($abi): exact 1280x720 JPEG ($actual_bytes bytes), I420 streaming, private Camera portal, invalid-input rejection, grant, denial, and no-reprompt validated."
