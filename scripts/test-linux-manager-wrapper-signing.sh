#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

serial=emulator-5554
skip_build=false
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; skip_build=true; shift 2 ;;
    --skip-build) skip_build=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--apk PATH | --skip-build]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_init_adb "$serial"
package=org.archpheneos.manager
apk="${apk:-$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/out-linux/archphene.apk}"
remote=/data/local/tmp/archphene-signing-input.apk
private_relative=files/package-runtime/signing-input.apk
private="/data/user/0/$package/$private_relative"
output_relative=files/package-runtime/generated-wrapper-test.apk

was_running=false
[[ -n "$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')" ]] \
  && was_running=true
cleanup() {
  archphene_adb_run shell run-as "$package" rm -f \
    "$private_relative" "$output_relative" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$remote" >/dev/null 2>&1 || true
  if [[ "$was_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

installed_apk_path() {
  archphene_adb_run shell pm path "$package" \
    | sed -n '1{s/^package://;s/\r$//;p;}'
}

package_identity() {
  local dump
  dump="$(archphene_adb_run shell dumpsys package "$package")"
  python3 -c '
import re, sys
text = sys.stdin.read()
fields = []
for pattern in (
    r"versionCode=(\d+)",
    r"versionName=([^\s]+)",
    r"(?:userId|appId)=(\d+)",
    r"firstInstallTime=([^\n\r]+)",
):
    match = re.search(pattern, text)
    if match is None:
        raise SystemExit(f"missing package identity field: {pattern}")
    fields.append(match.group(1).strip())
print("|".join(fields))
' <<<"$dump"
}

wait_signing_ui() {
  local ui
  for ((i = 0; i < 20; i++)); do
    sleep 0.5
    ui="$(archphene_capture_ui archphene-wrapper-signing 2>/dev/null || true)"
    if [[ "$ui" == *'Signed generated APK'* || "$ui" == *'APK signing failed'* ]]; then
      printf '%s' "$ui"
      return
    fi
  done
  archphene_die "timed out waiting for wrapper-signing result"
}

invoke_signing() {
  local ui signer
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run shell am start -W -n "$package/.MainActivity" \
    --es archphene_test_sign_apk_file "$private" >/dev/null
  ui="$(wait_signing_ui)"
  [[ "$ui" == *'v2=true v3=true'* ]] \
    || archphene_die "manager did not verify v2/v3 APK signing: $ui"
  signer="$(sed -n 's/.*Signer \([0-9a-f]\{64\}\).*/\1/p' <<<"$ui")"
  [[ "$signer" =~ ^[0-9a-f]{64}$ ]] \
    || archphene_die "manager did not report a valid wrapper signer"
  archphene_adb_run shell run-as "$package" test -s "$output_relative" \
    || archphene_die "manager did not retain a nonempty signed wrapper"
  printf '%s\n' "$signer"
}

if [[ "$skip_build" == false ]]; then
  "$ARCHPHENE_SCRIPTS_DIR/build-install-linux-manager-stub.sh" --serial "$serial"
fi
archphene_require_file "$apk"

installed_path="$(installed_apk_path)"
[[ -n "$installed_path" ]] || archphene_die "manager is not installed"
local_sha="$(archphene_sha256_file "$apk")"
installed_sha="$(archphene_adb_run shell sha256sum "$installed_path" | awk '{print $1}')"
[[ "$installed_sha" == "$local_sha" ]] \
  || archphene_die \
    "installed manager does not match the candidate: $installed_sha != $local_sha"
before="$(package_identity)"

archphene_adb_run push "$apk" "$remote" >/dev/null
archphene_adb_run shell run-as "$package" mkdir -p files/package-runtime
archphene_adb_run shell run-as "$package" cp "$remote" "$private_relative"
archphene_adb_run shell run-as "$package" chmod 600 "$private_relative"

first="$(invoke_signing)"
archphene_adb_run install -r "$apk" >/dev/null
after="$(package_identity)"
[[ "$after" == "$before" ]] \
  || archphene_die "same-build manager reinstall changed install identity: $before -> $after"
reinstalled_path="$(installed_apk_path)"
reinstalled_sha="$(archphene_adb_run shell sha256sum "$reinstalled_path" | awk '{print $1}')"
[[ "$reinstalled_sha" == "$local_sha" ]] \
  || archphene_die "reinstalled manager APK does not match the tested candidate"
second="$(invoke_signing)"
[[ "$first" == "$second" ]] \
  || archphene_die "wrapper signer changed across manager reinstall: $first -> $second"

archphene_note \
  "Persistent Android Keystore APK signer passed on $serial: $first; manager identity $before retained."
