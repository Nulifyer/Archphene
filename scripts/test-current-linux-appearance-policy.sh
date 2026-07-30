#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archphene.app.debug
wrapper=org.archphene.linux.pee4b7705a9f0d0ef0130cb119f25e6d4
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --manager) manager="${2:?}"; shift 2 ;;
    --wrapper) wrapper="${2:?}"; shift 2 ;;
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
  || archphene_die "GTK4 camera wrapper is not installed: $wrapper"

temporary="$(archphene_mktemp_dir current-appearance)"
ui_path=/sdcard/archphene-current-appearance.xml
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/current-appearance"
artifact_name="${serial//[^A-Za-z0-9_.-]/_}"
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
theme = next((node for node in root
              if node.attrib.get("name") == "theme_mode"), None)
material = next((node for node in root
                 if node.attrib.get("name") == "material_you"), None)
print(theme.attrib.get("value", "0") if theme is not None else "0",
      material.attrib.get("value", "true") if material is not None else "true")
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
print(x, (top + bottom) // 2,
      node.attrib.get("checked", "false"))
' "$kind" "$value" "$progress"
}

open_settings() {
  local ui action
  archphene_adb_run shell am start -W \
    -n "$manager/org.archphene.app.MainActivity" >/dev/null
  for _ in {1..10}; do
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

wait_gtk_dark() {
  local expected="$1" deadline=$((SECONDS + 15)) settings diagnostic
  while ((SECONDS < deadline)); do
    settings="$(
      archphene_adb_run shell run-as "$manager" \
        cat files/arch-root/home/archphene/.config/gtk-3.0/settings.ini \
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
  archphene_die "GTK live bridge did not apply dark=$expected"
}

wait_css_accent() {
  local expected="$1" deadline=$((SECONDS + 15)) css
  while ((SECONDS < deadline)); do
    css="$(
      archphene_adb_run shell run-as "$manager" \
        sed -n '1p' files/arch-root/home/archphene/.config/gtk-3.0/gtk.css \
        2>/dev/null || true
    )"
    if [[ "$css" == "@define-color accent_color $expected;" ]]; then
      return 0
    fi
    sleep 0.2
  done
  archphene_die "GTK live bridge did not publish accent $expected"
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
  linux_now="$(archphene_linux_loader_pid "$manager_now")"
  [[ "$manager_now" == "$manager_pid" && "$wrapper_now" == "$wrapper_pid" ]] \
    || archphene_die "appearance update restarted manager or wrapper"
  [[ "$linux_now" == "$linux_pid" ]] \
    || archphene_die "appearance update restarted the Linux process"
}

read -r original_theme original_material <<<"$(read_appearance)"
manager_pid=
wrapper_pid=
linux_pid=

cleanup() {
  set +e
  if [[ -n "${original_theme:-}" ]]; then
    set_theme "$original_theme" >/dev/null 2>&1
    set_material "$original_material" >/dev/null 2>&1
  fi
  archphene_adb_run shell rm -f "$ui_path" >/dev/null 2>&1
  if [[ "$temporary" == "$ARCHPHENE_ROOT/tooling/build/current-appearance."* ]]; then
    rm -rf -- "$temporary"
  fi
}
trap cleanup EXIT

camera_output="$(
  "$ARCHPHENE_SCRIPTS_DIR/test-current-camera-bridge.sh" \
    --serial "$serial" --manager "$manager" --wrapper "$wrapper"
)"
linux_pid="$(sed -n 's/.*Snapshot pid=\([0-9][0-9]*\).*/\1/p' <<<"$camera_output")"
[[ "$linux_pid" =~ ^[1-9][0-9]*$ ]] \
  || archphene_die "could not recover the current GTK4 Linux PID"
manager_pid="$(archphene_android_pid "$manager")"
wrapper_pid="$(archphene_android_pid "$wrapper")"
archphene_adb_run logcat -c

set_material true
set_theme 2
archphene_adb_run exec-out screencap -p \
  >"$artifact_dir/$artifact_name-manager-dark.png"
resume_wrapper
wait_gtk_dark true
assert_pids
archphene_adb_run exec-out screencap -p \
  >"$artifact_dir/$artifact_name-linux-dark.png"

set_theme 1
archphene_adb_run exec-out screencap -p \
  >"$artifact_dir/$artifact_name-manager-light.png"
resume_wrapper
wait_gtk_dark false
assert_pids
archphene_adb_run exec-out screencap -p \
  >"$artifact_dir/$artifact_name-linux-light.png"

material_accent="$(
  archphene_adb_run shell run-as "$manager" \
    cat files/arch-root/home/archphene/.config/gtk-3.0/gtk.css |
    sed -n 's/^@define-color accent_color \(#[0-9a-f]*\);/\1/p'
)"
[[ "$material_accent" =~ ^#[0-9a-f]{6}$ ]] \
  || archphene_die "Material You accent is malformed"
set_material false
resume_wrapper
wait_css_accent '#1793d1'
assert_pids
set_material true
resume_wrapper
wait_css_accent "$material_accent"
assert_pids

python3 - \
  "$artifact_dir/$artifact_name-linux-dark.png" \
  "$artifact_dir/$artifact_name-linux-light.png" <<'PY'
from pathlib import Path
import sys

from PIL import Image, ImageStat

dark_path, light_path = map(Path, sys.argv[1:])


def bars(path):
    image = Image.open(path).convert("RGB")
    width, height = image.size
    status = ImageStat.Stat(image.crop((0, 0, width, max(1, height // 40)))).mean
    navigation = ImageStat.Stat(
        image.crop((0, height - max(1, height // 60), width, height))
    ).mean
    return tuple(
        0.2126 * red + 0.7152 * green + 0.0722 * blue
        for red, green, blue in (status, navigation)
    )


dark = bars(dark_path)
light = bars(light_path)
for name, dark_value, light_value in zip(("status", "navigation"), dark, light):
    if dark_value >= 96 or light_value <= 160 or light_value - dark_value < 96:
        raise SystemExit(
            f"{name} bar did not follow explicit Linux appearance: "
            f"dark={dark_value:.1f} light={light_value:.1f}"
        )
PY

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
resume_wrapper
wait_gtk_dark "$system_dark"
assert_pids

logs="$(archphene_adb_run logcat -d -v threadtime)"
[[ "$logs" != *"FATAL EXCEPTION"* &&
    "$logs" != *"Fatal signal"* &&
    "$logs" != *"ANR in $manager"* &&
    "$logs" != *"ANR in $wrapper"* ]] \
  || archphene_die "appearance policy emitted a fatal Android failure"

cleanup
trap - EXIT
archphene_note \
  "Current Linux appearance policy passed on $serial: production Auto/Light/Dark and Material You controls, GTK live monitor, matching Android system bars, exact stable-palette fallback, unchanged manager/wrapper/Linux PIDs, and full-device captures."
archphene_note "Screenshots: $artifact_dir/$artifact_name-{manager,linux}-{dark,light}.png"
