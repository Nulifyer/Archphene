#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
allow_reboot=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --allow-reboot) allow_reboot=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --allow-reboot [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$allow_reboot" == true ]] ||
  archphene_die "--allow-reboot is required because this gate reboots the device"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

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

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
if archphene_adb_run shell run-as "$package" test -e "$marker"; then
  archphene_die "an active shared shell already exists; stop it before this gate"
fi
was_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  was_running=true
fi
package_dump="$(archphene_adb_run shell dumpsys package "$package")"
restore_notification=false
if ! archphene_regex_contains "$package_dump" \
  'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  archphene_adb_run shell pm grant "$package" \
    android.permission.POST_NOTIFICATIONS >/dev/null
  restore_notification=true
fi
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$restore_notification" == true ]]; then
    archphene_adb_run shell pm revoke "$package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
  if [[ "$was_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  fi
}
assert_restored() {
  local restored_dump
  restored_dump="$(archphene_adb_run shell dumpsys package "$package")"
  if [[ "$restore_notification" == true ]]; then
    ! archphene_regex_contains "$restored_dump" \
      'android\.permission\.POST_NOTIFICATIONS: granted=true' ||
      archphene_die "notification permission was not restored"
  else
    archphene_regex_contains "$restored_dump" \
      'android\.permission\.POST_NOTIFICATIONS: granted=true' ||
      archphene_die "pre-existing notification permission was lost"
  fi
  if [[ "$was_running" == true ]]; then
    archphene_android_pid "$package" >/dev/null ||
      archphene_die "manager running state was not restored"
  else
    ! archphene_android_pid "$package" >/dev/null 2>&1 ||
      archphene_die "manager was left running after the gate"
  fi
}
trap cleanup EXIT

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_open_manager_section Terminal "session-reboot-terminal-$serial"
archphene_wait_ui 'text="Start shell"' "session-reboot-start-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
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
archphene_open_manager_section Terminal "session-reboot-restart-terminal-$serial"
archphene_wait_ui 'The previous Linux session was interrupted' \
  "session-reboot-interrupted-$serial" 30
archphene_wait_ui 'text="Start shell"' "session-reboot-restart-$serial" 15
archphene_adb_run exec-out screencap -p >"$output"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'restart shell'
archphene_wait_ui 'text="Stop shell"' "session-reboot-stop-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "session-reboot-clean-$serial" 20
if archphene_adb_run shell run-as "$package" test -e "$marker"; then
  archphene_die "clean session stop left an active marker"
fi
archphene_adb_run shell am force-stop "$package" >/dev/null
cleanup
assert_restored
trap - EXIT
archphene_note "Real-reboot interruption and clean restart passed on $serial"
archphene_note "  Full-device screenshot: $output"
