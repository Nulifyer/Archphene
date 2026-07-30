#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
code_package=
manager=org.archphene.app.debug
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --code-package) code_package="${2:?missing value for --code-package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --code-package PACKAGE [--manager PACKAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$code_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--code-package is not a generated Archphene launcher"
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"

archphene_test_init "$serial"
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$code_package" >/dev/null ||
  archphene_die "Code launcher is not installed: $code_package"

fixture="$ARCHPHENE_ROOT/tests/fixtures/code-auto-theme-settings.json"
archphene_require_file "$fixture"
activity="$(archphene_launcher "$code_package")"
manager_activity="$manager/org.archphene.app.MainActivity"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$serial_slug/code-appearance-current"
temporary="$(archphene_mktemp_dir current-code-appearance)"
ui_path=/sdcard/archphene-current-code-appearance.xml
remote_fixture="/data/local/tmp/archphene-code-auto-theme-$serial_slug.json"
backup="files/arch-root/run/code-appearance-config-$serial_slug"
code_config=
config_inventory_before=
config_backed_up=false
manager_was_running=false
original_theme=
original_material=
original_section=
manager_pid=
wrapper_pid=
code_pid=

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

theme_slider_action() {
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET

position = int(sys.argv[1])
root = ET.fromstring(sys.stdin.read())
for node in root.iter("node"):
    description = node.attrib.get("content-desc", "")
    if not description.startswith("Color scheme, "):
        continue
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
    if bounds is None:
        raise SystemExit("invalid Color scheme bounds")
    left, top, right, bottom = map(int, bounds.groups())
    x = round(left + (right - left) * (0.03 + position * 0.47))
    print(x, (top + bottom) // 2)
    raise SystemExit
raise SystemExit("Color scheme slider is missing")
' "$1"
}

open_settings() {
  archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
  archphene_wait_ui 'text="Packages"' "code-appearance-manager-$serial_slug" 20
  archphene_open_manager_section Settings "code-appearance-settings-$serial_slug"
  archphene_wait_ui 'content-desc="Color scheme, [^"]+"' \
    "code-appearance-theme-$serial_slug" 15
}

set_theme() {
  local value="$1" label x y
  case "$value" in
    0) label=Auto ;;
    1) label=Light ;;
    2) label=Dark ;;
    *) archphene_die "invalid theme index: $value" ;;
  esac
  open_settings
  read -r x y <<<"$(theme_slider_action "$value" <<<"$ARCHPHENE_UI")"
  archphene_adb_run shell input tap "$x" "$y" >/dev/null
  archphene_wait_ui "content-desc=\"Color scheme, $label\"" \
    "code-appearance-theme-$serial_slug-$value" 10
  local deadline=$((SECONDS + 10)) theme material
  while ((SECONDS < deadline)); do
    read -r theme material <<<"$(read_appearance)"
    [[ "$theme" == "$value" ]] && return 0
    sleep 0.2
  done
  archphene_die "theme preference did not persist value=$value"
}

code_config_inventory() {
  archphene_adb_run shell \
    "run-as $manager sh -c 'cd \"$code_config\" && find . -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

find_code_pid() {
  local pid
  pid="$(
    archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
      tr -d '\r' |
      awk '
        $4 ~ /^libarchphene_(pkg_[0-9a-f]+|ld)\.so$/ &&
        ($0 ~ /--argv0 (code|code-oss) / ||
         $0 ~ /\/usr\/lib\/code\/code\.mjs/ ||
         $0 ~ /\/opt\/visual-studio-code\//) {
          print $1
          exit
        }
      '
  )"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  printf '%s\n' "$pid"
}

wait_code_pid() {
  local deadline=$((SECONDS + 60)) pid
  while ((SECONDS < deadline)); do
    pid="$(find_code_pid || true)"
    if [[ "$pid" =~ ^[1-9][0-9]*$ ]]; then
      printf '%s\n' "$pid"
      return 0
    fi
    sleep 0.3
  done
  return 1
}

