#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
timeout=30
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--package PACKAGE] [--timeout-seconds SECONDS]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 15 && timeout <= 120)) \
  || archphene_die '--timeout-seconds must be 15..120'

archphene_test_init "$serial"
activity="$(archphene_launcher "$package")"
safe_serial="${serial//[^A-Za-z0-9_.-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/mousepad-open-dialog}"
mkdir -p "$artifact_dir"
probe=org.archphene.accessibilityprobe
service="$probe/org.archphene.bridge.ProbeAccessibilityService"
safe_package="${package//[^A-Za-z0-9_.-]/_}"
tree_file="files/framework-accessibility-tree-$safe_package.txt"
command_file=files/framework-accessibility-command.txt
response_file=files/framework-accessibility-response.txt
old_services="$(archphene_adb_run shell settings get secure \
  enabled_accessibility_services | tr -d '\r')"
old_accessibility="$(archphene_adb_run shell settings get secure \
  accessibility_enabled | tr -d '\r')"

cleanup() {
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$probe" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f /sdcard/archphene-mousepad-open.xml \
    >/dev/null 2>&1 || true
  if [[ "$old_services" == null || -z "$old_services" ]]; then
    archphene_adb_run shell settings delete secure \
      enabled_accessibility_services >/dev/null 2>&1 || true
  else
    archphene_adb_run shell settings put secure \
      enabled_accessibility_services "$old_services" >/dev/null 2>&1 || true
  fi
  if [[ "$old_accessibility" == null || -z "$old_accessibility" ]]; then
    archphene_adb_run shell settings delete secure accessibility_enabled \
      >/dev/null 2>&1 || true
  else
    archphene_adb_run shell settings put secure accessibility_enabled \
      "$old_accessibility" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

ime_shown() {
  local state
  state="$(archphene_adb_run shell dumpsys input_method 2>/dev/null || true)"
  archphene_regex_contains "$state" \
    '(mInputShown|isInputViewShown|inputShown|showRequested)=true|mImeWindowVis=0x[23]'
}

wait_ime_state() {
  local expected="$1" description="$2" deadline=$((SECONDS + 8)) shown
  while ((SECONDS < deadline)); do
    shown=false
    ime_shown && shown=true
    [[ "$shown" == "$expected" ]] && return 0
    sleep .25
  done
  archphene_die "Android IME did not become $description"
}

get_tree() {
  archphene_adb_run shell run-as "$probe" cat "$tree_file" \
    2>/dev/null | tr -d '\r' || true
}

base64url() {
  printf '%s' "$1" | base64 -w0 | tr '+/' '-_' | tr -d '='
}

invoke_accessibility_action() {
  local selector="$1" action="${2:-click}" value="${3:-}" required id payload
  local response deadline tree
  case "$action" in
    click) required=16 ;;
    focus) required=1 ;;
    set-text) required=2097152 ;;
    *) archphene_die "unsupported accessibility action: $action" ;;
  esac
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    tree="$(get_tree)"
    if python3 -c '
import sys
selector, required = sys.argv[1], int(sys.argv[2])
for line in sys.stdin:
    fields = line.rstrip("\r\n").split("|")
    if len(fields) < 12 or fields[0] != "NODE" or fields[6] != "true":
        continue
    if selector not in (fields[3], fields[4]):
        continue
    if int(fields[-1]) & required:
        raise SystemExit(0)
raise SystemExit(1)
' "$selector" "$required" <<<"$tree"; then
      break
    fi
    sleep .1
  done
  ((SECONDS < deadline)) \
    || archphene_die "Mousepad node '$selector' lacks Android action $action"
  id="open-$(printf '%012x' "$((RANDOM << 16 | RANDOM))")"
  payload="$id"$'\t'"$package"$'\t'"$action"$'\t'"$(base64url "$selector")"$'\t'"$(base64url "$value")"
  archphene_adb_run shell run-as "$probe" rm -f "$response_file"
  printf '%s' "$payload" | "$ARCHPHENE_ADB" "${ARCHPHENE_ADB_ARGS[@]}" \
    shell run-as "$probe" tee "$command_file" >/dev/null
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    response="$(archphene_adb_run shell run-as "$probe" \
      cat "$response_file" 2>/dev/null | tr -d '\r\n' || true)"
    if [[ "$response" == "$id"$'\t'* ]]; then
      [[ "$response" == "$id"$'\t'OK ]] \
        || archphene_die "Mousepad Android action '$action $selector' was rejected"
      return 0
    fi
    sleep .1
  done
  archphene_die "timed out waiting for Mousepad Android action '$action $selector'"
}

