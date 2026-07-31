#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archphene.app.debug
package=org.archphene.linux.pfc8108094f20ad738c17116b6786348b
artifact_dir=
timeout=75
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --manager) manager="${2:?}"; shift 2 ;;
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
archphene_adb_run shell pm path "$manager" >/dev/null \
  || archphene_die "Archphene manager is not installed: $manager"
archphene_adb_run shell run-as "$manager" id >/dev/null \
  || archphene_die "SuperTux workflow requires a debuggable manager"
activity="$(archphene_launcher "$package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/supertux-workflows/$safe_serial}"
mkdir -p "$artifact_dir"
scoped_log="$artifact_dir/scoped-logcat.txt"
: >"$scoped_log"
collect_scoped_logcat() {
  archphene_adb_run logcat -d -v threadtime \
    -s ArchpheneLauncher:V ArchpheneLauncherSession:V ArchpheneAudio:I \
    ArchpheneGpu:I ArchpheneRuntime:I ArchpheneLinuxApp:I AndroidRuntime:E '*:S' \
    >>"$scoped_log"
}
clear_scoped_logcat() {
  collect_scoped_logcat
  archphene_adb_run logcat -c
}

shared_root_loader_pid() {
  local android_pid="$1" processes
  processes="$(archphene_adb_run shell ps -A -o PID,PPID,NAME,ARGS)"
  awk -v root="$android_pid" -v package="$manager" '
    {
      parent[$1] = $2
      name[$1] = $3
      command[$1] = $0
    }
    END {
      user_root = "/data/user/0/" package "/files/arch-root/"
      data_root = "/data/data/" package "/files/arch-root/"
      for (candidate in name) {
        if (name[candidate] != "loader" &&
            name[candidate] != "libarchphene_ld.so" &&
            name[candidate] !~ /^libarchphene_pkg_[0-9a-f]+\.so$/) continue
        if (index(command[candidate], user_root) == 0 &&
            index(command[candidate], data_root) == 0) continue
        current = candidate
        for (depth = 0; depth < 64 && current in parent; depth++) {
          if (parent[current] == root) {
            print candidate
            exit
          }
          current = parent[current]
        }
      }
      exit 1
    }
  ' <<<"$processes"
}

state=files/arch-root/home/archphene/.local/share/supertux2
backup="no_backup/archphene-supertux-workflow-$RANDOM-$RANDOM"
had_state=false
old_accelerometer="$(archphene_adb_run shell settings get system \
  accelerometer_rotation | tr -d '\r')"
old_rotation="$(archphene_adb_run shell settings get system \
  user_rotation | tr -d '\r')"
old_size="$(archphene_adb_run shell wm size | tr -d '\r')"
old_size_override="$(sed -n 's/^Override size: //p' <<<"$old_size")"
if archphene_android_pid "$package" >/dev/null 2>&1; then
  archphene_die 'refusing to replace an active SuperTux session'
fi
existing_manager_pid="$(archphene_android_pid "$manager" 2>/dev/null || true)"
manager_was_running=false
if [[ -n "$existing_manager_pid" ]]; then
  manager_was_running=true
fi
if [[ -n "$existing_manager_pid" ]] &&
    shared_root_loader_pid "$existing_manager_pid" >/dev/null 2>&1; then
  archphene_die 'refusing to run while another managed Linux application is active'
fi
original_focus="$(
  archphene_adb_run shell dumpsys window |
    sed -n 's/.*mCurrentFocus=.* u0 \([^} ]*\/[^} ]*\)}.*/\1/p' |
    head -1 |
    tr -d '\r'
)"

if archphene_adb_run shell run-as "$manager" test -e "$backup"; then
  archphene_die "refusing to reuse existing SuperTux backup path: $backup"
fi
archphene_adb_run shell run-as "$manager" mkdir -p no_backup
archphene_adb_run shell run-as "$manager" mkdir "$backup"
if archphene_adb_run shell run-as "$manager" test -e "$state"; then
  archphene_adb_run shell run-as "$manager" cp -a "$state" "$backup/state"
  had_state=true
fi
archphene_adb_run shell sync

