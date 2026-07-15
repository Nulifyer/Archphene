#!/usr/bin/env bash
set -euo pipefail

root="${TMPDIR:-/tmp}/archphene-path-bridge-test"
output="${1:-$root/libarchphene_path_bridge.so}"
rm -rf "$root"
mkdir -p "$root/usr/share/archphene-test"
printf expected > "$root/usr/share/archphene-test/value"

gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
  -o "$output" native/archphene-glibc-path-bridge/path_bridge.c -ldl
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/rename-probe" native/archphene-glibc-path-bridge/rename_probe.c
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/mkdir-probe" native/archphene-glibc-path-bridge/mkdir_probe.c
export LD_PRELOAD="$output"
export ARCHPHENE_RUNTIME_ROOT="$root"

test "$(cat /usr/share/archphene-test/value)" = expected
stat /usr/share/archphene-test/value >/dev/null
ls /usr/share/archphene-test | grep -qx value
if printf bad > /usr/share/archphene-test/value 2>/dev/null; then
  echo "translated write unexpectedly succeeded" >&2
  exit 20
fi
if cat /usr/share/../etc/passwd >/dev/null 2>&1; then
  echo "translated parent traversal unexpectedly succeeded" >&2
  exit 21
fi
test "$(cat "$root/usr/share/archphene-test/value")" = expected
printf rename-compatible > "$root/rename-source"
"$root/rename-probe" "$root/rename-source" "$root/rename-target"
test "$(cat "$root/rename-target")" = rename-compatible
"$root/mkdir-probe" "$root/mkdir-target"
test -d "$root/mkdir-target"
printf 'path-bridge-tests-passed\n'