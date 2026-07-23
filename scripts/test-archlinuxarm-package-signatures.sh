#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

stage="$ARCHPHENE_ROOT/tooling/build/ci-package-runtime-arm64"
cache_volume=archphene-arm-package-cache
image=docker.io/library/archlinux:base
while (($#)); do
  case "$1" in
    --stage) stage="${2:?missing value for --stage}"; shift 2 ;;
    --cache-volume) cache_volume="${2:?missing value for --cache-volume}"; shift 2 ;;
    --image) image="${2:?missing value for --image}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--stage PATH] [--cache-volume NAME] [--image IMAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_require_command podman
[[ "$cache_volume" =~ ^[A-Za-z0-9_.-]{1,128}$ ]] \
  || archphene_die "unsafe Podman cache-volume name: $cache_volume"
stage="$(realpath "$stage")"
versions="$stage/tooling/downloads/arch-runtime-pacman-aarch64/package-versions.tsv"
keyring="$stage/tooling/downloads/arch-runtime-archlinuxarm-keyring-aarch64/runtime-root/usr/share/pacman/keyrings/archlinuxarm.gpg"
fingerprint_file="$stage/package-signing-fingerprint.txt"
checksums="$stage/SHA256SUMS"
for file in "$versions" "$keyring" "$fingerprint_file" "$checksums"; do
  archphene_require_file "$file"
done

fingerprint="$(tr -d '\r\n' <"$fingerprint_file")"
[[ "$fingerprint" =~ ^[0-9A-F]{40}$ ]] \
  || archphene_die "invalid package-signing fingerprint: $fingerprint"
[[ "$fingerprint" == 68B3537F39A313B3E574D06777193F152BDBE6A6 ]] \
  || archphene_die "unexpected Arch Linux ARM build fingerprint: $fingerprint"
(cd "$stage" && sha256sum -c SHA256SUMS >/dev/null)
podman volume exists "$cache_volume" \
  || archphene_die \
    "Podman volume $cache_volume is missing; run scripts/build-ci-package-runtime-arm64.sh first"

verified="$(
  podman run --rm -i \
    -e "EXPECTED_FINGERPRINT=$fingerprint" \
    -v "$stage:/stage:ro" \
    -v "$cache_volume:/package-cache:ro" \
    "$image" bash -s <<'CONTAINER'
set -euo pipefail

versions=/stage/tooling/downloads/arch-runtime-pacman-aarch64/package-versions.tsv
keyring=/stage/tooling/downloads/arch-runtime-archlinuxarm-keyring-aarch64/runtime-root/usr/share/pacman/keyrings/archlinuxarm.gpg
gnupg="$(mktemp -d)"
trap 'rm -rf "$gnupg"' EXIT
chmod 700 "$gnupg"

keys="$(gpg --homedir "$gnupg" --batch --show-keys --with-colons "$keyring")"
[[ "$keys" == *"fpr:::::::::$EXPECTED_FINGERPRINT:"* ]] || {
  echo "staged keyring lacks $EXPECTED_FINGERPRINT" >&2
  exit 1
}
gpg --homedir "$gnupg" --batch --import "$keyring" >/dev/null 2>&1

verified=0
while IFS='|' read -r name version repository url filename; do
  [[ -n "$name" && -n "$version" ]] || {
    echo "invalid empty package manifest row" >&2
    exit 1
  }
  [[ "$repository" =~ ^(core|extra)$ ]] || {
    echo "invalid repository for $name: $repository" >&2
    exit 1
  }
  [[ "$url" == "https://ca.us.mirror.archlinuxarm.org/aarch64/$repository/$filename" ]] || {
    echo "unbounded package URL for $name: $url" >&2
    exit 1
  }
  [[ "$filename" =~ ^[A-Za-z0-9@._+:~-]+-(aarch64|any)\.pkg\.tar\.(xz|zst)$ ]] || {
    echo "unsafe package filename for $name: $filename" >&2
    exit 1
  }
  package="/package-cache/$filename"
  signature="$package.sig"
  [[ -s "$package" && -s "$signature" ]] || {
    echo "cache lacks package/signature pair: $filename" >&2
    exit 1
  }
  status="$(
    gpg --homedir "$gnupg" --batch --status-fd 1 \
      --verify "$signature" "$package" 2>&1
  )"
  signer="$(
    sed -n 's/^\[GNUPG:\] VALIDSIG \([0-9A-Fa-f]\{40\}\) .*/\1/p' \
      <<<"$status" | tr '[:lower:]' '[:upper:]'
  )"
  [[ "$signer" == "$EXPECTED_FINGERPRINT" ]] || {
    echo "unexpected signer for $filename: ${signer:-missing}" >&2
    exit 1
  }
  ((verified += 1))
done <"$versions"

((verified > 0)) || {
  echo "package manifest verified zero archives" >&2
  exit 1
}
manifest_rows="$(grep -cve '^[[:space:]]*$' "$versions")"
((verified == manifest_rows)) || {
  echo "verified $verified of $manifest_rows manifest rows" >&2
  exit 1
}
printf '%s\n' "$verified"
CONTAINER
)"

[[ "$verified" =~ ^[1-9][0-9]*$ ]] \
  || archphene_die "container returned an invalid verification count: $verified"
archphene_note \
  "Arch Linux ARM signature gate passed for all $verified staged AArch64 package archives with $fingerprint."
