#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=false
reset_data=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --skip-install) skip_install=true; shift ;;
    --reset-data) reset_data=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--skip-install] [--reset-data]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
archphene_require_command python3

package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/base-regression"
mkdir -p "$output_dir"

initial_wakefulness="$(archphene_adb_run shell dumpsys power |
  sed -n 's/.*mWakefulness=//p' | head -n1 | tr -d '\r')"
restore_screen=false
rotation_changed=false
initial_accelerometer_rotation=
initial_user_rotation=
if [[ "$initial_wakefulness" != Awake ]]; then
  archphene_adb_run shell input keyevent KEYCODE_WAKEUP >/dev/null
  archphene_adb_run shell wm dismiss-keyguard >/dev/null
  restore_screen=true
fi

restore_device() {
  if [[ "$rotation_changed" == true ]]; then
    archphene_adb_run shell settings put system accelerometer_rotation \
      "$initial_accelerometer_rotation" >/dev/null 2>&1 || true
    archphene_adb_run shell settings put system user_rotation \
      "$initial_user_rotation" >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$restore_screen" == true ]]; then
    archphene_adb_run shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
  fi
}
trap restore_device EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
if [[ "$reset_data" == true ]]; then
  archphene_adb_run shell pm clear "$package" >/dev/null
fi

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
launch="$(archphene_adb_run shell am start -W -n "$activity" | tr -d '\r')"
[[ "$launch" == *"Status: ok"* && "$launch" == *"LaunchState: COLD"* ]] ||
  archphene_die "base app did not cold-launch successfully: $launch"
archphene_wait_log 'Shared Rust runtime started' 15 >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
if [[ "$reset_data" == true ]]; then
  archphene_wait_log 'root directories created=[1-9][0-9]*' 15 >/dev/null
fi

first_pid="$(archphene_android_pid "$package")"
[[ -n "$first_pid" ]] || archphene_die "base app process is missing after launch"
archphene_wait_ui 'Rust runtime [0-9]+.*state 2.*Root ready.*jobs ready.*Pacman ready.*events [0-9]+' \
  "archphene-base-$serial" 15
first_generation="$(python3 -c '
import re, sys
match = re.search(r"text=\"Rust runtime ([0-9]+)", sys.stdin.read())
if match is None:
    raise SystemExit("missing first runtime generation")
print(match.group(1))
' <<<"$ARCHPHENE_UI")"

version_marker="$(archphene_adb_run exec-out run-as "$package" \
  cat files/arch-root/.archphene-root-version | tr -d '\r\n')"
[[ "$version_marker" == archphene-root-v1 ]] ||
  archphene_die "unexpected private Arch root version: $version_marker"
for entry in usr etc var/lib/pacman var/cache/pacman/pkg opt home/archphene tmp run mnt/android; do
  archphene_adb_run shell run-as "$package" stat "files/arch-root/$entry" >/dev/null ||
    archphene_die "private Arch root entry is missing: $entry"
done
for expected in \
    "files/arch-root:700" \
    "files/arch-root/home/archphene:700" \
    "files/arch-root/tmp:1777" \
    "files/arch-root/run:700"; do
  path="${expected%:*}"
  expected_mode="${expected##*:}"
  actual_mode="$(archphene_adb_run shell run-as "$package" \
    stat -c %a "$path" | tr -d '\r')"
  [[ "$actual_mode" == "$expected_mode" ]] ||
    archphene_die "unexpected mode for $path: $actual_mode (wanted $expected_mode)"
done
job_store_size="$(archphene_adb_run shell run-as "$package" \
  stat -c %s files/arch-root/var/lib/archphene/package-jobs.v1 | tr -d '\r')"
[[ "$job_store_size" == 11808 ]] ||
  archphene_die "unexpected package job store size: $job_store_size"
job_store_mode="$(archphene_adb_run shell run-as "$package" \
  stat -c %a files/arch-root/var/lib/archphene/package-jobs.v1 | tr -d '\r')"
[[ "$job_store_mode" == 600 ]] ||
  archphene_die "unexpected package job store mode: $job_store_mode"
package_alias="$(archphene_adb_run shell run-as "$package" \
  readlink files/arch-root/run/package-runtime-v1/libalpm.so.16 | tr -d '\r')"
[[ "$package_alias" == */libarchphene_pkg_*.so ]] ||
  archphene_die "libalpm package-runtime alias is invalid: $package_alias"

initial_accelerometer_rotation="$(archphene_adb_run shell settings get system \
  accelerometer_rotation | tr -d '\r')"
initial_user_rotation="$(archphene_adb_run shell settings get system \
  user_rotation | tr -d '\r')"
[[ "$initial_accelerometer_rotation" =~ ^[01]$ ]] ||
  archphene_die "unexpected accelerometer rotation setting: $initial_accelerometer_rotation"
