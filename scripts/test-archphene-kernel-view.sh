#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
probe=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --probe) probe="${2:?missing value for --probe}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --probe PATH"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ -n "$probe" ]] || archphene_die "--probe is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
archphene_require_file "$probe"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
device_stage="/data/local/tmp/archphene-kernel-view-${serial//[^a-zA-Z0-9]/-}"
root_probe=files/arch-root/tmp/archphene-kernel-view-probe
output_dir="$ARCHPHENE_ROOT/tooling/build/kernel-view"
output="$output_dir/$serial.png"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f "$root_probe" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_stage" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
if archphene_adb_run shell run-as "$package" test -e "$root_probe"; then
  archphene_die "kernel-view fixture path already exists"
fi
archphene_adb_run push "$probe" "$device_stage" >/dev/null
archphene_adb_run shell run-as "$package" cp "$device_stage" "$root_probe"
archphene_adb_run shell run-as "$package" chmod 500 "$root_probe"
archphene_adb_run shell run-as "$package" test -x \
  files/arch-root/usr/bin/bash ||
  archphene_die "kernel-view regression requires installed Bash"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Terminal "kernel-view-terminal-$serial"
archphene_wait_ui 'text="Start shell"' "kernel-view-start-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui \
  '(?:archphene:~|sh-[0-9.]+)\$' "kernel-view-ready-$serial" 20

archphene_wait_ui 'text="Command, e.g. btop"' \
  "kernel-view-command-field-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Command, e.g. btop"' 'Linux shell input'
archphene_adb_run shell input text /tmp/archphene-kernel-view-probe >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Send"' "kernel-view-send-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Send"' 'send shell input'
archphene_wait_ui 'kernel-view-ok' "kernel-view-output-$serial" 20
archphene_adb_run exec-out screencap -p >"$output"

archphene_wait_ui 'text="Stop shell"' "kernel-view-stop-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "kernel-view-stopped-$serial" 20

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "kernel-view regression emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene sandboxed kernel view passed on $serial"
archphene_note "  libc and direct getdents expose only readable process entries"
archphene_note "  Reliable self, CPU-topology, and safe-device paths remain available"
archphene_note "  Full-device screenshot: $output"