wait_result_state() {
  local selected="$1" open_enabled="$2" deadline=$((SECONDS + timeout)) tree
  while ((SECONDS < deadline)); do
    tree="$(get_tree)"
    if python3 -c '
import sys
fixture, selected, open_enabled = sys.argv[1:]
dialog = open_button = None
results = []
def bounds(fields):
    values = [int(value) for value in fields[5].split()]
    if len(values) != 4:
        raise SystemExit(1)
    return values
def area(fields):
    left, top, right, bottom = bounds(fields)
    return max(0, right - left) * max(0, bottom - top)
for line in sys.stdin:
    fields = line.rstrip("\r\n").split("|")
    if len(fields) < 12 or fields[0] != "NODE":
        continue
    if "Open File" in (fields[3], fields[4]):
        if dialog is None or area(fields) > area(dialog):
            dialog = fields
    if fixture in (fields[3], fields[4]) and fields[7] == "true":
        results.append(fields)
    if "Open" in (fields[3], fields[4]) and fields[2] == "android.widget.Button":
        open_button = fields
if dialog is None or not results or open_button is None:
    raise SystemExit(1)
if selected == "true":
    matches = [result for result in results if result[10] == "true"]
else:
    matches = results if all(result[10] == "false" for result in results) else []
if not matches or open_button[6] != open_enabled:
    raise SystemExit(1)
result = matches[0]
dl, dt, dr, db = bounds(dialog)
rl, rt, rr, rb = bounds(result)
if not (dl <= rl < rr <= dr and dt <= rt < rb <= db):
    raise SystemExit("result bounds escape the Open dialog")
if (rl, rt, rr, rb) == (dl, dt, dr, db) or rr - rl < 20 or rb - rt < 20:
    raise SystemExit(1)
' "$fixture" "$selected" "$open_enabled" <<<"$tree" 2>/dev/null; then
      return 0
    fi
    sleep .1
  done
  archphene_die "Mousepad result '$fixture' did not publish selected=$selected and Open enabled=$open_enabled"
}

wait_node() {
  local class_name="$1" selector="$2" clickable="$3"
  local deadline=$((SECONDS + timeout)) tree line
  while ((SECONDS < deadline)); do
    tree="$(get_tree)"
    line="$(python3 -c '
import sys
class_name, selector, clickable = sys.argv[1:]
for line in sys.stdin:
    fields = line.rstrip("\r\n").split("|")
    if len(fields) < 11 or fields[0] != "NODE":
        continue
    if class_name != "*" and fields[2] != class_name:
        continue
    if selector not in (fields[3], fields[4]):
        continue
    if clickable == "true" and fields[7] != "true":
        continue
    print(line, end="")
    raise SystemExit(0)
raise SystemExit(1)
' "$class_name" "$selector" "$clickable" <<<"$tree" 2>/dev/null || true)"
    if [[ -n "$line" ]]; then
      printf '%s' "$line"
      return 0
    fi
    sleep .2
  done
  archphene_die "Mousepad accessibility tree lacks $class_name node '$selector'"
}

archphene_adb_run shell pm path "$probe" >/dev/null 2>&1 \
  || archphene_die 'the test AccessibilityService is not installed'
archphene_adb_run shell am start -W -n \
  "$probe/org.archphene.bridge.AccessibilityProbeActivity" >/dev/null
archphene_adb_run shell run-as "$probe" rm -f \
  "$tree_file" "$command_file" "$response_file"
archphene_adb_run shell settings put secure enabled_accessibility_services "$service"
archphene_adb_run shell settings put secure accessibility_enabled 1
archphene_wait_log 'Accessibility service connected' 10 \
  'ArchpheneAccessibilityProbe:I AndroidRuntime:E *:S' >/dev/null

recent="$(archphene_adb_run shell run-as "$package" \
  cat files/linux-home/.local/share/recently-used.xbel 2>/dev/null || true)"
fixture_path=
fixture=
while IFS=$'\t' read -r candidate_path candidate_name; do
  if archphene_adb_run shell run-as "$package" test -f "$candidate_path" \
      </dev/null >/dev/null 2>&1; then
    fixture_path="$candidate_path"
    fixture="$candidate_name"
    break
  fi
