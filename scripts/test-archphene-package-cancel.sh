#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
target=git
during_review=false
debug_cancel_broadcast=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --target) target="${2:?missing value for --target}"; shift 2 ;;
    --during-review) during_review=true; shift ;;
    --debug-cancel-broadcast) debug_cancel_broadcast=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk] [--target PACKAGE] [--during-review] [--debug-cancel-broadcast]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$target" =~ ^[a-z0-9@._+:-]{1,128}$ ]] ||
  archphene_die "--target is not a valid package name"
[[ "$debug_cancel_broadcast" == false || "$during_review" == true ]] ||
  archphene_die "--debug-cancel-broadcast requires --during-review"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
cache=files/arch-root/var/cache/pacman/pkg
local_database=files/arch-root/var/lib/pacman/local
compatibility_cache=files/arch-root/var/cache/archphene/package-compatibility-v1
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
backup_root="files/test-fixtures/package-cancel-$serial_slug"
compatibility_backup="$backup_root/package-compatibility-v1"
job_store=files/arch-root/var/lib/archphene/package-jobs.v1
job_backup="$backup_root/package-jobs.v1"
recovery_preferences=shared_prefs/package_recovery.xml
recovery_backup="$backup_root/package-recovery.xml"
backup_ready=false
compatibility_moved=false
compatibility_fixture_created=false
job_existed=false
job_snapshot_ready=false
recovery_existed=false
recovery_snapshot_ready=false
job_sha_before=
recovery_sha_before=
database_inventory_before=
cache_inventory_before=
compatibility_inventory_before=
initially_running=false
original_section=
output_dir="$ARCHPHENE_ROOT/tooling/build/package-cancel"
target_executable="files/arch-root/usr/bin/$target"
mkdir -p "$output_dir"

package_database_inventory() {
  archphene_adb_run shell \
    "run-as $package sh -c 'cd $local_database && find . -mindepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

package_cache_inventory() {
  archphene_adb_run shell \
    "run-as $package sh -c 'cd $cache && find . -maxdepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

compatibility_cache_inventory() {
  if ! archphene_adb_run shell run-as "$package" \
    test -d "$compatibility_cache"; then
    printf 'absent\n'
    return
  fi
  archphene_adb_run shell \
    "run-as $package sh -c 'cd $compatibility_cache && find . -mindepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local center ui x y
  ui="$(archphene_capture_ui "package-cancel-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    center="$(
      archphene_ui_node_center \
        "$ui" \
        "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
        "$original_section" 2>/dev/null || true
    )"
    if [[ "$center" =~ ^[0-9]+[[:space:]][0-9]+$ ]]; then
      read -r x y <<<"$center"
      archphene_adb_run shell input tap "$x" "$y" >/dev/null 2>&1 || true
      archphene_wait_ui_optional \
        "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"[^>]*selected=\"true\"" \
        "package-cancel-restore-selected-$serial" 10 >/dev/null 2>&1 || true
    fi
  fi
}

open_packages() {
  local name="$1"
  archphene_wait_ui \
    'text="Packages"[^>]*class="android\.widget\.Button"' \
    "$name-navigation" 60
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" \
    'text="Packages"[^>]*class="android\.widget\.Button"' \
    "Packages"
  archphene_wait_ui \
    'text="Packages"[^>]*class="android\.widget\.Button"[^>]*selected="true"' \
    "$name-selected" 30
}

cleanup() {
  set +e
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$backup_ready" == true ]]; then
    if [[ "$compatibility_fixture_created" == true ]]; then
      archphene_adb_run shell run-as "$package" rm -r \
        "$compatibility_cache" >/dev/null 2>&1 || true
      compatibility_fixture_created=false
    fi
    if [[ "$compatibility_moved" == true ]]; then
      archphene_adb_run shell run-as "$package" mv \
        "$compatibility_backup" "$compatibility_cache" >/dev/null 2>&1 || true
      compatibility_moved=false
    fi
    if [[ "$job_snapshot_ready" == true ]]; then
      if [[ "$job_existed" == true ]]; then
        archphene_adb_run shell run-as "$package" cp -p \
          "$job_backup" "$job_store" >/dev/null 2>&1 || true
      else
        archphene_adb_run shell run-as "$package" rm -f \
          "$job_store" >/dev/null 2>&1 || true
      fi
      job_snapshot_ready=false
    fi
    if [[ "$recovery_snapshot_ready" == true ]]; then
      if [[ "$recovery_existed" == true ]]; then
        archphene_adb_run shell run-as "$package" cp -p \
          "$recovery_backup" "$recovery_preferences" >/dev/null 2>&1 || true
      else
        archphene_adb_run shell run-as "$package" rm -f \
          "$recovery_preferences" >/dev/null 2>&1 || true
      fi
      recovery_snapshot_ready=false
    fi
    archphene_adb_run shell run-as "$package" rm -f \
      "$job_backup" "$recovery_backup" >/dev/null 2>&1 || true
    archphene_adb_run shell run-as "$package" rmdir \
      "$backup_root" >/dev/null 2>&1 || true
    backup_ready=false
  fi
  archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  restore_section >/dev/null 2>&1 || true
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

assert_restored() {
  local actual
  if [[ "$job_existed" == true ]]; then
    actual="$(
      archphene_adb_run exec-out run-as "$package" sha256sum "$job_store" |
        tr -d '\r' |
        awk '{print $1}'
    )"
    [[ "$actual" == "$job_sha_before" ]] ||
      archphene_die "package cancellation did not restore the job store"
  else
    archphene_adb_run shell run-as "$package" test ! -e "$job_store" ||
      archphene_die "package cancellation retained a new job store"
  fi
  if [[ "$recovery_existed" == true ]]; then
    actual="$(
      archphene_adb_run exec-out run-as "$package" \
        sha256sum "$recovery_preferences" |
        tr -d '\r' |
        awk '{print $1}'
    )"
    [[ "$actual" == "$recovery_sha_before" ]] ||
      archphene_die "package cancellation did not restore recovery preferences"
  else
    archphene_adb_run shell run-as "$package" \
      test ! -e "$recovery_preferences" ||
      archphene_die "package cancellation retained new recovery preferences"
  fi
  archphene_adb_run shell run-as "$package" test ! -e "$backup_root" ||
    archphene_die "package cancellation retained its backup directory"
  [[ "$(package_database_inventory)" == "$database_inventory_before" ]] ||
    archphene_die "package cancellation changed the package database"
  [[ "$(package_cache_inventory)" == "$cache_inventory_before" ]] ||
    archphene_die "package cancellation changed the package cache"
  [[ "$(compatibility_cache_inventory)" == "$compatibility_inventory_before" ]] ||
    archphene_die "package cancellation did not restore the compatibility cache"
  if [[ "$initially_running" == true ]]; then
    archphene_android_pid "$package" >/dev/null ||
      archphene_die "package cancellation did not restore the running manager"
  else
    ! archphene_android_pid "$package" >/dev/null 2>&1 ||
      archphene_die "package cancellation left a previously stopped manager running"
  fi
}

