#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554; skip_build=false
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --skip-build) skip_build=true; shift;; -h|--help) echo "usage: $0 [--serial SERIAL] [--skip-build]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_init_adb "$serial"; package=org.archpheneos.manager; apk="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/out-linux/archphene.apk"
get_ui() { local ui; for ((i=0;i<20;i++)); do sleep 0.5; ui="$(archphene_capture_ui archphene-wrapper-signing 2>/dev/null || true)"; [[ "$ui" == *'Signed generated APK'* || "$ui" == *'APK signing failed'* ]] && { printf '%s' "$ui"; return; }; done; }
invoke_signing() { local ui input=/data/user/0/$package/files/package-runtime/signing-input.apk; archphene_adb_run push "$apk" /data/local/tmp/archphene-signing-input.apk >/dev/null; archphene_adb_run shell run-as "$package" mkdir -p files/package-runtime; archphene_adb_run shell run-as "$package" cp /data/local/tmp/archphene-signing-input.apk files/package-runtime/signing-input.apk; archphene_adb_run shell am force-stop "$package"; archphene_adb_run shell am start -W -n "$package/.MainActivity" --es archphene_test_sign_apk_file "$input" >/dev/null; ui="$(get_ui)"; [[ "$ui" == *'v2=true v3=true'* ]] || archphene_die "manager did not verify v2/v3 APK"; grep -oE 'Signer [0-9a-f]{64}' <<<"$ui" | head -n1 | cut -d' ' -f2; }
if [[ "$skip_build" == false ]]; then "$ARCHPHENE_SCRIPTS_DIR/build-install-linux-manager-stub.sh" --serial "$serial"; fi
archphene_require_file "$apk"; first="$(invoke_signing)"; [[ -n "$first" ]] || archphene_die "first signer missing"; archphene_adb_run install -r "$apk" >/dev/null; second="$(invoke_signing)"; [[ "$first" == "$second" ]] || archphene_die "wrapper signer changed: $first -> $second"; archphene_note "Persistent Android Keystore APK signer passed: $first"