assert_processes_stable() {
  local current_manager current_wrapper current_code
  current_manager="$(archphene_android_pid "$manager")"
  current_wrapper="$(archphene_android_pid "$code_package")"
  current_code="$(find_code_pid || true)"
  [[ "$current_manager" == "$manager_pid" ]] ||
    archphene_die "manager restarted during Code appearance update"
  [[ "$current_wrapper" == "$wrapper_pid" ]] ||
    archphene_die "Code wrapper restarted during appearance update"
  [[ "$current_code" == "$code_pid" ]] ||
    archphene_die "Code restarted during appearance update"
}

resume_code() {
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  sleep 2
}

capture_stable_frame() {
  local label="$1"
  local raw="$temporary/$label.raw"
  local previous="$temporary/$label.previous.raw"
  local attempt
  archphene_adb_run exec-out screencap >"$previous"
  for attempt in {1..40}; do
    sleep 1
    archphene_adb_run exec-out screencap >"$raw"
    if python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" similar \
        "$previous" "$raw" --maximum-difference 2.5 \
        --maximum-changed-ratio .08 >/dev/null 2>&1; then
      python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" inspect \
        "$raw" >/dev/null
      archphene_adb_run exec-out screencap -p >"$artifact_dir/$label.png"
      printf '%s\n' "$raw"
      return 0
    fi
    cp "$raw" "$previous"
  done
  archphene_die "Code $label frame did not stabilize"
}

stop_code() {
  archphene_adb_run shell am force-stop "$code_package" >/dev/null 2>&1 || true
  local deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)); do
    ! archphene_android_pid "$code_package" >/dev/null 2>&1 &&
      ! find_code_pid >/dev/null 2>&1 &&
      return 0
    sleep 0.3
  done
  return 1
}

