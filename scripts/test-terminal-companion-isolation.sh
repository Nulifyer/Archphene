#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
skip_build=false
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --skip-build) skip_build=true; shift ;;
    --install-apk) skip_install=false; shift ;;
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--skip-build] [--install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

if [[ "$skip_build" == false ]]; then
  "$ARCHPHENE_SCRIPTS_DIR/build-manager-podman.sh" --skip-runtime
fi

manager_apk="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/out-linux/archphene.apk"
terminal_apk="$ARCHPHENE_ROOT/prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
archphene_require_file "$manager_apk"
archphene_require_file "$terminal_apk"

embedded_hash="$(python3 -c '
import hashlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    print(hashlib.sha256(archive.read(
        "assets/package-runtime/archphene-terminal.apk")).hexdigest())
' "$manager_apk")"
terminal_hash="$(archphene_sha256_file "$terminal_apk")"
[[ "$embedded_hash" == "$terminal_hash" ]] \
  || archphene_die "manager-embedded Terminal differs from the companion build"

archphene_test_init "$serial"
manager=org.archpheneos.manager
terminal=org.archpheneos.terminal
restore_notification=false
cleanup() {
  archphene_adb_run shell am force-stop "$terminal" >/dev/null 2>&1 || true
  if [[ "$restore_notification" == true ]]; then
    archphene_adb_run shell pm revoke "$terminal" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
if [[ "$skip_install" == false ]]; then
  archphene_adb_run install -r "$manager_apk" >/dev/null
  archphene_adb_run install -r "$terminal_apk" >/dev/null
fi

packages="$(archphene_adb_run shell cmd package list packages -U)"
package_uid() {
  sed -n "s/^package:$1 uid:\\([0-9]*\\).*/\\1/p" <<<"$packages" | head -n1
}
manager_uid="$(package_uid "$manager")"
terminal_uid="$(package_uid "$terminal")"
[[ -n "$manager_uid" && -n "$terminal_uid" && "$manager_uid" != "$terminal_uid" ]] \
  || archphene_die "manager and Terminal do not have distinct Android UIDs"

package_version() {
  archphene_adb_run shell dumpsys package "$1" \
    | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | head -n1
}
manager_version="$(package_version "$manager")"
terminal_version="$(package_version "$terminal")"
[[ -n "$manager_version" && "$manager_version" == "$terminal_version" ]] \
  || archphene_die "manager/Terminal version mismatch: $manager_version / $terminal_version"
terminal_dump="$(archphene_adb_run shell dumpsys package "$terminal")"
if ! archphene_regex_contains "$terminal_dump" \
    'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  archphene_adb_run shell pm grant "$terminal" \
    android.permission.POST_NOTIFICATIONS
  restore_notification=true
fi

archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -n "$manager/.MainActivity" >/dev/null
archphene_wait_ui 'text="Settings"' terminal-manager-home 20
archphene_tap_text "$ARCHPHENE_UI" Settings
archphene_wait_ui 'text="Background update checks"' terminal-manager-settings 20
settings="$ARCHPHENE_UI"
for attempt in 1 2 3 4; do
  [[ "$settings" == *'text="Archphene Terminal"'* ]] && break
  archphene_adb_run shell input swipe 540 1700 540 600 400
  sleep .5
  settings="$(archphene_capture_ui "terminal-manager-settings-$attempt")"
done
for evidence in \
    'text="Archphene Terminal"' \
    'text="Ready |' \
    'text="Open Terminal"'; do
  [[ "$settings" == *"$evidence"* ]] \
    || archphene_die "manager Settings lacks Terminal readiness evidence: $evidence"
done

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$terminal"
command='pacman -Ss btop'
encoded="$(printf %s "$command" | base64 -w0)"
archphene_adb_run shell am start -W -n "$terminal/.TerminalActivity" \
  --es archphene_test_terminal_command_base64 "$encoded" \
  --ei archphene_test_terminal_capture_delay_ms 30000 >/dev/null
pty_log="$(archphene_wait_log 'PTY shell pid=[0-9]+' 30 \
  'ArchpheneTerminal:I AndroidRuntime:E *:S')"
pty_pid="$(python3 -c '
import re, sys
matches = re.findall(r"PTY shell pid=(\d+)", sys.stdin.read())
print(matches[-1] if matches else "")
' <<<"$pty_log")"
[[ -n "$pty_pid" ]] || archphene_die "Terminal did not report a PTY shell PID"
status="$(archphene_adb_run shell run-as "$terminal" cat "/proc/$pty_pid/status")"
archphene_regex_contains "$status" "(?m)^Uid:\\s+$terminal_uid\\s" \
  || archphene_die "PTY shell is not owned by the isolated Terminal UID"

archphene_wait_ui 'text="btop  [^"]+"' terminal-manager-search 60
search_ui="$ARCHPHENE_UI"
archphene_regex_contains "$search_ui" 'text="btop  [^"]+"' \
  || archphene_die "signed Terminal pacman request did not reach manager search results"
terminal_result_log="$(archphene_wait_log \
  'search: Found [1-9][0-9]* compatible package result' 30 \
  'ArchpheneTerminal:I AndroidRuntime:E *:S')"
[[ "$terminal_result_log" == *'pacman -Ss btop'* ]] \
  || archphene_die "Terminal result was not correlated with the requested command"

archphene_adb_run shell am force-stop "$manager"
set +e
denied="$(archphene_adb_run shell am start \
  -n "$manager/.TerminalRequestActivity" \
  --es archphene_terminal_request_id forged-request \
  --es archphene_terminal_action search \
  --es archphene_terminal_query forged 2>&1)"
denied_status=$?
set -e
[[ $denied_status -ne 0 && "$denied" == *'Permission Denial'* ]] \
  || archphene_die "shell caller reached the signature-protected Terminal request Activity"

archphene_adb_run shell am start -W -n "$manager/.MainActivity" \
  --es archphene_terminal_action search \
  --es archphene_terminal_query forged >/dev/null
sleep 1
forged_ui="$(archphene_capture_ui terminal-forged-launcher)"
[[ "$forged_ui" != *'Review the forged'* && "$forged_ui" != *'text="forged"'* ]] \
  || archphene_die "exported manager launcher accepted forged Terminal extras"

set +e
provider_denial="$(archphene_adb_run shell content call \
  --uri content://org.archpheneos.manager.runtime \
  --method org.archphene.runtime.TERMINAL_CATALOG_V2 2>&1)"
set -e
[[ "$provider_denial" == *'SecurityException'* ]] \
  || archphene_die "untrusted caller accessed the Terminal runtime catalog"

archphene_note "Terminal companion isolation passed on $serial: embedded artifact parity, version $manager_version, UIDs $manager_uid/$terminal_uid, PTY ownership, signed search/result routing, forged-intent denial, and provider denial."
