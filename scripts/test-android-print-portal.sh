#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p2bb8d769a2318af9bf9b60a9f8b7ec5f
probe=
runtime_pack_id=
timeout=60
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --probe-path) probe="${2:?}"; shift 2 ;;
    --runtime-pack-id) runtime_pack_id="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] \
  || archphene_die '--package must be a generated Linux wrapper ID'
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 20 && timeout <= 180)) \
  || archphene_die '--timeout-seconds must be 20..180'
[[ -z "$runtime_pack_id" || "$runtime_pack_id" =~ ^[0-9a-f]{64}$ ]] \
  || archphene_die '--runtime-pack-id must be 64 lowercase hex characters'
archphene_test_init "$serial"

suffix="$(date +%s)-$RANDOM"
build_dir="$ARCHPHENE_ROOT/tooling/build/print-test"
pdf="$build_dir/minimal-$suffix.pdf"
invalid="$build_dir/not-pdf-$suffix.txt"
remote_pdf="/data/local/tmp/archphene-print-$suffix.pdf"
remote_invalid="/data/local/tmp/archphene-print-invalid-$suffix.txt"
remote_probe="/data/local/tmp/archphene-print-probe-$suffix"
private_pdf="cache/print-test-$suffix.pdf"
private_invalid="cache/not-pdf-$suffix.txt"
private_probe="cache/print-portal-probe-$suffix"
bus_path="/data/user/0/$package/cache/desktop-integration/bus"

cleanup() {
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$remote_pdf" "$remote_invalid" "$remote_probe" \
    /sdcard/archphene-print-preview.xml \
    /sdcard/archphene-print-destinations.xml \
    /sdcard/archphene-print-pack-binding.xml >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f \
    "$private_pdf" "$private_invalid" "$private_probe" >/dev/null 2>&1 || true
  rm -f "$pdf" "$invalid"
}
trap cleanup EXIT

wait_top_activity() {
  local should_exist="$1" deadline=$((SECONDS + timeout)) activities present
  while ((SECONDS < deadline)); do
    activities="$(archphene_adb_run shell dumpsys activity activities)"
    present=false
    archphene_regex_contains "$activities" \
      'topResumedActivity=.*[A-Za-z0-9_.]*printspooler' && present=true
    if [[ "$present" == "$should_exist" ]]; then
      PRINT_ACTIVITIES="$activities"
      return 0
    fi
    sleep 0.3
  done
  archphene_die "Android print UI state did not become $should_exist"
}

invoke_portal() {
  local allow_failure="$1"
  shift
  local output status
  set +e
  output="$(archphene_adb_run shell run-as "$package" env \
    "DBUS_SESSION_BUS_ADDRESS=unix:path=$bus_path" "$private_probe" "$@" \
    2>&1 | tr -d '\r')"
  status=$?
  set -e
  PORTAL_OUTPUT="$output"
  PORTAL_STATUS=$status
  if [[ "$allow_failure" == false && $status -ne 0 ]]; then
    archphene_die "XDG print portal probe failed: $output"
  fi
}

mkdir -p "$build_dir"
python3 - "$pdf" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
newline = "\n"
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

activity="$(archphene_launcher "$package")"
archphene_adb_run shell pm path "$package" >/dev/null
archphene_adb_run push "$pdf" "$remote_pdf" >/dev/null
archphene_adb_run push "$invalid" "$remote_invalid" >/dev/null
archphene_adb_run shell chmod 644 "$remote_pdf" "$remote_invalid"
archphene_adb_run shell run-as "$package" cp "$remote_pdf" "$private_pdf"
archphene_adb_run shell run-as "$package" cp "$remote_invalid" "$private_invalid"

if [[ -n "$probe" ]]; then
  archphene_require_file "$probe"
  archphene_adb_run push "$probe" "$remote_probe" >/dev/null
  archphene_adb_run shell chmod 755 "$remote_probe"
  archphene_adb_run shell run-as "$package" cp "$remote_probe" "$private_probe"
else
  package_dump="$(archphene_adb_run shell dumpsys package "$package")"
  native_dir="$(sed -n 's/.*legacyNativeLibraryDir=\([^[:space:]]*\).*/\1/p' \
    <<<"$package_dump" | head -n1 | tr -d '\r')"
  abi="$(sed -n 's/.*primaryCpuAbi=\([^[:space:]]*\).*/\1/p' \
    <<<"$package_dump" | head -n1 | tr -d '\r')"
  [[ -n "$native_dir" && -n "$abi" ]] \
    || archphene_die 'printing wrapper native path or ABI is unavailable'
  if [[ "$abi" == arm64-v8a ]]; then abi_directory=arm64; else abi_directory="$abi"; fi
  archphene_adb_run shell run-as "$package" cp \
    "$native_dir/$abi_directory/libarchphene_portal_probe.so" "$private_probe"
