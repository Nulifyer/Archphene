#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
package=
artifact_dir=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package CURRENT_FOOT_LAUNCHER [--apk PATH --install-apk] [--artifact-dir PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package must be a generated Archphene launcher"

archphene_test_init "$serial"
manager=org.archphene.app.debug
manager_activity="$manager/org.archphene.app.MainActivity"
activity="$(archphene_launcher "$package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/foot-workflows/$safe_serial}"
mkdir -p "$artifact_dir"

ime_file=foot-ime-workflow.txt
composition_file=foot-ime-composition-workflow.txt
clipboard_file=foot-clipboard-workflow.txt
selection_file=foot-selection-workflow.txt
selection_render_file=foot-selection-render-ready.txt
scroll_file=foot-scroll-ready.txt
lifecycle_file=foot-lifecycle-workflow.txt
manager_home=files/arch-root/home/archphene
screen_size="$(archphene_adb_run shell wm size | tr -d '\r')"
override_size="$(sed -n 's/^Override size: //p' <<<"$screen_size")"
physical_size="$(sed -n 's/^Physical size: //p' <<<"$screen_size")"
size_changed=false
fixtures_owned=false
clipboard_saved=false
manager_was_running=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_was_running=true
fi
if archphene_android_pid "$package" >/dev/null 2>&1; then
  archphene_die "refusing to replace an active Foot launcher"
fi

restore_size() {
  if [[ "$size_changed" != true ]]; then
    return
  fi
  if [[ -n "$override_size" ]]; then
    archphene_adb_run shell wm size "$override_size" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm size reset >/dev/null 2>&1 || true
  fi
  size_changed=false
}

stop_foot_session() {
  local linux_pid linux_pgid deadline tree
  tree="$(
    archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
      tr -d '\r'
  )"
  linux_pid="$(awk '/--argv0 foot / { print $1; exit }' <<<"$tree")"
  linux_pgid="$(
    awk -v pid="$linux_pid" '$1 == pid { print $3; exit }' <<<"$tree"
  )"
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$linux_pgid" =~ ^[1-9][0-9]*$ ]]; then
    deadline=$((SECONDS + 20))
    while ((SECONDS < deadline)); do
      tree="$(
        archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
          tr -d '\r'
      )"
      if ! awk -v pgid="$linux_pgid" '$3 == pgid { found = 1 } END { exit !found }' \
        <<<"$tree"; then
        sleep 0.5
        return 0
      fi
      sleep 0.25
    done
    archphene_die "Foot process group did not stop during cleanup"
  fi
}

