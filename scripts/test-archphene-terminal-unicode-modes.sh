#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-unicode-modes-test"
device_temporary="/data/local/tmp/archphene-terminal-unicode-modes-test-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-unicode-modes-test
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-unicode-modes"
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
  archphene_die "terminal Unicode regression requires installed bash"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/tput ||
  archphene_die "terminal Unicode regression requires installed tput"

archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "terminal-unicode-section-$serial"
archphene_wait_ui 'text="Start shell"' "terminal-unicode-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui 'archphene:~\$' "terminal-unicode-prompt-$serial" 20

archphene_enter_terminal_line \
  "bash /usr/bin/archphene-terminal-unicode-modes-test" \
  "terminal-unicode-fixture-$serial"

archphene_wait_ui 'terminal-unicode-modes-ready' "terminal-unicode-ready-$serial" 20
decoded_ui="$(
  python3 -c 'import html,sys; print(html.unescape(sys.stdin.read()), end="")' \
    <<<"$ARCHPHENE_UI"
)"
for marker in 'café' '日本語' '🇺🇸' '👨‍👩‍👧‍👦' '👍🏽' \
  'OVERFLOW:' '�Z' 'AUTOWRAP-OFF:' 'ORIGIN-TOP' 'ORIGIN-BOTTOM'; do
  [[ "$decoded_ui" == *"$marker"* ]] ||
    archphene_die "terminal Unicode/mode output did not expose marker: $marker"
done
sleep 0.5
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
printf '%s' "$ARCHPHENE_UI" >"$output_dir/$serial.xml"

archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell input text x >/dev/null
archphene_wait_ui 'archphene:~\$' "terminal-unicode-exit-$serial" 15

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal Unicode regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal Unicode/mode regression passed on $serial"
archphene_note "  Combining, CJK, emoji, bounded overflow, autowrap, and origin mode passed"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
