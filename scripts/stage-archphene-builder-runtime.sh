#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command readelf
archphene_require_command sha256sum

generated="$ARCHPHENE_ROOT/android/builder/build/generated/builderRuntime"
staging="$(mktemp -d "$ARCHPHENE_ROOT/tooling/build/builder-runtime-stage.XXXXXX")"
trap 'rm -rf "$staging"' EXIT
mkdir -p "$staging/jniLibs" "$staging/assets"

stage_architecture() {
  local architecture="$1" android_abi="$2" machine="$3"
  local artifact glibc_root path_bridge loader native_dir manifest
  case "$architecture" in
    x86_64)
      artifact="$ARCHPHENE_ROOT/tooling/build/ci-package-runtime"
      glibc_root="$artifact/tooling/build/glibc-archphene-runtime-x86_64"
      path_bridge="$artifact/tooling/build/archphene-path-bridge-x86_64/libarchphene_path_bridge.so"
      loader="$glibc_root/ld-linux-x86-64.so.2"
      ;;
    aarch64)
      artifact="$ARCHPHENE_ROOT/tooling/build/ci-package-runtime-arm64"
      glibc_root="$artifact/tooling/build/glibc-archphene-runtime-aarch64"
      path_bridge="$artifact/tooling/build/archphene-path-bridge-aarch64/libarchphene_path_bridge.so"
      loader="$glibc_root/ld-linux-aarch64.so.1"
      ;;
    *) archphene_die "unsupported Builder-runtime architecture: $architecture" ;;
  esac

  archphene_require_file "$artifact/SHA256SUMS"
  (
    cd "$artifact"
    sha256sum --check --quiet SHA256SUMS
  ) || archphene_die "verified package-runtime artifact changed: $architecture"

  native_dir="$staging/jniLibs/$android_abi"
  manifest="$staging/assets/builder-runtime-$architecture.tsv"
  mkdir -p "$native_dir"
  printf '# org.archphene.builder-runtime.v1\n' >"$manifest"

  declare -A sources=()
  declare -A roles=()
  sources["@loader"]="$loader"
  roles["@loader"]=loader
  sources["libarchphene_path_bridge.so"]="$path_bridge"
  roles["libarchphene_path_bridge.so"]=library
  local source_file logical
  while IFS= read -r source_file; do
    logical="$(basename "$source_file")"
    sources["$logical"]="$source_file"
    roles["$logical"]=library
  done < <(find "$glibc_root" -maxdepth 1 -type f -name '*.so.*' | sort)

  declare -A packaged_hashes=()
  local identity packaged size actual_machine
  while IFS= read -r logical; do
    source_file="${sources[$logical]}"
    archphene_require_file "$source_file"
    actual_machine="$(readelf -h "$source_file" |
      sed -n 's/.*Machine:[[:space:]]*//p')"
    [[ "$actual_machine" == "$machine" ]] ||
      archphene_die "wrong Builder-runtime ELF machine for $logical: $actual_machine"
    identity="$(sha256sum "$source_file" | cut -d ' ' -f1)"
    packaged="libarchphene_builder_${identity:0:24}.so"
    if [[ -v "packaged_hashes[$packaged]" ]]; then
      [[ "${packaged_hashes[$packaged]}" == "$identity" ]] ||
        archphene_die "Builder-runtime hash-prefix collision: $packaged"
    else
      install -Dm755 "$source_file" "$native_dir/$packaged"
      packaged_hashes["$packaged"]="$identity"
    fi
    size="$(stat -c %s "$source_file")"
    printf '%s\t%s\t%s\t%s\t%s\n' \
      "${roles[$logical]}" "$logical" "$packaged" "$size" "$identity" >>"$manifest"
  done < <(printf '%s\n' "${!sources[@]}" | sort)

  local count
  count="$(grep -cv '^#' "$manifest")"
  ((count >= 12 && count <= 32)) ||
    archphene_die "unexpected $architecture Builder-runtime entry count: $count"
}

stage_architecture x86_64 x86_64 'Advanced Micro Devices X86-64'
stage_architecture aarch64 arm64-v8a AArch64

rm -rf "$generated"
mv "$staging" "$generated"
trap - EXIT
archphene_note "Verified Builder runtime: $generated"
