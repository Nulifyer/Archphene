#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.pb1623042aeee4267eb8c86dead4b2dd7
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ "$serial" == emulator-* ]] || archphene_die 'Kate large-display test requires an emulator'
archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts"
mkdir -p "$artifact_dir"

size_state="$(archphene_adb_run shell wm size | tr -d '\r')"
density_state="$(archphene_adb_run shell wm density | tr -d '\r')"
size_override="$(sed -n 's/^Override size: //p' <<<"$size_state")"
density_override="$(sed -n 's/^Override density: //p' <<<"$density_state")"
initial_displays="$(archphene_adb_run shell cmd display get-displays -i | tr -d '\r')"
[[ "$initial_displays" == 0 ]] \
  || archphene_die 'remove existing emulator secondary displays before this test'

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run emu multidisplay del 1 >/dev/null 2>&1 || true
  if [[ -n "$size_override" ]]; then
    archphene_adb_run shell wm size "$size_override" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm size reset >/dev/null 2>&1 || true
  fi
  if [[ -n "$density_override" ]]; then
    archphene_adb_run shell wm density "$density_override" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell wm density reset >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_pid() {
  local deadline=$((SECONDS + 30)) value
  while ((SECONDS < deadline)); do
    value="$(archphene_android_pid "$package" || true)"
    if [[ -n "$value" ]]; then
      printf '%s' "$value"
      return 0
    fi
    sleep .3
  done
  archphene_die 'Kate Android process did not start'
}

wait_pid_log() {
  local pid="$1" pattern="$2" seconds="$3" log
  local deadline=$((SECONDS + seconds))
  while ((SECONDS < deadline)); do
    log="$(archphene_adb_run logcat -d -v brief --pid="$pid" \
      -s ArchpheneLinuxApp:V ArchpheneInput:V AndroidRuntime:E '*:S' \
      2>/dev/null || true)"
    if archphene_regex_contains "$log" "$pattern"; then
      printf '%s' "$log"
      return 0
    fi
    sleep .3
  done
  archphene_die "timed out waiting for Kate log pattern: $pattern"
}

# Tablet portrait and landscape exercise Android configuration changes in the
# same Activity and Linux process tree.
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell wm size 1600x2560
archphene_adb_run shell wm density 320
archphene_adb_run logcat -c
archphene_adb_run shell am start --display 0 -W -n "$activity" >/dev/null
tablet_pid="$(wait_pid)"
wait_pid_log "$tablet_pid" 'mapped=true.*primary=true' 60 >/dev/null
tablet_loader="$(archphene_linux_loader_pid "$tablet_pid")"
[[ -n "$tablet_loader" ]] || archphene_die 'Kate tablet Linux loader is missing'
archphene_adb_run exec-out screencap -p \
  >"$artifact_dir/candidate-kate-$serial-tablet-portrait.png"

archphene_adb_run shell wm size 2560x1600
sleep 5
[[ "$(archphene_android_pid "$package")" == "$tablet_pid" \
    && "$(archphene_linux_loader_pid "$tablet_pid")" == "$tablet_loader" ]] \
  || archphene_die 'Kate restarted during tablet rotation/resize'
archphene_adb_run exec-out screencap -p \
  >"$artifact_dir/candidate-kate-$serial-tablet-landscape.png"

# The emulator console creates a real secondary Android display. Launch a new
# task there and target pointer/keyboard events to that display explicitly.
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell wm size reset
archphene_adb_run shell wm density reset
archphene_adb_run emu multidisplay add 1 1920 1080 240 0 >/dev/null
sleep 3
external_display="$(archphene_adb_run shell cmd display get-displays -i \
  | tr -d '\r' | awk '$1 != 0 {print; exit}')"
[[ "$external_display" =~ ^[0-9]+$ ]] \
  || archphene_die 'emulator secondary display was not created'
surface_display="$(archphene_adb_run shell dumpsys SurfaceFlinger --display-id \
  | awk '/Virtual display.*Emulator 2D Display/{print $2; exit}')"
[[ "$surface_display" =~ ^[0-9]+$ ]] \
  || archphene_die 'secondary SurfaceFlinger display was not found'

archphene_adb_run logcat -c
archphene_adb_run shell am start --display "$external_display" --windowingMode 5 \
  -f 0x18000000 -n "$activity" >/dev/null
external_pid="$(wait_pid)"
wait_pid_log "$external_pid" 'mapped=true.*primary=true' 60 >/dev/null
external_loader="$(archphene_linux_loader_pid "$external_pid")"
[[ -n "$external_loader" ]] || archphene_die 'Kate external-display loader is missing'

activities="$(archphene_adb_run shell dumpsys activity activities)"
python3 -c '
import re, sys
display, package, text = sys.argv[1], sys.argv[2], sys.stdin.read()
sections = re.split(r"(?=  Display: mDisplayId=)", text)
if not any(re.search(rf"Display: mDisplayId={re.escape(display)}\b", section)
           and package in section for section in sections):
    raise SystemExit("Kate task is not hosted on the secondary display")
' "$external_display" "$package" <<<"$activities"

archphene_adb_run shell input touchscreen -d "$external_display" tap 600 352
sleep 2
archphene_adb_run shell input touchscreen -d "$external_display" tap 700 300
archphene_adb_run shell input keyboard -d "$external_display" text KATE_EXTERNAL_TEST
wait_pid_log "$external_pid" 'title=Untitled.*KATE_EXTERNAL_TEST' 30 >/dev/null
[[ "$(archphene_android_pid "$package")" == "$external_pid" \
    && "$(archphene_linux_loader_pid "$external_pid")" == "$external_loader" ]] \
  || archphene_die 'Kate restarted during external-display input'
archphene_adb_run exec-out screencap -d "$surface_display" -p \
  >"$artifact_dir/candidate-kate-$serial-external-input.png"

archphene_note "Kate large-display regression passed on $serial: tablet PID $tablet_pid/$tablet_loader remained stable; display $external_display rendered 1920x1080 and accepted targeted pointer/keyboard input with PID $external_pid/$external_loader."
