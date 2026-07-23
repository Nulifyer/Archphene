#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=
activity=
startup_timeout=45
recovery_timeout=60
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --activity) activity="${2:?}"; shift 2 ;;
    --startup-timeout-seconds) startup_timeout="${2:?}"; shift 2 ;;
    --recovery-timeout-seconds) recovery_timeout="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --package NAME [--serial SERIAL] [--activity ACTIVITY] [--startup-timeout-seconds N] [--recovery-timeout-seconds N]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$package" ]] || archphene_die '--package is required'
for value in "$startup_timeout" "$recovery_timeout"; do
  if [[ ! "$value" =~ ^[0-9]+$ ]] || ((value < 10 || value > 300)); then
    archphene_die 'timeouts must be integers from 10 to 300 seconds'
  fi
done

archphene_test_init "$serial"
activity="${activity:-$(archphene_launcher "$package")}"
run_as="$(archphene_adb_run shell run-as "$package" id)"
[[ "$run_as" =~ uid=([0-9]+) ]] \
  || archphene_die 'target must be a debuggable wrapper'
uid="${BASH_REMATCH[1]}"
was_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  was_running=true
fi
tmp="$(archphene_mktemp_dir gpu-helper-recovery)"
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$was_running" == true ]]; then
    archphene_adb_run shell am start -n "$activity" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

owned_helper_pid() {
  archphene_adb_run shell ps -A -o PID,UID,NAME \
    | awk -v uid="$uid" '
      $2 == uid && $3 ~ /libarchphene_virgl_server\.so/ {
        print $1
        exit
      }
    '
}

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
deadline=$((SECONDS + startup_timeout))
helper_pid=
while ((SECONDS < deadline)); do
  sleep .5
  helper_pid="$(owned_helper_pid)"
  log="$(archphene_adb_run logcat -d -v brief \
    -s ArchpheneLinuxApp:V AndroidRuntime:E '*:S')"
  ! archphene_regex_contains "$log" 'FATAL EXCEPTION' \
    || archphene_die 'GPU wrapper crashed during startup'
  [[ -z "$helper_pid" ]] || break
done
[[ -n "$helper_pid" ]] \
  || archphene_die 'timed out waiting for the target UID virgl helper'
deadline=$((SECONDS + 30))
android_pid=
linux_pid=
while ((SECONDS < deadline)); do
  android_pid="$(archphene_android_pid "$package" || true)"
  if [[ -n "$android_pid" ]]; then
    linux_pid="$(archphene_linux_loader_pid "$android_pid" || true)"
  fi
  [[ -n "$android_pid" && -n "$linux_pid" ]] && break
  sleep .5
done
[[ -n "$android_pid" && -n "$linux_pid" ]] \
  || archphene_die 'accelerated process tree is incomplete'
status="$(archphene_adb_run shell run-as "$package" \
  cat "/proc/$helper_pid/status")"
[[ "$status" =~ $'Uid:\t'([0-9]+) && "${BASH_REMATCH[1]}" == "$uid" ]] \
  || archphene_die 'refusing to kill a helper not owned by the target UID'

archphene_adb_run logcat -c
archphene_adb_run shell run-as "$package" kill -9 "$helper_pid"
deadline=$((SECONDS + recovery_timeout))
log=
while ((SECONDS < deadline)); do
  sleep .5
  log="$(archphene_adb_run logcat -d -v brief \
    -s ArchpheneLinuxApp:V ArchpheneRuntime:V AndroidRuntime:E '*:S')"
  if archphene_regex_contains "$log" 'GPU helper (?:exited unexpectedly|failed)' \
      && [[ "$log" == *'restarting runtime once with replacement virpipe helper'* \
        && "$log" == *'Graphics renderer=virpipe helper-restart recovery'* \
        && "$log" == *'Linux Wayland client connected to shared native compositor'* ]]; then
    break
  fi
done
archphene_regex_contains "$log" 'GPU helper (?:exited unexpectedly|failed)' \
  || archphene_die 'missing recovery log: GPU helper failure'
for expected in \
    'restarting runtime once with replacement virpipe helper' \
    'Graphics renderer=virpipe helper-restart recovery' \
    'Linux Wayland client connected to shared native compositor'; do
  [[ "$log" == *"$expected"* ]] \
    || archphene_die "missing recovery log: $expected"
done
[[ "$(grep -o 'restarting runtime once with replacement virpipe helper' \
    <<<"$log" | wc -l)" == 1 ]] \
  || archphene_die 'GPU helper replacement did not occur exactly once'
[[ "$log" != *'Graphics renderer=llvmpipe helper-loss fallback'* ]] \
  || archphene_die 'healthy replacement helper unexpectedly fell back to llvmpipe'
! archphene_regex_contains "$log" \
  'FATAL EXCEPTION|protocol error|native dispatch failed' \
  || archphene_die 'helper-loss recovery produced a runtime failure'

recovered_android="$(archphene_android_pid "$package")"
[[ "$recovered_android" == "$android_pid" ]] \
  || archphene_die "Android host changed during recovery: $android_pid -> $recovered_android"
deadline=$((SECONDS + 20))
recovered_linux=
while ((SECONDS < deadline)); do
  recovered_linux="$(archphene_linux_loader_pid "$recovered_android" || true)"
  [[ -n "$recovered_linux" && "$recovered_linux" != "$linux_pid" ]] && break
  sleep .5
done
[[ -n "$recovered_linux" && "$recovered_linux" != "$linux_pid" ]] \
  || archphene_die "Linux runtime was not replaced during recovery: $linux_pid -> $recovered_linux"
replacement_helper="$(owned_helper_pid)"
[[ -n "$replacement_helper" && "$replacement_helper" != "$helper_pid" ]] \
  || archphene_die "replacement virgl helper is missing or unchanged: $helper_pid -> $replacement_helper"

archphene_adb_run exec-out screencap >"$tmp/recovered.raw"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" inspect \
  "$tmp/recovered.raw" >/dev/null
ui="$(archphene_capture_ui gpu-helper-recovered)"
[[ "$ui" == *'class="android.widget.ImageView"'* ]] \
  || archphene_die 'recovered GPU app has no rendered viewport'

cleanup
trap - EXIT
archphene_note "GPU helper-loss recovery passed on $serial: same-UID helper $helper_pid->$replacement_helper and Linux PID $linux_pid->$recovered_linux restarted once, Android PID $android_pid survived with rendered virpipe recovery, and prior running state was restored."
