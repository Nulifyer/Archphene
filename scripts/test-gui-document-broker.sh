#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
source_package=
target=org.archphene.linux.mousepad
tag=ArchpheneMousepad
timeout=35
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --source-package) source_package="${2:?}"; shift 2 ;;
    --target-package) target="${2:?}"; shift 2 ;;
    --target-log-tag) tag="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_test_init "$serial"

manager=org.archpheneos.manager
archphene_adb_run shell pm path "$manager" >/dev/null
archphene_adb_run shell pm path "$target" >/dev/null
component="$(archphene_launcher "$target")"

if [[ -z "$source_package" ]]; then
  while IFS= read -r candidate; do
    candidate="${candidate#package:}"
    [[ "$candidate" != "$target" ]] || continue
    candidate_dump="$(archphene_adb_run shell dumpsys package "$candidate")"
    if [[ "$candidate_dump" == *"$candidate.documents"* ]]; then
      source_package="$candidate"
      break
    fi
  done < <(archphene_adb_run shell pm list packages org.archphene.linux)
fi
[[ "$source_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ \
    && "$source_package" != "$target" ]] \
  || archphene_die 'a distinct generated GUI source wrapper is required'
archphene_adb_run shell pm path "$source_package" >/dev/null
probe_suffixes=()
cleanup() {
  archphene_adb_run shell am force-stop "$target" >/dev/null 2>&1 || true
  local suffix base second
  for suffix in "${probe_suffixes[@]}"; do
    archphene_adb_run shell run-as "$source_package" rm -rf \
      "files/linux-home/document-probe-a-$suffix" \
      "files/linux-home/document-probe-b-$suffix" \
      "files/linux-home/document-probe-c-$suffix" \
      "files/linux-home/document-probe-d-$suffix" >/dev/null 2>&1 || true
    base="archphene-document-probe-$suffix"
    second="$base (2).txt"
    archphene_adb_run shell run-as "$target" sh -c \
      "'rm -f \"files/linux-home/Documents/Android/$base.txt\" \"files/linux-home/Documents/Android/$second\"'" \
      >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

wait_document_log() {
  local expected="$1" failure="$2" deadline log
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    log="$(archphene_adb_run logcat -d -v brief \
      -s ArchpheneDocuments:I ArchpheneLinuxApp:I "$tag:I" AndroidRuntime:E '*:S')"
    if [[ "$log" == *"$expected"* ]]; then
      DOCUMENT_LOG="$log"
      return 0
    fi
    if archphene_regex_contains "$log" \
        'GUI document broker failed|Document conflict probe failed|FATAL EXCEPTION'; then
      archphene_die "$failure: $log"
    fi
    sleep 0.5
  done
  archphene_die "$failure: timed out waiting for $expected"
}

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -n "$manager/.MainActivity" \
  --es archphene_test_gui_documents "$source_package" >/dev/null
wait_document_log "GUI document broker passed package=$source_package" \
  'manager GUI document CRUD probe failed'

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$target"
archphene_adb_run shell am start -n "$component" \
  --es archphene_test_private_broker_authority "$source_package.documents" >/dev/null
wait_document_log 'Private GUI home provider' 'private provider denial probe failed'
archphene_regex_contains "$DOCUMENT_LOG" \
  'denied unauthorized caller|unavailable to unauthorized caller' \
  || archphene_die "private provider did not deny caller: $DOCUMENT_LOG"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$target"
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -n "$manager/.MainActivity" \
  --es archphene_test_document_session_source "$source_package" \
  --es archphene_test_document_session_target "$target" >/dev/null
wait_document_log 'Launched initial document session probe' \
  'initial multi-document session probe failed'
initial_suffix="$(sed -n 's/.*Launched initial document session probe .* suffix=\([0-9a-f][0-9a-f]*\).*/\1/p' \
  <<<"$DOCUMENT_LOG" | tail -n1)"
[[ -n "$initial_suffix" ]] || archphene_die 'initial document probe suffix is missing'
probe_suffixes+=("$initial_suffix")
archphene_wait_log 'mapped=true.*primary=true' "$timeout" \
  'ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E *:S' >/dev/null
# Android 15 correctly blocks the manager from launching a second Activity
# after the first document intent has put it in the background. Bring the
# debug manager back to the foreground before it sends the continuation; this
# models a visible external app opening another document without weakening the
# platform background-activity-launch policy.
archphene_adb_run shell am start -W --activity-single-top \
  -n "$manager/.MainActivity" \
  --es archphene_test_document_session_source "$source_package" \
  --es archphene_test_document_session_target "$target" \
  --ez archphene_test_document_session_continue true >/dev/null
wait_document_log 'Launched running document restart probe' \
  'running multi-document restart launch failed'
restart_suffix="$(sed -n 's/.*Launched running document restart probe .* suffix=\([0-9a-f][0-9a-f]*\).*/\1/p' \
  <<<"$DOCUMENT_LOG" | tail -n1)"
[[ -n "$restart_suffix" ]] || archphene_die 'restart document probe suffix is missing'
probe_suffixes+=("$restart_suffix")
wait_document_log 'Running document restart probe passed documents=2' \
  'running multi-document restart probe failed'

cleanup
trap - EXIT
archphene_note "GUI document broker passed on $serial: manager CRUD, cross-UID private-provider denial, running-app restart, same-name import, conflict preservation, and writeback validated."
