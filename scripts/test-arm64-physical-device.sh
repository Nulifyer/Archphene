#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
archphene_test_init "$serial"

package=org.archphene.bridgeprobe
activity="$package/.MainActivity"
safe="${serial//[^A-Za-z0-9._-]/_}"
output_dir="$ARCHPHENE_ROOT/tooling/build/arm64-physical-device/$safe"
xml="$output_dir/report.xml"
png="$output_dir/device.png"
mkdir -p "$output_dir"

abis="$(
  archphene_adb_run shell getprop ro.product.cpu.abilist |
    tr -d '\r'
)"
[[ ",$abis," == *,arm64-v8a,* ]] ||
  archphene_die "$serial is not ARM64: $abis"
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed on $serial"
was_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  was_running=true
fi
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$was_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui 'Native checks:' "arm64-physical-$safe" 15
ui="$ARCHPHENE_UI"
[[ "$ui" != *'package="com.android.systemui"'* ]] ||
  archphene_die "device screen is locked; unlock $serial"
for expected in \
  'ABIs: [arm64-v8a' \
  'DocumentsProvider: PASS read/write' \
  'Arch Linux ARM glibc: PASS ARCHPHENE_GLIBC_PASS' \
  'Native checks:&#10;PASS' \
  'uname.machine=aarch64' \
  'AF_UNIX socketpair=PASS' \
  'socket transfer=PASS' \
  'shared mmap=PASS' \
  'Wayland shim dlopen/exports=PASS'; do
  [[ "$ui" == *"$expected"* ]] ||
    archphene_die "missing ARM64 probe result: $expected"
done

before_probe_pid="$(
  sed -n 's/.*ARCHPHENE_GLIBC_PASS[^"]* pid=\([0-9][0-9]*\).*/\1/p' <<<"$ui"
)"
[[ "$before_probe_pid" =~ ^[0-9]+$ ]] ||
  archphene_die "initial bridge report has no probe PID"
archphene_tap_ui_pattern "$ui" 'text="RUN BRIDGE CHECKS"' bridge-check
deadline=$((SECONDS + 15))
after_probe_pid=
while ((SECONDS < deadline)); do
  ui="$(archphene_capture_ui "arm64-physical-rerun-$safe")"
  after_probe_pid="$(
    sed -n 's/.*ARCHPHENE_GLIBC_PASS[^"]* pid=\([0-9][0-9]*\).*/\1/p' <<<"$ui"
  )"
  if [[ "$after_probe_pid" =~ ^[0-9]+$ &&
        "$after_probe_pid" != "$before_probe_pid" &&
        "$ui" == *'Native checks:&#10;PASS'* ]]; then
    break
  fi
  sleep 0.25
done
[[ "$after_probe_pid" =~ ^[0-9]+$ &&
  "$after_probe_pid" != "$before_probe_pid" ]] ||
  archphene_die "RUN BRIDGE CHECKS did not publish a fresh successful probe"
printf '%s' "$ui" >"$xml"
uid_line="$(
  archphene_adb_run shell cmd package list packages -U "$package" |
    tr -d '\r' |
    grep -E "^package:$package uid:[0-9]+$" |
    head -n1
)"
[[ -n "$uid_line" ]] || archphene_die "Android did not assign an app UID"
pid="$(archphene_android_pid "$package")"
[[ -n "$pid" ]] || archphene_die "probe process is not running"
context="$(
  archphene_adb_run shell ps -AZ |
    grep -E "[[:space:]]$package$" |
    head -n1
)"
[[ "$context" == *u:r:untrusted_app* ]] ||
  archphene_die "unexpected SELinux domain: $context"

archphene_adb_run shell input keyevent KEYCODE_HOME
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui 'Native checks:&#10;PASS' "arm64-physical-resume-$safe" 15
archphene_adb_run exec-out screencap -p >"$png"
fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null ||
    true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "ARM64 bridge probe emitted a fatal runtime error: $fatal_log"

cleanup
trap - EXIT
archphene_note \
  "ARM64 physical-device bridge passed on $serial ($abis), $uid_line, pid=$pid."
archphene_note "  Full-device evidence: $xml and $png"
