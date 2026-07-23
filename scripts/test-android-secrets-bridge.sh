#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

abi=x86_64
serial=emulator-5554
timeout=30
apk=
while (($#)); do
  case "$1" in
    --android-abi) abi="${2:?}"; shift 2 ;;
    --serial) serial="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --apk) apk="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_validate_choice "$abi" ABI x86_64 arm64-v8a
archphene_test_init "$serial"

apk="${apk:-$ARCHPHENE_ROOT/prototypes/secrets-capability-probe/out-$abi/archphene-secrets-probe.apk}"
archphene_require_file "$apk"
package=org.archphene.secretsprobe
activity="$package/org.archphene.bridge.SecretsProbeActivity"
secret=archphene-secret-value-284917
updated_secret=archphene-updated-value-592641

start_probe() {
  local deadline socket
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    socket="$(archphene_adb_run shell run-as "$package" \
      cat files/secrets-broker-name 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$socket" ]]; then
      printf '%s' "$socket"
      return 0
    fi
    sleep 0.2
  done
  archphene_adb_run logcat -d -v brief -s ArchpheneCapabilities:I AndroidRuntime:E '*:S' >&2
  archphene_die 'secrets broker did not start'
}

native_path() {
  local native_dir subdirectory
  native_dir="$(archphene_adb_run shell dumpsys package "$package" \
    | sed -n 's/.*legacyNativeLibraryDir=\([^[:space:]]*\).*/\1/p' \
    | head -n1 | tr -d '\r')"
  [[ -n "$native_dir" ]] || archphene_die 'secrets probe native library directory is unavailable'
  if [[ "$abi" == arm64-v8a ]]; then subdirectory=arm64; else subdirectory=x86_64; fi
  printf '%s/%s/libarchphene_secrets_probe.so' "$native_dir" "$subdirectory"
}

invoke_probe() {
  local allow_failure="$1" socket="$2"
  shift 2
  local output status native
  native="$(native_path)"
  set +e
  output="$(archphene_adb_run shell run-as "$package" "$native" \
    --socket "@$socket" "$@" 2>&1)"
  status=$?
  set -e
  if [[ "$allow_failure" == false && $status -ne 0 ]]; then
    archphene_die "secrets capability request failed: $output"
  fi
  printf '%s' "$output"
}

read_private_file() {
  archphene_adb_run shell run-as "$package" cat "$1" | tr -d '\r'
}

