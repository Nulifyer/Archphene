#!/usr/bin/env bash

# Shared debug-performance probe helpers. The caller must source
# android-test.sh first and set `manager`.

performance_probe_init() {
  receiver="$manager/org.archphene.app.PerformanceTestReceiver"
  reset_action=org.archphene.app.debug.action.RESET_PERFORMANCE
  snapshot_action=org.archphene.app.debug.action.SNAPSHOT_PERFORMANCE
  performance_token=performance-gate
}

reset_metrics() {
  archphene_adb_run shell am broadcast \
    -n "$receiver" \
    -a "$reset_action" \
    --es token "$performance_token" >/dev/null
  archphene_adb_run logcat -c
}

snapshot_metrics() {
  local log payload
  archphene_adb_run shell am broadcast \
    -n "$receiver" \
    -a "$snapshot_action" \
    --es token "$performance_token" >/dev/null
  log="$(
    archphene_wait_log \
      'terminalCalls=[0-9]+' \
      10 'ArchphenePerformanceProbe:I AndroidRuntime:E *:S'
  )"
  [[ "$log" != *'FATAL EXCEPTION'* ]] ||
    archphene_die "performance probe emitted a fatal runtime error"
  payload="$(sed -n 's/^.*): //p' <<<"$log" | tail -1)"
  [[ "$payload" == terminalCalls=* ]] ||
    archphene_die "could not parse performance probe: $log"
  printf '%s\n' "$payload"
}

performance_metric() {
  local payload="$1" key="$2" value
  value="$(
    tr ' ' '\n' <<<"$payload" |
      sed -n "s/^${key}=//p" |
      tail -1
  )"
  [[ "$value" =~ ^-?[0-9]+$ ]] ||
    archphene_die "missing performance metric $key: $payload"
  printf '%s\n' "$value"
}

assert_at_least() {
  local payload="$1" key="$2" minimum="$3" value
  value="$(performance_metric "$payload" "$key")"
  ((value >= minimum)) ||
    archphene_die "$key was below $minimum: $value"
}

assert_at_most() {
  local payload="$1" key="$2" maximum="$3" value
  value="$(performance_metric "$payload" "$key")"
  ((value <= maximum)) ||
    archphene_die "$key exceeded $maximum: $value"
}

assert_art_bound() {
  local payload="$1" key="$2" maximum="$3" value
  value="$(performance_metric "$payload" "$key")"
  ((value == -1 || value <= maximum)) ||
    archphene_die "$key exceeded $maximum: $value"
}

assert_resource_bounds() {
  local payload="$1"
  assert_at_most "$payload" totalPssKb "$max_pss_kb"
  assert_at_most "$payload" totalRssKb "$max_rss_kb"
  assert_at_most "$payload" javaHeapPssKb "$max_java_heap_kb"
  assert_at_most "$payload" nativeHeapPssKb "$max_native_heap_kb"
  assert_at_most "$payload" threads "$max_threads"
  assert_at_most "$payload" fds "$max_fds"
  assert_at_most "$payload" uidProcesses "$max_uid_processes"
  assert_at_most "$payload" frameP95Ms "$max_frame_p95_ms"
}

assert_no_fatal_logs() {
  local label="$1" fatal_log
  fatal_log="$(
    archphene_adb_run logcat -d -v brief \
      -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
  )"
  [[ "$fatal_log" != *'FATAL EXCEPTION'* && "$fatal_log" != *'Fatal signal'* ]] ||
    archphene_die "$label emitted a fatal runtime error: $fatal_log"
}

resource_snapshot() {
  local pid meminfo total_pss total_rss java_heap native_heap status threads fds uid
  local uid_processes gfxinfo frames janky p95
  pid="$(archphene_android_pid "$manager")"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || archphene_die "manager process is missing"
  meminfo="$(archphene_adb_run shell dumpsys meminfo "$manager" | tr -d '\r')"
  total_pss="$(sed -n 's/.*TOTAL PSS:[[:space:]]*\([0-9]*\).*/\1/p' <<<"$meminfo" | head -1)"
  total_rss="$(sed -n 's/.*TOTAL RSS:[[:space:]]*\([0-9]*\).*/\1/p' <<<"$meminfo" | head -1)"
  java_heap="$(awk '/Java Heap:/{print $3; exit}' <<<"$meminfo")"
  native_heap="$(awk '/Native Heap:/{print $3; exit}' <<<"$meminfo")"
  status="$(
    archphene_adb_run shell run-as "$manager" cat "/proc/$pid/status" |
      tr -d '\r'
  )"
  threads="$(awk '/^Threads:/{print $2}' <<<"$status")"
  fds="$(archphene_adb_run shell run-as "$manager" ls "/proc/$pid/fd" | wc -l)"
  uid="$(
    archphene_adb_run shell cmd package list packages -U "$manager" |
      sed -n 's/.*uid://p' |
      tr -d '\r'
  )"
  uid_processes="$(
    archphene_adb_run shell ps -A -o UID,PID,NAME |
      awk -v uid="$uid" '$1 == uid {count++} END {print count + 0}'
  )"
  gfxinfo="$(archphene_adb_run shell dumpsys gfxinfo "$manager" | tr -d '\r')"
  frames="$(awk -F': ' '/Total frames rendered:/{print $2; exit}' <<<"$gfxinfo")"
  janky="$(awk '/^Janky frames:/{print $3; exit}' <<<"$gfxinfo")"
  p95="$(sed -n 's/^95th percentile: \([0-9]*\)ms/\1/p' <<<"$gfxinfo" | head -1)"
  for value in \
    "$total_pss" "$total_rss" "$java_heap" "$native_heap" \
    "$threads" "$fds" "$uid_processes"; do
    [[ "$value" =~ ^[0-9]+$ ]] ||
      archphene_die "could not parse active manager resource snapshot"
  done
  [[ "$frames" =~ ^[0-9]+$ ]] || frames=0
  [[ "$janky" =~ ^[0-9]+$ ]] || janky=0
  [[ "$p95" =~ ^[0-9]+$ ]] || p95=0
  if ((frames == 0)); then
    p95=0
  fi
  printf \
    'pid=%s totalPssKb=%s totalRssKb=%s javaHeapPssKb=%s nativeHeapPssKb=%s threads=%s fds=%s uidProcesses=%s frames=%s jankyFrames=%s frameP95Ms=%s\n' \
    "$pid" "$total_pss" "$total_rss" "$java_heap" "$native_heap" \
    "$threads" "$fds" "$uid_processes" "$frames" "$janky" "$p95"
}
