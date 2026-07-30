#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archphene.app.debug
package=org.archphene.linux.p46204b29816e2006b6f4a02b6c452e56
program=seahorse
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --program) program="${2:?missing value for --program}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial EMULATOR] [--manager PACKAGE] [--package WRAPPER] [--program EXECUTABLE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ "$serial" == emulator-* ]] ||
  archphene_die "live display movement requires an Android emulator"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package is not a generated Archphene launcher"
[[ "$program" =~ ^[A-Za-z0-9@._+-]{1,128}$ ]] ||
  archphene_die "--program is invalid"

archphene_test_init "$serial"
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "launcher is not installed: $package"

initial_displays="$(archphene_adb_run shell cmd display get-displays -i | tr -d '\r')"
[[ "$initial_displays" == 0 ]] ||
  archphene_die "remove existing emulator secondary displays before this test"
if archphene_android_pid "$package" >/dev/null 2>&1; then
  archphene_die "refusing to replace an active $program session"
fi

activity="$(archphene_launcher "$package")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$serial_slug/live-display-explicit-scale"
ui_path=/sdcard/archphene-live-display-scale.xml
manager_was_running=false
original_scale=
original_section=
original_focus="$(
  archphene_adb_run shell dumpsys window |
    sed -n 's/.*mCurrentFocus=.* u0 \([^} ]*\/[^} ]*\)}.*/\1/p' |
    head -1 |
    tr -d '\r'
)"
external_display=
surface_display=
manager_pid=
wrapper_pid=
linux_pid=
task_id=
fatal_logs=
mkdir -p "$artifact_dir"

if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_was_running=true
fi

read_preference() {
  local key="$1" preferences
  preferences="$(
    archphene_adb_run shell run-as "$manager" \
      cat shared_prefs/linux_appearance.xml 2>/dev/null || true
  )"
  python3 -c '
import sys
import xml.etree.ElementTree as ET

key = sys.argv[1]
text = sys.stdin.read().strip()
if not text:
    print(0)
    raise SystemExit
node = next(
    (item for item in ET.fromstring(text) if item.attrib.get("name") == key),
    None,
)
print(node.attrib.get("value", "0") if node is not None else "0")
' "$key" <<<"$preferences"
}

read_section() {
  local preferences
  preferences="$(
    archphene_adb_run shell run-as "$manager" \
      cat shared_prefs/manager_navigation.xml 2>/dev/null || true
  )"
  python3 -c '
import sys
import xml.etree.ElementTree as ET

labels = ("Packages", "Files", "Terminal", "Settings")
text = sys.stdin.read().strip()
value = 0
if text:
    node = next(
        (item for item in ET.fromstring(text)
         if item.attrib.get("name") == "selected_section"),
        None,
    )
    if node is not None:
        value = int(node.attrib.get("value", "0"))
print(labels[value] if 0 <= value < len(labels) else "Packages")
' <<<"$preferences"
}

scale_index() {
  case "$1" in
    0) echo 0 ;;
    75) echo 1 ;;
    100) echo 2 ;;
    125) echo 3 ;;
    150) echo 4 ;;
    *) archphene_die "unsupported App scale value: $1" ;;
  esac
}

scale_slider_action() {
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET

index = int(sys.argv[1])
root = ET.fromstring(sys.stdin.read())
node = next(
    (item for item in root.iter("node")
     if item.attrib.get("class") == "android.widget.SeekBar"
     and item.attrib.get("content-desc", "").startswith("App scale, ")),
    None,
)
if node is None:
    raise SystemExit("App scale slider is missing")
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
if match is None:
    raise SystemExit("invalid App scale bounds")
left, top, right, bottom = map(int, match.groups())
x = round(left + (right - left) * (0.03 + 0.94 * index / 4))
print(x, (top + bottom) // 2)
' "$1"
}

open_settings() {
  archphene_adb_run shell am start -W \
    -n "$manager/org.archphene.app.MainActivity" >/dev/null
  archphene_wait_ui 'text="Packages"' "display-scale-manager-$serial_slug" 20
  archphene_open_manager_section Settings "display-scale-settings-$serial_slug"
  archphene_wait_ui 'content-desc="App scale, [^"]+"' \
    "display-scale-slider-$serial_slug" 15
}

set_scale() {
  local value="$1" index label x y deadline
  index="$(scale_index "$value")"
  case "$value" in
    0) label=Auto ;;
    *) label="$value%" ;;
  esac
  open_settings
  read -r x y <<<"$(scale_slider_action "$index" <<<"$ARCHPHENE_UI")"
  archphene_adb_run shell input tap "$x" "$y" >/dev/null
  archphene_wait_ui "content-desc=\"App scale, $label\"" \
    "display-scale-value-$serial_slug-$value" 10
  deadline=$((SECONDS + 10))
  while ((SECONDS < deadline)); do
    [[ "$(read_preference geometry_percent)" == "$value" ]] && return 0
    sleep 0.2
  done
  archphene_die "App scale did not persist $label"
}

