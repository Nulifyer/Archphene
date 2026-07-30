#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archphene.app.debug
wrapper=org.archphene.linux.pee4b7705a9f0d0ef0130cb119f25e6d4
timeout=30
minimum_luma_range=0
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --manager) manager="${2:?}"; shift 2 ;;
    --wrapper) wrapper="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --minimum-luma-range) minimum_luma_range="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--manager PACKAGE] [--wrapper PACKAGE] [--timeout-seconds N] [--minimum-luma-range 0..255]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
((timeout >= 10 && timeout <= 120)) \
  || archphene_die "timeout must be from 10 to 120 seconds"
((minimum_luma_range >= 0 && minimum_luma_range <= 255)) \
  || archphene_die "minimum luma range must be from 0 to 255"

archphene_test_init "$serial"
archphene_adb_run shell pm path "$manager" >/dev/null \
  || archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$wrapper" >/dev/null \
  || archphene_die "camera wrapper is not installed: $wrapper"

package_dump="$(archphene_adb_run shell dumpsys package "$wrapper")"
[[ "$package_dump" == *"android.permission.CAMERA"* ]] \
  || archphene_die "camera wrapper does not declare Android CAMERA"
archphene_regex_contains "$package_dump" \
  'android\.permission\.CAMERA: granted=true' \
  || archphene_die "camera permission is not granted; launch the wrapper and approve it first"

component="$(archphene_launcher "$wrapper")"
temporary="$(archphene_mktemp_dir current-camera)"
environment_file=
linux_pid=

cleanup() {
  if [[ "$temporary" == "$ARCHPHENE_ROOT/tooling/build/current-camera."* ]]; then
    rm -rf -- "$temporary"
  fi
}
trap cleanup EXIT

find_camera_environment() {
  local deadline manager_pid processes pid candidate
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    manager_pid="$(archphene_android_pid "$manager" || true)"
    if [[ "$manager_pid" =~ ^[1-9][0-9]*$ ]]; then
      processes="$(archphene_adb_run shell ps -A -o PID,PPID | tr -d '\r')"
      mapfile -t descendants < <(
        awk -v root="$manager_pid" '
          NR > 1 { parent[$1] = $2 }
          END {
            descendant[root] = 1
            for (round = 0; round < 64; round++) {
              changed = 0
              for (pid in parent) {
                if (!descendant[pid] && descendant[parent[pid]]) {
                  descendant[pid] = 1
                  changed = 1
                }
              }
              if (!changed) break
            }
            for (pid in descendant) {
              if (pid != root && descendant[pid]) print pid
            }
          }
        ' <<<"$processes"
      )
      for pid in "${descendants[@]}"; do
        candidate="$temporary/environment-$pid"
        if ! archphene_adb_run exec-out run-as "$manager" \
            cat "/proc/$pid/environ" >"$candidate" 2>/dev/null; then
          continue
        fi
        if tr '\0' '\n' <"$candidate" |
            grep -Eq '^ARCHPHENE_RUNTIME_PROGRAM_PATH=.*/usr/bin/snapshot$' &&
            tr '\0' '\n' <"$candidate" |
              grep -Fxq 'GSK_RENDERER=cairo' &&
            tr '\0' '\n' <"$candidate" |
              grep -Eq '^DBUS_SESSION_BUS_ADDRESS=unix:path=/data/'; then
          environment_file="$candidate"
          linux_pid="$pid"
          return 0
        fi
      done
    fi
    sleep 0.3
  done
  archphene_die "could not locate Snapshot with the GTK camera environment"
}

archphene_adb_run shell am force-stop "$wrapper"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$component" >/dev/null
find_camera_environment

logs="$(
  archphene_wait_log \
    'Private PipeWire camera ready' "$timeout" \
    'ArchpheneCameraRuntime:I ArchpheneLauncherSession:I ArchpheneLauncher:I *:S'
)"
archphene_regex_contains "$logs" 'Starting Linux camera stream 640x480' \
  || logs="$(
    archphene_wait_log \
      'Starting Linux camera stream 640x480' "$timeout" \
      'ArchpheneCameraRuntime:I ArchpheneLauncherSession:I ArchpheneLauncher:I *:S'
  )"
archphene_wait_log \
  'Presented Linux frame session=[1-9][0-9]*' "$timeout" \
  'ArchpheneLauncherSession:I *:S' >/dev/null

sleep 2
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/current-camera"
artifact_name="${serial//[^A-Za-z0-9_.-]/_}"
mkdir -p "$artifact_dir"
archphene_adb_run exec-out screencap >"$artifact_dir/$artifact_name.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/$artifact_name.png"
frame_result="$(
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/camera-frame-check.py" \
    "$artifact_dir/$artifact_name.raw" \
    --minimum-luma-range "$minimum_luma_range"
)"

logs="$(archphene_adb_run logcat -d -v threadtime)"
[[ "$logs" != *"FATAL EXCEPTION"* &&
    "$logs" != *"Fatal signal"* &&
    "$logs" != *"ANR in $manager"* &&
    "$logs" != *"ANR in $wrapper"* ]] \
  || archphene_die "camera session emitted a fatal Android failure"

abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
archphene_note \
  "Current camera bridge passed on $serial ($abi): Snapshot pid=$linux_pid, GTK4 Cairo compatibility, private PipeWire stream, Linux frame presentation, and full-device pixels ($frame_result)."
archphene_note "Screenshot: $artifact_dir/$artifact_name.png"
