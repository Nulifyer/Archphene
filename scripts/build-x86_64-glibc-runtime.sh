#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

container=archphene-glibc-incremental
source_ref=fdf10644d6ee345c7b5277c3fa009c1bedb92d60
repository_dir=/build/archphene-glibc-src
work_root=/build/archphene-glibc-builds
jobs=8
while (($#)); do
  case "$1" in
    --container) container="${2:?}"; shift 2 ;;
    --source-ref) source_ref="${2:?}"; shift 2 ;;
    --repository-dir) repository_dir="${2:?}"; shift 2 ;;
    --work-root) work_root="${2:?}"; shift 2 ;;
    --jobs) jobs="${2:?}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--container NAME] [--source-ref REF] [--repository-dir PATH] [--work-root PATH] [--jobs N]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_require_command podman
archphene_require_command python3
patch="$ARCHPHENE_ROOT/patches/glibc/0001-android-app-seccomp-compat.patch"
archphene_require_file "$patch"
out="$ARCHPHENE_ROOT/tooling/build/glibc-archphene-runtime-x86_64"
token="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
remote_patch="/tmp/archphene-glibc-$token.patch"
remote_source="$work_root/source-x86-$token"
remote_build="$work_root/obj-x86-$token"
[[ "$(podman inspect "$container" --format '{{.State.Status}}' 2>/dev/null || true)" == running ]] || archphene_die "build container $container is not running"
podman cp "$patch" "$container:$remote_patch"
podman exec "$container" bash -s -- "$repository_dir" "$source_ref" "$remote_source" "$remote_patch" "$remote_build" "$jobs" <<'BUILD'
set -euo pipefail
repository_dir=$1 source_ref=$2 remote_source=$3 remote_patch=$4 remote_build=$5 jobs=$6
git -C "$repository_dir" cat-file -e "$source_ref^{commit}"
git -C "$repository_dir" worktree add --detach "$remote_source" "$source_ref"
git -C "$remote_source" apply "$remote_patch"
mkdir "$remote_build"
cd "$remote_build"
CPPFLAGS=-DARCHPHENE_ANDROID_APP_COMPAT=1 "$remote_source/configure" --prefix=/usr --disable-werror --enable-kernel=5.10
make -j"$jobs" >archphene-build.log 2>&1 || { tail -200 archphene-build.log; exit 1; }
make install DESTDIR="$remote_build/install" >archphene-install.log 2>&1 || { tail -200 archphene-install.log; exit 1; }
git -C "$remote_source" rev-parse HEAD >archphene-source-commit.txt
BUILD
mkdir -p "$out"
files=(ld-linux-x86-64.so.2 libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0 librt.so.1 libresolv.so.2 libutil.so.1 libanl.so.1 libnss_dns.so.2 libnss_files.so.2)
for file in "${files[@]}"; do podman cp "$container:$remote_build/install/lib64/$file" "$out/$file"; done
podman cp "$container:$remote_build/archphene-source-commit.txt" "$out/source-commit.txt"
python3 - "$out" "$source_ref" "${files[@]}" <<'PY'
import hashlib,json,pathlib,sys
out=pathlib.Path(sys.argv[1]); files=[]
for name in sys.argv[3:]:
 p=out/name; files.append({'file':name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
(out/'build-manifest.json').write_text(json.dumps({'source':'https://sourceware.org/git/glibc.git','commit':sys.argv[2],'cppflags':'-DARCHPHENE_ANDROID_APP_COMPAT=1','files':files},indent=2)+'\n')
PY
archphene_note "Patched x86_64 glibc runtime built: $out"