cleanup() {
  local status=$?
  set +e
  stop_code >/dev/null 2>&1
  if [[ "$config_backed_up" == true && -n "$code_config" ]]; then
    archphene_adb_run shell \
      "run-as $manager sh -c 'rm -rf -- \"$code_config\" && mv -- \"$backup\" \"$code_config\"'" \
      >/dev/null 2>&1
    config_backed_up=false
  fi
  if [[ -n "$original_theme" ]]; then
    set_theme "$original_theme" >/dev/null 2>&1
  fi
  if [[ -n "$original_section" ]]; then
    archphene_open_manager_section "$original_section" \
      "code-appearance-restore-section-$serial_slug" >/dev/null 2>&1
  fi
  archphene_adb_run shell rm -f "$ui_path" >/dev/null 2>&1
  archphene_adb_run shell rm -f "$remote_fixture" >/dev/null 2>&1
  if [[ "$manager_was_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1
  fi
  if [[ "$temporary" == "$ARCHPHENE_ROOT/tooling/build/current-code-appearance."* ]]; then
    rm -rf -- "$temporary"
  fi
  return "$status"
}
trap cleanup EXIT

if archphene_android_pid "$code_package" >/dev/null 2>&1 ||
    find_code_pid >/dev/null 2>&1; then
  archphene_die "refusing to replace an active Code session"
fi
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_was_running=true
fi
read -r original_theme original_material <<<"$(read_appearance)"
original_section="$(read_section)"

code_configs="$(
  archphene_adb_run exec-out run-as "$manager" find \
    files/arch-root/home/archphene/.config -mindepth 1 -maxdepth 1 -type d \
    -print 2>/dev/null |
    tr -d '\r' |
    sed -n '\#/\(Code\|Code - OSS\)$#p'
)"
[[ "$(sed '/^$/d' <<<"$code_configs" | wc -l)" == 1 ]] ||
  archphene_die "could not identify one exact Code configuration directory"
code_config="$(sed -n '1p' <<<"$code_configs")"
[[ "$code_config" =~ ^files/arch-root/home/archphene/\.config/(Code|Code\ -\ OSS)$ ]] ||
  archphene_die "Code configuration path is unsafe: $code_config"
archphene_adb_run shell run-as "$manager" test ! -L "$code_config" ||
  archphene_die "Code configuration directory is a symlink"
archphene_adb_run shell run-as "$manager" test ! -e "$backup" ||
  archphene_die "Code appearance backup already exists"
config_inventory_before="$(code_config_inventory)"
archphene_adb_run shell \
  "run-as $manager sh -c 'cp -a -- \"$code_config\" \"$backup\"'"
config_backed_up=true

archphene_adb_run shell run-as "$manager" mkdir -p "$code_config/User"
archphene_adb_run push "$fixture" "$remote_fixture" >/dev/null
archphene_adb_run shell chmod 0644 "$remote_fixture"
archphene_adb_run shell run-as "$manager" cp \
  "$remote_fixture" "$code_config/User/settings.json"
archphene_adb_run shell run-as "$manager" chmod 0600 \
  "$code_config/User/settings.json"
local_fixture_hash="$(sha256sum "$fixture" | awk '{print $1}')"
remote_fixture_hash="$(
  archphene_adb_run shell run-as "$manager" sha256sum \
    "$code_config/User/settings.json" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$remote_fixture_hash" == "$local_fixture_hash" ]] ||
  archphene_die "Code appearance settings transfer changed bytes"
archphene_adb_run shell rm "$remote_fixture"

set_theme 1
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Linux Wayland client connected' 45 \
  'ArchpheneLauncherSession:I *:S' >/dev/null
archphene_wait_log 'Presented Linux frame' 60 \
  'ArchpheneLauncherSession:I *:S' >/dev/null
code_pid="$(wait_code_pid)" || archphene_die "Code Linux process did not start"
manager_pid="$(archphene_android_pid "$manager")"
wrapper_pid="$(archphene_android_pid "$code_package")"
sleep 8
light_raw="$(capture_stable_frame light)"

set_theme 2
archphene_wait_log 'Published portal appearance session=[0-9]+ dark=true' 15 \
  'ArchphenePortal:I *:S' >/dev/null
resume_code
assert_processes_stable
dark_raw="$(capture_stable_frame dark)"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" light-dark \
  "$light_raw" "$dark_raw" --minimum-changed-ratio .12

set_theme 1
archphene_wait_log 'Published portal appearance session=[0-9]+ dark=false' 15 \
  'ArchphenePortal:I *:S' >/dev/null
resume_code
assert_processes_stable
light_return_raw="$(capture_stable_frame light-return)"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" similar \
  "$light_raw" "$light_return_raw" --maximum-difference 12 \
  --maximum-changed-ratio .35

logs="$(
  archphene_adb_run logcat -d -v brief |
    grep -E 'FATAL EXCEPTION|AndroidRuntime.*Process: (org\\.archphene|.*code)|Archphene(LauncherSession|Portal).* (E|F)/' ||
    true
)"
[[ -z "$logs" ]] || archphene_die "Code appearance gate emitted a fatal event: $logs"

stop_code || archphene_die "Code did not stop cleanly"
archphene_adb_run shell \
  "run-as $manager sh -c 'rm -rf -- \"$code_config\" && mv -- \"$backup\" \"$code_config\"'"
config_backed_up=false
[[ "$(code_config_inventory)" == "$config_inventory_before" ]] ||
  archphene_die "Code appearance gate did not restore exact configuration"
set_theme "$original_theme"
read -r restored_theme restored_material <<<"$(read_appearance)"
[[ "$restored_theme" == "$original_theme" &&
    "$restored_material" == "$original_material" ]] ||
  archphene_die "Code appearance gate did not restore exact appearance preferences"
archphene_open_manager_section "$original_section" \
  "code-appearance-final-section-$serial_slug"
if [[ "$manager_was_running" == false ]]; then
  archphene_adb_run shell am force-stop "$manager" >/dev/null
fi
archphene_adb_run shell rm -f "$ui_path" "$remote_fixture" >/dev/null
if [[ "$temporary" == "$ARCHPHENE_ROOT/tooling/build/current-code-appearance."* ]]; then
  rm -rf -- "$temporary"
fi
trap - EXIT

archphene_note \
  "Current Code appearance passed on $serial: live light/dark/light portal updates, stable manager/wrapper/Linux processes, exact configuration restoration, scoped logs, and full-device evidence in $artifact_dir."
