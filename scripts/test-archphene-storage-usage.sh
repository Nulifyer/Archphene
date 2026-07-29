#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
builder_apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --builder-apk) builder_apk="${2:?missing value for --builder-apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --builder-apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
  [[ -n "$builder_apk" ]] ||
    archphene_die "--builder-apk is required with --install-apk"
fi

archphene_test_init "$serial"
manager=org.archphene.app.debug
builder=org.archphene.builder.debug
activity="$manager/org.archphene.app.MainActivity"
manager_fixture=files/arch-root/var/cache/archphene/aur-sources/storage-usage-device-fixture
builder_fixture=files/aur-build-workspace-v2/storage-usage-device-fixture
output_dir="$ARCHPHENE_ROOT/tooling/build/storage-usage"
state_dir="$(archphene_mktemp_dir storage-usage-state)"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "storage-usage-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

cleanup() {
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" \
    rm -f "$manager_fixture" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$builder" \
    rm -f "$builder_fixture" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$builder" \
    rmdir files/aur-build-workspace-v2 >/dev/null 2>&1 || true
  if [[ "$initial_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    restore_section || true
  fi
  find "$state_dir" -mindepth 1 -maxdepth 1 -type f -delete 2>/dev/null || true
  rmdir "$state_dir" 2>/dev/null || true
}
trap cleanup EXIT

snapshot_protected_state() {
  local suffix="$1"
  archphene_adb_run shell run-as "$manager" \
    find files/arch-root/var/cache/pacman/pkg -mindepth 1 -maxdepth 1 \
    -type f -print 2>/dev/null |
    sort >"$state_dir/packages-$suffix"
  archphene_adb_run shell run-as "$manager" \
    du -ak files/arch-root/var/cache/pacman/pkg 2>/dev/null |
    sort >>"$state_dir/packages-$suffix"
  archphene_adb_run shell run-as "$manager" \
    find files/arch-root/var/lib/pacman/local -mindepth 1 -maxdepth 1 \
    -type d -print 2>/dev/null |
    sort >"$state_dir/installed-$suffix"
  archphene_adb_run shell run-as "$manager" \
    find files/arch-root/home/archphene -mindepth 1 -print 2>/dev/null |
    sort >"$state_dir/home-$suffix"
  archphene_adb_run shell run-as "$manager" \
    du -ak files/arch-root/home/archphene 2>/dev/null |
    sort >>"$state_dir/home-$suffix"
}

storage_value() {
  local ui="$1"
  local label="$2"
  python3 -c '
import sys
import xml.etree.ElementTree as ET

label = sys.argv[1]
root = ET.fromstring(sys.stdin.read())
texts = [node.attrib.get("text", "") for node in root.iter()]
try:
    offset = texts.index(label)
except ValueError:
    raise SystemExit(f"storage label is missing: {label}")
for text in texts[offset + 1:]:
    if text:
        print(text)
        break
else:
    raise SystemExit(f"storage value is missing: {label}")
' "$label" <<<"$ui"
}

wait_for_storage_value() {
  local label="$1"
  local expected="$2"
  local phase="$3"
  local ui value
  for attempt in {1..30}; do
    ui="$(archphene_capture_ui "$phase-$attempt-$serial")"
    value="$(storage_value "$ui" "$label")"
    if [[ "$value" =~ $expected ]]; then
      ARCHPHENE_UI="$ui"
      ARCHPHENE_STORAGE_VALUE="$value"
      return 0
    fi
    sleep 1
  done
  archphene_die "$label did not reach expected storage state: $value"
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_require_file "$builder_apk"
  archphene_adb_run install -r "$builder_apk" >/dev/null
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "$manager is not installed; pass --install-apk with --apk"
archphene_adb_run shell pm path "$builder" >/dev/null ||
  archphene_die "$builder is not installed; pass --install-apk with --builder-apk"

existing_manager="$(
  archphene_adb_run shell run-as "$manager" sh -c \
    "'if [ -d files/arch-root/var/cache/archphene/aur-sources ]; then find files/arch-root/var/cache/archphene/aur-sources -mindepth 1 -print -quit; fi'" \
    2>/dev/null || true
)"
[[ -z "$existing_manager" ]] ||
  archphene_die "refusing to replace existing manager AUR source data: $existing_manager"
existing_builder="$(
  archphene_adb_run shell run-as "$builder" sh -c \
    "'if [ -d files/aur-build-workspace-v2 ]; then find files/aur-build-workspace-v2 -mindepth 1 -print -quit; fi'" \
    2>/dev/null || true
)"
[[ -z "$existing_builder" ]] ||
  archphene_die "refusing to replace existing Builder workspace data: $existing_builder"

archphene_adb_run shell run-as "$manager" \
  mkdir -p files/arch-root/var/cache/archphene/aur-sources
archphene_adb_run shell run-as "$manager" \
  dd if=/dev/zero of="$manager_fixture" bs=4096 count=1024 >/dev/null 2>&1
archphene_adb_run shell run-as "$builder" mkdir -p files/aur-build-workspace-v2
archphene_adb_run shell run-as "$builder" \
  dd if=/dev/zero of="$builder_fixture" bs=4096 count=2048 >/dev/null 2>&1

archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null
ui="$(archphene_capture_ui "storage-usage-launch-$serial")"
if archphene_regex_contains "$ui" 'text="Connect Android files\?"'; then
  archphene_tap_ui_pattern "$ui" 'text="(?:NOT NOW|Not now)"' "Not now"
  ui="$(archphene_capture_ui "storage-usage-after-onboarding-$serial")"
fi
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$ui"
)"
archphene_open_manager_section Files "storage-usage-files-$serial"
ui="$(archphene_capture_ui "storage-usage-refresh-$serial")"
archphene_tap_ui_pattern \
  "$ui" 'text="Refresh"[^>]*class="android\.widget\.Button"' Refresh