assert_ciphertext_omits() {
  local record="$1" plaintext="$2" output status
  set +e
  output="$(archphene_adb_run shell run-as "$package" grep -a -F \
    "$plaintext" "$record" 2>&1)"
  status=$?
  set -e
  [[ $status -eq 1 ]] \
    || archphene_die "encrypted record plaintext scan failed for '$plaintext': $output"
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run logcat -c
socket="$(start_probe)"
attributes="'{\"application\":\"archphene-probe\",\"scope\":\"test\"}'"

stored="$(invoke_probe false "$socket" store-secret files/secret-input \
  probe-login Probe-login "$attributes")"
[[ "$stored" == OK ]] || archphene_die "secret store response is invalid: $stored"

mapfile -t records < <(archphene_adb_run shell run-as "$package" \
  ls files/secret-store | tr -d '\r')
[[ ${#records[@]} -eq 1 && "${records[0]}" =~ ^[0-9a-f]{64}\.secret$ ]] \
  || archphene_die "secret store did not create one hashed record: ${records[*]}"
record="files/secret-store/${records[0]}"
for plaintext in "$secret" probe-login Probe-login archphene-probe; do
  assert_ciphertext_omits "$record" "$plaintext"
done

stale="files/secret-store/$(printf '0%.0s' {1..64}).secret.tmp-deadbeef"
archphene_adb_run shell run-as "$package" touch "$stale"
listed="$(invoke_probe false "$socket" list-secrets files/secret-index.json)"
[[ "$listed" == $'OK\t1' ]] || archphene_die "secret list response is invalid: $listed"
if archphene_adb_run shell run-as "$package" test -e "$stale" 2>/dev/null; then
  archphene_die 'stale encrypted-record fixture was not reclaimed'
fi
index="$(read_private_file files/secret-index.json)"
python3 -c '
import json, sys
value = json.loads(sys.stdin.read())
valid = (
    isinstance(value, list) and len(value) == 1
    and value[0].get("id") == "probe-login"
    and value[0].get("label") == "Probe-login"
    and value[0].get("attributes") == {
        "application": "archphene-probe", "scope": "test"
    }
)
raise SystemExit(0 if valid else 1)
' <<<"$index" || archphene_die "secret index metadata is invalid: $index"

read_result="$(invoke_probe false "$socket" read-secret files/secret-output probe-login)"
IFS=$'\t' read -r read_status read_id read_label read_size read_extra <<<"$read_result"
[[ "$read_status" == OK && -n "$read_label" && "$read_size" =~ ^[0-9]+$ \
    && -z "${read_extra:-}" ]] \
  || archphene_die "secret read metadata is invalid: $read_result"
[[ "$(read_private_file files/secret-output)" == "$secret" ]] \
  || archphene_die 'secret read did not reproduce the exact payload'

updated="$(invoke_probe false "$socket" store-secret files/secret-updated \
  probe-login Updated-login "$attributes")"
[[ "$updated" == OK ]] || archphene_die "secret overwrite failed: $updated"
invoke_probe false "$socket" read-secret files/secret-output probe-login >/dev/null
[[ "$(read_private_file files/secret-output)" == "$updated_secret" ]] \
  || archphene_die 'secret overwrite did not replace the payload'

old_socket="$socket"
archphene_adb_run shell am force-stop "$package" >/dev/null
stale_response="$(invoke_probe true "$old_socket" list-secrets files/stale-index.json)"
[[ "$stale_response" != $'OK\t1' ]] \
  || archphene_die 'stopped secrets broker accepted a stale socket request'
socket="$(start_probe)"
invoke_probe false "$socket" read-secret files/persisted-output probe-login >/dev/null
[[ "$(read_private_file files/persisted-output)" == "$updated_secret" ]] \
  || archphene_die 'encrypted secret did not persist across process death'

malformed="$(invoke_probe true "$socket" store-secret files/secret-input \
  bad-attrs Bad-attributes "'[]'")"
[[ "$malformed" == $'ERROR\tINVALID_REQUEST' ]] \
  || archphene_die "malformed secret attributes were not rejected: $malformed"
oversized="$(invoke_probe true "$socket" store-secret files/secret-oversized \
  too-large Too-large "'{}'")"
[[ "$oversized" == $'ERROR\tINVALID_REQUEST' ]] \
  || archphene_die "oversized secret payload was not rejected: $oversized"

deleted="$(invoke_probe false "$socket" delete-secret probe-login)"
[[ "$deleted" == OK ]] || archphene_die "secret delete failed: $deleted"
missing="$(invoke_probe true "$socket" read-secret files/missing-output probe-login)"
[[ "$missing" == $'ERROR\tNOT_FOUND' ]] \
  || archphene_die "deleted secret remained readable: $missing"
empty="$(invoke_probe false "$socket" list-secrets files/empty-index.json)"
[[ "$empty" == $'OK\t0' && "$(read_private_file files/empty-index.json)" == '[]' ]] \
  || archphene_die 'deleted secret remained in the metadata index'

bus_address="$(read_private_file files/secrets-bus-address)"
service_probe="$(dirname "$(native_path)")/libarchphene_secret_service_probe.so"
service_command="export DBUS_SESSION_BUS_ADDRESS='$bus_address'; exec '$service_probe'"
set +e
service_output="$(archphene_adb_run shell run-as "$package" sh -c \
  "'$service_command'" 2>&1)"
service_status=$?
set -e
[[ $service_status -eq 0 ]] \
  || archphene_die "Secret Service D-Bus adapter failed: $service_output"
archphene_regex_contains "$service_output" '^PASS Secret Service:' \
  || archphene_die "Secret Service D-Bus adapter returned an invalid result: $service_output"

logs="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneCapabilities:I AndroidRuntime:E '*:S')"
[[ "$logs" != *'FATAL EXCEPTION'* ]] || archphene_die "secrets probe crashed: $logs"
[[ "$logs" != *"$secret"* && "$logs" != *"$updated_secret"* ]] \
  || archphene_die 'secret plaintext was written to Android logs'
page="$(archphene_adb_run shell getconf PAGESIZE | tr -d '\r')"
archphene_note "Android secrets bridge passed on $serial ($abi, $page-byte pages): encrypted storage, metadata, overwrite, restart persistence, bounds, deletion, lifecycle, log redaction, and the Secret Service D-Bus wire contract validated."