restore() {
  local status=$?
  local restore_failed=false
  local linux_stopped=false state_cleared=false manager_pid deadline
  set +e
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 ||
    restore_failed=true
  deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)); do
    manager_pid="$(archphene_android_pid "$manager" 2>/dev/null || true)"
    if ! archphene_android_pid "$package" >/dev/null 2>&1 &&
        { [[ -z "$manager_pid" ]] ||
          ! shared_root_loader_pid "$manager_pid" >/dev/null 2>&1; }; then
      break
    fi
    sleep .2
  done
  manager_pid="$(archphene_android_pid "$manager" 2>/dev/null || true)"
  if archphene_android_pid "$package" >/dev/null 2>&1 ||
      { [[ -n "$manager_pid" ]] &&
        shared_root_loader_pid "$manager_pid" >/dev/null 2>&1; }; then
    restore_failed=true
  else
    linux_stopped=true
  fi
  if [[ "$linux_stopped" == true ]]; then
    if archphene_adb_run shell run-as "$manager" rm -rf "$state" >/dev/null 2>&1; then
      state_cleared=true
      archphene_adb_run shell sync >/dev/null 2>&1 || restore_failed=true
    else
      restore_failed=true
    fi
    if [[ "$had_state" == true && "$state_cleared" == true ]]; then
      archphene_adb_run shell run-as "$manager" mkdir -p \
        files/arch-root/home/archphene/.local/share >/dev/null 2>&1 ||
        restore_failed=true
      archphene_adb_run shell run-as "$manager" cp -a \
        "$backup/state" "$state" >/dev/null 2>&1 || restore_failed=true
      archphene_adb_run shell sync >/dev/null 2>&1 || restore_failed=true
    fi
    if [[ "$manager_was_running" == false ]]; then
      archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 ||
        restore_failed=true
    fi
  fi
  archphene_adb_run shell settings put system accelerometer_rotation \
    "$old_accelerometer" >/dev/null 2>&1 || restore_failed=true
  archphene_adb_run shell settings put system user_rotation \
    "$old_rotation" >/dev/null 2>&1 || restore_failed=true
  if [[ -n "$old_size_override" ]]; then
    archphene_adb_run shell wm size "$old_size_override" >/dev/null 2>&1 ||
      restore_failed=true
  else
    archphene_adb_run shell wm size reset >/dev/null 2>&1 ||
      restore_failed=true
  fi
  if [[ -n "$original_focus" ]]; then
    archphene_adb_run shell am start -n "$original_focus" >/dev/null 2>&1 ||
      restore_failed=true
  fi
  if [[ "$restore_failed" == true ]]; then
    echo "error: could not restore SuperTux workflow state; backup retained at $backup" >&2
    return 1
  fi
  archphene_adb_run shell run-as "$manager" rm -rf "$backup" >/dev/null 2>&1 || {
    echo "error: could not remove restored SuperTux backup at $backup" >&2
    return 1
  }
  return "$status"
}
trap restore EXIT
archphene_adb_run shell run-as "$manager" rm -rf "$state"
archphene_adb_run shell sync

