#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
launcher=
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --launcher) launcher="${2:?missing value for --launcher}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk MANAGER_APK --launcher CURRENT_LAUNCHER [--artifact-dir PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$launcher" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--launcher must be a current generated Archphene launcher"

archphene_test_init "$serial"
archphene_require_file "$apk"
manager=org.archphene.app.debug
manager_activity="$manager/org.archphene.app.MainActivity"
launcher_activity="$(archphene_launcher "$launcher")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/launcher-document-save/$serial_slug}"
mkdir -p "$artifact_dir"

token="$(printf '%08x%08x' "$((RANDOM * 65536 + RANDOM))" "$((RANDOM * 65536 + RANDOM))")"
document_name="archphene-launcher-save-$token.txt"
target="/sdcard/Download/$document_name"
payload="Archphene authenticated launcher document proof $token"
payload_base64="$(printf %s "$payload" | base64 -w0)"
expected_sha256="$(printf %s "$payload" | sha256sum | awk '{print $1}')"

cleanup() {
  archphene_adb_run shell rm -f "$target" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$launcher" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

request_save() {
  archphene_adb_run shell am broadcast --user 0 \
    -n "$manager/org.archphene.app.LauncherSessionTestReceiver" \
    -a org.archphene.app.debug.action.REQUEST_LAUNCHER_DOCUMENT_SAVE \
    --es token launcher-session-gate \
    --es package "$launcher" \
    --es document_title Save_Linux_document \
    --es document_name "$document_name" \
    --es document_mime text/plain \
    --es document_payload_base64 "$payload_base64" >/dev/null
  archphene_wait_log \
    "Manager document request package=$launcher session=[1-9][0-9]* name=$document_name bytes=${#payload} result=accepted" \
    20 'ArchpheneLauncherSessionProbe:I AndroidRuntime:E *:S' >/dev/null
}

archphene_adb_run shell rm -f "$target" >/dev/null
archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run shell am force-stop "$launcher" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 30 >/dev/null

archphene_adb_run shell am broadcast --user 0 \
  -n "$manager/org.archphene.app.LauncherSessionTestReceiver" \
  -a org.archphene.app.debug.action.PROBE_LAUNCHER_SESSION \
  --es token launcher-session-gate >/dev/null
archphene_wait_log \
  'Untrusted Binder caller and malformed version rejected' 20 \
  'ArchpheneLauncherSessionProbe:I AndroidRuntime:E *:S' >/dev/null

archphene_adb_run shell am start -W -n "$launcher_activity" >/dev/null
archphene_wait_log \
  'Attached launcher Surface session=' 30 \
  'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null

archphene_adb_run logcat -c
request_save
archphene_wait_ui \
  'text="(?:SAVE|Save)"[^>]*enabled="true"' \
  "launcher-document-cancel-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/cancel-ready.png"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_log \
  'Android document request ended session=[1-9][0-9]* request=[1-9][0-9]* result=2' \
  20 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null
if archphene_adb_run shell test -e "$target"; then
  archphene_die "cancelled launcher Save As created an Android file"
fi

archphene_adb_run logcat -c
request_save
archphene_wait_ui \
  'text="(?:SAVE|Save)"[^>]*enabled="true"' \
  "launcher-document-save-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/save-ready.png"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:SAVE|Save)"[^>]*enabled="true"' Save
archphene_wait_log \
  "Android document save completed session=[1-9][0-9]* request=[1-9][0-9]* bytes=${#payload}" \
  30 'ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null

actual_sha256="$(
  archphene_adb_run shell sha256sum "$target" |
    awk '{print $1}' |
    tr -d '\r'
)"
[[ "$actual_sha256" == "$expected_sha256" ]] ||
  archphene_die "launcher Save As destination did not receive the exact payload"
archphene_adb_run exec-out screencap -p >"$artifact_dir/complete.png"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneLauncher:E ArchpheneLauncherSession:E AndroidRuntime:E libc:F '*:S' \
    2>/dev/null || true
)"
[[ "$fatal_log" != *'FATAL EXCEPTION'* && "$fatal_log" != *'Fatal signal'* ]] ||
  archphene_die "launcher Save As emitted a fatal event: $fatal_log"

trap - EXIT
cleanup
archphene_note "Authenticated launcher Save As passed on $serial"
archphene_note "  Untrusted Binder rejection, cancellation, and exact descriptor write passed"
archphene_note "  Full-device screenshots: $artifact_dir/{cancel-ready,save-ready,complete}.png"
