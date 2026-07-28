#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
old_mode=
watch_pid=
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
  if [[ -n "$watch_pid" ]]; then
    kill "$watch_pid" >/dev/null 2>&1 || true
    wait "$watch_pid" >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ -n "$old_mode" ]]; then
    archphene_adb_run shell cmd uimode night "$old_mode" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

archphene_adb_run shell pm path "$package" >/dev/null
old_mode="$(
  archphene_adb_run shell cmd uimode night |
    sed -n 's/^Night mode: //p' |
    tr -d '\r'
)"
[[ -n "$old_mode" ]] || archphene_die "could not read the current Android night mode"
archphene_adb_run shell cmd uimode night no >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private desktop portal ready session=' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
initial_pid="$(archphene_android_pid "$package")"
initial_manager_pid="$(archphene_android_pid "$manager")"
initial_linux_pid="$(archphene_linux_loader_pid "$initial_manager_pid")"
[[ -n "$initial_pid" && -n "$initial_manager_pid" && -n "$initial_linux_pid" ]] ||
  archphene_die "launcher, manager, or Linux process is missing before Settings test"

bus=
deadline=$((SECONDS + 10))
while ((SECONDS < deadline)); do
  bus="$(
    archphene_adb_run shell run-as "$manager" find cache -name bus -print |
      tr -d '\r'
  )"
  [[ "$bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] && break
  sleep 0.3
done
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

run_watch() {
  local target_mode="$1" expected_initial="$2" expected_changed="$3" label="$4"
  local watch_output="$artifact_dir/$label.txt"
  command="run-as $manager sh -c 'DBUS_SESSION_BUS_ADDRESS=$address \"$probe\" settings-watch'"
  archphene_adb_run shell "$command" >"$watch_output" 2>&1 &
  watch_pid=$!
  local deadline=$((SECONDS + 5))
  while ((SECONDS < deadline)); do
    grep -Fq "READY SettingsChanged initial=$expected_initial" "$watch_output" &&
      break
    sleep 0.1
  done
  grep -Fq "READY SettingsChanged initial=$expected_initial" "$watch_output" ||
    archphene_die "SettingsChanged watcher was not ready: $(<"$watch_output")"
  archphene_adb_run shell cmd uimode night "$target_mode" >/dev/null
  wait "$watch_pid" ||
    archphene_die "SettingsChanged watcher failed: $(<"$watch_output")"
  watch_pid=
  grep -Fq \
    "PASS SettingsChanged initial=$expected_initial changed=$expected_changed read=$expected_changed" \
    "$watch_output" ||
    archphene_die "SettingsChanged result was invalid: $(<"$watch_output")"
}

run_watch yes 2 1 light-to-dark
sleep 1
archphene_adb_run exec-out screencap -p >"$artifact_dir/dark.png"
run_watch no 1 2 dark-to-light
sleep 1
archphene_adb_run exec-out screencap -p >"$artifact_dir/light.png"
current_pid="$(archphene_android_pid "$package")"
current_manager_pid="$(archphene_android_pid "$manager")"
current_linux_pid="$(archphene_linux_loader_pid "$current_manager_pid")"
[[ "$current_pid" == "$initial_pid" &&
  "$current_manager_pid" == "$initial_manager_pid" &&
  "$current_linux_pid" == "$initial_linux_pid" ]] ||
  archphene_die "launcher, manager, or Linux process restarted during live Settings changes"

sleep 1
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'requested_reply=0'* && "$logs" != *'UnknownMethod'* ]] ||
  archphene_die "portal emitted a rejected no-reply error: $logs"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "portal Settings emitted a fatal event: $logs"
[[ "$logs" == *'Published portal appearance session='* ]] ||
  archphene_die "manager did not publish the live portal appearance: $logs"
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"

trap - EXIT
cleanup
archphene_note "Launcher Settings portal passed on $serial"
archphene_note "  $probe_output"
archphene_note "  Live light/dark/light SettingChanged signals matched subsequent reads"
archphene_note "  Full-device screenshots: $artifact_dir/dark.png $artifact_dir/light.png"
