#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5556; package=org.archpheneos.manager; runtime_directory="$ARCHPHENE_ROOT/tooling/build/glibc-archphene-runtime-x86_64"; probe="$ARCHPHENE_ROOT/tooling/build/16kb-probe/runtime-probe-dynamic"
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --package) package="${2:?}"; shift 2;; --runtime-directory) runtime_directory="${2:?}"; shift 2;; --probe) probe="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--serial SERIAL] [--package NAME] [--runtime-directory PATH] [--probe PATH]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
runtime_directory="$(realpath "$runtime_directory")"; probe="$(realpath "$probe")"; archphene_init_adb "$serial"
page_size="$(archphene_adb_run shell getconf PAGE_SIZE | tr -d '\r\n')"; [[ "$page_size" == 16384 ]] || archphene_die "$serial reports page size $page_size, expected 16384"
data_path="$(archphene_adb_run shell run-as "$package" pwd | tr -d '\r\n')"; [[ "$data_path" == /data/* ]] || archphene_die "$package is not installed as a debuggable app"
remote=/data/local/tmp/archphene-ps16k-runtime; target="$data_path/files/ps16k-runtime"
cleanup() { archphene_adb_run shell run-as "$package" rm -rf "$target" >/dev/null 2>&1 || true; archphene_adb_run shell rm -rf "$remote" >/dev/null 2>&1 || true; }; trap cleanup EXIT
archphene_adb_run shell rm -rf "$remote"; archphene_adb_run shell mkdir -p "$remote"
for file in "$runtime_directory"/*; do [[ -f "$file" ]] || continue; case "$(basename "$file")" in SHA256SUMS|source-commit.txt) continue;; esac; archphene_adb_run push "$file" "$remote/$(basename "$file")" >/dev/null; done
archphene_adb_run push "$probe" "$remote/runtime-probe-dynamic" >/dev/null; archphene_adb_run shell chmod -R 755 "$remote"
archphene_adb_run shell run-as "$package" rm -rf "$target"; archphene_adb_run shell run-as "$package" mkdir -p "$target"
for local_file in "$runtime_directory"/* "$probe"; do [[ -f "$local_file" ]] || continue; name="$(basename "$local_file")"; case "$name" in SHA256SUMS|source-commit.txt) continue;; esac; [[ "$local_file" != "$probe" ]] || name=runtime-probe-dynamic; archphene_adb_run shell run-as "$package" cp "$remote/$name" "$target/$name"; archphene_adb_run shell run-as "$package" chmod 700 "$target/$name"; done
result="$(archphene_adb_run shell run-as "$package" "$target/ld-linux-x86-64.so.2" --library-path "$target" "$target/runtime-probe-dynamic" | tr -d '\r')"
[[ "$result" == 'hello from shared glibc closure' ]] || archphene_die "unexpected glibc probe output: $result"
archphene_note "16 KB x86_64 glibc runtime passed inside $package on $serial."