current_pids() {
  local wrapper_android manager_android linux
  wrapper_android="$(archphene_android_pid "$package")"
  manager_android="$(archphene_android_pid "$manager")"
  linux="$(shared_root_loader_pid "$manager_android")"
  [[ -n "$wrapper_android" && -n "$manager_android" && -n "$linux" ]] || return 1
  printf '%s %s %s\n' "$wrapper_android" "$manager_android" "$linux"
}
assert_pids() {
  local expected_wrapper="$1" expected_manager="$2" expected_linux="$3" label="$4"
  local actual_wrapper actual_manager actual_linux
  read -r actual_wrapper actual_manager actual_linux <<<"$(current_pids)"
  [[ "$actual_wrapper" == "$expected_wrapper" &&
     "$actual_manager" == "$expected_manager" &&
     "$actual_linux" == "$expected_linux" ]] ||
    archphene_die "SuperTux restarted during $label: wrapper $expected_wrapper/$actual_wrapper, manager $expected_manager/$actual_manager, Linux $expected_linux/$actual_linux"
}
capture() {
  local name="$1" require_health="${2:-true}"
  archphene_adb_run exec-out screencap >"$artifact_dir/$name.raw"
  if [[ "$require_health" == true ]]; then
    if ! python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
        "$artifact_dir/$name.raw" >/dev/null 2>&1; then
      sleep .3
      archphene_adb_run exec-out screencap >"$artifact_dir/$name.raw"
      python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
        "$artifact_dir/$name.raw" >/dev/null
    fi
  fi
  archphene_adb_run exec-out screencap -p >"$artifact_dir/$name.png"
}
screen_text() {
  local name="$1" text crop
  crop="$artifact_dir/.$name-ocr-crop.png"
  python3 - "$artifact_dir/$name.png" "$crop" <<'PY'
from PIL import Image
import sys

image = Image.open(sys.argv[1])
width, height = image.size
image.crop((
    int(width * .36),
    int(height * .28),
    int(width * .66),
    int(height * .82),
)).save(sys.argv[2])
PY
  text="$({
    tesseract "$artifact_dir/$name.png" stdout --psm 11 2>/dev/null
    tesseract "$artifact_dir/$name.png" stdout --psm 6 2>/dev/null
    tesseract "$crop" stdout --psm 6 2>/dev/null
  } | tr '[:upper:]' '[:lower:]')"
  printf '%s\n' "$text" >"$artifact_dir/$name-ocr.txt"
  printf '%s\n' "$text"
}
assert_main_menu() {
  local name="$1" text
  text="$(screen_text "$name")"
  ! archphene_regex_contains "$text" \
    'ternet|onnec|ondec|ddition|new release|startup' \
    || archphene_die "$name still shows a SuperTux first-run prompt"
  archphene_regex_contains "$text" \
    'start|stare|game|gane|contrib|contr|levels|cevel|options|qpti|credits|gred|quit|qul' \
    || archphene_die "$name does not show the SuperTux main menu"
}
assert_options_menu() {
  local name="$1" text
  text="$(screen_text "$name")"
  if archphene_regex_contains "$text" 'options|op[eai].{0,8}s|or[eai].{0,4}ens'; then
    return 0
  fi
  archphene_regex_contains "$text" \
      'locale|loern|losoi|video|vide|videos|wvidea|audio|au.{0,2}io|avio|avdi|controls|codi|codt|codil' \
    && archphene_regex_contains "$text" \
      'extras|extio|exitto|xis|heto|advanced|adv|adyv|back|baen' \
    || archphene_die "$name does not show the SuperTux Options menu"
}
clear_first_run_dialogs() {
  local attempt text probe="$artifact_dir/.first-run-probe.png"
  for attempt in 1 2 3; do
    archphene_adb_run exec-out screencap -p >"$probe"
    text="$(
      {
        tesseract "$probe" stdout --psm 11 2>/dev/null
        tesseract "$probe" stdout --psm 6 2>/dev/null
      } |
        tr '[:upper:]' '[:lower:]'
    )"
    if archphene_regex_contains "$text" \
        'start|stare|game|gane|contrib|contr|levels|cevel|options|qpti|credits|gred|quit|qul'; then
      return 0
    fi
    archphene_adb_run shell input keyboard keyevent --longpress KEYCODE_ESCAPE
    sleep 1
  done
  archphene_adb_run exec-out screencap -p >"$probe"
  text="$(
    {
      tesseract "$probe" stdout --psm 11 2>/dev/null
      tesseract "$probe" stdout --psm 6 2>/dev/null
    } |
      tr '[:upper:]' '[:lower:]'
  )"
  archphene_regex_contains "$text" \
    'start|stare|game|gane|contrib|contr|levels|cevel|options|qpti|credits|gred|quit|qul'
}
ime_shown() {
  local state
  state="$(archphene_adb_run shell dumpsys input_method 2>/dev/null || true)"
  archphene_regex_contains "$state" \
    '(mInputShown|isInputViewShown|inputShown|showRequested)=true|mImeWindowVis=(0x)?[23]'
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
finger_tap() {
  local x="$1" y="$2"
  # Keep the movement below Android's touch-slop while ensuring the injected
  # gesture contains a real MOVE between pointer focus and button activation.
  archphene_adb_run shell input touchscreen swipe "$x" "$y" "$((x + 1))" "$y" 150
}
held_key() {
  local key="$1" duration="${2:-400}"
  archphene_adb_run shell input keyboard keyevent --async --duration "$duration" "$key"
  sleep "$(python3 -c 'import sys; print((int(sys.argv[1]) + 300) / 1000)' "$duration")"
}
assert_menu_selection() {
  local image="$1" selected_y="$2" other_y="$3" output="$4" threshold="${5:-15}"
  python3 - "$image" "$selected_y" "$other_y" "$threshold" >"$output" <<'PY'
from PIL import Image, ImageStat
import sys

image = Image.open(sys.argv[1]).convert("L")
width, height = image.size
threshold = float(sys.argv[4])

def row_luma(center):
    y = float(center)
    strips = (
        (0.435, 0.455),
        (0.590, 0.610),
    )
    return sum(
        ImageStat.Stat(image.crop((
            int(width * left),
            int(height * (y - 0.012)),
            int(width * right),
            int(height * (y + 0.012)),
        ))).mean[0]
        for left, right in strips
    ) / len(strips)

selected = row_luma(sys.argv[2])
other = row_luma(sys.argv[3])
print(f"selected_luma={selected:.2f} other_luma={other:.2f}")
if selected < other + threshold:
    raise SystemExit("expected SuperTux menu row is not visibly selected")
PY
}
archphene_adb_run shell input keyevent WAKEUP
archphene_adb_run shell wm dismiss-keyguard >/dev/null 2>&1 || true
archphene_adb_run shell wm user-rotation lock 0
wait_orientation portrait
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --ez archphene_test_input_debug true >/dev/null
startup_log="$(archphene_wait_log \
  'Presented Linux frame session=.*attachmentFrame=1' "$timeout" \
  'ArchpheneLauncherSession:I ArchpheneLauncher:I ArchpheneGpu:I ArchpheneAudio:I AndroidRuntime:E *:S')"
wait_orientation landscape
archphene_regex_contains "$startup_log" \
  'Applied orientation policy=1' \
  || archphene_die 'SuperTux did not apply the generic SDL orientation policy'
archphene_regex_contains "$startup_log" \
  'GPU bridge ready session=' \
  || archphene_die 'SuperTux did not use the accelerated Android renderer'
audio_log="$(archphene_wait_log 'Private AAudio server ready' "$timeout" \
  'ArchpheneAudio:I AndroidRuntime:E *:S')"
archphene_regex_contains "$audio_log" 'Private AAudio server ready' \
  || archphene_die 'SuperTux audio bridge did not become ready'
playback_log="$(archphene_wait_log \
  'Created input [0-9]+ ".*[Ss]uper[Tt]ux.*" on archphene_output|application.name = "supertux2"' \
  "$timeout" 'ArchpheneAudio:I AndroidRuntime:E *:S')"
focus_log="$(archphene_wait_log 'Android audio focus request.*result=granted' "$timeout" \
  'ArchpheneAudio:I AndroidRuntime:E *:S')"
archphene_regex_contains "$focus_log" \
  'Android audio focus request.*result=granted' \
  || archphene_die 'SuperTux playback did not receive Android audio focus'
printf '%s\n%s\n%s\n%s\n' "$startup_log" "$audio_log" "$playback_log" "$focus_log" \
  | tee "$artifact_dir/startup-logcat.txt" >>"$scoped_log"
printf '%s\n%s\n' "$playback_log" "$focus_log" \
  >"$artifact_dir/audio-playback-logcat.txt"
read -r wrapper_pid manager_pid linux_pid <<<"$(current_pids)"
sleep 1
! ime_shown \
  || archphene_die 'legacy SDL startup text input opened Android IME over SuperTux'

capture landscape-a
sleep 4
capture landscape-b
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/landscape-a.raw" "$artifact_dir/landscape-b.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" \
  'sustained landscape rendering'

# SuperTux's upstream first-run consent dialogs are keyboard/controller
# modals. Clear both with a physical-keyboard source before testing ordinary
# menu touch, and require the actual main menu rather than an animated-frame
# difference.
capture keyboard-before
clear_scoped_logcat
clear_first_run_dialogs \
  || archphene_die 'keyboard could not clear SuperTux first-run dialogs'
keyboard_log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneLauncher:V ArchpheneLauncherSession:V AndroidRuntime:E '*:S')"
printf '%s\n' "$keyboard_log" \
  | tee "$artifact_dir/keyboard-logcat.txt" >>"$scoped_log"
