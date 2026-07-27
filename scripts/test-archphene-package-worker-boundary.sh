#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.PackagePhaseTestReceiver"
action=org.archphene.app.debug.action.START_PACKAGE_PHASES
output_dir="$ARCHPHENE_ROOT/tooling/build/package-worker-boundary"
mkdir -p "$output_dir"
raw_log="$output_dir/$serial.log"
screenshot="$output_dir/$serial.png"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"

initially_running=false
if [[ -n "$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')" ]]; then
  initially_running=true
fi

cleanup() {
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Package runtime ready:.*Pacman v[0-9]' 30 \
  'ArchpheneRuntime:V AndroidRuntime:E *:S' >/dev/null
archphene_open_manager_section Packages "package-worker-packages-$serial"
package_ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$package_ui" \
  'class="android.widget.EditText"[^>]*(?:text|hint)="Package name"|text="Package name"[^>]*class="android.widget.EditText"' \
  "Package name"
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text worker-boundary-fixture >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_adb_run logcat -c

archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "worker-$serial_slug" \
  --es package worker-boundary-fixture \
  --ei hold-ms 10000 >/dev/null
archphene_wait_log \
  "Started package phases=true token=worker-$serial_slug" 15 \
  'ArchphenePackagePhaseProbe:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  'Durable package job queued on ArchphenePackagePhases' 15 \
  'ArchpheneRuntime:V AndroidRuntime:E *:S' >/dev/null

archphene_wait_ui_exact_text \
  'Install · Queued · 0%' "package-worker-queued-$serial" 15
[[ "$ARCHPHENE_UI" == *'text="Queued"'* ]] ||
  archphene_die "durably queued package job did not expose immediate progress"
archphene_adb_run exec-out screencap -p >"$screenshot"

if archphene_regex_contains \
  "$ARCHPHENE_UI" \
  'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"[^>]*enabled="true"'; then
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" \
    'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"[^>]*enabled="true"' \
    "Cancel"
else
  archphene_die "queued package job did not expose cancellation"
fi
archphene_wait_ui \
  'Install · Cancelled · [0-9]+%' \
  "package-worker-cancelled-$serial" 15 >/dev/null

archphene_adb_run logcat -d -v threadtime \
  ArchpheneRuntime:V StrictMode:D AndroidRuntime:E libc:F '*:S' >"$raw_log"
python3 - "$raw_log" <<'PY'
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
marker = "StrictMode policy violation; "
owned = []
for index, line in enumerate(lines):
    if marker not in line:
        continue
    block = []
    for candidate in lines[index + 1 :]:
        if marker in candidate:
            break
        block.append(candidate)
    if any("\tat org.archphene." in candidate for candidate in block):
        owned.append("\n".join([line, *block]))
fatal = [
    line
    for line in lines
    if "FATAL EXCEPTION" in line or "Fatal signal" in line
]
if owned:
    print("\n\n".join(owned), file=sys.stderr)
    raise SystemExit(f"{len(owned)} Archphene StrictMode violation(s)")
if fatal:
    print("\n".join(fatal), file=sys.stderr)
    raise SystemExit(f"{len(fatal)} fatal runtime event(s)")
PY

trap - EXIT
cleanup
archphene_note "Archphene package worker boundary passed on $serial"
archphene_note "  Accepted UI state, off-main durable queue, progress, and cancellation passed"
archphene_note "  Existing app data and Linux package database were not cleared"
archphene_note "  Raw log: $raw_log"
archphene_note "  Full-device screenshot: $screenshot"
