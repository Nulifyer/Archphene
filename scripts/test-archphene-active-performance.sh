#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"
source "$(dirname "$0")/lib/performance-probe.sh"

serial=
apk=
launcher_package=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --launcher-package)
      launcher_package="${2:?missing value for --launcher-package}"
      shift 2
      ;;
    --install-apk) skip_install=false; shift ;;
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --launcher-package PACKAGE [--install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$launcher_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--launcher-package must be a generated Archphene launcher"

archphene_test_init "$serial"
archphene_require_file "$apk"
manager=org.archphene.app.debug
manager_activity="$manager/org.archphene.app.MainActivity"
performance_probe_init
launcher_activity="$(archphene_launcher "$launcher_package")"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
output_dir="$ARCHPHENE_ROOT/tooling/build/active-performance/$safe_serial"
mkdir -p "$output_dir"

max_terminal_calls="${ARCHPHENE_MAX_ACTIVE_TERMINAL_JNI_CALLS:-64}"
max_compositor_calls="${ARCHPHENE_MAX_ACTIVE_COMPOSITOR_JNI_CALLS:-180}"
max_latency_ms="${ARCHPHENE_MAX_ACTIVE_INPUT_LATENCY_MS:-500}"
max_art_bytes="${ARCHPHENE_MAX_ACTIVE_ART_BYTES:-2097152}"
max_art_objects="${ARCHPHENE_MAX_ACTIVE_ART_OBJECTS:-10000}"
max_direct_bytes="${ARCHPHENE_MAX_ACTIVE_DIRECT_BYTES:-1048576}"
max_kotlin_copy_bytes="${ARCHPHENE_MAX_ACTIVE_KOTLIN_COPY_BYTES:-16384}"
max_pss_kb="${ARCHPHENE_MAX_ACTIVE_PSS_KB:-163840}"
max_rss_kb="${ARCHPHENE_MAX_ACTIVE_RSS_KB:-307200}"
max_java_heap_kb="${ARCHPHENE_MAX_ACTIVE_JAVA_HEAP_KB:-65536}"
max_native_heap_kb="${ARCHPHENE_MAX_ACTIVE_NATIVE_HEAP_KB:-98304}"
max_threads="${ARCHPHENE_MAX_ACTIVE_THREADS:-64}"
max_fds="${ARCHPHENE_MAX_ACTIVE_FDS:-256}"
# The active Wayland session owns exactly the manager, shared D-Bus daemon,
# private portal service, graphical client, and its shell child. Keep the
# override for constrained regression fixtures, but budget the production
# process topology instead of the older pre-portal four-process shape.
max_uid_processes="${ARCHPHENE_MAX_ACTIVE_UID_PROCESSES:-5}"
max_frame_p95_ms="${ARCHPHENE_MAX_ACTIVE_FRAME_P95_MS:-250}"

cleanup() {
  archphene_adb_run shell am force-stop "$launcher_package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

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

archphene_open_manager_section Terminal "active-performance-terminal-$safe_serial"
archphene_wait_ui 'text="Start shell"' "active-performance-start-$safe_serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui \
  '(?:archphene:~|sh-[0-9.]+)\$' \
  "active-performance-prompt-$safe_serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell dumpsys gfxinfo "$manager" reset >/dev/null
archphene_adb_run exec-out screencap >"$output_dir/terminal-before.raw"
reset_metrics
archphene_adb_run shell input keyevent KEYCODE_A >/dev/null
sleep 1
terminal_metrics="$(snapshot_metrics)"
archphene_adb_run exec-out screencap >"$output_dir/terminal-after.raw"
archphene_adb_run exec-out screencap -p >"$output_dir/terminal.png"
terminal_resources="$(resource_snapshot)"
assert_resource_bounds "$terminal_resources"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$output_dir/terminal-before.raw" "$output_dir/terminal-after.raw" \
  --minimum-changed-ratio 0.00001 \
  --minimum-difference 0.05 \
  --top-percent 0 \
  --bottom-percent 65 >/dev/null
assert_at_least "$terminal_metrics" terminalCalls 1
assert_at_least "$terminal_metrics" terminalDirectIn 1
assert_at_least "$terminal_metrics" terminalDirectOut 1
assert_at_least "$terminal_metrics" terminalKotlinCopy 1
assert_at_least "$terminal_metrics" terminalLatencySamples 1
assert_at_most "$terminal_metrics" compositorCalls 0
assert_at_most "$terminal_metrics" compositorKotlinCopy 0
assert_at_most "$terminal_metrics" jniArrayCopy 0
assert_at_most "$terminal_metrics" terminalCalls "$max_terminal_calls"
assert_at_most "$terminal_metrics" terminalDirectIn "$max_direct_bytes"
assert_at_most "$terminal_metrics" terminalDirectOut "$max_direct_bytes"
assert_at_most "$terminal_metrics" terminalKotlinCopy "$max_kotlin_copy_bytes"
assert_at_most "$terminal_metrics" terminalLatencyMaxMs "$max_latency_ms"
assert_art_bound "$terminal_metrics" artBytesAllocated "$max_art_bytes"
assert_art_bound "$terminal_metrics" artObjectsAllocated "$max_art_objects"
assert_art_bound "$terminal_metrics" artGcCount 0
assert_art_bound "$terminal_metrics" artBlockingGcCount 0
assert_no_fatal_logs "active Terminal performance run"
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Stop shell"' "active-performance-stop-$safe_serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' "active-performance-stopped-$safe_serial" 20

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$launcher_activity" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected session=' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  'Presented Linux frame session=.*attachmentFrame=1' 30 \
  'ArchpheneLauncherSession:V AndroidRuntime:E *:S' >/dev/null
archphene_adb_run shell dumpsys gfxinfo "$manager" reset >/dev/null
archphene_adb_run exec-out screencap >"$output_dir/foot-before.raw"
reset_metrics
archphene_adb_run shell input keyevent KEYCODE_A >/dev/null
sleep 1
foot_metrics="$(snapshot_metrics)"
archphene_adb_run exec-out screencap >"$output_dir/foot-after.raw"
archphene_adb_run exec-out screencap -p >"$output_dir/foot.png"
foot_resources="$(resource_snapshot)"
assert_resource_bounds "$foot_resources"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$output_dir/foot-before.raw" "$output_dir/foot-after.raw" \
  --minimum-changed-ratio 0.00001 \
  --minimum-difference 0.05 \
  --top-percent 0 \
  --bottom-percent 65 >/dev/null
assert_at_least "$foot_metrics" compositorDispatchCalls 1
assert_at_least "$foot_metrics" compositorInputCalls 1
assert_at_least "$foot_metrics" compositorDirectIn 24
assert_at_least "$foot_metrics" compositorKotlinCopy 24
assert_at_least "$foot_metrics" launcherLatencySamples 1
assert_at_most "$foot_metrics" terminalCalls 0
assert_at_most "$foot_metrics" terminalKotlinCopy 0
assert_at_most "$foot_metrics" jniArrayCopy 0
assert_at_most "$foot_metrics" compositorCalls "$max_compositor_calls"
assert_at_most "$foot_metrics" compositorDirectIn "$max_direct_bytes"
assert_at_most "$foot_metrics" compositorDirectOut "$max_direct_bytes"
assert_at_most "$foot_metrics" compositorKotlinCopy "$max_kotlin_copy_bytes"
assert_at_most "$foot_metrics" launcherLatencyMaxMs "$max_latency_ms"
assert_art_bound "$foot_metrics" artBytesAllocated "$max_art_bytes"
assert_art_bound "$foot_metrics" artObjectsAllocated "$max_art_objects"
assert_art_bound "$foot_metrics" artGcCount 0
assert_art_bound "$foot_metrics" artBlockingGcCount 0
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
assert_no_fatal_logs "active Foot performance run"

python3 - \
  "$output_dir/report.json" "$serial" \
  "$terminal_metrics" "$terminal_resources" \
  "$foot_metrics" "$foot_resources" <<'PY'
import json
import sys

def fields(payload):
    result = {}
    for field in payload.split():
        key, value = field.split("=", 1)
        result[key] = int(value)
    return result

report = {
    "serial": sys.argv[2],
    "terminal": {
        "metrics": fields(sys.argv[3]),
        "resources": fields(sys.argv[4]),
    },
    "foot": {
        "metrics": fields(sys.argv[5]),
        "resources": fields(sys.argv[6]),
    },
}
with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(report, output, indent=2, sort_keys=True)
    output.write("\n")
PY

trap - EXIT
cleanup
archphene_note "Active Terminal and Foot performance gate passed on $serial"
archphene_note "  Terminal: $terminal_metrics"
archphene_note "  Foot: $foot_metrics"
archphene_note "  Report: $output_dir/report.json"
archphene_note "  Full-device screenshots: $output_dir/{terminal,foot}.png"
