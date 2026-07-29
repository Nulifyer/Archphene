#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
probe=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --probe) probe="${2:?missing value for --probe}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --probe PATH [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$probe" ]] || archphene_die "--probe is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
archphene_require_file "$probe"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
device_stage="/data/local/tmp/archphene-kernel-view-${serial//[^a-zA-Z0-9]/-}-$$"
root_probe=files/arch-root/tmp/archphene-kernel-view-probe
session_marker=files/arch-root/var/lib/archphene/session-active-v1
output_dir="$ARCHPHENE_ROOT/tooling/build/kernel-view"
output="$output_dir/$serial.png"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=
probe_owned=false

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "kernel-view-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$probe_owned" == true ]]; then
    archphene_adb_run shell run-as "$package" rm -f "$root_probe" \
      >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell rm -f "$device_stage" >/dev/null 2>&1 || true
  if [[ "$initial_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    restore_section || true
  fi
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
if archphene_adb_run shell run-as "$package" test -e "$session_marker"; then
  archphene_die "an active shared shell already exists; stop it before this gate"
fi
archphene_adb_run shell am force-stop "$package" >/dev/null
if archphene_adb_run shell run-as "$package" test -e "$root_probe"; then
  archphene_die "kernel-view fixture path already exists"
fi
archphene_adb_run push "$probe" "$device_stage" >/dev/null
archphene_adb_run shell run-as "$package" cp "$device_stage" "$root_probe"
probe_owned=true
archphene_adb_run shell run-as "$package" chmod 500 "$root_probe"
archphene_adb_run shell run-as "$package" test -x \
  files/arch-root/usr/bin/bash ||
  archphene_die "kernel-view regression requires installed Bash"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
initial_ui="$(archphene_capture_ui "kernel-view-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "kernel-view-onboarding-$serial"
  initial_ui="$ARCHPHENE_UI"
fi
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal", "Settings"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$initial_ui"
)"
archphene_open_manager_section Terminal "kernel-view-terminal-$serial"
archphene_wait_ui 'text="Start shell"' "kernel-view-start-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui \
  '(?:archphene:~|sh-[0-9.]+)\$' "kernel-view-ready-$serial" 20

archphene_enter_terminal_line \
  "/tmp/archphene-kernel-view-probe" \
  "kernel-view-command-$serial"
archphene_wait_ui 'kernel-view-ok' "kernel-view-output-$serial" 20
archphene_adb_run exec-out screencap -p >"$output"

archphene_wait_ui 'text="Stop shell"' "kernel-view-stop-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "kernel-view-stopped-$serial" 20

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "kernel-view regression emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene sandboxed kernel view passed on $serial"
archphene_note "  libc and direct getdents expose only readable process entries"
archphene_note "  Reliable self, CPU-topology, and safe-device paths remain available"
archphene_note "  Full-device screenshot: $output"
