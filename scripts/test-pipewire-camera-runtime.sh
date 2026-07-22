#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/tooling/build/pipewire-camera/x86_64"
supervisor="$out/archphene-runtime-supervisor"
[[ -x "$supervisor" ]] || {
  echo "Build the x86_64 PipeWire camera runtime first" >&2
  exit 1
}

runtime="$(mktemp -d)"
app_pid=
cleanup() {
  if [[ -n "$app_pid" ]]; then
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
  rm -rf "$runtime"
}
trap cleanup EXIT

export XDG_RUNTIME_DIR="$runtime"
export ARCHPHENE_PIPEWIRE_DEBUG_LOG="$runtime/helpers.log"
export ARCHPHENE_PIPEWIRE_TEST_PATTERN=1
export PIPEWIRE_DEBUG=0

"$supervisor" /lib64/ld-linux-x86-64.so.2 "$out" \
  sleep /usr/bin/sleep 15 &
app_pid=$!
for _ in $(seq 1 100); do
  [[ -S "$runtime/pipewire-0" ]] && break
  kill -0 "$app_pid" 2>/dev/null || break
  sleep 0.05
done
[[ -S "$runtime/pipewire-0" ]] || {
  cat "$runtime/helpers.log" >&2
  echo "Private PipeWire core did not start" >&2
  exit 1
}

sleep 0.25
pw-dump -N >"$runtime/registry.json" 2>"$runtime/pw-dump.log"
camera_node="$(
  jq -r '.[] | select(.info.props["node.name"] == "archphene.android.camera") | .id' \
    "$runtime/registry.json"
)"
[[ "$camera_node" =~ ^[0-9]+$ ]] || {
  cat "$runtime/registry.json" >&2
  echo "Camera node was not registered" >&2
  exit 1
}

GST_DEBUG=pipewiresrc:6 timeout 8 gst-launch-1.0 -q \
  pipewiresrc path="$camera_node" num-buffers=3 ! \
  "video/x-raw,format=I420,width=640,height=480,framerate=30/1" ! \
  filesink location="$runtime/frames.i420" \
  >"$runtime/gstreamer.log" 2>&1 || {
    cat "$runtime/gstreamer.log" >&2
    cat "$runtime/helpers.log" >&2
    echo "Unmodified GStreamer PipeWire consumer failed" >&2
    exit 1
  }

expected_bytes=$((3 * 640 * 480 * 3 / 2))
actual_bytes="$(stat -c %s "$runtime/frames.i420")"
[[ "$actual_bytes" -eq "$expected_bytes" ]] || {
  echo "Expected $expected_bytes frame bytes, found $actual_bytes" >&2
  exit 1
}
unique_luma="$(
  od -An -tu1 -N 640 "$runtime/frames.i420" |
    tr -s ' ' '\n' |
    sed '/^$/d' |
    sort -u |
    wc -l
)"
[[ "$unique_luma" -gt 8 ]] || {
  echo "Camera test frames did not contain the expected luma pattern" >&2
  exit 1
}
grep -q "Archphene camera link=" "$runtime/helpers.log"
grep -Eq "pts [1-9][0-9]+" "$runtime/gstreamer.log" || {
  cat "$runtime/gstreamer.log" >&2
  echo "PipeWire frames did not carry monotonic presentation timestamps" >&2
  exit 1
}
! grep -q "pts 18446744073709551615" "$runtime/gstreamer.log" || {
  cat "$runtime/gstreamer.log" >&2
  echo "PipeWire frames carried an unknown presentation timestamp" >&2
  exit 1
}

mapfile -t helper_pids < <(pgrep -P "$app_pid" || true)
kill "$app_pid"
wait "$app_pid" 2>/dev/null || true
app_pid=
for _ in $(seq 1 40); do
  live=0
  for pid in "${helper_pids[@]}"; do
    state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
    [[ -n "$state" && "$state" != Z* ]] && live=1
  done
  [[ "$live" -eq 0 ]] && break
  sleep 0.05
done
[[ "$live" -eq 0 ]] || {
  echo "PipeWire helper processes survived target exit" >&2
  exit 1
}

"$supervisor" /lib64/ld-linux-x86-64.so.2 "$out" \
  true /usr/bin/true
echo "Private PipeWire camera runtime passed registry, frame, cleanup, and restart tests"
