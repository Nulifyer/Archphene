#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
timeout=30
skip_probe_build=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --skip-probe-build) skip_probe_build=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--package PACKAGE] [--timeout-seconds N] [--skip-probe-build]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
((timeout >= 10 && timeout <= 120)) \
  || archphene_die "timeout must be from 10 to 120 seconds"

archphene_test_init "$serial"
abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in
  x86_64) compiler=x86_64-linux-android35-clang ;;
  arm64-v8a) compiler=aarch64-linux-android35-clang ;;
  *) archphene_die "unsupported capability-probe ABI: $abi" ;;
esac
probe_dir="$ARCHPHENE_ROOT/tooling/build/capability-broker/$abi"
probe="$probe_dir/archphene-capability-probe"
remote_probe=/data/local/tmp/archphene-capability-probe
private_probe=code_cache/archphene-capability-probe
original_permission=false
notification_id="capability-test-$(date +%s%N)"
notification_body="Linux-notification-broker-$notification_id"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$remote_probe" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f "$private_probe" >/dev/null 2>&1 || true
  if [[ "$original_permission" == true ]]; then
    archphene_adb_run shell pm grant "$package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  else
    archphene_adb_run shell pm revoke "$package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$skip_probe_build" == false ]]; then
  mkdir -p "$probe_dir"
  image=localhost/archphene-android-native:ndk29-rust1.88
  archphene_podman_image_exists "$image" \
    || archphene_die "missing Android native build image: $image"
  podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$image" \
    "/opt/android-sdk-linux/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/$compiler" \
    -DARCHPHENE_CAPABILITY_PROBE_MAIN -fPIE -pie -O2 -Wall -Wextra -Werror \
    -o "tooling/build/capability-broker/$abi/archphene-capability-probe" \
    native/archphene-android-capability/archphene_android.c
fi
archphene_require_file "$probe"

component="$(archphene_launcher "$package")"
package_dump="$(archphene_adb_run shell dumpsys package "$package")"
[[ "$package_dump" == *android.permission.POST_NOTIFICATIONS* ]] \
  || archphene_die "generated wrapper does not declare notification permission"
if archphene_regex_contains "$package_dump" \
    'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  original_permission=true
fi
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell pm revoke "$package" \
  android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

archphene_adb_run push "$probe" "$remote_probe" >/dev/null
archphene_adb_run shell chmod 755 "$remote_probe"
archphene_adb_run shell run-as "$package" cp "$remote_probe" "$private_probe"
archphene_adb_run shell run-as "$package" chmod 700 "$private_probe"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$component" >/dev/null
broker_log="$(archphene_wait_log \
  'Capability broker ready abstract=archphene-[A-Za-z0-9_-]+' "$timeout" \
  'ArchpheneCapabilities:I AndroidRuntime:E *:S')"
socket="$(python3 -c '
import re, sys
matches = re.findall(r"Capability broker ready abstract=(archphene-[A-Za-z0-9_-]+)",
                     sys.stdin.read())
print(matches[-1] if matches else "")
' <<<"$broker_log")"
[[ -n "$socket" && "$broker_log" != *'FATAL EXCEPTION'* ]] \
  || archphene_die "capability broker did not start cleanly"

probe_request() {
  local output status
  set +e
  output="$(archphene_adb_run shell run-as "$package" "$private_probe" \
    --socket "@$socket" "$@" 2>&1)"
  status=$?
  set -e
  ARCHPHENE_PROBE_OUTPUT="$output"
  ARCHPHENE_PROBE_STATUS=$status
}

probe_request notify "$notification_id" Archphene "$notification_body"
if [[ "$ARCHPHENE_PROBE_OUTPUT" == *$'ERROR\tPERMISSION_REQUESTED'* ]]; then
  archphene_wait_ui \
    'resource-id="[^"]*:id/permission_allow_button"|text="Allow"' \
    capability-notification-permission 15
elif [[ "$ARCHPHENE_PROBE_OUTPUT" != *$'ERROR\tPERMISSION_DENIED'* ]]; then
  archphene_die "first notification did not expose a permission gate: $ARCHPHENE_PROBE_OUTPUT"
fi
archphene_adb_run shell pm grant "$package" \
  android.permission.POST_NOTIFICATIONS
sleep 1

probe_request notify "$notification_id" Archphene "$notification_body"
[[ "$ARCHPHENE_PROBE_OUTPUT" == OK && $ARCHPHENE_PROBE_STATUS -eq 0 ]] \
  || archphene_die "notification retry failed: $ARCHPHENE_PROBE_OUTPUT"
notifications="$(archphene_adb_run shell dumpsys notification --noredact)"
[[ "$notifications" == *"$notification_id"* && "$notifications" == *"$notification_body"* ]] \
  || archphene_die "Android did not publish the Linux notification"

probe_request open-uri file:///data/local/tmp/secret
[[ "$ARCHPHENE_PROBE_OUTPUT" == *$'ERROR\tINVALID_REQUEST'* ]] \
  || archphene_die "unsafe URI was not rejected: $ARCHPHENE_PROBE_OUTPUT"

set +e
unauthorized="$(archphene_adb_run shell "$remote_probe" \
  --socket "@$socket" withdraw "$notification_id" 2>&1)"
unauthorized_status=$?
set -e
[[ $unauthorized_status -ne 0
    && ( "$unauthorized" == *$'ERROR\tUNAUTHORIZED'*
      || "$unauthorized" == *'Permission denied'* ) ]] \
  || archphene_die "cross-UID broker request was not rejected: $unauthorized"

archphene_adb_run logcat -c
probe_request open-uri https://example.com/archphene-capability-test
[[ "$ARCHPHENE_PROBE_OUTPUT" == OK && $ARCHPHENE_PROBE_STATUS -eq 0 ]] \
  || archphene_die "HTTPS URI did not open: $ARCHPHENE_PROBE_OUTPUT"
uri_log="$(archphene_wait_log 'Opened Android URI scheme=https' 15 \
  'ArchpheneCapabilities:I AndroidRuntime:E *:S')"
[[ "$uri_log" == *'Opened Android URI scheme=https'* ]] \
  || archphene_die "Android URL bridge did not complete"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null

probe_request withdraw "$notification_id"
[[ "$ARCHPHENE_PROBE_OUTPUT" == OK && $ARCHPHENE_PROBE_STATUS -eq 0 ]] \
  || archphene_die "notification withdrawal failed: $ARCHPHENE_PROBE_OUTPUT"
sleep .5
notifications="$(archphene_adb_run shell dumpsys notification --noredact)"
active_notifications="$(python3 -c '
import sys
text = sys.stdin.read()
print(text.split("mArchive=", 1)[0])
' <<<"$notifications")"
[[ "$active_notifications" != *"$notification_id"* ]] \
  || archphene_die "withdrawn Linux notification remains published"

archphene_note "Android capability broker passed on $serial ($abi): permission gate/retry, notification post/withdraw, HTTPS dispatch, unsafe-URI rejection, and cross-UID denial; original permission restored."
