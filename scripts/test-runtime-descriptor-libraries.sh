#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554; kcalc=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2; mousepad=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --kcalc-package) kcalc="${2:?}"; shift 2;; --mousepad-package) mousepad="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--serial SERIAL] [--kcalc-package NAME] [--mousepad-package NAME]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_init_adb "$serial"
wait_log() { local pattern="$1" label="$2" timeout="${3:-30}" deadline=$((SECONDS+${3:-30})); WAIT_LOG=; while ((SECONDS<deadline)); do WAIT_LOG="$(archphene_adb_run logcat -d -v brief -s ArchpheneRuntime:V ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"; archphene_regex_contains "$WAIT_LOG" "$pattern" && return 0; sleep 0.4; done; archphene_die "timed out waiting for $label"; }
test_wrapper() {
  local package="$1" missing_library="$2" activity dump failure cache cache_kib
  activity="$(archphene_adb_run shell cmd package resolve-activity --brief "$package" | grep -E '^[^[:space:]]+/[^[:space:]]+$' | tail -n1)"; [[ -n "$activity" ]] || archphene_die "$package has no launcher"
  dump="$(archphene_adb_run shell dumpsys package "$package")"; archphene_regex_contains "$dump" '(?m)^\s*flags=\[[^]]*DEBUGGABLE' || archphene_die "$package must be debuggable"
  archphene_adb_run logcat -c; archphene_adb_run shell am force-stop "$package"; archphene_adb_run shell am start -W -n "$activity" --ez archphene_test_descriptor_libraries_runtime true >/dev/null
  wait_log 'Runtime GUI exit=127' "$package descriptor failure"; failure="$WAIT_LOG"
  [[ "$failure" == *'Runtime module view=named-program-descriptor-libraries'* && "$failure" == *"$missing_library"* ]] || archphene_die "$package did not expose expected descriptor limitation"
  cache="$(archphene_adb_run shell run-as "$package" du -sk cache)"; cache_kib="$(awk '{print $1}' <<<"$cache")"; ((cache_kib<65536)) || archphene_die "$package retained a materialized closure"
  archphene_adb_run logcat -c; archphene_adb_run shell am force-stop "$package"; archphene_adb_run shell am start -W -n "$activity" >/dev/null; wait_log 'Linux Wayland client connected to shared native compositor' "$package normal launch" 45; archphene_adb_run shell am force-stop "$package"
  archphene_note "$package passed: descriptor-library mode failed closed on $missing_library; normal launch remained healthy."
}
test_wrapper "$kcalc" libKF6Notifications.so.6; test_wrapper "$mousepad" libmousepad.so.0
archphene_note "Qt and GTK descriptor-library compatibility gate passed on $serial; retain the bounded named runtime cache."

