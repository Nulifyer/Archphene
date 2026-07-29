#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
manager=org.archphene.app.debug
wrapper=org.archphene.linux.p9527868ff38b4f4ea2dd06c0af905874
output_dir="$ARCHPHENE_ROOT/tooling/build/foot-crash"
device_screenshot="/sdcard/archphene-foot-crash-${serial//[^a-zA-Z0-9]/-}-$$.png"
mkdir -p "$output_dir"

manager_initially_running=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_initially_running=true
fi

cleanup() {
  archphene_adb_run shell am force-stop "$wrapper" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_screenshot" >/dev/null 2>&1 || true
  if [[ "$manager_initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

process_tree() {
  archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS | tr -d '\r'
}

wait_process_absent() {
  local pid="$1" deadline=$((SECONDS + 10))
  while ((SECONDS < deadline)); do
    if ! archphene_adb_run shell test -e "/proc/$pid"; then
      return 0
    fi
    sleep 0.2
  done
  archphene_die "Linux process $pid survived the Foot crash"
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "manager $manager is not installed"
archphene_adb_run shell pm path "$wrapper" >/dev/null ||
  archphene_die "$wrapper is not installed; pass --install-apk with --apk"
archphene_adb_run shell run-as "$manager" test -x files/arch-root/usr/bin/foot ||
  archphene_die "Foot is not installed in the shared Arch root"
if archphene_android_pid "$wrapper" >/dev/null 2>&1; then
  archphene_die "the Foot wrapper is already running; close it before this gate"
fi
preflight_tree="$(process_tree)"
if grep -qE -- '--argv0 (foot|bash) ' <<<"$preflight_tree"; then
  archphene_die "a Linux Foot or Bash session already exists; close it before this gate"
fi
activity="$(archphene_launcher "$wrapper")"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$wrapper" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Presented Linux frame session=.*attachmentFrame=1' 20 \
  'ArchpheneLauncherSession:V *:S' >/dev/null

tree="$(process_tree)"
manager_pid="$(archphene_android_pid "$manager")"
wrapper_pid="$(archphene_android_pid "$wrapper")"
foot_pid="$(awk '/--argv0 foot / {print $1; exit}' <<<"$tree")"
[[ "$foot_pid" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "could not identify the manager-owned Foot leader"
foot_pgid="$(awk -v pid="$foot_pid" '$1 == pid {print $3; exit}' <<<"$tree")"
bash_pid="$(awk -v parent="$foot_pid" \
  '$2 == parent && /--argv0 bash / {print $1; exit}' <<<"$tree")"
[[ "$bash_pid" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "could not identify Foot's Bash descendant"
bash_pgid="$(awk -v pid="$bash_pid" '$1 == pid {print $3; exit}' <<<"$tree")"
[[ "$foot_pgid" =~ ^[1-9][0-9]*$ &&
   "$bash_pgid" =~ ^[1-9][0-9]*$ &&
   "$foot_pgid" != "$bash_pgid" ]] ||
  archphene_die "Foot's Bash descendant did not own a separate process group"
[[ "$manager_pid" =~ ^[1-9][0-9]*$ && "$wrapper_pid" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "could not identify the Android manager and Foot wrapper"

archphene_adb_run logcat -c
archphene_adb_run shell input keyevent KEYCODE_HOME >/dev/null
archphene_wait_log 'Detached launcher Surface session=' 15 \
  'ArchpheneLauncherSession:V *:S' >/dev/null
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-home.png"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Attached launcher Surface session=' 15 \
  'ArchpheneLauncherSession:V *:S' >/dev/null
archphene_wait_log 'Presented Linux frame session=.*attachmentFrame=1' 20 \
  'ArchpheneLauncherSession:V *:S' >/dev/null
[[ "$(archphene_android_pid "$manager")" == "$manager_pid" &&
   "$(archphene_android_pid "$wrapper")" == "$wrapper_pid" ]] ||
  archphene_die "Android manager or wrapper restarted across Home/resume"
resumed_tree="$(process_tree)"
for pid in "$foot_pid" "$bash_pid"; do
  awk -v pid="$pid" '$1 == pid { found = 1 } END { exit !found }' <<<"$resumed_tree" ||
    archphene_die "Linux process $pid restarted across Home/resume"
done
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-resumed.png"

archphene_adb_run shell run-as "$manager" kill -9 "$foot_pid"
archphene_wait_log 'Linux process exited session=.*status=-9' 15 \
  'ArchpheneLauncherSession:V *:S' >/dev/null
archphene_wait_ui 'text="Foot stopped \(exit -9\)\."' \
  "foot-crash-status-$serial" 15
wait_process_absent "$foot_pid"
wait_process_absent "$bash_pid"
sleep 2
stopped_tree="$(process_tree)"
if grep -qE -- '--argv0 (foot|bash) ' <<<"$stopped_tree"; then
  archphene_die "a Surface change relaunched the stopped Linux client"
fi

archphene_adb_run shell screencap -p "$device_screenshot"
archphene_adb_run pull "$device_screenshot" \
  "$output_dir/$serial.png" >/dev/null
archphene_adb_run shell rm "$device_screenshot"

archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Presented Linux frame session=.*attachmentFrame=1' 20 \
  'ArchpheneLauncherSession:V *:S' >/dev/null
restarted_tree="$(process_tree)"
grep -q -- '--argv0 foot ' <<<"$restarted_tree" ||
  archphene_die "Foot did not relaunch after its crash"
grep -q -- '--argv0 bash ' <<<"$restarted_tree" ||
  archphene_die "Foot's Bash child did not relaunch after the crash"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Foot crash regression emitted a fatal Android error: $fatal_log"

archphene_note "Archphene Foot crash regression passed on $serial"
archphene_note "  Manager, wrapper, Foot, and Bash survived Home/resume unchanged"
archphene_note "  Exit -9 was visible and the separate Bash descendant was reaped"
archphene_note "  Foot relaunched and presented a fresh real Wayland frame"
archphene_note "  Full-device screenshots: $output_dir/$serial-{home,resumed}.png and $output_dir/$serial.png"
