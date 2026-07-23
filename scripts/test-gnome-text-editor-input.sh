#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.pe7675d0e278efaeade715635f437a43d
clipboard_package=org.archphene.linux.p97eb2a60fdffcfe66758935b730cb3f1
timeout=60
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --clipboard-package) clipboard_package="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
if [[ ! "$timeout" =~ ^[0-9]+$ ]] || ((timeout < 15 || timeout > 180)); then
  archphene_die '--timeout-seconds must be 15..180'
fi

archphene_test_init "$serial"
archphene_adb_run shell pm path "$package" >/dev/null \
  || archphene_die "GNOME Text Editor wrapper is not installed: $package"
archphene_adb_run shell pm path "$clipboard_package" >/dev/null \
  || archphene_die "wl-paste diagnostic wrapper is not installed: $clipboard_package"
activity="$(archphene_launcher "$package")"
clipboard_activity="$(archphene_launcher "$clipboard_package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
state=files/linux-home/.local/share/org.gnome.TextEditor
backup="files/archphene-text-editor-input-state-$safe_serial"
had_state=false
state_snapshot_taken=false
clipboard_session=false
restoration_done=false

restore_state() {
  local restore_failed=false deadline log
  if [[ "$clipboard_session" == true ]]; then
    # A normal Activity teardown restores the clipboard captured by the
    # debuggable test hook. A force-stop is only the final cleanup fallback.
    archphene_adb_run shell am start -W -n "$activity" \
      >/dev/null 2>&1 || restore_failed=true
    archphene_adb_run shell input keyboard keycombination \
      KEYCODE_CTRL_LEFT KEYCODE_Z >/dev/null 2>&1 || true
    archphene_adb_run shell input keyboard keycombination \
      KEYCODE_CTRL_LEFT KEYCODE_Z >/dev/null 2>&1 || true
    archphene_adb_run logcat -c >/dev/null 2>&1 || true
    archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
    deadline=$((SECONDS + 10))
    while ((SECONDS < deadline)); do
      log="$(archphene_adb_run logcat -d -v brief -s \
        ArchpheneLinuxApp:I AndroidRuntime:E '*:S' 2>/dev/null || true)"
      if [[ "$log" == *'Android host destroyed after runtime shutdown'* ]]; then
        clipboard_session=false
        break
      fi
      sleep .25
    done
    [[ "$clipboard_session" == false ]] || restore_failed=true
  fi
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 \
    || restore_failed=true
  archphene_adb_run shell am force-stop "$clipboard_package" >/dev/null 2>&1 \
    || restore_failed=true
  if [[ "$state_snapshot_taken" == true && "$restoration_done" == false ]]; then
    archphene_adb_run shell run-as "$package" rm -rf "$state" \
      >/dev/null 2>&1 || restore_failed=true
    if [[ "$had_state" == true ]]; then
      archphene_adb_run shell run-as "$package" mkdir -p \
        files/linux-home/.local/share >/dev/null 2>&1 || restore_failed=true
      archphene_adb_run shell run-as "$package" cp -a "$backup" "$state" \
        >/dev/null 2>&1 || restore_failed=true
    fi
    archphene_adb_run shell run-as "$package" rm -rf "$backup" \
      >/dev/null 2>&1 || restore_failed=true
    restoration_done=true
  fi
  if [[ "$restore_failed" == true ]]; then
    echo 'error: could not restore GNOME Text Editor input-test state' >&2
    return 1
  fi
}
trap restore_state EXIT

archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell run-as "$package" rm -rf "$backup"
if archphene_adb_run shell run-as "$package" test -e "$state"; then
  archphene_adb_run shell run-as "$package" cp -a "$state" "$backup"
  had_state=true
fi
state_snapshot_taken=true
archphene_adb_run shell run-as "$package" rm -rf "$state"

suffix="${RANDOM}${RANDOM}"
android_text="Archphene-clipboard-$suffix-"
composing_text='Archphene-preedit-é-雪-🙂'
committed_text='compose-é-雪-🙂'
expected="$android_text$committed_text"
composing_base64="$(printf %s "$composing_text" | base64 -w0)"
committed_base64="$(printf %s "$committed_text" | base64 -w0)"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --es archphene_test_android_clipboard "$android_text" \
  --ez archphene_test_restore_clipboard_on_destroy true >/dev/null
clipboard_session=true
archphene_wait_log 'mapped=true.*primary=true.*Text Editor' "$timeout" \
  'ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E *:S' >/dev/null

read -r width height <<<"$(archphene_adb_run shell wm size \
  | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
[[ -n "${width:-}" && -n "${height:-}" ]] \
  || archphene_die 'could not read display size for Text Editor focus'
archphene_adb_run shell input tap "$((width / 2))" "$((height * 3 / 5))"
sleep .5
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_V
sleep .5

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --es archphene_test_ime_composing_base64 "$composing_base64" \
  --es archphene_test_ime_commit_base64 "$committed_base64" >/dev/null
ime_log="$(archphene_wait_log \
  'Injected test IME preeditBytes=[1-9][0-9]*.*commitBytes=[1-9][0-9]*.*submit=false' \
  "$timeout" 'ArchpheneInput:I AndroidRuntime:E *:S')"
[[ "$ime_log" != *'FATAL EXCEPTION'* ]] \
  || archphene_die 'GNOME Text Editor crashed during complex IME input'

archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_A
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_C
sleep 1

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$clipboard_package"
archphene_adb_run shell am start -W -n "$clipboard_activity" >/dev/null
clipboard_log="$(archphene_wait_log 'Runtime GUI exit=0' "$timeout" \
  'ArchpheneRuntime:V ArchpheneInput:V AndroidRuntime:E *:S')"
[[ "$clipboard_log" == *"$expected"* \
    && "$clipboard_log" == *'Clipboard Android content reads=1'* ]] \
  || archphene_die "Text Editor clipboard/IME result did not round-trip exactly: $clipboard_log"

# Return to the editor and undo both edits so its blank buffer can close
# without a save prompt. Normal host destruction restores the user's clipboard.
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_Z
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_Z
archphene_adb_run logcat -c
archphene_adb_run shell input keyevent KEYCODE_BACK
destroy_log="$(archphene_wait_log \
  'Android host destroyed after runtime shutdown' "$timeout" \
  'ArchpheneLinuxApp:I AndroidRuntime:E *:S')"
[[ "$destroy_log" != *'FATAL EXCEPTION'* ]] \
  || archphene_die 'GNOME Text Editor crashed during graceful close'
clipboard_session=false

restore_state
trap - EXIT
archphene_note "GNOME Text Editor input passed on $serial: Android paste, UTF-8 preedit/commit, Linux copy, exact Android clipboard readback, graceful close, and prior session/clipboard restoration."