if archphene_android_pid "$package" >/dev/null 2>&1; then
  initially_running=true
fi
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell run-as "$package" test ! -e "$backup_root" ||
  archphene_die "package cancellation backup path already exists"
archphene_adb_run shell run-as "$package" test ! -e \
  files/arch-root/run/package-install-reasons-v1 ||
  archphene_die "an existing install-reason recovery must finish first"
compatibility_inventory_before="$(compatibility_cache_inventory)"
archphene_adb_run shell run-as "$package" mkdir -p "$backup_root"
backup_ready=true
if archphene_adb_run shell run-as "$package" test -d "$compatibility_cache"; then
  archphene_adb_run shell run-as "$package" mv \
    "$compatibility_cache" "$compatibility_backup"
  compatibility_moved=true
fi
archphene_adb_run shell run-as "$package" mkdir -p "$compatibility_cache"
compatibility_fixture_created=true
if archphene_adb_run shell run-as "$package" test -f "$job_store"; then
  job_existed=true
  archphene_adb_run shell run-as "$package" cp -p "$job_store" "$job_backup"
  job_sha_before="$(
    archphene_adb_run exec-out run-as "$package" sha256sum "$job_store" |
      tr -d '\r' |
      awk '{print $1}'
  )"
fi
job_snapshot_ready=true
if archphene_adb_run shell run-as "$package" \
  test -f "$recovery_preferences"; then
  recovery_existed=true
  archphene_adb_run shell run-as "$package" cp -p \
    "$recovery_preferences" "$recovery_backup"
  recovery_sha_before="$(
    archphene_adb_run exec-out run-as "$package" \
      sha256sum "$recovery_preferences" |
      tr -d '\r' |
      awk '{print $1}'
  )"
fi
recovery_snapshot_ready=true
database_inventory_before="$(package_database_inventory)"
cache_inventory_before="$(package_cache_inventory)"

archphene_adb_run shell run-as "$package" test -x "$target_executable" ||
  archphene_die "package cancellation requires $target_executable"
local_target="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name "$target-*" |
  tr -d '\r' | head -n1)"
[[ -n "$local_target" ]] ||
  archphene_die "package cancellation requires the existing $target database entry"
database_state="$(archphene_adb_run exec-out run-as "$package" sha256sum \
  "$local_target/desc" "$local_target/files" | tr -d '\r')"
