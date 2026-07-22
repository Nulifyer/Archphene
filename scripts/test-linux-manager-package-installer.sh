#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"
serial=emulator-5554
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; *) archphene_die "unknown argument: $1";; esac; done
archphene_test_init "$serial"; manager=org.archpheneos.manager; kcalc=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2; apk="$ARCHPHENE_ROOT/tooling/build/package-installer-current-kcalc.apk"; mkdir -p "$(dirname "$apk")"
path="$(archphene_adb_run shell pm path "$kcalc" | head -n1 | sed 's/^package://;s/\r$//')"; [[ -n "$path" ]] || archphene_die 'installed generated KCalc unavailable'; archphene_adb_run pull "$path" "$apk" >/dev/null; hash="$(archphene_sha256_file "$apk")"; remote=/data/local/tmp/archpheneos-kcalc-update.apk
cleanup(){ archphene_adb_run shell appops set "$manager" REQUEST_INSTALL_PACKAGES default >/dev/null 2>&1 || true; archphene_adb_run shell rm -f "$remote" >/dev/null 2>&1 || true; archphene_adb_run shell run-as "$manager" rm -f cache/archpheneos-kcalc-update.apk >/dev/null 2>&1 || true; rm -f "$apk"; }; trap cleanup EXIT
archphene_adb_run push "$apk" "$remote" >/dev/null; archphene_adb_run shell run-as "$manager" cp "$remote" cache/archpheneos-kcalc-update.apk; archphene_adb_run shell run-as "$manager" chmod 600 cache/archpheneos-kcalc-update.apk; archphene_adb_run shell appops set "$manager" REQUEST_INSTALL_PACKAGES allow
archphene_adb_run shell am start -n "$manager/.MainActivity" --es archphene_test_apk_url "file:///data/user/0/$manager/cache/archpheneos-kcalc-update.apk" --es archphene_test_apk_sha256 "$hash" --es archphene_test_apk_package "$kcalc" >/dev/null
archphene_wait_ui_text Update package-installer-confirm 25; ui="$ARCHPHENE_UI"; [[ "$ui" == *KCalc* ]] || archphene_die 'KCalc update confirmation did not appear'; archphene_tap_text "$ui" Update; archphene_wait_ui_text 'Android package update installed' package-installer-result 25
"$ARCHPHENE_SCRIPTS_DIR/test-kcalc-calculation.sh" --serial "$serial"; archphene_note 'Manager PackageInstaller update passed with Android confirmation and KCalc health check.'