wait_for_storage_value \
  "AUR build data" '^[1-9][0-9]*(\.[0-9]+)? [kMGT]B$' storage-usage-populated
build_value="$ARCHPHENE_STORAGE_VALUE"
archphene_regex_contains \
  "$ARCHPHENE_UI" 'text="Clear"[^>]*class="android\.widget\.Button"[^>]*enabled="true"' ||
  archphene_die "AUR build-data Clear action was not enabled"
snapshot_protected_state before
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-before-clear.png"

archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Clear"[^>]*class="android\.widget\.Button"' Clear
archphene_wait_ui_exact_text "Clear AUR build data?" "storage-usage-confirm-$serial" 10
archphene_regex_contains \
  "$ARCHPHENE_UI" 'text="This removes retained AUR sources[^"]+Installed packages and Archphene Home files remain' ||
  archphene_die "AUR build-data confirmation did not explain its scope"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Clear"[^>]*resource-id="android:id/button1"' Clear
wait_for_storage_value "AUR build data" '^0 B$' storage-usage-cleared
cleared_value="$ARCHPHENE_STORAGE_VALUE"
archphene_regex_contains "$ARCHPHENE_UI" 'text="Freed [^"]+ of AUR build data"' ||
  archphene_die "AUR build-data cleanup did not report reclaimed storage"
archphene_regex_contains \
  "$ARCHPHENE_UI" 'text="Clear"[^>]*class="android\.widget\.Button"[^>]*enabled="false"' ||
  archphene_die "AUR build-data Clear action remained enabled at zero bytes"

snapshot_protected_state after
cmp "$state_dir/packages-before" "$state_dir/packages-after" ||
  archphene_die "AUR cleanup changed package downloads"
cmp "$state_dir/installed-before" "$state_dir/installed-after" ||
  archphene_die "AUR cleanup changed installed package state"
cmp "$state_dir/home-before" "$state_dir/home-after" ||
  archphene_die "AUR cleanup changed Archphene Home"
archphene_adb_run shell run-as "$manager" test ! -e "$manager_fixture"
archphene_adb_run shell run-as "$builder" test ! -e "$builder_fixture"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-after-clear.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F ArchpheneRuntime:E '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Storage usage emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene storage usage passed on $serial"
archphene_note "  AUR build data: $build_value -> $cleared_value"
archphene_note "  Package downloads, installed packages, and Archphene Home remained exact"
archphene_note "  Full-device screenshots: $output_dir/$serial-{before,after}-clear.png"
