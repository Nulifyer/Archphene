#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archphene.app.debug
wrapper=org.archphene.linux.p46204b29816e2006b6f4a02b6c452e56
timeout=30
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --manager) manager="${2:?}"; shift 2 ;;
    --wrapper) wrapper="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--manager PACKAGE] [--wrapper PACKAGE] [--timeout-seconds N]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
((timeout >= 10 && timeout <= 120)) \
  || archphene_die "timeout must be from 10 to 120 seconds"

archphene_test_init "$serial"
archphene_adb_run shell pm path "$manager" >/dev/null \
  || archphene_die "manager is not installed: $manager"
archphene_adb_run shell pm path "$wrapper" >/dev/null \
  || archphene_die "wrapper is not installed: $wrapper"

abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in
  arm64-v8a)
    capability_probe="$ARCHPHENE_ROOT/tooling/build/capability-broker/arm64-v8a/archphene-capability-probe"
    dbus_probe="$ARCHPHENE_ROOT/tooling/build/android-dbus/aarch64/secret-probe"
    ;;
  x86_64)
    capability_probe="$ARCHPHENE_ROOT/tooling/build/capability-broker/x86_64/archphene-capability-probe"
    dbus_probe="$ARCHPHENE_ROOT/tooling/build/android-dbus/x86_64/secret-probe"
    ;;
  *) archphene_die "unsupported Android ABI: $abi" ;;
esac
archphene_require_file "$capability_probe"
archphene_require_file "$dbus_probe"

component="$(archphene_launcher "$wrapper")"
test_id="current-secrets-$(date +%s)-$$"
direct_value="direct-$test_id"
libsecret_value="libsecret-$test_id"
remote_capability=/data/local/tmp/archphene-current-secret-probe
remote_dbus=/data/local/tmp/archphene-current-dbus-secret-probe
private_capability=code_cache/archphene-current-secret-probe
private_dbus=code_cache/archphene-current-dbus-secret-probe
secret_input="cache/$test_id-input"
secret_output="cache/$test_id-output"
secret_index="cache/$test_id-index"
secret_catalog="cache/$test_id-catalog"
oversized_input="cache/$test_id-oversized"
loader_alias="cache/$test_id-loader"
temporary="$(archphene_mktemp_dir current-secrets)"
current_socket=
current_bus=
environment_file=
runtime_environment=()
runtime_root=
runtime_loader=
runtime_library_path=
initial_count=

cleanup() {
  if [[ -n "$runtime_root" ]]; then
    run_secret_tool "" clear application archphene-secret-service-probe \
      >/dev/null 2>&1 || true
    clear_test_records >/dev/null 2>&1 || true
  fi
  if [[ -n "$current_socket" ]]; then
    archphene_adb_run shell run-as "$manager" "$private_capability" \
      --socket "@$current_socket" delete-secret "$test_id" >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell \
    "run-as $manager sh -c 'rm -f $private_capability $private_dbus $secret_input $secret_output $secret_index $secret_catalog $oversized_input $loader_alias'" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f \
    "$remote_capability" "$remote_dbus" "/data/local/tmp/$test_id-input" \
    "/data/local/tmp/$test_id-unauthorized" \
    >/dev/null 2>&1 || true
  if [[ "$temporary" == "$ARCHPHENE_ROOT/tooling/build/current-secrets."* ]]; then
    rm -rf -- "$temporary"
  fi
}
trap cleanup EXIT

archphene_adb_run push "$capability_probe" "$remote_capability" >/dev/null
archphene_adb_run push "$dbus_probe" "$remote_dbus" >/dev/null
archphene_adb_run shell chmod 755 "$remote_capability" "$remote_dbus"
archphene_adb_run shell run-as "$manager" cp "$remote_capability" "$private_capability"
archphene_adb_run shell run-as "$manager" cp "$remote_dbus" "$private_dbus"
archphene_adb_run shell run-as "$manager" chmod 700 "$private_capability" "$private_dbus"

find_socket() {
  local deadline manager_pid candidates socket output
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    manager_pid="$(archphene_android_pid "$manager" || true)"
    if [[ "$manager_pid" =~ ^[1-9][0-9]*$ ]]; then
      candidates="$(
        archphene_adb_run shell cat /proc/net/unix |
          sed -n "s/.*@\\(archphene\\.portal\\.$manager_pid\\.[1-9][0-9]*\\.[0-9a-f]*\\).*/\\1/p" |
          tr -d '\r'
      )"
      while IFS= read -r socket; do
        [[ -n "$socket" ]] || continue
        output="$(
          archphene_adb_run shell run-as "$manager" "$private_capability" \
            --socket "@$socket" list-secrets "$secret_index" 2>/dev/null || true
        )"
        if [[ "$output" =~ ^OK[[:space:]][0-9]+$ ]]; then
          current_socket="$socket"
          return 0
        fi
      done <<<"$candidates"
    fi
    sleep 0.3
  done
  archphene_die "current launcher capability socket did not become ready"
}

