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
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-terminal-reflow-test"
device_temporary="/data/local/tmp/archphene-terminal-reflow-test-${serial//[^a-zA-Z0-9]/-}-$$"
installed_fixture=files/arch-root/usr/bin/archphene-terminal-reflow-test
session_marker=files/arch-root/var/lib/archphene/session-active-v1
output_dir="$ARCHPHENE_ROOT/tooling/build/terminal-reflow"
output="$output_dir/$serial-landscape.png"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=
fixture_owned=false
session_owned=false
initial_accelerometer="$(
  archphene_adb_run shell settings get system accelerometer_rotation | tr -d '\r'
)"
initial_rotation="$(
  archphene_adb_run shell settings get system user_rotation | tr -d '\r'
)"

restore_setting() {
  local namespace="$1" key="$2" value="$3"
  if [[ "$value" == null ]]; then
    archphene_adb_run shell settings delete "$namespace" "$key" \
      >/dev/null 2>&1 || true
  else
    archphene_adb_run shell settings put "$namespace" "$key" "$value" \
      >/dev/null 2>&1 || true
  fi
}

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "terminal-reflow-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

cleanup() {
  restore_setting system accelerometer_rotation "$initial_accelerometer"
  restore_setting system user_rotation "$initial_rotation"
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$session_owned" == true ]]; then
    archphene_adb_run shell run-as "$package" rm -f "$session_marker" \
      >/dev/null 2>&1 || true
  fi
  if [[ "$fixture_owned" == true ]]; then
    archphene_adb_run shell run-as "$package" rm -f "$installed_fixture" \
      >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell rm -f "$device_temporary" >/dev/null 2>&1 || true
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
if archphene_adb_run shell run-as "$package" test -e "$installed_fixture"; then
  archphene_die "terminal-reflow fixture path already exists"
fi
archphene_require_file "$fixture"
archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp \
  "$device_temporary" "$installed_fixture"
fixture_owned=true
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"
archphene_adb_run shell settings put system accelerometer_rotation 0 >/dev/null
archphene_adb_run shell settings put system user_rotation 0 >/dev/null

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
initial_ui="$(archphene_capture_ui "terminal-reflow-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "terminal-reflow-onboarding-$serial"
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
archphene_open_manager_section Terminal "terminal-reflow-section-$serial"
archphene_wait_ui 'text="Start shell"' "terminal-reflow-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
session_owned=true
archphene_wait_ui '(?:archphene:~|sh-[0-9.]+)\$' \
  "terminal-reflow-prompt-$serial" 20
archphene_enter_terminal_line \
  "bash /usr/bin/archphene-terminal-reflow-test" \
  "terminal-reflow-fixture-$serial"
archphene_wait_ui 'REFLOW-END' "terminal-reflow-live-tail-$serial" 40

manager_pid="$(
  archphene_adb_run shell pidof "$package" | tr -d '\r'
)"
archphene_adb_run shell settings put system user_rotation 1 >/dev/null
deadline=$((SECONDS + 20))
landscape_ui=
while ((SECONDS < deadline)); do
  landscape_ui="$(archphene_capture_ui "terminal-reflow-landscape-$serial" \
    2>/dev/null || true)"
  bounds="$(
    python3 -c '
import re, sys
m = re.search(
    r"content-desc=\"Linux terminal, ([0-9]+) columns by ([0-9]+) rows\"",
    sys.stdin.read(),
)
print(f"{m.group(1)} {m.group(2)}" if m else "")
' <<<"$landscape_ui"
  )"
  if [[ -n "$bounds" ]]; then
    read -r columns rows <<<"$bounds"
    if ((columns > rows)); then
      break
    fi
  fi
  sleep 0.5
done
[[ -n "${columns:-}" && "$columns" -gt "$rows" ]] ||
  archphene_die "terminal did not reach a landscape grid"

archphene_regex_contains "$landscape_ui" 'REFLOW-BEGIN-' &&
  archphene_regex_contains "$landscape_ui" 'REFLOW-END' ||
  archphene_die \
    "history/live marker endpoints were not both visible after landscape resize"

archphene_adb_run exec-out screencap -p >"$output"
after_pid="$(
  archphene_adb_run shell pidof "$package" | tr -d '\r'
)"
[[ -n "$manager_pid" && "$manager_pid" == "$after_pid" ]] ||
  archphene_die "manager process changed during terminal reflow"

archphene_wait_ui 'text="Stop shell"' "terminal-reflow-stop-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "terminal-reflow-stopped-$serial" 20
archphene_adb_run shell run-as "$package" test ! -e "$session_marker" ||
  archphene_die "terminal reflow left an active shell marker"
session_owned=false

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "terminal reflow emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene logical terminal reflow passed on $serial"
archphene_note "  One marker crossed portrait history/live state and rejoined in landscape"
archphene_note "  Stable manager PID: $manager_pid; landscape grid: ${columns}x${rows}"
archphene_note "  Full-device screenshot: $output"
