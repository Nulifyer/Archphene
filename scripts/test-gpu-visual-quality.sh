#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=
artifact_dir=
sustain=8
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    --sustain-seconds) sustain="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$package" ]] || archphene_die '--package is required'
[[ "$sustain" =~ ^[0-9]+$ ]] && ((sustain >= 3 && sustain <= 120)) \
  || archphene_die '--sustain-seconds must be 3..120'
archphene_test_init "$serial"
archphene_adb_run shell pm path "$package" >/dev/null \
  || archphene_die "GPU wrapper is not installed: $package"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/gpu}"
mkdir -p "$artifact_dir"
activity="$(archphene_launcher "$package")"

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Graphics renderer=virpipe Android EGL/GLES bridge' 45 \
  'ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_log 'mapped=true.*primary=true' 45 \
  'ArchpheneInput:V AndroidRuntime:E *:S' >/dev/null
pid="$(archphene_android_pid "$package")"
linux_pid="$(archphene_linux_loader_pid "$pid")"
[[ -n "$pid" && -n "$linux_pid" ]] || archphene_die 'GPU process tree is missing'

archphene_adb_run exec-out screencap >"$artifact_dir/frame-a.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/frame-a.png"
sleep "$sustain"
archphene_adb_run exec-out screencap >"$artifact_dir/frame-b.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/frame-b.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" "$artifact_dir/frame-a.raw"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" "$artifact_dir/frame-b.raw"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/frame-a.raw" "$artifact_dir/frame-b.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01

log="$(archphene_adb_run logcat -d -s ArchpheneLinuxApp:V ArchpheneRuntime:V ArchpheneInput:V AndroidRuntime:E '*:S')"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/wayland-geometry-check.py" \
  "$artifact_dir/logcat.txt"
[[ "$log" != *'FATAL EXCEPTION'* \
    && "$log" != *'GPU helper exited unexpectedly'* \
    && "$log" != *'Graphics renderer=llvmpipe helper-loss fallback'* ]] \
  || archphene_die 'accelerated visual run crashed or fell back to software'
[[ "$(archphene_android_pid "$package")" == "$pid" ]] \
  || archphene_die 'Android host changed during sustained GPU presentation'
current_linux="$(archphene_linux_loader_pid "$pid" || true)"
[[ "$current_linux" == "$linux_pid" ]] \
  || archphene_die 'GPU workload exited during the sustained presentation window'
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" --field 'app=GPU probe' \
  --field 'state=sustained animated presentation' --field 'toolkit=wayland-egl' \
  --field "androidPid=$pid" --field "linuxPid=$linux_pid" \
  --field "sustainSeconds=$sustain" \
  --artifact "$artifact_dir/frame-a.raw" --artifact "$artifact_dir/frame-a.png" \
  --artifact "$artifact_dir/frame-b.raw" --artifact "$artifact_dir/frame-b.png" \
  --artifact "$artifact_dir/logcat.txt"

archphene_note "GPU visual gate passed: two distinct nonblank virpipe frames over ${sustain}s with bounded geometry and stable Android host. Evidence: $artifact_dir"
