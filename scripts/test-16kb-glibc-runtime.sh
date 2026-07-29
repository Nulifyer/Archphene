#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5556
package=org.archphene.app.debug
runtime_directory="$ARCHPHENE_ROOT/tooling/build/glibc-archphene-runtime-x86_64"
probe="$ARCHPHENE_ROOT/tooling/build/16kb-probe/runtime-probe-dynamic"
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --runtime-directory)
      runtime_directory="${2:?missing value for --runtime-directory}"
      shift 2
      ;;
    --probe) probe="${2:?missing value for --probe}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--package NAME] [--runtime-directory PATH] [--probe PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_require_directory "$runtime_directory"
archphene_require_file "$probe"
runtime_directory="$(realpath "$runtime_directory")"
probe="$(realpath "$probe")"
archphene_test_init "$serial"
page_size="$(
  archphene_adb_run shell getconf PAGE_SIZE |
    tr -d '\r\n'
)"
[[ "$page_size" == 16384 ]] ||
  archphene_die "$serial reports page size $page_size, expected 16384"
data_path="$(
  archphene_adb_run shell run-as "$package" pwd |
    tr -d '\r\n'
)"
[[ "$data_path" =~ ^/data/(user/[0-9]+|data)/[A-Za-z0-9._-]+$ ]] ||
  archphene_die "$package is not installed as a debuggable app"
[[ -f "$runtime_directory/ld-linux-x86-64.so.2" ]] ||
  archphene_die "runtime directory has no x86_64 glibc loader"

remote=/data/local/tmp/archphene-16kb-glibc-runtime
target="$data_path/files/archphene-16kb-glibc-runtime"
cleanup() {
  archphene_adb_run shell run-as "$package" rm -rf "$target" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -rf "$remote" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup
archphene_adb_run shell mkdir -p "$remote"
runtime_files=0
for file in "$runtime_directory"/*; do
  [[ -f "$file" ]] || continue
  case "${file##*/}" in
    SHA256SUMS|source-commit.txt) continue ;;
  esac
  archphene_adb_run push "$file" "$remote/${file##*/}" >/dev/null
  runtime_files=$((runtime_files + 1))
done
((runtime_files > 1)) ||
  archphene_die "16 KB glibc runtime is incomplete"
archphene_adb_run push "$probe" "$remote/runtime-probe-dynamic" >/dev/null
archphene_adb_run shell chmod -R 755 "$remote"

archphene_adb_run shell run-as "$package" mkdir -p "$target"
for local_file in "$runtime_directory"/* "$probe"; do
  [[ -f "$local_file" ]] || continue
  name="${local_file##*/}"
  case "$name" in
    SHA256SUMS|source-commit.txt) continue ;;
  esac
  [[ "$local_file" != "$probe" ]] || name=runtime-probe-dynamic
  archphene_adb_run shell run-as "$package" \
    cp "$remote/$name" "$target/$name"
  archphene_adb_run shell run-as "$package" chmod 700 "$target/$name"
done
result="$(
  archphene_adb_run exec-out run-as "$package" \
    "$target/ld-linux-x86-64.so.2" \
    --library-path "$target" \
    "$target/runtime-probe-dynamic" |
    tr -d '\r'
)"
[[ "$result" == "hello from shared glibc closure" ]] ||
  archphene_die "unexpected glibc probe output: $result"

cleanup
trap - EXIT
archphene_note \
  "16 KB x86_64 glibc runtime passed inside $package on $serial with $runtime_files staged files."
