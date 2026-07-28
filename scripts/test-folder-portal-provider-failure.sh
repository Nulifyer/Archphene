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
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/folder-portal-provider-failure/$serial_slug}"
mkdir -p "$artifact_dir"

folder=ProviderFailureFixture
source_path="files/arch-root/home/archphene/$folder"
target_path="files/arch-root/home/archphene/Projects/$folder"
failure_file=cache/portal-folder-provider-failure
probe_output=cache/portal-folder-provider-failure-probe.out

cleanup() {
  archphene_adb_run shell run-as "$manager" rm -f "$failure_file" "$probe_output" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -rf "$source_path" "$target_path" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell run-as "$manager" test ! -e "$source_path" ||
  archphene_die "refusing to overwrite provider failure source: $source_path"
archphene_adb_run shell run-as "$manager" test ! -e "$target_path" ||
  archphene_die "refusing to overwrite provider failure target: $target_path"
archphene_adb_run shell run-as "$manager" mkdir -p "$source_path"
printf 'provider-failure-fixture\n' |
  archphene_adb_run shell run-as "$manager" sh -c \
    "'tee \"$source_path/payload.txt\" >/dev/null'"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private desktop portal ready session=' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_prepare_portal_probe "$manager"

choose_fixture() {
  local capture="$1"
  archphene_wait_ui \
    'package="com\.(google\.)?android\.documentsui"' \
    "folder-provider-failure-picker-$serial_slug" 20
  archphene_open_documents_archphene_home_root \
    "$ARCHPHENE_UI" "folder-provider-failure-root-$serial_slug"
  deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)); do
    ARCHPHENE_UI="$(
      archphene_capture_ui "folder-provider-failure-source-$serial_slug" \
        2>/dev/null || true
    )"
    [[ "$ARCHPHENE_UI" == *"text=\"$folder\""* ]] && break
    archphene_adb_run shell input swipe 540 1700 540 600 400
    sleep 0.4
  done
  [[ "$ARCHPHENE_UI" == *"text=\"$folder\""* ]] ||
    archphene_die "timed out waiting for Android folder: $folder"
  archphene_tap_text "$ARCHPHENE_UI" "$folder"
  archphene_wait_ui \
    'text="USE THIS FOLDER"[^>]*enabled="true"' \
    "folder-provider-failure-use-$serial_slug" 20
  archphene_adb_run exec-out screencap -p >"$artifact_dir/$capture"
  archphene_tap_text "$ARCHPHENE_UI" "USE THIS FOLDER"
  archphene_wait_ui_exact_text \
    "ALLOW" "folder-provider-failure-allow-$serial_slug" 20
  archphene_tap_text "$ARCHPHENE_UI" "ALLOW"
}

wait_probe_result() {
  local expected="$1"
  deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)); do
    probe_result="$(
      archphene_adb_run shell run-as "$manager" cat "$probe_output" \
        2>/dev/null | tr -d '\r' || true
    )"
    [[ "$probe_result" == "$expected" ]] && return 0
    sleep 0.3
  done
  archphene_die "portal probe result was not '$expected': $probe_result"
}

run_failure() {
  local operation="$1"
  printf '%s\n' "$operation" |
    archphene_adb_run shell run-as "$manager" sh -c \
      "'tee \"$failure_file\" >/dev/null'"
  archphene_adb_run logcat -c
  archphene_run_portal_directory_probe "$manager" "$probe_output"
  choose_fixture "$operation-picker-ready.png"
  archphene_wait_log \
    "Portal folder failure requested operation=$operation caller=$package" \
    20 'ArchpheneDocuments:I AndroidRuntime:E *:S' >/dev/null
  archphene_wait_log \
    'Reported Android directory stream failure' \
    20 'ArchpheneLauncher:I AndroidRuntime:E *:S' >/dev/null
  archphene_adb_run exec-out screencap -p \
    >"$artifact_dir/$operation-feedback.png"
  archphene_wait_log \
    'Portal folder import failed:' \
    20 'ArchpheneRuntime:E AndroidRuntime:E *:S' >/dev/null
  wait_probe_result 'FAIL portal folder: response=2'
  archphene_adb_run shell pidof "$package" >/dev/null ||
    archphene_die "provider $operation failure killed the launcher"
  archphene_adb_run shell pidof "$manager" >/dev/null ||
    archphene_die "provider $operation failure killed the manager"
  archphene_adb_run shell run-as "$manager" test ! -e "$target_path" ||
    archphene_die "provider $operation failure published a partial project"
  archphene_wait_ui \
    "package=\"$package\"" "folder-provider-failure-$operation-complete-$serial_slug" 20
  archphene_adb_run exec-out screencap -p >"$artifact_dir/$operation-complete.png"
}

run_failure query
run_failure open

archphene_adb_run shell run-as "$manager" rm -f "$failure_file"
archphene_adb_run logcat -c
archphene_run_portal_directory_probe "$manager" "$probe_output"
choose_fixture normal-picker-ready.png
archphene_wait_log \
  'Portal folder imported name=ProviderFailureFixture entries=1 bytes=25' \
  30 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
wait_probe_result 'PASS portal folder selected'
expected="$(
  printf 'provider-failure-fixture\n' | sha256sum | awk '{print $1}'
)"
actual="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$target_path/payload.txt" |
    awk '{print $1}' | tr -d '\r'
)"
[[ "$actual" == "$expected" ]] ||
  archphene_die "normal retry changed the provider fixture"
archphene_wait_ui \
  "package=\"$package\"" "folder-provider-failure-normal-complete-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/normal-complete.png"

logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E ArchpheneRuntime:E \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "provider failure recovery emitted a fatal event: $logs"

trap - EXIT
cleanup
archphene_note "Folder provider failures passed on $serial"
archphene_note "  Query and open failures preserved both processes and published no partial project"
archphene_note "  The generated launcher showed a user-facing failure message"
archphene_note "  A normal retry imported the exact file"
archphene_note "  Full-device screenshots: $artifact_dir/{query,open}-{picker-ready,feedback,complete}.png and normal-{picker-ready,complete}.png"
