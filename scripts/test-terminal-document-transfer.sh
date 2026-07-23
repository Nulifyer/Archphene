#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--serial SERIAL]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
package=org.archpheneos.terminal
token="$(date +%s%N)"
source_name="archphene-transfer-source-$token.txt"
export_name="archphene-transfer-export-$token.txt"
android_source="/sdcard/Download/$source_name"
android_export="/sdcard/Download/$export_name"
terminal_source="files/terminal/home/Downloads/$source_name"
terminal_export="files/terminal/home/Documents/$export_name"
restore_notification=false

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$android_source" "$android_export" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f \
    "$terminal_source" "$terminal_export" >/dev/null 2>&1 || true
  if [[ "$restore_notification" == true ]]; then
    archphene_adb_run shell pm revoke "$package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

archphene_adb_run shell pm path "$package" >/dev/null 2>&1 \
  || archphene_die "Archphene Terminal is not installed"
terminal_dump="$(archphene_adb_run shell dumpsys package "$package")"
if ! archphene_regex_contains "$terminal_dump" \
    'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  archphene_adb_run shell pm grant "$package" \
    android.permission.POST_NOTIFICATIONS
  restore_notification=true
fi

archphene_adb_run shell mkdir -p /sdcard/Download
archphene_adb_run shell sh -c \
  "'printf android-import-verified > $android_source'"
archphene_adb_run shell run-as "$package" sh -c \
  "'mkdir -p files/terminal/home/Documents files/terminal/home/Downloads; printf terminal-export-verified > $terminal_export'"
archphene_adb_run shell logcat -c
archphene_adb_run shell am force-stop com.google.android.documentsui
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$package/.TerminalActivity" >/dev/null
archphene_wait_ui 'package="org\.archpheneos\.terminal"' transfer-terminal 20

send_command() {
  archphene_adb_run shell input text "${1// /%s}"
  archphene_adb_run shell input keyevent KEYCODE_ENTER
}

tap_exact() {
  local value="$1" label="$2" seconds="${3:-15}"
  archphene_wait_ui "text=\"$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' \
    "$value")\"" "transfer-$label" "$seconds"
  archphene_tap_text "$ARCHPHENE_UI" "$value"
}

send_command "archphene-import Downloads"
archphene_wait_ui \
  "content-desc=\"Show roots\"|text=\"$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' \
    "$source_name")\"" transfer-picker 15
picker="$ARCHPHENE_UI"
if [[ "$picker" != *"text=\"$source_name\""* ]]; then
  archphene_tap_ui_pattern "$picker" 'content-desc="Show roots"' \
    'document roots button'
  archphene_wait_ui 'text="Downloads"[^>]*resource-id="android:id/title"' \
    transfer-downloads 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Downloads"[^>]*resource-id="android:id/title"' 'Android Downloads root'
fi
tap_exact "$source_name" source 15

deadline=$((SECONDS + 20))
imported=
while ((SECONDS < deadline)); do
  imported="$(archphene_adb_run shell run-as "$package" \
    cat "$terminal_source" 2>/dev/null | tr -d '\r' || true)"
  [[ "$imported" == android-import-verified ]] && break
  sleep .5
done
[[ "$imported" == android-import-verified ]] \
  || archphene_die "Terminal import content mismatch"

send_command "archphene-export Documents/$export_name"
tap_exact SAVE save 15
deadline=$((SECONDS + 20))
exported=
while ((SECONDS < deadline)); do
  exported="$(archphene_adb_run shell cat "$android_export" \
    2>/dev/null | tr -d '\r' || true)"
  [[ "$exported" == terminal-export-verified ]] && break
  sleep .5
done
[[ "$exported" == terminal-export-verified ]] \
  || archphene_die "Terminal export content mismatch"

archphene_adb_run shell logcat -c
send_command 'archphene-export .config/private.txt'
hidden_log="$(archphene_wait_log 'hidden Terminal paths are private' \
  20 'ArchpheneTerminal:I AndroidRuntime:E *:S')"
[[ "$hidden_log" == *'hidden Terminal paths are private'* ]] \
  || archphene_die "document bridge did not reject a hidden-home export"
archphene_adb_run shell logcat -c
send_command 'archphene-import ../escape'
traversal_log="$(archphene_wait_log 'path is outside Archphene Home' \
  20 'ArchpheneTerminal:I AndroidRuntime:E *:S')"
[[ "$traversal_log" == *'path is outside Archphene Home'* ]] \
  || archphene_die "document bridge did not reject traversal"

archphene_note "Terminal document transfer passed on $serial: exact SAF import/export plus hidden-home and traversal rejection; unique fixtures cleaned."
