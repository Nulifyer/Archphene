#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
producer="$root/tooling/build/pipewire-camera/x86_64/archphene-pipewire-camera"
[[ -x "$producer" ]] || {
  echo "Build the PipeWire camera producer first" >&2
  exit 1
}
runtime="$(mktemp -d)"
core_pid=
producer_pid=
cleanup() {
  [[ -z "$producer_pid" ]] || kill "$producer_pid" 2>/dev/null || true
  [[ -z "$core_pid" ]] || kill "$core_pid" 2>/dev/null || true
  rm -rf "$runtime"
}
trap cleanup EXIT
chmod 700 "$runtime"
export XDG_RUNTIME_DIR="$runtime"
pipewire >"$runtime/pipewire.log" 2>&1 &
core_pid=$!
for _ in $(seq 1 100); do
  [[ -S "$runtime/pipewire-0" ]] && break
  sleep 0.05
done
[[ -S "$runtime/pipewire-0" ]] || {
  cat "$runtime/pipewire.log" >&2
  exit 1
}
ARCHPHENE_PIPEWIRE_TEST_PATTERN=1 \
  "$producer" >"$runtime/camera.log" 2>&1 &
producer_pid=$!
registered=false
dump=
for _ in $(seq 1 100); do
  dump="$(pw-dump 2>/dev/null || true)"
  if [[ "$dump" == *'"node.name": "archphene.android.camera"'* ]]; then
    registered=true
    break
  fi
  kill -0 "$producer_pid" 2>/dev/null || break
  sleep 0.05
done
if [[ "$registered" != true ]]; then
  cat "$runtime/camera.log" >&2
  cat "$runtime/pipewire.log" >&2
  echo "Camera producer did not register its PipeWire node" >&2
  exit 1
fi
printf '%s\n' "$dump" > "$runtime/dump.json"
grep -q '"media.class": "Video/Source"' "$runtime/dump.json"
grep -q '"node.name": "archphene.android.camera"' "$runtime/dump.json"
grep -Eq '^Archphene camera node=[0-9]+$' "$runtime/camera.log"
echo "PASS private PipeWire camera producer registered a standard Video/Source node"
