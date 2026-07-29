#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

abi=x86_64
serial=emulator-5554
timeout=30
clean_data=false
skip_install=true
while (($#)); do
  case "$1" in
    --android-abi) abi="${2:?}"; shift 2 ;;
    --serial) serial="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --clean-data) clean_data=true; shift ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 [--android-abi x86_64|arm64-v8a] [--serial SERIAL] [--timeout-seconds N] [--install-apk] --clean-data"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$clean_data" == true ]] ||
  archphene_die "--clean-data is required because this gate clears accessibility-probe app data"
archphene_validate_choice "$abi" ABI x86_64 arm64-v8a
archphene_test_init "$serial"

apk="$ARCHPHENE_ROOT/prototypes/accessibility-capability-probe/out-$abi/archphene-accessibility-probe.apk"
package=org.archphene.accessibilityprobe
activity="$package/org.archphene.bridge.AccessibilityProbeActivity"
service="$package/org.archphene.bridge.ProbeAccessibilityService"
old_services="$(archphene_adb_run shell settings get secure \
  enabled_accessibility_services | tr -d '\r')"
old_enabled="$(archphene_adb_run shell settings get secure \
  accessibility_enabled | tr -d '\r')"

restore() {
  if [[ -z "$old_services" || "$old_services" == null ]]; then
    archphene_adb_run shell settings delete secure \
      enabled_accessibility_services >/dev/null 2>&1 || true
  else
    archphene_adb_run shell settings put secure \
      enabled_accessibility_services "$old_services" >/dev/null 2>&1 || true
  fi
  if [[ -z "$old_enabled" || "$old_enabled" == null ]]; then
    archphene_adb_run shell settings delete secure \
      accessibility_enabled >/dev/null 2>&1 || true
  else
    archphene_adb_run shell settings put secure \
      accessibility_enabled "$old_enabled" >/dev/null 2>&1 || true
  fi
}
trap restore EXIT

start_probe() {
  local deadline socket
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    socket="$(archphene_adb_run shell run-as "$package" \
      cat files/accessibility-broker-name 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$socket" ]]; then
      printf '%s' "$socket"
      return 0
    fi
    sleep 0.2
  done
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneCapabilities:I AndroidRuntime:E '*:S' >&2
  archphene_die 'accessibility broker did not start'
}

native_path() {
  local native_dir subdirectory
  native_dir="$(archphene_adb_run shell dumpsys package "$package" \
    | sed -n 's/.*legacyNativeLibraryDir=\([^[:space:]]*\).*/\1/p' \
    | head -n1 | tr -d '\r')"
  [[ -n "$native_dir" ]] \
    || archphene_die 'accessibility native library directory is unavailable'
  if [[ "$abi" == arm64-v8a ]]; then subdirectory=arm64; else subdirectory=x86_64; fi
  printf '%s/%s/libarchphene_accessibility_probe.so' "$native_dir" "$subdirectory"
}

invoke_probe() {
  local allow_failure="$1" socket="$2"
  shift 2
  local output status native
  native="$(native_path)"
  set +e
  output="$(archphene_adb_run shell run-as "$package" "$native" \
    --socket "@$socket" "$@" 2>&1)"
  status=$?
  set -e
  output="${output//$'\r'/}"
  if [[ "$allow_failure" == false && $status -ne 0 ]]; then
    archphene_die "accessibility request failed: $output"
  fi
  printf '%s' "$output"
}

dump_accessibility() {
  local contains="${1:-}" absent="${2:-}" deadline tree
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    tree="$(archphene_adb_run shell run-as "$package" \
      cat files/framework-accessibility-tree.txt 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$tree" && ( -z "$contains" || "$tree" == *"$contains"* ) \
        && ( -z "$absent" || "$tree" != *"$absent"* ) ]]; then
      printf '%s' "$tree"
      return 0
    fi
    sleep 0.2
  done
  archphene_die 'Android AccessibilityService did not receive the expected virtual tree'
}

queue_action() {
  local node="$1" action="$2" provider="${3:-}" expect_rejected="${4:-false}"
  local args=(shell am start -n "$activity" --ei archphene_node "$node"
    --es archphene_action "$action")
  [[ -z "$provider" ]] || args+=(--es archphene_provider "$provider")
  [[ "$expect_rejected" == false ]] \
    || args+=(--ez archphene_expect_rejected true)
  archphene_adb_run "${args[@]}" >/dev/null
}

