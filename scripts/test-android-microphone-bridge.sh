#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=RFCT90AEEFA
package=org.archphene.linux.p28ae847c2c818246c42d2ba69544759e
activity=org.archphene.linux.kcalc.MainActivity
capture_seconds=5
disable_privacy=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --activity) activity="${2:?}"; shift 2 ;;
    --capture-seconds) capture_seconds="${2:?}"; shift 2 ;;
    --temporarily-disable-microphone-privacy) disable_privacy=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--package PACKAGE] [--activity CLASS] [--capture-seconds 1..30] [--temporarily-disable-microphone-privacy]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ "$package" =~ ^[A-Za-z0-9_.]+$
    && "$activity" =~ ^[A-Za-z0-9_.$]+$ ]] \
  || archphene_die "package and activity must be Android identifiers"
[[ "$capture_seconds" =~ ^[0-9]+$ ]] \
  || archphene_die "capture seconds must be an integer"
((capture_seconds >= 1 && capture_seconds <= 30)) \
  || archphene_die "capture seconds must be 1..30"

archphene_test_init "$serial"
package_dump="$(archphene_adb_run shell dumpsys package "$package")"
[[ "$package_dump" == *android.permission.RECORD_AUDIO* ]] \
  || archphene_die "$package does not declare RECORD_AUDIO"
[[ "$package_dump" == *'android.permission.RECORD_AUDIO: granted=true'* ]] \
  || archphene_die "grant microphone access to $package before this capture test"

audio_dump="$(archphene_adb_run shell dumpsys audio)"
privacy_enabled=false
archphene_regex_contains "$audio_dump" 'mic mute .*from system=true' \
  && privacy_enabled=true
if [[ "$privacy_enabled" == true && "$disable_privacy" == false ]]; then
  archphene_die \
    "device-wide microphone privacy is enabled; pass --temporarily-disable-microphone-privacy to restore it after the test"
fi

privacy_changed=false
capture=cache/archphene-microphone-test.raw
cleanup() {
  archphene_adb_run shell run-as "$package" rm -f "$capture" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$privacy_changed" == true ]]; then
    archphene_adb_run shell cmd sensor_privacy \
      enable 0 microphone >/dev/null 2>&1 || {
        archphene_note \
          "warning: could not restore the device-wide microphone privacy switch"
      }
  fi
}
trap cleanup EXIT

if [[ "$privacy_enabled" == true ]]; then
  archphene_adb_run shell cmd sensor_privacy disable 0 microphone
  privacy_changed=true
fi

archphene_adb_run shell run-as "$package" rm -f "$capture" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$package/$activity" >/dev/null
log="$(archphene_wait_log \
  'Android AAudio microphone capture started' 20 \
  'ArchpheneAudio:I ArchpheneBridge:I AndroidRuntime:E *:S')"
! archphene_regex_contains "$log" 'FATAL EXCEPTION|SIG(SEGV|ABRT)|native crash' \
  || archphene_die "audio wrapper crashed during microphone startup"
for evidence in \
  'Private PulseAudio microphone bridge ready' \
  'Linux microphone stream attached' \
  'Android AAudio microphone capture started'; do
  [[ "$log" == *"$evidence"* ]] \
    || archphene_die "missing audio evidence: $evidence"
done

private_root="/data/data/$package"
runtime_lib="$private_root/files/linux-runtime/lib"
command="LD_LIBRARY_PATH=$runtime_lib PULSE_SERVER=unix:$private_root/cache/pa/s timeout $capture_seconds $runtime_lib/libarchphene_pulse_probe.so --record --raw --device=archphene_input --rate=48000 --channels=1 > $private_root/$capture"
set +e
archphene_adb_run shell run-as "$package" sh -c "'$command'" >/dev/null 2>&1
capture_status=$?
set -e
[[ "$capture_status" == 124 ]] \
  || archphene_die "Pulse capture client exited unexpectedly: $capture_status"

values="$(archphene_adb_run shell run-as "$package" \
  od -An -tu1 -v "$capture")"
bytes="$(wc -w <<<"$values")"
nonzero="$(tr -s ' ' '\n' <<<"$values" | grep -c -v '^0$' || true)"
minimum_bytes=$((48000 * 2 * capture_seconds * 8 / 10))
((bytes >= minimum_bytes)) \
  || archphene_die \
    "capture was truncated: $bytes bytes, expected at least $minimum_bytes"
((nonzero > 0)) \
  || archphene_die \
    "capture contains only silence; verify the microphone and privacy state"

archphene_note \
  "Android microphone bridge passed on $serial: permission and Pulse/AAudio startup verified; $bytes bytes captured with $nonzero nonzero."
