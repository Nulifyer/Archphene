#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
timeout=60
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?missing value for --timeout-seconds}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package PRINTING_LAUNCHER [--manager PACKAGE] [--artifact-dir PATH] [--timeout-seconds SECONDS]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package must be a generated Archphene launcher"
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 20 && timeout <= 180)) ||
  archphene_die "--timeout-seconds must be 20..180"
archphene_test_init "$serial"

serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/launcher-printing/$serial_slug}"
mkdir -p "$artifact_dir"
suffix="$(date +%s)-$RANDOM"
pdf="$artifact_dir/minimal-$suffix.pdf"
invalid="$artifact_dir/not-pdf-$suffix.txt"
private_pdf="cache/print-probe-$suffix.pdf"
private_invalid="cache/print-invalid-$suffix.txt"

cleanup() {
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -f \
    "$private_pdf" "$private_invalid" >/dev/null 2>&1 || true
  rm -f "$pdf" "$invalid"
}
trap cleanup EXIT

python3 - "$pdf" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
stream = "BT /F1 24 Tf 72 720 Td (Archphene print bridge) Tj ET\n"
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
    "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode('ascii'))} >>\nstream\n{stream}endstream",
]
content = "%PDF-1.4\n"
offsets = []
for index, value in enumerate(objects, 1):
    offsets.append(len(content.encode("ascii")))
    content += f"{index} 0 obj\n{value}\nendobj\n"
xref = len(content.encode("ascii"))
content += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n"
content += "".join(f"{offset:010d} 00000 n \n" for offset in offsets)
content += (
    f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
    f"startxref\n{xref}\n%%EOF\n"
)
path.write_bytes(content.encode("ascii"))
PY
printf 'not a PDF\n' >"$invalid"

manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
archphene_regex_contains "$manager_dump" '(?m)^\s*flags=\[[^]]*DEBUGGABLE' ||
  archphene_die "print regression requires a debuggable manager"
native_dir="$(
  sed -n 's/.*legacyNativeLibraryDir=//p' <<<"$manager_dump" |
    head -n 1 | tr -d '\r'
)"
abi="$(
  sed -n 's/.*primaryCpuAbi=//p' <<<"$manager_dump" |
    head -n 1 | tr -d '\r'
)"
case "$abi" in
  arm64-v8a) abi_directory=arm64 ;;
  x86_64) abi_directory=x86_64 ;;
  *) archphene_die "unsupported manager ABI: $abi" ;;
esac
[[ "$native_dir" =~ ^/data/app/[A-Za-z0-9_~+./=-]+/lib$ ]] ||
  archphene_die "manager native library directory is invalid"
probe="$native_dir/$abi_directory/libarchphene_portal_probe.so"

remote_pdf="/data/local/tmp/archphene-print-$suffix.pdf"
remote_invalid="/data/local/tmp/archphene-print-invalid-$suffix.txt"
archphene_adb_run push "$pdf" "$remote_pdf" >/dev/null
archphene_adb_run push "$invalid" "$remote_invalid" >/dev/null
archphene_adb_run shell run-as "$manager" cp "$remote_pdf" "$private_pdf"
archphene_adb_run shell run-as "$manager" cp "$remote_invalid" "$private_invalid"
archphene_adb_run shell rm -f "$remote_pdf" "$remote_invalid"

activity="$(archphene_launcher "$package")"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
ready_log="$(archphene_wait_log \
  'Private desktop portal ready session=' "$timeout" \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S')"
