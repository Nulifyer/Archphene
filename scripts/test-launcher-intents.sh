#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
provider_mode=normal
intent_action=view
artifact_dir=
timeout=60
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --provider-mode) provider_mode="${2:?missing value for --provider-mode}"; shift 2 ;;
    --intent-action) intent_action="${2:?missing value for --intent-action}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?missing value for --timeout-seconds}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package GENERATED_LAUNCHER [--manager PACKAGE] [--provider-mode normal|code-workspace] [--intent-action view|send] [--artifact-dir PATH] [--timeout-seconds SECONDS]"
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
[[ "$provider_mode" == normal || "$provider_mode" == code-workspace ]] ||
  archphene_die "--provider-mode must be normal or code-workspace"
[[ "$intent_action" == view || "$intent_action" == send ]] ||
  archphene_die "--intent-action must be view or send"
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 20 && timeout <= 180)) ||
  archphene_die "--timeout-seconds must be 20..180"

archphene_test_init "$serial"
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "debug manager is not installed"
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "generated launcher is not installed"

case "$provider_mode" in
  normal)
    mime_type=text/plain
    extension=.txt
    ;;
  code-workspace)
    mime_type=application/x-code-oss-workspace
    extension=.code-workspace
    ;;
esac

resolved="$(
  archphene_adb_run shell cmd package query-activities --brief \
    -a "android.intent.action.${intent_action^^}" \
    -d "content://$manager.import-test/$provider_mode/00000000" \
    -t "$mime_type"
)"
[[ "$resolved" == *"$package/org.archphene.launcher.LauncherActivity"* ]] ||
  archphene_die "launcher does not advertise $intent_action $mime_type"

token="$(printf '%08x' "$((RANDOM * 65536 + RANDOM))")"
name="Provider-$token$extension"
relative="files/arch-root/home/archphene/Documents/Android/$name"
receiver="$manager/org.archphene.app.DocumentsProviderTestReceiver"
activity="$manager/org.archphene.app.MainActivity"
initial_manager_running=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  initial_manager_running=true
fi

serial_slug="${serial//[^A-Za-z0-9._-]/_}"
if [[ -z "$artifact_dir" ]]; then
  artifact_dir="$ARCHPHENE_ROOT/tooling/build/launcher-intents/$serial_slug-$provider_mode-$intent_action"
fi
mkdir -p "$artifact_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -f "$relative" >/dev/null 2>&1 || true
  if [[ "$initial_manager_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_adb_run shell am broadcast \
  -n "$receiver" \
  -a "$manager.action.SEND_LAUNCHER_DOCUMENT" \
  --es token "$token" \
  --es launcher_package "$package" \
  --es provider_mode "$provider_mode" \
  --es mime_type "$mime_type" \
  --es intent_action "$intent_action" >/dev/null

archphene_wait_log \
  "Imported Android launch document session=" "$timeout" \
  'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  "Linux Wayland client connected session=" "$timeout" \
  'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  "Presented Linux frame session=" "$timeout" \
  'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
sleep 2

actual="$(archphene_adb_run shell run-as "$manager" cat "$relative" | tr -d '\r')"
if [[ "$provider_mode" == normal ]]; then
  expected="$(
    printf 'Archphene provider deadline fixture %s chunk 1\n' "$token"
    printf 'Archphene provider deadline fixture %s chunk 2\n' "$token"
    printf 'Archphene provider deadline fixture %s chunk 3' "$token"
  )"
else
  expected='{"folders":[]}'
fi
[[ "$actual" == "$expected" ]] ||
  archphene_die "imported launcher document bytes differ"

top="$(archphene_adb_run shell dumpsys activity activities)"
archphene_regex_contains "$top" \
  "topResumedActivity=.*${package//./\\.}/org\\.archphene\\.launcher\\.LauncherActivity" ||
  archphene_die "generated launcher is not the resumed full-device Activity"

archphene_adb_run exec-out screencap -p >"$artifact_dir/view.png"
archphene_adb_run logcat -d -v brief >"$artifact_dir/logcat.txt"
fatal="$(
  archphene_adb_run logcat -d -v brief \
    'AndroidRuntime:E' 'libc:F' 'ArchpheneLauncher:E' \
    'ArchpheneLauncherSession:E' '*:S'
)"
[[ "$fatal" != *"FATAL EXCEPTION"* && "$fatal" != *"Fatal signal"* ]] ||
  archphene_die "fatal launcher intent log detected: $fatal"

archphene_note \
  "Launcher $intent_action intent passed on $serial: $mime_type imported exact bytes and launched $package."