cleanup() {
  if [[ "$clipboard_saved" == true ]]; then
    archphene_adb_run logcat -c
    archphene_adb_run shell am broadcast \
      -n "$manager/org.archphene.app.ClipboardTestReceiver" \
      -a org.archphene.app.debug.action.RESTORE_TEST_CLIPBOARD \
      >/dev/null
    archphene_wait_log \
      'Restored Android clipboard after debug test' \
      10 'ArchpheneClipboardProbe:I AndroidRuntime:E *:S' >/dev/null
    clipboard_saved=false
  fi
  restore_size
  stop_foot_session
  if [[ "$fixtures_owned" == true ]]; then
    archphene_adb_run shell run-as "$manager" rm -f \
      "$manager_home/$ime_file" \
      "$manager_home/$composition_file" \
      "$manager_home/$clipboard_file" \
      "$manager_home/$selection_file" \
      "$manager_home/$selection_render_file" \
      "$manager_home/$scroll_file" \
      "$manager_home/$lifecycle_file" \
      >/dev/null 2>&1 || true
    fixtures_owned=false
  fi
  if [[ "$manager_was_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$manager_activity" \
      >/dev/null 2>&1 || true
  else
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_file() {
  local name="$1" expected="$2" deadline=$((SECONDS + 20)) value
  while ((SECONDS < deadline)); do
    value="$(
      archphene_adb_run shell run-as "$manager" \
        cat "$manager_home/$name" 2>/dev/null |
        tr -d '\r' || true
    )"
    [[ "$value" == "$expected" ]] && return 0
    sleep 0.3
  done
  archphene_die \
    "shared Foot file did not contain expected UTF-8 text: $name actual=$(printf %q "$value")"
}

process_tree() {
  archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS | tr -d '\r'
}

foot_pid() {
  process_tree |
    awk '/--argv0 foot / && !found { print $1; found = 1 }'
}

current_pids() {
  local wrapper linux
  wrapper="$(archphene_android_pid "$package")"
  linux="$(foot_pid)"
  [[ "$wrapper" =~ ^[1-9][0-9]*$ ]] ||
    archphene_die "Foot Android wrapper PID is unavailable: $(printf %q "$wrapper")"
  [[ "$linux" =~ ^[1-9][0-9]*$ ]] ||
    archphene_die "Foot Linux PID is unavailable: $(printf %q "$linux")"
  printf '%s %s\n' "$wrapper" "$linux"
}

wait_foreground() {
  local expected="$1" deadline=$((SECONDS + 15)) top
  while ((SECONDS < deadline)); do
    top="$(
      archphene_adb_run shell dumpsys activity activities |
        awk '/topResumedActivity=/ { print; exit }'
    )"
    [[ "$top" == *"$expected"* ]] && return 0
    sleep 0.25
  done
  archphene_die "timed out waiting for foreground package: $expected"
}

capture_png() {
  archphene_adb_run exec-out screencap -p >"$artifact_dir/$1.png"
}

capture_raw() {
  archphene_adb_run exec-out screencap >"$artifact_dir/$1.raw"
}

inject_text() {
  local value="$1" submit="${2:-false}" composing="${3:-}"
  local value_base64 composing_base64 log
  local -a composing_extra=()
  local -a committed_extra=()
  if [[ -n "$value" ]]; then
    value_base64="$(printf %s "$value" | base64 -w0)"
    committed_extra=(--es committed_base64 "$value_base64")
  fi
  if [[ -n "$composing" ]]; then
    composing_base64="$(printf %s "$composing" | base64 -w0)"
    composing_extra=(--es composing_base64 "$composing_base64")
  fi
  archphene_adb_run logcat -c
  archphene_adb_run shell am broadcast \
    -n "$manager/org.archphene.app.LauncherSessionTestReceiver" \
    -a org.archphene.app.debug.action.INJECT_LAUNCHER_IME \
    --es token launcher-session-gate \
    --es package "$package" \
    "${composing_extra[@]}" \
    "${committed_extra[@]}" \
    --ez submit "$submit" >/dev/null
  log="$(
    archphene_wait_log \
      "Manager session IME package=$package.*submit=$submit result=accepted" \
      20 'ArchpheneLauncherSessionProbe:I AndroidRuntime:E *:S'
  )"
  [[ "$log" != *'FATAL EXCEPTION'* ]] ||
    archphene_die "Foot crashed while accepting manager-session IME"
  # The debug receiver only enqueues work. Let the compositor owner drain it
  # before a following real key event is delivered through the wrapper Binder.
  sleep 0.2
}

key_chord() {
  # Android's zero/default synthetic chord duration can leave the final key
  # repeating on some emulator and OEM builds. An explicit short hold produces
  # one ordinary hardware chord with ordered down/up events.
  archphene_adb_run shell input keyboard keycombination -t 30 "$@" >/dev/null
}

hide_ime_if_visible() {
  local state before top
  state="$(archphene_adb_run shell dumpsys window displays 2>/dev/null || true)"
  if archphene_regex_contains "$state" 'type=ime[^\n]*visible=true'; then
    before="$(archphene_android_pid "$package")"
    archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
    sleep 0.7
    [[ "$(archphene_android_pid "$package")" == "$before" ]] ||
      archphene_die "hiding the Android IME closed the Foot launcher"
    top="$(
      archphene_adb_run shell dumpsys activity activities |
        awk '/topResumedActivity=/ { print; exit }'
    )"
    [[ "$top" == *"$package"* ]] ||
      archphene_die "hiding the Android IME moved focus away from Foot: $top"
  fi
}

