#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554; package=; activity=org.archphene.linux.kcalc.MainActivity; startup_timeout=45; recovery_timeout=60
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --package) package="${2:?}"; shift 2;; --activity) activity="${2:?}"; shift 2;; --startup-timeout-seconds) startup_timeout="${2:?}"; shift 2;; --recovery-timeout-seconds) recovery_timeout="${2:?}"; shift 2;; -h|--help) echo "usage: $0 --package NAME [options]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
[[ -n "$package" ]] || archphene_die "--package is required"; archphene_init_adb "$serial"; run_as="$(archphene_adb_run shell run-as "$package" id)"; [[ "$run_as" =~ uid=([0-9]+) ]] || archphene_die "target must be debuggable"; uid="${BASH_REMATCH[1]}"
archphene_adb_run logcat -c; archphene_adb_run shell am force-stop "$package"; archphene_adb_run shell am start -W -n "$package/$activity" >/dev/null; deadline=$((SECONDS + startup_timeout)); helper_pid=
while ((SECONDS < deadline)); do sleep 0.5; helper_pid="$(archphene_adb_run shell ps -A | awk '/libarchphene_virgl_server\.so/ {print $2; exit}')"; log="$(archphene_adb_run logcat -d -v brief)"; [[ "$log" != *'FATAL EXCEPTION'* ]] || archphene_die "wrapper crashed during startup"; [[ -z "$helper_pid" ]] || break; done
[[ -n "$helper_pid" ]] || archphene_die "timed out waiting for virgl helper"; status="$(archphene_adb_run shell run-as "$package" cat "/proc/$helper_pid/status")"; [[ "$status" =~ $'Uid:\t'([0-9]+) && "${BASH_REMATCH[1]}" == "$uid" ]] || archphene_die "refusing to kill helper not owned by target UID"
archphene_adb_run logcat -c; archphene_adb_run shell run-as "$package" kill -9 "$helper_pid"; deadline=$((SECONDS + recovery_timeout)); log=
while ((SECONDS < deadline)); do sleep 0.5; log="$(archphene_adb_run logcat -d -v brief)"; [[ "$log" == *'GPU helper exited unexpectedly'* && "$log" == *'restarting runtime once with llvmpipe'* && "$log" == *'Graphics renderer=llvmpipe helper-loss fallback'* && "$log" == *'Linux Wayland client connected to shared native compositor'* ]] && break; done
for expected in 'GPU helper exited unexpectedly' 'restarting runtime once with llvmpipe' 'Graphics renderer=llvmpipe helper-loss fallback'; do [[ "$log" == *"$expected"* ]] || archphene_die "missing recovery log: $expected"; done
[[ "$(grep -o 'restarting runtime once with llvmpipe' <<<"$log" | wc -l)" == 1 ]] || archphene_die "fallback attempted more than once"; app_pid="$(archphene_adb_run shell pidof "$package" | head -n1 | tr -d '\r')"; [[ -n "$app_pid" ]] || archphene_die "Activity exited during recovery"; archphene_note "GPU helper-loss recovery passed on $serial (app PID $app_pid, helper PID $helper_pid)."

