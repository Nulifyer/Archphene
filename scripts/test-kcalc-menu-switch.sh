#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p70b9ee91a45dfb9dc38f5721bcfabbcc
manager=org.archphene.app.debug
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --manager) manager="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
archphene_adb_run shell pm path "$manager" >/dev/null
if [[ -n "$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')" ]]; then
  archphene_die "refusing to replace an active KCalc session: $package"
fi
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/kcalc-menu}"
mkdir -p "$artifact_dir"

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui \
  'text="File"[^>]*clickable="true".*text="Settings"[^>]*clickable="true"' \
  kcalc-menu-ui 30
ui="$ARCHPHENE_UI"
archphene_tap_text "$ui" File
archphene_wait_ui 'text="Quit"[^>]*clickable="true"' kcalc-file-menu 10
ui="$ARCHPHENE_UI"
archphene_tap_text "$ui" Settings
archphene_wait_ui \
  'text="Simple Mode"[^>]*checked="true".*text="Configure KCalc' \
  kcalc-settings-menu 10
printf '%s\n' "$ARCHPHENE_UI" >"$artifact_dir/settings-ui.xml"

log="$(
  archphene_adb_run logcat -d \
    -s ArchpheneLauncherSession:I ArchpheneInput:V ArchpheneLinuxApp:V '*:S'
)"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
! archphene_regex_contains "$log" 'protocol error|InvalidGrab|UnconfiguredBuffer' \
  || archphene_die 'Wayland popup error'
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/wayland-geometry-check.py" \
  "$artifact_dir/logcat.txt" --require-popup

archphene_adb_run exec-out screencap >"$artifact_dir/settings-menu.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/settings-menu.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
  "$artifact_dir/settings-menu.raw" --luma-tail-percent 1
config="$(archphene_adb_run shell run-as "$manager" \
  cat files/arch-root/home/archphene/.config/kdeglobals)"
printf '%s\n' "$config" >"$artifact_dir/kdeglobals"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-contrast-check.py" kde \
  "$artifact_dir/kdeglobals"
grep -Eq '^ControlDensity=(automatic|compact|comfortable|touch)$' <<<"$config" \
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
  --artifact "$artifact_dir/settings-ui.xml" \
  --artifact "$artifact_dir/logcat.txt" --artifact "$artifact_dir/kdeglobals"

archphene_note "KCalc menu visual gate passed: constrained popup, nonblank frame, density=$minimum. Evidence: $artifact_dir"
