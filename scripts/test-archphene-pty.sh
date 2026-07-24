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
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/pty-regression"
mkdir -p "$output_dir"

original_accelerometer_rotation=
original_user_rotation=
rotation_changed=false
restore_rotation() {
  [[ "$rotation_changed" == true ]] || return 0
  if [[ "$original_accelerometer_rotation" == null ]]; then
    archphene_adb_run shell settings delete system accelerometer_rotation >/dev/null
  else
    archphene_adb_run shell settings put system accelerometer_rotation \
      "$original_accelerometer_rotation" >/dev/null
  fi
  if [[ "$original_user_rotation" == null ]]; then
    archphene_adb_run shell settings delete system user_rotation >/dev/null
  else
    archphene_adb_run shell settings put system user_rotation \
      "$original_user_rotation" >/dev/null
  fi
}
cleanup() {
  restore_rotation || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "shared-shell regression requires installed bash"

enter_shell_line() {
  local line="$1" ui_name="$2"
  archphene_wait_ui 'text="Linux command, for example btop --version"' "$ui_name-field" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Linux command, for example btop --version"' 'Linux shell input'
  archphene_adb_run shell input text "${line// /%s}" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_wait_ui 'text="SEND"' "$ui_name-send" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="SEND"' 'send shell input'
}

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_log 'Activity created generation=1' 15 'ArchpheneActivity:V *:S' >/dev/null
archphene_wait_ui 'text="START SHELL"' "archphene-shell-action-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="START SHELL"' 'start shell'
archphene_wait_ui 'text="Shared shell ready' "archphene-shell-ready-$serial" 20
archphene_wait_ui 'archphene:~\$' "archphene-shell-startup-$serial" 15
archphene_wait_log 'Shared Bash session started' 15 >/dev/null

enter_shell_line "pwd" "archphene-shell-pwd-$serial"
archphene_wait_ui '/home/archphene' "archphene-shell-pwd-output-$serial" 15
enter_shell_line "declare -p HOME" "archphene-shell-home-$serial"
archphene_wait_ui 'HOME=.*/home/archphene' "archphene-shell-home-output-$serial" 15
enter_shell_line "declare -p PATH" "archphene-shell-path-$serial"
archphene_wait_ui '/usr/local/sbin:/usr/local/bin:/usr/bin' \
  "archphene-shell-path-output-$serial" 15
enter_shell_line "locale charmap" "archphene-shell-locale-$serial"
archphene_wait_ui 'UTF-8' "archphene-shell-locale-output-$serial" 15
enter_shell_line "echo archphene-session-one" "archphene-shell-one-$serial"
archphene_wait_ui 'archphene-session-one' "archphene-shell-one-output-$serial" 15

original_accelerometer_rotation="$(
  archphene_adb_run shell settings get system accelerometer_rotation | tr -d '\r'
)"
original_user_rotation="$(
  archphene_adb_run shell settings get system user_rotation | tr -d '\r'
)"
rotation_changed=true
target_rotation=1
[[ "$original_user_rotation" == 1 ]] && target_rotation=0
archphene_adb_run shell settings put system accelerometer_rotation 0 >/dev/null
archphene_adb_run shell settings put system user_rotation "$target_rotation" >/dev/null
archphene_wait_log 'Activity created generation=2' 20 'ArchpheneActivity:V *:S' >/dev/null
archphene_wait_ui 'text="STOP SHELL"' "archphene-shell-rotated-$serial" 20
archphene_wait_ui 'archphene-session-one' "archphene-shell-retained-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-running.png"
restore_rotation
rotation_changed=false
archphene_wait_ui 'text="STOP SHELL"' "archphene-shell-restored-$serial" 20

enter_shell_line "echo archphene-session-two" "archphene-shell-two-$serial"
archphene_wait_ui 'archphene-session-two' "archphene-shell-two-output-$serial" 15
enter_shell_line "exit 7" "archphene-shell-exit-$serial"
archphene_wait_ui 'Shared shell exited 7' "archphene-shell-exited-$serial" 20
archphene_wait_log 'Shared Bash session finished with status 7' 15 >/dev/null
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-exited.png"

archphene_wait_ui 'text="START SHELL"' "archphene-shell-restart-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="START SHELL"' 'restart shell'
archphene_wait_ui 'text="STOP SHELL"' "archphene-shell-stop-action-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="STOP SHELL"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "archphene-shell-stopped-$serial" 20
archphene_wait_log 'Shared Bash session finished with status stopped' 15 >/dev/null

android_pid="$(archphene_android_pid "$package")"
[[ -n "$android_pid" ]] || archphene_die "Archphene process stopped unexpectedly"
if linux_pid="$(archphene_linux_loader_pid "$android_pid")" && [[ -n "$linux_pid" ]]; then
  archphene_die "shared shell child survived stop: $linux_pid"
fi

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "shared-shell regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene shared-shell lifecycle regression passed on $serial"
archphene_note "  Startup, C.UTF-8, paths, lifecycle, exit status, and stop/reap passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-running.png"
archphene_note "                           $output_dir/$serial-exited.png"
