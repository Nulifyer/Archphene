#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.app.debug
activity=org.archphene.app.MainActivity
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing serial}"; shift 2 ;;
    --package) package="${2:?missing package}"; shift 2 ;;
    --activity) activity="${2:?missing activity}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--package PACKAGE] [--activity CLASS]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
component="$package/$activity"
output_dir="$ARCHPHENE_ROOT/tooling/build/performance"
mkdir -p "$output_dir"
report="$output_dir/$serial.json"
screenshot="$output_dir/$serial.png"

max_cold_ms="${ARCHPHENE_MAX_COLD_MS:-1500}"
max_hot_ms="${ARCHPHENE_MAX_HOT_MS:-750}"
max_response_ms="${ARCHPHENE_MAX_RESPONSE_MS:-2500}"
max_pss_kb="${ARCHPHENE_MAX_PSS_KB:-163840}"
max_rss_kb="${ARCHPHENE_MAX_RSS_KB:-307200}"
max_java_heap_kb="${ARCHPHENE_MAX_JAVA_HEAP_KB:-65536}"
max_native_heap_kb="${ARCHPHENE_MAX_NATIVE_HEAP_KB:-65536}"
max_threads="${ARCHPHENE_MAX_THREADS:-64}"
max_fds="${ARCHPHENE_MAX_FDS:-256}"
max_idle_children="${ARCHPHENE_MAX_IDLE_CHILDREN:-0}"
max_jank_percent="${ARCHPHENE_MAX_JANK_PERCENT:-50}"
max_frame_p95_ms="${ARCHPHENE_MAX_FRAME_P95_MS:-250}"
was_running=false
[[ -n "$(archphene_android_pid "$package" 2>/dev/null || true)" ]] && was_running=true
original_section=
section_restored=false
cleanup() {
  if [[ -n "$original_section" && "$section_restored" == false ]]; then
    local cleanup_ui
    cleanup_ui="$(archphene_capture_ui "performance-cleanup-$serial" 2>/dev/null || true)"
    if [[ -n "$cleanup_ui" ]]; then
      archphene_tap_text "$cleanup_ui" "$original_section" >/dev/null 2>&1 || true
    fi
  fi
  if [[ "$was_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
archphene_adb_run logcat -c

wait_ready() {
  archphene_wait_ui 'Pacman ready' "performance-ready-$serial" 20 >/dev/null
}

launch_time() {
  local kind="$1" output state milliseconds
  if [[ "$kind" == cold ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null
  else
    archphene_adb_run shell input keyevent KEYCODE_HOME >/dev/null
  fi
  output="$(archphene_adb_run shell am start -W -n "$component" | tr -d '\r')"
  state="$(awk -F': ' '/^LaunchState:/{print $2}' <<<"$output")"
  milliseconds="$(awk -F': ' '/^TotalTime:/{print $2}' <<<"$output")"
  if [[ -z "$milliseconds" && "$kind" == hot ]]; then
    milliseconds="$(awk -F': ' '/^WaitTime:/{print $2}' <<<"$output")"
  fi
  [[ "$milliseconds" =~ ^[0-9]+$ ]] ||
    archphene_die "could not parse $kind launch time: $output"
  if [[ "$kind" == cold ]]; then
    [[ "$state" == COLD ]] ||
      archphene_die "force-stopped launch was not cold: $state"
  else
    [[ "$state" == HOT || "$state" == WARM || "$state" == "UNKNOWN (0)" ]] ||
      archphene_die "retained-process launch was not hot/warm: $state"
  fi
  wait_ready
  printf '%s\n' "$milliseconds"
}

median_three() {
  printf '%s\n' "$1" "$2" "$3" | sort -n | sed -n '2p'
}

cold_1="$(launch_time cold)"
cold_2="$(launch_time cold)"
cold_3="$(launch_time cold)"
cold_median="$(median_three "$cold_1" "$cold_2" "$cold_3")"
cold_max="$(printf '%s\n' "$cold_1" "$cold_2" "$cold_3" | sort -n | tail -1)"

hot_1="$(launch_time hot)"
hot_2="$(launch_time hot)"
hot_3="$(launch_time hot)"
hot_median="$(median_three "$hot_1" "$hot_2" "$hot_3")"
hot_max="$(printf '%s\n' "$hot_1" "$hot_2" "$hot_3" | sort -n | tail -1)"

ui="$(archphene_capture_ui "performance-original-$serial")"
original_section="$(
  python3 -c '
import sys
import xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
sections = {"Packages", "Files", "Terminal", "Settings"}
for node in root.iter("node"):
    if node.attrib.get("selected") == "true" and node.attrib.get("text") in sections:
        print(node.attrib["text"])
        break
' <<<"$ui"
)"
[[ -n "$original_section" ]] ||
  archphene_die "could not identify the selected manager section"

archphene_adb_run shell dumpsys gfxinfo "$package" reset >/dev/null
response_start="$(date +%s%3N)"
response_section=Settings
response_marker='text="Linux app appearance"'
if [[ "$original_section" == Settings ]]; then
  response_section=Files
  response_marker='text="Android and Linux files"'
fi
archphene_tap_text "$ui" "$response_section"
deadline=$((SECONDS + 10))
while ((SECONDS < deadline)); do
  ui="$(archphene_capture_ui "performance-response-$serial" 2>/dev/null || true)"
  [[ "$ui" == *"$response_marker"* ]] && break
  sleep 0.1
done
[[ "$ui" == *"$response_marker"* ]] ||
  archphene_die "$response_section did not become accessible after input"
response_ms="$(( $(date +%s%3N) - response_start ))"

if [[ "$original_section" != "$response_section" ]]; then
  archphene_tap_text "$ui" "$original_section"
  deadline=$((SECONDS + 10))
  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "performance-restored-$serial" 2>/dev/null || true)"
    selected="$(
      python3 -c '
import sys
import xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
target = sys.argv[1]
print(any(n.attrib.get("text") == target and n.attrib.get("selected") == "true"
          for n in root.iter("node")))
' "$original_section" <<<"$ui" 2>/dev/null || true
    )"
    [[ "$selected" == True ]] && break
    sleep 0.1
  done
  [[ "$selected" == True ]] ||
    archphene_die "could not restore the original $original_section section"
fi
section_restored=true
sleep 1

pid="$(archphene_android_pid "$package")"
[[ "$pid" =~ ^[0-9]+$ ]] || archphene_die "manager process is missing"
meminfo="$(archphene_adb_run shell dumpsys meminfo "$package" | tr -d '\r')"
total_pss_kb="$(sed -n 's/.*TOTAL PSS:[[:space:]]*\([0-9]*\).*/\1/p' <<<"$meminfo" | head -1)"
total_rss_kb="$(sed -n 's/.*TOTAL RSS:[[:space:]]*\([0-9]*\).*/\1/p' <<<"$meminfo" | head -1)"
java_heap_kb="$(awk '/Java Heap:/{print $3; exit}' <<<"$meminfo")"
native_heap_kb="$(awk '/Native Heap:/{print $3; exit}' <<<"$meminfo")"
views="$(awk '/Views:/{print $2; exit}' <<<"$meminfo")"
native_allocations=unavailable
if [[ "$meminfo" == *"Native Allocations"* ]]; then
  native_allocations="$(
    awk '
      /Native Allocations/{inside=1; next}
      inside && /^$/{inside=0}
      inside && /\(malloced\)|\(nonmalloced\)|Bitmap/{sum += $3}
      END{print sum + 0}
    ' <<<"$meminfo"
  )"
fi
for metric in total_pss_kb total_rss_kb java_heap_kb native_heap_kb views; do
  value="${!metric}"
  [[ "$value" =~ ^[0-9]+$ ]] || archphene_die "could not parse $metric"
done

status="$(archphene_adb_run shell run-as "$package" cat "/proc/$pid/status" | tr -d '\r')"
threads="$(awk '/^Threads:/{print $2}' <<<"$status")"
fds="$(archphene_adb_run shell run-as "$package" ls "/proc/$pid/fd" | wc -l)"
uid="$(archphene_adb_run shell cmd package list packages -U "$package" |
  sed -n 's/.*uid://p' | tr -d '\r')"