find_runtime_environment() {
  local deadline manager_pid processes pid candidate
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    manager_pid="$(archphene_android_pid "$manager" || true)"
    if [[ "$manager_pid" =~ ^[1-9][0-9]*$ ]]; then
      processes="$(archphene_adb_run shell ps -A -o PID,PPID | tr -d '\r')"
      mapfile -t descendants < <(
        awk -v root="$manager_pid" '
          NR > 1 { parent[$1] = $2 }
          END {
            descendant[root] = 1
            for (round = 0; round < 64; round++) {
              changed = 0
              for (pid in parent) {
                if (!descendant[pid] && descendant[parent[pid]]) {
                  descendant[pid] = 1
                  changed = 1
                }
              }
              if (!changed) break
            }
            for (pid in descendant) {
              if (pid != root && descendant[pid]) print pid
            }
          }
        ' <<<"$processes"
      )
      for pid in "${descendants[@]}"; do
        candidate="$temporary/environment-$pid"
        if ! archphene_adb_run exec-out run-as "$manager" \
            cat "/proc/$pid/environ" >"$candidate" 2>/dev/null; then
          continue
        fi
        if tr '\0' '\n' <"$candidate" |
            grep -Eq "^DBUS_SESSION_BUS_ADDRESS=unix:path=/data/data/$manager/cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$" &&
            tr '\0' '\n' <"$candidate" |
              grep -q '^ARCHPHENE_RUNTIME_PROGRAM_PATH='; then
          environment_file="$candidate"
          return 0
        fi
      done
    fi
    sleep 0.3
  done
  archphene_die "could not locate a Linux process in the current launcher session"
}

load_runtime_environment() {
  mapfile -d '' -t runtime_environment <"$environment_file"
  runtime_root=
  runtime_loader=
  runtime_library_path=
  runtime_bus_address=
  for item in "${runtime_environment[@]}"; do
    case "$item" in
      ARCHPHENE_RUNTIME_ROOT=*) runtime_root=${item#*=} ;;
      ARCHPHENE_RUNTIME_LOADER=*) runtime_loader=${item#*=} ;;
      ARCHPHENE_RUNTIME_LIB=*) runtime_library_path=${item#*=} ;;
      DBUS_SESSION_BUS_ADDRESS=*) runtime_bus_address=${item#*=} ;;
    esac
  done
  [[ "$runtime_root" == "/data/user/0/$manager/files/arch-root" ]] \
    || archphene_die "runtime root is invalid: $runtime_root"
  [[ "$runtime_loader" == /data/app/*/lib/*/libarchphene_pkg_*.so ]] \
    || archphene_die "runtime loader is invalid: $runtime_loader"
  [[ -n "$runtime_library_path" ]] \
    || archphene_die "runtime library path is missing"
  current_bus="${runtime_bus_address#unix:path=/data/data/$manager/}"
  [[ "$current_bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] \
    || archphene_die "runtime Secret Service bus is invalid: $runtime_bus_address"
  archphene_adb_run shell run-as "$manager" test -x "$runtime_root/usr/bin/secret-tool" \
    || archphene_die "the shared Arch root does not contain executable secret-tool"
  archphene_adb_run shell run-as "$manager" ln -sf "$runtime_loader" "$loader_alias"
}

validate_secret_service() {
  local output
  output="$(
    archphene_adb_run shell run-as "$manager" env \
      "DBUS_SESSION_BUS_ADDRESS=unix:path=/data/data/$manager/$current_bus" \
      "$private_dbus" 2>&1
  )"
  [[ "$output" == PASS\ Secret\ Service:* ]] \
    || archphene_die "native Secret Service regression failed: $output"
  ARCHPHENE_DBUS_PROBE_OUTPUT="$output"
}

run_secret_tool() {
  local input="$1"
  shift
  if [[ -n "$input" ]]; then
    printf '%s' "$input" |
      archphene_adb_run shell run-as "$manager" env -i \
        "${runtime_environment[@]}" \
        "ARCHPHENE_RUNTIME_PROGRAM_PATH=$runtime_root/usr/bin/secret-tool" \
        "/data/data/$manager/$loader_alias" \
        --library-path "$runtime_library_path" --argv0 secret-tool \
        "$runtime_root/usr/bin/secret-tool" "$@"
  else
    archphene_adb_run shell run-as "$manager" env -i \
      "${runtime_environment[@]}" \
      "ARCHPHENE_RUNTIME_PROGRAM_PATH=$runtime_root/usr/bin/secret-tool" \
      "/data/data/$manager/$loader_alias" \
      --library-path "$runtime_library_path" --argv0 secret-tool \
      "$runtime_root/usr/bin/secret-tool" "$@"
  fi
}

clear_test_records() {
  local attempt
  for attempt in $(seq 1 32); do
    if ! run_secret_tool "" clear application archphene-current-test; then
      return 0
    fi
  done
  printf 'error: more than 32 stale production secret-test records remain\n' >&2
  return 1
}

start_wrapper() {
  archphene_adb_run shell am force-stop "$wrapper" >/dev/null
  archphene_adb_run shell am start -W -n "$component" >/dev/null
  current_socket=
  current_bus=
  environment_file=
  find_socket
  find_runtime_environment
  load_runtime_environment
  validate_secret_service
}

start_wrapper
run_secret_tool "" clear application archphene-secret-service-probe || true
clear_test_records
initial_list="$(
  archphene_adb_run shell run-as "$manager" "$private_capability" \
    --socket "@$current_socket" list-secrets "$secret_index"
)"
initial_count="${initial_list#*$'\t'}"
[[ "$initial_count" =~ ^[0-9]+$ ]] \
  || archphene_die "initial secret count is invalid: $initial_list"
