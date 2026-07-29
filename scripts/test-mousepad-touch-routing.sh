#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--package PACKAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
output_dir="$ARCHPHENE_ROOT/tooling/build/mousepad-touch-routing/$safe_serial"
mkdir -p "$output_dir"
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
startup="$(
  archphene_wait_log 'mapped=true.*title=.*Mousepad' 30 \
    'ArchpheneInput:I AndroidRuntime:E *:S'
)"
[[ "$startup" == *"mapped=true"* ]] ||
  archphene_die "Mousepad did not publish mapped input geometry"

deadline=$((SECONDS + 20))
window_bounds=
while ((SECONDS < deadline)); do
  windows="$(archphene_adb_run shell dumpsys window windows)"
  window_bounds="$(
    python3 -c '
import re, sys

package = sys.argv[1]
blocks = re.split(r"(?=  Window #[0-9]+ Window\{)", sys.stdin.read())
for block in blocks:
    if package not in block:
        continue
    if "isOnScreen=true" not in block or "isVisible=true" not in block:
        continue
    match = re.search(
        r"Frames:.*? frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]",
        block,
        re.DOTALL,
    )
    if match is not None:
        print(*match.groups())
        break
' "$package" <<<"$windows"
  )"
  [[ -n "$window_bounds" ]] && break
  sleep 0.5
done
[[ -n "$window_bounds" ]] ||
  archphene_die "Mousepad did not expose a visible Android window"
read -r left top right bottom <<<"$window_bounds"
((right > left + 200 && bottom > top + 400)) ||
  archphene_die "Mousepad Android window bounds are not actionable"
x=$((left + (right - left) * 3 / 4))
start_y=$((top + (bottom - top) * 3 / 4))
end_y=$((top + (bottom - top) / 3))
((x >= left && x < right && end_y >= top && start_y < bottom)) ||
  archphene_die "derived touch path escaped Mousepad's visible device bounds"

archphene_adb_run exec-out screencap -p >"$output_dir/before.png"
archphene_adb_run logcat -c
archphene_adb_run shell input swipe "$x" "$start_y" "$x" "$end_y" 350
sleep 1
log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneInput:D AndroidRuntime:E libc:F '*:S'
)"
archphene_regex_contains "$log" 'touch down.*result=1' ||
  archphene_die "one-finger gesture did not enter the Wayland touch path"
archphene_regex_contains "$log" 'touch up.*result=1' ||
  archphene_die "one-finger gesture did not complete the Wayland touch sequence"
! archphene_regex_contains "$log" 'pointer button pressed=true' ||
  archphene_die "one-finger gesture incorrectly activated a pointer click"
[[ "$log" != *"FATAL EXCEPTION"* && "$log" != *"Fatal signal"* ]] ||
  archphene_die "Mousepad touch routing emitted a fatal runtime error: $log"
archphene_adb_run exec-out screencap -p >"$output_dir/after.png"

cleanup
trap - EXIT
archphene_note \
  "Mousepad touch routing passed on $serial: a visible in-bounds gesture used wl_touch without a pointer click."
archphene_note "  Full-device screenshots: $output_dir/{before,after}.png"