[[ "$threads" =~ ^[0-9]+$ && "$fds" =~ ^[0-9]+$ && "$uid" =~ ^[0-9]+$ ]] ||
  archphene_die "could not parse process resources"
uid_processes="$(
  archphene_adb_run shell ps -A -o UID,PID,PPID,NAME |
    awk -v uid="$uid" '$1 == uid {count++} END {print count + 0}'
)"
idle_children="$((uid_processes - 1))"

gfxinfo="$(archphene_adb_run shell dumpsys gfxinfo "$package" | tr -d '\r')"
frames="$(awk -F': ' '/Total frames rendered:/{print $2; exit}' <<<"$gfxinfo")"
janky_line="$(awk '/^Janky frames:/{print; exit}' <<<"$gfxinfo")"
janky_frames="$(awk '{print $3}' <<<"$janky_line")"
jank_percent="$(sed -n 's/.*(\([0-9.]*\)%).*/\1/p' <<<"$janky_line")"
frame_p95_ms="$(sed -n 's/^95th percentile: \([0-9]*\)ms/\1/p' <<<"$gfxinfo" | head -1)"
[[ "$frames" =~ ^[0-9]+$ && "$janky_frames" =~ ^[0-9]+$ &&
    "$jank_percent" =~ ^[0-9.]+$ && "$frame_p95_ms" =~ ^[0-9]+$ ]] ||
  archphene_die "could not parse graphics metrics: frames=$frames janky=$janky_frames percent=$jank_percent p95=$frame_p95_ms"

