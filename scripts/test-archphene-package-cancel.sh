#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
during_review=false
debug_cancel_broadcast=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --during-review) during_review=true; shift ;;
    --debug-cancel-broadcast) debug_cancel_broadcast=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH [--during-review] [--debug-cancel-broadcast]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$debug_cancel_broadcast" == false || "$during_review" == true ]] ||
  archphene_die "--debug-cancel-broadcast requires --during-review"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
cache=files/arch-root/var/cache/pacman/pkg
local_database=files/arch-root/var/lib/pacman/local
database_backup=files/arch-root/run/package-cancel-local-backup
database_hidden=false
output_dir="$ARCHPHENE_ROOT/tooling/build/package-cancel"
mkdir -p "$output_dir"

cleanup() {
  set +e
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$database_hidden" == true ]]; then
    archphene_adb_run shell run-as "$package" rm -r "$local_database" >/dev/null 2>&1
    archphene_adb_run shell run-as "$package" mv \
      "$database_backup" "$local_database" >/dev/null 2>&1
  fi
}
trap cleanup EXIT

restore_local_database() {
  [[ "$database_hidden" == true ]] || return 0
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run shell run-as "$package" rm -r "$local_database"
  archphene_adb_run shell run-as "$package" mv "$database_backup" "$local_database"
  database_hidden=false
}

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

assert_package_state_unchanged() {
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
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
if [[ "$during_review" == true ]]; then
  archphene_adb_run shell run-as "$package" test ! -e "$database_backup" ||
    archphene_die "package cancellation database backup already exists"
  archphene_adb_run shell run-as "$package" mv "$local_database" "$database_backup"
  database_hidden=true
  archphene_adb_run shell run-as "$package" mkdir "$local_database"
  archphene_adb_run shell run-as "$package" cp \
    "$database_backup/ALPM_DB_VERSION" "$local_database/ALPM_DB_VERSION"
fi
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui 'text="Package catalog ready"' "package-cancel-catalog-$serial" 20
if [[ "$during_review" == true ]]; then
  compatibility_cache=files/arch-root/var/cache/archphene/package-compatibility-v1
  archphene_adb_run shell run-as "$package" find \
    "$compatibility_cache" -maxdepth 1 -type f -delete >/dev/null
  remaining_records="$(archphene_adb_run exec-out run-as "$package" find \
    "$compatibility_cache" -maxdepth 1 -type f 2>/dev/null | tr -d '\r')"
  [[ -z "$remaining_records" ]] ||
    archphene_die "could not clear the derived compatibility cache"
fi
archphene_wait_ui 'text="Package name"' "package-cancel-field-$serial" 15
if [[ "$during_review" == true ]]; then
  archphene_adb_run shell am broadcast \
    -n "$package/org.archphene.app.PackagePhaseTestReceiver" \
    -a org.archphene.app.debug.action.ARM_PACKAGE_COMPATIBILITY_REVIEW \
    --es token compatibility-review \
    --es package btop \
    --ei hold-ms 30000 >/dev/null
  archphene_wait_log \
    'Started package phases=true token=compatibility-review' 15 \
    'ArchphenePackagePhaseProbe:I *:S' >/dev/null
fi
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input text btop >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="(?:DETAILS|Details)"' "package-cancel-details-$serial" 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:DETAILS|Details)"' 'package details'
if [[ "$during_review" == true ]]; then
  if [[ "$debug_cancel_broadcast" == true ]]; then
    archphene_wait_log \
      'Package compatibility review started for btop' 60 >/dev/null
    archphene_adb_run shell am broadcast \
      -n "$package/org.archphene.app.PackagePhaseTestReceiver" \
      -a org.archphene.app.debug.action.CANCEL_PACKAGE_COMPATIBILITY_REVIEW \
      --es token compatibility-cancel \
      --es package btop \
      --ei hold-ms 750 >/dev/null
    archphene_wait_log \
      'Started package phases=true token=compatibility-cancel' 15 \
      'ArchphenePackagePhaseProbe:I *:S' >/dev/null
  else
    archphene_wait_ui \
      'text="Reviewing cached signed packages for this device"' \
      "package-cancel-review-$serial" 300
    archphene_regex_contains \
      "$ARCHPHENE_UI" 'text="(?:CANCEL|Cancel)"[^>]*enabled="true"' ||
      archphene_die "Cancel must be enabled during a compatibility review"
    archphene_tap_ui_pattern \
      "$ARCHPHENE_UI" 'text="(?:CANCEL|Cancel)"' 'cancel package review'
  fi
  archphene_wait_ui \
    'text="Package compatibility review cancelled"' \
    "package-cancel-review-complete-$serial" 30
  archphene_wait_log 'Cancelled package compatibility review for btop' 15 >/dev/null
  archphene_adb_run exec-out screencap -p >"$output_dir/$serial-review.png"
  fatal_log="$(archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
  [[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
    archphene_die "package review cancellation emitted a fatal error: $fatal_log"
  restore_local_database
  assert_package_state_unchanged
  archphene_note "Archphene compatibility-review cancellation passed on $serial"
  archphene_note "  Cancelled an uncached streaming review before package mutation"
  archphene_note "  Full-device screenshot: $output_dir/$serial-review.png"
  exit 0
fi
archphene_wait_ui \
  'text="(?:VERIFY|Verify|RETRY|Retry)"' \
  "package-cancel-ready-$serial" 180
if archphene_regex_contains "$ARCHPHENE_UI" 'text="(?:CANCEL|Cancel)"'; then
  archphene_die "Cancel must be hidden before a cancellable operation starts"
fi
details_ui="$ARCHPHENE_UI"

archphene_adb_run shell am broadcast \
  -n "$package/org.archphene.app.PackagePhaseTestReceiver" \
  -a org.archphene.app.debug.action.ARM_PACKAGE_WORKER \
  --es token package-worker \
  --es package btop \
  --ei hold-ms 5000 >/dev/null
archphene_wait_log \
  'Started package phases=true token=package-worker' 15 \
  'ArchphenePackagePhaseProbe:I *:S' >/dev/null
archphene_tap_ui_pattern \
  "$details_ui" 'text="(?:VERIFY|Verify|RETRY|Retry)"' 'verify package'
archphene_wait_ui \
  'text="(?:CANCEL|Cancel)"[^>]*enabled="true"' \
  "package-cancel-active-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:CANCEL|Cancel)"' 'cancel package'
archphene_wait_ui \
  'text="(?:Install|Update) · Cancelled · [0-9]+%"' \
  "package-cancel-complete-$serial" 30
archphene_regex_contains \
  "$ARCHPHENE_UI" 'text="Cancelled before package mutation"' ||
  archphene_die "cancelled operation did not expose the pre-mutation boundary"
archphene_wait_log 'Cancelled package operation for btop' 15 >/dev/null

assert_package_state_unchanged

archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui \
  'text="Package name"' \
  "package-cancel-reused-field-$serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input text btop >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui \
  'text="(?:DETAILS|Details)"' \
  "package-cancel-reused-details-$serial" 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:DETAILS|Details)"' 'package details'
archphene_wait_ui \
  'text="(?:Install|Update) · Cancelled · [0-9]+%"' \
  "package-cancel-reused-$serial" 20
archphene_regex_contains \
  "$ARCHPHENE_UI" 'text="Cancelled before package mutation"' ||
  archphene_die "restarted manager did not preserve the pre-mutation boundary"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package cancellation emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene package cancellation passed on $serial"
archphene_note "  Cancelled before mutation and preserved installed/cache state"
archphene_note "  Durable Cancelled state survived manager process restart"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
