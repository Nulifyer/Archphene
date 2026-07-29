#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

abi=x86_64
serial=emulator-5554
timeout=30
apk=
clean_data=false
skip_install=true
while (($#)); do
  case "$1" in
    --android-abi) abi="${2:?}"; shift 2 ;;
    --serial) serial="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --apk) apk="${2:?}"; shift 2 ;;
    --clean-data) clean_data=true; shift ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 [--android-abi x86_64|arm64-v8a] [--serial SERIAL] [--timeout-seconds N] [--apk PATH --install-apk] --clean-data"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$clean_data" == true ]] ||
  archphene_die "--clean-data is required because this gate clears secrets-probe app data"
archphene_validate_choice "$abi" ABI x86_64 arm64-v8a
archphene_test_init "$serial"

apk="${apk:-$ARCHPHENE_ROOT/prototypes/secrets-capability-probe/out-$abi/archphene-secrets-probe.apk}"
package=org.archphene.secretsprobe
activity="$package/org.archphene.bridge.SecretsProbeActivity"
secret=archphene-secret-value-284917
updated_secret=archphene-updated-value-592641
libsecret_value=archphene-real-libsecret-52941
kwallet_direct_value=archphene-kwallet-direct-68413
kwallet_query_value=archphene-kwallet-query-39752

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}

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

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk"
trap cleanup EXIT
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

page="$(archphene_adb_run shell getconf PAGESIZE | tr -d '\r')"
libsecret_validated=false
kwallet_validated=false
fixture_status=0
archphene_adb_run shell run-as "$package" test -f files/libsecret-runtime-root \
  >/dev/null 2>&1 || fixture_status=$?
((fixture_status == 0 || fixture_status == 1)) \
  || archphene_die 'could not inspect the packaged libsecret fixture'
if ((fixture_status == 0)) \
    && [[ "$abi" == arm64-v8a || ("$abi" == x86_64 && "$page" == 4096) ]]; then
  native_dir="$(dirname "$(native_path)")"
  libsecret_root="$(read_private_file files/libsecret-runtime-root)"
  libsecret_loader="$native_dir/libarchphene_libsecret_loader.so"
  secret_tool="$libsecret_root/secret-tool"
  client_status=0
  client_output=

  invoke_client() {
    local allowed="$1" command="$2" remote
    remote="export DBUS_SESSION_BUS_ADDRESS=$bus_address; "
    remote+="export LD_LIBRARY_PATH=$libsecret_root/lib; $command"
    set +e
    client_output="$(archphene_adb_run shell run-as "$package" sh -c \
      "'$remote'" 2>&1 | tr -d '\r')"
    client_status=$?
    set -e
    [[ " $allowed " == *" $client_status "* ]] \
      || archphene_die "packaged Arch secrets client failed with status $client_status: $client_output"
  }

  invoke_client '0' "echo $libsecret_value | $libsecret_loader "\
"$secret_tool store --label=Archphene-libsecret-probe "\
"application archphene-libsecret scope encrypted-dh"
  invoke_client '0' "$libsecret_loader $secret_tool lookup "\
"application archphene-libsecret scope encrypted-dh"
  [[ "$client_output" == "$libsecret_value" ]] \
    || archphene_die "packaged Arch libsecret lookup mismatch: $client_output"
  invoke_client '0' "$libsecret_loader $secret_tool clear "\
"application archphene-libsecret scope encrypted-dh"
  invoke_client '1' "$libsecret_loader $secret_tool lookup "\
"application archphene-libsecret scope encrypted-dh"
  [[ -z "$client_output" ]] \
    || archphene_die 'packaged Arch libsecret clear left output behind'
  libsecret_validated=true

  kwallet_fixture_status=0
  archphene_adb_run shell run-as "$package" \
    test -x files/libsecret-runtime/kwalletd6 >/dev/null 2>&1 \
    || kwallet_fixture_status=$?
  ((kwallet_fixture_status == 0 || kwallet_fixture_status == 1)) \
    || archphene_die 'could not inspect the packaged KWallet fixture'
  if ((kwallet_fixture_status == 0)); then
    kwallet_home="$libsecret_root/kwallet-home"
    kwallet_runtime="$libsecret_root/kwallet-runtime"
    kwallet_config_base64="$(printf '[KSecretD]\nEnabled=false\n\n[Wallet]\nDefault Wallet=Login\n' \
      | base64 -w0)"
    invoke_client '0' "mkdir -p $kwallet_home/.config $kwallet_runtime; "\
"printf %s $kwallet_config_base64 | base64 -d > $kwallet_home/.config/kwalletrc; "\
"chmod 700 $libsecret_root/gdbus $libsecret_root/kwalletd6 $libsecret_root/kwallet-query"
    kwallet_environment="export HOME=$kwallet_home; "
    kwallet_environment+="export XDG_CONFIG_HOME=$kwallet_home/.config; "
    kwallet_environment+="export XDG_DATA_HOME=$kwallet_home/.local/share; "
    kwallet_environment+="export XDG_RUNTIME_DIR=$kwallet_runtime; "
    kwallet_environment+="export QT_QPA_PLATFORM=minimal; "
    kwallet_environment+="export QT_QPA_PLATFORM_PLUGIN_PATH=$libsecret_root/qt/plugins/platforms"
    invoke_kwallet() {
      local allowed="$1" command="$2"
      invoke_client "$allowed" "$kwallet_environment; $command"
    }

    kwallet_pid=
    invoke_kwallet '0' "nohup $libsecret_loader $libsecret_root/kwalletd6 "\