((cold_max <= max_cold_ms)) ||
  archphene_die "cold launch exceeded ${max_cold_ms}ms: $cold_1/$cold_2/$cold_3"
((hot_max <= max_hot_ms)) ||
  archphene_die "hot launch exceeded ${max_hot_ms}ms: $hot_1/$hot_2/$hot_3"
((response_ms <= max_response_ms)) ||
  archphene_die "Settings response exceeded ${max_response_ms}ms: ${response_ms}ms"
((total_pss_kb <= max_pss_kb)) ||
  archphene_die "idle PSS exceeded ${max_pss_kb}KiB: ${total_pss_kb}KiB"
((total_rss_kb <= max_rss_kb)) ||
  archphene_die "idle RSS exceeded ${max_rss_kb}KiB: ${total_rss_kb}KiB"
((java_heap_kb <= max_java_heap_kb)) ||
  archphene_die "Java heap PSS exceeded ${max_java_heap_kb}KiB: ${java_heap_kb}KiB"
((native_heap_kb <= max_native_heap_kb)) ||
  archphene_die "native heap PSS exceeded ${max_native_heap_kb}KiB: ${native_heap_kb}KiB"
((threads <= max_threads)) ||
  archphene_die "idle thread count exceeded $max_threads: $threads"
((fds <= max_fds)) || archphene_die "idle FD count exceeded $max_fds: $fds"
((idle_children <= max_idle_children)) ||
  archphene_die "idle child count exceeded $max_idle_children: $idle_children"
awk -v value="$jank_percent" -v limit="$max_jank_percent" \
  'BEGIN {exit !(value <= limit)}' ||
  archphene_die "jank exceeded ${max_jank_percent}%: ${jank_percent}%"
((frame_p95_ms <= max_frame_p95_ms)) ||
  archphene_die "frame p95 exceeded ${max_frame_p95_ms}ms: ${frame_p95_ms}ms"

archphene_adb_run exec-out screencap -p >"$screenshot"
fatal_log="$(
  archphene_adb_run logcat -d -v brief -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "performance run emitted a fatal runtime error: $fatal_log"

python3 - "$report" "$serial" "$cold_1" "$cold_2" "$cold_3" \
  "$cold_median" "$hot_1" "$hot_2" "$hot_3" "$hot_median" "$response_ms" \
  "$total_pss_kb" "$total_rss_kb" "$java_heap_kb" "$native_heap_kb" \
  "$native_allocations" "$threads" "$fds" "$idle_children" "$views" \
  "$frames" "$janky_frames" "$jank_percent" "$frame_p95_ms" <<'PY'
import json
import sys

keys = (
    "serial", "cold_1_ms", "cold_2_ms", "cold_3_ms", "cold_median_ms",
    "hot_1_ms", "hot_2_ms", "hot_3_ms", "hot_median_ms",
    "settings_response_ms", "total_pss_kb", "total_rss_kb",
    "java_heap_pss_kb", "native_heap_pss_kb", "native_allocations",
    "threads", "file_descriptors", "idle_children", "views",
    "frames", "janky_frames", "jank_percent", "frame_p95_ms",
)
values = [sys.argv[2]]
for value in sys.argv[3:]:
    if value == "unavailable":
        values.append(None)
    else:
        values.append(float(value) if "." in value else int(value))
with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(dict(zip(keys, values)), output, indent=2, sort_keys=True)
    output.write("\n")
PY

archphene_note "Archphene performance gate passed on $serial"
archphene_note "  Cold median/max: ${cold_median}/${cold_max} ms; hot median/max: ${hot_median}/${hot_max} ms"
archphene_note "  Settings response: ${response_ms} ms; frame p95/jank: ${frame_p95_ms} ms/${jank_percent}%"
archphene_note "  PSS/RSS: ${total_pss_kb}/${total_rss_kb} KiB; Java/native: ${java_heap_kb}/${native_heap_kb} KiB"
archphene_note "  Threads/FDs/idle children: $threads/$fds/$idle_children; native allocations: $native_allocations"
archphene_note "  Report: $report"
archphene_note "  Full-device screenshot: $screenshot"
