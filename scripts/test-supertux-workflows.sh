#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p64c477d086133282817fe68df267782c
artifact_dir=
timeout=75
workflow_failures=()
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 30 && timeout <= 180)) \
  || archphene_die '--timeout-seconds must be 30..180'

archphene_test_init "$serial"
command -v tesseract >/dev/null \
  || archphene_die 'SuperTux visual workflow requires the installed tesseract command'
archphene_adb_run shell pm path "$package" >/dev/null \
  || archphene_die "SuperTux wrapper is not installed: $package"
activity="$(archphene_launcher "$package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/supertux-workflows/$safe_serial}"
mkdir -p "$artifact_dir"

state=files/linux-home/.local/share/supertux2
backup="cache/archphene-supertux-workflow-$RANDOM-$RANDOM"
had_state=false
was_running=false
old_accelerometer="$(archphene_adb_run shell settings get system \
  accelerometer_rotation | tr -d '\r')"
old_rotation="$(archphene_adb_run shell settings get system \
  user_rotation | tr -d '\r')"
old_size="$(archphene_adb_run shell wm size | tr -d '\r')"
old_size_override="$(sed -n 's/^Override size: //p' <<<"$old_size")"
archphene_android_pid "$package" >/dev/null 2>&1 && was_running=true

archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell run-as "$package" mkdir -p "$backup"
if archphene_adb_run shell run-as "$package" test -e "$state"; then
  archphene_adb_run shell run-as "$package" cp -a "$state" "$backup/state"
  had_state=true
fi
archphene_adb_run shell run-as "$package" rm -rf "$state"

restore() {
  set +e
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1
  archphene_adb_run shell run-as "$package" rm -rf "$state" >/dev/null 2>&1
  if [[ "$had_state" == true ]]; then
    archphene_adb_run shell run-as "$package" mkdir -p \
      files/linux-home/.local/share >/dev/null 2>&1
    archphene_adb_run shell run-as "$package" cp -a \
      "$backup/state" "$state" >/dev/null 2>&1
  fi
  archphene_adb_run shell run-as "$package" rm -rf "$backup" >/dev/null 2>&1
  archphene_adb_run shell settings put system accelerometer_rotation \
    "$old_accelerometer" >/dev/null 2>&1
  archphene_adb_run shell settings put system user_rotation \
    "$old_rotation" >/dev/null 2>&1
  if [[ -n "$old_size_override" ]]; then
    archphene_adb_run shell wm size "$old_size_override" >/dev/null 2>&1
  else
    archphene_adb_run shell wm size reset >/dev/null 2>&1
  fi
  if [[ "$was_running" == true ]]; then
    archphene_adb_run shell am start -n "$activity" >/dev/null 2>&1
  fi
}
trap restore EXIT

