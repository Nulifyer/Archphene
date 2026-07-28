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
archphene_adb_run shell pm path "$package" >/dev/null 2>&1 \
  || archphene_die "$package is not installed; this test does not install APKs"
resolved="$(
  archphene_adb_run shell cmd package resolve-activity --brief \
    -n "$activity" 2>/dev/null | tail -n1 | tr -d '\r'
)"
[[ "$resolved" == "$activity" ]] \
  || archphene_die "$package does not contain the debug terminal accessibility fixture"

if archphene_adb_run shell pm path com.google.android.marvin.talkback \
    >/dev/null 2>&1; then
  talkback_package=com.google.android.marvin.talkback
  service="$talkback_package/.TalkBackService"
  notification_permission=true
elif archphene_adb_run shell pm path com.samsung.android.accessibility.talkback \
    >/dev/null 2>&1; then
  talkback_package=com.samsung.android.accessibility.talkback
  service="$talkback_package/com.samsung.android.marvin.talkback.TalkBackService"
  notification_permission=false
else
  archphene_die 'no supported installed TalkBack service is available'
fi

safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/terminal-screen-reader/$safe_serial}"
mkdir -p "$artifact_dir"
old_services="$(
  archphene_adb_run shell settings get secure enabled_accessibility_services |
    tr -d '\r'
)"
old_enabled="$(
  archphene_adb_run shell settings get secure accessibility_enabled |
    tr -d '\r'
)"
old_touch="$(
  archphene_adb_run shell settings get secure touch_exploration_enabled |
    tr -d '\r'
)"
talkback_was_enabled=false
[[ "$old_services" != *"$service"* ]] || talkback_was_enabled=true
if [[ "$talkback_was_enabled" == true ]]; then
  test_services="$old_services"
elif [[ -z "$old_services" || "$old_services" == null ]]; then
  test_services="$service"
else
  test_services="$old_services:$service"
fi
notification_was_granted=false
notification_granted_by_test=false
if [[ "$notification_permission" == true ]]; then
  package_state="$(archphene_adb_run shell dumpsys package "$talkback_package")"
  [[ "$package_state" != *'android.permission.POST_NOTIFICATIONS: granted=true'* ]] \
    || notification_was_granted=true
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
  local failed=false
  restore_setting enabled_accessibility_services "$old_services" || failed=true
  restore_setting accessibility_enabled "$old_enabled" || failed=true
  restore_setting touch_exploration_enabled "$old_touch" || failed=true
  if [[ "$notification_granted_by_test" == true ]]; then
    archphene_adb_run shell pm revoke "$talkback_package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || failed=true
  fi
  if [[ "$talkback_was_enabled" == false ]]; then
    archphene_adb_run shell am force-stop "$talkback_package" \
      >/dev/null 2>&1 || failed=true
  fi
  [[ "$failed" == false ]] \
    || echo 'error: could not fully restore screen-reader test state' >&2
}
trap restore EXIT

if [[ "$notification_permission" == true &&
    "$notification_was_granted" == false ]]; then
  archphene_adb_run shell pm grant "$talkback_package" \
    android.permission.POST_NOTIFICATIONS >/dev/null
  notification_granted_by_test=true
fi
if [[ "$talkback_was_enabled" == false ]]; then
  archphene_adb_run shell am force-stop "$talkback_package" >/dev/null
fi
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Terminal accessibility fixture ready' "$timeout" \
  'ArchpheneTerminalA11y:I AndroidRuntime:E *:S' >/dev/null

archphene_adb_run shell settings put secure \
  enabled_accessibility_services "$test_services" >/dev/null
archphene_adb_run shell settings put secure accessibility_enabled 1 >/dev/null
archphene_adb_run shell settings put secure touch_exploration_enabled 1 >/dev/null

