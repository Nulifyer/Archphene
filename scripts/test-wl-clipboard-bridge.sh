#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archpheneos.manager
package=org.archphene.linux.p97eb2a60fdffcfe66758935b730cb3f1
activity=org.archphene.linux.kcalc.MainActivity
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --manager) manager="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --activity) activity="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--manager PACKAGE] [--package PACKAGE] [--activity CLASS]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"

runtime_log() {
  archphene_adb_run logcat -d -v brief -s \
    ArchpheneRuntime:V ArchpheneLinuxApp:V ArchpheneInput:V \
    AndroidRuntime:E DEBUG:V linker:V '*:S'
}

assert_clean_runtime() {
  local log="$1"
  ! archphene_regex_contains "$log" \
    'CANNOT LINK|SIGSEGV|Permission denied|exec cat|Could not launch|FATAL EXCEPTION' \
    || archphene_die "wl-clipboard runtime failure detected"
}

set_wrapper_command() {
  local executable="$1" confirmation activation
  archphene_adb_run logcat -c
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run shell am start -W \
    -n "$manager/.MainActivity" \
    --es archphene_test_assemble_qt wl-clipboard \
    --ez archphene_test_stage_transaction true \
    --ez archphene_test_wayland_candidate true \
    --es archphene_test_wayland_executable "$executable" \
    --ez archphene_test_install_assembled true >/dev/null

  archphene_wait_ui 'text="(?:Install|Update)"' \
    "clipboard-$executable-confirmation" 120
  confirmation="$ARCHPHENE_UI"
  archphene_tap_ui_pattern "$confirmation" \
    'text="(?:Install|Update)"' "$executable install confirmation"
  activation="$(archphene_wait_log \
    'activated generated wrapper' 30 \
    'ArchpheneRuntime:V ArchpheneLinuxApp:V AndroidRuntime:E *:S')"
  [[ "$activation" == *"$package"* ]] \
    || archphene_die "unexpected generated wrapper activated for $executable"
}

start_wrapper() {
  archphene_adb_run logcat -c
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run shell am start -W \
    -n "$package/$activity" "$@" >/dev/null
}

android_text="archphene-android-to-linux-$(date +%s%N)"
set_wrapper_command wl-paste
start_wrapper --es archphene_test_android_clipboard "$android_text"
paste_log="$(archphene_wait_log \
  'Runtime GUI exit=0' 30 \
  'ArchpheneRuntime:V ArchpheneLinuxApp:V ArchpheneInput:V AndroidRuntime:E *:S')"
assert_clean_runtime "$paste_log"
[[ "$paste_log" == *"$android_text"*
    && "$paste_log" == *'Clipboard Android content reads=1'* ]] \
  || archphene_die "Android-to-Linux clipboard transfer or lazy read failed"

linux_text="archphene-linux-to-android-$(date +%s%N)"
set_wrapper_command wl-copy
start_wrapper \
  --esa archphene_test_runtime_args "$linux_text" \
  --ez archphene.wayland_debug true
archphene_wait_log \
  'mapped=true active=true' 30 \
  'ArchpheneRuntime:V ArchpheneLinuxApp:V ArchpheneInput:V AndroidRuntime:E *:S' \
  >/dev/null
copy_android_pid="$(archphene_android_pid "$package")"
copy_linux_pid="$(archphene_linux_loader_pid "$copy_android_pid")"
[[ -n "$copy_linux_pid" ]] \
  || archphene_die "wl-copy did not remain alive as the Linux clipboard owner"
# The wrapper captures the client's stderr protocol trace and publishes it to
# logcat only after the client exits. Give Android's ClipboardManager time to
# request the newly selected source, then close the otherwise persistent owner.
sleep 2
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
copy_log="$(archphene_wait_log \
  'Runtime GUI exit=(0|143)' 30 \
  'ArchpheneRuntime:V ArchpheneLinuxApp:V ArchpheneInput:V AndroidRuntime:E *:S')"
assert_clean_runtime "$copy_log"
for evidence in \
  'set_selection(wl_data_source' \
  '.send(' \
  'Clipboard Android content reads=0'; do
  [[ "$copy_log" == *"$evidence"* ]] \
    || archphene_die "Linux clipboard source lacks protocol evidence: $evidence"
done

set_wrapper_command wl-paste
start_wrapper
verify_log="$(archphene_wait_log \
  'Runtime GUI exit=0' 30 \
  'ArchpheneRuntime:V ArchpheneLinuxApp:V ArchpheneInput:V AndroidRuntime:E *:S')"
assert_clean_runtime "$verify_log"
[[ "$verify_log" == *"$linux_text"*
    && "$verify_log" == *'Clipboard Android content reads=1'* ]] \
  || archphene_die "Linux-to-Android clipboard readback or lazy read failed"

archphene_note "wl-clipboard bridge passed on $serial: exact Android-to-Linux and Linux-to-Android text, focused selection/source protocol, and demand-driven Android reads verified."
