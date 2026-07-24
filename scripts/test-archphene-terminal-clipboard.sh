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
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-bracketed-mode"
device_temporary="/data/local/tmp/archphene-terminal-bracketed-mode-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-bracketed-mode
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-clipboard"
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
  archphene_die "terminal clipboard regression requires installed bash"

archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"

enter_shell_line() {
  local line="$1" ui_name="$2"
  archphene_wait_ui 'text="Linux command, for example btop --version"' \
    "$ui_name-field" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Linux command, for example btop --version"' 'Linux shell input'
  archphene_adb_run shell input text "${line// /%s}" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_wait_ui 'text="SEND"' "$ui_name-send" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="SEND"' 'send shell input'
}

paste_with_hardware() {
  local ui_name="$1"
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$ui_name-terminal" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface'
  archphene_adb_run shell input keycombination \
    KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V >/dev/null
}

paste_with_touch_menu() {
  local ui_name="$1" center x y
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$ui_name-terminal" 15
  center="$(archphene_ui_node_center "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface')"
  read -r x y <<<"$center"
  archphene_adb_run shell input motionevent DOWN "$x" "$y" >/dev/null
  sleep 0.8
  archphene_adb_run shell input motionevent UP "$x" "$y" >/dev/null
  archphene_wait_ui 'text="Paste"' "$ui_name-menu" 10
  archphene_adb_run exec-out screencap -p >"$output_dir/$serial-menu.png"
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Paste"' 'paste action'
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$ui_name-refocus" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface'
}

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui 'text="START SHELL"' "terminal-clipboard-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="START SHELL"' 'start shell'
archphene_wait_ui 'archphene:~\$' "terminal-clipboard-prompt-$serial" 20

archphene_adb_run shell am broadcast \
  -n "$package/org.archphene.app.ClipboardTestReceiver" \
  -a org.archphene.app.debug.action.SET_TEST_CLIPBOARD \
  --es text clipboard-marker >/dev/null

enter_shell_line "read -r REPLY" "terminal-clipboard-normal-read-$serial"
paste_with_touch_menu "terminal-clipboard-normal-paste-$serial"
archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
enter_shell_line "declare -p REPLY" "terminal-clipboard-normal-declare-$serial"
archphene_wait_ui 'REPLY="clipboard-marker"' \
  "terminal-clipboard-normal-result-$serial" 15

enter_shell_line \
  "bash /usr/bin/archphene-terminal-bracketed-mode on" \
  "terminal-clipboard-enable-$serial"
enter_shell_line "read -r -N 28 REPLY" "terminal-clipboard-bracketed-read-$serial"
paste_with_hardware "terminal-clipboard-bracketed-paste-$serial"
enter_shell_line "declare -p REPLY" "terminal-clipboard-bracketed-declare-$serial"
archphene_wait_ui_unwrapped 'REPLY=\$.*E\[200~clipboard-marker.*E\[201~' \
  "terminal-clipboard-bracketed-result-$serial" 15
enter_shell_line \
  "bash /usr/bin/archphene-terminal-bracketed-mode off" \
  "terminal-clipboard-disable-$serial"
enter_shell_line "echo clipboard-paste-ready" "terminal-clipboard-ready-$serial"
archphene_wait_ui 'clipboard-paste-ready' "terminal-clipboard-ready-output-$serial" 15

sleep 1
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal clipboard regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene terminal clipboard regression passed on $serial"
archphene_note "  Touch-menu normal paste and bracketed Ctrl+Shift+V PTY bytes passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-menu.png"
archphene_note "                           $output_dir/$serial.png"
