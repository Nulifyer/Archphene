#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=; skip_signature=false; skip_install=false
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --skip-signature-gate) skip_signature=true; shift;; --skip-install) skip_install=true; shift;; -h|--help) echo "usage: $0 --serial SERIAL [options]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
[[ -n "$serial" ]] || archphene_die '--serial is required'; archphene_init_adb "$serial"; [[ "$(archphene_adb_run get-state 2>/dev/null || true)" == device ]] || archphene_die "ADB device $serial is not authorized and online"; abis="$(archphene_adb_run shell getprop ro.product.cpu.abilist | tr -d '\r')"; [[ ",$abis," == *,arm64-v8a,* ]] || archphene_die "$serial is not ARM64: $abis"
run() { archphene_note "=== $1 ==="; "$ARCHPHENE_SCRIPTS_DIR/$1.sh" "${@:2}"; }
[[ "$skip_signature" == true ]] || run test-archlinuxarm-package-signatures
if [[ "$skip_install" == false ]]; then
  run build-install-arm64-bridge-probe --serial "$serial"
  run build-install-kcalc-app \
    --descriptor-path prototypes/kcalc-android-app/archphene-app-aarch64.json \
    --android-abi arm64-v8a --serial "$serial"
  run build-install-native-compositor-probe --serial "$serial" --android-abi arm64-v8a
fi
run test-arm64-physical-device --serial "$serial"
run test-kcalc-menu-switch --serial "$serial"
native_probe_args=(--serial "$serial" --android-abi arm64-v8a)
[[ "$skip_install" == false ]] || native_probe_args+=(--skip-install)
run test-native-compositor-probe "${native_probe_args[@]}"
run test-kcalc-calculation --serial "$serial"
run test-kcalc-live-resize --serial "$serial"
run test-kcalc-fd-lifecycle --serial "$serial"
run test-kcalc-physical-freeform-resize --serial "$serial"
archphene_note "ARM64 physical regression passed on $serial ($abis)."