assert_queue_empty() {
  local value
  value="$(invoke_probe true "$socket" take-accessibility-action 0)"
  [[ "$value" == $'ERROR\tEMPTY' ]] || archphene_die "$1: $value"
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk"
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run logcat -c
socket="$(start_probe)"
archphene_adb_run shell settings put secure enabled_accessibility_services "$service"
archphene_adb_run shell settings put secure accessibility_enabled 1
archphene_wait_log 'Accessibility service connected' "$timeout" \
  'ArchpheneAccessibilityProbe:I AndroidRuntime:E *:S' >/dev/null

assert_queue_empty 'accessibility action queue was not initially empty'
published="$(invoke_probe false "$socket" publish-accessibility-tree \
  files/accessibility-tree.json)"
[[ "$published" == OK ]] || archphene_die "tree publication failed: $published"

queue_action 2 click
republished="$(invoke_probe false "$socket" publish-accessibility-tree \
  files/accessibility-tree.json)"
[[ "$republished" == OK ]] || archphene_die "tree refresh failed: $republished"
preserved="$(invoke_probe false "$socket" take-accessibility-action 250)"
[[ "$preserved" == $'OK\t2\tclick\t' ]] \
  || archphene_die "semantic refresh dropped a pending action: $preserved"

sleep 1.5
event="$(invoke_probe false "$socket" accessibility-event 0 content)"
[[ "$event" == OK ]] || archphene_die "accessibility readiness event failed: $event"
secondary_tree="$(dump_accessibility 'Secondary accessible button')"
(( $(grep -c '^WINDOW|' <<<"$secondary_tree") >= 1 )) \
  || archphene_die 'Android did not expose the active secondary accessibility window'
read -r display_width display_height <<<"$(archphene_adb_run shell wm size \
  | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
python3 -c '
import re, sys
width, height = map(int, sys.argv[1:])
text = sys.stdin.read()
match = re.search(
    r"(?m)^\d+\|android\.widget\.Button\|Secondary accessible button\|[^|]*\|"
    r"(-?\d+) (-?\d+) (-?\d+) (-?\d+)$", text
)
if match is None:
    raise SystemExit("secondary accessible button is missing")
left, top, right, bottom = map(int, match.groups())
if left < 0 or top < 0 or right <= left or bottom <= top or right > width or bottom > height:
    raise SystemExit(f"secondary bounds are outside {width}x{height}: {match.group(0)}")
' "$display_width" "$display_height" <<<"$secondary_tree"

archphene_adb_run shell am start -n "$activity" \
  --ez archphene_reorder_windows true >/dev/null
sleep 0.25
queue_action 12 click secondary
secondary_click="$(invoke_probe false "$socket" take-accessibility-action 250)"
[[ "$secondary_click" == $'OK\t12\tclick\t' ]] \
  || archphene_die "secondary click was not routed to Linux: $secondary_click"
queue_action 12 click primary true
assert_queue_empty 'secondary node leaked into the primary provider'

archphene_adb_run shell run-as "$package" rm -f \
  files/framework-accessibility-tree.txt
archphene_adb_run shell am start -n "$activity" \
  --ez archphene_hide_secondary true >/dev/null
primary_tree="$(dump_accessibility 'Archphene accessible button' \
  'Secondary accessible button')"
for control in 'Archphene accessible button' 'Accessible editor' 'Scrollable list'; do
  [[ "$primary_tree" == *"$control"* ]] \
    || archphene_die "primary accessibility tree is missing $control"
done
(( $(grep -c '^WINDOW|' <<<"$primary_tree") >= 1 )) \
  || archphene_die 'Android did not expose the restored primary accessibility window'

queue_action 4 scroll-forward
scroll="$(invoke_probe false "$socket" take-accessibility-action 250)"
[[ "$scroll" == $'OK\t4\tscroll-forward\t' ]] \
  || archphene_die "Android scroll was not routed to Linux: $scroll"
queue_action 5 click '' true
assert_queue_empty 'disabled accessibility action reached Linux'

oversized="$(printf 'x%.0s' {1..1025})"
archphene_adb_run shell am start -n "$activity" --ei archphene_node 3 \
  --es archphene_action set-text --es archphene_text "$oversized" \
  --ez archphene_expect_rejected true >/dev/null
assert_queue_empty 'oversized accessibility text reached Linux'
multibyte="$(python3 -c 'print("€" * 400, end="")')"
archphene_adb_run shell am start -n "$activity" --ei archphene_node 3 \
  --es archphene_action set-text --es archphene_text "$multibyte" \
  --ez archphene_expect_rejected true >/dev/null
assert_queue_empty 'oversized multibyte accessibility text reached Linux'

event="$(invoke_probe false "$socket" accessibility-event 3 text)"
[[ "$event" == OK ]] || archphene_die "accessibility event publication failed: $event"
invalid="$(invoke_probe true "$socket" publish-accessibility-tree \
  files/bad-accessibility-tree.json)"
[[ "$invalid" == $'ERROR\tINVALID_REQUEST' ]] \
  || archphene_die "cyclic accessibility tree was accepted: $invalid"
dump_accessibility 'Archphene accessible button' >/dev/null

queue_action 2 click
click="$(invoke_probe false "$socket" take-accessibility-action 250)"
[[ "$click" == $'OK\t2\tclick\t' ]] \
  || archphene_die "Android click was not routed to Linux: $click"
archphene_adb_run shell am start -n "$activity" --ei archphene_node 3 \
  --es archphene_action set-text --es archphene_text edited-from-Android >/dev/null
edit="$(invoke_probe false "$socket" take-accessibility-action 250)"
[[ "$edit" == $'OK\t3\tset-text\tZWRpdGVkLWZyb20tQW5kcm9pZA' ]] \
  || archphene_die "Android set-text was not routed to Linux: $edit"

archphene_adb_run shell am force-stop "$package" >/dev/null
stale="$(invoke_probe true "$socket" take-accessibility-action 0)"
archphene_regex_contains "$stale" \
  'Connection refused|No such file|Archphene Android capability request' \
  || archphene_die "stopped accessibility broker accepted work: $stale"
logs="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneCapabilities:I AndroidRuntime:E '*:S')"
[[ "$logs" != *'FATAL EXCEPTION'* ]] || archphene_die "accessibility probe crashed: $logs"

archphene_note "Android accessibility bridge passed on $serial ($abi): sticky two-window ownership, cross-window rejection, virtual trees, normalized bounds, events, invalid-tree rollback, reverse actions, input bounds, and lifecycle validated."
