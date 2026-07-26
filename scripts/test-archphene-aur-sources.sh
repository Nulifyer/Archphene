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
manager=org.archphene.app.debug
package=visual-studio-code-bin
output_dir="$ARCHPHENE_ROOT/tooling/build/aur-sources"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
}
trap cleanup EXIT

local_package_count() {
  archphene_adb_run shell run-as "$manager" \
    ls files/arch-root/var/lib/pacman/local |
    tr -d '\r' |
    awk 'NF { count++ } END { print count + 0 }'
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell monkey -p "$manager" \
  -c android.intent.category.LAUNCHER 1 >/dev/null
archphene_wait_ui 'text="Archphene is ready"' aur-sources-ready 30
before_count="$(local_package_count)"
[[ "$before_count" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "could not read the installed-package count"

ui="$(archphene_capture_ui aur-sources-input)"
archphene_tap_ui_pattern \
  "$ui" 'class="android\.widget\.EditText"' "package input"
archphene_adb_run shell input text "$package"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="AUR"[^>]*enabled="true"' aur-sources-review-action 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="AUR"[^>]*enabled="true"' "AUR"
archphene_wait_log \
  "Reviewed AUR $package .* commit=[0-9a-f]{40}" 45 \
  'ArchpheneRuntime:I *:S' >/dev/null
archphene_wait_ui 'text="Verify"[^>]*enabled="true"' aur-sources-verify-action 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Verify"[^>]*enabled="true"' "Verify"

verified_log="$(
  archphene_wait_log \
    "Verified 1 AUR source\\(s\\) for $package: [1-9][0-9]+ bytes" 300 \
    'ArchpheneRuntime:I *:S'
)"
verified_bytes="$(
  sed -nE \
    "s/.*Verified 1 AUR source\\(s\\) for $package: ([1-9][0-9]+) bytes.*/\\1/p" \
    <<<"$verified_log" |
    tail -1
)"
[[ "$verified_bytes" =~ ^[1-9][0-9]+$ ]] ||
  archphene_die "could not parse the verified source size"
archphene_wait_ui 'Verified source downloads:' aur-sources-result 30
ui="$ARCHPHENE_UI"
for pattern in \
  'Verified source downloads:' \
  'HTTPS endpoint[^:]*: https://' \
  'Installed/build disk impact: pending the isolated package build\.' \
  'code[^<]*\.deb' \
  'direct HTTPS download' \
  'SHA-256: [0-9a-f]{64}'
do
  archphene_regex_contains "$ui" "$pattern" ||
    archphene_die "verified AUR review omits required UI evidence: $pattern"
done
archphene_regex_contains \
  "$ui" 'text="Install"[^>]*enabled="false"' ||
  archphene_die "source verification unexpectedly enabled official install"

cache_listing="$(
  archphene_adb_run shell run-as "$manager" \
    ls -l files/arch-root/var/cache/archphene/aur-sources |
    tr -d '\r'
)"
grep -Eq '[0-9a-f]{64}-code[^ ]*\.deb$' <<<"$cache_listing" ||
  archphene_die "verified AUR source is absent from the bounded private cache"
[[ "$cache_listing" != *".part"* ]] ||
  archphene_die "AUR source verification left a partial file"
cache_filename="$(
  grep -Eo '[0-9a-f]{64}-code[^[:space:]]*\.deb' <<<"$cache_listing" |
    tail -1
)"
[[ "$cache_filename" =~ ^([0-9a-f]{64})- ]] ||
  archphene_die "verified AUR cache filename omits its expected digest"
expected_sha256="${BASH_REMATCH[1]}"
actual_sha256="$(
  archphene_adb_run shell run-as "$manager" sha256sum \
    "files/arch-root/var/cache/archphene/aur-sources/$cache_filename" |
    awk '{ print $1 }' |
    tr -d '\r'
)"
[[ "$actual_sha256" == "$expected_sha256" ]] ||
  archphene_die "independent device SHA-256 does not match the reviewed digest"

after_count="$(local_package_count)"
[[ "$after_count" == "$before_count" ]] ||
  archphene_die "AUR source verification mutated the pacman database"

archphene_adb_run shell screencap -p /sdcard/archphene-aur-sources.png
archphene_adb_run pull /sdcard/archphene-aur-sources.png \
  "$output_dir/$serial.png" >/dev/null
archphene_adb_run shell rm /sdcard/archphene-aur-sources.png

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "AUR source verification emitted a fatal Android error: $fatal_log"

archphene_note "Archphene AUR source verification passed on $serial"
archphene_note "  Rust verified $verified_bytes source bytes"
archphene_note "  Pacman state remained at $after_count local database entries"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
