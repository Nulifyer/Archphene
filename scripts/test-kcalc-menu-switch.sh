#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/kcalc-menu}"
mkdir -p "$artifact_dir"

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'mapped=true.*title=KCalc' 30 'ArchpheneInput:V *:S' >/dev/null
ui="$(archphene_capture_ui kcalc-menu-ui)"
if [[ "$ui" == *'text="File"'* ]]; then
  archphene_tap_text "$ui" File
else
  archphene_adb_run shell input tap 40 120
fi
archphene_wait_log 'popup registry=' 10 'ArchpheneInput:V *:S' >/dev/null
if [[ "$ui" == *'text="Settings"'* ]]; then
  archphene_tap_text "$ui" Settings
else
  archphene_adb_run shell input tap 500 120
fi
sleep 2

log="$(archphene_adb_run logcat -d -s ArchpheneInput:V ArchpheneLinuxApp:V '*:S')"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
(( $(grep -c 'popup registry=' <<<"$log") >= 2 )) \
  || archphene_die 'menu switching did not produce two popup frames'
! archphene_regex_contains "$log" 'protocol error|InvalidGrab|UnconfiguredBuffer' \
  || archphene_die 'Wayland popup error'
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/wayland-geometry-check.py" \
  "$artifact_dir/logcat.txt" --require-title KCalc --require-popup

archphene_adb_run exec-out screencap >"$artifact_dir/settings-menu.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/settings-menu.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
  "$artifact_dir/settings-menu.raw"
config="$(archphene_adb_run shell run-as "$package" \
  cat files/linux-home/.config/kdeglobals)"
printf '%s\n' "$config" >"$artifact_dir/kdeglobals"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-contrast-check.py" kde \
  "$artifact_dir/kdeglobals"
grep -Eq '^ControlDensity=(compact|comfortable|touch)$' <<<"$config" \
  || archphene_die 'KCalc is missing resolved control-density configuration'
minimum="$(sed -n 's/^ControlMinSize=//p' <<<"$config" | tail -n1)"
[[ "$minimum" =~ ^[0-9]+$ ]] && ((minimum >= 24)) \
  || archphene_die "invalid KCalc control minimum: ${minimum:-missing}"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" --field 'app=KCalc' \
  --field 'state=Settings menu' --field 'toolkit=qt6' \
  --field "controlMinSize=$minimum" \
  --artifact "$artifact_dir/settings-menu.raw" \
  --artifact "$artifact_dir/settings-menu.png" \
  --artifact "$artifact_dir/logcat.txt" --artifact "$artifact_dir/kdeglobals"

archphene_note "KCalc menu visual gate passed: constrained popup, nonblank frame, density=$minimum. Evidence: $artifact_dir"
