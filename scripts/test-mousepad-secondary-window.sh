#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
activity=org.archphene.linux.kcalc.MainActivity
artifact_dir=
while (($#)); do
  case "$1" in
    --serial)
      serial="${2:?}"
      shift 2
      ;;
    --package)
      package="${2:?}"
      shift 2
      ;;
    --activity)
      activity="${2:?}"
      shift 2
      ;;
    --artifact-dir)
      artifact_dir="${2:?}"
      shift 2
      ;;
    *)
      archphene_die "unknown argument: $1"
      ;;
  esac
done

archphene_test_init "$serial"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/mousepad-preferences}"
mkdir -p "$artifact_dir"
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$package/$activity" >/dev/null

main_log="$(archphene_wait_log 'window id=[0-9]+.*primary=true.*Mousepad' 30 'ArchpheneInput:V *:S')"
main="$({
  python3 -c 'import re,sys;m=re.search(r"window id=(\d+).*primary=true.*title=[^\n]*Mousepad",sys.stdin.read());print(m.group(1) if m else "")' <<<"$main_log"
})"
appearance_log="$(archphene_wait_log 'controlTargetDp=[0-9]+' 10 \
  'ArchpheneLinuxApp:V *:S')"

read -r width height <<<"$(archphene_adb_run shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
[[ -n "${width:-}" && -n "${height:-}" ]] || archphene_die 'unable to read emulator display size'
target_dp="$(sed -n 's/.*controlTargetDp=\([0-9][0-9]*\).*/\1/p' \
  <<<"$appearance_log" | tail -n1)"
wm_density="$(archphene_adb_run shell wm density \
  | sed -n 's/.*: \([0-9][0-9]*\).*/\1/p' | tail -n1)"
status_top="$(archphene_adb_run shell dumpsys window \
  | sed -n 's/.*type=statusBars frame=\[[^]]*\]\[[0-9]*,\([0-9]*\)\].*/\1/p' \
  | head -n1)"
[[ -n "$target_dp" && -n "$wm_density" && -n "$status_top" ]] \
  || archphene_die 'unable to resolve Mousepad density or Android status inset'
control_pixels=$(((target_dp * wm_density + 80) / 160))
archphene_adb_run shell input tap "$((width / 4))" "$((height * 3 / 5))"
sleep 1
input_log="$(archphene_adb_run logcat -d -v brief -s ArchpheneInput:V '*:S')"
if [[ "$input_log" == *'IME show'* ]]; then
  archphene_adb_run shell input keyevent KEYCODE_BACK
  sleep 1
fi

# Mousepad does not bind a bare comma to Preferences. Exercise the real menu
# path so this proves popup routing as well as secondary-window composition.
menu_y=$((status_top + control_pixels * 3 / 2))
archphene_adb_run shell input tap "$((width * 18 / 100))" "$menu_y"
popup_log="$(archphene_wait_log 'popup registry=[0-9]' 10 'ArchpheneInput:V *:S')"
read -r popup_x popup_y popup_width popup_height <<<"$(sed -n \
  's/.*popup registry=[0-9]*:\([0-9][0-9]*\),\([0-9][0-9]*\),\([0-9][0-9]*\),\([0-9][0-9]*\).*/\1 \2 \3 \4/p' \
  <<<"$popup_log" | tail -n1)"
[[ -n "${popup_height:-}" ]] || archphene_die 'unable to resolve Mousepad Edit popup geometry'
preferences_x=$((popup_x + control_pixels * 4 / 3))
preferences_y=$((status_top + popup_y + popup_height - control_pixels / 2))
archphene_adb_run shell input tap "$preferences_x" "$preferences_y"
sleep .5
archphene_adb_run shell input tap "$preferences_x" "$preferences_y"
child_log="$(archphene_wait_log 'primary=false .*title=Mousepad Preferences' 15 'ArchpheneInput:V *:S')"
child="$({
  python3 -c 'import re,sys;m=re.search(r"window id=(\d+).*primary=false.*title=Mousepad Preferences",sys.stdin.read());print(m.group(1) if m else "")' <<<"$child_log"
})"

[[ -n "$main" && -n "$child" && "$main" != "$child" ]] || archphene_die 'secondary preferences window not created'
log="$(archphene_adb_run logcat -d -s ArchpheneInput:V ArchpheneLinuxApp:V '*:S')"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/wayland-geometry-check.py" \
  "$artifact_dir/logcat.txt" --require-title 'Mousepad Preferences' --require-popup
archphene_adb_run exec-out screencap >"$artifact_dir/preferences.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/preferences.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
  "$artifact_dir/preferences.raw"
settings="$(archphene_adb_run shell run-as "$package" \
  cat files/linux-home/.config/gtk-3.0/settings.ini)"
css="$(archphene_adb_run shell run-as "$package" \
  cat files/linux-home/.config/gtk-3.0/gtk.css)"
printf '%s\n' "$settings" >"$artifact_dir/settings.ini"
printf '%s\n' "$css" >"$artifact_dir/gtk.css"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-contrast-check.py" gtk-accent \
  "$artifact_dir/gtk.css"
grep -Fxq 'gtk-theme-name=Adwaita' <<<"$settings" \
  || archphene_die 'Mousepad does not use one complete Adwaita theme'
grep -Eq '^gtk-application-prefer-dark-theme=(true|false)$' <<<"$settings" \
  || archphene_die 'Mousepad is missing explicit light/dark preference'
[[ "$css" != *'background-color:'* ]] \
  || archphene_die 'Mousepad GTK CSS still overrides partial surface colors'
if ((target_dp >= 48)); then
  affordance_dp=22
elif ((target_dp >= 40)); then
  affordance_dp=20
else
  affordance_dp=18
fi
affordance_pixels=$(((affordance_dp * wm_density + 80) / 160))
grep -Fq 'checkbutton check, check, radiobutton radio, radio' <<<"$css" \
  || archphene_die 'Mousepad GTK CSS does not scale check and radio indicators'
grep -Fq "min-width: ${affordance_pixels}px" <<<"$css" \
  || archphene_die "Mousepad GTK visible affordance is not ${affordance_pixels}px"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" --field 'app=Mousepad' \
  --field 'state=Preferences' --field 'toolkit=gtk3' \
  --field "controlTargetDp=$target_dp" \
  --field "visibleAffordanceDp=$affordance_dp" \
  --field "visibleAffordancePixels=$affordance_pixels" \
  --field "primaryWindow=$main" --field "secondaryWindow=$child" \
  --artifact "$artifact_dir/preferences.raw" \
  --artifact "$artifact_dir/preferences.png" \
  --artifact "$artifact_dir/logcat.txt" --artifact "$artifact_dir/settings.ini" \
  --artifact "$artifact_dir/gtk.css"
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_note "Mousepad Preferences visual gate passed: child $child is bounded, Adwaita owns complete colors, and the rendered frame is nonblank. Evidence: $artifact_dir"
