#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archphene.app.debug
wrapper=org.archphene.linux.p46204b29816e2006b6f4a02b6c452e56
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --wrapper) wrapper="${2:?missing value for --wrapper}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--manager PACKAGE] [--wrapper PACKAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
archphene_adb_run shell pm path "$manager" >/dev/null \
  || archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$wrapper" >/dev/null \
  || archphene_die "Seahorse wrapper is not installed: $wrapper"

manager_running=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_running=true
fi
if archphene_android_pid "$wrapper" >/dev/null 2>&1; then
  archphene_die "Seahorse must be stopped before the state-preserving appearance gate"
fi

temporary="$(archphene_mktemp_dir current-seahorse-appearance)"
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/${serial//[^A-Za-z0-9_.-]/_}/seahorse-appearance-current"
ui_path=/sdcard/archphene-seahorse-appearance.xml
mkdir -p "$artifact_dir"

read_appearance() {
  local preferences
  preferences="$(
    archphene_adb_run shell run-as "$manager" \
      cat shared_prefs/linux_appearance.xml 2>/dev/null || true
  )"
  python3 -c '
import sys
import xml.etree.ElementTree as ET

text = sys.stdin.read().strip()
if not text:
    print("0 true")
    raise SystemExit
root = ET.fromstring(text)
theme = next((node for node in root if node.attrib.get("name") == "theme_mode"), None)
material = next((node for node in root if node.attrib.get("name") == "material_you"), None)
print(
    theme.attrib.get("value", "0") if theme is not None else "0",
    material.attrib.get("value", "true") if material is not None else "true",
)
' <<<"$preferences"
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

dump_ui() {
  archphene_adb_run shell uiautomator dump "$ui_path" >/dev/null 2>&1
  archphene_adb_run exec-out cat "$ui_path"
}

node_action() {
  local kind="$1" value="$2" progress="${3:-}"
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET

kind, value, progress = sys.argv[1:4]
root = ET.fromstring(sys.stdin.read())
node = None
for candidate in root.iter("node"):
    if kind == "text" and candidate.attrib.get("text") == value:
        node = candidate
        break
    if kind == "description" and candidate.attrib.get("content-desc", "").startswith(value):
        node = candidate
        break
if node is None:
    raise SystemExit(1)
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
if match is None:
    raise SystemExit(1)
left, top, right, bottom = map(int, match.groups())
if progress:
    position = int(progress)
    x = round(left + (right - left) * (0.03 + position * 0.47))
else:
    x = (left + right) // 2
print(x, (top + bottom) // 2, node.attrib.get("checked", "false"))
' "$kind" "$value" "$progress"
}

open_settings() {
  local ui action
  archphene_adb_run shell am start -W \
    -n "$manager/org.archphene.app.MainActivity" >/dev/null
  for _ in {1..12}; do
    sleep 0.4
    ui="$(dump_ui)"
    if [[ "$ui" == *'content-desc="Color scheme,'* ]]; then
      printf '%s' "$ui"
      return 0
    fi
    action="$(node_action text Settings <<<"$ui" 2>/dev/null || true)"
    if [[ -n "$action" ]]; then
      read -r x y _ <<<"$action"
      archphene_adb_run shell input tap "$x" "$y"
    fi
  done
  archphene_die "Linux appearance settings did not become visible"
}

set_theme() {
  local value="$1" ui action x y
  ui="$(open_settings)"
  action="$(node_action description "Color scheme," "$value" <<<"$ui")" \
    || archphene_die "Color scheme slider is missing"
  read -r x y _ <<<"$action"
  archphene_adb_run shell input tap "$x" "$y"
}

set_material() {
  local desired="$1" ui action x y checked
  ui="$(open_settings)"
  action="$(node_action text "Material You colors" <<<"$ui")" \
    || archphene_die "Material You switch is missing"
  read -r x y checked <<<"$action"
  if [[ "$checked" != "$desired" ]]; then
    archphene_adb_run shell input tap "$x" "$y"
  fi
}

wait_appearance_preference() {
  local expected_theme="$1" expected_material="$2"
  local deadline=$((SECONDS + 10)) theme material
  while ((SECONDS < deadline)); do
    read -r theme material <<<"$(read_appearance)"
    if [[ "$theme" == "$expected_theme" &&
        "$material" == "$expected_material" ]]; then
      return 0
    fi
    sleep 0.2
  done
  archphene_die \
    "appearance preference did not persist theme=$expected_theme materialYou=$expected_material"
}

wait_gtk4_state() {
  local expected="$1" deadline=$((SECONDS + 20)) settings diagnostic
  while ((SECONDS < deadline)); do
    settings="$(
      archphene_adb_run shell run-as "$manager" \
        cat files/arch-root/home/archphene/.config/gtk-4.0/settings.ini \
        2>/dev/null || true
    )"
    diagnostic="$(
      archphene_adb_run shell run-as "$manager" \
        cat files/arch-root/home/archphene/.cache/archphene-gtk-settings.log \
        2>/dev/null || true
    )"
    if [[ "$settings" == *"gtk-application-prefer-dark-theme=$expected"* &&
        "$diagnostic" == *"dark=$expected"* ]]; then
      return 0
    fi
    sleep 0.2
  done
  archphene_die "GTK 4 live bridge did not apply dark=$expected"
}

