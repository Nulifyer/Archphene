#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.pb1623042aeee4267eb8c86dead4b2dd7
timeout=60
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
[[ "$timeout" =~ ^[0-9]+$ ]] \
  || archphene_die '--timeout-seconds must be 20..180'
((timeout >= 20 && timeout <= 180)) \
  || archphene_die '--timeout-seconds must be 20..180'

archphene_test_init "$serial"
archphene_adb_run shell pm path "$package" >/dev/null
activity="$(archphene_launcher "$package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/kate-workflows}"
mkdir -p "$artifact_dir"

suffix="$(date +%s)$RANDOM"
session_name="ArchpheneWorkflow$suffix"
backup="files/archphene-kate-workflow-backup-$suffix"
katerc=files/linux-home/.config/katerc
kate_state=files/linux-home/.local/share/kate
feedback=files/linux-home/.local/state/UserFeedback.org.kde.kate
had_katerc=false
had_kate_state=false
had_feedback=false

archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell run-as "$package" mkdir -p "$backup"
if archphene_adb_run shell run-as "$package" test -e "$katerc"; then
  archphene_adb_run shell run-as "$package" cp -a "$katerc" "$backup/katerc"
  had_katerc=true
fi
if archphene_adb_run shell run-as "$package" test -e "$kate_state"; then
  archphene_adb_run shell run-as "$package" cp -a "$kate_state" "$backup/kate"
  had_kate_state=true
fi
if archphene_adb_run shell run-as "$package" test -e "$feedback"; then
  archphene_adb_run shell run-as "$package" cp -a "$feedback" "$backup/feedback"
  had_feedback=true
fi

restore_state() {
  set +e
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1
  archphene_adb_run shell run-as "$package" rm -rf \
    "$katerc" "$kate_state" "$feedback" >/dev/null 2>&1
  if [[ "$had_katerc" == true ]]; then
    archphene_adb_run shell run-as "$package" mkdir -p \
      files/linux-home/.config >/dev/null 2>&1
    archphene_adb_run shell run-as "$package" cp -a \
      "$backup/katerc" "$katerc" >/dev/null 2>&1
  fi
  if [[ "$had_kate_state" == true ]]; then
    archphene_adb_run shell run-as "$package" mkdir -p \
      files/linux-home/.local/share >/dev/null 2>&1
    archphene_adb_run shell run-as "$package" cp -a \
      "$backup/kate" "$kate_state" >/dev/null 2>&1
  fi
  if [[ "$had_feedback" == true ]]; then
    archphene_adb_run shell run-as "$package" mkdir -p \
      files/linux-home/.local/state >/dev/null 2>&1
    archphene_adb_run shell run-as "$package" cp -a \
      "$backup/feedback" "$feedback" >/dev/null 2>&1
  fi
  archphene_adb_run shell run-as "$package" rm -rf "$backup" \
    >/dev/null 2>&1
}
trap restore_state EXIT

# Start from an isolated anonymous Kate session. The exact prior config,
# sessions, and feedback state are restored by the EXIT trap.
archphene_adb_run shell run-as "$package" rm -rf \
  "$katerc" "$kate_state" "$feedback"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
main_log="$(archphene_wait_log \
  'window id=[0-9]+.*mapped=true active=true primary=true.*Welcome.*Kate' \
  "$timeout" 'ArchpheneInput:V AndroidRuntime:E *:S')"
main_window="$(python3 -c '
import re, sys
matches = re.findall(r"window id=(\d+).*mapped=true active=true primary=true.*Welcome.*Kate",
                     sys.stdin.read())
print(matches[-1] if matches else "")
' <<<"$main_log")"
[[ -n "$main_window" ]] || archphene_die 'Kate primary window ID was not published'
android_pid="$(archphene_android_pid "$package")"
linux_pid="$(archphene_linux_loader_pid "$android_pid")"
[[ -n "$android_pid" && -n "$linux_pid" ]] \
  || archphene_die 'Kate Android/Linux process pair is incomplete'

# Two real Kate documents must become distinct, actionable tabs.
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_N
archphene_wait_log 'title=Untitled  .*Kate' "$timeout" \
  'ArchpheneInput:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_N
archphene_wait_log 'title=Untitled \(2\)  .*Kate' "$timeout" \
  'ArchpheneInput:V AndroidRuntime:E *:S' >/dev/null
tabs_ui=
deadline=$((SECONDS + timeout))
while ((SECONDS < deadline)); do
  tabs_ui="$(archphene_capture_ui kate-workflows-tabs 2>/dev/null || true)"
  if python3 -c '
