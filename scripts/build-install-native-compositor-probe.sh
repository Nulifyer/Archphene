#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
android_abi=x86_64
serial=emulator-5554
skip_install=false
while (($#)); do
  case "$1" in
    --android-abi) android_abi="${2:?}"; shift 2;; --serial) serial="${2:?}"; shift 2;; --skip-install) skip_install=true; shift;;
    -h|--help) echo "usage: $0 [--android-abi x86_64|arm64-v8a] [--serial SERIAL] [--skip-install]"; exit 0;; *) archphene_die "unknown argument: $1";;
  esac
done
"$ARCHPHENE_SCRIPTS_DIR/build-native-compositor-probe-podman.sh" --android-abi "$android_abi"
if [[ "$skip_install" == false ]]; then
  "$ARCHPHENE_SCRIPTS_DIR/install-apk.sh" \
    --apk "prototypes/native-compositor-probe/out-$android_abi/archphene-compositor-probe.apk" \
    --serial "$serial" --package org.archphene.compositorprobe
fi

