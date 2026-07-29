#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
clean_data=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --skip-install) skip_install=true; shift ;;
    --clean-data) clean_data=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--install-apk] [--clean-data]"
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

wait_for_btop_install() {
  local name="$1" result_pattern="${2:-Installed}" deadline=$((SECONDS + 180)) ui stable=0
  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "$name" 2>/dev/null || true)"
    if [[ "$ui" == *'package="com.google.android.packageinstaller"'* ]]; then
      archphene_tap_ui_pattern \
        "$ui" 'text="Install"[^>]*enabled="true"' 'Install btop launcher APK'
      sleep 1
      archphene_adb_run shell monkey -p "$package" \
        -c android.intent.category.LAUNCHER 1 >/dev/null
      stable=0
      continue
    fi
    if [[ "$ui" == *"package=\"$package\""* ]] &&
      archphene_regex_contains \
        "$ui" 'text="(?:Install|Update) · Complete · 100%"' &&
      archphene_regex_contains \
        "$ui" "text=\"(?:$result_pattern) btop [^\"]+\""; then
      stable=$((stable + 1))
      if ((stable >= 3)); then
        ARCHPHENE_UI="$ui"
        return 0
      fi
    else
      stable=0
    fi
    sleep 0.5
  done
  archphene_die "timed out waiting for btop and its Android launcher installation"
}

wait_for_btop_removal() {
  local name="$1" deadline=$((SECONDS + 120)) ui stable=0
  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "$name" 2>/dev/null || true)"
    if [[ "$ui" == *'package="com.google.android.packageinstaller"'* ]] &&
      [[ "$ui" == *'Do you want to uninstall this app?'* ]]; then
      archphene_tap_ui_pattern \
        "$ui" 'text="OK"[^>]*enabled="true"' 'Uninstall btop launcher APK'
      sleep 1
      archphene_adb_run shell monkey -p "$package" \
        -c android.intent.category.LAUNCHER 1 >/dev/null
      stable=0
      continue
    fi
    if [[ "$ui" == *"package=\"$package\""* ]] &&
      archphene_regex_contains "$ui" 'text="Remove · Complete · 100%"' &&
      archphene_regex_contains "$ui" 'text="Removed btop [^"]+"'; then
      stable=$((stable + 1))
      if ((stable >= 3)); then
        ARCHPHENE_UI="$ui"
        return 0
      fi
    else
      stable=0
    fi
    sleep 0.5
  done
  archphene_die "timed out waiting for btop and its Android launcher removal"
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  if [[ "$clean_data" == true ]]; then
    archphene_adb_run uninstall "$package" >/dev/null 2>&1 || true
  fi
  archphene_adb_run install -r "$apk" >/dev/null
fi
if [[ "$clean_data" == true && "$skip_install" == true ]]; then
  archphene_adb_run shell pm clear "$package" >/dev/null
fi

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
if [[ "$clean_data" == true ]]; then
  archphene_skip_storage_onboarding "archphene-prepare-onboarding-$serial"
fi
archphene_open_manager_section Packages "archphene-prepare-packages-$serial"
archphene_wait_ui 'Package catalog (ready|not downloaded)' \
  "archphene-prepare-catalog-$serial" 15
if ! archphene_regex_contains "$ARCHPHENE_UI" 'Package catalog ready'; then
archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="(?:REFRESH CATALOGS|Refresh catalogs)"' 'refresh catalogs'
  archphene_wait_ui 'text="Package catalog ready"' \
    "archphene-prepare-catalog-ready-$serial" 60
fi

archphene_wait_ui 'text="Package name"' "archphene-prepare-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input text btop >/dev/null
archphene_wait_ui 'text="btop"' "archphene-prepare-entered-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:DETAILS|Details)"' 'package details'
archphene_wait_ui \
  'text="[^"]*/btop [^"]+.*Dependency closure: [1-9][0-9]* packages' \
  "archphene-prepare-resolution-$serial" 180
closure_count="$(python3 -c '
import re, sys
match = re.search(r"Dependency closure: ([1-9][0-9]*) packages", sys.stdin.read())
if match is None:
    raise SystemExit("missing dependency closure count")
print(match.group(1))
' <<<"$ARCHPHENE_UI")"

archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:INSTALL|Install|VERIFY|Verify|RETRY|Retry)"' \
  'install, verify, or retry package'
wait_for_btop_install "archphene-prepare-complete-$serial" "Installed|Verified"
archphene_wait_log \
  "(?:Installed|Verified) btop: $closure_count signed packages" 15 >/dev/null
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/btop ||
  archphene_die "installed btop executable is missing"
local_btop="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name 'btop-*' |
  tr -d '\r')"
[[ -n "$local_btop" ]] || archphene_die "pacman did not record the btop installation"
btop_description="$(archphene_adb_run exec-out run-as "$package" \
  cat "$local_btop/desc" | tr -d '\r')"
btop_reason="$(awk '/^%REASON%$/{getline; print; exit}' <<<"$btop_description")"
[[ -z "$btop_reason" ]] ||
  archphene_die "requested package was unexpectedly recorded as a dependency"
dependency_entry="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name 'glibc-*' |
  tr -d '\r' | head -n1)"
[[ -n "$dependency_entry" ]] || archphene_die "installed dependency entry is missing"
dependency_description="$(archphene_adb_run exec-out run-as "$package" \
  cat "$dependency_entry/desc" | tr -d '\r')"
dependency_reason="$(awk '/^%REASON%$/{getline; print; exit}' \
  <<<"$dependency_description")"
