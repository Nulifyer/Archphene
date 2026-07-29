#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
artifact_dir=
timeout=600
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?missing value for --timeout-seconds}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package PACKAGE [--artifact-dir DIR] [--timeout-seconds N]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package is not a generated Archphene launcher"
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 120 && timeout <= 1200)) ||
  archphene_die "--timeout-seconds must be 120..1200"
archphene_test_init "$serial"
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "GLMark2 launcher is not installed: $package"

safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/current-glmark2/$safe_serial/gate}"
mkdir -p "$artifact_dir"
activity="$(archphene_launcher "$package")"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'GPU bridge ready session=[0-9]+' 45 \
  'ArchpheneGpu:I ArchpheneLauncherSession:I AndroidRuntime:E libc:F *:S' \
  >/dev/null
archphene_wait_log \
  'Linux Wayland client connected session=[0-9]+' 45 \
  'ArchpheneGpu:I ArchpheneLauncherSession:I AndroidRuntime:E libc:F *:S' \
  >/dev/null
pid="$(archphene_android_pid "$package")"
[[ -n "$pid" ]] || archphene_die "GLMark2 Android host is missing"

sleep 3
archphene_adb_run exec-out screencap >"$artifact_dir/scene-a.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/scene-a.png"
sleep 6
archphene_adb_run exec-out screencap >"$artifact_dir/scene-b.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/scene-b.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
  "$artifact_dir/scene-a.raw"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/frame-health-check.py" \
  "$artifact_dir/scene-b.raw"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/scene-a.raw" "$artifact_dir/scene-b.raw" \
  --minimum-difference .2 --minimum-changed-ratio .01

archphene_wait_log \
  'Linux process exited session=[0-9]+ status=0' "$timeout" \
  'ArchpheneGpu:V ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' \
  >/dev/null
log="$(
  archphene_adb_run logcat -d -v threadtime \
    -s ArchpheneGpu:V ArchpheneLauncherSession:V AndroidRuntime:E libc:F '*:S' \
    2>/dev/null || true
)"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"

archphene_regex_contains "$log" 'GL_RENDERER:\s+virgl \(' ||
  archphene_die "GLMark2 did not use the Android virgl renderer"
archphene_regex_contains "$log" \
  'GL_VERSION:\s+OpenGL ES [23]\.[0-9]+ Mesa \S+' ||
  archphene_die "GLMark2 did not report a Mesa OpenGL ES context"
archphene_regex_contains "$log" 'glmark2 Score:\s+[1-9][0-9]*' ||
  archphene_die "GLMark2 did not publish a positive score"
archphene_regex_contains "$log" '\[build\].*FPS: [1-9][0-9]*' ||
  archphene_die "GLMark2 did not complete the build scene"
archphene_regex_contains "$log" '\[refract\].*FPS: [1-9][0-9]*' ||
  archphene_die "GLMark2 did not complete the refract scene"
scene_count="$(grep -Ec '\[[^]]+\].*FPS: [1-9][0-9]*' <<<"$log" || true)"
((scene_count >= 30)) ||
  archphene_die "GLMark2 completed only $scene_count default scene variants"
[[ "$log" != *"FATAL EXCEPTION"* &&
    "$log" != *"Fatal signal"* &&
    "$log" != *"GPU helper exited unexpectedly"* &&
    "$log" != *"llvmpipe"* ]] ||
  archphene_die "GLMark2 crashed or fell back to software"
[[ "$(archphene_android_pid "$package")" == "$pid" ]] ||
  archphene_die "Android host changed during the complete benchmark"
score="$(sed -n 's/.*glmark2 Score:[[:space:]]*\([1-9][0-9]*\).*/\1/p' \
  <<<"$log" | tail -n1)"
[[ -n "$score" ]] || archphene_die "could not decode the validated GLMark2 score"

python3 "$ARCHPHENE_SCRIPTS_DIR/lib/wayland-geometry-check.py" \
  "$artifact_dir/logcat.txt"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" --field 'app=GLMark2' \
  --field 'state=complete default OpenGL ES suite' \
  --field 'toolkit=wayland-egl' --field "androidPid=$pid" \
  --field "sceneVariants=$scene_count" --field "score=$score" \
  --artifact "$artifact_dir/scene-a.raw" --artifact "$artifact_dir/scene-a.png" \
  --artifact "$artifact_dir/scene-b.raw" --artifact "$artifact_dir/scene-b.png" \
  --artifact "$artifact_dir/logcat.txt"
archphene_note "Current Archphene GLMark2 gate passed on $serial"
archphene_note "  virgl completed $scene_count scene variants with score $score"
archphene_note "  Full-device evidence and scoped log: $artifact_dir"