capture keyboard-after
assert_main_menu keyboard-after
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" 'keyboard navigation'
sleep 3

read -r touch_width touch_height \
  <<<"$(full_frame_dimensions "$artifact_dir/keyboard-after.png")"
# Hold each synthetic key across several game frames. A short press and release
# can otherwise enter SuperTux's SDL queue within one controller polling tick.
# Vary the bounded hold across retries because Android's first repeat boundary
# can coincide with a game tick on a loaded physical device.
keyboard_selected=false
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
  held_key KEYCODE_DPAD_DOWN 300
  capture keyboard-selected
  if assert_menu_selection "$artifact_dir/keyboard-selected.png" .46 .418 \
      "$artifact_dir/keyboard-selected-visual.txt" 2>/dev/null; then
    keyboard_selected=true
    break
  fi
done
[[ "$keyboard_selected" == true ]] \
  || archphene_die 'keyboard could not select the SuperTux Contrib Levels row'
held_key KEYCODE_ENTER
sleep 2
capture keyboard-activated
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/keyboard-after.raw" "$artifact_dir/keyboard-activated.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
keyboard_activated_text="$(screen_text keyboard-activated)"
if archphene_regex_contains "$keyboard_activated_text" 'start game' &&
    archphene_regex_contains "$keyboard_activated_text" 'options' &&
    archphene_regex_contains "$keyboard_activated_text" 'credits'; then
  archphene_die 'keyboard activation remained on the SuperTux main menu'
