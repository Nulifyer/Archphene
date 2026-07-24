#!/usr/bin/env bash
set -euo pipefail

root="${TMPDIR:-/tmp}/archphene-path-bridge-test"
output="${1:-$root/libarchphene_path_bridge.so}"
loader_path="$(readlink -f /bin/echo)"
rm -rf "$root"
mkdir -p "$root/usr/share/archphene-test"
mkdir -p "$root/usr/lib/locale/C.utf8"
printf expected > "$root/usr/share/archphene-test/value"
printf expected-locale > "$root/usr/lib/locale/C.utf8/LC_CTYPE"

gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
  -o "$output" native/archphene-glibc-path-bridge/path_bridge.c -ldl
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/rename-probe" native/archphene-glibc-path-bridge/rename_probe.c
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/mkdir-probe" native/archphene-glibc-path-bridge/mkdir_probe.c
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/shm-probe" native/archphene-glibc-path-bridge/shm_probe.c
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/exec-probe" native/archphene-glibc-path-bridge/exec_probe.c
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/readlink-probe" native/archphene-glibc-path-bridge/readlink_probe.c
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/identity-probe" native/archphene-glibc-path-bridge/identity_probe.c
export LD_PRELOAD="$output"
export ARCHPHENE_RUNTIME_ROOT="$root"
export XDG_RUNTIME_DIR="$root/runtime"
mkdir -p "$XDG_RUNTIME_DIR"
mkdir -p "$root/usr/bin"
printf program > "$root/usr/bin/test-program"
chmod 500 "$root/usr/bin/test-program"
program_path="$(
  ARCHPHENE_RUNTIME_PROGRAM_PATH="$root/usr/bin/test-program" \
    "$root/readlink-probe"
)"
test "$program_path" = "$root/usr/bin/test-program"
mkdir -p "$root/commands"
printf command > "$root/commands/cat"
test ! -x "$root/commands/cat"
access_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  "$root/exec-probe" --access "$root/commands/cat"
)"
test "$access_output" = runtime-command-accessible
loader_output="$(
  ARCHPHENE_RUNTIME_LOADER="$loader_path" \
  "$root/exec-probe" --loader
)"
test "$loader_output" = trusted-loader-exec
exec_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$loader_path" \
  ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe"
)"
test "$exec_output" = "--library-path /lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu --argv0 cat $root/commands/cat bridge-arg"
set +e
ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
ARCHPHENE_RUNTIME_LOADER="$loader_path" \
ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" sh >"$root/unknown-command.out" 2>&1
unknown_status=$?
set -e
test "$unknown_status" -eq 2
grep -qx 'execlp: No such file or directory' "$root/unknown-command.out"
direct_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$loader_path" \
  ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --direct "$root/commands/cat"
)"
test "$direct_output" = "--library-path /lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu --argv0 cat $root/commands/cat bridge-arg"
set +e
ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
ARCHPHENE_RUNTIME_LOADER="$loader_path" \
ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --direct /bin/sh >"$root/direct-host-command.out" 2>&1
direct_status=$?
set -e
test "$direct_status" -eq 2
grep -qx 'execlp: No such file or directory' "$root/direct-host-command.out"
echo exec-bridge-tests-passed

test "$(cat /usr/share/archphene-test/value)" = expected
test "$(cat /usr/lib/locale/C.utf8/LC_CTYPE)" = expected-locale
stat /usr/share/archphene-test/value >/dev/null
ls /usr/share/archphene-test | grep -qx value
if printf bad | tee /usr/share/archphene-test/value >/dev/null 2>&1; then
  echo "translated write unexpectedly succeeded" >&2
  exit 20
fi
if printf bad | tee /usr/lib/locale/C.utf8/LC_CTYPE >/dev/null 2>&1; then
  echo "translated locale write unexpectedly succeeded" >&2
  exit 22
fi
if cat /usr/share/../etc/passwd >/dev/null 2>&1; then
  echo "translated parent traversal unexpectedly succeeded" >&2
  exit 21
fi
test "$(cat "$root/usr/share/archphene-test/value")" = expected
test "$(
  cd "$root/usr/share/archphene-test"
  ARCHPHENE_FAKE_CHROOT=1 /bin/pwd -P
)" = /usr/share/archphene-test
printf rename-compatible > "$root/rename-source"
"$root/rename-probe" "$root/rename-source" "$root/rename-target"
test "$(cat "$root/rename-target")" = rename-compatible
"$root/mkdir-probe" "$root/mkdir-target"
test -d "$root/mkdir-target"
"$root/shm-probe"
"$root/identity-probe"
printf 'path-bridge-tests-passed\n'
