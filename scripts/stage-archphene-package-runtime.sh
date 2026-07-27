#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command readelf
archphene_require_command sha256sum

(
  cd "$ARCHPHENE_ROOT/prebuilt/gtk3-compat"
  sha256sum --check --quiet SHA256SUMS
) || archphene_die "verified GTK compatibility payload changed"

generated="$ARCHPHENE_ROOT/android/app/build/generated/packageRuntime"
staging="$(mktemp -d "$ARCHPHENE_ROOT/tooling/build/package-runtime-stage.XXXXXX")"
trap 'rm -rf "$staging"' EXIT
mkdir -p "$staging/jniLibs" "$staging/assets"

stage_architecture() {
  local architecture="$1" android_abi="$2" machine="$3"
  local artifact runtime_root resolved glibc_root keyring ownertrust path_bridge native_dir manifest
  case "$architecture" in
    x86_64)
      artifact="$ARCHPHENE_ROOT/tooling/build/ci-package-runtime"
      runtime_root="$artifact/tooling/downloads/arch-runtime-pacman-x86_64/runtime-root"
      resolved="$artifact/tooling/downloads/arch-runtime-pacman-x86_64/elf-needed-resolved.tsv"
      glibc_root="$artifact/tooling/build/glibc-archphene-runtime-x86_64"
      path_bridge="$artifact/tooling/build/archphene-path-bridge-x86_64/libarchphene_path_bridge.so"
      keyring="$artifact/tooling/downloads/arch-runtime-archlinux-keyring-x86_64/runtime-root/usr/share/pacman/keyrings/archlinux.gpg"
      ownertrust="$artifact/tooling/downloads/arch-runtime-archlinux-keyring-x86_64/runtime-root/usr/share/pacman/keyrings/archlinux-trusted"
      ;;
    aarch64)
      artifact="$ARCHPHENE_ROOT/tooling/build/ci-package-runtime-arm64"
      runtime_root="$artifact/tooling/downloads/arch-runtime-pacman-aarch64/runtime-root"
      resolved="$artifact/tooling/downloads/arch-runtime-pacman-aarch64/elf-needed-resolved.tsv"
      glibc_root="$artifact/tooling/build/glibc-archphene-runtime-aarch64"
      path_bridge="$artifact/tooling/build/archphene-path-bridge-aarch64/libarchphene_path_bridge.so"
      keyring="$artifact/tooling/downloads/arch-runtime-archlinuxarm-keyring-aarch64/runtime-root/usr/share/pacman/keyrings/archlinuxarm.gpg"
      ownertrust="$artifact/tooling/downloads/arch-runtime-archlinuxarm-keyring-aarch64/runtime-root/usr/share/pacman/keyrings/archlinuxarm-trusted"
      ;;
    *) archphene_die "unsupported package-runtime architecture: $architecture" ;;
  esac

  archphene_require_file "$artifact/SHA256SUMS"
  archphene_require_file "$resolved"
  (
    cd "$artifact"
    sha256sum --check --quiet SHA256SUMS
  ) || archphene_die "verified package-runtime artifact changed: $architecture"

  local direct_ownertrust="$staging/ownertrust-$architecture"
  local fingerprint trust_value trailing trust_count=0
  : >"$direct_ownertrust"
  while IFS=: read -r fingerprint trust_value trailing; do
    [[ "$fingerprint" =~ ^[0-9A-F]{40}$ && "$trust_value" == 4 && -z "$trailing" ]] ||
      archphene_die "invalid official owner-trust anchor: $architecture"
    printf '%s:6:\n' "$fingerprint" >>"$direct_ownertrust"
    trust_count=$((trust_count + 1))
  done <"$ownertrust"
  ((trust_count >= 1 && trust_count <= 16)) ||
    archphene_die "unexpected owner-trust anchor count: $architecture $trust_count"
  ownertrust="$direct_ownertrust"

  native_dir="$staging/jniLibs/$android_abi"
  manifest="$staging/assets/package-runtime-$architecture.tsv"
  mkdir -p "$native_dir"
  printf '# org.archphene.package-runtime.v1\n' >"$manifest"

  declare -A sources=()
  declare -A roles=()
  local tool logical relative source_file
  for tool in pacman bsdtar gpg gpgv gpgconf; do
    sources["@$tool"]="$runtime_root/usr/bin/$tool"
    roles["@$tool"]=tool
  done
  if [[ "$architecture" == x86_64 ]]; then
    sources["@loader"]="$glibc_root/ld-linux-x86-64.so.2"
  else
    sources["@loader"]="$glibc_root/ld-linux-aarch64.so.1"
  fi
  roles["@loader"]=loader
  sources["libarchphene_path_bridge.so"]="$path_bridge"
  roles["libarchphene_path_bridge.so"]=library
  sources["@keyring"]="$keyring"
  roles["@keyring"]=keyring
  sources["@ownertrust"]="$ownertrust"
  roles["@ownertrust"]=ownertrust

  while IFS=$'\t' read -r logical relative; do
    [[ -n "$logical" && -n "$relative" ]] ||
      archphene_die "invalid dependency entry in $resolved"
    source_file="$runtime_root/$relative"
    [[ -f "$glibc_root/$logical" ]] && source_file="$glibc_root/$logical"
    sources["$logical"]="$source_file"
    roles["$logical"]=library
  done <"$resolved"
  while IFS= read -r source_file; do
    logical="$(basename "$source_file")"
    sources["$logical"]="$source_file"
    roles["$logical"]=library
  done < <(find "$glibc_root" -maxdepth 1 -type f -name '*.so.*' | sort)

  local gtk_compat="$ARCHPHENE_ROOT/prebuilt/gtk3-compat/$architecture"
  archphene_require_file "$gtk_compat/libarchphene_gtk3_pixbuf.so"
  archphene_require_file "$gtk_compat/libarchphene_gtk3_rsvg.so"
  archphene_require_file "$gtk_compat/libarchphene_gtk3_pixbufloader_svg.so"
  sources["libgdk_pixbuf-2.0.so.0"]="$gtk_compat/libarchphene_gtk3_pixbuf.so"
  roles["libgdk_pixbuf-2.0.so.0"]=library
  sources["librsvg-2.so.2"]="$gtk_compat/libarchphene_gtk3_rsvg.so"
  roles["librsvg-2.so.2"]=library
  sources["libarchphene_pixbufloader_svg.so"]="$gtk_compat/libarchphene_gtk3_pixbufloader_svg.so"
  roles["libarchphene_pixbufloader_svg.so"]=library

  declare -A packaged_hashes=()
  local identity packaged size actual_machine
  while IFS= read -r logical; do
    source_file="${sources[$logical]}"
    archphene_require_file "$source_file"
    if [[ "${roles[$logical]}" != keyring && "${roles[$logical]}" != ownertrust ]]; then
      actual_machine="$(readelf -h "$source_file" |
        sed -n 's/.*Machine:[[:space:]]*//p')"
      [[ "$actual_machine" == "$machine" ]] ||
        archphene_die "wrong ELF machine for $logical: $actual_machine"
    fi
    identity="$(sha256sum "$source_file" | cut -d ' ' -f1)"
    packaged="libarchphene_pkg_${identity:0:24}.so"
    if [[ -v "packaged_hashes[$packaged]" ]]; then
      [[ "${packaged_hashes[$packaged]}" == "$identity" ]] ||
        archphene_die "package-runtime hash-prefix collision: $packaged"
    else
      if [[ "${roles[$logical]}" == keyring || "${roles[$logical]}" == ownertrust ]]; then
        install -Dm644 "$source_file" "$native_dir/$packaged"
      else
        install -Dm755 "$source_file" "$native_dir/$packaged"
      fi
      packaged_hashes["$packaged"]="$identity"
    fi
    size="$(stat -c %s "$source_file")"
    printf '%s\t%s\t%s\t%s\n' \
      "${roles[$logical]}" "$logical" "$packaged" "$size" >>"$manifest"
  done < <(printf '%s\n' "${!sources[@]}" | sort)

  local count
  count="$(grep -cv '^#' "$manifest")"
  ((count >= 16 && count <= 128)) ||
    archphene_die "unexpected $architecture package-runtime entry count: $count"
}

stage_architecture x86_64 x86_64 'Advanced Micro Devices X86-64'
stage_architecture aarch64 arm64-v8a AArch64

rm -rf "$generated"
mv "$staging" "$generated"
trap - EXIT
archphene_note "Verified package runtime: $generated"
