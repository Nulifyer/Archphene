#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
android_sdk=; skip_native_build=false
while (($#)); do case "$1" in --android-sdk) android_sdk="${2:?}"; shift 2;; --skip-native-build) skip_native_build=true; shift;; -h|--help) echo "usage: $0 [--android-sdk PATH] [--skip-native-build]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
if [[ -n "$android_sdk" ]]; then export ANDROID_SDK_ROOT="$(cd "$android_sdk" && pwd)"; else export ANDROID_SDK_ROOT="$(archphene_android_sdk)"; fi
if [[ "$skip_native_build" == false ]]; then "$ARCHPHENE_SCRIPTS_DIR/build-native-compositor-podman.sh" --architecture x86_64 --release; "$ARCHPHENE_SCRIPTS_DIR/build-native-compositor-podman.sh" --architecture aarch64 --release; fi
archphene_probe_signing_environment
ARCHPHENE_TEMPLATE_ONLY=true ARCHPHENE_TERMINAL_ONLY=false DEBUGGABLE=false "$ARCHPHENE_SCRIPTS_DIR/build-linux-manager-apk.sh"
out="$ARCHPHENE_ROOT/tooling/build/wrapper-templates/qt/qt-wrapper-template.apk"; archphene_require_file "$out"; archphene_note "Qt wrapper template: $out"
