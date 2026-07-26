#!/usr/bin/env bash
set -euo pipefail

root="${TMPDIR:-/tmp}/archphene-path-bridge-test"
output="${1:-$root/libarchphene_path_bridge.so}"
loader_path="$(readlink -f /bin/echo)"
rm -rf "$root"
mkdir -p "$root/usr/share/archphene-test"
mkdir -p "$root/usr/lib/locale/C.utf8"
mkdir -p "$root/usr/lib/archphene-example"
printf expected > "$root/usr/share/archphene-test/value"
printf expected-locale > "$root/usr/lib/locale/C.utf8/LC_CTYPE"
cp "$(readlink -f /bin/echo)" "$root/usr/lib/archphene-example/example"
chmod 500 "$root/usr/lib/archphene-example/example"

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
gcc -O2 -Wall -Wextra -Werror \
  -o "$root/socket-probe" native/archphene-glibc-path-bridge/socket_probe.c
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
cp "$(readlink -f /bin/echo)" "$root/commands/cat"
chmod 400 "$root/commands/cat"
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
exec_path_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$loader_path" \
  ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" "$root/commands/cat"
)"
test "$exec_path_output" = "--library-path /lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu --argv0 cat $root/commands/cat bridge-arg"
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
spawn_direct_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$loader_path" \
  ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --spawn-direct "$root/commands/cat"
)"
test "$spawn_direct_output" = "--library-path /lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu --argv0 cat $root/commands/cat bridge-arg"
spawn_path_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$loader_path" \
  ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --spawn-path cat
)"
test "$spawn_path_output" = "--library-path /lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu --argv0 cat $root/commands/cat bridge-arg"
nested_access_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  "$root/exec-probe" --access /usr/lib/archphene-example/example
)"
test "$nested_access_output" = runtime-command-accessible
nested_exec_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$loader_path" \
  ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --direct /usr/lib/archphene-example/example
)"
test "$nested_exec_output" = "--library-path /lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu --argv0 example $root/usr/lib/archphene-example/example bridge-arg"
nested_spawn_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$loader_path" \
  ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --spawn-path /usr/lib/archphene-example/example
)"
test "$nested_spawn_output" = "--library-path /lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu --argv0 example $root/usr/lib/archphene-example/example bridge-arg"
host_loader="$(gcc -print-file-name=ld-linux-x86-64.so.2)"
host_libc="$(gcc -print-file-name=libc.so.6)"
test -f "$host_loader"
test -f "$host_libc"
cp "$(readlink -f /bin/echo)" "$root/usr/lib/archphene-example/real-example"
chmod 500 "$root/usr/lib/archphene-example/real-example"
cp "$root/readlink-probe" "$root/usr/lib/archphene-example/readlink-example"
chmod 500 "$root/usr/lib/archphene-example/readlink-example"
real_nested_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  "$root/exec-probe" --direct /usr/lib/archphene-example/real-example
)"
test "$real_nested_output" = bridge-arg
real_program_path="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  ARCHPHENE_RUNTIME_PROGRAM_PATH=/stale/program \
  "$root/exec-probe" --direct /usr/lib/archphene-example/readlink-example
)"
test "$real_program_path" = "$root/usr/lib/archphene-example/readlink-example"
spawned_program_path="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  ARCHPHENE_RUNTIME_PROGRAM_PATH=/stale/program \
  "$root/exec-probe" --spawn-path /usr/lib/archphene-example/readlink-example
)"
test "$spawned_program_path" = "$root/usr/lib/archphene-example/readlink-example"
ln -s /usr/lib/archphene-example/real-example "$root/usr/bin/absolute-bare"
absolute_bare_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  "$root/exec-probe" absolute-bare
)"
test "$absolute_bare_output" = bridge-arg
cp "$(readlink -f /bin/echo)" "$root/usr/bin/non-executable"
chmod 400 "$root/usr/bin/non-executable"
set +e
ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
ARCHPHENE_RUNTIME_LOADER="$host_loader" \
ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  "$root/exec-probe" non-executable >"$root/non-executable.out" 2>&1
non_executable_status=$?
set -e
test "$non_executable_status" -eq 2
grep -qx 'execlp: No such file or directory' "$root/non-executable.out"
cp "$(readlink -f /bin/sh)" "$root/usr/bin/sh"
chmod 500 "$root/usr/bin/sh"
printf '%s\n' '#!/usr/bin/sh' 'printf "nested-script:%s\\n" "$1"' \
  >"$root/usr/lib/archphene-example/nested-script"
chmod 500 "$root/usr/lib/archphene-example/nested-script"
nested_script_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  ARCHPHENE_FAKE_CHROOT=1 \
  "$root/exec-probe" --direct /usr/lib/archphene-example/nested-script
)"
test "$nested_script_output" = nested-script:bridge-arg
nested_script_spawn_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  ARCHPHENE_FAKE_CHROOT=1 \
  "$root/exec-probe" --spawn-path /usr/lib/archphene-example/nested-script
)"
test "$nested_script_spawn_output" = nested-script:bridge-arg
printf '%s\n' '#!/usr/bin/sh -e' 'printf "shebang-argument:%s\\n" "$1"' \
  >"$root/usr/lib/archphene-example/shebang-argument"