current_pids() {
  local android linux
  android="$(archphene_android_pid "$package")"
  linux="$(archphene_linux_loader_pid "$android")"
  [[ -n "$android" && -n "$linux" ]] || return 1
  printf '%s %s\n' "$android" "$linux"
}
assert_pids() {
  local expected_android="$1" expected_linux="$2" label="$3"
  local actual_android actual_linux
  read -r actual_android actual_linux <<<"$(current_pids)"
  [[ "$actual_android" == "$expected_android" && "$actual_linux" == "$expected_linux" ]] \
    || archphene_die "SuperTux restarted during $label: Android $expected_android/$actual_android, Linux $expected_linux/$actual_linux"
}
capture() {
  local name="$1"
  archphene_adb_run exec-out screencap >"$artifact_dir/$name.raw"
  archphene_adb_run exec-out screencap -p >"$artifact_dir/$name.png"
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
    "$artifact_dir/$name.raw" >/dev/null
}
assert_first_run_dialogs_cleared() {
  local name="$1" require_menu="${2:-false}" text
  text="$(tesseract "$artifact_dir/$name.png" stdout 2>/dev/null \
    | tr '[:upper:]' '[:lower:]')"
  printf '%s\n' "$text" >"$artifact_dir/$name-ocr.txt"
  ! archphene_regex_contains "$text" 'ternet|onnec|ondec|ddition' \
    || archphene_die "$name still shows a SuperTux first-run prompt"
  if [[ "$require_menu" == true ]]; then
    archphene_regex_contains "$text" \
      'start|stare|game|gane|gana|credits|redits|quit|qit|vels|vls|fewels|tevele|editor|sedire|coderib' \
      || archphene_die "$name does not show the SuperTux main menu"
  fi
}
ime_shown() {
  local state
  state="$(archphene_adb_run shell dumpsys input_method 2>/dev/null || true)"
  archphene_regex_contains "$state" \
    '(mInputShown|isInputViewShown|inputShown|showRequested)=true|mImeWindowVis=0x[23]'
}
wait_foreground() {
  local deadline=$((SECONDS + 20)) activities
  while ((SECONDS < deadline)); do
    activities="$(archphene_adb_run shell dumpsys activity activities \
      2>/dev/null || true)"
    if archphene_regex_contains "$activities" \
        "topResumedActivity=.*${package//./\\.}/"; then
      return 0
    fi
    sleep .3
  done
  archphene_die 'SuperTux did not return to the foreground'
}
wait_orientation() {
  local expected="$1" deadline=$((SECONDS + 15)) probe width height
  probe="$artifact_dir/.orientation-probe.png"
  while ((SECONDS < deadline)); do
    archphene_adb_run exec-out screencap -p >"$probe"
    read -r width height < <(python3 -c \
      'import struct,sys; data=open(sys.argv[1],"rb").read(24); print(*struct.unpack(">II",data[16:24]))' \
      "$probe")
    if [[ "$expected" == portrait ]] && ((height > width)); then return 0; fi
    if [[ "$expected" == landscape ]] && ((width > height)); then return 0; fi
    sleep .2
  done
  archphene_die "display did not settle in $expected orientation"
}
full_frame_dimensions() {
  python3 -c \
    'import struct,sys; data=open(sys.argv[1],"rb").read(24); print(*struct.unpack(">II",data[16:24]))' \
    "$1"
}
restart_with_clean_profile() {
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run shell run-as "$package" rm -rf "$state"
  archphene_adb_run shell wm user-rotation lock 0
  wait_orientation portrait
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$activity" \
    --ez archphene_test_input_debug true >/dev/null
  archphene_wait_log 'mapped=true.*primary=true.*SuperTux' "$timeout" \
    'ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E *:S' >/dev/null
  wait_orientation landscape
  sleep 1
  ! ime_shown \
    || archphene_die 'legacy SDL startup text input opened Android IME over SuperTux'
}

archphene_adb_run shell input keyevent WAKEUP
archphene_adb_run shell wm dismiss-keyguard >/dev/null 2>&1 || true
archphene_adb_run shell wm user-rotation lock 0
wait_orientation portrait
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --ez archphene_test_input_debug true >/dev/null
startup_log="$(archphene_wait_log 'mapped=true.*primary=true.*SuperTux' "$timeout" \
  'ArchpheneInput:V ArchpheneLinuxApp:I ArchpheneAudio:I AndroidRuntime:E *:S')"
wait_orientation landscape
archphene_regex_contains "$startup_log" \
  'Automatic SDL orientation policy=landscape-sensor' \
  || archphene_die 'SuperTux did not apply the generic SDL orientation policy'
archphene_regex_contains "$startup_log" \
  'Graphics renderer=virpipe Android EGL/GLES bridge' \
  || archphene_die 'SuperTux did not use the accelerated Android renderer'
audio_log="$(archphene_wait_log 'Private AAudio PulseAudio server ready' "$timeout" \
  'ArchpheneAudio:I AndroidRuntime:E *:S')"
archphene_regex_contains "$audio_log" 'Private AAudio PulseAudio server ready' \
  || archphene_die 'SuperTux audio bridge did not become ready'
