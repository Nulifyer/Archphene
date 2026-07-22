#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554; package=org.archpheneos.terminal; delay_ms=30000; require_device=false; expected_pattern=
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --package) package="${2:?}"; shift 2;; --capture-delay-ms) delay_ms="${2:?}"; shift 2;; --require-device) require_device=true; shift;; --expected-device-pattern) expected_pattern="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--serial SERIAL] [--package NAME] [--capture-delay-ms MS] [--require-device] [--expected-device-pattern REGEX]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_init_adb "$serial"; archphene_adb_run get-state >/dev/null; archphene_adb_run shell run-as "$package" id >/dev/null; archphene_adb_run shell am force-stop "$package"; archphene_adb_run logcat -c
archphene_adb_run shell am start -n "$package/.TerminalActivity" --es archphene_test_terminal_command 'vulkaninfo --summary' --ei archphene_test_terminal_capture_delay_ms "$delay_ms" >/dev/null
deadline=$((SECONDS + delay_ms / 1000 + 20)); log=
while ((SECONDS < deadline)); do sleep 2; log="$(archphene_adb_run logcat -d -v brief -s ArchpheneTerminal:I '*:S')"; [[ "$log" == *'Terminal command probe transcript='* ]] && break; done
[[ "$log" == *'Terminal command probe transcript='* ]] || archphene_die "timed out waiting for vulkaninfo transcript"
[[ "$log" != *'Vulkan loader is not installed, not found, or failed to load'* ]] || archphene_die "runtime-loaded Vulkan loader was omitted from the closure"
[[ "$log" == *'Vulkan Instance Version'* || "$log" == *'Found no drivers'* ]] || archphene_die "vulkaninfo did not reach Vulkan loader device discovery"
[[ "$require_device" == false || "$log" == *'GPU0:'* ]] || archphene_die "Vulkan loader exposed no device"
[[ -z "$expected_pattern" ]] || archphene_regex_contains "$log" "$expected_pattern" || archphene_die "Vulkan output did not match $expected_pattern"
archphene_note "Terminal Vulkan loader/device discovery passed on $serial."

