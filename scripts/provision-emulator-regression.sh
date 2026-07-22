#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
packages=()
timeout=900
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) packages+=("${2:?}"); shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial emulator-SERIAL] [--package kcalc|mousepad] [--timeout-seconds N]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ "$serial" == emulator-* ]] || archphene_die 'regression provisioning is restricted to an emulator'
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 30)) \
  || archphene_die 'timeout must be an integer of at least 30 seconds'
((${#packages[@]})) || packages=(kcalc mousepad)

archphene_test_init "$serial"
manager=org.archpheneos.manager
manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
archphene_regex_contains "$manager_dump" '(?m)^\s*flags=\[[^]]*DEBUGGABLE' \
  || archphene_die 'clean regression provisioning requires a debuggable manager build'

# Generated wrappers intentionally use Android's normal package installer. The
# disposable emulator lane grants the manager permission to launch that UI;
# physical devices remain user-controlled and are never changed here.
archphene_adb_run shell appops set "$manager" REQUEST_INSTALL_PACKAGES allow

package_id() {
  case "$1" in
    kcalc) printf '%s\n' org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2 ;;
    mousepad) printf '%s\n' org.archphene.linux.p241d399e14343c53b8b766e9126776aa ;;
    *) archphene_die "unsupported regression fixture: $1" ;;
  esac
}

for source_package in "${packages[@]}"; do
  android_package="$(package_id "$source_package")"
  if archphene_adb_run shell pm path "$android_package" >/dev/null 2>&1; then
    archphene_note "$source_package regression fixture is already installed ($android_package)."
    continue
  fi

  archphene_adb_run logcat -c
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" \
    --es archphene_test_assemble_qt "$source_package" \
    --ez archphene_test_stage_transaction true \
    --ez archphene_test_install_assembled true >/dev/null

  archphene_wait_ui 'text="(?:Install|Update)"' "provision-$source_package-installer" "$timeout"
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:Install|Update)"' \
    "$source_package installer confirmation"
  archphene_wait_log "activated generated wrapper $android_package" 120 \
    'ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
  archphene_adb_run shell pm path "$android_package" >/dev/null \
    || archphene_die "$source_package package was not installed"
  archphene_note "$source_package regression fixture installed and runtime pack activated ($android_package)."
done

archphene_note "Emulator regression fixtures are ready on $serial."
