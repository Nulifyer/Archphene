#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial="${1:?usage: $0 SERIAL APK}"
apk="${2:?usage: $0 SERIAL APK}"
archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
marker=files/arch-root/var/lib/archphene/session-active-v1
output="$ARCHPHENE_ROOT/tooling/build/pty-regression/$serial-reboot-interrupted.png"

wait_for_boot() {
  local deadline=$((SECONDS + 180))
  archphene_adb_run wait-for-device
  while ((SECONDS < deadline)); do
    if [[ "$(archphene_adb_run shell getprop sys.boot_completed 2>/dev/null |
        tr -d '\r')" == 1 ]]; then
      return 0
    fi
    sleep 1
  done
  archphene_die "device did not finish booting after reboot"
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell pm grant "$package" android.permission.POST_NOTIFICATIONS \
  >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui 'text="START SHELL"' "session-reboot-start-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="START SHELL"' 'start shell'
archphene_wait_ui 'archphene:~\$' "session-reboot-ready-$serial" 20
[[ "$(archphene_adb_run shell run-as "$package" cat "$marker" | tr -d '\r')" == active ]] ||
  archphene_die "active session marker was not published"

archphene_note "Rebooting $serial with an active shared shell"
archphene_adb_run reboot
wait_for_boot
archphene_adb_run shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
archphene_adb_run shell wm dismiss-keyguard >/dev/null 2>&1 || true
[[ "$(archphene_adb_run shell run-as "$package" cat "$marker" | tr -d '\r')" == active ]] ||
  archphene_die "reboot did not preserve the active session marker"

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui 'The previous Linux session was interrupted' \
  "session-reboot-interrupted-$serial" 30
archphene_wait_ui 'text="START SHELL"' "session-reboot-restart-$serial" 15
archphene_adb_run exec-out screencap -p >"$output"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="START SHELL"' 'restart shell'
archphene_wait_ui 'text="STOP SHELL"' "session-reboot-stop-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="STOP SHELL"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "session-reboot-clean-$serial" 20
if archphene_adb_run shell run-as "$package" test -e "$marker"; then
  archphene_die "clean session stop left an active marker"
fi
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_note "Real-reboot interruption and clean restart passed on $serial"
archphene_note "  Full-device screenshot: $output"
