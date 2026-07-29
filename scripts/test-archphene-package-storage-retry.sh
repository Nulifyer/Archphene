#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
package_name=
dependency=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --package) package_name="${2:?missing value for --package}"; shift 2 ;;
    --dependency) dependency="${2:?missing value for --dependency}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --package NAME --dependency NAME"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
for name in "$package_name" "$dependency"; do
  [[ "$name" =~ ^[a-zA-Z0-9@._+-]{1,128}$ ]] ||
    archphene_die "invalid package name: $name"
done

archphene_test_init "$serial"
archphene_require_file "$apk"
[[ "$(archphene_adb_run shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] ||
  archphene_die "real storage-pressure gate is intentionally limited to an emulator"

manager=org.archphene.app.debug
activity="$manager/org.archphene.app.MainActivity"
root=files/arch-root
cache="$root/var/cache/pacman/pkg"
local_database="$root/var/lib/pacman/local"
output_dir="$ARCHPHENE_ROOT/tooling/build/package-storage-pressure"
fixture_prefix=archphene-pressure
fixture_paths=()
target_was_installed=

remove_pressure_fixtures() {
  local path
  for path in "${fixture_paths[@]}"; do
    archphene_adb_run shell run-as "$manager" rm -f "$path" >/dev/null 2>&1 || true
  done
}

cleanup() {
  remove_pressure_fixtures
}
trap cleanup EXIT

open_package() {
  local suffix="$1"
  archphene_open_manager_section Packages "storage-$suffix-section-$serial"
  archphene_wait_ui 'class="android.widget.EditText"' \
    "storage-$suffix-field-$serial" 25
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'class="android.widget.EditText"' 'package name'
  archphene_adb_run shell input keycombination 113 29 >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
  archphene_adb_run shell input text "$package_name" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_tap_ui_pattern \
    "$(archphene_capture_ui "storage-$suffix-details-$serial")" \
    'text="(?:DETAILS|Details)"' Details
}

remove_target() {
  local suffix="$1"
  archphene_wait_ui 'text="(?:Remove|Retry)"[^>]*enabled="true"' \
    "storage-$suffix-remove-$serial" 120
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="(?:Remove|Retry)"[^>]*enabled="true"' Remove
  if archphene_wait_ui_optional 'text="Remove unused dependencies\?"' \
    "storage-$suffix-removal-review-$serial" 45; then
    archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Remove all"' 'Remove all'
  fi
  archphene_wait_ui 'text="Remove · Complete · 100%"' \
    "storage-$suffix-removed-$serial" 180
}

archphene_adb_run install -r "$apk" >/dev/null
target_was_installed="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$package_name-*" -print -quit | tr -d '\r'
)"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null
open_package prime

# Establish a freshly verified cache through the public flow, then return to
# an absent target so the pressure attempt exercises a complete install.
if [[ -n "$target_was_installed" ]]; then
  remove_target baseline
fi
archphene_wait_ui 'text="Install"[^>]*enabled="true"' \
  "storage-prime-install-$serial" 120
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Install"[^>]*enabled="true"' Install
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "storage-prime-complete-$serial" 240
remove_target prime

available_kib="$(
  archphene_adb_run shell df -k /data |
    tr -d '\r' |
    awk 'END {print $4}'
)"
[[ "$available_kib" =~ ^[0-9]+$ ]] ||
  archphene_die "could not read emulator free space"
leave_bytes=$((48 * 1024 * 1024))
allocate_bytes=$((available_kib * 1024 - leave_bytes))
((allocate_bytes > 256 * 1024 * 1024)) ||
  archphene_die "emulator has too little free space for a safe pressure fixture"
((allocate_bytes <= 32 * 1024 * 1024 * 1024)) ||
  archphene_die "emulator data volume is too large for this bounded gate"

index=0
while ((allocate_bytes > 0)); do
  chunk_bytes=3000000000
  if ((allocate_bytes < chunk_bytes)); then
    chunk_bytes=$allocate_bytes
  fi
  index=$((index + 1))
  ((index <= 12)) || archphene_die "pressure fixture exceeded its file bound"
  path="$cache/$fixture_prefix-$index-1-1-any.pkg.tar.zst"
  fixture_paths+=("$path")
  archphene_adb_run shell run-as "$manager" \
    fallocate -l "$chunk_bytes" "$path"
  allocate_bytes=$((allocate_bytes - chunk_bytes))
done

open_package pressure
archphene_wait_ui 'text="Install"[^>]*enabled="true"' \
  "storage-pressure-install-$serial" 120
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Install"[^>]*enabled="true"' Install
archphene_wait_ui 'text="Clear cache"' "storage-pressure-failed-$serial" 240
archphene_wait_ui 'text="Not enough Linux storage: [^"]+ is required and [^"]+ is available' \
  "storage-pressure-capacity-$serial" 30
mkdir -p "$output_dir"
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-real-pressure.png"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Clear cache"' 'Clear cache'
archphene_wait_ui 'text="Freed [^"]+ of unrelated downloads and retained this package' \
  "storage-pressure-cleared-$serial" 120
archphene_wait_ui 'text="Review"' "storage-pressure-review-$serial" 30
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-cache-recovered.png"
for path in "${fixture_paths[@]}"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$path" ||
    archphene_die "cache recovery retained pressure fixture: $path"
done
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Review"' Review
archphene_wait_ui 'text="Retry"[^>]*enabled="true"' \
  "storage-pressure-retry-$serial" 120
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Retry"[^>]*enabled="true"' Retry
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "storage-pressure-complete-$serial" 240
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-retry-complete.png"

for name in "$package_name" "$dependency"; do
  entry="$(
    archphene_adb_run exec-out run-as "$manager" find "$local_database" \
      -maxdepth 1 -type d -name "$name-*" -print -quit | tr -d '\r'
  )"
  [[ -n "$entry" ]] || archphene_die "$name was not installed after storage retry"
done
for residue in \
  "$root/run/package-mutation-v1" \
  "$root/run/package-replacement-repair-v1" \
  "$root/var/lib/pacman/db.lck"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "storage retry retained transaction state: $residue"
done

if [[ -z "$target_was_installed" ]]; then
  remove_target restore
fi
trap - EXIT
cleanup
archphene_note "Archphene real storage-pressure retry passed on $serial"
archphene_note "  Real capacity rejection, selective cache recovery, Review, signed Retry, and baseline restoration passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{real-pressure,cache-recovered,retry-complete}.png"
