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
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-production-test"
device_temporary="/data/local/tmp/archphene-terminal-production-test-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-production-test
stage=files/arch-root/tmp/archphene-terminal-production-stage
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
output_dir="$ARCHPHENE_ROOT/tooling/artifacts/terminal-production/$safe_serial"
mkdir -p "$output_dir"
notification_granted_by_test=false

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f "$installed_fixture" "$stage" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_temporary" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$notification_granted_by_test" == true ]]; then
    archphene_adb_run shell pm revoke "$package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_require_file "$fixture"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "production terminal regression requires installed bash"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/tput ||
  archphene_die "production terminal regression requires installed tput"

package_dump="$(archphene_adb_run shell dumpsys package "$package")"
if ! archphene_regex_contains "$package_dump" \
    'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  archphene_adb_run shell pm grant "$package" \
    android.permission.POST_NOTIFICATIONS
  notification_granted_by_test=true
fi

archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"

wait_stage() {
  local expected="$1" deadline=$((SECONDS + 15)) actual
  while ((SECONDS < deadline)); do
    actual="$(
      archphene_adb_run shell run-as "$package" cat "$stage" 2>/dev/null |
        tr -d '\r'
    )"
    [[ "$actual" == "$expected" ]] && return 0
    sleep 0.2
  done
  archphene_die "timed out waiting for terminal stage: $expected"
}

send_character() {
  archphene_adb_run shell input text "$1" >/dev/null
  sleep 0.2
}

terminal_center() {
  archphene_wait_ui \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "terminal-production-surface-$safe_serial" 15
  archphene_ui_node_center \
    "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface'
}

capture_device() {
  archphene_adb_run exec-out screencap -p >"$output_dir/$1.png"
}

capture_atomic_sequence() {
  local frame
  for frame in 1 2 3 4 5 6 7 8; do
    capture_device "atomic-$frame"
  done
}

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Terminal "terminal-production-section-$safe_serial"
archphene_wait_ui 'text="Start shell"' "terminal-production-start-$safe_serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows".*text="Shared shell ready"' \
  "terminal-production-prompt-$safe_serial" 20

archphene_enter_terminal_line \
  "bash /usr/bin/archphene-terminal-production-test" \
  "terminal-production-fixture-$safe_serial"
archphene_wait_ui 'VISUAL-READY' "terminal-production-visual-$safe_serial" 15
wait_stage visual
for frame in 1 2 3 4; do
  capture_device "visual-$frame"
  sleep 0.3
done

capture_atomic_sequence &
atomic_capture_pid=$!
sleep 0.2
send_character v
archphene_wait_ui 'ATOMIC-FRAME-COMPLETE' \
  "terminal-production-sync-release-$safe_serial" 10
wait "$atomic_capture_pid"
capture_device atomic-complete

send_character t
wait_stage sync-timeout
archphene_wait_ui 'SYNC-TIMEOUT' "terminal-production-sync-timeout-$safe_serial" 10
capture_device sync-timeout
send_character c

archphene_wait_ui 'TOUCH-WAIT' "terminal-production-touch-$safe_serial" 10
read -r terminal_x terminal_y <<<"$(terminal_center)"
archphene_adb_run shell input tap "$terminal_x" "$terminal_y" >/dev/null
archphene_wait_ui 'TOUCH-PASS' "terminal-production-touch-pass-$safe_serial" 10
if archphene_regex_contains "$ARCHPHENE_UI" 'TOUCH-FAIL'; then
  archphene_die "touch did not produce a valid SGR mouse report"
fi

archphene_wait_ui 'MOUSE-WAIT' "terminal-production-mouse-$safe_serial" 10
read -r terminal_x terminal_y <<<"$(terminal_center)"
archphene_adb_run shell input mouse tap "$terminal_x" "$terminal_y" >/dev/null
archphene_wait_ui 'MOUSE-PASS' "terminal-production-mouse-pass-$safe_serial" 10
if archphene_regex_contains "$ARCHPHENE_UI" 'MOUSE-FAIL'; then
  archphene_die "mouse click did not produce a valid SGR mouse report"
fi

archphene_wait_ui 'WHEEL-WAIT' "terminal-production-wheel-$safe_serial" 10
read -r terminal_x terminal_y <<<"$(terminal_center)"
archphene_adb_run shell input mouse scroll "$terminal_x" "$terminal_y" \
  --axis VSCROLL,2 >/dev/null
archphene_wait_ui 'WHEEL-PASS' "terminal-production-wheel-pass-$safe_serial" 10
if archphene_regex_contains "$ARCHPHENE_UI" 'WHEEL-FAIL'; then
  archphene_die "mouse wheel did not produce a valid SGR mouse report"
fi

archphene_wait_ui 'FOCUS-WAIT' "terminal-production-focus-$safe_serial" 10
archphene_adb_run shell input keyevent KEYCODE_HOME >/dev/null
sleep 1
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui 'FOCUS-PASS' "terminal-production-focus-pass-$safe_serial" 15
if archphene_regex_contains "$ARCHPHENE_UI" 'FOCUS-FAIL'; then
  archphene_die "Home/resume did not produce exact xterm focus reports"
fi

archphene_wait_ui 'META-WAIT' "terminal-production-meta-$safe_serial" 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell input keycombination \
  KEYCODE_ALT_LEFT KEYCODE_A >/dev/null
archphene_wait_ui 'META-PASS' "terminal-production-meta-pass-$safe_serial" 10
if archphene_regex_contains "$ARCHPHENE_UI" 'META-FAIL'; then
  archphene_die "hardware eight-bit Meta did not produce exact UTF-8"
fi

archphene_wait_ui 'OSC52-WAIT' "terminal-production-osc52-$safe_serial" 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
sleep 0.5
archphene_adb_run shell input keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V >/dev/null
archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
archphene_wait_ui 'OSC52-PASS' "terminal-production-osc52-pass-$safe_serial" 10
if archphene_regex_contains "$ARCHPHENE_UI" 'OSC52-FAIL'; then
  archphene_die "terminal OSC 52 did not reach the Android clipboard"
fi

archphene_wait_ui 'PRODUCTION-TERMINAL-PASS' \
  "terminal-production-pass-$safe_serial" 10
capture_device final
printf '%s\n' "$ARCHPHENE_UI" >"$output_dir/framework-tree.xml"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F ArchpheneRuntime:E '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "production terminal regression emitted a fatal error: $fatal_log"
printf '%s\n' "$fatal_log" >"$output_dir/fatal-log.txt"

archphene_note "Archphene production terminal regression passed on $serial"
archphene_note "  BCE, cursor/blink visuals, synchronized release/timeout, touch, mouse,"
archphene_note "  wheel, focus, eight-bit Meta, and OSC 52 clipboard passed"
archphene_note "  Full-device evidence: $output_dir"