find_linux_pid() {
  local escaped_program pid
  escaped_program="${program//./\\.}"
  pid="$(
    archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
      tr -d '\r' |
      awk -v program="$escaped_program" '
        $4 ~ /^libarchphene_(pkg_[0-9a-f]+|ld)\.so$/ &&
        $0 ~ ("--argv0 " program " ") {
          print $1
          exit
        }
      '
  )"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  printf '%s\n' "$pid"
}

wait_linux_pid() {
  local deadline=$((SECONDS + 45)) pid
  while ((SECONDS < deadline)); do
    pid="$(find_linux_pid || true)"
    if [[ "$pid" =~ ^[1-9][0-9]*$ ]]; then
      printf '%s\n' "$pid"
      return 0
    fi
    sleep 0.3
  done
  return 1
}

find_task_id() {
  archphene_adb_run shell dumpsys activity activities |
    python3 -c '
import re
import sys

package = sys.argv[1]
text = sys.stdin.read()
match = re.search(
    r"\* Task\{[^\n]*#(\d+)[^\n]*\bA=\d+:" + re.escape(package) + r"\b",
    text,
)
if match is None:
    raise SystemExit(1)
print(match.group(1))
' "$package"
}

assert_task_display() {
  local display="$1"
  archphene_adb_run shell dumpsys activity activities |
    python3 -c '
import re
import sys

display, package = sys.argv[1:3]
sections = re.split(r"(?=  Display: mDisplayId=)", sys.stdin.read())
if not any(
    re.search(rf"Display: mDisplayId={re.escape(display)}\b", section)
    and package in section
    for section in sections
):
    raise SystemExit(f"{package} is not hosted on display {display}")
' "$display" "$package"
}

assert_processes() {
  [[ "$(archphene_android_pid "$manager")" == "$manager_pid" ]] ||
    archphene_die "manager restarted during live display movement"
  [[ "$(archphene_android_pid "$package")" == "$wrapper_pid" ]] ||
    archphene_die "$program wrapper restarted during live display movement"
  [[ "$(find_linux_pid || true)" == "$linux_pid" ]] ||
    archphene_die "$program restarted during live display movement"
}

collect_fatal_logs() {
  local found
  found="$(
    archphene_adb_run logcat -d -v brief |
      grep -E \
        'FATAL EXCEPTION|AndroidRuntime.*Process: org\.archphene|[EF]/ArchpheneLauncherSession' ||
      true
  )"
  if [[ -n "$found" ]]; then
    fatal_logs+="${fatal_logs:+$'\n'}$found"
  fi
}

