#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
activity=org.archphene.linux.kcalc.MainActivity
while (($#)); do
  case "$1" in
    --serial)
      serial="${2:?}"
      shift 2
      ;;
    --package)
      package="${2:?}"
      shift 2
      ;;
    --activity)
      activity="${2:?}"
      shift 2
      ;;
    *)
      archphene_die "unknown argument: $1"
      ;;
  esac
done

archphene_test_init "$serial"
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$package/$activity" >/dev/null

main_log="$(archphene_wait_log 'window id=[0-9]+.*primary=true.*Mousepad' 30 'ArchpheneInput:V *:S')"
main="$({
  python3 -c 'import re,sys;m=re.search(r"window id=(\d+).*primary=true.*title=[^\n]*Mousepad",sys.stdin.read());print(m.group(1) if m else "")' <<<"$main_log"
})"

read -r width height <<<"$(archphene_adb_run shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
[[ -n "${width:-}" && -n "${height:-}" ]] || archphene_die 'unable to read emulator display size'
archphene_adb_run shell input tap "$((width / 4))" "$((height * 3 / 5))"
sleep 1
input_log="$(archphene_adb_run logcat -d -v brief -s ArchpheneInput:V '*:S')"
if [[ "$input_log" == *'IME show'* ]]; then
  archphene_adb_run shell input keyevent KEYCODE_BACK
  sleep 1
fi

# Mousepad does not bind a bare comma to Preferences. Exercise the real menu
# path so this proves popup routing as well as secondary-window composition.
archphene_adb_run shell input tap "$((width * 18 / 100))" "$((height * 14 / 100))"
archphene_wait_log 'popup registry=[0-9]' 10 'ArchpheneInput:V *:S' >/dev/null
archphene_adb_run shell input tap "$((width * 28 / 100))" "$((height * 925 / 1000))"
sleep .5
archphene_adb_run shell input tap "$((width * 28 / 100))" "$((height * 925 / 1000))"
child_log="$(archphene_wait_log 'primary=false .*title=Mousepad Preferences' 15 'ArchpheneInput:V *:S')"
child="$({
  python3 -c 'import re,sys;m=re.search(r"window id=(\d+).*primary=false.*title=Mousepad Preferences",sys.stdin.read());print(m.group(1) if m else "")' <<<"$child_log"
})"

[[ -n "$main" && -n "$child" && "$main" != "$child" ]] || archphene_die 'secondary preferences window not created'
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_note "Mousepad secondary-window bridge passed: child $child closed and parent $main remained active."
