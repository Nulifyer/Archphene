#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
code_package=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --code-package) code_package="${2:?missing value for --code-package}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --skip-manager-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --code-package PACKAGE [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$code_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--code-package is not a generated Archphene launcher"
archphene_test_init "$serial"

manager=org.archphene.app.debug
manager_activity="$manager/org.archphene.app.MainActivity"
output_dir="$ARCHPHENE_ROOT/tooling/build/code-phone-workspace"
mkdir -p "$output_dir"
original_scale_index=
original_section=
restore_pending=false
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
code_config=
code_config_backup="files/arch-root/run/code-phone-config-$serial_slug"
code_config_backed_up=false
code_config_inventory_before=
manager_was_running=false
manager_state_restored=false
original_accelerometer_rotation="$(
  archphene_adb_run shell settings get system accelerometer_rotation | tr -d '\r'
)"
original_user_rotation="$(
  archphene_adb_run shell settings get system user_rotation | tr -d '\r'
)"
[[ "$original_accelerometer_rotation" =~ ^[01]$ ]] ||
  archphene_die "unexpected automatic rotation setting: $original_accelerometer_rotation"
[[ "$original_user_rotation" =~ ^[0-3]$ ]] ||
  archphene_die "unexpected user rotation setting: $original_user_rotation"
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_was_running=true
fi
if archphene_android_pid "$code_package" >/dev/null 2>&1; then
  archphene_die "refusing to replace an active Code session"
fi

code_linux_pgid() {
  local process_tree
  process_tree="$(
    archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
      tr -d '\r'
  )"
  awk '
    $4 ~ /^libarchphene_(pkg_[0-9a-f]+|ld)\.so$/ &&
    $0 ~ /--argv0 (code|code-oss) / {
      print $3
      exit
    }
  ' <<<"$process_tree"
}

stop_code_session() {
  local linux_pgid deadline process_tree
  linux_pgid="$(code_linux_pgid)"
  archphene_adb_run shell am force-stop "$code_package" >/dev/null 2>&1 || true
  if [[ "$linux_pgid" =~ ^[1-9][0-9]*$ ]]; then
    deadline=$((SECONDS + 20))
    while ((SECONDS < deadline)); do
      process_tree="$(
        archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
          tr -d '\r'
      )"
      if ! awk -v pgid="$linux_pgid" '$3 == pgid { found = 1 } END { exit !found }' \
        <<<"$process_tree"; then
        sleep 1
        return 0
      fi
      sleep 0.25
    done
    archphene_die "Code Linux process group did not stop before configuration restoration"
  fi
  sleep 1
}

scale_slider() {
  python3 -c '
import re
import sys
import xml.etree.ElementTree as ET

root = ET.fromstring(sys.stdin.read())
for node in root.iter("node"):
    description = node.attrib.get("content-desc", "")
    if (
        node.attrib.get("class") != "android.widget.SeekBar"
        or not description.startswith("App scale, ")
    ):
        continue
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
    if bounds is None:
        raise SystemExit("invalid App scale bounds")
    value = description.removeprefix("App scale, ")
    index = {"Auto": 0, "75%": 1, "100%": 2, "125%": 3, "150%": 4}.get(value)
    if index is None:
        raise SystemExit("invalid App scale value")
    print(index, *bounds.groups())
    raise SystemExit
raise SystemExit("App scale slider is missing")
'
}

open_settings() {
  archphene_adb_run shell am force-stop "$manager" >/dev/null
  archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
  archphene_wait_ui 'text="Packages"' "code-phone-manager-$serial" 20
  archphene_open_manager_section Settings "code-phone-settings-$serial"
  archphene_wait_ui 'content-desc="App scale, [^"]+"' \
    "code-phone-slider-$serial" 15
}

set_scale_index() {
  local index="$1"
  local left top right bottom x y
  read -r _ left top right bottom <<<"$(scale_slider <<<"$ARCHPHENE_UI")"
  x=$((left + 21 + (right - left - 42) * index / 4))
  y=$(((top + bottom) / 2))
  archphene_adb_run shell input tap "$x" "$y" >/dev/null
  local label
  case "$index" in
    0) label=Auto ;;
    1) label=75% ;;
    2) label=100% ;;
    3) label=125% ;;
    4) label=150% ;;
    *) archphene_die "invalid App scale index: $index" ;;
  esac
  archphene_wait_ui "content-desc=\"App scale, $label\"" \
    "code-phone-scale-$serial-$index" 10
}

selected_manager_section() {
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal", "Settings"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        raise SystemExit
'
}