done < <(python3 -c '
import html, os, re, sys
from urllib.parse import unquote
entries = re.findall(r"href=\"file://([^\"?]+)", sys.stdin.read())
for encoded in reversed(entries):
    path = unquote(html.unescape(encoded))
    name = os.path.basename(path)
    if name.lower().endswith(".txt") and not any(char.isspace() for char in name):
        print(path, name, sep="\t")
' <<<"$recent")
[[ -n "$fixture_path" && -n "$fixture" ]] \
  || archphene_die 'Mousepad has no present indexed document; run its Android document workflow first'
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W --windowingMode 1 -n "$activity" >/dev/null
archphene_wait_log 'mapped=true.*title=.*Mousepad' "$timeout" \
  'ArchpheneInput:I AndroidRuntime:E *:S' >/dev/null
activities="$(archphene_adb_run shell dumpsys activity activities)"
archphene_regex_contains "$activities" \
  "topResumedActivity=.*${package//./\\.}/" \
  || archphene_die 'Mousepad is not the resumed Android Activity'

# A forced stop can make Mousepad offer to restore the interrupted session. The
# left response is "No"; on a clean start this harmlessly focuses the editor.
read -r width height <<<"$(archphene_adb_run shell wm size \
  | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
[[ -n "${width:-}" && -n "${height:-}" ]] \
  || archphene_die 'unable to read Android display size'
status_top="$(archphene_adb_run shell dumpsys window \
  | sed -n 's/.*type=statusBars frame=\[[^]]*\]\[[0-9]*,\([0-9]*\)\].*/\1/p' \
  | head -n1)"
[[ "$status_top" =~ ^[0-9]+$ ]] \
  || archphene_die 'unable to read Android status-bar inset'
archphene_adb_run shell input tap "$((width / 4))" "$((height * 3 / 5))"
sleep .5
ime_shown && archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null

archphene_adb_run logcat -c
archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_O
open_log="$(archphene_wait_log 'mapped=true.*title=Open File' "$timeout" \
  'ArchpheneInput:I AndroidRuntime:E *:S')"
read -r content_x content_y content_width content_height \
    canvas_width canvas_height frame_x frame_y frame_width frame_height \
    <<<"$(python3 -c '
import re, sys
matches = re.findall(
    r"mapped=true.*content=(-?\d+),(-?\d+) (\d+)x(\d+) "
    r"canvas=(\d+)x(\d+) compositedFrame=(-?\d+),(-?\d+) "
    r"(\d+)x(\d+).*title=Open File",
    sys.stdin.read(),
)
if not matches:
    raise SystemExit("Open dialog did not publish composited frame geometry")
print(*matches[-1])
' <<<"$open_log")"
((frame_x >= 0 && frame_y >= 0
    && frame_x + frame_width <= canvas_width
    && frame_y + frame_height <= canvas_height)) \
  || archphene_die 'Open dialog frame is outside its Android viewport'
((content_x >= 0 && content_y >= 0
    && content_x + content_width <= canvas_width
    && content_y + content_height <= canvas_height)) \
  || archphene_die 'Open dialog content is outside its Android viewport'

wait_node '*' Search true >/dev/null
archphene_adb_run shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_F
wait_node android.widget.TextView Search true >/dev/null
# GTK on physical ARM does not request text-input-v3 merely because Ctrl+F
# moved Linux focus. Touch the visible Search entry, as a phone user does; the
# coordinate is derived from the finalized dialog frame rather than a device
# or Mousepad-specific constant.
search_x=$((frame_x + frame_width / 2))
search_y=$((status_top + frame_y + frame_height * 11 / 100))
archphene_adb_run shell input tap "$search_x" "$search_y"
wait_ime_state true 'shown for Open dialog Search'

encoded="$(printf '%s' "$fixture" | base64 -w0)"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --es archphene_test_ime_commit_base64 "$encoded" >/dev/null
injection_log="$(archphene_wait_log \
  "Injected test IME preeditBytes=0 commitBytes=${#fixture} submit=false" \
  10 'ArchpheneInput:I AndroidRuntime:E *:S')"
[[ "$injection_log" != *'FATAL EXCEPTION'* ]] \
  || archphene_die "Mousepad crashed during IME injection: $injection_log"
wait_ime_state true 'retained after the Search query'

sleep 1
archphene_adb_run exec-out screencap -p >"$artifact_dir/search-result.png"

archphene_adb_run shell input keyevent KEYCODE_BACK
wait_ime_state false 'hidden after Android Back'
wait_node '*' 'Open File' false >/dev/null
sleep .7
archphene_adb_run exec-out screencap >"$artifact_dir/result-unselected.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/result-unselected.png"

archphene_adb_run logcat -c
wait_result_state false false
invoke_accessibility_action "$fixture"
wait_result_state true true
sleep .5
archphene_adb_run exec-out screencap >"$artifact_dir/result-selected.raw"
archphene_adb_run exec-out screencap -p >"$artifact_dir/result-selected.png"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/result-unselected.raw" "$artifact_dir/result-selected.raw" \
  --left-percent 20 --right-percent 98 --top-percent 20 --bottom-percent 50 \
  --minimum-difference .2 --minimum-changed-ratio .002

archphene_adb_run logcat -c
invoke_accessibility_action Open
fixture_pattern="$(python3 -c \
  'import re,sys;print(re.escape(sys.argv[1]))' "$fixture")"
archphene_wait_log "mapped=true.*title=.*$fixture_pattern.*Mousepad" 15 \
  'ArchpheneInput:I AndroidRuntime:E *:S' >/dev/null
deadline=$((SECONDS + 10))
while ((SECONDS < deadline)); do
  [[ "$(get_tree)" != *'|Open File|'* ]] && break
  sleep .2
done
((SECONDS < deadline)) \
  || archphene_die 'Mousepad Open dialog remained after its Android Open action'

cleanup
trap - EXIT
archphene_note "Mousepad Open dialog IME/accessibility passed on $serial: bounded live control geometry, retained keyboard, exact indexed InputConnection query, Back dismissal, semantic result selection, live selected/enabled state, rendered selection, and semantic Open verified. Evidence: $artifact_dir"
