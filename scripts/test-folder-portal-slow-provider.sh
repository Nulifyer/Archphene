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
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/folder-portal-slow-provider/$serial_slug}"
mkdir -p "$artifact_dir"

folder=SlowProviderFixture
source_path="files/arch-root/home/archphene/$folder"
target_path="files/arch-root/home/archphene/Projects/$folder"
delay_file=cache/portal-folder-provider-read-delay-ms
probe_output=cache/portal-folder-slow-provider-probe.out

cleanup() {
  archphene_adb_run shell run-as "$manager" rm -f "$delay_file" "$probe_output" \
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
  archphene_die "refusing to overwrite slow-provider source: $source_path"
archphene_adb_run shell run-as "$manager" test ! -e "$target_path" ||
  archphene_die "refusing to overwrite slow-provider target: $target_path"
archphene_adb_run shell run-as "$manager" mkdir -p "$source_path"
printf '0123456789abcdef0123456789abcdef0123456789abcdef' |
  archphene_adb_run shell run-as "$manager" sh -c \
    "'tee \"$source_path/payload.bin\" >/dev/null'"
printf '20000\n' |
  archphene_adb_run shell run-as "$manager" sh -c \
    "'tee \"$delay_file\" >/dev/null'"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private desktop portal ready session=' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
archphene_prepare_portal_probe "$manager"
archphene_run_portal_directory_probe "$manager" "$probe_output"

archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "folder-slow-provider-picker-$serial_slug" 20
archphene_open_documents_archphene_home_root \
  "$ARCHPHENE_UI" "folder-slow-provider-root-$serial_slug"
deadline=$((SECONDS + 20))
while ((SECONDS < deadline)); do
  ARCHPHENE_UI="$(
    archphene_capture_ui "folder-slow-provider-source-$serial_slug" \
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
  "folder-slow-provider-use-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker-ready.png"
archphene_tap_text "$ARCHPHENE_UI" "USE THIS FOLDER"
archphene_wait_ui_exact_text \
  "ALLOW" "folder-slow-provider-allow-$serial_slug" 20
started=$SECONDS
archphene_tap_text "$ARCHPHENE_UI" "ALLOW"

archphene_wait_log \
  "Portal folder slow read requested delay=20000 caller=$package" \
  20 'ArchpheneDocuments:I AndroidRuntime:E *:S' >/dev/null
archphene_wait_ui_unwrapped \
  'text="Importing selected folder…Large folders may take a moment."' \
  "folder-slow-provider-progress-$serial_slug" 10
[[ "$ARCHPHENE_UI" == *'class="android.widget.ProgressBar"'* ]] ||
  archphene_die "folder progress has no activity indicator"
archphene_adb_run exec-out screencap -p >"$artifact_dir/progress.png"
archphene_wait_log \
  'Portal folder imported name=SlowProviderFixture entries=1 bytes=48' \
  55 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
elapsed=$((SECONDS - started))
((elapsed >= 38 && elapsed <= 52)) ||
  archphene_die "slow provider completed outside its expected window: ${elapsed}s"

probe_result="$(
  archphene_adb_run shell run-as "$manager" cat "$probe_output" |
    tr -d '\r'
)"
[[ "$probe_result" == "PASS portal folder selected with one logical project URI" ]] ||
  archphene_die "slow-provider portal probe did not receive its selected URI"
expected="$(
  printf '0123456789abcdef0123456789abcdef0123456789abcdef' |
    sha256sum | awk '{print $1}'
)"
actual="$(
  archphene_adb_run shell run-as "$manager" sha256sum "$target_path/payload.bin" |
    awk '{print $1}' | tr -d '\r'
)"
[[ "$actual" == "$expected" ]] ||
  archphene_die "slow provider changed the payload"
archphene_adb_run shell pidof "$package" >/dev/null ||
  archphene_die "slow provider killed the launcher"
archphene_adb_run shell pidof "$manager" >/dev/null ||
  archphene_die "slow provider killed the manager"

logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneLauncher:E ArchphenePortal:I ArchpheneLauncherSession:E \
    ArchpheneRuntime:E AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'provider remained blocked'* ]] ||
  archphene_die "sliding watchdog incorrectly rejected the slow provider"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "slow provider emitted a fatal event: $logs"

archphene_wait_ui \
  "package=\"$package\"" "folder-slow-provider-complete-$serial_slug" 20
[[ "$ARCHPHENE_UI" != *'text="Importing selected folder…"'* ]] ||
  archphene_die "folder progress remained visible after completion"
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"

trap - EXIT
cleanup
archphene_note "Slow folder provider passed on $serial in ${elapsed}s"
archphene_note "  Three 16-byte chunks each arrived within the sliding 30-second deadline"
archphene_note "  The total transfer exceeded 30 seconds without a false timeout"
archphene_note "  Progress appeared and cleared; exact bytes and both processes survived"
archphene_note "  Full-device screenshots: $artifact_dir/{picker-ready,progress,complete}.png"
