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
activity="$(archphene_launcher "$package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/foot-workflows/$safe_serial}"
mkdir -p "$artifact_dir"
ime_file=foot-ime-workflow.txt
clipboard_file=foot-clipboard-workflow.txt
selection_file=foot-selection-workflow.txt
lifecycle_file=foot-lifecycle-workflow.txt
linux_home="/data/user/0/$package/files/linux-home"
screen_size="$(archphene_adb_run shell wm size | tr -d '\r')"
override_size="$(sed -n 's/^Override size: //p' <<<"$screen_size")"
physical_size="$(sed -n 's/^Physical size: //p' <<<"$screen_size")"
size_changed=false
clipboard_session=false

restore_size() {
  if [[ "$size_changed" != true ]]; then return; fi
  if [[ -n "$override_size" ]]; then
    archphene_adb_run shell wm size "$override_size" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm size reset >/dev/null 2>&1 || true
  fi
  size_changed=false
}
cleanup() {
  restore_size
  if [[ "$clipboard_session" == true ]]; then
    close_with_back >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f \
    "files/linux-home/$ime_file" "files/linux-home/$clipboard_file" \
    "files/linux-home/$selection_file" "files/linux-home/$lifecycle_file" \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_file() {
  local path="$1" expected="$2" deadline=$((SECONDS + 20)) value
  while ((SECONDS < deadline)); do
    value="$(archphene_adb_run shell run-as "$package" cat "$path" 2>/dev/null \
      | tr -d '\r' || true)"
    [[ "$value" == "$expected" ]] && return 0
    sleep .3
  done
  archphene_die "Foot file did not contain expected UTF-8 text: $path"
}
key_chord() {
  archphene_adb_run shell input keyboard keycombination "$@"
}
inject_text() {
  local value="$1" submit="${2:-false}" composing="${3:-}"
  local value_base64 composing_base64
  local -a composing_extra=()
  value_base64="$(printf %s "$value" | base64 -w0)"
  if [[ -n "$composing" ]]; then
    composing_base64="$(printf %s "$composing" | base64 -w0)"
    composing_extra=(--es archphene_test_ime_composing_base64 "$composing_base64")
  fi
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$activity" \
    "${composing_extra[@]}" \
    --es archphene_test_ime_commit_base64 "$value_base64" \
    --ez archphene_test_ime_submit "$submit" >/dev/null
  archphene_wait_log "Injected test IME preeditBytes=[0-9]+.*submit=$submit" 20 \
    'ArchpheneInput:I AndroidRuntime:E *:S' >/dev/null
}
hide_ime() {
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$activity" \
    --ez archphene_test_hide_ime true >/dev/null
  archphene_wait_log 'Test IME hidden with Linux focus retained' 20 \
    'ArchpheneInput:I AndroidRuntime:E *:S' >/dev/null
  sleep .5
}
current_pids() {
  local android linux
  android="$(archphene_android_pid "$package")"
  linux="$(archphene_linux_loader_pid "$android")"
  [[ -n "$android" && -n "$linux" ]] || return 1
  printf '%s %s\n' "$android" "$linux"
}
close_with_back() {
  local attempt deadline log
  archphene_adb_run logcat -c
  for attempt in 1 2 3 4; do
    archphene_adb_run shell input keyevent KEYCODE_BACK
    deadline=$((SECONDS + 5))
    while ((SECONDS < deadline)); do
      log="$(archphene_adb_run logcat -d -v brief -s \
        ArchpheneLinuxApp:I AndroidRuntime:E '*:S' 2>/dev/null || true)"
      if [[ "$log" == *'Linux runtime exited; finishing Android host'* \
          && "$log" == *'Android host destroyed after runtime shutdown'* ]]; then
        archphene_adb_run shell am force-stop "$package"
        return 0
      fi
      sleep .25
    done
  done
  return 1
}

ime_value='Archphene-é-雪-🙂'
ime_command="printf '$ime_value' > $linux_home/$ime_file"
ime_command_base64="$(printf %s "$ime_command" | base64 -w0)"
ime_composing_base64="$(printf %s 'Archphene-preedit-雪' | base64 -w0)"
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --es archphene_test_ime_composing_base64 "$ime_composing_base64" \
  --es archphene_test_ime_commit_base64 "$ime_command_base64" \
  --ez archphene_test_ime_submit true >/dev/null
ime_log="$(archphene_wait_log 'Injected test IME preeditBytes=[1-9][0-9]*.*submit=true' 45 \
  'ArchpheneInput:I ArchpheneLinuxApp:V AndroidRuntime:E *:S')"
[[ "$ime_log" != *'FATAL EXCEPTION'* ]] || archphene_die 'Foot crashed during IME composition'
wait_file "files/linux-home/$ime_file" "$ime_value"

# Start a fresh Activity so the debug clipboard seed can snapshot and restore
# the user's previous clipboard when this session closes normally.
close_with_back || archphene_die 'Foot did not exit after initial IME workflow'
clipboard_value='Archphene-clipboard-é-雪-🙂'
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --es archphene_test_android_clipboard "$clipboard_value" \
  --ez archphene_test_restore_clipboard_on_destroy true >/dev/null
clipboard_session=true
archphene_wait_log 'mapped=true.*primary=true.*title=foot' 45 \
  'ArchpheneInput:V ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null
sleep 1
inject_text 'printf '
key_chord KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V
inject_text " > $linux_home/$clipboard_file" true
wait_file "files/linux-home/$clipboard_file" "$clipboard_value"

# Produce a single-token output row, detect its rendered glyph bounds, select
# it with a real mouse drag, copy through Foot, and paste it into a shell file.
selection_value="ARCHPHENE_SELECTION_${RANDOM}_${RANDOM}"
inject_text "echo '    $selection_value'" true
sleep 1
hide_ime
archphene_adb_run exec-out screencap >"$artifact_dir/selection.raw"
read -r x1 y1 x2 y2 <<<"$(python3 \
  "$ARCHPHENE_SCRIPTS_DIR/lib/foot-selection-geometry.py" \
  "$artifact_dir/selection.raw")"
archphene_adb_run shell input mouse swipe "$x1" "$y1" "$x2" "$y2" 700
sleep .5
archphene_adb_run exec-out screencap >"$artifact_dir/selection-highlight.raw"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/selection.raw" "$artifact_dir/selection-highlight.raw" \
  --minimum-changed-ratio 0.0002 --minimum-difference 0.1 \
  --top-percent 8 --bottom-percent 55
key_chord KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_C
sleep .5
inject_text 'printf '
key_chord KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V
inject_text " > $linux_home/$selection_file" true
wait_file "files/linux-home/$selection_file" "$selection_value"

# Generate real scrollback, move away from the live bottom, and require a
# visibly different Linux frame without process churn.
inject_text 'for i in {1..200}; do echo "$i"; done' true
sleep 2
archphene_adb_run exec-out screencap >"$artifact_dir/scroll-bottom.raw"
key_chord KEYCODE_SHIFT_LEFT KEYCODE_PAGE_UP
sleep 1
archphene_adb_run exec-out screencap >"$artifact_dir/scroll-up.raw"
scroll_method=shift-page-up
if ! python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/scroll-bottom.raw" "$artifact_dir/scroll-up.raw" \
  --minimum-changed-ratio 0.002 --minimum-difference 0.2 \
  --top-percent 8 --bottom-percent 55 >/dev/null 2>&1; then
  scroll_method=mouse-wheel
  for _ in 1 2 3 4; do
    archphene_adb_run shell input mouse scroll 500 500 --axis VSCROLL,5
  done
  sleep 1
  archphene_adb_run exec-out screencap >"$artifact_dir/scroll-up.raw"
fi
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/scroll-bottom.raw" "$artifact_dir/scroll-up.raw" \
  --minimum-changed-ratio 0.002 --minimum-difference 0.2 \
  --top-percent 8 --bottom-percent 55
key_chord KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_MOVE_END
read -r flow_android flow_linux <<<"$(current_pids)"

# Change Android's logical display size live, prove the same Activity and Linux
# loader survive, then restore the user's exact prior override state.
base_size="${override_size:-$physical_size}"
[[ "$base_size" =~ ^([0-9]+)x([0-9]+)$ ]] \
  || archphene_die "could not parse display size: $screen_size"
base_width="${BASH_REMATCH[1]}"
base_height="${BASH_REMATCH[2]}"
resize_width=$((base_width > 900 ? base_width - 120 : base_width + 120))
resize_height=$((base_height > 1500 ? base_height - 200 : base_height + 200))
archphene_adb_run logcat -c
archphene_adb_run shell wm size "${resize_width}x${resize_height}"
size_changed=true
archphene_wait_log "output frame=${resize_width}x[0-9]+" 30 \
  'ArchpheneInput:I ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null
read -r resized_android resized_linux <<<"$(current_pids)"
[[ "$resized_android" == "$flow_android" && "$resized_linux" == "$flow_linux" ]] \
  || archphene_die 'Foot restarted during live display resize'
archphene_adb_run exec-out screencap >"$artifact_dir/resized.raw"
restore_size
sleep 2
read -r restored_android restored_linux <<<"$(current_pids)"
[[ "$restored_android" == "$flow_android" && "$restored_linux" == "$flow_linux" ]] \
  || archphene_die 'Foot restarted while restoring display size'

# Close normally first so the test clipboard is restored, then exercise an
# abrupt force-stop and a clean cold relaunch with new processes.
close_with_back || archphene_die 'Foot did not exit after closing its primary window'
clipboard_session=false

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'mapped=true.*primary=true.*title=foot' 45 \
  'ArchpheneInput:V ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null
read -r before_stop_android before_stop_linux <<<"$(current_pids)"
archphene_adb_run shell am force-stop "$package"
deadline=$((SECONDS + 20))
while ((SECONDS < deadline)) && [[ -n "$(archphene_android_pid "$package" || true)" ]]; do
  sleep .2
done
[[ -z "$(archphene_android_pid "$package" || true)" ]] \
  || archphene_die 'Foot Android process survived force-stop'
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'mapped=true.*primary=true.*title=foot' 45 \
  'ArchpheneInput:V ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null
read -r relaunched_android relaunched_linux <<<"$(current_pids)"
[[ "$relaunched_android" != "$before_stop_android" \
    && "$relaunched_linux" != "$before_stop_linux" ]] \
  || archphene_die 'Foot destructive relaunch reused stale processes'
inject_text "printf ARCHPHENE_LIFECYCLE_OK > $linux_home/$lifecycle_file" true
wait_file "files/linux-home/$lifecycle_file" ARCHPHENE_LIFECYCLE_OK

archphene_note "Foot workflows passed on $serial: UTF-8 composition, bidirectional clipboard/selection, scrollback ($scroll_method), live resize, graceful close, force-stop, and cold relaunch. Evidence: $artifact_dir"
