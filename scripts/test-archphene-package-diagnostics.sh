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
receiver="$package/org.archphene.app.PackageJobTestReceiver"
action=org.archphene.app.debug.action.SEED_PACKAGE_JOB
output_dir="$ARCHPHENE_ROOT/tooling/build/package-diagnostics"
mkdir -p "$output_dir"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cases=(
  "network|install|Download failed. Check the connection, then Review.|0"
  "storage|install|Not enough Linux storage. Free space, then Review.|0"
  "trust|install|Package trust verification failed. Refresh catalogs, then Review.|0"
  "changed|install|Repository or installed state changed. Review the current package before retrying.|0"
  "catalog|install|Package catalog is unavailable or invalid. Refresh catalogs, then Review.|0"
  "generic|install|Install failed before package mutation: pacman exited with status 1. Review before retrying.|0"
  "mutation|install|Install did not finish. Installed state was refreshed; Review before continuing.|97"
  "refresh-failed|remove|Removal did not finish and installed state could not be refreshed. Restart Archphene, then Review.|97"
)

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null

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
    archphene_skip_storage_onboarding "package-diagnostics-onboarding-$serial"
    first=false
  fi
  archphene_wait_ui_exact_text \
    "$package_name" "package-diagnostics-name-$failure-$serial" 20
  archphene_wait_ui_exact_text \
    "$message" "package-diagnostics-message-$failure-$serial" 15
  action_label=Review
  [[ "$failure" == storage ]] && action_label="Clear cache"
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
      "Freed 4 KiB of downloaded packages. Review before retrying." \
      "package-diagnostics-storage-cleaned-$serial" 20
    archphene_wait_ui_exact_text \
      "Review" "package-diagnostics-storage-review-$serial" 15
    remaining="$(
      archphene_adb_run shell run-as "$package" \
        ls files/arch-root/var/cache/pacman/pkg |
        tr -d '\r'
    )"
    [[ -z "$remaining" ]] ||
      archphene_die "package cache cleanup left entries behind: $remaining"
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
