#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.PackageJobTestReceiver"
action=org.archphene.app.debug.action.SEED_PACKAGE_JOB
output_dir="$ARCHPHENE_ROOT/tooling/build/package-diagnostics"
mkdir -p "$output_dir"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
backup_root="files/test-fixtures/package-diagnostics-$serial_slug"
job_store="files/arch-root/var/lib/archphene/package-jobs.v1"
job_backup="$backup_root/package-jobs.v1"
recovery_preferences="shared_prefs/package_recovery.xml"
recovery_backup="$backup_root/package-recovery.xml"
job_existed=false
recovery_existed=false
backup_ready=false
cache_inventory_before=
initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=

package_cache_inventory() {
  archphene_adb_run shell \
    "run-as $package sh -c 'cd files/arch-root/var/cache/pacman/pkg && find . -maxdepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

clean_package_cache_fixture() {
  archphene_adb_run shell run-as "$package" rm -f \
    files/arch-root/var/cache/pacman/pkg/fixture-1.0-1-any.pkg.tar.zst \
    files/arch-root/var/cache/pacman/pkg/fixture-1.0-1-any.pkg.tar.zst.sig \
    files/arch-root/var/cache/pacman/pkg/dependency-1.0-1-any.pkg.tar.zst.part \
    >/dev/null 2>&1 || true
}

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "package-diagnostics-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  clean_package_cache_fixture
  if [[ "$backup_ready" == true ]]; then
    if [[ "$job_existed" == true ]] &&
        archphene_adb_run shell run-as "$package" test -f "$job_backup" 2>/dev/null; then
      archphene_adb_run shell run-as "$package" cp -p "$job_backup" "$job_store" \
        >/dev/null 2>&1 || true
    else
      archphene_adb_run shell run-as "$package" rm -f "$job_store" \
        >/dev/null 2>&1 || true
    fi
    if [[ "$recovery_existed" == true ]] &&
        archphene_adb_run shell run-as "$package" test -f "$recovery_backup" 2>/dev/null; then
      archphene_adb_run shell run-as "$package" cp -p \
        "$recovery_backup" "$recovery_preferences" >/dev/null 2>&1 || true
    else
      archphene_adb_run shell run-as "$package" rm -f "$recovery_preferences" \
        >/dev/null 2>&1 || true
    fi
    archphene_adb_run shell run-as "$package" rm -f \
      "$job_backup" "$recovery_backup" >/dev/null 2>&1 || true
    archphene_adb_run shell run-as "$package" rmdir "$backup_root" \
      >/dev/null 2>&1 || true
  fi
  if [[ "$initial_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    restore_section || true
  fi
}
trap cleanup EXIT

cases=(
  "network|install|Download failed. Check the connection, then Review.|0"
  "storage|install|Not enough Linux storage: 757 MiB is required and 454 MiB is available. Clear unrelated downloads or free Android storage, then Review.|0"
  "trust|install|Package trust verification failed. Refresh catalogs, then Review.|0"
  "changed|install|Repository or installed state changed. Review the current package before retrying.|0"
  "catalog|install|Package catalog is unavailable or invalid. Refresh catalogs, then Review.|0"
  "generic|install|Install failed before package mutation: pacman exited with status 1. Review before retrying.|0"
  "mutation|install|Install did not finish. Installed state was refreshed; Review before continuing.|97"
  "refresh-failed|remove|Removal did not finish and installed state could not be refreshed. Restart Archphene, then Review.|97"
)

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell am force-stop "$package" >/dev/null
clean_package_cache_fixture
archphene_adb_run shell run-as "$package" mkdir -p "$backup_root"
if archphene_adb_run shell run-as "$package" test -f "$job_store"; then
  job_existed=true
  archphene_adb_run shell run-as "$package" cp -p "$job_store" "$job_backup"
fi
if archphene_adb_run shell run-as "$package" test -f "$recovery_preferences"; then
  recovery_existed=true
  archphene_adb_run shell run-as "$package" cp -p \
    "$recovery_preferences" "$recovery_backup"
fi
backup_ready=true
cache_inventory_before="$(package_cache_inventory)"

first=true
for fixture in "${cases[@]}"; do
  IFS='|' read -r failure operation message progress <<<"$fixture"
  package_name="diag-$failure"
  token="diag-$failure-$serial_slug"
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run logcat -c
  broadcast_args=(
    shell am broadcast
    -f 0x20
    -n "$receiver"
    -a "$action"
    --es token "$token"
    --es package "$package_name"
    --es state failed
    --es operation "$operation"
    --es failure "$failure"
  )
  if [[ "$failure" == storage ]]; then
    broadcast_args+=(--ez cache-fixture true)
  fi
  archphene_adb_run "${broadcast_args[@]}" >/dev/null
  archphene_wait_log \
    "Seeded package job state=failed token=$token" 20 \
    "ArchphenePackageJobProbe:V *:S" >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  if [[ "$first" == true ]]; then
    archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
    ARCHPHENE_UI="$(
      archphene_capture_ui "package-diagnostics-onboarding-check-$serial"
    )"
    if archphene_regex_contains "$ARCHPHENE_UI" 'text="Connect Android files\?"'; then
      archphene_skip_storage_onboarding "package-diagnostics-onboarding-$serial"
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
' <<<"$ARCHPHENE_UI"
    )"
    first=false
  fi
  archphene_open_manager_section \
    Packages "package-diagnostics-packages-$failure-$serial"
  archphene_wait_ui 'class="android.widget.EditText"' \
    "package-diagnostics-field-$failure-$serial" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'class="android.widget.EditText"' 'package name'
  archphene_adb_run shell input keycombination 113 29 >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
  archphene_adb_run shell input text "$package_name" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_wait_ui_exact_text \
    "$package_name" "package-diagnostics-name-$failure-$serial" 20
  archphene_wait_ui_exact_text \
    "$message" "package-diagnostics-message-$failure-$serial" 15
  action_label=Review
  [[ "$failure" == storage ]] && action_label="Clear cache"
  if [[ "$failure" == trust || "$failure" == catalog ]]; then
    action_label="Refresh package catalogs"
  fi
  archphene_wait_ui_exact_text \
    "$action_label" "package-diagnostics-action-$failure-$serial" 15
  operation_label=Install
  [[ "$operation" == remove ]] && operation_label=Remove
  archphene_wait_ui_exact_text \
    "$operation_label · Failed · $progress%" \
    "package-diagnostics-state-$failure-$serial" 15
  sleep 1
  archphene_adb_run exec-out screencap -p \
    >"$output_dir/$serial-$failure.png"
  if [[ "$failure" == storage ]]; then
    archphene_tap_text "$ARCHPHENE_UI" "Clear cache"
    archphene_wait_ui_exact_text \
      "Freed 3.5 KiB of unrelated downloads and retained this package's verified closure. Review before retrying." \
      "package-diagnostics-storage-cleaned-$serial" 20
    archphene_wait_ui_exact_text \
      "Review" "package-diagnostics-storage-review-$serial" 15
    cache_inventory_after="$(package_cache_inventory)"
    [[ "$cache_inventory_after" == "$cache_inventory_before" ]] ||
      archphene_die "package cache cleanup changed pre-existing verified downloads"
    archphene_adb_run shell am force-stop "$package" >/dev/null
    archphene_adb_run shell am start -W -n "$activity" >/dev/null
    archphene_wait_ui 'class="android.widget.EditText"' \
      "package-diagnostics-storage-restored-field-$serial" 15
    archphene_tap_ui_pattern "$ARCHPHENE_UI" \
      'class="android.widget.EditText"' 'package name'
    archphene_adb_run shell input keycombination 113 29 >/dev/null
    archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
    archphene_adb_run shell input text "$package_name" >/dev/null
    archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
    archphene_wait_ui_exact_text \
      "Freed 3.5 KiB of unrelated downloads and retained this package's verified closure. Review before retrying." \
      "package-diagnostics-storage-restored-$serial" 20
    archphene_wait_ui_exact_text \
      "Review" "package-diagnostics-storage-restored-review-$serial" 15
    sleep 1
    archphene_adb_run exec-out screencap -p \
      >"$output_dir/$serial-storage-cleaned.png"
  fi
done

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Package diagnostics emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene package diagnostics passed on $serial"
archphene_note "  Network, storage cleanup, trust, changed-state, catalog, generic, and mutation guidance passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{network,storage,storage-cleaned,trust,changed,catalog,generic,mutation,refresh-failed}.png"