close_with_back() {
  local attempt deadline log
  for attempt in 1 2 3 4; do
    archphene_adb_run logcat -c
    archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
    deadline=$((SECONDS + 5))
    while ((SECONDS < deadline)); do
      log="$(
        archphene_adb_run logcat -d -v brief \
          -s ArchpheneLauncherSession:I AndroidRuntime:E '*:S' 2>/dev/null || true
      )"
      if archphene_regex_contains \
        "$log" \
        'Closed launcher session=|Client Binder died for launcher session='; then
        return 0
      fi
      sleep 0.25
    done
  done
  return 1
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "$manager is not installed; pass --install-apk with --apk"
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "Foot launcher is not installed: $package"
[[ -z "$(foot_pid)" ]] ||
  archphene_die "refusing to interrupt an active manager-owned Foot process"
for fixture in \
  "$ime_file" \
  "$composition_file" \
  "$clipboard_file" \
  "$selection_file" \
  "$selection_render_file" \
  "$scroll_file" \
  "$lifecycle_file"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$manager_home/$fixture" ||
    archphene_die "refusing to replace pre-existing Foot fixture: $fixture"
done
fixtures_owned=true
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -b crash -c
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
archphene_wait_log \
  'Package runtime ready:.*Pacman v[0-9]' 30 \
  'ArchpheneRuntime:V AndroidRuntime:E *:S' >/dev/null

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected session=' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  'Presented Linux frame session=.*attachmentFrame=1' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null

# Exercise UTF-8 preedit, commit, and editor action through the manager-owned
# active session. Generated launcher APKs intentionally contain no test extras.
ime_value='Archphene-é-雪-🙂'
inject_text \
  "printf '$ime_value' > ~/$ime_file" \
  true \
  'Archphene-preedit-雪'
wait_file "$ime_file" "$ime_value"
capture_png ime

# Replace an active Japanese candidate several times. Only the final committed
# command may enter the Linux PTY; leaking any intermediate preedit would prefix
# the command and prevent the exact result file from being created.
composition_value='日本語-雪-👍🏽-👩‍💻'
inject_text "" false 'に'
inject_text "" false '日本'
inject_text "" false '日本語変換'
capture_png ime-preedit-replacement
inject_text "printf '%s' '$composition_value' > ~/$composition_file" true
wait_file "$composition_file" "$composition_value"
capture_png ime-complex-commit

# Put the manager in front before its debug receiver seeds Android's real
# ClipboardManager. Returning to the existing app-shell task makes its normal focus
# callback submit the clipboard over authenticated Binder. This avoids relying
# on OEM delivery of a clipboard-listener callback to a background writer.
# The existing wrapper and Linux process must survive the round trip.
selection_value="ARCHPHENE_SELECTION_${RANDOM}_${RANDOM}"
selection_length="${#selection_value}"
clipboard_value="Archphene-clipboard-é-雪-🙂-${RANDOM}_${RANDOM}"
clipboard_command="printf '%s' '$clipboard_value' > ~/$clipboard_file; echo '    $selection_value'; printf READY > ~/$selection_render_file; bind 'set enable-bracketed-paste off'; IFS= read -r -N $selection_length archphene_selected; bind 'set enable-bracketed-paste on'; printf '%s' \"\$archphene_selected\" > ~/$selection_file; for i in {1..200}; do echo \"\$i\"; done; printf READY > ~/$scroll_file"
clipboard_command_base64="$(printf %s "$clipboard_command" | base64 -w0)"
read -r clipboard_wrapper_pid clipboard_linux_pid < <(current_pids)
archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
wait_foreground "$manager"
archphene_adb_run logcat -c
archphene_adb_run shell am broadcast \
  -n "$manager/org.archphene.app.ClipboardTestReceiver" \
  -a org.archphene.app.debug.action.SET_TEST_CLIPBOARD \
  --ez save_existing true \
  --es text_base64 "$clipboard_command_base64" >/dev/null
clipboard_saved=true
archphene_wait_log \
  'Saved Android clipboard before debug test' \
  10 'ArchpheneClipboardProbe:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
wait_foreground "$package"
read -r resumed_wrapper_pid resumed_linux_pid < <(current_pids)
[[ "$resumed_wrapper_pid" == "$clipboard_wrapper_pid" ]] ||
  archphene_die "Foot wrapper restarted during Android clipboard focus round trip"
