#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/live-theme-test.sh"

serial=emulator-5554
package=
toolkit=
label=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --toolkit) toolkit="${2:?}"; shift 2 ;;
    --label) label="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] \
  || archphene_die '--package must be a generated Linux wrapper ID'
archphene_validate_choice "$toolkit" toolkit qt6 gtk3 gtk4
[[ -n "$label" ]] || archphene_die '--label is required'

archphene_test_init "$serial"
manager=org.archpheneos.manager
manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
archphene_regex_contains "$manager_dump" '(?m)^\s*flags=\[[^]]*DEBUGGABLE' \
  || archphene_die 'appearance policy test requires a debuggable manager'
activity="$(archphene_launcher "$package")"
old_mode="$(archphene_adb_run shell cmd uimode night \
  | sed -n 's/^Night mode: //p' | tr -d '\r')"
read -r old_theme old_material \
  <<<"$(archphene_saved_linux_appearance "$manager")"
tmp="$(archphene_mktemp_dir appearance-policy)"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_set_test_linux_appearance \
    "$manager" "$old_theme" "$old_material" >/dev/null 2>&1 || true
  archphene_adb_run shell cmd uimode night "$old_mode" >/dev/null 2>&1 || true
}
trap cleanup EXIT

config_path() {
  case "$toolkit" in
    qt6) printf '%s' files/linux-home/.config/kdeglobals ;;
    gtk3) printf '%s' files/linux-home/.config/gtk-3.0/gtk.css ;;
    gtk4) printf '%s' files/linux-home/.config/gtk-4.0/gtk.css ;;
  esac
}

run_case() {
  local name="$1" theme="$2" android_mode="$3" material="$4" pid runtime_pid
  archphene_adb_run shell cmd uimode night "$android_mode" >/dev/null
  archphene_set_test_linux_appearance "$manager" "$theme" "$material"
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  read -r pid runtime_pid \
    <<<"$(archphene_wait_theme_runtime "$package")" \
    || archphene_die "$label $name runtime is missing"
  sleep 5
  archphene_wait_appearance_log "$pid" \
    "Appearance theme=$theme resolved=$theme.*materialYou=$material" 20
  archphene_assert_theme_config "$package" "$toolkit" \
    "$([[ "$theme" == dark ]] && echo true || echo false)"
  archphene_adb_run exec-out screencap >"$tmp/$name.raw"
  archphene_adb_run shell run-as "$package" cat "$(config_path)" \
    >"$tmp/$name.config"
  archphene_note "$label $name policy rendered with Android PID $pid and Linux PID $runtime_pid."
}

# Explicit manager choices must override the opposite Android system mode.
run_case manager-light light yes false
run_case manager-dark dark no false
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" light-dark \
  "$tmp/manager-light.raw" "$tmp/manager-dark.raw"

# Material You uses the same resolved light/dark policy but substitutes Android
# semantic colors in both the generated toolkit configuration and app pixels.
run_case material-light light no true
! cmp -s "$tmp/manager-light.config" "$tmp/material-light.config" \
  || archphene_die "$label Material You did not change toolkit colors"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$tmp/manager-light.raw" "$tmp/material-light.raw" \
  --minimum-difference 1 --minimum-changed-ratio 0

archphene_note "$label manager light/dark overrides and Material You palette passed on $serial."
