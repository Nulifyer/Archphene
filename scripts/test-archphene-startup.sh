#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
reboot_target=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --allow-reboot) reboot_target=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk] [--allow-reboot]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
trust=files/arch-root/run/package-trust-v1
output="$ARCHPHENE_ROOT/tooling/build/startup-regression/$serial.png"
mkdir -p "$(dirname "$output")"

measure_ready() {
  local name="$1" deadline activity_time ready_time logs
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)); do
    logs="$(
      archphene_adb_run logcat -d -v epoch 2>/dev/null |
        grep -E 'Archphene(Runtime|Activity)' || true
    )"
    [[ "$logs" == *"Package runtime ready:"* ]] && break
    sleep 0.2
  done
  activity_time="$(
    awk '/ArchpheneActivity: Activity created/{print $1; exit}' <<<"$logs"
  )"
  ready_time="$(
    awk '/ArchpheneRuntime: Package runtime ready:/{print $1; exit}' <<<"$logs"
  )"
  [[ -n "$activity_time" && -n "$ready_time" ]] ||
    archphene_die "missing scoped startup timestamps for $name"
  python3 -c '
from decimal import Decimal
import sys
print(int((Decimal(sys.argv[2]) - Decimal(sys.argv[1])) * 1000))
' "$activity_time" "$ready_time"
}

wait_for_boot() {
  local deadline=$((SECONDS + 180))
  archphene_adb_run wait-for-device
  while ((SECONDS < deadline)); do
    if [[ "$(archphene_adb_run shell getprop sys.boot_completed 2>/dev/null |
        tr -d '\r')" == 1 ]]; then
      return 0
    fi
    sleep 1
  done
  archphene_die "device did not finish booting"
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
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

# The trust directory is derived data. Removing only its identity marker forces
# one controlled rebuild without touching packages, catalogs, or user files.
archphene_adb_run shell run-as "$package" rm -f "$trust/source-v1"
rebuild_ms="$(measure_ready rebuild)"
state="$(
  archphene_adb_run exec-out run-as "$package" cat "$trust/source-v1" |
    tr -d '\r'
)"
[[ "$state" == org.archphene.package-trust.v1$'\n'*libarchphene_pkg_* ]] ||
  archphene_die "verification-keyring source identity is missing or malformed"
keybox_before="$(
  archphene_adb_run exec-out run-as "$package" cat "$trust/pubring.kbx" |
    sha256sum | awk '{print $1}'
)"

reuse_ms="$(measure_ready reuse)"
keybox_after="$(
  archphene_adb_run exec-out run-as "$package" cat "$trust/pubring.kbx" |
    sha256sum | awk '{print $1}'
)"
[[ "$keybox_after" == "$keybox_before" ]] ||
  archphene_die "unchanged trust sources unexpectedly rebuilt a different keybox"
((reuse_ms < rebuild_ms)) ||
  archphene_die "cached startup was not faster: rebuild=${rebuild_ms}ms reuse=${reuse_ms}ms"
((reuse_ms < 1000)) ||
  archphene_die "cached package-runtime readiness exceeded 1000 ms: ${reuse_ms}ms"

boot_ms=
if [[ "$reboot_target" == true ]]; then
  archphene_note "Rebooting $serial with the validated trust cache"
  archphene_adb_run reboot
  wait_for_boot
  archphene_adb_run shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  archphene_adb_run shell wm dismiss-keyguard >/dev/null 2>&1 || true
  boot_ms="$(measure_ready post-reboot)"
  ((boot_ms < 1500)) ||
    archphene_die "post-reboot package-runtime readiness exceeded 1500 ms: ${boot_ms}ms"
fi

archphene_wait_ui 'Pacman ready' "startup-ready-$serial" 15
archphene_adb_run exec-out screencap -p >"$output"
fatal_log="$(
  archphene_adb_run logcat -d -v brief -s AndroidRuntime:E libc:F '*:S' \
    2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "startup emitted a fatal runtime error: $fatal_log"
archphene_adb_run shell am force-stop "$package" >/dev/null

cleanup
trap - EXIT
archphene_note "Cached package-runtime startup passed on $serial"
archphene_note "  Trust rebuild: ${rebuild_ms} ms; unchanged-source reuse: ${reuse_ms} ms"
if [[ -n "$boot_ms" ]]; then
  archphene_note "  Post-reboot unchanged-source reuse: ${boot_ms} ms"
fi
archphene_note "  Full-device screenshot: $output"
