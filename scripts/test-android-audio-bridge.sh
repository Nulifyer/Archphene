#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

serial=emulator-5554
package=org.archphene.linux.p28ae847c2c818246c42d2ba69544759e
activity=org.archphene.linux.kcalc.MainActivity
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --activity) activity="${2:?missing value for --activity}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--serial SERIAL] [--package NAME] [--activity NAME]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_init_adb "$serial"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$package/$activity" >/dev/null
deadline=$((SECONDS + 20))
log=
while ((SECONDS < deadline)); do
  sleep 0.5
  log="$(archphene_adb_run logcat -d -v brief -s ArchpheneAudio:I ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
  if [[ "$log" == *"Private AAudio PulseAudio server ready"* \
      && "$log" == *"Client authenticated anonymously"* \
      && "$log" == *'application.name = "PulseAudio Volume Control"'* ]]; then
    break
  fi
done
[[ "$log" != *"FATAL EXCEPTION"* ]] || archphene_die "audio wrapper crashed: $log"
[[ "$log" == *"Private AAudio PulseAudio server ready"* ]] || archphene_die "Android AAudio sink did not start: $log"
[[ "$log" == *"Client authenticated anonymously"* ]] || archphene_die "Linux Pulse client did not authenticate: $log"
[[ "$log" == *'application.name = "PulseAudio Volume Control"'* ]] || archphene_die "pavucontrol did not create its Pulse stream: $log"
process_id="$(archphene_android_pid "$package")"
[[ -n "$process_id" ]] || archphene_die "audio wrapper exited after connecting"
archphene_note "Android audio bridge passed on $serial (PID $process_id)."
