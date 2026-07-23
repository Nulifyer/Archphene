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
appearance_log="$(archphene_wait_log 'controlTargetDp=[0-9]+.*controlVisualDp=[0-9]+' 10 \
  'ArchpheneLinuxApp:V *:S')"

read -r width height <<<"$(archphene_adb_run shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
[[ -n "${width:-}" && -n "${height:-}" ]] || archphene_die 'unable to read emulator display size'
target_dp="$(sed -n 's/.*controlTargetDp=\([0-9][0-9]*\).*/\1/p' \
  <<<"$appearance_log" | tail -n1)"
affordance_dp="$(sed -n 's/.*controlVisualDp=\([0-9][0-9]*\).*/\1/p' \
  <<<"$appearance_log" | tail -n1)"
wm_density="$(archphene_adb_run shell wm density \
  | sed -n 's/.*: \([0-9][0-9]*\).*/\1/p' | tail -n1)"
status_top="$(archphene_adb_run shell dumpsys window \
  | sed -n 's/.*type=statusBars frame=\[[^]]*\]\[[0-9]*,\([0-9]*\)\].*/\1/p' \
  | head -n1)"
[[ -n "$target_dp" && -n "$affordance_dp" && -n "$wm_density" && -n "$status_top" ]] \
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
sleep .5
popup_settled_log="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneInput:V ArchpheneRuntime:I AndroidRuntime:E '*:S')"
ime_before="$(grep -c 'IME show source=' <<<"$input_log" || true)"
ime_after="$(grep -c 'IME show source=' <<<"$popup_settled_log" || true)"
((ime_after <= ime_before)) \
  || archphene_die 'Mousepad menu reopened the editor IME over its popup'
[[ "$popup_settled_log" != *'Protocol error'* ]] \
  || archphene_die 'Mousepad menu triggered a Wayland protocol error'
read -r popup_x popup_y popup_width popup_height <<<"$(sed -n \
  's/.*popup registry=[0-9]*:\([0-9][0-9]*\),\([0-9][0-9]*\),\([0-9][0-9]*\),\([0-9][0-9]*\).*/\1 \2 \3 \4/p' \
  <<<"$popup_log" | tail -n1)"
[[ -n "${popup_height:-}" ]] || archphene_die 'unable to resolve Mousepad Edit popup geometry'
preferences_x=$((popup_x + control_pixels * 4 / 3))
preferences_y=$((status_top + popup_y + popup_height - control_pixels / 2))
archphene_adb_run shell input tap "$preferences_x" "$preferences_y"
sleep .5
archphene_adb_run shell input tap "$preferences_x" "$preferences_y"
child_log="$(archphene_wait_log \
  'mapped=true active=true primary=false .*title=Mousepad Preferences' 15 \
  'ArchpheneInput:V *:S')"
child="$({
  python3 -c 'import re,sys;m=re.search(r"window id=(\d+).*primary=false.*title=Mousepad Preferences",sys.stdin.read());print(m.group(1) if m else "")' <<<"$child_log"
})"
read -r child_geometry_x child_geometry_y child_width child_height \
  child_frame_x child_frame_y child_frame_width child_frame_height <<<"$({
  python3 -c '
import re, sys
child, text = sys.argv[1], sys.stdin.read()
matches = re.findall(
    rf"window id={re.escape(child)} .*?mapped=true .*?"
    r"geometry=(-?\d+),(-?\d+) (\d+)x(\d+).*?"
    r"compositedFrame=(-?\d+),(-?\d+) (\d+)x(\d+)", text)
print(" ".join(matches[-1]) if matches else "")
' "$child" <<<"$child_log"
})"

[[ -n "$main" && -n "$child" && "$main" != "$child" ]] || archphene_die 'secondary preferences window not created'
[[ -n "${child_frame_height:-}" && "$child_width" -gt "$control_pixels" \
    && "$child_height" -gt "$control_pixels" ]] \
  || archphene_die 'secondary preferences window geometry is incomplete'
log="$(archphene_adb_run logcat -d -s ArchpheneInput:V ArchpheneLinuxApp:V '*:S')"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/wayland-geometry-check.py" \
  "$artifact_dir/logcat.txt" --require-title 'Mousepad Preferences' --require-popup
archphene_adb_run exec-out screencap >"$artifact_dir/preferences.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/preferences.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
  "$artifact_dir/preferences.raw"