fi
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" 'keyboard activation'
archphene_adb_run shell input keyboard keyevent --longpress KEYCODE_ESCAPE
sleep 2
capture activation-returned
assert_main_menu activation-returned

# Exercise a non-default menu target with keyboard selection and activation.
# This avoids encoding SuperTux's SDL2 mouse-button union bug as bridge behavior.
options_selected=false
for _ in 1 2 3 4 5 6 7 8 9 10; do
  held_key KEYCODE_DPAD_DOWN 300
  capture options-selected
  if assert_menu_selection "$artifact_dir/options-selected.png" .555 .510 \
      "$artifact_dir/options-selected-above-visual.txt" 8 2>/dev/null &&
      assert_menu_selection "$artifact_dir/options-selected.png" .555 .600 \
        "$artifact_dir/options-selected-below-visual.txt" 8 2>/dev/null; then
    options_selected=true
    break
  fi
done
[[ "$options_selected" == true ]] \
  || archphene_die 'keyboard could not select the SuperTux Options row'
held_key KEYCODE_ENTER
sleep 2
capture options-menu
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/activation-returned.raw" "$artifact_dir/options-menu.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
assert_options_menu options-menu
for _ in 1 2; do
  held_key KEYCODE_DPAD_RIGHT
done
held_key KEYCODE_ENTER
sleep 2
capture audio-options
archphene_adb_run shell input keyboard keyevent --longpress KEYCODE_ESCAPE
sleep 2
archphene_adb_run shell input keyboard keyevent --longpress KEYCODE_ESCAPE
sleep 2
capture options-returned
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/options-menu.raw" "$artifact_dir/options-returned.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
assert_main_menu options-returned
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" \
  'Options menu transition'

# Prove the full-device finger route independently, then activate the selected
# packaged world with the keyboard because SuperTux 0.7.0 reads the wrong SDL
# union member for mouse-button coordinates.
read -r touch_width touch_height \
  <<<"$(full_frame_dimensions "$artifact_dir/options-returned.png")"
contrib_selected=false
for _ in 1 2 3 4 5 6 7 8 9 10; do
  held_key KEYCODE_DPAD_UP 300
  capture contrib-selected
  if assert_menu_selection "$artifact_dir/contrib-selected.png" .464 .418 \
      "$artifact_dir/contrib-selected-above-visual.txt" 8 2>/dev/null &&
      assert_menu_selection "$artifact_dir/contrib-selected.png" .464 .510 \
        "$artifact_dir/contrib-selected-below-visual.txt" 8 2>/dev/null; then
    contrib_selected=true
    break
  fi
done
[[ "$contrib_selected" == true ]] \
  || archphene_die 'keyboard could not return to the SuperTux Contrib Levels row'
held_key KEYCODE_ENTER
sleep 2
capture game-selection false
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/options-returned.raw" "$artifact_dir/game-selection.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" \
  'Contrib Levels activation'
capture touch-before false
clear_scoped_logcat
finger_tap "$((touch_width / 2))" \
  "$((touch_height * 47 / 100))"
sleep 3
touch_log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneLauncher:V ArchpheneLauncherSession:V AndroidRuntime:E '*:S')"
printf '%s\n' "$touch_log" \
  | tee "$artifact_dir/touch-logcat.txt" >>"$scoped_log"
