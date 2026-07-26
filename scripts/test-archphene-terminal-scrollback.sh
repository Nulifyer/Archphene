#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--skip-install]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-scrollback-test"
device_temporary="/data/local/tmp/archphene-terminal-scrollback-test-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-scrollback-test
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-scrollback"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f "$installed_fixture" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_temporary" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_require_file "$fixture"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "terminal scrollback regression requires installed bash"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/tput ||
  archphene_die "terminal scrollback regression requires installed tput"

archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "terminal-scrollback-section-$serial"
archphene_wait_ui 'text="Start shell"' "terminal-scrollback-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui 'archphene:~\$' "terminal-scrollback-prompt-$serial" 20

archphene_wait_ui 'text="Linux command, for example btop"' \
  "terminal-scrollback-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Linux command, for example btop"' 'Linux shell input'
archphene_adb_run shell input text \
  'bash%s/usr/bin/archphene-terminal-scrollback-test' >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Send"' "terminal-scrollback-send-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Send"' 'send shell input'

archphene_wait_ui 'SCROLL-LIVE-READY' "terminal-scrollback-live-$serial" 40
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-live.png"

read -r terminal_x terminal_top terminal_bottom <<<"$(
  python3 -c '
import re, sys
match = re.search(
    r"content-desc=\"Linux terminal, [0-9]+ columns by [0-9]+ rows\""
    r"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",
    sys.stdin.read(),
)
if match is None:
    raise SystemExit("Could not find terminal surface bounds")
x1, y1, x2, y2 = map(int, match.groups())
print((x1 + x2) // 2, y1, y2)
' <<<"$ARCHPHENE_UI"
)"
swipe_start=$((terminal_bottom - 180))
swipe_end=$((terminal_top + 180))
terminal_middle=$(((terminal_top + terminal_bottom) / 2))
for _ in 1 2 3; do
  archphene_adb_run shell input touchscreen swipe \
    "$terminal_x" "$swipe_start" "$terminal_x" "$swipe_end" 350 >/dev/null
done
archphene_wait_ui 'SCROLL-0[0-8][0-9]' "terminal-scrollback-touch-$serial" 15
archphene_regex_contains "$ARCHPHENE_UI" 'SCROLL-LIVE-READY' &&
  archphene_die "touch scroll did not move the viewport away from live output"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-touch.png"

archphene_adb_run shell input tap "$terminal_x" "$terminal_middle" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
for _ in 1 2 3 4; do
  archphene_adb_run shell input keyboard keycombination -t 120 \
    KEYCODE_SHIFT_LEFT KEYCODE_PAGE_DOWN >/dev/null
done
archphene_wait_ui 'SCROLL-LIVE-READY' "terminal-scrollback-return-$serial" 15

archphene_adb_run shell input mouse scroll "$terminal_x" "$terminal_middle" \
  --axis VSCROLL,3 >/dev/null
archphene_wait_ui 'SCROLL-1(0[0-9]|1[0-9])' "terminal-scrollback-wheel-$serial" 15

archphene_adb_run shell input keyboard keycombination -t 120 \
  KEYCODE_SHIFT_LEFT KEYCODE_PAGE_UP >/dev/null
archphene_wait_ui 'SCROLL-0[0-9][0-9]' "terminal-scrollback-page-up-$serial" 15
for _ in 1 2 3 4; do
  archphene_adb_run shell input keyboard keycombination -t 120 \
    KEYCODE_SHIFT_LEFT KEYCODE_PAGE_DOWN >/dev/null
done
archphene_wait_ui 'SCROLL-LIVE-READY' "terminal-scrollback-page-down-$serial" 15

archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell input text x >/dev/null
archphene_wait_ui 'archphene:~\$' "terminal-scrollback-exit-$serial" 15

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal scrollback regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal scrollback regression passed on $serial"
archphene_note "  Touch, mouse-wheel, Shift+PageUp/PageDown, and accessibility passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{live,touch}.png"