playback_log="$(archphene_wait_log 'application.name = "supertux2"' "$timeout" \
  'ArchpheneAudio:I AndroidRuntime:E *:S')"
printf '%s\n%s\n%s\n' "$startup_log" "$audio_log" "$playback_log" \
  >"$artifact_dir/startup-logcat.txt"
read -r android_pid linux_pid <<<"$(current_pids)"
sleep 1
! ime_shown \
  || archphene_die 'legacy SDL startup text input opened Android IME over SuperTux'

capture landscape-a
sleep 4
capture landscape-b
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/landscape-a.raw" "$artifact_dir/landscape-b.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
assert_pids "$android_pid" "$linux_pid" 'sustained landscape rendering'

# Simulate a physical keyboard source with keys that emit a normal down/up
# pair through both ADB implementations. Require compositor delivery and the
# first-run dialogs to disappear, not merely an animated-frame difference.
capture keyboard-before
archphene_adb_run logcat -c
archphene_adb_run shell input keyboard keyevent --longpress KEYCODE_DPAD_CENTER
sleep .5
archphene_adb_run shell input keyboard keyevent KEYCODE_ESCAPE
sleep 2
keyboard_log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneInput:V AndroidRuntime:E '*:S')"
printf '%s\n' "$keyboard_log" >"$artifact_dir/keyboard-logcat.txt"
if ! archphene_regex_contains "$keyboard_log" \
    'keyboard key=28 pressed=true result=1' \
    || ! archphene_regex_contains "$keyboard_log" \
    'keyboard key=1 pressed=true result=1'; then
  workflow_failures+=('Android Enter/Escape did not reach the Wayland keyboard')
fi
capture keyboard-after
keyboard_text="$(tesseract "$artifact_dir/keyboard-after.png" stdout 2>/dev/null \
  | tr '[:upper:]' '[:lower:]')"