wait_css_accent() {
  local expected="$1" deadline=$((SECONDS + 20)) css
  while ((SECONDS < deadline)); do
    css="$(
      archphene_adb_run exec-out run-as "$manager" \
        cat files/arch-root/home/archphene/.config/gtk-4.0/gtk.css \
        2>/dev/null |
        sed -n '1p' || true
    )"
    if [[ "$css" == "@define-color accent_color $expected;" ]]; then
      return 0
    fi
    sleep 0.2
  done
  archphene_die "GTK 4 bridge did not publish accent $expected"
}

read_css_accent() {
  archphene_adb_run exec-out run-as "$manager" \
    cat files/arch-root/home/archphene/.config/gtk-4.0/gtk.css |
    sed -n 's/^@define-color accent_color \(#[0-9a-fA-F]*\);/\1/p'
}

find_seahorse_pid() {
  local manager_pid processes candidate
  manager_pid="$(archphene_android_pid "$manager" || true)"
  [[ "$manager_pid" =~ ^[1-9][0-9]*$ ]] || return 1
  processes="$(archphene_adb_run shell ps -A -o PID,PPID | tr -d '\r')"
  while read -r candidate; do
    [[ "$candidate" =~ ^[1-9][0-9]*$ ]] || continue
    if archphene_adb_run exec-out run-as "$manager" \
        cat "/proc/$candidate/environ" 2>/dev/null |
        tr '\0' '\n' |
        grep -Eq '^ARCHPHENE_RUNTIME_PROGRAM_PATH=.*/usr/bin/seahorse$'; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(
    awk -v root="$manager_pid" '
      NR > 1 { parent[$1] = $2 }
      END {
        descendant[root] = 1
        for (round = 0; round < 64; round++) {
          changed = 0
          for (pid in parent) {
            if (!descendant[pid] && descendant[parent[pid]]) {
              descendant[pid] = 1
              changed = 1
            }
          }
          if (!changed) break
        }
        for (pid in descendant) {
          if (pid != root && descendant[pid]) print pid
        }
      }
    ' <<<"$processes"
  )
  return 1
}

wait_seahorse_pid() {
  local deadline=$((SECONDS + 30)) pid
  while ((SECONDS < deadline)); do
    pid="$(find_seahorse_pid || true)"
    if [[ "$pid" =~ ^[1-9][0-9]*$ ]]; then
      printf '%s\n' "$pid"
      return 0
    fi
    sleep 0.3
  done
  return 1
}

resume_wrapper() {
  local activity
  activity="$(archphene_launcher "$wrapper")"
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  sleep 1
}

assert_pids() {
  local manager_now wrapper_now linux_now
  manager_now="$(archphene_android_pid "$manager")"
  wrapper_now="$(archphene_android_pid "$wrapper")"
  linux_now="$(find_seahorse_pid || true)"
  [[ "$manager_now" == "$manager_pid" && "$wrapper_now" == "$wrapper_pid" ]] \
    || archphene_die "appearance update restarted manager or Seahorse wrapper"
  [[ "$linux_now" == "$linux_pid" ]] \
    || archphene_die "appearance update restarted Seahorse"
}

