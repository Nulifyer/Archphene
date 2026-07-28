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
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-ime-test"
device_temporary="/data/local/tmp/archphene-terminal-ime-test-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-ime-test
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-ime"
mkdir -p "$output_dir"

cleanup() {
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

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Terminal "terminal-ime-section-$serial"
archphene_wait_ui 'text="Start shell"' "terminal-ime-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui '(?:archphene:~|sh-[0-9.]+)\$' \
  "terminal-ime-prompt-$serial" 20
archphene_enter_terminal_line \
  "bash /usr/bin/archphene-terminal-ime-test" \
  "terminal-ime-fixture-$serial"
archphene_wait_ui 'IME-WAITING' "terminal-ime-waiting-$serial" 20

intermediate='かなゆ'
intermediate_base64="$(printf %s "$intermediate" | base64 -w0)"
composed='かな雪👩🏽‍💻'
composed_base64="$(printf %s "$composed" | base64 -w0)"
newline_base64="$(printf '\n' | base64 -w0)"
archphene_adb_run shell am start -W -n "$activity" \
  -a org.archphene.app.debug.action.TERMINAL_IME \
  --es composing_base64 "$intermediate_base64" >/dev/null
archphene_wait_log \
  'Debug terminal IME operation accepted=true composing=true finish=false commit=false' \
  15 'ArchpheneActivity:I *:S' >/dev/null
archphene_wait_ui 'Composing text: かなゆ' \
  "terminal-ime-intermediate-$serial" 15
archphene_regex_contains "$ARCHPHENE_UI" 'IME-RESULT:' &&
  archphene_die "intermediate composing text reached the PTY"

archphene_adb_run shell am start -W -n "$activity" \
  -a org.archphene.app.debug.action.TERMINAL_IME \
  --es composing_base64 "$composed_base64" >/dev/null
archphene_wait_log \
  'Debug terminal IME operation accepted=true composing=true finish=false commit=false' \
  15 'ArchpheneActivity:I *:S' >/dev/null
archphene_wait_ui 'Composing text:' \
  "terminal-ime-preedit-$serial" 15
[[ "$ARCHPHENE_UI" == *'かな雪'* &&
    "$ARCHPHENE_UI" != *'かなゆ'* &&
    "$ARCHPHENE_UI" == *'&#128105;'* &&
    "$ARCHPHENE_UI" == *'&#127997;'* &&
    "$ARCHPHENE_UI" == *'&#128187;'* ]] ||
  archphene_die "accessibility preedit did not replace the intermediate candidate"
archphene_regex_contains "$ARCHPHENE_UI" 'IME-RESULT:' &&
  archphene_die "composing text reached the PTY before finish"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-preedit.png"

archphene_adb_run shell am start -W -n "$activity" \
  -a org.archphene.app.debug.action.TERMINAL_IME \
  --ez finish true \
  --es commit_base64 "$newline_base64" >/dev/null
archphene_wait_log \
  'Debug terminal IME operation accepted=true composing=false finish=true commit=true' \
  15 'ArchpheneActivity:I *:S' >/dev/null
archphene_wait_ui 'IME-RESULT:かな雪' \
  "terminal-ime-committed-$serial" 20
[[ "$ARCHPHENE_UI" == *'&#128105;'* &&
    "$ARCHPHENE_UI" == *'&#127997;'* &&
    "$ARCHPHENE_UI" == *'&#128187;'* ]] ||
  archphene_die "committed terminal output omitted composed emoji content"
archphene_regex_contains "$ARCHPHENE_UI" 'Composing text:' &&
  archphene_die "finished composition remained exposed as preedit"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-committed.png"

archphene_wait_ui 'text="Stop shell"' "terminal-ime-stop-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "terminal-ime-stopped-$serial" 20

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal IME regression emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene terminal composing IME passed on $serial"
archphene_note "  Japanese candidate replacement and CJK/emoji/ZWJ preedit stayed local"
archphene_note "  Finish plus newline committed exact UTF-8 to Bash"
archphene_note "  Full-device screenshots: $output_dir/$serial-{preedit,committed}.png"
