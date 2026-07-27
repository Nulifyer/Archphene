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
      echo "usage: $0 --serial SERIAL [--apk PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
output_dir="$ARCHPHENE_ROOT/tooling/build/runtime-shutdown"
mkdir -p "$output_dir"
raw_log="$output_dir/$serial.log"

initially_running=false
if [[ -n "$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')" ]]; then
  initially_running=true
fi

cleanup() {
  if [[ "$initially_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ -n "$apk" ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Package runtime ready:.*Pacman v[0-9]' 30 \
  'ArchpheneRuntime:V *:S' >/dev/null

started_ms="$(date +%s%3N)"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
activity_deadline=$((SECONDS + 3))
while ((SECONDS < activity_deadline)); do
  resumed="$(
    archphene_adb_run shell dumpsys activity activities |
      sed -n 's/.*mResumedActivity: //p' |
      head -n1
  )"
  [[ "$resumed" != *"$package"* ]] && break
  sleep 0.1
done
[[ "$resumed" != *"$package"* ]] ||
  archphene_die "manager Activity remained resumed during runtime shutdown"
activity_elapsed_ms=$(($(date +%s%3N) - started_ms))

archphene_wait_log \
  'Shared Rust runtime stopped on ArchpheneShutdown' 20 \
  'ArchpheneRuntime:V *:S' >/dev/null
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
archphene_note "Archphene runtime shutdown gate passed on $serial"
archphene_note "  Activity left the foreground in ${activity_elapsed_ms} ms"
archphene_note "  Native cancellation, worker drain, and destruction ran on ArchpheneShutdown"
archphene_note "  Raw log: $raw_log"
