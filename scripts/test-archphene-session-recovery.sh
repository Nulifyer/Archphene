#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial="${1:?usage: $0 SERIAL APK}"
apk="${2:?usage: $0 SERIAL APK}"
archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
marker=files/arch-root/var/lib/archphene/session-active-v1
output="$ARCHPHENE_ROOT/tooling/build/pty-regression/$serial-interrupted.png"

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell pm grant "$package" android.permission.POST_NOTIFICATIONS \
  >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_open_manager_section Terminal "session-recovery-terminal-$serial"
archphene_wait_ui 'text="Start shell"' "session-recovery-start-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui 'archphene:~\$' "session-recovery-ready-$serial" 20
[[ "$(archphene_adb_run shell run-as "$package" cat "$marker" | tr -d '\r')" == active ]] ||
  archphene_die "active session marker was not published"

android_pid="$(archphene_android_pid "$package")"
archphene_adb_run shell run-as "$package" kill -9 "$android_pid" >/dev/null
deadline=$((SECONDS + 15))
while archphene_android_pid "$package" >/dev/null 2>&1 && ((SECONDS < deadline)); do
  sleep 0.2
done
if archphene_android_pid "$package" >/dev/null 2>&1; then
  archphene_die "manager process survived SIGKILL"
fi
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_open_manager_section Terminal "session-recovery-restart-terminal-$serial"
archphene_wait_ui 'The previous Linux session was interrupted' \
  "session-recovery-interrupted-$serial" 20
archphene_wait_ui 'text="Start shell"' "session-recovery-restart-$serial" 15
archphene_adb_run exec-out screencap -p >"$output"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'restart shell'
archphene_wait_ui 'text="Stop shell"' "session-recovery-stop-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "session-recovery-clean-$serial" 20
if archphene_adb_run shell run-as "$package" test -e "$marker"; then
  archphene_die "clean session stop left an active marker"
fi
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_note "Interrupted-session detection and clean restart passed on $serial"
archphene_note "  Full-device screenshot: $output"