import sys
from xml.etree import ElementTree
try:
    root = ElementTree.fromstring(sys.stdin.read())
except ElementTree.ParseError:
    raise SystemExit(1)
tabs = {
    node.attrib.get("text")
    for node in root.iter("node")
    if node.attrib.get("class") == "android.widget.Button"
    and node.attrib.get("clickable") == "true"
}
raise SystemExit(0 if {"Untitled", "Untitled (2)"} <= tabs else 1)
' <<<"$tabs_ui"; then
    break
  fi
  sleep 0.4
done
printf '%s\n' "$tabs_ui" >"$artifact_dir/tabs.xml"
python3 -c '
import re, sys
from xml.etree import ElementTree

root = ElementTree.fromstring(sys.stdin.read())
tabs = {
    node.attrib.get("text")
    for node in root.iter("node")
    if node.attrib.get("class") == "android.widget.Button"
    and node.attrib.get("clickable") == "true"
}
missing = {"Untitled", "Untitled (2)"} - tabs
if missing:
    raise SystemExit(f"Kate tab semantics missing: {sorted(missing)}")
for node in root.iter("node"):
    if node.attrib.get("text") not in {"Untitled", "Untitled (2)"}:
        continue
    if node.attrib.get("class") != "android.widget.Button":
        continue
    values = list(map(int, re.findall(r"\d+", node.attrib.get("bounds", ""))))
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        raise SystemExit("Kate exposed a tab without usable bounds")
' <<<"$tabs_ui"

# KDE's documented default Ctrl+Shift+L shortcut creates a vertical split.
# Require two separately bounded editor panes and two split controls.
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_L
split_ui=
deadline=$((SECONDS + timeout))
while ((SECONDS < deadline)); do
  split_ui="$(archphene_capture_ui kate-workflows-split 2>/dev/null || true)"
  if python3 -c '
import sys
from xml.etree import ElementTree
try:
    root = ElementTree.fromstring(sys.stdin.read())
except ElementTree.ParseError:
    raise SystemExit(1)
controls = [
    n for n in root.iter("node")
    if n.attrib.get("content-desc") == "Split View"
    and n.attrib.get("clickable") == "true"
]
editors = [
    n for n in root.iter("node")
    if n.attrib.get("class") == "android.widget.TextView"
    and n.attrib.get("focusable") == "true"
    and n.attrib.get("text") in {"Untitled", "Untitled (2)"}
]
raise SystemExit(0 if len(controls) >= 2 and len(editors) >= 2 else 1)
' <<<"$split_ui"; then
    break
  fi
  sleep 0.4
done
printf '%s\n' "$split_ui" >"$artifact_dir/vertical-split.xml"
python3 -c '
import re, sys
from xml.etree import ElementTree

root = ElementTree.fromstring(sys.stdin.read())
editors = []
for node in root.iter("node"):
    if node.attrib.get("class") != "android.widget.TextView":
        continue
    if node.attrib.get("focusable") != "true":
        continue
    if node.attrib.get("text") not in {"Untitled", "Untitled (2)"}:
        continue
    values = list(map(int, re.findall(r"\d+", node.attrib.get("bounds", ""))))
    if len(values) == 4 and values[2] - values[0] >= 100 and values[3] - values[1] >= 300:
        editors.append(values)
if len(editors) < 2:
    raise SystemExit("Kate vertical split did not expose two usable editor panes")
editors.sort(key=lambda bounds: bounds[0])
if editors[0][2] > editors[-1][0]:
    raise SystemExit("Kate vertical split editor panes overlap horizontally")
' <<<"$split_ui"
archphene_adb_run exec-out screencap >"$artifact_dir/vertical-split.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/vertical-split.png"
[[ -s "$artifact_dir/vertical-split.raw" \
    && -s "$artifact_dir/vertical-split.png" ]] \
  || archphene_die 'Kate vertical-split frame capture is empty'

# Save the temporary split/tab layout as a named Kate session and prove it is
# discoverable through Kate's real session manager.
ui="$(archphene_capture_ui kate-workflows-before-session)"
archphene_tap_text "$ui" Sessions
archphene_wait_ui_exact_text 'Save Session As...' kate-workflows-session-menu \
  "$timeout"
archphene_tap_text "$ARCHPHENE_UI" 'Save Session As...'
archphene_wait_ui 'text="Specify a name for this session .*Kate"' \
  kate-workflows-session-name "$timeout"
archphene_adb_run shell input text "$session_name"
sleep 0.4
session_ui="$(archphene_capture_ui kate-workflows-session-filled)"
archphene_tap_text "$session_ui" OK
session_file="$kate_state/sessions/$session_name.katesession"
deadline=$((SECONDS + timeout))
while ((SECONDS < deadline)); do
  if archphene_adb_run shell run-as "$package" test -f "$session_file"; then
    break
  fi
  sleep 0.3
