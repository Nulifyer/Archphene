#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command sha256sum

generated="$ARCHPHENE_ROOT/android/app/build/generated/audioJniLibs"
staging="$(mktemp -d "$ARCHPHENE_ROOT/tooling/build/audio-jni-stage.XXXXXX")"
trap 'rm -rf "$staging"' EXIT

payload=(
  libarchphene_pulseaudio.so
  libarchphene_pulse_module_aaudio_sink.so
  libarchphene_pulse_module_sles_sink.so
  libarchphene_pulse_module_native_protocol_unix.so
  libarchphene_pulse_probe.so
  libprotocol-native.so
  libpulsecore-17.0.so
  libpulsecommon-17.0.so
  libpulse.so
  libltdl.so
  libdbus-1.so
  libsndfile.so
  libsoxr.so
  libspeexdsp.so
  libiconv.so
  libandroid-execinfo.so
  libFLAC.so
  libvorbis.so
  libvorbisenc.so
  libopus.so
  libogg.so
  libmp3lame.so
)

stage_architecture() {
  local architecture="$1" android_abi="$2"
  local source="$ARCHPHENE_ROOT/tooling/build/android-pulse/$architecture/out"
  archphene_require_file "$source/SHA256SUMS"
  (
    cd "$source"
    sha256sum --check --quiet SHA256SUMS
  ) || archphene_die "verified Android audio payload changed: $architecture"
  mkdir -p "$staging/$android_abi"
  local library
  for library in "${payload[@]}"; do
    archphene_require_file "$source/$library"
    cp "$source/$library" "$staging/$android_abi/$library"
  done
}

stage_architecture x86_64 x86_64
stage_architecture aarch64 arm64-v8a
rm -rf "$generated"
mv "$staging" "$generated"
trap - EXIT
printf 'Staged manager-owned Android audio payload: %s\n' "$generated"