restore_manager_state() {
  [[ "$manager_state_restored" == false ]] || return 0
  stop_code_session
  if [[ "$code_config_backed_up" == true ]]; then
    archphene_adb_run shell \
      "run-as $manager sh -c 'rm -rf -- \"$code_config\" && mv -- \"$code_config_backup\" \"$code_config\"'" \
      >/dev/null
    code_config_backed_up=false
  fi
  if [[ "$restore_pending" == true && -n "$original_scale_index" ]]; then
    (
      open_settings
      set_scale_index "$original_scale_index"
    ) >/dev/null 2>&1 || true
    restore_pending=false
  fi
  if [[ -n "$original_section" ]]; then
    archphene_adb_run shell am start -W -n "$manager_activity" \
      >/dev/null 2>&1 || true
    local ui
    ui="$(archphene_capture_ui "code-phone-restore-section-$serial" 2>/dev/null || true)"
    if archphene_regex_contains \
      "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
      archphene_tap_ui_pattern \
        "$ui" \
        "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" >/dev/null 2>&1 || true
    fi
  fi
  archphene_adb_run shell settings put system accelerometer_rotation \
    "$original_accelerometer_rotation" >/dev/null 2>&1 || true
  archphene_adb_run shell settings put system user_rotation \
    "$original_user_rotation" >/dev/null 2>&1 || true
  if [[ "$manager_was_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$manager_activity" \
      >/dev/null 2>&1 || true
  else
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
  manager_state_restored=true
}

cleanup() {
  restore_manager_state
}
trap cleanup EXIT

assert_manager_restored() {
  if [[ -n "$original_section" ]]; then
    if [[ "$manager_was_running" == false ]]; then
      archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
    fi
    local ui
    ui="$(archphene_capture_ui "code-phone-restored-section-$serial")"
    archphene_regex_contains \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"[^>]*selected=\"true\"" ||
      archphene_die "Code phone gate did not restore manager section $original_section"
    if [[ "$manager_was_running" == false ]]; then
      archphene_adb_run shell am force-stop "$manager" >/dev/null
    fi
  fi
  if [[ "$manager_was_running" == true ]]; then
    archphene_android_pid "$manager" >/dev/null ||
      archphene_die "Code phone gate did not restore the running manager"
  else
    ! archphene_android_pid "$manager" >/dev/null 2>&1 ||
      archphene_die "Code phone gate left the manager running"
  fi
}

code_config_inventory() {
  archphene_adb_run shell \
    "run-as $manager sh -c 'cd \"$code_config\" && find . -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "$manager is not installed; pass --install-apk with --apk"
archphene_adb_run shell pm path "$code_package" >/dev/null ||
  archphene_die "Code launcher is not installed: $code_package"
[[ -z "$(code_linux_pgid)" ]] ||
  archphene_die "refusing to snapshot an active manager-owned Code process"

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
archphene_adb_run shell run-as "$manager" test ! -e "$code_config_backup" ||
  archphene_die "Code configuration backup path already exists"
archphene_adb_run shell \
  "run-as $manager sh -c 'cp -a -- \"$code_config\" \"$code_config_backup\"'"
code_config_backed_up=true
code_config_inventory_before="$(code_config_inventory)"

archphene_adb_run shell settings put system accelerometer_rotation 0 >/dev/null
archphene_adb_run shell settings put system user_rotation 0 >/dev/null
archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
archphene_wait_ui 'text="Packages"' "code-phone-initial-$serial" 20
initial_ui="$(archphene_capture_ui "code-phone-initial-section-$serial")"
original_section="$(selected_manager_section <<<"$initial_ui")"
[[ -n "$original_section" ]] ||
  archphene_die "could not determine the original manager section"

open_settings
read -r original_scale_index _ <<<"$(scale_slider <<<"$ARCHPHENE_UI")"
restore_pending=true
set_scale_index 1
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-setting-75.png"

# Recreate the manager before launch so this is a durable user preference, not
# a transient in-memory test override.
open_settings
read -r persisted_scale_index _ <<<"$(scale_slider <<<"$ARCHPHENE_UI")"
[[ "$persisted_scale_index" == 1 ]] ||
  archphene_die "75% App scale did not persist across manager restart"

code_activity="$(archphene_launcher "$code_package")"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run shell am force-stop "$code_package" >/dev/null
archphene_adb_run shell am start -W -n "$code_activity" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected' 30 'ArchpheneLauncherSession:I *:S' \
  >/dev/null
archphene_wait_log \
  'Presented Linux frame.*selected=([0-9]+x[0-9]+) surface=\1 original=\1 logical=576x[0-9]+.*output=576x[0-9]+' \
  45 'ArchpheneLauncherSession:I *:S' >/dev/null
sleep 8

# Open the application's standard integrated-terminal panel so the capture
# measures a real multi-pane desktop workspace rather than an empty splash.
archphene_adb_run shell input keycombination 113 59 68 >/dev/null
sleep 5
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-code-75.png"

appearance_log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneLauncherSession:I '*:S' 2>/dev/null
)"
archphene_regex_contains "$appearance_log" \
  'Resolved launcher appearance.*geometry=75.*controls=20dp' ||
  archphene_die "Code did not use the selected generic phone-workspace policy"
fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Code phone-workspace regression emitted a fatal runtime error: $fatal_log"

stop_code_session
open_settings
set_scale_index "$original_scale_index"
open_settings
read -r restored_scale_index _ <<<"$(scale_slider <<<"$ARCHPHENE_UI")"
[[ "$restored_scale_index" == "$original_scale_index" ]] ||
  archphene_die "original App scale did not persist after restoration"
restore_pending=false

trap - EXIT
restore_manager_state
[[ "$(code_config_inventory)" == "$code_config_inventory_before" ]] ||
  archphene_die "Code phone gate did not restore the exact Code configuration"
archphene_adb_run shell run-as "$manager" test ! -e "$code_config_backup" ||
  archphene_die "Code phone gate left its configuration backup"
[[ "$(
  archphene_adb_run shell settings get system accelerometer_rotation | tr -d '\r'
)" == "$original_accelerometer_rotation" ]] ||
  archphene_die "Code phone gate did not restore automatic rotation"
[[ "$(
  archphene_adb_run shell settings get system user_rotation | tr -d '\r'
)" == "$original_user_rotation" ]] ||
  archphene_die "Code phone gate did not restore user rotation"
assert_manager_restored
archphene_note "Code generic phone workspace passed on $serial"
archphene_note "  App scale persisted at 75% and rendered a 576-logical-pixel workspace"
archphene_note "  Original App scale index $original_scale_index restored"
archphene_note "  Full-device screenshots: $output_dir/$serial-setting-75.png and $output_dir/$serial-code-75.png"