[[ "$resumed_linux_pid" == "$clipboard_linux_pid" ]] ||
  archphene_die "Foot Linux process restarted during Android clipboard focus round trip"
sleep 0.4
archphene_adb_run logcat -c
key_chord KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V
archphene_wait_log \
  'Wrote first Android clipboard transfer.*on ArchpheneLauncherClipboard' \
  20 'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
# The worker log proves the Wayland data-source write completed; Foot consumes
# and renders those bytes on its own event loop. Do not race the following real
# Enter key ahead of that client-side paste.
sleep 0.7
capture_png android-clipboard-paste
archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
wait_file "$clipboard_file" "$clipboard_value"
wait_file "$selection_render_file" READY
capture_png clipboard
hide_ime_if_visible

# Render a single token, select its actual on-screen glyph bounds with a real
# pointer drag, copy it through Foot, and paste it back into the shared home.
sleep 0.7
capture_raw selection-locator
selection_geometry="$(
  python3 \
    "$ARCHPHENE_SCRIPTS_DIR/lib/foot-selection-geometry.py" \
    "$artifact_dir/selection-locator.raw"
)" || archphene_die "could not locate Foot's rendered selection row"
read -r x1 y1 x2 y2 <<<"$selection_geometry"
capture_raw selection
capture_png selection
archphene_adb_run logcat -c
archphene_adb_run shell input mouse swipe "$x2" "$y2" "$x1" "$y1" 700
sleep 0.5
[[ -n "$(archphene_android_pid "$package" 2>/dev/null || true)" ]] ||
  archphene_die "Foot launcher left the foreground during pointer selection"
top_activity="$(
  archphene_adb_run shell dumpsys activity activities |
    awk '/topResumedActivity=/ { print; exit }'
)"
[[ "$top_activity" == *"$package"* ]] ||
  archphene_die "pointer selection moved focus away from Foot: $top_activity"
capture_raw selection-highlight
capture_png selection-highlight
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/selection.raw" "$artifact_dir/selection-highlight.raw" \
  --minimum-changed-ratio 0.0002 \
  --minimum-difference 0.1 \
  --top-percent 8 \
  --bottom-percent 55
archphene_adb_run logcat -c
key_chord KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_C
archphene_wait_log \
  'Read first Linux clipboard transfer.*on ArchpheneLauncherClipboard' \
  20 'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
key_chord KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_V
sleep 0.7
capture_png linux-clipboard-paste
wait_file "$selection_file" "$selection_value"
wait_file "$scroll_file" READY

# Generate scrollback and require a visibly changed full-device frame.
hide_ime_if_visible
# Android's clipboard preview is part of the real user-visible screen. Let its
# transient animation settle so the comparison measures Foot scrollback.
sleep 4
capture_png scroll-bottom
sleep 0.3
capture_raw scroll-bottom
key_chord KEYCODE_SHIFT_LEFT KEYCODE_PAGE_UP
sleep 1.5
capture_png scroll-up
sleep 0.3
capture_raw scroll-up
if ! python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/scroll-bottom.raw" "$artifact_dir/scroll-up.raw" \
  --minimum-changed-ratio 0.002 \
  --minimum-difference 0.2 \
  --left-percent 0 \
  --right-percent 35 \
  --top-percent 8 \
  --bottom-percent 55 >/dev/null 2>&1; then
  for _ in 1 2 3 4; do
    archphene_adb_run shell input mouse scroll 500 500 --axis VSCROLL,5
  done
  sleep 1.5
  capture_png scroll-up
  sleep 0.3
  capture_raw scroll-up
fi
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/scroll-bottom.raw" "$artifact_dir/scroll-up.raw" \
  --minimum-changed-ratio 0.002 \
  --minimum-difference 0.2 \
  --left-percent 0 \
  --right-percent 35 \
  --top-percent 8 \
  --bottom-percent 55
key_chord KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_MOVE_END
read -r flow_wrapper flow_linux <<<"$(current_pids)"
flow_manager="$(archphene_android_pid "$manager")"