deadline=$((SECONDS + timeout))
focused=false
while ((SECONDS < deadline)); do
  current_focus="$(
    archphene_adb_run shell dumpsys window |
      sed -n 's/.*mCurrentFocus=//p' |
      head -n1 |
      tr -d '\r'
  )"
  if [[ "$current_focus" != *"$activity"* ]]; then
    if [[ "$current_focus" == *"$talkback_package"* ||
        "$current_focus" == *PermissionController* ]]; then
      archphene_die \
        "TalkBack opened first-run UI instead of the terminal; initialize the installed screen reader once, then rerun"
    fi
    sleep 0.25
    continue
  fi
  log="$(
    archphene_adb_run logcat -d -v brief \
      -s ArchpheneTerminalA11y:I AndroidRuntime:E '*:S'
  )"
  if archphene_regex_contains \
      "$log" 'focused=true selection=[0-9]+\.\.[0-9]+ granularities=7'; then
    focused=true
    break
  fi
  sleep 0.25
done
[[ "$focused" == true ]] \
  || archphene_die 'TalkBack did not accessibility-focus the terminal'

dumpsys_file="$artifact_dir/accessibility-manager.txt"
archphene_adb_run shell dumpsys accessibility >"$dumpsys_file"
accessibility_state="$(cat "$dumpsys_file")"
[[ "$accessibility_state" == *'Service[label=TalkBack'* &&
    "$accessibility_state" == *"Enabled services:"*"$talkback_package"* ]] \
  || archphene_die 'the installed TalkBack service is not bound and enabled'
read -r active top accessibility <<<"$(
  sed -n \
    -e 's/.*Active Window Id = \([-0-9]*\).*/\1/p' \
    -e 's/.*Top Focused Window Id = \([-0-9]*\).*/\1/p' \
    -e 's/.*Accessibility Focused Window Id = \([-0-9]*\).*/\1/p' \
    "$dumpsys_file" |
    head -n3 |
    tr '\n' ' '
)"
[[ -n "$active" && "$active" != -1 &&
    "$active" == "$top" && "$top" == "$accessibility" ]] \
  || archphene_die \
    "TalkBack focus is inconsistent: active=$active top=$top accessibility=$accessibility"

focused_png="$artifact_dir/full-device-focused.png"
after_next_png="$artifact_dir/full-device-after-next.png"
"$ARCHPHENE_ADB" "${ARCHPHENE_ADB_ARGS[@]}" exec-out screencap -p >"$focused_png"
# Official TalkBack navigation uses a one-finger right swipe for the next item.
archphene_adb_run shell input swipe 180 1000 820 1000 250 >/dev/null
sleep 1
"$ARCHPHENE_ADB" "${ARCHPHENE_ADB_ARGS[@]}" exec-out screencap -p >"$after_next_png"

python3 - "$focused_png" <<'PY'
import sys
from PIL import Image

image = Image.open(sys.argv[1]).convert("RGB")
width, height = image.size
focused = 0
for y in range(height):
    for x in range(width):
        if 15 < x < width - 16 and 15 < y < height - 16:
            continue
        red, green, blue = image.getpixel((x, y))
        if (
            green > 80
            and green > red * 1.3
            and green > blue * 1.3
        ) or (
            blue > 100
            and blue > red * 1.3
            and blue > green * 1.1
        ):
            focused += 1
if focused < 2 * (width + height):
    raise SystemExit(
        f"full-device screenshot lacks the TalkBack focus border: {focused} pixels"
    )
PY

archphene_capture_ui "terminal-screen-reader-$safe_serial" \
  >"$artifact_dir/framework-tree.xml"
tree="$(cat "$artifact_dir/framework-tree.xml")"
[[ "$tree" == *'Archphene terminal screen reader'* &&
    "$tree" == *'cargo 1.88.0'* &&
    "$tree" == *'content-desc="Linux terminal, 40 columns by 8 rows"'* ]] \
  || archphene_die 'the focused terminal text is not exact in the framework tree'
logs="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneTerminalA11y:I AndroidRuntime:E '*:S'
)"
printf '%s\n' "$logs" >"$artifact_dir/scoped-log.txt"
[[ "$logs" != *'Terminal accessibility fixture failed'* &&
    "$logs" != *'FATAL EXCEPTION'* ]] \
  || archphene_die "terminal screen-reader workflow failed: $logs"

archphene_note \
  "Terminal screen reader passed on $serial: the installed TalkBack service bound, accessibility-focused the exact production terminal View, exposed character/word/line granularities, retained focus across next-item navigation, and produced inspected full-device evidence."
