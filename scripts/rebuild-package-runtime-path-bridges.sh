#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command podman
archphene_require_command readelf

x86_image=docker.io/library/archlinux:base-devel
arm_image=localhost/archphene-arm-runtime-builder:latest
archphene_podman_image_exists "$x86_image" ||
  archphene_die "missing existing build image: $x86_image"
archphene_podman_image_exists "$arm_image" ||
  archphene_die "missing existing build image: $arm_image"

x86_artifact="$ARCHPHENE_ROOT/tooling/build/ci-package-runtime"
arm_artifact="$ARCHPHENE_ROOT/tooling/build/ci-package-runtime-arm64"
for artifact in "$x86_artifact" "$arm_artifact"; do
  archphene_require_file "$artifact/SHA256SUMS"
  (
    cd "$artifact"
    sha256sum --check --quiet SHA256SUMS
  ) || archphene_die "package-runtime artifact changed before bridge refresh: $artifact"
done

work="$(archphene_mktemp_dir package-runtime-bridge-refresh)"
cleanup() {
  rm -rf -- "$work"
}
trap cleanup EXIT
source_root="$ARCHPHENE_ROOT/native/archphene-glibc-path-bridge"

podman run --rm --network=none \
  -v "$source_root:/source:ro" \
  -v "$work:/out" \
  "$x86_image" \
  gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
    -o /out/libarchphene_path_bridge-x86_64.so /source/path_bridge.c -ldl

podman run --rm --network=none \
  -v "$source_root:/source:ro" \
  -v "$work:/out" \
  "$arm_image" \
  aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
    -Wl,--version-script=/source/arm64.map \
    -o /out/libarchphene_path_bridge-aarch64.so /source/path_bridge.c -ldl

[[ "$(readelf -h "$work/libarchphene_path_bridge-x86_64.so" |
  sed -n 's/.*Machine:[[:space:]]*//p')" == "Advanced Micro Devices X86-64" ]] ||
  archphene_die "refreshed x86_64 bridge has the wrong machine"
[[ "$(readelf -h "$work/libarchphene_path_bridge-aarch64.so" |
  sed -n 's/.*Machine:[[:space:]]*//p')" == "AArch64" ]] ||
  archphene_die "refreshed AArch64 bridge has the wrong machine"
readelf -Ws "$work/libarchphene_path_bridge-x86_64.so" >"$work/x86-symbols.txt"
readelf -Ws "$work/libarchphene_path_bridge-aarch64.so" >"$work/arm-symbols.txt"
awk '$4 == "FUNC" && $5 == "GLOBAL" && $7 != "UND" {
       sub(/@.*/, "", $8)
       print $8
     }' "$work/x86-symbols.txt" |
  sort -u >"$work/x86-global-functions.txt"
awk '$4 == "FUNC" && $5 == "GLOBAL" && $7 != "UND" {
       sub(/@.*/, "", $8)
       print $8
     }' "$work/arm-symbols.txt" |
  sort -u >"$work/arm-global-functions.txt"
missing_arm_functions="$(
  comm -23 "$work/x86-global-functions.txt" "$work/arm-global-functions.txt"
)"
[[ -z "$missing_arm_functions" ]] ||
  archphene_die \
    "refreshed AArch64 bridge omits exported wrappers: $missing_arm_functions"
for symbol in bind chroot connect execve fchmodat fstat fstatat getcwd \
    getpwnam_r getpwuid_r \
    linkat mkdir msgctl msgget msgrcv msgsnd posix_spawn posix_spawnp renameat \
    readdir readdir64 semctl semget semop semtimedop setfsgid setfsuid stat statx symlinkat \
    unlinkat; do
  grep -Eq " $symbol$" "$work/x86-symbols.txt" ||
    archphene_die "refreshed x86_64 bridge is missing $symbol"
done
for symbol in bind chroot connect execve fchmodat getcwd getpwnam_r \
    getpwuid_r linkat mkdir msgctl msgget msgrcv msgsnd posix_spawn \
    posix_spawnp readdir readdir64 renameat semctl semget semop semtimedop setfsgid setfsuid \
    symlinkat unlinkat; do
  grep -Eq "$symbol@@GLIBC_2\\.17" "$work/arm-symbols.txt" ||
    archphene_die "refreshed AArch64 bridge is missing $symbol@GLIBC_2.17"
done
grep -Eq 'statx@@GLIBC_2\.28' "$work/arm-symbols.txt" ||
  archphene_die "refreshed AArch64 bridge is missing statx@GLIBC_2.28"
for symbol in fstat fstatat stat; do
  grep -Eq "$symbol@@GLIBC_2\\.33" "$work/arm-symbols.txt" ||
    archphene_die "refreshed AArch64 bridge is missing $symbol@GLIBC_2.33"
done

install -m755 "$work/libarchphene_path_bridge-x86_64.so" \
  "$x86_artifact/tooling/build/archphene-path-bridge-x86_64/libarchphene_path_bridge.so"
install -m755 "$work/libarchphene_path_bridge-aarch64.so" \
  "$arm_artifact/tooling/build/archphene-path-bridge-aarch64/libarchphene_path_bridge.so"

for artifact in "$x86_artifact" "$arm_artifact"; do
  sums="$artifact/SHA256SUMS.next"
  (
    cd "$artifact"
    find . -type f ! -name SHA256SUMS ! -name SHA256SUMS.next -print0 |
      sort -z |
      xargs -0 sha256sum
  ) >"$sums"
  mv "$sums" "$artifact/SHA256SUMS"
  (
    cd "$artifact"
    sha256sum --check --quiet SHA256SUMS
  ) || archphene_die "refreshed package-runtime artifact failed its seal: $artifact"
done

archphene_note "Refreshed sealed x86_64 and AArch64 package-runtime path bridges"