session_id="$(
  sed -n 's/.*Private desktop portal ready session=\([1-9][0-9]*\).*/\1/p' \
    <<<"$ready_log" | tail -n 1
)"
[[ "$session_id" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "could not identify the launcher portal session"

bus=
deadline=$((SECONDS + timeout))
while ((SECONDS < deadline)); do
  bus="$(
    archphene_adb_run shell run-as "$manager" find cache \
      -path "cache/p$session_id-*/bus" -type s -print 2>/dev/null | tr -d '\r'
  )"
  [[ "$bus" =~ ^cache/p$session_id-[0-9a-f]{16}/bus$ ]] && break
  sleep 0.2
done
[[ "$bus" =~ ^cache/p$session_id-[0-9a-f]{16}/bus$ ]] ||
  archphene_die "expected the private launcher bus for session $session_id, received: $bus"
address="unix:path=/data/user/0/$manager/$bus"

invoke_probe() {
  local arguments="$1"
  local command
  command="run-as $manager sh -c 'DBUS_SESSION_BUS_ADDRESS=$address \"$probe\" $arguments'"
  archphene_adb_run shell "$command" 2>&1 | tr -d '\r'
}

contract="$(invoke_probe contract)"
archphene_regex_contains "$contract" 'PASS portal PreparePrint accepted' ||
  archphene_die "XDG PreparePrint contract failed: $contract"
printed="$(invoke_probe "print \"$private_pdf\"")"
archphene_regex_contains "$printed" 'PASS portal Print accepted' ||
  archphene_die "XDG Print did not accept the valid PDF: $printed"

deadline=$((SECONDS + timeout))
activities=
while ((SECONDS < deadline)); do
  activities="$(archphene_adb_run shell dumpsys activity activities)"
  archphene_regex_contains "$activities" \
    'topResumedActivity=.*[A-Za-z0-9_.]*printspooler' && break
  sleep 0.2
done
archphene_regex_contains "$activities" \
  'topResumedActivity=.*[A-Za-z0-9_.]*printspooler' ||
  archphene_die "Android print UI did not open"
archphene_adb_run exec-out screencap -p >"$artifact_dir/preview.png"
ui="$(archphene_capture_ui launcher-print-preview)"
archphene_regex_contains "$ui" 'text="(?:1/1|1 of 1)"' ||
  archphene_die "Android print preview did not render one page"
archphene_tap_text "$ui" "Select a printer"
archphene_wait_ui 'text="Save as PDF"' launcher-print-destinations "$timeout"
archphene_adb_run exec-out screencap -p >"$artifact_dir/destinations.png"
archphene_adb_run shell input keyevent KEYCODE_BACK
archphene_adb_run shell input keyevent KEYCODE_BACK

deadline=$((SECONDS + timeout))
pending=
while ((SECONDS < deadline)); do
  pending="$(
    archphene_adb_run shell run-as "$package" \
      find cache/print -type f -print 2>/dev/null | tr -d '\r' || true
  )"
  [[ -z "$pending" ]] && break
  sleep 0.2
done
[[ -z "$pending" ]] ||
  archphene_die "cancelled print left private staged documents: $pending"

set +e
invalid_output="$(invoke_probe "print \"$private_invalid\"")"
invalid_status=$?
set -e
((invalid_status != 0)) &&
  archphene_regex_contains "$invalid_output" 'response=2' ||
  archphene_die "invalid PDF did not return an XDG failure: $invalid_output"

pipe_output="$(invoke_probe print-pipe)"
archphene_regex_contains "$pipe_output" \
  'PASS portal Print rejected non-regular descriptor' ||
  archphene_die "non-regular print descriptor was not rejected: $pipe_output"

fatal="$(
  archphene_adb_run logcat -d -v brief \
    'AndroidRuntime:E' 'libc:F' 'ArchphenePortal:E' 'ArchpheneLauncherSession:E' '*:S'
)"
[[ "$fatal" != *"FATAL EXCEPTION"* && "$fatal" != *"Fatal signal"* ]] ||
  archphene_die "fatal launcher printing log detected: $fatal"

cleanup
trap - EXIT
archphene_note \
  "Android XDG printing passed on $serial: capability contract, rendered full-device preview, Save as PDF, cancellation cleanup, malformed-PDF rejection, and non-regular-FD rejection validated."