printf '%s\n' "$keyboard_text" >"$artifact_dir/keyboard-after-ocr.txt"
if ((${#workflow_failures[@]} == 0)); then
  ! archphene_regex_contains "$keyboard_text" 'ternet|onnec|ondec|ddition' \
    || archphene_die 'keyboard-after still shows a SuperTux first-run prompt'
fi
assert_pids "$android_pid" "$linux_pid" 'keyboard navigation'

# Restart from an independently clean profile so the finger path must also
# commit the first-run choice instead of inheriting the keyboard result.
restart_with_clean_profile
read -r android_pid linux_pid <<<"$(current_pids)"

# A direct-Wayland desktop app gets mouse semantics for an unmoved finger tap.
# Derive the established two-column prompt targets from the full-device frame,
# including Android chrome. Do not use wm size or an app-only crop: those can
# retain natural-orientation dimensions while Android displays the SDL host in
# landscape.
capture touch-before
touch_before_text="$(tesseract "$artifact_dir/touch-before.png" stdout 2>/dev/null \
  | tr '[:upper:]' '[:lower:]')"
archphene_regex_contains "$touch_before_text" 'ternet|onnec|ondec|ddition' \
  || archphene_die 'full-device touch frame does not show the first-run prompt'
read -r touch_width touch_height \
  <<<"$(full_frame_dimensions "$artifact_dir/touch-before.png")"
tap_x=$((touch_width * 35 / 100))
tap_y=$((touch_height * 62 / 100))
archphene_adb_run logcat -c
archphene_adb_run shell input tap "$tap_x" "$tap_y"
sleep 1
capture touch-between
read -r touch_width touch_height \
  <<<"$(full_frame_dimensions "$artifact_dir/touch-between.png")"
tap_no_x=$((touch_width * 65 / 100))
tap_no_y=$((touch_height * 62 / 100))
archphene_adb_run shell input tap "$tap_no_x" "$tap_no_y"
sleep 2
touch_log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneInput:V AndroidRuntime:E '*:S')"
printf '%s\n' "$touch_log" >"$artifact_dir/touch-logcat.txt"
pointer_presses="$(grep -c 'pointer button pressed=true result=1' \
  "$artifact_dir/touch-logcat.txt" || true)"
((pointer_presses >= 2)) \
  || archphene_die 'SuperTux did not accept both finger-to-pointer clicks'
capture touch-after
assert_first_run_dialogs_cleared touch-after true
assert_pids "$android_pid" "$linux_pid" 'finger activation'

# HOME/resume is the phone/tablet pause path. Both managed processes must
# survive, then the existing Wayland surface must resume presentation.
archphene_adb_run shell input keyevent KEYCODE_HOME
sleep 2
assert_pids "$android_pid" "$linux_pid" 'HOME pause'
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
wait_foreground
sleep 1
capture resumed
assert_pids "$android_pid" "$linux_pid" 'foreground resume'

# The same client must resize into a tablet presentation without restarting
# either the Android host or glibc loader.
archphene_adb_run logcat -c
archphene_adb_run shell wm size 1920x1200
archphene_wait_log 'output frame=[1-9][0-9]{3,}x[1-9][0-9]{2,3}' 30 \
  'ArchpheneInput:I AndroidRuntime:E *:S' >/dev/null
sleep 2
capture tablet-resized
assert_pids "$android_pid" "$linux_pid" 'tablet resize'

log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneInput:V ArchpheneLinuxApp:I ArchpheneAudio:I \
  ArchpheneGpu:I AndroidRuntime:E '*:S')"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
! archphene_regex_contains "$log" \
  'FATAL EXCEPTION|Runtime GUI exit=(?!0)|protocol error|GPU helper exited unexpectedly|helper-loss fallback' \
  || archphene_die 'SuperTux workflow produced a runtime/compositor failure'

python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" --field 'app=SuperTux' \
  --field 'toolkit=wayland-sdl' --field "androidPid=$android_pid" \
  --field "linuxPid=$linux_pid" \
  --field 'state=automatic orientation audio input pause resume tablet resize' \
  --artifact "$artifact_dir/landscape-a.raw" \
  --artifact "$artifact_dir/landscape-a.png" \
  --artifact "$artifact_dir/landscape-b.raw" \
  --artifact "$artifact_dir/landscape-b.png" \
  --artifact "$artifact_dir/keyboard-before.raw" \
  --artifact "$artifact_dir/keyboard-before.png" \
  --artifact "$artifact_dir/keyboard-after.raw" \
  --artifact "$artifact_dir/keyboard-after.png" \
  --artifact "$artifact_dir/keyboard-after-ocr.txt" \
  --artifact "$artifact_dir/keyboard-logcat.txt" \
  --artifact "$artifact_dir/touch-before.raw" \
  --artifact "$artifact_dir/touch-before.png" \
  --artifact "$artifact_dir/touch-between.raw" \
  --artifact "$artifact_dir/touch-between.png" \
  --artifact "$artifact_dir/touch-after.raw" \
  --artifact "$artifact_dir/touch-after.png" \
  --artifact "$artifact_dir/touch-after-ocr.txt" \
  --artifact "$artifact_dir/touch-logcat.txt" \
  --artifact "$artifact_dir/resumed.raw" \
  --artifact "$artifact_dir/resumed.png" \
  --artifact "$artifact_dir/tablet-resized.raw" \
  --artifact "$artifact_dir/tablet-resized.png" \
  --artifact "$artifact_dir/startup-logcat.txt" \
  --artifact "$artifact_dir/logcat.txt"

restore
trap - EXIT
if ((${#workflow_failures[@]} > 0)); then
  archphene_die "${workflow_failures[*]}"
fi
archphene_note "SuperTux workflows passed on $serial: automatic SDL landscape, animated EGL rendering, PulseAudio bridge, full-device OCR finger input, HOME/resume, tablet resize, stable process pair, and exact prior state restored. Evidence: $artifact_dir"
