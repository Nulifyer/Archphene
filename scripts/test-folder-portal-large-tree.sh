#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
timeout=600
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?missing value for --timeout-seconds}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package GENERATED_LAUNCHER [--manager PACKAGE] [--artifact-dir PATH] [--timeout-seconds N]"
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
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 60 && timeout <= 1800)) ||
  archphene_die "--timeout-seconds must be between 60 and 1800"
archphene_test_init "$serial"

activity="$(archphene_launcher "$package")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/folder-portal-large-tree/$serial_slug}"
mkdir -p "$artifact_dir"

folder=ArchpheneLargePortalTree
source_path="files/arch-root/home/archphene/$folder"
target_path="files/arch-root/home/archphene/Projects/$folder"
probe_output=cache/portal-folder-large-tree-probe.out
expected_entries=10000
expected_files=9997
expected_bytes=57

cleanup() {
  archphene_adb_run shell run-as "$manager" rm -f "$probe_output" \
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
  archphene_die "refusing to overwrite large-tree source: $source_path"
archphene_adb_run shell run-as "$manager" test ! -e "$target_path" ||
  archphene_die "refusing to overwrite large-tree target: $target_path"

for shard in a b c; do
  archphene_adb_run shell run-as "$manager" mkdir -p "$source_path/shard-$shard"
  archphene_adb_run shell run-as "$manager" sh -c \
    "'i=0; while [ \"\$i\" -lt 3332 ]; do : > \"$source_path/shard-$shard/file-\$i.txt\"; i=\$((i + 1)); done'"
  printf 'shard-%s-marker\n' "$shard" |
    archphene_adb_run shell run-as "$manager" sh -c \
      "'tee \"$source_path/shard-$shard/file-0.txt\" >/dev/null'"
done
printf 'root-marker\n' |
  archphene_adb_run shell run-as "$manager" sh -c \
    "'tee \"$source_path/marker.txt\" >/dev/null'"

source_entries="$(
  archphene_adb_run shell run-as "$manager" find "$source_path" -mindepth 1 |
    wc -l
)"
[[ "$source_entries" -eq "$expected_entries" ]] ||
  archphene_die "large-tree fixture has $source_entries entries, expected $expected_entries"

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
  "folder-large-tree-picker-$serial_slug" 20
archphene_open_documents_archphene_home_root \
  "$ARCHPHENE_UI" "folder-large-tree-root-$serial_slug"
deadline=$((SECONDS + 20))
while ((SECONDS < deadline)); do
  ARCHPHENE_UI="$(
    archphene_capture_ui "folder-large-tree-source-$serial_slug" \
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
  "folder-large-tree-use-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker-ready.png"
archphene_tap_text "$ARCHPHENE_UI" "USE THIS FOLDER"
archphene_wait_ui_exact_text \
  "ALLOW" "folder-large-tree-allow-$serial_slug" 20
started=$SECONDS
archphene_tap_text "$ARCHPHENE_UI" "ALLOW"

archphene_wait_log \
  "Portal folder imported name=$folder entries=$expected_entries bytes=$expected_bytes" \
  "$timeout" 'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
elapsed=$((SECONDS - started))
probe_result="$(
  archphene_adb_run shell run-as "$manager" cat "$probe_output" |
    tr -d '\r'
)"
[[ "$probe_result" == "PASS portal folder selected" ]] ||
  archphene_die "large-tree portal probe did not receive its selected URI"

actual_entries="$(
  archphene_adb_run shell run-as "$manager" find "$target_path" -mindepth 1 |
    wc -l
)"
actual_files="$(
  archphene_adb_run shell run-as "$manager" find "$target_path" -type f |
    wc -l
)"
[[ "$actual_entries" -eq "$expected_entries" ]] ||
  archphene_die "large-tree import has $actual_entries entries"
[[ "$actual_files" -eq "$expected_files" ]] ||
  archphene_die "large-tree import has $actual_files files"

for relative in marker.txt shard-a/file-0.txt shard-b/file-0.txt shard-c/file-0.txt; do
  source_hash="$(
    archphene_adb_run shell run-as "$manager" sha256sum "$source_path/$relative" |
      awk '{print $1}' | tr -d '\r'
  )"
  target_hash="$(
    archphene_adb_run shell run-as "$manager" sha256sum "$target_path/$relative" |
      awk '{print $1}' | tr -d '\r'
  )"
  [[ "$target_hash" == "$source_hash" ]] ||
    archphene_die "large-tree import changed $relative"
done
[[ "$(
  archphene_adb_run shell run-as "$manager" stat -c %s \
    "$target_path/shard-c/file-3331.txt" | tr -d '\r'
)" == 0 ]] || archphene_die "large-tree import changed an empty file"

archphene_adb_run shell pidof "$package" >/dev/null ||
  archphene_die "large-tree import killed the launcher"
archphene_adb_run shell pidof "$manager" >/dev/null ||
  archphene_die "large-tree import killed the manager"
archphene_wait_ui \
  "package=\"$package\"" "folder-large-tree-complete-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchphenePortal:I ArchpheneLauncherSession:E ArchpheneRuntime:E \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Fatal signal'* ]] ||
  archphene_die "large-tree import emitted a fatal event: $logs"

trap - EXIT
cleanup
archphene_note "Large folder portal tree passed on $serial in ${elapsed}s"
archphene_note "  Exact boundary: $expected_entries entries, $expected_files files, $expected_bytes nonempty bytes"
archphene_note "  Both processes survived and sampled hashes/empty content matched"
archphene_note "  Full-device screenshots: $artifact_dir/{picker-ready,complete}.png"