archphene_regex_contains "$touch_log" \
  'Delivered new bounded input kinds=0x40 .*records=[1-9][0-9]* result=[1-9][0-9]*' \
  || archphene_die 'finger gesture did not establish Wayland pointer focus'
archphene_regex_contains "$touch_log" \
  'Delivered new bounded input kinds=0x100 .*buttonStates=0x3 records=[3-9][0-9]* result=[3-9][0-9]*' \
  || archphene_die 'finger gesture did not atomically deliver primary press and release'
capture touch-returned false
screen_text touch-returned >/dev/null
! ime_shown || archphene_die 'SDL touch unexpectedly opened Android IME'
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" 'finger pointer routing'
held_key KEYCODE_ENTER
sleep 3
capture game-active
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/game-selection.raw" "$artifact_dir/game-active.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" \
  'contributed world activation'
game_active_text="$(screen_text game-active)"
archphene_regex_contains "$game_active_text" \
  'official|offici.{0,2}l|bonus|island|revenge|redmond|onerts.{0,8}lavals|onus.{0,8}is.{0,3}nd|reven.{0,8}red.{0,8}ond' \
  || archphene_die 'keyboard activation did not open the official Contrib world list'
held_key KEYCODE_DPAD_DOWN
sleep 2
capture contrib-world-selected
held_key KEYCODE_ENTER
sleep 60
capture level-entry false
clear_scoped_logcat
archphene_adb_run shell input keyboard keyevent --longpress KEYCODE_DPAD_DOWN
sleep 2
capture map-selected
held_key KEYCODE_ENTER
sleep 8
capture level-intro false
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/map-selected.raw" "$artifact_dir/level-intro.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
held_key KEYCODE_ENTER
sleep 5
level_input_log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneLauncher:V ArchpheneLauncherSession:V AndroidRuntime:E '*:S')"
printf '%s\n' "$level_input_log" \
  | tee "$artifact_dir/level-input-logcat.txt" >>"$scoped_log"
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" 'level activation'
capture level-active
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/level-intro.raw" "$artifact_dir/level-active.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
sleep 2
capture level-idle
clear_scoped_logcat
held_key KEYCODE_DPAD_RIGHT 1000
held_key KEYCODE_SPACE
sleep 2
capture level-controlled
! ime_shown || archphene_die 'gameplay keyboard input unexpectedly opened Android IME'
gameplay_log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneLauncher:V ArchpheneLauncherSession:V AndroidRuntime:E '*:S')"
printf '%s\n' "$gameplay_log" \
  | tee "$artifact_dir/gameplay-input-logcat.txt" >>"$scoped_log"
archphene_regex_contains "$gameplay_log" \
  'Submitted input key code=22 action=1 accepted=true' \
  || archphene_die 'right-movement key was not accepted by the manager'
archphene_regex_contains "$gameplay_log" \
  'Submitted input key code=62 action=1 accepted=true' \
  || archphene_die 'jump key was not accepted by the manager'
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" more-different \
  "$artifact_dir/level-active.raw" "$artifact_dir/level-idle.raw" \
  "$artifact_dir/level-controlled.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01 >/dev/null
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" \
  'level movement and jump'

# HOME/resume is the phone/tablet pause path. Both managed processes must
# survive, then the existing Wayland surface must resume presentation.
archphene_adb_run shell input keyevent KEYCODE_HOME
background_focus_log="$(archphene_wait_log \
  'Android audio focus abandon' 20 'ArchpheneAudio:I AndroidRuntime:E *:S')"
printf '%s\n' "$background_focus_log" \
  | tee "$artifact_dir/background-audio-focus-logcat.txt" >>"$scoped_log"
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" 'HOME pause'
clear_scoped_logcat
archphene_adb_run shell am start -W -n "$activity" >/dev/null
wait_foreground
resumed_focus_log="$(archphene_wait_log \
  'Android audio focus request.*result=granted' 20 \
  'ArchpheneAudio:I AndroidRuntime:E *:S')"
printf '%s\n' "$resumed_focus_log" \
  | tee "$artifact_dir/resumed-audio-focus-logcat.txt" >>"$scoped_log"
sleep 1
capture resumed
! ime_shown || archphene_die 'foreground resume unexpectedly opened Android IME'
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" 'foreground resume'

# The same client must resize into a tablet presentation without restarting
# either the Android host or glibc loader.
clear_scoped_logcat
archphene_adb_run shell wm size 1920x1200
archphene_wait_log \
  'Presented Linux frame session=.*surface=[1-9][0-9]{3,}x[1-9][0-9]{2,3}' 30 \
  'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
