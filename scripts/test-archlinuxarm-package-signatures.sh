#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
package_dir=tooling/downloads/arch-curated-kcalc-aarch64/packages
keyring=tooling/downloads/archlinuxarm-aarch64/archlinuxarm.gpg
container=archphene-glibc-incremental
while (($#)); do
  case "$1" in
    --package-dir) package_dir="${2:?}"; shift 2;; --keyring) keyring="${2:?}"; shift 2;; --container) container="${2:?}"; shift 2;;
    -h|--help) echo "usage: $0 [--package-dir PATH] [--keyring PATH] [--container NAME]"; exit 0;; *) archphene_die "unknown argument: $1";;
  esac
done
[[ "$package_dir" == /* ]] || package_dir="$ARCHPHENE_ROOT/$package_dir"
[[ "$keyring" == /* ]] || keyring="$ARCHPHENE_ROOT/$keyring"
archphene_require_directory "$package_dir"
archphene_require_file "$keyring"
fingerprint=68B3537F39A313B3E574D06777193F152BDBE6A6
remote="/tmp/archphene-signatures-$(openssl rand -hex 16)"
podman exec "$container" mkdir -p "$remote/packages" "$remote/gnupg"
podman cp "$keyring" "$container:$remote/archlinuxarm.gpg"
podman cp "$package_dir/." "$container:$remote/packages"
keys="$(podman exec "$container" gpg --show-keys --with-colons "$remote/archlinuxarm.gpg")"
[[ "$keys" == *"fpr:::::::::$fingerprint:"* ]] || archphene_die "keyring lacks Arch Linux ARM fingerprint $fingerprint"
podman exec "$container" gpg --homedir "$remote/gnupg" --batch --import "$remote/archlinuxarm.gpg" >/dev/null
verified=0
shopt -s nullglob
for package in "$package_dir"/*.pkg.tar.*; do
  [[ "$package" != *.sig ]] || continue
  signature="$package.sig"
  archphene_require_file "$signature"
  name="$(basename "$package")"
  status="$(podman exec "$container" gpg --homedir "$remote/gnupg" --batch --status-fd 1 --verify "$remote/packages/$name.sig" "$remote/packages/$name" 2>&1)"
  [[ "$status" == *"VALIDSIG $fingerprint "* ]] || archphene_die "invalid Arch Linux ARM package signature: $name"
  ((verified += 1))
done
archphene_note "Arch Linux ARM signature gate passed for $verified AArch64 packages with $fingerprint."

