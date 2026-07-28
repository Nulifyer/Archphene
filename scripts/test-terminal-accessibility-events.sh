#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=org.archphene.app.debug
timeout=20
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die '--serial is required'
archphene_test_init "$serial"

activity="$package/org.archphene.app.TerminalAccessibilityFixtureActivity"
service="$package/org.archphene.app.TerminalAccessibilityTestService"
archphene_adb_run shell pm path "$package" >/dev/null 2>&1 ||
  archphene_die "$package is not installed; this test does not install APKs"
resolved="$(
  archphene_adb_run shell cmd package resolve-activity --brief \
    -n "$activity" 2>/dev/null | tail -n1 | tr -d '\r'
)"
[[ "$resolved" == "$activity" ]] ||
  archphene_die "$package does not contain the terminal accessibility fixture"
service_dump="$(
  archphene_adb_run shell dumpsys package "$package" |
    tr -d '\r'
)"
[[ "$service_dump" == *".TerminalAccessibilityTestService"* ]] ||
  archphene_die "$package does not contain the debug accessibility consumer"

safe_serial="${serial//[^A-Za-z0-9._-]/_}"
if [[ -z "$artifact_dir" ]]; then
  artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/terminal-accessibility-events/$safe_serial"
fi
mkdir -p "$artifact_dir"
old_services="$(
  archphene_adb_run shell settings get secure enabled_accessibility_services |
    tr -d '\r'
)"
old_enabled="$(
  archphene_adb_run shell settings get secure accessibility_enabled |
    tr -d '\r'
)"
if [[ -z "$old_services" || "$old_services" == null ]]; then
  test_services="$service"
elif [[ "$old_services" == *"$service"* ]]; then
  test_services="$old_services"
else
  test_services="$old_services:$service"
fi

restore_setting() {
  local key="$1" value="$2"
  if [[ -z "$value" || "$value" == null ]]; then
    archphene_adb_run shell settings delete secure "$key" >/dev/null 2>&1
  else
    archphene_adb_run shell settings put secure "$key" "$value" >/dev/null 2>&1
  fi
}

restore() {
  restore_setting enabled_accessibility_services "$old_services" || true
  restore_setting accessibility_enabled "$old_enabled" || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap restore EXIT

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Terminal accessibility fixture ready' "$timeout" \
  'ArchpheneTerminalA11y:I AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell settings put secure \
  enabled_accessibility_services "$test_services" >/dev/null
archphene_adb_run shell settings put secure accessibility_enabled 1 >/dev/null

logs="$(
  archphene_wait_log \
    'Terminal accessibility service (?:pass|failed)' "$timeout" \
    'ArchpheneTerminalA11yService:V AndroidRuntime:E *:S'
)"
printf '%s\n' "$logs" >"$artifact_dir/scoped-log.txt"
[[ "$logs" == *"Terminal accessibility service pass"* ]] ||
  archphene_die "terminal accessibility event gate failed: $logs"

archphene_adb_run exec-out screencap -p >"$artifact_dir/full-device.png"
archphene_capture_ui "terminal-accessibility-events-$safe_serial" \
  >"$artifact_dir/framework-tree.xml"
tree="$(cat "$artifact_dir/framework-tree.xml")"
[[ "$tree" == *'cargo 1.88.0'* &&
    "$tree" == *'selected="true"'* &&
    "$tree" == *'content-desc="Linux terminal, 40 columns by 8 rows"'* ]] ||
  archphene_die "framework tree does not retain the selected terminal state"

archphene_note "Terminal accessibility events passed on $serial: a framework service focused the production terminal, selected text, traversed by word, and received the exact focus/selection/traversal events."