restore() {
  local status=$?
  set +e
  if [[ -n "$task_id" ]]; then
    archphene_adb_run shell am display move-stack "$task_id" 0 \
      >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ -n "$external_display" ]]; then
    archphene_adb_run emu multidisplay del 1 >/dev/null 2>&1 || true
  fi
  if [[ -n "$original_scale" ]]; then
    set_scale "$original_scale" >/dev/null 2>&1
  fi
  if [[ -n "$original_section" ]]; then
    archphene_open_manager_section "$original_section" \
      "display-scale-restore-section-$serial_slug" >/dev/null 2>&1
  fi
  archphene_adb_run shell rm -f "$ui_path" >/dev/null 2>&1
  if [[ "$manager_was_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1
  fi
  if [[ -n "$original_focus" ]]; then
    archphene_adb_run shell am start -n "$original_focus" >/dev/null 2>&1 || true
  fi
  return "$status"
}
trap restore EXIT

original_scale="$(read_preference geometry_percent)"
original_section="$(read_section)"
set_scale 125

archphene_adb_run logcat -c
archphene_adb_run shell am start --display 0 -W -n "$activity" >/dev/null
archphene_wait_log \
  'Resolved launcher appearance session=[0-9]+ geometry=125' 30 \
  'ArchpheneLauncherSession:I *:S' >/dev/null
archphene_wait_log \
  'Presented Linux frame.*surface=1080x[0-9]+.*logical=346x[0-9]+.*output=346x[0-9]+.*pending=0' 60 \
  'ArchpheneLauncherSession:I *:S' >/dev/null
linux_pid="$(wait_linux_pid)" ||
  archphene_die "$program Linux process did not start"
manager_pid="$(archphene_android_pid "$manager")"
wrapper_pid="$(archphene_android_pid "$package")"
task_id="$(find_task_id)" ||
  archphene_die "could not identify the $program Android task"
assert_task_display 0
sleep 2
archphene_adb_run exec-out screencap -p \
  >"$artifact_dir/phone-125-percent.png"

archphene_adb_run emu multidisplay add 1 1920 1080 240 0 >/dev/null
sleep 3
external_display="$(
  archphene_adb_run shell cmd display get-displays -i |
    tr -d '\r' |
    awk '$1 != 0 {print; exit}'
)"
[[ "$external_display" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "emulator secondary display was not created"
display_deadline=$((SECONDS + 20))
while ((SECONDS < display_deadline)); do
  surface_display="$(
    archphene_adb_run shell dumpsys SurfaceFlinger --display-id |
      awk '/Virtual display.*Emulator 2D Display/{print $2; exit}'
  )"
  [[ "$surface_display" =~ ^[0-9]+$ ]] && break
  sleep 0.5
done
[[ "$surface_display" =~ ^[0-9]+$ ]] ||
  archphene_die "secondary SurfaceFlinger display was not found"

collect_fatal_logs
archphene_adb_run logcat -c
archphene_adb_run shell am display move-stack "$task_id" "$external_display" \
  >/dev/null
display_deadline=$((SECONDS + 30))
while ((SECONDS < display_deadline)); do
  if assert_task_display "$external_display" 2>/dev/null; then
    break
  fi
  sleep 0.3
done
assert_task_display "$external_display"
archphene_wait_log 'Attached launcher Surface.*size=1920x[0-9]+' 30 \
  'ArchpheneLauncherSession:I *:S' >/dev/null
archphene_wait_log \
  'Presented Linux frame.*surface=1920x[0-9]+.*logical=1024x[0-9]+.*output=1024x[0-9]+.*pending=0' 45 \
  'ArchpheneLauncherSession:I *:S' >/dev/null
assert_processes
archphene_adb_run exec-out screencap -d "$surface_display" -p \
  >"$artifact_dir/external-1920x1080-125-percent.png"

collect_fatal_logs
archphene_adb_run logcat -c
archphene_adb_run shell am display move-stack "$task_id" 0 >/dev/null
display_deadline=$((SECONDS + 30))
while ((SECONDS < display_deadline)); do
  if assert_task_display 0 2>/dev/null; then
    break
  fi
  sleep 0.3
done
assert_task_display 0
archphene_wait_log 'Attached launcher Surface.*size=1080x[0-9]+' 30 \
  'ArchpheneLauncherSession:I *:S' >/dev/null
archphene_wait_log \
  'Presented Linux frame.*surface=1080x[0-9]+.*logical=346x[0-9]+.*output=346x[0-9]+.*pending=0' 45 \
  'ArchpheneLauncherSession:I *:S' >/dev/null
assert_processes
archphene_adb_run exec-out screencap -p \
  >"$artifact_dir/phone-return-125-percent.png"

collect_fatal_logs
[[ -z "$fatal_logs" ]] ||
  archphene_die "live display movement emitted a fatal event: $fatal_logs"

restore
trap - EXIT
[[ "$(read_preference geometry_percent)" == "$original_scale" ]] ||
  archphene_die "App scale was not restored"
[[ "$(archphene_adb_run shell cmd display get-displays -i | tr -d '\r')" == 0 ]] ||
  archphene_die "secondary display was not removed"

archphene_note \
  "Explicit-scale live display movement passed on $serial: task $task_id and manager/wrapper/Linux PIDs $manager_pid/$wrapper_pid/$linux_pid moved display 0→$external_display→0 without restart; full-display evidence is in $artifact_dir."
