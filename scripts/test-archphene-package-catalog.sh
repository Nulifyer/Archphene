#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
reset_data=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --skip-install) skip_install=true; shift ;;
    --reset-data) reset_data=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--install-apk] [--reset-data]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/package-catalog-regression"
mkdir -p "$output_dir"
device_abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$device_abi" in
  x86_64)
    search_query=dotnet-sdk
    search_pattern='extra/dotnet-sdk [^"]+.*The \.NET Core SDK'
    ;;
  arm64-v8a)
    search_query=btop
    search_pattern='extra/btop [^"]+'
    ;;
  *)
    archphene_die "unsupported device ABI: $device_abi"
    ;;
esac

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
if [[ "$reset_data" == true ]]; then
  archphene_adb_run shell pm clear "$package" >/dev/null
fi

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
if [[ "$reset_data" == true ]]; then
  archphene_skip_storage_onboarding "archphene-catalog-onboarding-$serial"
fi
archphene_wait_ui 'Package catalog (ready|not downloaded)' \
  "archphene-catalog-initial-$serial" 15
if ! archphene_regex_contains "$ARCHPHENE_UI" 'Package catalog ready'; then
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="REFRESH CATALOGS"' 'refresh catalogs'
  archphene_wait_ui 'text="Package catalog ready"' \
    "archphene-catalog-ready-$serial" 60
  archphene_wait_log 'Official package catalogs refreshed' 10 >/dev/null
fi

for catalog in core extra; do
  path="files/arch-root/var/lib/pacman/sync/$catalog.db"
  size="$(archphene_adb_run exec-out run-as "$package" stat -c %s "$path" |
    tr -d '\r')"
  mode="$(archphene_adb_run exec-out run-as "$package" stat -c %a "$path" |
    tr -d '\r')"
  [[ "$size" =~ ^[1-9][0-9]*$ && "$size" -le $((64 * 1024 * 1024)) ]] ||
    archphene_die "invalid $catalog catalog size: $size"
  [[ "$mode" == 600 ]] ||
    archphene_die "invalid $catalog catalog mode: $mode"
done
catalog_listing="$(archphene_adb_run exec-out run-as "$package" \
  ls -A files/arch-root/var/lib/pacman/sync | tr -d '\r')"
[[ "$catalog_listing" != *".core.db.download"* ]] ||
  archphene_die "core catalog temporary file survived publication"
[[ "$catalog_listing" != *".extra.db.download"* ]] ||
  archphene_die "extra catalog temporary file survived publication"

search_package() {
  local suffix="$1"
  archphene_wait_ui 'text="Package name"' "archphene-search-field-$suffix-$serial" 15
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="Package name"' 'package search input'
  archphene_adb_run shell input text "$search_query" >/dev/null
  archphene_wait_ui "text=\"$search_query\"" \
    "archphene-search-entered-$suffix-$serial" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="SEARCH"' 'package search'
  archphene_wait_ui "$search_pattern" \
    "archphene-search-results-$suffix-$serial" 20
}

resolve_package() {
  local suffix="$1"
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="DETAILS"' 'package details'
  archphene_wait_ui \
    "text=\"[^\\\"]*/$search_query [^\\\"]+.*Dependency closure: [1-9][0-9]* packages" \
    "archphene-resolution-$suffix-$serial" 20
}

search_package first
resolve_package first
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-$search_query.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui 'text="Package catalog ready"' \
  "archphene-catalog-reused-$serial" 15
search_package reused
resolve_package reused

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "catalog/search flow emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene official package catalog passed on $serial"
archphene_note "  Catalogs were bounded, mode 600, atomic, and reusable after process death"
archphene_note "  Real $search_query results and dependency closure came from packaged pacman"
archphene_note "  Full-device screenshot: $output_dir/$serial-$search_query.png"
