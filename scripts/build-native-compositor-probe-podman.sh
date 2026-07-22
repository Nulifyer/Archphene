#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

android_abi=x86_64
while (($#)); do
  case "$1" in
    --android-abi) android_abi="${2:?missing value for --android-abi}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--android-abi x86_64|arm64-v8a]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_validate_choice "$android_abi" Android-ABI x86_64 arm64-v8a
architecture=x86_64
[[ "$android_abi" == arm64-v8a ]] && architecture=aarch64
"$ARCHPHENE_SCRIPTS_DIR/build-native-compositor-podman.sh" --architecture "$architecture" --release
archphene_probe_signing_environment
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace \
  -e "ANDROID_ABI=$android_abi" -e KEYSTORE_PATH -e KEYSTORE_PASSWORD -e KEY_ALIAS -e KEY_PASSWORD \
  ghcr.io/cirruslabs/android-sdk:36 bash scripts/build-native-compositor-probe.sh
apk="$ARCHPHENE_ROOT/prototypes/native-compositor-probe/out-$android_abi/archphene-compositor-probe.apk"
archphene_require_file "$apk"
archphene_note "Container-built native compositor probe: $apk"

