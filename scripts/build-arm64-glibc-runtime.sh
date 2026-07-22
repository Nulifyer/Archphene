#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
container=archphene-glibc-incremental
source_ref=8362e8ce10b24068bacc19552c128dd10e082fd9
repository_dir=/build/archphene-glibc-src
work_root=/build/archphene-glibc-builds
jobs=8
while (($#)); do case "$1" in
  --container) container="${2:?}"; shift 2;; --source-ref) source_ref="${2:?}"; shift 2;;
  --repository-dir) repository_dir="${2:?}"; shift 2;; --work-root) work_root="${2:?}"; shift 2;;
  --jobs) jobs="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [options]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_require_command podman; archphene_require_command python3
patch="$ARCHPHENE_ROOT/patches/glibc/0001-android-app-seccomp-compat.patch"; archphene_require_file "$patch"
out="$ARCHPHENE_ROOT/tooling/build/glibc-archphene-runtime-aarch64"
token="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"; remote_patch="/tmp/archphene-glibc-$token.patch"; remote_source="$work_root/source-$token"; remote_build="$work_root/obj-$token"
[[ "$(podman inspect "$container" --format '{{.State.Status}}' 2>/dev/null || true)" == running ]] || archphene_die "build container $container is not running"
podman exec "$container" bash -lc 'command -v git && command -v aarch64-linux-gnu-gcc && command -v make && command -v bison && command -v python3'
podman cp "$patch" "$container:$remote_patch"
podman exec "$container" bash -s -- "$repository_dir" "$source_ref" "$remote_source" "$remote_patch" "$remote_build" "$work_root" "$jobs" <<'BUILD'
set -euo pipefail
repository_dir=$1 source_ref=$2 remote_source=$3 remote_patch=$4 remote_build=$5 work_root=$6 jobs=$7
mkdir -p "$work_root"
if [[ ! -d "$repository_dir/.git" ]]; then git clone --filter=blob:none https://sourceware.org/git/glibc.git "$repository_dir"; fi
git -C "$repository_dir" cat-file -e "$source_ref^{commit}"
git -C "$repository_dir" worktree add --detach "$remote_source" "$source_ref"
git -C "$remote_source" apply "$remote_patch"
mkdir "$remote_build"; cd "$remote_build"
CPPFLAGS=-DARCHPHENE_ANDROID_APP_COMPAT=1 "$remote_source/configure" --prefix=/usr --build=x86_64-pc-linux-gnu --host=aarch64-linux-gnu --disable-werror --enable-kernel=5.10
make -j"$jobs" >archphene-build.log 2>&1 || { tail -200 archphene-build.log; exit 1; }
mkdir archphene-export
cp elf/ld.so archphene-export/ld-linux-aarch64.so.1
cp libc.so archphene-export/libc.so.6
aarch64-linux-gnu-strip --strip-unneeded archphene-export/ld-linux-aarch64.so.1 archphene-export/libc.so.6
git -C "$remote_source" rev-parse HEAD >archphene-source-commit.txt
BUILD
mkdir -p "$out"
files=(ld-linux-aarch64.so.1 libc.so.6)
for file in "${files[@]}"; do podman cp "$container:$remote_build/archphene-export/$file" "$out/$file"; done
podman cp "$container:$remote_build/archphene-source-commit.txt" "$out/source-commit.txt"
readelf_cmd="$(command -v llvm-readelf || command -v readelf || true)"; [[ -n "$readelf_cmd" ]] || archphene_die 'llvm-readelf or readelf is required'
for file in "${files[@]}"; do "$readelf_cmd" -h "$out/$file" | grep -Eq 'Machine:[[:space:]]+AArch64' || archphene_die "exported runtime is not AArch64: $out/$file"; done
python3 - "$out" <<'PY'
import hashlib,json,pathlib,sys
out=pathlib.Path(sys.argv[1]); names=['ld-linux-aarch64.so.1','libc.so.6']; files=[]
for name in names:
 p=out/name; files.append({'file':name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
(out/'build-manifest.json').write_text(json.dumps({'source':'https://sourceware.org/git/glibc.git','commit':(out/'source-commit.txt').read_text().strip(),'cppflags':'-DARCHPHENE_ANDROID_APP_COMPAT=1','files':files},indent=2)+'\n')
PY
archphene_note "Patched AArch64 glibc runtime built and verified: $out"
