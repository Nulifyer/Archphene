#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command sha256sum

generated="$ARCHPHENE_ROOT/android/app/build/generated/cameraJniLibs"
staging="$(mktemp -d "$ARCHPHENE_ROOT/tooling/build/camera-jni-stage.XXXXXX")"
trap 'rm -rf "$staging"' EXIT

payload=(
  libpipewire-0.3.so.0:libarchphene_pipewire_client.so
  archphene-pipewire:libarchphene_pipewire_daemon.so
  archphene-pipewire-camera:libarchphene_pipewire_camera.so
  archphene-pipewire-policy:libarchphene_pipewire_policy.so
  archphene-runtime-supervisor:libarchphene_pipewire_supervisor.so
  pipewire-0.3/libpipewire-module-protocol-native.so:libarchphene_pw_module_protocol_native.so
  pipewire-0.3/libpipewire-module-access.so:libarchphene_pw_module_access.so
  pipewire-0.3/libpipewire-module-metadata.so:libarchphene_pw_module_metadata.so
  pipewire-0.3/libpipewire-module-client-node.so:libarchphene_pw_module_client_node.so
  pipewire-0.3/libpipewire-module-adapter.so:libarchphene_pw_module_adapter.so
  pipewire-0.3/libpipewire-module-link-factory.so:libarchphene_pw_module_link_factory.so
  spa-0.2/support/libspa-support.so:libarchphene_spa_support.so
  spa-0.2/videoconvert/libspa-videoconvert.so:libarchphene_spa_videoconvert.so
)

stage_architecture() {
  local architecture="$1" android_abi="$2"
  local source="$ARCHPHENE_ROOT/tooling/build/pipewire-camera/$architecture"
  archphene_require_file "$source/SHA256SUMS"
  (
    cd "$source"
    sha256sum --check --quiet SHA256SUMS
  ) || archphene_die "verified PipeWire camera payload changed: $architecture"
  mkdir -p "$staging/$android_abi"
  local mapping source_name destination_name
  for mapping in "${payload[@]}"; do
    source_name="${mapping%%:*}"
    destination_name="${mapping#*:}"
    archphene_require_file "$source/$source_name"
    cp "$source/$source_name" "$staging/$android_abi/$destination_name"
  done
}

stage_architecture x86_64 x86_64
stage_architecture aarch64 arm64-v8a
rm -rf "$generated"
mv "$staging" "$generated"
trap - EXIT
printf 'Staged manager-owned PipeWire camera payload: %s\n' "$generated"
