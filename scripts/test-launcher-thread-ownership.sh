#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
package=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk] --package CURRENT_LAUNCHER_PACKAGE"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package must be a generated Archphene launcher"

archphene_test_init "$serial"
manager=org.archphene.app.debug
manager_activity="$manager/org.archphene.app.MainActivity"
output_dir="$ARCHPHENE_ROOT/tooling/build/launcher-thread-ownership"
mkdir -p "$output_dir"
raw_log="$output_dir/$serial.log"
screenshot="$output_dir/$serial.png"

manager_running=false
launcher_running=false
[[ -n "$(archphene_adb_run shell pidof "$manager" 2>/dev/null | tr -d '\r')" ]] &&
  manager_running=true
[[ -n "$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')" ]] &&
  launcher_running=true
[[ "$launcher_running" == false ]] ||
  archphene_die "refusing to replace an active generated-launcher session"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$manager_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "$manager is not installed; pass --install-apk with --apk"
if archphene_adb_run shell run-as "$manager" test -e \
  files/arch-root/var/lib/archphene/session-active-v1; then
  archphene_die "refusing to interrupt an active shared terminal"
fi
while IFS= read -r installed_wrapper; do
  installed_wrapper="${installed_wrapper#package:}"
  [[ -n "$installed_wrapper" ]] || continue
  if archphene_android_pid "$installed_wrapper" >/dev/null 2>&1; then
    archphene_die \
      "refusing to interrupt active generated launcher $installed_wrapper"
  fi
done < <(
  archphene_adb_run shell pm list packages org.archphene.linux.p |
    tr -d '\r'
)
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
archphene_wait_log \
  'Package runtime ready:.*Pacman v[0-9]' 30 \
  'ArchpheneRuntime:V *:S' >/dev/null

component="$(
  archphene_adb_run shell cmd package resolve-activity --brief "$package" |
    tail -n1 |
    tr -d '\r'
)"
[[ "$component" == "$package/"* ]] ||
  archphene_die "could not resolve generated launcher Activity: $component"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$component" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected session=' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  'Accepted first bounded Android clipboard.*present=true' 20 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run exec-out screencap -p >"$screenshot"
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V >/dev/null
archphene_wait_log \
  'Wrote first Android clipboard transfer.*on ArchpheneLauncherClipboard' 20 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null

archphene_adb_run logcat -d -v threadtime \
  ArchpheneLauncherSession:V AndroidRuntime:E libc:F '*:S' >"$raw_log"
fatal="$(
  rg 'FATAL EXCEPTION|Fatal signal' "$raw_log" 2>/dev/null || true
)"
[[ -z "$fatal" ]] ||
  archphene_die "launcher thread gate emitted a fatal event: $fatal"

trap - EXIT
cleanup
archphene_note "Launcher compositor/clipboard thread gate passed on $serial"
archphene_note "  Surface, input, Wayland frame, and off-thread clipboard transfer passed"
archphene_note "  Raw log: $raw_log"
archphene_note "  Full-device screenshot: $screenshot"
