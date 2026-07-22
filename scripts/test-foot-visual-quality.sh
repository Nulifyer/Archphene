#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p73ccc00a787cdc19febdd4a01d4b9d10
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
archphene_adb_run shell pm path "$package" >/dev/null \
  || archphene_die "Foot wrapper is not installed: $package"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/foot}"
mkdir -p "$artifact_dir"
activity="$(archphene_launcher "$package")"

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'mapped=true.*primary=true.*title=foot' 45 \
  'ArchpheneInput:V ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null
sleep 2
pid="$(archphene_android_pid "$package")"
linux_pid="$(archphene_linux_loader_pid "$pid")"
[[ -n "$pid" && -n "$linux_pid" ]] || archphene_die 'Foot process tree is missing'

archphene_adb_run exec-out screencap >"$artifact_dir/prompt.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/prompt.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" "$artifact_dir/prompt.raw"
archphene_adb_run shell input text 'echo%sARCHPHENE_FOOT_VISUAL'
archphene_adb_run shell input keyevent KEYCODE_ENTER
sleep 2
archphene_adb_run exec-out screencap >"$artifact_dir/command.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/command.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/prompt.raw" "$artifact_dir/command.raw" \
  --minimum-changed-ratio 0.0001 --minimum-difference 0.01 \
  --top-percent 4 --bottom-percent 55

archphene_adb_run shell run-as "$package" test -f \
  files/linux-home/.config/archphene/foot.ini \
  || archphene_die 'Foot is missing Archphene-managed visual defaults'
managed="$(archphene_adb_run shell run-as "$package" \
  cat files/linux-home/.config/archphene/foot.ini)"
user="$(archphene_adb_run shell run-as "$package" \
  cat files/linux-home/.config/foot/foot.ini)"
printf '%s\n' "$managed" >"$artifact_dir/foot-managed.ini"
printf '%s\n' "$user" >"$artifact_dir/foot.ini"
grep -Fq 'include=' <<<"$user" || archphene_die 'Foot user config omits managed defaults'
font_pixels="$(sed -n 's/^font=.*:pixelsize=//p' <<<"$managed" | head -n1)"
button_pixels="$(sed -n 's/^button-width=//p' <<<"$managed" | head -n1)"
[[ "$font_pixels" =~ ^[0-9]+$ ]] && ((font_pixels >= 16)) \
  || archphene_die "Foot font remains too small: ${font_pixels:-missing}px"
[[ "$button_pixels" =~ ^[0-9]+$ ]] && ((button_pixels >= 32)) \
  || archphene_die "Foot CSD controls remain too small: ${button_pixels:-missing}px"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-contrast-check.py" foot \
  "$artifact_dir/foot-managed.ini"

log="$(archphene_adb_run logcat -d -s ArchpheneInput:V ArchpheneLinuxApp:V ArchpheneRuntime:V AndroidRuntime:E '*:S')"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/wayland-geometry-check.py" \
  "$artifact_dir/logcat.txt" --require-title foot
[[ "$log" != *'FATAL EXCEPTION'* ]] || archphene_die 'Foot crashed'
[[ "$(archphene_android_pid "$package")" == "$pid" \
    && "$(archphene_linux_loader_pid "$pid")" == "$linux_pid" ]] \
  || archphene_die 'Foot process changed during visible command test'
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" --field 'app=Foot' \
  --field 'state=visible shell command' --field 'toolkit=wayland' \
  --field "androidPid=$pid" --field "linuxPid=$linux_pid" \
  --field "fontPixels=$font_pixels" --field "controlPixels=$button_pixels" \
  --artifact "$artifact_dir/prompt.raw" --artifact "$artifact_dir/prompt.png" \
  --artifact "$artifact_dir/command.raw" --artifact "$artifact_dir/command.png" \
  --artifact "$artifact_dir/logcat.txt" \
  --artifact "$artifact_dir/foot-managed.ini" --artifact "$artifact_dir/foot.ini"

archphene_note "Foot visual gate passed: ${font_pixels}px font, ${button_pixels}px controls, visible command output, bounded frame, stable processes. Evidence: $artifact_dir"
