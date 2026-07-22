#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
while (($#)); do
  case "$1" in
    --serial)
      serial="${2:?}"
      shift 2
      ;;
    *)
      archphene_die "unknown argument: $1"
      ;;
  esac
done

archphene_test_init "$serial"
manager=org.archpheneos.manager

archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -S -W -n "$manager/.MainActivity" >/dev/null
sleep 2

ui="$(archphene_capture_ui manager-test)"
for expected in Apps KCalc extra/kcalc 26.04.3-1 glibc-x86_64; do
  [[ "$ui" == *"$expected"* ]] || archphene_die "manager catalog evidence missing: $expected"
done

archphene_tap_text "$ui" KCalc
sleep 1
detail="$(archphene_capture_ui manager-kcalc-detail)"
kcalc="$({
  python3 -c 'import re,sys;m=re.search(r"text=\"(org\.archphene\.linux\.p[0-9a-f]{32})\"",sys.stdin.read());print(m.group(1) if m else "")' <<<"$detail"
})"
[[ -n "$kcalc" ]] || archphene_die 'detail view did not expose package'

# A previous lifecycle test may have left the Android task resident after its
# Linux runtime exited. Exercise the manager's cold-launch path deterministically.
archphene_adb_run shell am force-stop "$kcalc"
archphene_tap_text "$detail" Launch

# ActivityTaskManager can publish the task before zygote has returned the app PID.
# Wait for both independently so a normal cold launch cannot become a false failure.
activity_ready=false
deadline=$((SECONDS + 30))
while ((SECONDS < deadline)); do
  activities="$(archphene_adb_run shell dumpsys activity activities)"
  if [[ "$activities" == *"$kcalc/org.archphene.linux.kcalc.MainActivity"* ]]; then
    activity_ready=true
    break
  fi
  sleep .5
done
[[ "$activity_ready" == true ]] || archphene_die 'managed KCalc activity did not start'

pid=
deadline=$((SECONDS + 30))
while ((SECONDS < deadline)); do
  pid="$(archphene_android_pid "$kcalc" || true)"
  [[ -n "$pid" ]] && break
  sleep .5
done
[[ -n "$pid" ]] || archphene_die 'managed KCalc Android process did not start'

loader=
deadline=$((SECONDS + 30))
while ((SECONDS < deadline)); do
  loader="$(archphene_linux_loader_pid "$pid" || true)"
  [[ -n "$loader" ]] && break
  sleep .5
done
[[ -n "$loader" ]] || archphene_die 'managed Linux loader missing'

archphene_note "Linux app manager launched KCalc $kcalc (Android $pid, Linux $loader)."
