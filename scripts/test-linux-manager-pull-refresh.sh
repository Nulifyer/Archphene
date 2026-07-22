#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554; while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--serial SERIAL]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_init_adb "$serial"; package=org.archpheneos.manager; archphene_adb_run shell am force-stop "$package"; archphene_adb_run shell am start -W -n "$package/.MainActivity" >/dev/null; sleep 2; before="$(archphene_capture_ui manager-pull-before)"
! archphene_regex_contains "$before" 'text="Check all"|text="Check package updates"' || archphene_die "persistent check-all controls remain"
bounds_of() { python3 -c 'import re,sys; m=re.search(r"text=\"KCalc\"[^>]*bounds=\"([^\"]+)\"",sys.stdin.read()); print(m.group(1) if m else "")' <<<"$1"; }
before_bounds="$(bounds_of "$before")"; [[ -n "$before_bounds" ]] || archphene_die "could not locate KCalc"
archphene_adb_run shell input swipe 540 900 540 1020 250; sleep 1; short="$(archphene_capture_ui manager-pull-short)"; [[ "$short" != *'Checking KCalc for updates'* && "$(bounds_of "$short")" == "$before_bounds" ]] || archphene_die "below-threshold pull triggered refresh"
archphene_adb_run shell input swipe 540 900 540 1900 700; deadline=$((SECONDS+20)); after=; while ((SECONDS<deadline)); do sleep 1; after="$(archphene_capture_ui manager-pull-after)"; [[ "$after" == *'KCalc 26.04.3-1 is up to date'* && "$after" == *'Mousepad 0.7.0-1 is up to date'* ]] && break; done
[[ "$after" == *'KCalc 26.04.3-1 is up to date'* && "$after" == *'Mousepad 0.7.0-1 is up to date'* ]] || archphene_die "pull-to-refresh did not check all apps"; [[ "$(bounds_of "$after")" == "$before_bounds" ]] || archphene_die "list did not settle after refresh"
archphene_note "Linux manager pull-to-refresh passed: batch update completed and list position settled."