# Resize Android's logical display and prove the manager, wrapper, and Linux
# process all survive. Restore the user's exact prior override on every exit.
base_size="${override_size:-$physical_size}"
[[ "$base_size" =~ ^([0-9]+)x([0-9]+)$ ]] ||
  archphene_die "could not parse display size: $screen_size"
base_width="${BASH_REMATCH[1]}"
base_height="${BASH_REMATCH[2]}"
resize_width=$((base_width > 900 ? base_width - 120 : base_width + 120))
resize_height=$((base_height > 1500 ? base_height - 200 : base_height + 200))
archphene_adb_run logcat -c
archphene_adb_run shell wm size "${resize_width}x${resize_height}"
size_changed=true
archphene_wait_log \
  "Attached launcher Surface session=.*size=${resize_width}x" \
  30 'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
read -r resized_wrapper resized_linux <<<"$(current_pids)"
[[ "$resized_wrapper" == "$flow_wrapper" &&
   "$resized_linux" == "$flow_linux" &&
   "$(archphene_android_pid "$manager")" == "$flow_manager" ]] ||
  archphene_die "Foot processes restarted during live display resize"
capture_png resized
restore_size
sleep 1
read -r restored_wrapper restored_linux <<<"$(current_pids)"
[[ "$restored_wrapper" == "$flow_wrapper" &&
   "$restored_linux" == "$flow_linux" ]] ||
  archphene_die "Foot processes restarted while restoring display size"

# Prove graceful close, Binder-death cleanup after force-stop, and a fresh cold
# launcher/process pair which still writes to the same shared Linux home.
close_with_back || archphene_die "Foot did not close through Android Back"
deadline=$((SECONDS + 15))
while ((SECONDS < deadline)) && archphene_adb_run shell test -e "/proc/$flow_linux"; do
  sleep 0.2
done
archphene_adb_run shell test ! -e "/proc/$flow_linux" ||
  archphene_die "manager-owned Foot process survived graceful launcher close"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Presented Linux frame session=.*attachmentFrame=1' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
read -r before_stop_wrapper before_stop_linux <<<"$(current_pids)"
archphene_adb_run shell am force-stop "$package"
deadline=$((SECONDS + 15))
while ((SECONDS < deadline)); do
  if ! archphene_android_pid "$package" >/dev/null 2>&1 &&
     ! archphene_adb_run shell test -e "/proc/$before_stop_linux"; then
    break
  fi
  sleep 0.2
done
archphene_android_pid "$package" >/dev/null 2>&1 &&
  archphene_die "Foot wrapper survived force-stop"
archphene_adb_run shell test -e "/proc/$before_stop_linux" &&
  archphene_die "manager-owned Foot process survived wrapper force-stop"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Presented Linux frame session=.*attachmentFrame=1' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
read -r relaunched_wrapper relaunched_linux <<<"$(current_pids)"
[[ "$relaunched_wrapper" != "$before_stop_wrapper" &&
   "$relaunched_linux" != "$before_stop_linux" ]] ||
  archphene_die "Foot destructive relaunch reused stale processes"
inject_text "printf ARCHPHENE_LIFECYCLE_OK > ~/$lifecycle_file" true
wait_file "$lifecycle_file" ARCHPHENE_LIFECYCLE_OK
capture_png lifecycle

fatal_log="$(
  {
    archphene_adb_run logcat -b crash -d -v brief 2>/dev/null || true
    archphene_adb_run logcat -d -v brief \
      -s ArchpheneLauncherSession:E AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
  }
)"
[[ "$fatal_log" != *'FATAL EXCEPTION'* && "$fatal_log" != *'Fatal signal'* ]] ||
  archphene_die "Foot workflow emitted a fatal event: $fatal_log"

trap - EXIT
cleanup
archphene_note "Foot manager-session workflows passed on $serial"
archphene_note "  Japanese candidate replacement, exact complex UTF-8 commit, clipboard, selection, and scrollback passed"
archphene_note "  Live resize, graceful close, force-stop cleanup, and cold relaunch passed"
archphene_note "  Full-device screenshots and raw comparison frames: $artifact_dir"
