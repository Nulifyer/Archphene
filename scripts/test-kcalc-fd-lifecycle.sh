#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
cycles=6
package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --cycles) cycles="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--cycles N] [--package PACKAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die '--serial is required'
if [[ ! "$cycles" =~ ^[0-9]+$ ]] || ((cycles < 1)); then
  archphene_die '--cycles must be an integer of at least 1'
fi

archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
old_accelerometer="$(archphene_adb_run shell settings get system \
  accelerometer_rotation | tr -d '\r')"
old_rotation="$(archphene_adb_run shell settings get system \
  user_rotation | tr -d '\r')"
was_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  was_running=true
fi
tmp="$(archphene_mktemp_dir kcalc-fd-lifecycle)"
restore() {
  archphene_adb_run shell settings put system accelerometer_rotation \
    "$old_accelerometer" >/dev/null 2>&1 || true
  archphene_adb_run shell settings put system user_rotation \
    "$old_rotation" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$was_running" == true ]]; then
    archphene_adb_run shell am start -n "$activity" >/dev/null 2>&1 || true
  fi
}
trap restore EXIT

snapshot() {
  local pid="$1" targets total wayland sync_fences ashmem
  targets="$(archphene_adb_run shell run-as "$package" sh -c \
    "'for descriptor in /proc/$pid/fd/*; do readlink \$descriptor; done'" \
    2>/dev/null)" \
    || archphene_die 'could not inspect descriptors; install a debuggable build'
  total="$(sed '/^$/d' <<<"$targets" | wc -l)"
  wayland="$(grep -c '/memfd:wayland-shm' <<<"$targets" || true)"
  sync_fences="$(grep -c '^anon_inode:sync_file$' <<<"$targets" || true)"
  ashmem="$(grep -c '^/dev/ashmem' <<<"$targets" || true)"
  printf '%s|%s|%s|%s\n' "$total" "$wayland" "$sync_fences" "$ashmem"
}

archphene_adb_run shell input keyevent WAKEUP
archphene_adb_run shell wm dismiss-keyguard >/dev/null 2>&1 || true
archphene_adb_run shell settings put system accelerometer_rotation 0
archphene_adb_run shell settings put system user_rotation 0
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
sleep 8
android_pid="$(archphene_android_pid "$package")"
linux_pid="$(archphene_linux_loader_pid "$android_pid")"
[[ -n "$android_pid" && -n "$linux_pid" ]] \
  || archphene_die 'KCalc process tree is not running'
# Rotation causes Android, Qt, and the compositor to allocate a small stable
# set of configuration descriptors on first use. Establish the leak baseline
# after that one-time path, not before it has ever run.
archphene_adb_run shell settings put system user_rotation 1
sleep 2
archphene_adb_run shell settings put system user_rotation 0
sleep 5
warm_android="$(archphene_android_pid "$package")"
warm_linux="$(archphene_linux_loader_pid "$warm_android")"
[[ "$warm_android" == "$android_pid" && "$warm_linux" == "$linux_pid" ]] \
  || archphene_die 'KCalc restarted during descriptor-baseline warmup'
before="$(snapshot "$android_pid")"

for ((cycle = 1; cycle <= cycles; cycle++)); do
  archphene_adb_run shell settings put system user_rotation 1
  sleep .9
  archphene_adb_run shell settings put system user_rotation 0
  sleep .9
done
sleep 15

after_android="$(archphene_android_pid "$package")"
after_linux="$(archphene_linux_loader_pid "$after_android")"
[[ "$after_android" == "$android_pid" && "$after_linux" == "$linux_pid" ]] \
  || archphene_die "KCalc restarted during rotation: Android $android_pid/$after_android, Linux $linux_pid/$after_linux"
after="$(snapshot "$android_pid")"
IFS='|' read -r before_total before_wayland before_sync before_ashmem \
  <<<"$before"
IFS='|' read -r after_total after_wayland after_sync after_ashmem \
  <<<"$after"
((after_total <= before_total + 4)) \
  || archphene_die "total descriptors leaked: $before_total -> $after_total"
((after_wayland <= before_wayland + 1)) \
  || archphene_die "Wayland SHM descriptors leaked: $before_wayland -> $after_wayland"
((after_sync <= before_sync + 3)) \
  || archphene_die "sync-fence descriptors leaked: $before_sync -> $after_sync"
((after_ashmem <= before_ashmem + 1)) \
  || archphene_die "ashmem descriptors leaked: $before_ashmem -> $after_ashmem"

archphene_adb_run exec-out screencap >"$tmp/final.raw"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" inspect \
  "$tmp/final.raw" >/dev/null
ui="$(archphene_capture_ui kcalc-fd-lifecycle-final)"
[[ "$ui" == *'class="android.widget.ImageView"'* ]] \
  || archphene_die 'KCalc viewport is missing after rotation cycles'
log="$(archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
! archphene_regex_contains "$log" \
  'FATAL EXCEPTION|Runtime GUI exit=(?!0)|protocol error|InvalidGrab|UnconfiguredBuffer|native dispatch failed' \
  || archphene_die 'rotation lifecycle produced a runtime or compositor failure'

restore
trap - EXIT
archphene_note "KCalc FD lifecycle passed on $serial after $cycles cycles with stable Android PID $android_pid and Linux PID $linux_pid. Total $before_total->$after_total; Wayland SHM $before_wayland->$after_wayland; sync fences $before_sync->$after_sync; ashmem $before_ashmem->$after_ashmem; rendered viewport and prior running state restored."
