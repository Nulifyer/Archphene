#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-reflow-test"
device_temporary="/data/local/tmp/archphene-terminal-reflow-test-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-reflow-test
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-reflow"
output="$output_dir/$serial-landscape.png"
mkdir -p "$output_dir"

initial_accelerometer="$(
  archphene_adb_run shell settings get system accelerometer_rotation | tr -d '\r'
)"
initial_rotation="$(
  archphene_adb_run shell settings get system user_rotation | tr -d '\r'
)"
cleanup() {
  archphene_adb_run shell settings put system accelerometer_rotation \
    "$initial_accelerometer" >/dev/null 2>&1 || true
  archphene_adb_run shell settings put system user_rotation \
    "$initial_rotation" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f "$installed_fixture" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_temporary" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_require_file "$fixture"
archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp \
  "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"
archphene_adb_run shell settings put system accelerometer_rotation 0 >/dev/null
archphene_adb_run shell settings put system user_rotation 0 >/dev/null

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Terminal "terminal-reflow-section-$serial"
archphene_wait_ui 'text="Start shell"' "terminal-reflow-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui '(?:archphene:~|sh-[0-9.]+)\$' \
  "terminal-reflow-prompt-$serial" 20
archphene_wait_ui 'text="Command, e.g. btop"' \
  "terminal-reflow-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Command, e.g. btop"' 'Linux shell input'
archphene_adb_run shell input text \
  'bash%s/usr/bin/archphene-terminal-reflow-test' >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Send"' "terminal-reflow-send-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Send"' 'send shell input'
archphene_wait_ui 'REFLOW-LIVE-READY' "terminal-reflow-live-$serial" 40

manager_pid="$(
  archphene_adb_run shell pidof "$package" | tr -d '\r'
)"
archphene_adb_run shell settings put system user_rotation 1 >/dev/null
deadline=$((SECONDS + 20))
landscape_ui=
while ((SECONDS < deadline)); do
  landscape_ui="$(archphene_capture_ui "terminal-reflow-landscape-$serial" \
    2>/dev/null || true)"
  bounds="$(
    python3 -c '
import re, sys
m = re.search(
    r"content-desc=\"Linux terminal, ([0-9]+) columns by ([0-9]+) rows\"",
    sys.stdin.read(),
)
print(f"{m.group(1)} {m.group(2)}" if m else "")
' <<<"$landscape_ui"
  )"
  if [[ -n "$bounds" ]]; then
    read -r columns rows <<<"$bounds"
    if ((columns > rows)); then
      break
    fi
  fi
  sleep 0.5
done
[[ -n "${columns:-}" && "$columns" -gt "$rows" ]] ||
  archphene_die "terminal did not reach a landscape grid"

read -r terminal_x terminal_y <<<"$(
  python3 -c '
import re, sys
m = re.search(
    r"content-desc=\"Linux terminal, [0-9]+ columns by [0-9]+ rows\""
    r"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",
    sys.stdin.read(),
)
if not m:
    raise SystemExit("terminal bounds missing")
x1, y1, x2, y2 = map(int, m.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
' <<<"$landscape_ui"
)"

found=false
for attempt in $(seq 1 40); do
  archphene_adb_run shell input mouse scroll "$terminal_x" "$terminal_y" \
    --axis VSCROLL,3 >/dev/null
  candidate="$(archphene_capture_ui \
    "terminal-reflow-history-$serial-$attempt" 2>/dev/null || true)"
  if archphene_regex_contains "$candidate" 'REFLOW-BEGIN-' &&
      archphene_regex_contains "$candidate" 'REFLOW-END'; then
    found=true
    break
  fi
done
[[ "$found" == true ]] ||
  archphene_die "joined reflow marker was not visible after landscape resize"

archphene_adb_run exec-out screencap -p >"$output"
after_pid="$(
  archphene_adb_run shell pidof "$package" | tr -d '\r'
)"
[[ -n "$manager_pid" && "$manager_pid" == "$after_pid" ]] ||
  archphene_die "manager process changed during terminal reflow"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal reflow emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene logical terminal reflow passed on $serial"
archphene_note "  Joined marker survived portrait history and landscape reflow"
archphene_note "  Stable manager PID: $manager_pid; landscape grid: ${columns}x${rows}"
archphene_note "  Full-device screenshot: $output"
