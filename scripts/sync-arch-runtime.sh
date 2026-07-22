#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
package=kcalc; arch=x86_64; mirror=https://geo.mirror.pkgbuild.com; refresh=false
while (($#)); do case "$1" in --package) package="${2:?}"; shift 2;; --arch) arch="${2:?}"; shift 2;; --mirror) mirror="${2:?}"; shift 2;; --refresh) refresh=true; shift;; -h|--help) echo "usage: $0 [--package NAME] [--arch ARCH] [--mirror URL] [--refresh]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
args=(--root "$ARCHPHENE_ROOT" --package "$package" --arch "$arch" --mirror "$mirror"); [[ "$refresh" == false ]] || args+=(--refresh); exec python3 "$ARCHPHENE_SCRIPTS_DIR/lib/sync-arch-runtime.py" "${args[@]}"
