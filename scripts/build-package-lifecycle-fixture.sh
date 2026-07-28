#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

output_directory="tooling/build/package-lifecycle-fixture"
while (($#)); do
  case "$1" in
    --output-directory)
      output_directory="${2:?missing output directory}"
      shift 2
      ;;
    -h | --help)
      echo "usage: $0 [--output-directory PATH]"
      exit 0
      ;;
    *)
      archphene_die "unknown argument: $1"
      ;;
  esac
done

archphene_require_command podman
archphene_require_command bsdtar
fixture="$ARCHPHENE_ROOT/tests/fixtures/package-lifecycle"
output="$ARCHPHENE_ROOT/$output_directory"
if [[ "$output_directory" == /* ]]; then
  output="$output_directory"
fi
mkdir -p "$output"

for version in 1.0 2.0; do
  podman run --rm --network=none \
    -v "$fixture:/input:ro" \
    -v "$output:/output" \
    docker.io/library/archlinux:base-devel \
    bash -euo pipefail -c '
      version="$1"
      useradd --create-home builder
      cp -a /input /tmp/package
      sed -i "s/^pkgver=.*/pkgver=$version/" /tmp/package/PKGBUILD
      chown -R builder:builder /tmp/package
      runuser -u builder -- bash -euo pipefail -c \
        "cd /tmp/package && makepkg --nodeps --noconfirm"
      cp "/tmp/package/archphene-lifecycle-fixture-$version-1-any.pkg.tar.zst" /output/
    ' bash "$version"
done

for version in 1.0 2.0; do
  archive="$output/archphene-lifecycle-fixture-$version-1-any.pkg.tar.zst"
  archphene_require_file "$archive"
  bsdtar -tf "$archive" | grep -Fxq .INSTALL ||
    archphene_die "fixture $version omitted .INSTALL"
done

archphene_note "Built package lifecycle fixtures in $output"
