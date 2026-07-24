#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--skip-install]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
archphene_require_command python3
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/package-prepare-regression"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui 'Package catalog (ready|not downloaded)' \
  "archphene-prepare-catalog-$serial" 15
if ! archphene_regex_contains "$ARCHPHENE_UI" 'Package catalog ready'; then
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="REFRESH CATALOGS"' 'refresh catalogs'
  archphene_wait_ui 'text="Package catalog ready"' \
    "archphene-prepare-catalog-ready-$serial" 60
fi

archphene_wait_ui 'text="Package name"' "archphene-prepare-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input text btop >/dev/null
archphene_wait_ui 'text="btop"' "archphene-prepare-entered-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="DETAILS"' 'package details'
archphene_wait_ui \
  'text="[^"]*/btop [^"]+.*Dependency closure: [1-9][0-9]* packages' \
  "archphene-prepare-resolution-$serial" 20
closure_count="$(python3 -c '
import re, sys
match = re.search(r"Dependency closure: ([1-9][0-9]*) packages", sys.stdin.read())
if match is None:
    raise SystemExit("missing dependency closure count")
print(match.group(1))
' <<<"$ARCHPHENE_UI")"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="INSTALL"' 'install package'
archphene_wait_ui 'text="btop · (Downloading|Verifying|Installing|Complete) · [1-9][0-9]*%' \
  "archphene-prepare-active-$serial" 60
archphene_wait_ui 'text="btop · Complete · 100%.*Installed btop [^"]+"' \
  "archphene-prepare-complete-$serial" 180
archphene_wait_log "Installed btop: $closure_count signed packages" 15 >/dev/null
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/btop ||
  archphene_die "installed btop executable is missing"
local_btop="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name 'btop-*' |
  tr -d '\r')"
[[ -n "$local_btop" ]] || archphene_die "pacman did not record the btop installation"

cache=files/arch-root/var/cache/pacman/pkg
cache_listing="$(archphene_adb_run exec-out run-as "$package" find "$cache" \
  -maxdepth 1 -type f | tr -d '\r')"
file_count="$(grep -c . <<<"$cache_listing")"
expected_count=$((closure_count * 2))
[[ "$file_count" == "$expected_count" ]] ||
  archphene_die "unexpected verified cache count: $file_count (wanted $expected_count)"
[[ "$cache_listing" != *".part"* ]] ||
  archphene_die "temporary package download survived publication"
while IFS= read -r file; do
  [[ -n "$file" ]] || continue
  mode="$(archphene_adb_run exec-out run-as "$package" stat -c %a "$file" | tr -d '\r')"
  [[ "$mode" == 600 ]] || archphene_die "unexpected package cache mode: $file $mode"
done <<<"$cache_listing"

tampered_package="$(grep '/btop-[^/]*\.pkg\.tar\.' <<<"$cache_listing" |
  grep -v '\.sig$' | head -n1)"
[[ -n "$tampered_package" ]] || archphene_die "verified package cache has no archive"
archphene_adb_run logcat -c
archphene_adb_run shell run-as "$package" dd if=/dev/zero of="$tampered_package" \
  bs=1 count=1 conv=notrunc >/dev/null 2>&1
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="INSTALL"' 'reinstall tampered cache'
archphene_wait_log 'Rejected invalid cached package .*package command failed with status' \
  30 >/dev/null
archphene_wait_ui 'text="btop · Complete · 100%.*Installed btop [^"]+"' \
  "archphene-prepare-tamper-recovered-$serial" 90

archphene_adb_run exec-out screencap -p >"$output_dir/$serial-btop.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_wait_ui 'text="btop · Complete · 100%.*Installed btop [^"]+"' \
  "archphene-prepare-reused-$serial" 20

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package install emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene signed package install passed on $serial"
archphene_note "  $closure_count archives and signatures were atomically cached, verified, and installed"
archphene_note "  Tampered cache was rejected, redownloaded, and reverified"
archphene_note "  Durable Complete state survived process death"
archphene_note "  Full-device screenshot: $output_dir/$serial-btop.png"