"</dev/null >$kwallet_home/kwalletd.log 2>&1 & echo \$!"
    kwallet_pid="$client_output"
    [[ "$kwallet_pid" =~ ^[0-9]+$ ]] \
      || archphene_die "KWallet daemon returned an invalid PID: $kwallet_pid"
    sleep .75
    gdbus="$libsecret_loader $libsecret_root/gdbus call --session "
    gdbus+="--dest org.kde.kwalletd6 --object-path /modules/kwalletd6 "
    gdbus+="--method org.kde.KWallet."
    invoke_kwallet '0' "${gdbus}wallets"
    [[ "$client_output" == *Login* ]] \
      || archphene_die "KWallet daemon did not expose Login: $client_output"
    invoke_kwallet '0' "${gdbus}open Login 0 archphene-probe"
    handle="$(sed -n 's/[^-0-9]*\(-\{0,1\}[0-9][0-9]*\).*/\1/p' \
      <<<"$client_output" | head -n1)"
    [[ "$handle" =~ ^[0-9]+$ ]] && ((handle > 0)) \
      || archphene_die "KWallet daemon returned an invalid handle: $client_output"
    invoke_kwallet '0' "${gdbus}createFolder $handle Archphene archphene-probe"
    [[ "$client_output" == '(true,)' ]] \
      || archphene_die "KWallet folder creation failed: $client_output"
    invoke_kwallet '0' "${gdbus}writePassword $handle Archphene bridge-entry "\
"$kwallet_direct_value archphene-probe"
    [[ "$client_output" == '(0,)' ]] \
      || archphene_die "KWallet direct password write failed: $client_output"
    invoke_kwallet '0' "${gdbus}readPassword $handle Archphene bridge-entry archphene-probe"
    [[ "$client_output" == *"$kwallet_direct_value"* ]] \
      || archphene_die "KWallet direct password read mismatch: $client_output"

    invoke_kwallet '0' "echo $kwallet_query_value > $kwallet_home/query-value"
    invoke_kwallet '0' "$libsecret_loader $libsecret_root/kwallet-query "\
"--write-password bridge-entry --folder Archphene Login < $kwallet_home/query-value"
    invoke_kwallet '0' "$libsecret_loader $libsecret_root/kwallet-query "\
"--read-password bridge-entry --folder Archphene Login"
    [[ "$client_output" == "$kwallet_query_value" ]] \
      || archphene_die "packaged Arch kwallet-query read/write mismatch: $client_output"

    invoke_kwallet '0 1' "kill $kwallet_pid"
    kwallet_pid=
    sleep .3
    invoke_kwallet '0' "nohup $libsecret_loader $libsecret_root/kwalletd6 "\
"</dev/null >>$kwallet_home/kwalletd.log 2>&1 & echo \$!"
    kwallet_pid="$client_output"
    [[ "$kwallet_pid" =~ ^[0-9]+$ ]] \
      || archphene_die "restarted KWallet daemon returned an invalid PID: $kwallet_pid"
    sleep .75
    invoke_kwallet '0' "$libsecret_loader $libsecret_root/kwallet-query "\
"--read-password bridge-entry --folder Archphene Login"
    [[ "$client_output" == "$kwallet_query_value" ]] \
      || archphene_die "KWallet secret did not persist across daemon restart: $client_output"
    invoke_kwallet '0' "${gdbus}open Login 0 archphene-probe"
    handle="$(sed -n 's/[^-0-9]*\(-\{0,1\}[0-9][0-9]*\).*/\1/p' \
      <<<"$client_output" | head -n1)"
    [[ "$handle" =~ ^[0-9]+$ ]] && ((handle > 0)) \
      || archphene_die "KWallet daemon returned an invalid restart handle: $client_output"
    invoke_kwallet '0' "${gdbus}removeEntry $handle Archphene bridge-entry archphene-probe"
    [[ "$client_output" == '(0,)' ]] \
      || archphene_die "KWallet cleanup failed: $client_output"
    invoke_kwallet '0 1' "kill $kwallet_pid"
    kwallet_pid=
    kwallet_validated=true
  fi
fi

logs="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneCapabilities:I AndroidRuntime:E '*:S')"
[[ "$logs" != *'FATAL EXCEPTION'* ]] || archphene_die "secrets probe crashed: $logs"
[[ "$logs" != *"$secret"* && "$logs" != *"$updated_secret"* \
    && "$logs" != *"$libsecret_value"* \
    && "$logs" != *"$kwallet_direct_value"* \
    && "$logs" != *"$kwallet_query_value"* ]] \
  || archphene_die 'secret plaintext was written to Android logs'
client_result=
if [[ "$libsecret_validated" == true && "$kwallet_validated" == true ]]; then
  client_result=', and packaged Arch libsecret and KWallet clients validated'
elif [[ "$libsecret_validated" == true ]]; then
  client_result=', and packaged Arch libsecret validated; KWallet was not included'
elif [[ "$abi" == x86_64 ]]; then
  client_result='; packaged Arch clients skipped on a non-4 KB lane'
else
  client_result='; packaged Arch clients were not included in this ABI probe'
fi
archphene_note "Android secrets bridge passed on $serial ($abi, $page-byte pages): encrypted storage, metadata, overwrite, restart persistence, bounds, deletion, lifecycle, log redaction, the Secret Service D-Bus wire contract$client_result."
