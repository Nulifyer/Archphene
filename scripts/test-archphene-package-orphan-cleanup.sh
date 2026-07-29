#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
package_name=
dependency=
decision=cleanup
interrupt=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --package) package_name="${2:?missing value for --package}"; shift 2 ;;
    --dependency) dependency="${2:?missing value for --dependency}"; shift 2 ;;
    --decision) decision="${2:?missing value for --decision}"; shift 2 ;;
    --interrupt) interrupt=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --package NAME --dependency NAME [--decision keep|cleanup] [--interrupt]"
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
[[ "$decision" == keep || "$decision" == cleanup ]] ||
  archphene_die "--decision must be keep or cleanup"
[[ "$interrupt" == false || "$decision" == cleanup ]] ||
  archphene_die "--interrupt requires --decision cleanup"

archphene_test_init "$serial"
archphene_require_file "$apk"
manager=org.archphene.app.debug
activity="$manager/org.archphene.app.MainActivity"
receiver="$manager/org.archphene.app.PackagePhaseTestReceiver"
root=files/arch-root
local_database="$root/var/lib/pacman/local"
intent="$root/run/package-mutation-v1"
repair="$root/run/package-replacement-repair-v1"
output_dir="$ARCHPHENE_ROOT/tooling/build/package-orphan-cleanup"
mkdir -p "$output_dir"

archphene_adb_run install -r "$apk" >/dev/null
target_was_installed="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$package_name-*" -print -quit | tr -d '\r'
)"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Packages "orphan-section-$serial"
archphene_wait_ui 'class="android.widget.EditText"' "orphan-field-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'class="android.widget.EditText"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$package_name" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_tap_ui_pattern \
  "$(archphene_capture_ui "orphan-details-$serial")" \
  'text="(?:DETAILS|Details)"' Details

if [[ -z "$target_was_installed" ]]; then
  archphene_wait_ui 'text="Install"[^>]*enabled="true"' \
    "orphan-install-$serial" 90
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Install"[^>]*enabled="true"' Install
  archphene_wait_ui 'text="Install · Complete · 100%"' \
    "orphan-install-complete-$serial" 180
fi
archphene_wait_ui 'text="(?:Remove|Retry)"[^>]*enabled="true"' \
  "orphan-remove-$serial" 120

target_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$package_name-*" -print -quit | tr -d '\r'
)"
dependency_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$dependency-*" -print -quit | tr -d '\r'
)"
[[ -n "$target_entry" && -n "$dependency_entry" ]] ||
  archphene_die "fixture packages are not both installed"
dependency_reason="$(
  archphene_adb_run exec-out run-as "$manager" cat "$dependency_entry/desc" |
    tr -d '\r' | awk '/^%REASON%$/{getline; print; exit}'
)"
[[ "$dependency_reason" == 1 ]] ||
  archphene_die "$dependency is not installed as a dependency"

if [[ "$interrupt" == true ]]; then
  archphene_adb_run shell am broadcast \
    -f 0x20 \
    -n "$receiver" \
    -a org.archphene.app.debug.action.ARM_PACKAGE_PRE_TRANSACTION \
    --es token orphan-cleanup \
    --es package "$package_name" \
    --ei hold-ms 30000 >/dev/null
  archphene_wait_log \
    'Started package phases=true token=orphan-cleanup' 15 \
    'ArchphenePackagePhaseProbe:V *:S' >/dev/null
fi

archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="(?:Remove|Retry)"[^>]*enabled="true"' Remove
# UIAutomator can take tens of seconds to settle on a busy emulator even after
# the dialog is visibly presented, so retain a generous device-level bound.
archphene_wait_ui 'text="Remove unused dependencies\?"' \
  "orphan-review-$serial" 150
archphene_wait_ui "Remove $dependency [^&\"]+" \
  "orphan-dependency-$serial" 20
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-$decision-review.png"

if [[ "$decision" == keep ]]; then
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Keep dependencies"' 'Keep dependencies'
else
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Remove all"' 'Remove all'
fi

if [[ "$interrupt" == true ]]; then
  deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)); do
    if archphene_adb_run shell run-as "$manager" test -f "$intent" &&
        archphene_adb_run shell run-as "$manager" test -d "$repair"; then
      break
    fi
    sleep 0.2
  done
  archphene_adb_run shell run-as "$manager" test -f "$intent" ||
    archphene_die "removal mutation intent was not durable"
  archphene_adb_run shell run-as "$manager" test -d "$repair" ||
    archphene_die "complete removal database snapshot was not durable"
  for entry in "$target_entry" "$dependency_entry"; do
    archphene_adb_run shell run-as "$manager" test -d "$entry" ||
      archphene_die "pacman began before the deterministic hold"
  done
  android_pid="$(archphene_android_pid "$manager")"
  archphene_adb_run shell run-as "$manager" kill -9 "$android_pid" >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
  archphene_open_manager_section Packages "orphan-repair-section-$serial"
  archphene_wait_ui 'class="android.widget.EditText"' \
    "orphan-repair-field-$serial" 20
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'class="android.widget.EditText"' 'package name'
  archphene_adb_run shell input keycombination 113 29 >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
  archphene_adb_run shell input text "$package_name" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_wait_ui 'text="Repair"' "orphan-repair-$serial" 40
  archphene_adb_run exec-out screencap -p \
    >"$output_dir/$serial-cleanup-interrupted.png"
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Repair"' Repair
fi

archphene_wait_ui 'text="Remove · Complete · 100%"' \
  "orphan-complete-$serial" 180
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-$decision-complete.png"
target_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$package_name-*" -print -quit | tr -d '\r'
)"
dependency_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$dependency-*" -print -quit | tr -d '\r'
)"
[[ -z "$target_entry" ]] || archphene_die "$package_name remains installed"
if [[ "$decision" == keep ]]; then
  [[ -n "$dependency_entry" ]] ||
    archphene_die "$dependency was removed after Keep dependencies"
else
  [[ -z "$dependency_entry" ]] ||
    archphene_die "$dependency remains after Remove all"
fi
for residue in \
  "$intent" "$repair" "$root/run/package-replacement-repair-v1.tmp" \
  "$root/var/lib/pacman/local/.archphene-replacement-repair.tmp" \
  "$root/var/lib/pacman/db.lck"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "orphan cleanup retained transaction state: $residue"
done

archphene_note "Archphene reviewed dependency cleanup passed on $serial"
archphene_note "  Decision: $decision; interrupted: $interrupt"
archphene_note "  Full-device screenshots: $output_dir/$serial-$decision-{review,complete}.png"
