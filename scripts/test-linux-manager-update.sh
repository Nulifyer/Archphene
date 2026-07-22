#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--serial SERIAL]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_init_adb "$serial"
manager=org.archpheneos.manager
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -S -W -n "$manager/.MainActivity" >/dev/null
sleep 2
ui="$(archphene_capture_ui archphene-update-before)"
pattern='content-desc="(?:Check KCalc for updates\.[^"]*|KCalc [^"]+\. Check again)"'
archphene_tap_ui_pattern "$ui" "$pattern" "KCalc update-check control"
deadline=$((SECONDS + 15))
result=
while ((SECONDS < deadline)); do
  sleep 1
  result="$(archphene_capture_ui archphene-update-after)"
  [[ "$result" == *'content-desc="KCalc 26.04.3-1 is up to date. Check again"'* ]] && break
done
[[ "$result" == *'content-desc="KCalc 26.04.3-1 is up to date. Check again"'* ]] || archphene_die "official Arch update comparison did not return the expected installed version"
archphene_note "Manager verified KCalc 26.04.3-1 is current via Arch's official package endpoint."

