#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
wrapper=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --wrapper) wrapper="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
manager=org.archpheneos.manager
activity="$(archphene_launcher "$wrapper")"
cleanup() {
  archphene_adb_run shell am force-stop "$wrapper" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
}
trap cleanup EXIT

packages="$(archphene_adb_run shell cmd package list packages -U)"
package_uid() {
  local package="$1"
  sed -n "s/^package:$package uid:\\([0-9]*\\).*/\\1/p" <<<"$packages" \
    | head -n1
}
manager_uid="$(package_uid "$manager")"
wrapper_uid="$(package_uid "$wrapper")"
[[ -n "$manager_uid" && -n "$wrapper_uid" ]] \
  || archphene_die 'manager and runtime wrapper must both be installed'
[[ "$manager_uid" != "$wrapper_uid" ]] \
  || archphene_die 'manager and wrapper require distinct Android UIDs'

manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
if archphene_regex_contains "$manager_dump" \
    '(?m)^\s*flags=\[[^]]*DEBUGGABLE'; then
  legacy_payload="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/assets/payload-hello-linux-amd64"
  archphene_require_file "$legacy_payload"
  legacy_hash="$(archphene_sha256_file "$legacy_payload")"
  legacy_uri="content://org.archpheneos.manager.runtime/v1/$legacy_hash"

  archphene_adb_run logcat -c
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" \
    --es archphene_test_runtime_module_package "$wrapper" \
    --es archphene_test_runtime_module_action verify_catalog >/dev/null
  archphene_wait_log 'Runtime catalog parser passed' 30 >/dev/null

  archphene_adb_run logcat -c
  archphene_adb_run shell am force-stop "$wrapper"
  archphene_adb_run shell am start -W -n "$activity" \
    --es archphene_test_runtime_module_uri "$legacy_uri" >/dev/null
  denied="$(archphene_wait_log 'Runtime FD probe failed' 30)"
  ! archphene_regex_contains "$denied" 'Runtime FD probe exit=0' \
    || archphene_die 'wrapper opened an unbound legacy runtime module'
else
  legacy_payload="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/assets/payload-hello-linux-amd64"
  archphene_require_file "$legacy_payload"
  legacy_hash="$(archphene_sha256_file "$legacy_payload")"
  legacy_uri="content://org.archpheneos.manager.runtime/v1/$legacy_hash"
  denied="$(archphene_adb_run shell content read --uri "$legacy_uri" 2>&1 || true)"
  archphene_regex_contains "$denied" \
    'Runtime module is unavailable to this caller|Permission Denial' \
    || archphene_die 'production runtime provider did not reject the shell caller'
fi

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$wrapper"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
lease="$(archphene_wait_log \
  "Acquired runtime pack lease [a-f0-9]{64} for ${wrapper//./\\.}" 30)"
pack="$(python3 -c '
import re, sys
match = re.search(r"Acquired runtime pack lease ([a-f0-9]{64})",
                  sys.stdin.read())
print(match.group(1) if match else "")
' <<<"$lease")"
[[ "$pack" =~ ^[a-f0-9]{64}$ ]] \
  || archphene_die 'runtime pack lease did not identify its immutable pack'

pid="$(archphene_android_pid "$wrapper")"
deadline=$((SECONDS + 30))
linux_pid=
while ((SECONDS < deadline)); do
  linux_pid="$(archphene_linux_loader_pid "$pid" || true)"
  [[ -n "$linux_pid" ]] && break
  sleep .5
done
[[ -n "$linux_pid" ]] || archphene_die 'managed Linux loader is missing'

runtime_log="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneRuntime:V AndroidRuntime:E '*:S')"
! archphene_regex_contains "$runtime_log" \
  'Runtime GUI exit=|FATAL EXCEPTION' \
  || archphene_die 'runtime exited before the Linux loader became interactive'

fds="$(archphene_adb_run shell run-as "$wrapper" \
  ls -l "/proc/$linux_pid/fd")"
archphene_regex_contains "$fds" 'runtime-fd-[^/]+/\.program' \
  || archphene_die 'Linux loader is missing its bounded program view'
archphene_regex_contains "$fds" 'runtime-fd-[^/]+/\.library-' \
  || archphene_die 'Linux loader is missing its bounded library view'

live="$(archphene_adb_run shell run-as "$wrapper" du -sk cache \
  | awk 'NR == 1 { print $1 }')"
[[ "$live" =~ ^[0-9]+$ ]] \
  || archphene_die "could not measure live wrapper cache: $live"
((live > 0 && live < 524288)) \
  || archphene_die "wrapper execution cache is outside 512 MiB: $live KiB"

archphene_adb_run shell input keyevent 4
archphene_wait_log \
  "(Released runtime pack lease|Runtime process died; released pack lease) $pack" \
  20 >/dev/null
deadline=$((SECONDS + 20))
clean="$live"
while ((SECONDS < deadline)); do
  clean="$(archphene_adb_run shell run-as "$wrapper" du -sk cache \
    | awk 'NR == 1 { print $1 }')"
  [[ "$clean" =~ ^[0-9]+$ ]] || clean="$live"
  ((clean < 65536)) && break
  sleep .5
done
((clean < 65536)) \
  || archphene_die "wrapper retained a runtime closure after exit: $clean KiB"

cleanup
trap - EXIT
archphene_note "Runtime-pack trust and execution passed on $serial: catalog and unbound-module rejection, manager UID $manager_uid -> wrapper UID $wrapper_uid, pack $pack, cache $live -> $clean KiB."