fi
archphene_adb_run shell run-as "$package" chmod 700 "$private_probe"

if [[ -n "$runtime_pack_id" ]]; then
  manager=org.archpheneos.manager
  manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
  archphene_regex_contains "$manager_dump" '(?m)^\s*flags=\[[^]]*DEBUGGABLE' \
    || archphene_die 'runtime-pack preparation requires a debuggable manager'
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" \
    --ez archphene_test_package_runtime true \
    --es archphene_test_bind_pack "$runtime_pack_id" \
    --es archphene_test_bind_package "$package" >/dev/null
  archphene_wait_ui_text "Bound runtime pack to $package" \
    archphene-print-pack-binding "$timeout"
fi

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
ready_log="$(archphene_wait_log \
  'Private session bus and desktop adapters ready' "$timeout" \
  'ArchpheneDesktop:I AndroidRuntime:E *:S')"
[[ "$ready_log" != *'FATAL EXCEPTION'* ]] \
  || archphene_die "printing wrapper crashed during startup: $ready_log"

invoke_portal false contract
contract="$PORTAL_OUTPUT"
archphene_regex_contains "$contract" 'PASS portal PreparePrint accepted' \
  || archphene_die "XDG PreparePrint contract failed: $contract"
invoke_portal false print "$private_pdf"
printed="$PORTAL_OUTPUT"
archphene_regex_contains "$printed" 'PASS portal Print accepted' \
  || archphene_die "XDG Print did not accept the valid PDF: $printed"
wait_top_activity true

archphene_adb_run shell rm -f /sdcard/archphene-print-preview.xml \
  >/dev/null 2>&1 || true
ui="$(archphene_capture_ui archphene-print-preview)"
archphene_regex_contains "$ui" 'content-desc="(?:Page )?1 of 1"' \
  || archphene_die 'Android print preview did not render one PDF page'
destination_pattern='resource-id="[^"]*:id/destination_spinner"'
archphene_regex_contains "$ui" "$destination_pattern" \
  || archphene_die 'Android print destination selector is unavailable'
archphene_tap_ui_pattern "$ui" "$destination_pattern" 'print destination selector'
archphene_wait_ui 'text="Save as PDF"' archphene-print-destinations "$timeout"
archphene_adb_run shell input keyevent KEYCODE_BACK
sleep 0.5
archphene_adb_run shell input keyevent KEYCODE_BACK
wait_top_activity false

deadline=$((SECONDS + timeout))
pending=
while ((SECONDS < deadline)); do
  pending="$(archphene_adb_run shell run-as "$package" \
    find cache/print -type f -print 2>/dev/null | tr -d '\r' || true)"
  [[ -z "$pending" ]] && break
  sleep 0.3
done
[[ -z "$pending" ]] \
  || archphene_die "cancelled print left private staged documents: $pending"

invoke_portal true print "$private_invalid"
invalid_output="$PORTAL_OUTPUT"
((PORTAL_STATUS != 0)) \
  && archphene_regex_contains "$invalid_output" 'response=2' \
  || archphene_die "invalid PDF did not return an XDG failure: $invalid_output"
activities="$(archphene_adb_run shell dumpsys activity activities)"
! archphene_regex_contains "$activities" \
  'topResumedActivity=.*[A-Za-z0-9_.]*printspooler' \
  || archphene_die 'invalid PDF opened Android print UI'

invoke_portal false print-pipe
pipe_output="$PORTAL_OUTPUT"
archphene_regex_contains "$pipe_output" \
    'PASS portal Print rejected non-regular descriptor' \
  || archphene_die "non-regular print descriptor was not rejected: $pipe_output"
activities="$(archphene_adb_run shell dumpsys activity activities)"
! archphene_regex_contains "$activities" \
  'topResumedActivity=.*[A-Za-z0-9_.]*printspooler' \
  || archphene_die 'non-regular print descriptor opened Android print UI'
logs="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneDesktop:I AndroidRuntime:E '*:S')"
[[ "$logs" != *'FATAL EXCEPTION'* ]] \
  || archphene_die "printing regression crashed: $logs"

cleanup
trap - EXIT
archphene_note "Android XDG printing passed on $serial: PreparePrint, regular PDF transfer, rendered preview, Save as PDF discovery, cancellation cleanup, invalid-PDF rejection, and non-regular-FD rejection validated."
