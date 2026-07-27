#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"
source "$(dirname "$0")/lib/performance-probe.sh"

serial=
apk=
launcher_package=
skip_install=false
window_seconds=30
active_windows=4
idle_windows=4
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --launcher-package)
      launcher_package="${2:?missing value for --launcher-package}"
      shift 2
      ;;
    --window-seconds)
      window_seconds="${2:?missing value for --window-seconds}"
      shift 2
      ;;
    --active-windows)
      active_windows="${2:?missing value for --active-windows}"
      shift 2
      ;;
    --idle-windows)
      idle_windows="${2:?missing value for --idle-windows}"
      shift 2
      ;;
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --launcher-package PACKAGE [--skip-install] [--window-seconds N] [--active-windows N] [--idle-windows N]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$launcher_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--launcher-package must be a generated Archphene launcher"
[[ "$window_seconds" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "--window-seconds must be a positive integer"
[[ "$active_windows" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "--active-windows must be a positive integer"
[[ "$idle_windows" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "--idle-windows must be a positive integer"

archphene_test_init "$serial"
archphene_require_file "$apk"
manager=org.archphene.app.debug
manager_activity="$manager/org.archphene.app.MainActivity"
performance_probe_init
launcher_activity="$(archphene_launcher "$launcher_package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
output_dir="$ARCHPHENE_ROOT/tooling/build/performance-soak/$safe_serial"
windows_file="$output_dir/windows.tsv"
mkdir -p "$output_dir"
: >"$windows_file"

max_pss_kb="${ARCHPHENE_MAX_SOAK_PSS_KB:-163840}"
max_rss_kb="${ARCHPHENE_MAX_SOAK_RSS_KB:-307200}"
max_java_heap_kb="${ARCHPHENE_MAX_SOAK_JAVA_HEAP_KB:-65536}"
max_native_heap_kb="${ARCHPHENE_MAX_SOAK_NATIVE_HEAP_KB:-98304}"
max_threads="${ARCHPHENE_MAX_SOAK_THREADS:-64}"
max_fds="${ARCHPHENE_MAX_SOAK_FDS:-256}"
max_uid_processes="${ARCHPHENE_MAX_SOAK_UID_PROCESSES:-4}"
max_frame_p95_ms="${ARCHPHENE_MAX_SOAK_FRAME_P95_MS:-250}"
max_latency_ms="${ARCHPHENE_MAX_SOAK_INPUT_LATENCY_MS:-500}"
max_terminal_calls_per_second="${ARCHPHENE_MAX_SOAK_TERMINAL_JNI_PER_SECOND:-32}"
max_compositor_calls_per_second="${ARCHPHENE_MAX_SOAK_COMPOSITOR_JNI_PER_SECOND:-145}"
max_direct_bytes_per_second="${ARCHPHENE_MAX_SOAK_DIRECT_BYTES_PER_SECOND:-1048576}"
max_kotlin_copy_per_second="${ARCHPHENE_MAX_SOAK_KOTLIN_COPY_PER_SECOND:-65536}"
max_art_bytes_base="${ARCHPHENE_MAX_SOAK_ART_BYTES_BASE:-2097152}"
max_art_bytes_per_second="${ARCHPHENE_MAX_SOAK_ART_BYTES_PER_SECOND:-262144}"
max_art_objects_base="${ARCHPHENE_MAX_SOAK_ART_OBJECTS_BASE:-10000}"
max_art_objects_per_second="${ARCHPHENE_MAX_SOAK_ART_OBJECTS_PER_SECOND:-2000}"
max_pss_growth_kb="${ARCHPHENE_MAX_SOAK_PSS_GROWTH_KB:-16384}"
max_rss_growth_kb="${ARCHPHENE_MAX_SOAK_RSS_GROWTH_KB:-32768}"
max_fd_growth="${ARCHPHENE_MAX_SOAK_FD_GROWTH:-8}"
max_thread_growth="${ARCHPHENE_MAX_SOAK_THREAD_GROWTH:-4}"
max_thermal_status="${ARCHPHENE_MAX_SOAK_THERMAL_STATUS:-3}"
max_temperature_millic="${ARCHPHENE_MAX_SOAK_TEMPERATURE_MILLIC:-55000}"

cleanup() {
  archphene_adb_run shell am force-stop "$launcher_package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

environment_snapshot() {
  local name="$1" battery thermal level temperature voltage thermal_status max_temp
  battery="$(archphene_adb_run shell dumpsys battery | tr -d '\r')"
  thermal="$(archphene_adb_run shell dumpsys thermalservice | tr -d '\r')"
  {
    printf '%s\n' "$battery"
    printf '%s\n' "$thermal"
  } >"$output_dir/environment-$name.txt"
  level="$(awk -F': ' '/^[[:space:]]*level:/{print $2; exit}' <<<"$battery")"
  temperature="$(awk -F': ' '/^[[:space:]]*temperature:/{print $2; exit}' <<<"$battery")"
  voltage="$(awk -F': ' '/^[[:space:]]*voltage:/{print $2; exit}' <<<"$battery")"
  thermal_status="$(awk -F': ' '/^Thermal Status:/{print $2; exit}' <<<"$thermal")"
  max_temp="$(
    awk '
      /^Current temperatures from HAL:/{inside=1; next}
      inside && /^Current cooling devices from HAL:/{inside=0}
      inside && match($0, /mValue=[0-9.]+/) {
        value=substr($0, RSTART + 7, RLENGTH - 7)
        if (value > maximum) maximum=value
      }
      END {printf "%.0f", maximum * 1000}
    ' <<<"$thermal"
  )"
  [[ "$level" =~ ^-?[0-9]+$ ]] || level=-1
  [[ "$temperature" =~ ^-?[0-9]+$ ]] || temperature=-1
  [[ "$voltage" =~ ^-?[0-9]+$ ]] || voltage=-1
  [[ "$thermal_status" =~ ^[0-9]+$ ]] || thermal_status=-1
  [[ "$max_temp" =~ ^[0-9]+$ ]] || max_temp=-1
  printf \
    'batteryLevel=%s batteryTemperatureDeciC=%s batteryVoltageMv=%s thermalStatus=%s maximumTemperatureMilliC=%s\n' \
    "$level" "$temperature" "$voltage" "$thermal_status" "$max_temp"
}

assert_environment_bounds() {
  local payload="$1" status maximum
  status="$(performance_metric "$payload" thermalStatus)"
  maximum="$(performance_metric "$payload" maximumTemperatureMilliC)"
  ((status == -1 || status <= max_thermal_status)) ||
    archphene_die "thermal status exceeded $max_thermal_status: $status"
  ((maximum == -1 || maximum <= max_temperature_millic)) ||
    archphene_die "temperature exceeded ${max_temperature_millic}mC: ${maximum}mC"
}

assert_window_metrics() {
  local surface="$1" mode="$2" payload="$3"
  local terminal_limit compositor_limit direct_limit kotlin_limit art_bytes_limit art_objects_limit
  terminal_limit=$((max_terminal_calls_per_second * window_seconds))
  compositor_limit=$((max_compositor_calls_per_second * window_seconds + 32))
  direct_limit=$((max_direct_bytes_per_second * window_seconds))
  kotlin_limit=$((max_kotlin_copy_per_second * window_seconds))
  art_bytes_limit=$((max_art_bytes_base + max_art_bytes_per_second * window_seconds))
  art_objects_limit=$((max_art_objects_base + max_art_objects_per_second * window_seconds))

  assert_at_most "$payload" terminalCalls "$terminal_limit"
  assert_at_most "$payload" compositorCalls "$compositor_limit"
  assert_at_most "$payload" terminalDirectIn "$direct_limit"
  assert_at_most "$payload" terminalDirectOut "$direct_limit"
  assert_at_most "$payload" compositorDirectIn "$direct_limit"
  assert_at_most "$payload" compositorDirectOut "$direct_limit"
  assert_at_most "$payload" terminalKotlinCopy "$kotlin_limit"
  assert_at_most "$payload" compositorKotlinCopy "$kotlin_limit"
  assert_at_most "$payload" jniArrayCopy 0
  assert_at_most "$payload" terminalLatencyMaxMs "$max_latency_ms"
  assert_at_most "$payload" launcherLatencyMaxMs "$max_latency_ms"
  assert_art_bound "$payload" artBytesAllocated "$art_bytes_limit"
  assert_art_bound "$payload" artObjectsAllocated "$art_objects_limit"
  assert_art_bound "$payload" artGcCount 0
  assert_art_bound "$payload" artBlockingGcCount 0

  if [[ "$surface" == terminal ]]; then
    assert_at_most "$payload" compositorCalls 0
    if [[ "$mode" == active ]]; then
      assert_at_least "$payload" terminalCalls 1
      assert_at_least "$payload" terminalLatencySamples 1
    fi
  else
    assert_at_most "$payload" terminalCalls 0
    assert_at_least "$payload" compositorDispatchCalls "$window_seconds"
    if [[ "$mode" == active ]]; then
      assert_at_least "$payload" compositorInputCalls 1
      assert_at_least "$payload" launcherLatencySamples 1
    fi
  fi
}

run_window() {
  local surface="$1" mode="$2" index="$3" tick metrics resources environment name
  name="$surface-$mode-$index"
  archphene_adb_run shell dumpsys gfxinfo "$manager" reset >/dev/null
  reset_metrics
  if [[ "$mode" == active ]]; then
    for ((tick = 0; tick < window_seconds; tick++)); do
      if ((tick % 2 == 0)); then
        archphene_adb_run shell input keyevent KEYCODE_A >/dev/null
      else
        archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
      fi
      sleep 1
    done
  else
    sleep "$window_seconds"
  fi
  metrics="$(snapshot_metrics)"
  resources="$(resource_snapshot)"
  environment="$(environment_snapshot "$name")"
  assert_window_metrics "$surface" "$mode" "$metrics"
  assert_resource_bounds "$resources"
  assert_environment_bounds "$environment"
  assert_no_fatal_logs "$name soak window"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$surface" "$mode" "$index" "$metrics" "$resources" "$environment" \
    >>"$windows_file"
  archphene_note "  $name passed"
}

run_surface_windows() {
  local surface="$1" index
  sleep 5
  for ((index = 1; index <= active_windows; index++)); do
    run_window "$surface" active "$index"
  done
  archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
  for ((index = 1; index <= idle_windows; index++)); do
    run_window "$surface" idle "$index"
  done
}

if [[ "$skip_install" == false ]]; then
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run shell am force-stop "$launcher_package" >/dev/null
archphene_adb_run logcat -b crash -c
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
archphene_wait_log \
  'Package runtime ready:.*Pacman v[0-9]' 30 \
  'ArchpheneRuntime:V AndroidRuntime:E *:S' >/dev/null

archphene_open_manager_section Terminal "performance-soak-terminal-$safe_serial"
archphene_wait_ui 'text="Start shell"' "performance-soak-start-$safe_serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui \
  '(?:archphene:~|sh-[0-9.]+)\$' \
  "performance-soak-prompt-$safe_serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
run_surface_windows terminal
archphene_adb_run shell input keyevent KEYCODE_A >/dev/null
sleep 1
archphene_adb_run exec-out screencap -p >"$output_dir/terminal.png"
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Stop shell"' "performance-soak-stop-$safe_serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "performance-soak-stopped-$safe_serial" 20

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$launcher_activity" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected session=' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  'Presented Linux frame session=.*attachmentFrame=1' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
run_surface_windows foot
archphene_adb_run shell input keyevent KEYCODE_A >/dev/null
sleep 1
archphene_adb_run exec-out screencap -p >"$output_dir/foot.png"
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
assert_no_fatal_logs "complete sustained performance run"

python3 - \
  "$windows_file" "$output_dir/report.json" "$serial" \
  "$window_seconds" "$active_windows" "$idle_windows" \
  "$max_pss_growth_kb" "$max_rss_growth_kb" "$max_fd_growth" \
  "$max_thread_growth" <<'PY'
import json
import math
import sys

source, destination, serial = sys.argv[1:4]
window_seconds, active_windows, idle_windows = map(int, sys.argv[4:7])
growth_limits = dict(
    totalPssKb=int(sys.argv[7]),
    totalRssKb=int(sys.argv[8]),
    fds=int(sys.argv[9]),
    threads=int(sys.argv[10]),
)

def fields(payload):
    result = {}
    for field in payload.split():
        key, value = field.split("=", 1)
        result[key] = int(value)
    return result

windows = []
with open(source, encoding="utf-8") as input_file:
    for line in input_file:
        surface, mode, index, metrics, resources, environment = line.rstrip("\n").split("\t")
        windows.append({
            "surface": surface,
            "mode": mode,
            "index": int(index),
            "metrics": fields(metrics),
            "resources": fields(resources),
            "environment": fields(environment),
        })

def percentile(values, percentage):
    values = sorted(values)
    if not values:
        return None
    return values[math.ceil(len(values) * percentage) - 1]

summaries = {}
for surface in ("terminal", "foot"):
    selected = [window for window in windows if window["surface"] == surface]
    first, last = selected[0]["resources"], selected[-1]["resources"]
    final_growth = {key: last[key] - first[key] for key in growth_limits}
    peak_growth = {
        key: max(window["resources"][key] for window in selected) - first[key]
        for key in growth_limits
    }
    for key, limit in growth_limits.items():
        if peak_growth[key] > limit:
            raise SystemExit(
                f"{surface} peak {key} growth exceeded {limit}: {peak_growth[key]}"
            )
    summaries[surface] = {
        "final_growth": final_growth,
        "peak_growth": peak_growth,
        "art_bytes_p50": percentile(
            [window["metrics"]["artBytesAllocated"] for window in selected], .50
        ),
        "art_bytes_p95": percentile(
            [window["metrics"]["artBytesAllocated"] for window in selected], .95
        ),
        "art_bytes_max": max(
            window["metrics"]["artBytesAllocated"] for window in selected
        ),
        "latency_p95_ms": percentile(
            [
                max(
                    window["metrics"]["terminalLatencyMaxMs"],
                    window["metrics"]["launcherLatencyMaxMs"],
                )
                for window in selected
                if window["mode"] == "active"
            ],
            .95,
        ),
        "maximum_thermal_status": max(
            window["environment"]["thermalStatus"] for window in selected
        ),
        "maximum_temperature_millic": max(
            window["environment"]["maximumTemperatureMilliC"] for window in selected
        ),
    }

report = {
    "serial": serial,
    "protocol": {
        "window_seconds": window_seconds,
        "active_windows": active_windows,
        "idle_windows": idle_windows,
        "total_seconds": window_seconds * (active_windows + idle_windows) * 2,
    },
    "summary": summaries,
    "windows": windows,
}
with open(destination, "w", encoding="utf-8") as output:
    json.dump(report, output, indent=2, sort_keys=True)
    output.write("\n")
PY

trap - EXIT
cleanup
archphene_note "Sustained Terminal and Foot performance gate passed on $serial"
archphene_note "  Protocol: ${active_windows}x${window_seconds}s active + ${idle_windows}x${window_seconds}s idle per surface"
archphene_note "  Report: $output_dir/report.json"
archphene_note "  Full-device screenshots: $output_dir/{terminal,foot}.png"
