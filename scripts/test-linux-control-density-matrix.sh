#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/live-theme-test.sh"

serial=emulator-5554
package=
toolkit=
label=
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --toolkit) toolkit="${2:?}"; shift 2 ;;
    --label) label="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$serial" == emulator-* ]] || archphene_die 'density matrix requires an emulator'
[[ -n "$package" && -n "$label" ]] || archphene_die '--package and --label are required'
archphene_validate_choice "$toolkit" toolkit qt6 gtk3 gtk4 foot
archphene_test_init "$serial"
manager=org.archpheneos.manager
archphene_adb_run shell pm path "$package" >/dev/null \
  || archphene_die "$label wrapper is not installed"
manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
archphene_regex_contains "$manager_dump" '(?m)^\s*flags=\[[^]]*DEBUGGABLE' \
  || archphene_die 'density matrix requires a debuggable manager'

safe_serial="${serial//[^A-Za-z0-9._-]/_}"
safe_label="${label//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/$safe_label-density}"
mkdir -p "$artifact_dir"
activity="$(archphene_launcher "$package")"
old_size="$(archphene_adb_run shell wm size | tr -d '\r')"
old_density="$(archphene_adb_run shell wm density | tr -d '\r')"
old_controls="$(archphene_saved_control_density "$manager")"
restore() {
  local override
  override="$(sed -n 's/^Override size: //p' <<<"$old_size")"
  if [[ -n "$override" ]]; then
    archphene_adb_run shell wm size "$override" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm size reset >/dev/null 2>&1 || true
  fi
  override="$(sed -n 's/^Override density: //p' <<<"$old_density")"
  if [[ -n "$override" ]]; then
    archphene_adb_run shell wm density "$override" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm density reset >/dev/null 2>&1 || true
  fi
  archphene_set_test_control_density "$manager" "$old_controls" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap restore EXIT

profiles=(
  'phone-auto 1080 2400 420 automatic touch 48 20'
  'tablet-auto 1280 1920 280 automatic comfortable 40 20'
  'docked-auto 1920 1080 240 automatic comfortable 40 20'
  'phone-compact 1080 2400 420 compact compact 32 18'
  'phone-comfortable 1080 2400 420 comfortable comfortable 40 20'
  'phone-touch 1080 2400 420 touch touch 48 22'
)
for row in "${profiles[@]}"; do
  read -r name width height wm_density requested resolved target_dp affordance_dp <<<"$row"
  profile_dir="$artifact_dir/$name"
  mkdir -p "$profile_dir"
  archphene_adb_run shell wm size "${width}x${height}"
  archphene_adb_run shell wm density "$wm_density"
  archphene_set_test_control_density "$manager" "$requested"
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_wait_log 'mapped=true.*primary=true' 45 \
    'ArchpheneInput:V ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null
  archphene_wait_log "controls=$resolved controlTargetDp=$target_dp" 20 \
    'ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null
  pid="$(archphene_android_pid "$package")"
  [[ -n "$pid" ]] || archphene_die "$name $label Android process is missing"
  sleep 2
  archphene_adb_run exec-out screencap >"$profile_dir/main.raw"
  archphene_adb_run exec-out screencap -p >"$profile_dir/main.png"
  frame_health_args=()
  case "$toolkit" in
    gtk3|gtk4)
      # Empty editors and terminals legitimately devote most of the surface to
      # one background color. Inspect their title/menu/prompt region instead of
      # accepting or rejecting them based on the blank document body.
      frame_health_args=(--top-percent 4 --bottom-percent 20 \
        --minimum-colors 8 --minimum-luma-range 10)
      ;;
    foot)
      frame_health_args=(--top-percent 4 --bottom-percent 20 \
        --minimum-colors 16 --minimum-luma-range 15)
      ;;
  esac
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
    "$profile_dir/main.raw" "${frame_health_args[@]}"
  archphene_adb_run logcat -d -v threadtime --pid="$pid" \
    -s ArchpheneInput:V ArchpheneLinuxApp:V AndroidRuntime:E '*:S' \
    >"$profile_dir/logcat.txt"
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/wayland-geometry-check.py" \
    "$profile_dir/logcat.txt"

  expected_pixels=$(((target_dp * wm_density + 80) / 160))
  expected_affordance_pixels=$(((affordance_dp * wm_density + 80) / 160))
  config_artifact=
  case "$toolkit" in
    qt6)
      config_path=files/linux-home/.config/kdeglobals
      archphene_adb_run shell run-as "$package" cat "$config_path" \
        >"$profile_dir/kdeglobals"
      grep -Fxq "ControlDensity=$resolved" "$profile_dir/kdeglobals" \
        || archphene_die "$name Qt density is not $resolved"
      python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-contrast-check.py" kde \
        "$profile_dir/kdeglobals"
      config_artifact="$profile_dir/kdeglobals"
      ;;
    gtk3|gtk4)
      version=3
      [[ "$toolkit" == gtk4 ]] && version=4
      config_path="files/linux-home/.config/gtk-$version.0/gtk.css"
      archphene_adb_run shell run-as "$package" cat "$config_path" \
        >"$profile_dir/gtk.css"
      grep -Fq "min-height: ${expected_pixels}px" "$profile_dir/gtk.css" \
        || archphene_die "$name GTK target is not ${expected_pixels}px"
      grep -Fq 'checkbutton check, check, radiobutton radio, radio' \
        "$profile_dir/gtk.css" \
        || archphene_die "$name GTK indicator selectors are missing"
      grep -Fq "min-width: ${expected_affordance_pixels}px" \
        "$profile_dir/gtk.css" \
        || archphene_die "$name GTK visible affordance is not ${expected_affordance_pixels}px"
      python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-contrast-check.py" gtk-accent \
        "$profile_dir/gtk.css"
      config_artifact="$profile_dir/gtk.css"
      ;;
    foot)
      config_path=files/linux-home/.config/archphene/foot.ini
      archphene_adb_run shell run-as "$package" cat "$config_path" \
        >"$profile_dir/foot.ini"
      grep -Fxq "button-width=$expected_pixels" "$profile_dir/foot.ini" \
        || archphene_die "$name Foot target is not ${expected_pixels}px"
      python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-contrast-check.py" foot \
        "$profile_dir/foot.ini"
      config_artifact="$profile_dir/foot.ini"
      ;;
  esac
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
    "$profile_dir/manifest.json" \
    --field "serial=$serial" --field "package=$package" --field "app=$label" \
    --field 'state=main window' --field "toolkit=$toolkit" \
    --field "profile=$name" --field "requestedDensity=$requested" \
    --field "resolvedDensity=$resolved" --field "targetDp=$target_dp" \
    --field "targetPixels=$expected_pixels" \
    --field "visibleAffordanceDp=$affordance_dp" \
    --field "visibleAffordancePixels=$expected_affordance_pixels" \
    --artifact "$profile_dir/main.raw" --artifact "$profile_dir/main.png" \
    --artifact "$profile_dir/logcat.txt" --artifact "$config_artifact"
  archphene_note "$label $name rendered: requested=$requested resolved=$resolved target=${target_dp}dp/${expected_pixels}px"
done

restore
trap - EXIT
archphene_note "$label control-density matrix passed. Evidence: $artifact_dir"
