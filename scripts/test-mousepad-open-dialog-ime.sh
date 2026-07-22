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
package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
activity="$(archphene_launcher "$package")"

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'mapped=true.*Mousepad' 30 'ArchpheneInput:I *:S' >/dev/null

# A forced stop can make Mousepad offer to restore the interrupted session. The
# left response is "No"; on a clean start this harmlessly focuses the editor.
read -r width height <<<"$(archphene_adb_run shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
[[ -n "${width:-}" && -n "${height:-}" ]] || archphene_die 'unable to read emulator display size'
archphene_adb_run shell input tap "$((width / 4))" "$((height * 3 / 5))"
sleep 1
input_log="$(archphene_adb_run logcat -d -v brief -s ArchpheneInput:V '*:S')"
if [[ "$input_log" == *'IME show'* ]]; then
  archphene_adb_run shell input keyevent KEYCODE_BACK
  sleep 1
fi
archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_O
archphene_wait_log 'mapped=true.*title=Open File' 15 'ArchpheneInput:I *:S' >/dev/null
archphene_adb_run shell input text arch
archphene_adb_run shell input keyevent KEYCODE_BACK

archphene_note 'Mousepad open-dialog IME passed: query retained keyboard; Back dismissed it; result routing exercised.'
