#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p73ccc00a787cdc19febdd4a01d4b9d10
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
archphene_adb_run shell pm path "$package" >/dev/null \
  || archphene_die "generated wrapper is not installed: $package"
activity="$(archphene_launcher "$package")"
home="/data/user/0/$package/files/linux-home"
stdout_file=runtime-command-boundary.out
stderr_file=runtime-command-boundary.err
status_file=runtime-command-boundary.status
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f \
    "files/linux-home/$stdout_file" "files/linux-home/$stderr_file" \
    "files/linux-home/$status_file" >/dev/null 2>&1 || true
}
trap cleanup EXIT

command="clear > $home/$stdout_file 2> $home/$stderr_file; "
command+="printf '%s' \"\$?\" > $home/$status_file"
encoded="$(printf %s "$command" | base64 -w0)"
archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --es archphene_test_ime_commit_base64 "$encoded" \
  --ez archphene_test_ime_submit true >/dev/null
archphene_wait_log 'Injected test IME preeditBytes=0.*submit=true' 45 \
  'ArchpheneInput:I ArchpheneLinuxApp:V AndroidRuntime:E *:S' >/dev/null

deadline=$((SECONDS + 20))
status=
while ((SECONDS < deadline)); do
  status="$(archphene_adb_run shell run-as "$package" \
    cat "files/linux-home/$status_file" 2>/dev/null | tr -d '\r' || true)"
  [[ -n "$status" ]] && break
  sleep .25
done
[[ "$status" == 127 ]] \
  || archphene_die "unpublished Android PATH command returned $status instead of 127"
error="$(archphene_adb_run shell run-as "$package" \
  cat "files/linux-home/$stderr_file" | tr -d '\r')"
[[ "$error" == *'clear: command not found'* \
    || "$error" == *'/system/bin/clear: cannot execute: required file not found'* ]] \
  || archphene_die "unpublished command did not fail as a normal shell lookup: $error"
[[ "$error" != *'CANNOT LINK EXECUTABLE'* && "$error" != *'libc.so.6'* ]] \
  || archphene_die "unpublished command escaped into Android's linker namespace"
stdout="$(archphene_adb_run shell run-as "$package" \
  cat "files/linux-home/$stdout_file" | tr -d '\r')"
[[ -z "$stdout" ]] || archphene_die 'blocked Android PATH command produced output'

archphene_note "Runtime command boundary passed on $serial: verified pack commands remain brokered and unpublished Android PATH commands fail closed."