# Exercise the first visible checkbox through the same Android pointer route a
# user touches. The checkbox center is derived from the finalized child content
# frame and density policy; no Mousepad-specific source or widget ID is used.
checkbox_x=$((child_frame_x + child_geometry_x + control_pixels / 2))
checkbox_y=$((status_top + child_frame_y + child_geometry_y \
  + control_pixels * 5 / 2))
archphene_adb_run logcat -c
archphene_adb_run shell input tap "$checkbox_x" "$checkbox_y"
archphene_wait_log 'pointer button pressed=false result=1' 10 \
  'ArchpheneInput:D *:S' >/dev/null
sleep .5
archphene_adb_run exec-out screencap >"$artifact_dir/checkbox-toggled.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/checkbox-toggled.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/preferences.raw" "$artifact_dir/checkbox-toggled.raw" \
  --left-percent 14 --right-percent 25 --top-percent 13 --bottom-percent 23 \
  --minimum-difference 1 --minimum-changed-ratio 0.01
# Restore the original checkbox state so this visual regression is
# non-destructive to the user's Mousepad preferences.
archphene_adb_run shell input tap "$checkbox_x" "$checkbox_y"
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
if [[ "$css" == *'@define-color accent_bg_color'* ]]; then
  [[ "$css" == *'checkbutton check:checked, check:checked'* \
      && "$css" == *'checkbutton check:checked:disabled'* \
      && "$css" == *'background-image: none'* \
      && "$css" == *'background-color: @accent_bg_color'* \
      && "$css" == *'color: @accent_fg_color'* ]] \
    || archphene_die 'Mousepad Material You CSS lacks complete selected-control states'
else
  [[ "$css" != *'background-color:'* ]] \
    || archphene_die 'Mousepad non-Material GTK CSS overrides Adwaita surfaces'
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
  --artifact "$artifact_dir/checkbox-toggled.raw" \
  --artifact "$artifact_dir/checkbox-toggled.png" \
  --artifact "$artifact_dir/logcat.txt" --artifact "$artifact_dir/settings.ini" \
  --artifact "$artifact_dir/gtk.css"

# Close the secondary through its actual GTK title button and require only that
# child to disappear. Then close the primary title button and require the Linux
# runtime and Android host to finish instead of leaving a stale black frame.
android_pid="$(archphene_android_pid "$package")"
linux_pid="$(archphene_linux_loader_pid "$android_pid")"
child_close_x=$((child_frame_x + child_geometry_x + child_width \
  - control_pixels / 2))
child_close_y=$((status_top + child_frame_y + child_geometry_y \
  + control_pixels / 2))
archphene_adb_run logcat -c
archphene_adb_run shell input tap "$child_close_x" "$child_close_y"
archphene_wait_log "window id=$main .*mapped=true active=true primary=true" 15 \
  'ArchpheneInput:I AndroidRuntime:E *:S' >/dev/null
post_child_log="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneInput:I AndroidRuntime:E '*:S')"
[[ "$post_child_log" != *"window id=$child "*"mapped=true"* ]] \
  || archphene_die 'Mousepad Preferences remained in the settled window registry after close'
[[ "$(archphene_android_pid "$package")" == "$android_pid" \
    && "$(archphene_linux_loader_pid "$android_pid")" == "$linux_pid" ]] \
  || archphene_die 'closing Mousepad Preferences restarted or stopped the primary app'

primary_close_x=$((width - control_pixels / 2))
primary_close_y=$((status_top + control_pixels / 2))
archphene_adb_run logcat -c
archphene_adb_run shell input tap "$primary_close_x" "$primary_close_y"
archphene_wait_log 'Linux runtime exited; finishing Android host' 20 \
  'ArchpheneLinuxApp:I ArchpheneRuntime:I AndroidRuntime:E *:S' >/dev/null
deadline=$((SECONDS + 10))
activity_state=
while ((SECONDS < deadline)); do
  activity_state="$(archphene_adb_run shell dumpsys activity activities)"
  if [[ -z "$(archphene_linux_loader_pid "$android_pid" || true)" \
      && "$activity_state" != *"$package"* ]]; then
    break
  fi
  sleep .25
done
[[ -z "$(archphene_linux_loader_pid "$android_pid" || true)" ]] \
  || archphene_die 'Mousepad primary close left the Linux runtime running'
[[ "$activity_state" != *"$package"* ]] \
  || archphene_die 'Mousepad primary close left a stale Android Activity'
archphene_note "Mousepad Preferences visual/interaction gate passed: checkbox toggled/restored, child $child closed independently, primary close finished the host, and Adwaita-owned pixels remained bounded. Evidence: $artifact_dir"
