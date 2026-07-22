#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

skip_runtime=false
release_build=false
skip_gpu=false
skip_desktop=false
skip_audio=false
skip_pipewire=false
artifact_abi=universal
version_code=10000
version_name=1.0.0
jobs=2
while (($#)); do
  case "$1" in
    --skip-runtime) skip_runtime=true; shift ;;
    --release-build) release_build=true; shift ;;
    --skip-gpu-helper-build) skip_gpu=true; shift ;;
    --skip-desktop-integration-build) skip_desktop=true; shift ;;
    --skip-audio-build) skip_audio=true; shift ;;
    --skip-pipewire-build) skip_pipewire=true; shift ;;
    --artifact-abi) artifact_abi="${2:?missing value for --artifact-abi}"; shift 2 ;;
    --version-code) version_code="${2:?missing value for --version-code}"; shift 2 ;;
    --version-name) version_name="${2:?missing value for --version-name}"; shift 2 ;;
    --jobs) jobs="${2:?missing value for --jobs}"; shift 2 ;;
    -h|--help)
      cat <<'EOF'
usage: scripts/build-manager-podman.sh [options]
  --skip-runtime
  --release-build
  --skip-gpu-helper-build
  --skip-desktop-integration-build
  --skip-audio-build
  --skip-pipewire-build
  --artifact-abi universal|x86_64|arm64-v8a
  --version-code NUMBER
  --version-name VERSION
  --jobs 1..16
EOF
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_validate_choice "$artifact_abi" artifact-ABI universal x86_64 arm64-v8a
[[ "$version_code" =~ ^[0-9]+$ ]] || archphene_die "version code must be an integer"
[[ "$jobs" =~ ^[0-9]+$ ]] && ((jobs >= 1 && jobs <= 16)) || archphene_die "jobs must be from 1 to 16"
archphene_require_command podman
archphene_require_command flock

mkdir -p "$ARCHPHENE_ROOT/tooling/build"
exec 9>"$ARCHPHENE_ROOT/tooling/build/manager-build.lock"
flock -w 900 9 || archphene_die "timed out waiting for another manager build"
podman info --format '{{.Host.OS}}/{{.Host.Arch}}'

"$ARCHPHENE_SCRIPTS_DIR/build-android-capability-client-podman.sh"
if [[ "$skip_desktop" == false ]]; then
  "$ARCHPHENE_SCRIPTS_DIR/build-android-dbus-podman.sh" --architecture x86_64
  "$ARCHPHENE_SCRIPTS_DIR/build-android-dbus-podman.sh" --architecture aarch64
else
  for architecture in x86_64 aarch64; do
    for helper in dbus-daemon portal-service portal-probe xdg-open; do
      archphene_require_file "$ARCHPHENE_ROOT/tooling/build/android-dbus/$architecture/$helper"
    done
  done
fi

if [[ "$skip_audio" == false ]]; then
  "$ARCHPHENE_SCRIPTS_DIR/build-android-pulse-podman.sh" --architecture x86_64
  "$ARCHPHENE_SCRIPTS_DIR/build-android-pulse-podman.sh" --architecture aarch64
else
  archphene_require_file "$ARCHPHENE_ROOT/tooling/build/android-pulse/x86_64/out/SHA256SUMS"
  archphene_require_file "$ARCHPHENE_ROOT/tooling/build/android-pulse/aarch64/out/SHA256SUMS"
fi

if [[ "$skip_pipewire" == false ]]; then
  "$ARCHPHENE_SCRIPTS_DIR/build-pipewire-camera-runtime-podman.sh" --architecture x86_64
  "$ARCHPHENE_SCRIPTS_DIR/build-pipewire-camera-runtime-podman.sh" --architecture aarch64
else
  archphene_require_file "$ARCHPHENE_ROOT/tooling/build/pipewire-camera/x86_64/SHA256SUMS"
  archphene_require_file "$ARCHPHENE_ROOT/tooling/build/pipewire-camera/aarch64/SHA256SUMS"
fi

for architecture in x86_64 aarch64; do
  "$ARCHPHENE_SCRIPTS_DIR/build-native-compositor-podman.sh" --architecture "$architecture" --release
  "$ARCHPHENE_SCRIPTS_DIR/build-terminal-pty-podman.sh" --architecture "$architecture"
done

if [[ "$skip_gpu" == false ]]; then
  "$ARCHPHENE_SCRIPTS_DIR/build-android-gpu-helper-podman.sh" --architecture x86_64
  "$ARCHPHENE_SCRIPTS_DIR/build-android-gpu-helper-podman.sh" --architecture aarch64
else
  archphene_require_file "$ARCHPHENE_ROOT/tooling/build/android-gpu/x86_64/virgl_test_server_android"
  archphene_require_file "$ARCHPHENE_ROOT/tooling/build/android-gpu/aarch64/virgl_test_server_android"
fi

if [[ "$skip_runtime" == false ]]; then
  (cd "$ARCHPHENE_ROOT" && CONTAINER_CLI=podman SKIP_CHOWN=1 JOBS="$jobs" bash scripts/build-ci-package-runtime.sh)
  (cd "$ARCHPHENE_ROOT" && CONTAINER_CLI=podman SKIP_CHOWN=1 JOBS="$jobs" bash scripts/build-ci-package-runtime-arm64.sh)
fi

if [[ "$release_build" == true ]]; then
  credentials="$ARCHPHENE_ROOT/tooling/signing/archphene-release-credentials.json"
  keystore="$ARCHPHENE_ROOT/tooling/signing/archphene-release.keystore"
  archphene_require_file "$credentials"
  archphene_require_file "$keystore"
  archphene_require_command jq
  export KEYSTORE_PATH=/workspace/tooling/signing/archphene-release.keystore
  export KEYSTORE_PASSWORD="$(jq -er .storePassword "$credentials")"
  export KEY_ALIAS="$(jq -er .keyAlias "$credentials")"
  export KEY_PASSWORD="$(jq -er .keyPassword "$credentials")"
  debuggable=false
else
  archphene_ensure_debug_keystore >/dev/null
  export KEYSTORE_PATH=/workspace/tooling/signing/archpheneos-manager-debug.keystore
  export KEYSTORE_PASSWORD=android KEY_ALIAS=androiddebugkey KEY_PASSWORD=android
  debuggable=true
fi

podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace \
  -e "VERSION_CODE=$version_code" -e "VERSION_NAME=$version_name" \
  -e "DEBUGGABLE=$debuggable" -e "ARCHPHENE_ABI=$artifact_abi" \
  -e KEYSTORE_PATH -e KEYSTORE_PASSWORD -e KEY_ALIAS -e KEY_PASSWORD \
  ghcr.io/cirruslabs/android-sdk:36 bash scripts/build-linux-manager-apk.sh

apk="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/out-linux/archphene.apk"
archphene_require_file "$apk"
archphene_note "Container-built APK: $apk"

