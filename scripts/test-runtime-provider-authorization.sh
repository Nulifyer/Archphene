#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
wrapper=
timeout=30
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --wrapper) wrapper="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$wrapper" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] \
  || archphene_die '--wrapper must be a generated Linux wrapper ID'
archphene_test_init "$serial"

manager=org.archpheneos.manager
authority=$manager.runtime
method=org.archphene.runtime.APPEARANCE_V1
archphene_adb_run shell pm path "$manager" >/dev/null
archphene_adb_run shell pm path "$wrapper" >/dev/null

# A generated wrapper signed by this manager must retain the positive path.
activity="$(archphene_launcher "$wrapper")"
archphene_adb_run shell am force-stop "$wrapper"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
appearance_log="$(archphene_wait_log 'Appearance theme=' "$timeout" \
  'ArchpheneLinuxApp:I AndroidRuntime:E *:S')"
[[ "$appearance_log" != *'FATAL EXCEPTION'* ]] \
  || archphene_die 'signed wrapper crashed while reading manager appearance policy'

# Android's shell UID has no signature relationship with the manager. The
# exported provider must reject it before returning even low-sensitivity policy.
set +e
denial="$(archphene_adb_run shell content call \
  --uri "content://$authority/v1" --method "$method" 2>&1)"
status=$?
set -e
if [[ $status -eq 0 && "$denial" == *'Result: Bundle'* ]]; then
  archphene_die "shell UID read manager appearance policy: $denial"
fi
archphene_regex_contains "$denial" \
  'SecurityException|Permission Denial|not signed|no active Archphene runtime pack' \
  || archphene_die "appearance-provider denial was not explicit: $denial"

archphene_note "Runtime provider authorization passed on $serial: signed wrapper allowed, shell UID denied."