[[ "$dependency_reason" == 1 ]] ||
  archphene_die "dependency package was not recorded with dependency reason"

cache=files/arch-root/var/cache/pacman/pkg
cache_listing="$(archphene_adb_run exec-out run-as "$package" find "$cache" \
  -maxdepth 1 -type f | tr -d '\r')"
file_count="$(grep -c . <<<"$cache_listing")"
expected_count=$((closure_count * 2))
((file_count >= expected_count)) ||
  archphene_die "verified cache is missing closure artifacts: $file_count (need at least $expected_count)"
[[ "$cache_listing" != *".part"* ]] ||
  archphene_die "temporary package download survived publication"
while IFS= read -r file; do
  [[ -n "$file" ]] || continue
  mode="$(archphene_adb_run exec-out run-as "$package" stat -c %a "$file" | tr -d '\r')"
  [[ "$mode" == 600 ]] || archphene_die "unexpected package cache mode: $file $mode"
done <<<"$cache_listing"

compatibility_cache=files/arch-root/var/cache/archphene/package-compatibility-v1
compatibility_listing="$(
  archphene_adb_run exec-out run-as "$package" find "$compatibility_cache" \
    -maxdepth 1 -type f | tr -d '\r'
)"
compatibility_count="$(grep -c . <<<"$compatibility_listing")"
((compatibility_count >= 1 && compatibility_count <= 1024)) ||
  archphene_die "compatibility review cache is not bounded: $compatibility_count"
[[ "$compatibility_listing" != *".tmp"* ]] ||
  archphene_die "temporary compatibility review survived publication"
while IFS= read -r file; do
  [[ -n "$file" ]] || continue
  name="${file##*/}"
  [[ "$name" =~ ^[0-9a-f]{64}$ ]] ||
    archphene_die "invalid compatibility cache identity: $name"
  mode="$(archphene_adb_run exec-out run-as "$package" stat -c %a "$file" | tr -d '\r')"
  bytes="$(archphene_adb_run exec-out run-as "$package" stat -c %s "$file" | tr -d '\r')"
  [[ "$mode" == 600 && "$bytes" -gt 0 && "$bytes" -le 1024 ]] ||
    archphene_die "invalid compatibility cache record: $file $mode $bytes"
done <<<"$compatibility_listing"

tampered_package="$(grep '/btop-[^/]*\.pkg\.tar\.' <<<"$cache_listing" |
  grep -v '\.sig$' | head -n1)"
[[ -n "$tampered_package" ]] || archphene_die "verified package cache has no archive"
archphene_adb_run logcat -c
archphene_adb_run shell run-as "$package" dd if=/dev/zero of="$tampered_package" \
  bs=1 count=1 conv=notrunc >/dev/null 2>&1
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:VERIFY|Verify)"' 'verify tampered cache'
archphene_wait_log 'Rejected invalid cached package .*package command failed with status' \
  30 >/dev/null
archphene_wait_ui 'text="Update · Complete · 100%"' \
  "archphene-prepare-tamper-state-$serial" 90
archphene_wait_ui 'text="Verified btop [^"]+"' \
  "archphene-prepare-tamper-recovered-$serial" 15

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:REMOVE|Remove)"' 'remove package'
if archphene_wait_ui_optional 'text="Remove unused dependencies\?"' \
  "archphene-remove-review-$serial" 30; then
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Keep dependencies"' 'Keep dependencies'
fi
wait_for_btop_removal "archphene-remove-complete-$serial"
archphene_wait_log 'Removed btop [^ ]+' 15 >/dev/null
if archphene_adb_run shell run-as "$package" test -e files/arch-root/usr/bin/btop; then
  archphene_die "removed btop executable remains present"
fi
removed_btop="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name 'btop-*' |
  tr -d '\r')"
[[ -z "$removed_btop" ]] || archphene_die "removed btop remains in pacman's database"
archphene_adb_run shell run-as "$package" test -f "$dependency_entry/desc" ||
  archphene_die "conservative removal unexpectedly removed a dependency"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:INSTALL|Install)"' 'reinstall removed package'
wait_for_btop_install "archphene-reinstall-complete-$serial"
archphene_wait_log 'Installed btop: [1-9][0-9]* signed packages' 15 >/dev/null
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/btop ||
  archphene_die "reinstalled btop executable is missing"

archphene_open_manager_section Terminal "archphene-prepare-terminal-$serial"
archphene_run_debug_linux_command "$package" "btop --version"
archphene_wait_ui 'text="Exited 0[^"]*btop version' \
  "archphene-command-complete-$serial" 45
archphene_wait_log 'Linux command btop exited 0' 15 >/dev/null

archphene_adb_run exec-out screencap -p >"$output_dir/$serial-btop.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Packages "archphene-prepare-reused-packages-$serial"
archphene_wait_ui 'text="Package name"' \
  "archphene-prepare-reused-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input text btop >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Search results"' \
  "archphene-prepare-reused-results-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Search results"' 'Search results'
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "archphene-prepare-reused-state-$serial" 20
archphene_wait_ui 'text="Installed btop [^"]+"' \
  "archphene-prepare-reused-$serial" 15

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package install emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene signed package install passed on $serial"
archphene_note "  $closure_count archives and signatures were atomically cached, verified, and installed"
archphene_note "  Tampered cache was rejected, redownloaded, and reverified"
archphene_note "  Conservative removal and verified-cache reinstall passed"
archphene_note "  Shared Linux command environment executed btop --version"
archphene_note "  Durable Complete state survived process death"
archphene_note "  Full-device screenshot: $output_dir/$serial-btop.png"