done
archphene_adb_run shell run-as "$package" test -f "$session_file" \
  || archphene_die 'Kate did not persist the named workflow session'
ui="$(archphene_capture_ui kate-workflows-session-saved)"
archphene_tap_text "$ui" Sessions
archphene_wait_ui_exact_text 'Manage Sessions...' \
  kate-workflows-session-saved-menu "$timeout"
archphene_tap_text "$ARCHPHENE_UI" 'Manage Sessions...'
archphene_wait_ui_exact_text "$session_name" kate-workflows-session-manager \
  "$timeout"
manager_ui="$ARCHPHENE_UI"
printf '%s\n' "$manager_ui" >"$artifact_dir/session-manager.xml"
[[ "$manager_ui" == *'text="Close"'* ]] \
  || archphene_die 'Kate session manager did not expose its Close action'
archphene_tap_text "$manager_ui" Close
archphene_wait_ui 'text="Untitled \(2\)"' kate-workflows-after-session \
  "$timeout"

# A new Kate window is a second Linux Wayland top-level in the same Android
# Activity and process tree. Android Back must close only that secondary.
ui="$(archphene_capture_ui kate-workflows-before-new-window)"
archphene_adb_run logcat -c
archphene_tap_text "$ui" File
archphene_wait_ui_exact_text 'New Window' kate-workflows-file-menu "$timeout"
archphene_tap_text "$ARCHPHENE_UI" 'New Window'
secondary_log="$(archphene_wait_log \
  'window id=[0-9]+.*mapped=true active=true primary=false.*Kate' \
  "$timeout" 'ArchpheneInput:V AndroidRuntime:E *:S')"
secondary_window="$(python3 -c '
import re, sys
matches = re.findall(r"window id=(\d+).*mapped=true active=true primary=false.*Kate",
                     sys.stdin.read())
print(matches[-1] if matches else "")
' <<<"$secondary_log")"
[[ -n "$secondary_window" && "$secondary_window" != "$main_window" ]] \
  || archphene_die 'Kate did not expose a distinct secondary Linux window'
[[ "$(archphene_android_pid "$package")" == "$android_pid" \
    && "$(archphene_linux_loader_pid "$android_pid")" == "$linux_pid" ]] \
  || archphene_die 'Kate restarted while opening its secondary Linux window'
archphene_adb_run exec-out screencap -p >"$artifact_dir/secondary-window.png"
secondary_closed=false
for _ in 1 2 3; do
  archphene_adb_run logcat -c
  archphene_adb_run shell input keyevent KEYCODE_BACK
  close_deadline=$((SECONDS + 5))
  while ((SECONDS < close_deadline)); do
    close_log="$(archphene_adb_run logcat -d -v brief \
      -s ArchpheneInput:V AndroidRuntime:E '*:S')"
    if archphene_regex_contains "$close_log" \
      "window id=$main_window .*mapped=true active=true primary=true"; then
      secondary_closed=true
      break 2
    fi
    sleep 0.2
  done
done
[[ "$secondary_closed" == true ]] \
  || archphene_die 'Android Back did not close Kate secondary window'
[[ "$(archphene_android_pid "$package")" == "$android_pid" \
    && "$(archphene_linux_loader_pid "$android_pid")" == "$linux_pid" ]] \
  || archphene_die 'closing Kate secondary window disturbed the primary process'

logs="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
printf '%s\n' "$logs" >"$artifact_dir/logcat.txt"
[[ "$logs" != *'FATAL EXCEPTION'* && "$logs" != *'Protocol error'* ]] \
  || archphene_die 'Kate workflow produced an Android crash or Wayland protocol error'
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" --field 'app=Kate' \
  --field 'state=tabs, vertical split, named session, secondary window' \
  --field "primaryWindow=$main_window" \
  --field "secondaryWindow=$secondary_window" \
  --artifact "$artifact_dir/tabs.xml" \
  --artifact "$artifact_dir/vertical-split.xml" \
  --artifact "$artifact_dir/vertical-split.raw" \
  --artifact "$artifact_dir/vertical-split.png" \
  --artifact "$artifact_dir/session-manager.xml" \
  --artifact "$artifact_dir/secondary-window.png" \
  --artifact "$artifact_dir/logcat.txt"

restore_state
trap - EXIT
archphene_note "Kate workflows passed on $serial: actionable tabs, bounded vertical split, named session discovery, and an independently closable secondary Linux window remained in one Android/Linux process pair."
