#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
cache=files/arch-root/var/cache/pacman/pkg
reason_intent=files/arch-root/run/package-install-reasons-v1
reason_intent_temp=files/arch-root/run/package-install-reasons-v1.tmp
output_dir="$ARCHPHENE_ROOT/tooling/build/package-preflight"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/btop ||
  archphene_die "package preflight requires the existing btop installation"
archive="$(archphene_adb_run shell run-as "$package" find "$cache" \
  -maxdepth 1 -type f -name 'btop-*.pkg.tar.*' | tr -d '\r' | head -n1)"
[[ -n "$archive" ]] || archphene_die "package preflight requires cached btop archive"
signature="$archive.sig"
archphene_adb_run shell run-as "$package" test -f "$signature" ||
  archphene_die "package preflight requires cached btop signature"
archive_state="$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$archive" |
  tr -d '\r')"
signature_state="$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$signature" |
  tr -d '\r')"
cache_listing="$(archphene_adb_run exec-out run-as "$package" find "$cache" \
  -maxdepth 1 -type f | tr -d '\r')"
[[ "$cache_listing" != *".part"* ]] ||
  archphene_die "package preflight requires a fully published cache"
local_count="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -mindepth 2 -maxdepth 2 \
  -type f -name desc | tr -d '\r' | grep -c .)"
archive_count="$(grep -vE '\.sig$|\.part$' <<<"$cache_listing" | grep -c .)"
signature_count="$(grep -E '\.sig$' <<<"$cache_listing" | grep -c .)"
[[ "$archive_count" == "$local_count" && "$signature_count" == "$local_count" ]] ||
  archphene_die \
    "package preflight requires one cached archive/signature pair per installed package"

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui 'text="Package catalog ready"' "package-preflight-catalog-$serial" 20
archphene_wait_ui 'text="Package name"' "package-preflight-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input text btop >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="DETAILS"' "package-preflight-details-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="DETAILS"' 'package details'
archphene_wait_ui 'text="[^"]*/btop [^"]+.*Installed: [^"]+"' \
  "package-preflight-resolution-$serial" 20
archphene_wait_ui 'text="VERIFY"' "package-preflight-verify-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="VERIFY"' 'verify package'
archphene_wait_ui 'text="btop · Complete · 100%.*Verified btop [^"]+"' \
  "package-preflight-complete-$serial" 120
archphene_wait_log 'Verified btop: [1-9][0-9]* signed packages' 15 >/dev/null
archphene_adb_run shell run-as "$package" test ! -e "$reason_intent" ||
  archphene_die "completed package preflight left an install-reason intent"
archphene_adb_run shell run-as "$package" test ! -e "$reason_intent_temp" ||
  archphene_die "completed package preflight left a temporary install-reason intent"

[[ "$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$archive" |
  tr -d '\r')" == "$archive_state" ]] ||
  archphene_die "package preflight unexpectedly rewrote the cached archive"
[[ "$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$signature" |
  tr -d '\r')" == "$signature_state" ]] ||
  archphene_die "package preflight unexpectedly rewrote the cached signature"
temporary="$(archphene_adb_run exec-out run-as "$package" find "$cache" \
  -maxdepth 1 -type f -name '*.part' | tr -d '\r')"
[[ -z "$temporary" ]] ||
  archphene_die "package preflight left a temporary cache entry"

archphene_adb_run logcat -c
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="REMOVE"' 'remove package'
if archphene_wait_ui_optional 'text="Remove unused dependencies\?"' \
  "package-preflight-remove-review-$serial" 30; then
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Keep dependencies"' 'Keep dependencies'
fi
archphene_wait_ui 'text="btop · Complete · 100%.*Removed btop [^"]+"' \
  "package-preflight-remove-$serial" 90
archphene_adb_run shell run-as "$package" test ! -e files/arch-root/usr/bin/btop ||
  archphene_die "cache-only transaction gate did not remove btop"
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="INSTALL"' 'reinstall package'
archphene_wait_ui 'text="btop · Complete · 100%.*Installed btop [^"]+"' \
  "package-preflight-reinstall-$serial" 120
archphene_wait_log 'Installed btop: [1-9][0-9]* signed packages' 15 >/dev/null
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/btop ||
  archphene_die "cache-only transaction gate did not reinstall btop"
[[ "$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$archive" |
  tr -d '\r')" == "$archive_state" ]] ||
  archphene_die "cache-only reinstall unexpectedly rewrote the cached archive"
[[ "$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$signature" |
  tr -d '\r')" == "$signature_state" ]] ||
  archphene_die "cache-only reinstall unexpectedly rewrote the cached signature"

archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

printf 'org.archphene.package-install-reasons.v1\nbtop\n' |
  archphene_adb_run shell run-as "$package" tee "$reason_intent" >/dev/null
archphene_adb_run shell run-as "$package" chmod 600 "$reason_intent"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_adb_run shell run-as "$package" test ! -e "$reason_intent" ||
  archphene_die "startup did not recover the durable install-reason intent"
local_btop="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name 'btop-*' |
  tr -d '\r' | head -n1)"
[[ -n "$local_btop" ]] || archphene_die "startup recovery lost the btop database entry"
btop_description="$(archphene_adb_run exec-out run-as "$package" \
  cat "$local_btop/desc" | tr -d '\r')"
btop_reason="$(awk '/^%REASON%$/{getline; print; exit}' <<<"$btop_description")"
[[ -z "$btop_reason" ]] ||
  archphene_die "startup recovery did not restore btop's explicit install reason"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package preflight emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene package closure preflight passed on $serial"
archphene_note "  Verified, removed, and reinstalled btop without rewriting cached payloads"
archphene_note "  Durable explicit-install reason recovery passed after process restart"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
