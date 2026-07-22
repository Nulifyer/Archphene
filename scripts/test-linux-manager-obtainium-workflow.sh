#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--serial SERIAL]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
manager=org.archpheneos.manager
kcalc=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2

archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -n "$manager/.MainActivity" >/dev/null
archphene_wait_ui_exact_text Apps manager-obtainium-home 20
ui="$ARCHPHENE_UI"
archphene_tap_text "$ui" KCalc
archphene_wait_ui_exact_text 'Package source' manager-obtainium-detail 20
detail="$ARCHPHENE_UI"

for evidence in \
    'text="Package source"' \
    'text="extra/kcalc"' \
    'text="Runtime"' \
    'text="glibc-x86_64"' \
    "text=\"$kcalc\"" \
    'text="Launch"' \
    'text="Check for update"' \
    'text="Android app settings"'; do
  [[ "$detail" == *"$evidence"* ]] || archphene_die "package detail is missing: $evidence"
done

archphene_tap_text "$detail" 'Android app settings'
archphene_wait_ui 'package="com\.android\.settings"' manager-android-app-settings 20
settings="$ARCHPHENE_UI"
[[ "$settings" == *'text="KCalc"'* ]] || archphene_die 'Android app settings did not open KCalc'
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_wait_ui_exact_text 'Package source' manager-obtainium-return 20

archphene_note 'Manager package-detail workflow passed: source/runtime identity, launch/update controls, and Android app settings round-trip verified.'