capture_stable_frame() {
  local destination="$1" previous="$temporary/previous.raw"
  local current="$temporary/current.raw" attempt
  archphene_adb_run exec-out screencap >"$previous"
  for attempt in {1..30}; do
    sleep 1
    archphene_adb_run exec-out screencap >"$current"
    if python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" similar \
        "$previous" "$current" >/dev/null 2>&1; then
      python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" inspect \
        "$current" --top-percent 4 --bottom-percent 20 >/dev/null
      archphene_adb_run exec-out screencap -p >"$destination"
      return 0
    fi
    cp "$current" "$previous"
  done
  archphene_die "Seahorse render did not stabilize"
}

capture_semantics() {
  local destination="$1" ui root_only=
  for _ in {1..20}; do
    ui="$(dump_ui)"
    if [[ "$ui" != *"package=\"$wrapper\""* ]]; then
      sleep 0.25
      continue
    fi
    if [[ "$ui" == *'text="Passwords and Keys"'* ||
        "$ui" == *'text="GnuPG keys"'* ||
        "$ui" == *'text="Add new items"'* ]]; then
      semantic_mode=content
      printf '%s\n' "$ui" >"$destination"
      return 0
    fi
    if [[ "$ui" != *'text="Passwords and Keys"'* &&
        "$ui" != *'text="GnuPG keys"'* ]]; then
      root_only="$ui"
    fi
    sleep 0.25
  done
  if [[ -n "$root_only" && "$semantic_mode" != content ]]; then
    printf '%s\n' "$root_only" >"$destination"
    return 0
  fi
  if [[ "$semantic_mode" == content ]]; then
    archphene_die "Seahorse Android semantic tree disappeared during the gate"
  fi
  archphene_die "Seahorse Android semantic tree remained partial"
}

restore_section() {
  local ui action x y
  [[ -n "${original_section:-}" ]] || return 0
  archphene_adb_run shell am start -W \
    -n "$manager/org.archphene.app.MainActivity" >/dev/null
  sleep 0.5
  ui="$(dump_ui)"
  action="$(node_action text "$original_section" <<<"$ui" 2>/dev/null || true)"
  if [[ -n "$action" ]]; then
    read -r x y _ <<<"$action"
    archphene_adb_run shell input tap "$x" "$y"
  fi
}

read -r original_theme original_material <<<"$(read_appearance)"
original_section="$(read_section)"
manager_pid=
wrapper_pid=
linux_pid=
semantic_mode=root-only