[[ "$initial_user_rotation" =~ ^[0-3]$ ]] ||
  archphene_die "unexpected user rotation setting: $initial_user_rotation"
if [[ "$initial_user_rotation" == 0 || "$initial_user_rotation" == 2 ]]; then
  test_rotation=1
else
  test_rotation=0
fi
rotation_changed=true
archphene_adb_run shell settings put system accelerometer_rotation 0 >/dev/null
archphene_adb_run shell settings put system user_rotation "$test_rotation" >/dev/null
archphene_wait_ui 'Rust runtime [0-9]+.*state 2.*Root ready.*jobs ready.*Pacman ready.*events [0-9]+' \
  "archphene-base-rotation-$serial" 15
rotation_generation="$(python3 -c '
import re, sys
match = re.search(r"text=\"Rust runtime ([0-9]+)", sys.stdin.read())
if match is None:
    raise SystemExit("missing rotated runtime generation")
print(match.group(1))
' <<<"$ARCHPHENE_UI")"
rotation_pid="$(archphene_android_pid "$package")"
[[ "$rotation_pid" == "$first_pid" ]] ||
  archphene_die "process changed across Activity recreation: $first_pid -> $rotation_pid"
[[ "$rotation_generation" == "$first_generation" ]] ||
  archphene_die \
    "runtime changed across Activity recreation: $first_generation -> $rotation_generation"
archphene_adb_run shell settings put system accelerometer_rotation \
  "$initial_accelerometer_rotation" >/dev/null
archphene_adb_run shell settings put system user_rotation "$initial_user_rotation" >/dev/null
rotation_changed=false
archphene_wait_ui 'Rust runtime [0-9]+.*state 2.*Root ready.*jobs ready.*Pacman ready.*events [0-9]+' \
  "archphene-base-rotation-restored-$serial" 15

archphene_wait_ui 'text="Linux session display"' \
  "archphene-base-input-surface-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Linux session display"' 'runtime input surface'
archphene_wait_ui 'Rust runtime [0-9]+.*state 2.*Root ready.*jobs ready.*Pacman ready.*events [1-9][0-9]*' \
  "archphene-base-input-$serial" 15

archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
python3 -c '
import struct, sys
data = open(sys.argv[1], "rb").read(24)
if data[:8] != b"\x89PNG\r\n\x1a\n":
    raise SystemExit("device screenshot is not PNG")
width, height = struct.unpack(">II", data[16:24])
if width < 320 or height < 320:
    raise SystemExit(f"device screenshot is unexpectedly small: {width}x{height}")
' "$output_dir/$serial.png"

archphene_adb_run shell input keyevent KEYCODE_HOME >/dev/null
sleep 0.5
resume="$(archphene_adb_run shell am start -W -n "$activity" | tr -d '\r')"
[[ "$resume" == *"Status: ok"* ]] || archphene_die "base app did not resume: $resume"
archphene_wait_ui 'Rust runtime [0-9]+.*state 2.*Root ready.*jobs ready.*Pacman ready.*events [1-9][0-9]*' \
  "archphene-base-resume-$serial" 15
second_generation="$(python3 -c '
import re, sys
match = re.search(r"text=\"Rust runtime ([0-9]+)", sys.stdin.read())
if match is None:
    raise SystemExit("missing resumed runtime generation")
print(match.group(1))
' <<<"$ARCHPHENE_UI")"
second_pid="$(archphene_android_pid "$package")"
[[ "$second_pid" == "$first_pid" ]] ||
  archphene_die "process changed across HOME/resume: $first_pid -> $second_pid"
[[ "$second_generation" == "$first_generation" ]] ||
  archphene_die "runtime changed across HOME/resume: $first_generation -> $second_generation"

archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_log 'Shared Rust runtime stopped' 15 >/dev/null
restart="$(archphene_adb_run shell am start -W -n "$activity" | tr -d '\r')"
[[ "$restart" == *"Status: ok"* ]] ||
  archphene_die "base app did not restart for root reuse: $restart"
archphene_wait_log 'root directories created=0' 15 >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui 'Rust runtime [0-9]+.*state 2.*Root ready.*jobs ready.*Pacman ready.*events [0-9]+' \
  "archphene-base-root-reuse-$serial" 15
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_log 'Shared Rust runtime stopped' 15 >/dev/null

fatal_log="$(archphene_adb_run logcat -d -v brief -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "base app emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene Rust/Kotlin base passed on $serial"
archphene_note "  PID remained $first_pid across recreation and HOME/resume"
archphene_note "  Runtime generation remained $first_generation"
archphene_note "  Private root, durable jobs, pacman runtime, and restart reuse passed"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
