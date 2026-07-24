#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
cache=files/arch-root/var/cache/pacman/pkg
output_dir="$ARCHPHENE_ROOT/tooling/build/package-cancel"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/btop ||
  archphene_die "package cancellation requires the existing btop installation"
local_btop="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name 'btop-*' |
  tr -d '\r' | head -n1)"
[[ -n "$local_btop" ]] ||
  archphene_die "package cancellation requires the existing btop database entry"
database_state="$(archphene_adb_run exec-out run-as "$package" sha256sum \
  "$local_btop/desc" "$local_btop/files" | tr -d '\r')"
archive="$(archphene_adb_run exec-out run-as "$package" find "$cache" \
  -maxdepth 1 -type f -name 'btop-*.pkg.tar.*' | tr -d '\r' | head -n1)"
[[ -n "$archive" && "$archive" != *.sig ]] ||
  archphene_die "package cancellation requires the cached btop archive"
signature="$archive.sig"
archphene_adb_run shell run-as "$package" test -f "$signature" ||
  archphene_die "package cancellation requires the cached btop signature"
archive_state="$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$archive" |
  tr -d '\r')"
signature_state="$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$signature" |
  tr -d '\r')"

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui 'text="Package catalog ready"' "package-cancel-catalog-$serial" 20
archphene_wait_ui 'text="Package name"' "package-cancel-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input text btop >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="DETAILS"' "package-cancel-details-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="DETAILS"' 'package details'
archphene_wait_ui 'text="VERIFY"' "package-cancel-ready-$serial" 20
archphene_regex_contains "$ARCHPHENE_UI" 'text="CANCEL"[^>]*enabled="false"' ||
  archphene_die "Cancel must be visible and disabled before an operation"
details_ui="$ARCHPHENE_UI"

archphene_tap_ui_pattern "$details_ui" 'text="VERIFY"' 'verify package'
archphene_tap_ui_pattern "$details_ui" 'text="CANCEL"' 'cancel package'
archphene_wait_ui \
  'text="btop · Cancelled · [0-9]+%.*Cancelled before package mutation"' \
  "package-cancel-complete-$serial" 30
archphene_wait_log 'Cancelled package operation for btop' 15 >/dev/null

archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/btop ||
  archphene_die "cancelled verification mutated the installed package"
[[ "$(archphene_adb_run exec-out run-as "$package" sha256sum \
  "$local_btop/desc" "$local_btop/files" | tr -d '\r')" == "$database_state" ]] ||
  archphene_die "cancelled verification changed btop's package database entry"
[[ "$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$archive" |
  tr -d '\r')" == "$archive_state" ]] ||
  archphene_die "cancelled verification rewrote the cached archive"
[[ "$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$signature" |
  tr -d '\r')" == "$signature_state" ]] ||
  archphene_die "cancelled verification rewrote the cached signature"
temporary="$(archphene_adb_run exec-out run-as "$package" find "$cache" \
  -maxdepth 1 -type f -name '*.part' | tr -d '\r')"
[[ -z "$temporary" ]] ||
  archphene_die "cancelled verification left a partial package payload"
archphene_adb_run shell run-as "$package" test ! -e \
  files/arch-root/run/package-install-reasons-v1 ||
  archphene_die "cancelled verification reached the package commit boundary"

archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui \
  'text="btop · Cancelled · [0-9]+%.*Cancelled before package mutation"' \
  "package-cancel-reused-$serial" 20

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package cancellation emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene package cancellation passed on $serial"
archphene_note "  Cancelled before mutation and preserved installed/cache state"
archphene_note "  Durable Cancelled state survived manager process restart"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