cleanup() {
  local status=$?
  set +e
  if [[ -n "${original_theme:-}" ]]; then
    set_theme "$original_theme" >/dev/null 2>&1
    set_material "$original_material" >/dev/null 2>&1
  fi
  archphene_adb_run shell am force-stop "$wrapper" >/dev/null 2>&1
  restore_section >/dev/null 2>&1
  if [[ "$manager_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1
  fi
  archphene_adb_run shell rm -f "$ui_path" >/dev/null 2>&1
  if [[ "$temporary" == "$ARCHPHENE_ROOT/tooling/build/current-seahorse-appearance."* ]]; then
    rm -rf -- "$temporary"
  fi
  trap - EXIT
  return "$status"
}
trap cleanup EXIT

set_material true
set_theme 1
wait_appearance_preference 1 true
archphene_adb_run logcat -c
resume_wrapper
manager_pid="$(archphene_android_pid "$manager")"
wrapper_pid="$(archphene_android_pid "$wrapper")"
linux_pid="$(wait_seahorse_pid)" \
  || archphene_die "Seahorse Linux process did not start"
wait_gtk4_state false
assert_pids
capture_stable_frame "$artifact_dir/light-full-device.png"
capture_semantics "$artifact_dir/light-accessibility.xml"

material_accent="$(read_css_accent)"
[[ "$material_accent" =~ ^#[0-9a-fA-F]{6}$ ]] \
  || archphene_die "light Material You GTK 4 accent is malformed"

set_theme 2
wait_appearance_preference 2 true
resume_wrapper
wait_gtk4_state true
dark_material_accent="$(read_css_accent)"
[[ "$dark_material_accent" =~ ^#[0-9a-fA-F]{6}$ ]] \
  || archphene_die "dark Material You GTK 4 accent is malformed"
assert_pids
capture_stable_frame "$artifact_dir/dark-full-device.png"
capture_semantics "$artifact_dir/dark-accessibility.xml"
python3 - \
  "$artifact_dir/light-full-device.png" \
  "$artifact_dir/dark-full-device.png" <<'PY'
from pathlib import Path
import sys

from PIL import Image, ImageChops, ImageStat

light_path, dark_path = map(Path, sys.argv[1:])
light = Image.open(light_path).convert("RGB")
dark = Image.open(dark_path).convert("RGB")
if light.size != dark.size:
    raise SystemExit("Seahorse light and dark frames have different dimensions")
width, height = light.size
box = (width * 5 // 100, height * 15 // 100,
       width * 95 // 100, height * 85 // 100)
light = light.crop(box)
dark = dark.crop(box)
light_luma = ImageStat.Stat(light.convert("L")).mean[0]
dark_luma = ImageStat.Stat(dark.convert("L")).mean[0]
difference = ImageChops.difference(light, dark).convert("L")
histogram = difference.histogram()
changed = sum(histogram[20:]) / (difference.width * difference.height)
if light_luma - dark_luma < 40 or changed < 0.20:
    raise SystemExit(
        "Seahorse pixels did not visibly follow Light/Dark: "
        f"light={light_luma:.1f} dark={dark_luma:.1f} changed={changed:.3f}"
    )
PY

set_material false
wait_appearance_preference 2 false
resume_wrapper
wait_css_accent '#56bcec'
assert_pids
capture_stable_frame "$artifact_dir/stable-accent-full-device.png"
capture_semantics "$artifact_dir/stable-accent-accessibility.xml"

set_material true
wait_appearance_preference 2 true
resume_wrapper
wait_css_accent "$dark_material_accent"
assert_pids

night_mode="$(
  archphene_adb_run shell cmd uimode night |
    sed -n 's/^Night mode: //p' |
    tr -d '\r'
)"
case "$night_mode" in
  yes) system_dark=true ;;
  no) system_dark=false ;;
  *) archphene_die "Android night mode is not an exact yes/no policy: $night_mode" ;;
esac
set_theme 0
wait_appearance_preference 0 true
resume_wrapper
wait_gtk4_state "$system_dark"
assert_pids

logs="$(
  archphene_adb_run logcat -d -v threadtime \
    -s ArchpheneLauncher:I ArchpheneLauncherSession:I AndroidRuntime:E '*:S'
)"
printf '%s\n' "$logs" >"$artifact_dir/logcat.txt"
[[ "$logs" != *"FATAL EXCEPTION"* &&
    "$logs" != *"Fatal signal"* &&
    "$logs" != *"ANR in $manager"* &&
    "$logs" != *"ANR in $wrapper"* ]] \
  || archphene_die "Seahorse appearance gate emitted a fatal Android failure"

python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" \
  --field "app=Seahorse" \
  --field "toolkit=GTK 4/libadwaita" \
  --field "managerPid=$manager_pid" \
  --field "wrapperPid=$wrapper_pid" \
  --field "linuxPid=$linux_pid" \
  --field "androidSemantics=$semantic_mode" \
  --artifact "$artifact_dir/light-full-device.png" \
  --artifact "$artifact_dir/dark-full-device.png" \
  --artifact "$artifact_dir/stable-accent-full-device.png" \
  --artifact "$artifact_dir/light-accessibility.xml" \
  --artifact "$artifact_dir/dark-accessibility.xml" \
  --artifact "$artifact_dir/stable-accent-accessibility.xml" \
  --artifact "$artifact_dir/logcat.txt"

cleanup
trap - EXIT
read -r restored_theme restored_material <<<"$(read_appearance)"
[[ "$restored_theme" == "$original_theme" &&
    "$restored_material" == "$original_material" ]] \
  || archphene_die "appearance preferences were not restored after the gate"
archphene_note \
  "Current Seahorse GTK 4 appearance passed on $serial: explicit Light/Dark, Auto, Material You/stable accent, unchanged manager/wrapper/Linux PIDs, Android UI-tree evidence ($semantic_mode), restored preferences, and full-device artifacts."
