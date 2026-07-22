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
archphene_probe_signing_environment
image=localhost/archphene-android-native:ndk29-rust1.88
if ! archphene_podman_image_exists "$image"; then
  podman build -f "$ARCHPHENE_ROOT/containers/android-native.Containerfile" -t "$image" "$ARCHPHENE_ROOT"
fi
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace \
  -e "ANDROID_ABI=$android_abi" -e KEYSTORE_PATH -e KEYSTORE_PASSWORD -e KEY_ALIAS -e KEY_PASSWORD \
  "$image" bash scripts/build-camera-capability-probe.sh
apk="$ARCHPHENE_ROOT/prototypes/camera-capability-probe/out-$android_abi/archphene-camera-probe.apk"
archphene_require_file "$apk"
archphene_note "Container-built camera capability probe: $apk"

