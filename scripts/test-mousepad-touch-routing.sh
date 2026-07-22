#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--serial SERIAL]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_init_adb "$serial"
package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -n "$package/org.archphene.linux.kcalc.MainActivity" >/dev/null
deadline=$((SECONDS + 30))
startup=
while ((SECONDS < deadline)); do
  startup="$(archphene_adb_run logcat -d -s ArchpheneInput:I '*:S')"
  [[ "$startup" =~ mapped=true.*title=.*Mousepad ]] && break
  sleep 0.5
done
[[ "$startup" =~ mapped=true.*title=.*Mousepad ]] || archphene_die "Mousepad Wayland client did not map before the drag test"
archphene_adb_run logcat -c
archphene_adb_run shell input swipe 800 1500 800 900 350
sleep 1
log="$(archphene_adb_run logcat -d -s ArchpheneInput:D '*:S')"
[[ "$log" =~ touch\ down.*result=1 && "$log" =~ touch\ up.*result=1 ]] || archphene_die "one-finger drag did not produce a complete Wayland touch sequence"
[[ ! "$log" =~ pointer\ button\ pressed=true ]] || archphene_die "one-finger drag incorrectly activated a pointer click"
archphene_note "Mousepad touch routing passed: drag used wl_touch without a pointer click."