archive="$(archphene_adb_run exec-out run-as "$package" find "$cache" \
  -maxdepth 1 -type f -name "$target-*.pkg.tar.*" \
  ! -name '*.sig' ! -name '*.part' | tr -d '\r' | head -n1)"
[[ -n "$archive" ]] ||
  archphene_die "package cancellation requires the cached $target archive"
signature="$archive.sig"
archphene_adb_run shell run-as "$package" test -f "$signature" ||
  archphene_die "package cancellation requires the cached $target signature"
archive_state="$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$archive" |
  tr -d '\r')"
signature_state="$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$signature" |
  tr -d '\r')"

assert_package_state_unchanged() {
  archphene_adb_run shell run-as "$package" test -x "$target_executable" ||
    archphene_die "cancelled verification mutated the installed package"
  [[ "$(archphene_adb_run exec-out run-as "$package" sha256sum \
    "$local_target/desc" "$local_target/files" | tr -d '\r')" == "$database_state" ]] ||
    archphene_die "cancelled verification changed $target's package database entry"
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

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
initial_ui="$(archphene_capture_ui "package-cancel-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "package-cancel-onboarding-$serial"
  initial_ui="$ARCHPHENE_UI"
fi
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal", "Settings"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$initial_ui"
)"
[[ -n "$original_section" ]] ||
  archphene_die "could not determine the original manager section"
open_packages "package-cancel-packages-$serial"
archphene_wait_ui 'text="Package catalog ready"' "package-cancel-catalog-$serial" 20
if [[ "$during_review" == true ]]; then
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
    --es package "$target" \
    --ei hold-ms 30000 >/dev/null
  archphene_wait_log \
    'Started package phases=true token=compatibility-review' 15 \
    'ArchphenePackagePhaseProbe:I *:S' >/dev/null
fi
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$target" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="(?:DETAILS|Details)"' "package-cancel-details-$serial" 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:DETAILS|Details)"' 'package details'
if [[ "$during_review" == true ]]; then
  if [[ "$debug_cancel_broadcast" == true ]]; then
    archphene_wait_log \
      "Package compatibility review started for $target" 60 >/dev/null
    archphene_adb_run shell am broadcast \
      -n "$package/org.archphene.app.PackagePhaseTestReceiver" \
      -a org.archphene.app.debug.action.CANCEL_PACKAGE_COMPATIBILITY_REVIEW \
      --es token compatibility-cancel \
      --es package "$target" \
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
  archphene_wait_log \
    "Cancelled package compatibility review for $target" 15 >/dev/null
  archphene_adb_run exec-out screencap -p >"$output_dir/$serial-review.png"
  fatal_log="$(archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
  [[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
    archphene_die "package review cancellation emitted a fatal error: $fatal_log"
  assert_package_state_unchanged
  trap - EXIT
  cleanup
  assert_restored
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
  --es package "$target" \
  --ei hold-ms 30000 >/dev/null
archphene_wait_log \
  'Started package phases=true token=package-worker' 15 \
  'ArchphenePackagePhaseProbe:I *:S' >/dev/null
archphene_tap_ui_pattern \
  "$details_ui" 'text="(?:VERIFY|Verify|RETRY|Retry)"' 'verify package'
archphene_wait_ui \
  'text="(?:CANCEL|Cancel)"[^>]*enabled="true"' \
  "package-cancel-active-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  'text="Search results"[^>]*class="android.widget.Button"' \
  "Search results"
archphene_wait_ui \
  'class="android.widget.ProgressBar"[^>]*content-desc="(?:Install|Update) · (?:Queued|Resolving|Verifying) · [0-9]+% progress"' \
  "package-cancel-row-progress-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-active.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:CANCEL|Cancel)"' 'cancel package'
archphene_wait_ui \
  'text="(?:Install|Update) · Cancelled · [0-9]+%"' \
  "package-cancel-complete-$serial" 30
archphene_regex_contains \
  "$ARCHPHENE_UI" 'text="Cancelled before package mutation"' ||
  archphene_die "cancelled operation did not expose the pre-mutation boundary"
archphene_wait_log "Cancelled package operation for $target" 15 >/dev/null

assert_package_state_unchanged

archphene_adb_run exec-out screencap -p >"$output_dir/$serial-cancelled.png"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
open_packages "package-cancel-reused-packages-$serial"
archphene_wait_ui \
  'text="Package name"' \
  "package-cancel-reused-field-$serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$target" >/dev/null
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

trap - EXIT
cleanup
assert_restored
archphene_note "Archphene package cancellation passed on $serial"
archphene_note "  Cancelled before mutation and preserved installed/cache state"
archphene_note "  Durable Cancelled state survived manager process restart"
archphene_note "  Full-device screenshots: $output_dir/$serial-{active,cancelled}.png"