chmod 500 "$root/usr/lib/archphene-example/shebang-argument"
shebang_argument_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  ARCHPHENE_FAKE_CHROOT=1 \
  "$root/exec-probe" --direct /usr/lib/archphene-example/shebang-argument
)"
test "$shebang_argument_output" = shebang-argument:bridge-arg
printf '%s\n' '#!/usr/bin/sh' 'printf "bare-script:%s\\n" "$1"' \
  >"$root/usr/bin/bare-script"
chmod 500 "$root/usr/bin/bare-script"
bare_script_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  ARCHPHENE_FAKE_CHROOT=1 \
  "$root/exec-probe" bare-script
)"
test "$bare_script_output" = bare-script:bridge-arg
printf '%s\n' '#!/usr/bin/recursive-interpreter' 'exit 0' \
  >"$root/usr/bin/recursive-interpreter"
chmod 500 "$root/usr/bin/recursive-interpreter"
printf '%s\n' '#!/usr/bin/recursive-interpreter' 'exit 0' \
  >"$root/usr/lib/archphene-example/recursive-script"
chmod 500 "$root/usr/lib/archphene-example/recursive-script"
set +e
ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
ARCHPHENE_RUNTIME_LOADER="$host_loader" \
ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
ARCHPHENE_FAKE_CHROOT=1 \
  "$root/exec-probe" --direct /usr/lib/archphene-example/recursive-script \
  >"$root/recursive-nested-script.out" 2>&1
recursive_nested_status=$?
set -e
test "$recursive_nested_status" -eq 1
grep -qx 'execlp: Exec format error' "$root/recursive-nested-script.out"
ln -s /usr/lib/archphene-example/real-example \
  "$root/usr/lib/archphene-example/absolute-link"
absolute_link_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  "$root/exec-probe" --direct /usr/lib/archphene-example/absolute-link
)"
test "$absolute_link_output" = bridge-arg
ln -s real-example "$root/usr/lib/archphene-example/relative-link"
relative_link_output="$(
  ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
  ARCHPHENE_RUNTIME_LOADER="$host_loader" \
  ARCHPHENE_RUNTIME_LIB="$(dirname "$host_libc")" \
  "$root/exec-probe" --spawn-direct /usr/lib/archphene-example/relative-link
)"
test "$relative_link_output" = bridge-arg
ln -s ../../../../../../bin/sh "$root/usr/lib/archphene-example/escape"
set +e
ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
ARCHPHENE_RUNTIME_LOADER="$loader_path" \
ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --direct /usr/lib/archphene-example/escape \
  >"$root/escaped-nested-command.out" 2>&1
escaped_nested_status=$?
set -e
test "$escaped_nested_status" -eq 2
grep -qx 'execlp: No such file or directory' "$root/escaped-nested-command.out"
ln -s cycle-b "$root/usr/lib/archphene-example/cycle-a"
ln -s cycle-a "$root/usr/lib/archphene-example/cycle-b"
set +e
ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
ARCHPHENE_RUNTIME_LOADER="$loader_path" \
ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --direct /usr/lib/archphene-example/cycle-a \
  >"$root/cyclic-nested-command.out" 2>&1
cyclic_nested_status=$?
set -e
test "$cyclic_nested_status" -eq 2
grep -qx 'execlp: No such file or directory' "$root/cyclic-nested-command.out"
cp "$root/usr/lib/archphene-example/example" \
  "$root/usr/lib/archphene-example/writable"
chmod 520 "$root/usr/lib/archphene-example/writable"
set +e
ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
ARCHPHENE_RUNTIME_LOADER="$loader_path" \
ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --direct /usr/lib/archphene-example/writable \
  >"$root/writable-nested-command.out" 2>&1
writable_nested_status=$?
set -e
test "$writable_nested_status" -eq 2
grep -qx 'execlp: No such file or directory' "$root/writable-nested-command.out"
set +e
ARCHPHENE_RUNTIME_COMMAND_DIR="$root/commands" \
ARCHPHENE_RUNTIME_LOADER="$loader_path" \
ARCHPHENE_RUNTIME_LIB=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu \
  "$root/exec-probe" --direct /system/bin/sh \
  >"$root/direct-host-command.out" 2>&1
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
mkdir -p "$root/run"
ARCHPHENE_FAKE_CHROOT=1 "$root/socket-probe" |
  grep -qx unix-socket-bridge-passed
test -S "$root/run/archphene-path-bridge-test.sock"
test ! -e /run/archphene-path-bridge-test.sock
printf 'path-bridge-tests-passed\n'
