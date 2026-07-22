#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
runtime_dir=tooling/build/glibc-archphene-runtime-x86_64
app_lib_dir=prototypes/kcalc-android-app/lib/x86_64
backup_dir=tooling/backups/kcalc-arch-stock-glibc-x86_64
while (($#)); do
  case "$1" in --runtime-dir) runtime_dir="${2:?}"; shift 2;; --app-lib-dir) app_lib_dir="${2:?}"; shift 2;; --backup-dir) backup_dir="${2:?}"; shift 2;;
    -h|--help) echo "usage: $0 [--runtime-dir PATH] [--app-lib-dir PATH] [--backup-dir PATH]"; exit 0;; *) archphene_die "unknown argument: $1";; esac
done
[[ "$runtime_dir" == /* ]] || runtime_dir="$ARCHPHENE_ROOT/$runtime_dir"
[[ "$app_lib_dir" == /* ]] || app_lib_dir="$ARCHPHENE_ROOT/$app_lib_dir"
[[ "$backup_dir" == /* ]] || backup_dir="$ARCHPHENE_ROOT/$backup_dir"
archphene_require_directory "$runtime_dir"; archphene_require_directory "$app_lib_dir"; mkdir -p "$backup_dir"
runtime_files=(libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0 librt.so.1 libresolv.so.2 libutil.so.1 libanl.so.1 libnss_dns.so.2 libnss_files.so.2)
loader_targets=(libarchphene_ld.so libld.so.2 ld-linux-x86-64.so.2)
loader="$runtime_dir/ld-linux-x86-64.so.2"; archphene_require_file "$loader"
for name in "${loader_targets[@]}" "${runtime_files[@]}"; do
  [[ ! -f "$app_lib_dir/$name" || -f "$backup_dir/$name" ]] || cp "$app_lib_dir/$name" "$backup_dir/$name"
done
for name in "${loader_targets[@]}"; do cp "$loader" "$app_lib_dir/$name"; done
for name in "${runtime_files[@]}"; do archphene_require_file "$runtime_dir/$name"; cp "$runtime_dir/$name" "$app_lib_dir/$name"; done
manifest="$runtime_dir/runtime-manifest.tsv"
: > "$manifest"
printf '%-28s %12s %s\n' NAME BYTES SHA256
for name in "${loader_targets[@]}" "${runtime_files[@]}"; do
  bytes="$(stat -c %s "$app_lib_dir/$name")"; hash="$(archphene_sha256_file "$app_lib_dir/$name")"
  printf '%s\t%s\t%s\n' "$name" "$bytes" "$hash" >> "$manifest"
  printf '%-28s %12s %s\n' "$name" "$bytes" "$hash"
done
archphene_note "Runtime manifest: $manifest"
archphene_note "Stock backup: $backup_dir"

