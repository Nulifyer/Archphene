#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554; skip_document=false; skip_installer=false; provision=false
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --provision) provision=true; shift;; --skip-document-workflow) skip_document=true; shift;; --skip-package-installer) skip_installer=true; shift;; -h|--help) echo "usage: $0 [--serial SERIAL] [--provision] [--skip-document-workflow] [--skip-package-installer]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
archphene_init_adb "$serial"; manager_dump="$(archphene_adb_run shell dumpsys package org.archpheneos.manager)"; manager_debuggable=false; archphene_regex_contains "$manager_dump" '(?m)^\s*flags=\[[^]]*DEBUGGABLE' && manager_debuggable=true
if [[ "$provision" == true ]]; then "$ARCHPHENE_SCRIPTS_DIR/provision-emulator-regression.sh" --serial "$serial"; fi
for required in org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2 org.archphene.linux.p241d399e14343c53b8b766e9126776aa; do archphene_adb_run shell pm path "$required" >/dev/null 2>&1 || archphene_die "required emulator fixture is missing: $required (rerun with --provision)"; done
tests=(test-linux-manager-update test-linux-manager-pull-refresh test-linux-manager-kcalc test-runtime-module-fd-sharing test-linux-manager-catalog test-linux-manager-obtainium-workflow test-linux-manager-repository-search test-linux-manager-version-selector test-kcalc-menu-switch native-compositor test-kcalc-calculation test-kcalc-live-resize)
if [[ "$skip_installer" == false && "$manager_debuggable" == true ]]; then tests+=(test-linux-manager-package-installer); elif [[ "$skip_installer" == false ]]; then archphene_note 'Skipping debug-hook PackageInstaller fixture for non-debuggable manager.'; fi
if [[ "$skip_document" == false ]]; then tests+=(test-mousepad-android-document-workflow test-mousepad-open-dialog-ime test-mousepad-touch-routing test-mousepad-secondary-window test-mousepad-live-theme); fi
for test in "${tests[@]}"; do start=$SECONDS; archphene_note "==> $test"; if [[ "$test" == native-compositor ]]; then "$ARCHPHENE_SCRIPTS_DIR/build-install-native-compositor-probe.sh" --serial "$serial" --android-abi x86_64; "$ARCHPHENE_SCRIPTS_DIR/test-native-compositor-probe.sh" --serial "$serial" --android-abi x86_64 --skip-install; else "$ARCHPHENE_SCRIPTS_DIR/$test.sh" --serial "$serial"; fi; archphene_note "PASS $test ($((SECONDS-start))s)"; done
archphene_note 'Archphene emulator regression suite passed.'
