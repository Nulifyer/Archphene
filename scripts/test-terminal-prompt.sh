#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
apk=
skip_install=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --apk) apk="${2:?}"; shift 2 ;;
    --skip-install) skip_install=true; shift ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

apk="${apk:-$ARCHPHENE_ROOT/tooling/build/terminal-prompt/archphene-terminal.apk}"
package=org.archpheneos.terminal
archphene_test_init "$serial"
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi

restore_notification=false
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$restore_notification" == true ]]; then
    archphene_adb_run shell pm revoke "$package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
package_dump="$(archphene_adb_run shell dumpsys package "$package")"
if archphene_regex_contains "$package_dump" \
    'android\.permission\.POST_NOTIFICATIONS' \
    && ! archphene_regex_contains "$package_dump" \
      'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  archphene_adb_run shell pm grant "$package" \
    android.permission.POST_NOTIFICATIONS
  restore_notification=true
fi
archphene_adb_run shell run-as "$package" mkdir -p \
  files/terminal/home/Documents/source/ArchpheneOS

probe() {
  local command="$1" encoded deadline log
  encoded="$(printf %s "$command" | base64 -w0)"
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$package/.TerminalActivity" \
    --ei archphene_test_terminal_send_delay_ms 3000 \
    --ei archphene_test_terminal_capture_delay_ms 3000 \
    --es archphene_test_terminal_command_base64 "$encoded" >/dev/null
  deadline=$((SECONDS + 25))
  while ((SECONDS < deadline)); do
    sleep 1
    log="$(archphene_adb_run logcat -d -v brief \
      -s ArchpheneTerminal:I AndroidRuntime:E '*:S')"
    if [[ "$log" == *'Terminal command probe transcript='* ]]; then
      ! archphene_regex_contains "$log" 'FATAL EXCEPTION' \
        || archphene_die 'Terminal crashed during prompt probe'
      printf '%s' "$log"
      return
    fi
  done
  archphene_die "timed out waiting for Terminal prompt probe: $command"
}

home="$(probe 'echo HOME_PROMPT_OK')"
archphene_regex_contains "$home" '(?m): \$ echo HOME_PROMPT_OK\r?$' \
  || archphene_die 'submitted command was not retained'
archphene_regex_contains "$home" '(?m): HOME_PROMPT_OK\r?$' \
  || archphene_die 'submitted command did not execute'
archphene_regex_contains "$home" '(?m): archphene ~\r?$' \
  || archphene_die 'home prompt was not abbreviated'
[[ "$(grep -c ': archphene ' <<<"$home")" == 1 ]] \
  || archphene_die 'submitted prompt context was not collapsed'

nested="$(probe 'cd ~/Documents/source/ArchpheneOS')"
archphene_regex_contains "$nested" \
  '(?m): \$ cd ~/Documents/source/ArchpheneOS\r?$' \
  || archphene_die 'nested command was not retained'
archphene_regex_contains "$nested" \
  '(?m): archphene ~/D/s/ArchpheneOS\r?$' \
  || archphene_die 'fish-style path abbreviation was not rendered'
[[ "$(grep -c ': archphene ' <<<"$nested")" == 1 ]] \
  || archphene_die 'nested submitted prompt context was not collapsed'
[[ "$nested" != *'Shell marker='* ]] \
  || archphene_die 'diagnostic shell marker remains enabled'

cleanup
trap - EXIT
archphene_note "Terminal transient two-line prompt passed on $serial with home/nested collapse and notification-state restoration."
