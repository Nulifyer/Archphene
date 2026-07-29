#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
uri=http://127.0.0.1:54321/archphene-open-uri-test
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    --uri) uri="${2:?missing value for --uri}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package GENERATED_LAUNCHER [--manager PACKAGE] [--artifact-dir PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package must be a generated Archphene launcher"
uri_pattern='^https?://[A-Za-z0-9._:][A-Za-z0-9._~:/?#@!%+&=-]*$'
[[ "$uri" =~ $uri_pattern ]] ||
  archphene_die "--uri must be a shell-safe HTTP(S) URI"
archphene_test_init "$serial"

serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/launcher-open-uri/$serial_slug}"
mkdir -p "$artifact_dir"
activity="$(archphene_launcher "$package")"

top_package() {
  archphene_adb_run shell dumpsys activity activities |
    sed -n 's/.*topResumedActivity=.* u[0-9][0-9]* \([^/ ]*\)\/.*/\1/p' |
    head -n 1 |
    tr -d '\r'
}

run_probe() {
  local operation="$1" uri="$2"
  local command
  command="run-as $manager sh -c 'DBUS_SESSION_BUS_ADDRESS=$ARCHPHENE_PORTAL_ADDRESS \"$ARCHPHENE_PORTAL_PROBE\" \"$operation\" \"$uri\"'"
  archphene_adb_run shell "$command" | tr -d '\r'
}

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private desktop portal ready session=' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_prepare_portal_probe "$manager"

rejected="$(run_probe open-rejected file:///home/archphene/private)"
[[ "$rejected" == "PASS portal OpenURI response=2" ]] ||
  archphene_die "unsafe URI was not rejected: $rejected"
[[ "$(top_package)" == "$package" ]] ||
  archphene_die "unsafe URI changed the foreground Android app"

accepted="$(run_probe open "$uri")"
[[ "$accepted" == "PASS portal OpenURI response=0" ]] ||
  archphene_die "HTTP URI was not accepted: $accepted"

deadline=$((SECONDS + 10))
browser=
while ((SECONDS < deadline)); do
  browser="$(top_package)"
  [[ -n "$browser" && "$browser" != "$package" ]] && break
  sleep 0.25
done
[[ -n "$browser" && "$browser" != "$package" ]] ||
  archphene_die "Android browser did not become visible"
archphene_wait_log \
  'Opened Android browser for Linux URI' 10 \
  'ArchpheneLauncher:I AndroidRuntime:E *:S' >"$artifact_dir/logcat.txt"
sleep 1
archphene_adb_run exec-out screencap -p >"$artifact_dir/device.png"
archphene_capture_ui "launcher-open-uri-$serial_slug" >"$artifact_dir/device.xml"

archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_note \
  "Launcher OpenURI passed on $serial: rejected file URI, opened HTTP URI in $browser. Evidence: $artifact_dir"
