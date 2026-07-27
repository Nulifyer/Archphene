#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
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
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"
archphene_test_init "$serial"

activity="$(archphene_launcher "$package")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/launcher-portal-settings/$serial_slug}"
mkdir -p "$artifact_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell pm path "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private desktop portal ready session=' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null

bus="$(
  archphene_adb_run shell run-as "$manager" find cache -name bus -print |
    tr -d '\r'
)"
[[ "$bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] ||
  archphene_die "expected exactly one private launcher bus, received: $bus"

package_dump="$(archphene_adb_run shell dumpsys package "$manager")"
native_dir="$(
  sed -n 's/.*legacyNativeLibraryDir=//p' <<<"$package_dump" |
    head -n 1 |
    tr -d '\r'
)"
[[ "$native_dir" =~ ^/data/app/[A-Za-z0-9_~+./=-]+/lib$ ]] ||
  archphene_die "manager native library directory is invalid"
abi="$(
  sed -n 's/.*primaryCpuAbi=//p' <<<"$package_dump" |
    head -n 1 |
    tr -d '\r'
)"
case "$abi" in
  arm64-v8a) abi_directory=arm64 ;;
  x86_64) abi_directory=x86_64 ;;
  *) archphene_die "unsupported manager ABI: $abi" ;;
esac

address="unix:path=/data/user/0/$manager/$bus"
probe="$native_dir/$abi_directory/libarchphene_portal_probe.so"
command="run-as $manager sh -c 'DBUS_SESSION_BUS_ADDRESS=$address \"$probe\" settings'"
probe_output="$(archphene_adb_run shell "$command" | tr -d '\r')"
[[ "$probe_output" == PASS\ portal\ Settings* ]] ||
  archphene_die "portal Settings contract failed: $probe_output"

sleep 1
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'requested_reply=0'* && "$logs" != *'UnknownMethod'* ]] ||
  archphene_die "portal emitted a rejected no-reply error: $logs"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "portal Settings emitted a fatal event: $logs"
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"

trap - EXIT
cleanup
archphene_note "Launcher Settings portal passed on $serial"
archphene_note "  $probe_output"
archphene_note "  Full-device screenshot: $artifact_dir/complete.png"
