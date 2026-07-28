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
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/folder-portal-timeout/$serial_slug}"
mkdir -p "$artifact_dir"

folder=Documents
source_path="files/arch-root/home/archphene/$folder"
target_path="files/arch-root/home/archphene/Projects/$folder"
delay_file=cache/portal-folder-provider-delay-ms
probe_output=cache/portal-folder-timeout-probe.out

cleanup() {
  archphene_adb_run shell run-as "$manager" rm -f "$delay_file" "$probe_output" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -rf "$target_path" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell run-as "$manager" test -d "$source_path" ||
  archphene_die "shared timeout source is unavailable: $source_path"
archphene_adb_run shell run-as "$manager" test ! -e "$target_path" ||
  archphene_die "refusing to overwrite timeout target: $target_path"
printf '60000\n' |
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
  "folder-timeout-picker-$serial_slug" 20
archphene_open_documents_archphene_home_root \
  "$ARCHPHENE_UI" "folder-timeout-root-$serial_slug"
deadline=$((SECONDS + 20))
while ((SECONDS < deadline)); do
  ARCHPHENE_UI="$(
    archphene_capture_ui "folder-timeout-source-$serial_slug" 2>/dev/null || true
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
  "folder-timeout-use-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker-ready.png"
archphene_tap_text "$ARCHPHENE_UI" "USE THIS FOLDER"
archphene_wait_ui_exact_text \
  "ALLOW" "folder-timeout-allow-$serial_slug" 20

started=$SECONDS
archphene_tap_text "$ARCHPHENE_UI" "ALLOW"
timeout_log="$(
  archphene_wait_log \
    'Android directory provider remained blocked while attempting to list Android folder' \
    45 'ArchpheneLauncher:E AndroidRuntime:E *:S'
)"
elapsed=$((SECONDS - started))
((elapsed >= 30 && elapsed <= 40)) ||
  archphene_die "provider watchdog fired outside its 30-second deadline: ${elapsed}s"
[[ "$timeout_log" != *'FATAL EXCEPTION'* ]] ||
  archphene_die "provider watchdog caused an Android exception"

deadline=$((SECONDS + 10))
while ((SECONDS < deadline)); do
  if ! archphene_adb_run shell pidof "$package" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done
if archphene_adb_run shell pidof "$package" >/dev/null 2>&1; then
  archphene_die "launcher survived a provider that ignored cancellation"
fi
archphene_adb_run shell pidof "$manager" >/dev/null ||
  archphene_die "provider timeout killed the manager"
archphene_adb_run shell run-as "$manager" test ! -e "$target_path" ||
  archphene_die "provider timeout published a partial project"
archphene_adb_run exec-out screencap -p >"$artifact_dir/rolled-back.png"

trap - EXIT
cleanup
archphene_note "Folder provider timeout passed on $serial"
archphene_note "  The 30-second watchdog stopped only the blocked launcher; the manager survived"
archphene_note "  Rust published no partial project"
archphene_note "  Full-device screenshots: $artifact_dir/{picker-ready,rolled-back}.png"