printf '%s' "$direct_value" >"$temporary/direct-value"
archphene_adb_run push "$temporary/direct-value" "/data/local/tmp/$test_id-input" >/dev/null
archphene_adb_run shell run-as "$manager" cp \
  "/data/local/tmp/$test_id-input" "$secret_input"

stored="$(
  archphene_adb_run shell run-as "$manager" "$private_capability" \
    --socket "@$current_socket" store-secret "$secret_input" "$test_id" \
    "$test_id" "{\"application\":\"archphene-current-test\"}"
)"
[[ "$stored" == OK ]] || archphene_die "direct secret store failed: $stored"

run_secret_tool "$libsecret_value" store --label="$test_id" \
  application archphene-current-test test-id "$test_id"

archphene_adb_run shell run-as "$manager" dd if=/dev/zero \
  of="$oversized_input" bs=65537 count=1 >/dev/null 2>&1
set +e
oversized="$(
  archphene_adb_run shell run-as "$manager" "$private_capability" \
    --socket "@$current_socket" store-secret "$oversized_input" oversized \
    oversized '{}' 2>&1
)"
oversized_status=$?
set -e
[[ $oversized_status -ne 0 && "$oversized" == $'ERROR\tINVALID_REQUEST' ]] \
  || archphene_die "oversized secret was not rejected precisely: $oversized"

set +e
unauthorized="$(
  archphene_adb_run shell "$remote_capability" --socket "@$current_socket" \
    list-secrets "/data/local/tmp/$test_id-unauthorized" 2>&1
)"
unauthorized_status=$?
set -e
[[ $unauthorized_status -ne 0 &&
    ( "$unauthorized" == *'Permission denied'* ||
      "$unauthorized" == *$'ERROR\tUNAUTHORIZED'* ) ]] \
  || archphene_die "cross-UID secret request was not rejected: $unauthorized"

start_wrapper
run_secret_tool "" clear application archphene-secret-service-probe || true
read_result="$(
  archphene_adb_run shell run-as "$manager" "$private_capability" \
    --socket "@$current_socket" read-secret "$secret_output" "$test_id"
)"
[[ "$read_result" == OK$'\t'* ]] \
  || archphene_die "persisted direct secret read failed: $read_result"
persisted="$(
  archphene_adb_run shell run-as "$manager" cat "$secret_output" | tr -d '\r'
)"
[[ "$persisted" == "$direct_value" ]] \
  || archphene_die "persisted direct secret value did not match"

libsecret_persisted="$(
  run_secret_tool "" lookup \
    application archphene-current-test test-id "$test_id" | tr -d '\r'
)"
[[ "$libsecret_persisted" == "$libsecret_value" ]] \
  || archphene_die "persisted libsecret value did not match"

listed="$(
  archphene_adb_run shell run-as "$manager" "$private_capability" \
    --socket "@$current_socket" list-secrets "$secret_index"
)"
cataloged="$(
  archphene_adb_run shell run-as "$manager" "$private_capability" \
    --socket "@$current_socket" catalog-secrets "$secret_catalog"
)"
expected_count=$((initial_count + 2))
[[ "$listed" == "OK"$'\t'"$expected_count" ]] \
  || archphene_die "secret list did not contain both test records: $listed"
[[ "$cataloged" == "OK"$'\t'"$expected_count" ]] \
  || archphene_die "secret catalog did not contain both test records: $cataloged"

logs="$(archphene_adb_run logcat -d -v threadtime)"
[[ "$logs" != *"$direct_value"* && "$logs" != *"$libsecret_value"* ]] \
  || archphene_die "secret plaintext appeared in Android logs"

run_secret_tool "" clear \
  application archphene-current-test test-id "$test_id"
deleted="$(
  archphene_adb_run shell run-as "$manager" "$private_capability" \
    --socket "@$current_socket" delete-secret "$test_id"
)"
[[ "$deleted" == OK ]] || archphene_die "direct secret cleanup failed: $deleted"
current_count="$(
  archphene_adb_run shell run-as "$manager" "$private_capability" \
    --socket "@$current_socket" list-secrets "$secret_index"
)"
[[ "$current_count" == "OK"$'\t'"$initial_count" ]] \
  || archphene_die "secret cleanup changed pre-existing records: $current_count"

archphene_note \
  "Current Secret Service passed on $serial ($abi): wrapper-owned Keystore persistence, unmodified secret-tool, native D-Bus semantics, precise bounds, cross-UID denial, redaction, and scoped cleanup ($current_count)."