sleep 2
capture tablet-resized
! ime_shown || archphene_die 'tablet resize unexpectedly opened Android IME'
assert_pids "$wrapper_pid" "$manager_pid" "$linux_pid" 'tablet resize'

log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneLauncher:V ArchpheneLauncherSession:V ArchpheneAudio:I \
  ArchpheneGpu:I ArchpheneRuntime:I ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
printf '%s\n' "$log" | tee "$artifact_dir/logcat.txt" >>"$scoped_log"
! archphene_regex_contains "$(<"$scoped_log")" \
  'FATAL EXCEPTION|Runtime GUI exit=(?!0)|protocol error|GPU helper exited unexpectedly|helper-loss fallback' \
  || archphene_die 'SuperTux workflow produced a runtime/compositor failure'

python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" --field 'app=SuperTux' \
  --field 'toolkit=wayland-sdl' --field "wrapperPid=$wrapper_pid" \
  --field "managerPid=$manager_pid" --field "linuxPid=$linux_pid" \
  --field 'state=automatic orientation audio-focus gameplay input pause resume tablet resize' \
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
  --artifact "$artifact_dir/keyboard-selected.raw" \
  --artifact "$artifact_dir/keyboard-selected.png" \
  --artifact "$artifact_dir/keyboard-selected-visual.txt" \
  --artifact "$artifact_dir/keyboard-activated-ocr.txt" \
  --artifact "$artifact_dir/keyboard-activated.raw" \
  --artifact "$artifact_dir/keyboard-activated.png" \
  --artifact "$artifact_dir/activation-returned.raw" \
  --artifact "$artifact_dir/activation-returned.png" \
  --artifact "$artifact_dir/touch-returned.raw" \
  --artifact "$artifact_dir/touch-returned.png" \
  --artifact "$artifact_dir/touch-returned-ocr.txt" \
  --artifact "$artifact_dir/touch-logcat.txt" \
  --artifact "$artifact_dir/options-menu.raw" \
  --artifact "$artifact_dir/options-menu.png" \
  --artifact "$artifact_dir/options-menu-ocr.txt" \
  --artifact "$artifact_dir/options-returned.raw" \
  --artifact "$artifact_dir/options-returned.png" \
  --artifact "$artifact_dir/options-returned-ocr.txt" \
  --artifact "$artifact_dir/game-selection.raw" \
  --artifact "$artifact_dir/game-selection.png" \
  --artifact "$artifact_dir/game-active.raw" \
  --artifact "$artifact_dir/game-active.png" \
  --artifact "$artifact_dir/audio-playback-logcat.txt" \
  --artifact "$artifact_dir/level-entry.raw" \
  --artifact "$artifact_dir/level-entry.png" \
  --artifact "$artifact_dir/map-selected.raw" \
  --artifact "$artifact_dir/map-selected.png" \
  --artifact "$artifact_dir/level-intro.raw" \
  --artifact "$artifact_dir/level-intro.png" \
  --artifact "$artifact_dir/level-input-logcat.txt" \
  --artifact "$artifact_dir/level-active.raw" \
  --artifact "$artifact_dir/level-active.png" \
  --artifact "$artifact_dir/level-controlled.raw" \
  --artifact "$artifact_dir/level-controlled.png" \
  --artifact "$artifact_dir/gameplay-input-logcat.txt" \
  --artifact "$artifact_dir/background-audio-focus-logcat.txt" \
  --artifact "$artifact_dir/resumed-audio-focus-logcat.txt" \
  --artifact "$artifact_dir/resumed.raw" \
  --artifact "$artifact_dir/resumed.png" \
  --artifact "$artifact_dir/tablet-resized.raw" \
  --artifact "$artifact_dir/tablet-resized.png" \
  --artifact "$artifact_dir/startup-logcat.txt" \
  --artifact "$artifact_dir/scoped-logcat.txt" \
  --artifact "$artifact_dir/logcat.txt"

restore
trap - EXIT
archphene_note "SuperTux workflows passed on $serial: automatic SDL landscape, animated EGL rendering, PulseAudio with Android focus handoff, full-device OCR finger input, real gameplay movement/jump, HOME/resume, tablet resize, stable process pair, and exact prior state restored. Evidence: $artifact_dir"
