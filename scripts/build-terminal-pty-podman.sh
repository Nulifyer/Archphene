#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

architecture=x86_64
while (($#)); do
  case "$1" in
    --architecture) architecture="${2:?missing value for --architecture}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--architecture x86_64|aarch64]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_validate_choice "$architecture" architecture x86_64 aarch64
archphene_require_command podman

image=localhost/archphene-android-native:ndk29-rust1.88
archphene_podman_image_exists "$image" || archphene_die \
  "Android native image is missing; run scripts/build-native-compositor-podman.sh first"
target=x86_64-linux-android
[[ "$architecture" == aarch64 ]] && target=aarch64-linux-android
output="native/archphene-terminal/out/$architecture/libtermux.so"
command="mkdir -p 'native/archphene-terminal/out/$architecture' && \"\$ANDROID_SDK_ROOT/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/${target}29-clang\" -shared -fPIC -O2 -Wall -Wextra -Werror -Wl,-z,relro,-z,now -o '$output' native/archphene-terminal/terminal_pty.c"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace "$image" bash -lc "$command"
archphene_require_file "$ARCHPHENE_ROOT/$output"
archphene_note "Terminal PTY library: $ARCHPHENE_ROOT/$output"

