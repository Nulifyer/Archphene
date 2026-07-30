#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
target=git
target_file_relative=usr/bin/git
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --package) target="${2:?missing value for --package}"; shift 2 ;;
    --file) target_file_relative="${2:?missing value for --file}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk] [--package NAME --file ROOT_RELATIVE_FILE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$target" =~ ^[a-zA-Z0-9@._+-]{1,128}$ ]] ||
  archphene_die "invalid target package"
[[ "$target_file_relative" =~ ^[a-zA-Z0-9@._+/-]{1,1024}$ &&
  "$target_file_relative" != /* && "$target_file_relative" != *".."* ]] ||
  archphene_die "invalid target file"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
cache=files/arch-root/var/cache/pacman/pkg
target_file="files/arch-root/$target_file_relative"
reason_intent=files/arch-root/run/package-install-reasons-v1
reason_intent_temp=files/arch-root/run/package-install-reasons-v1.tmp
output_dir="$ARCHPHENE_ROOT/tooling/build/package-preflight"
initially_running=false
original_section=
mkdir -p "$output_dir"

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local center ui x y
  ui="$(archphene_capture_ui "package-preflight-restore-$serial" 2>/dev/null || true)"
  center="$(
    archphene_ui_node_center \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" 2>/dev/null || true
  )"
  if [[ "$center" =~ ^[0-9]+[[:space:]][0-9]+$ ]]; then
    read -r x y <<<"$center"
    archphene_adb_run shell input tap "$x" "$y" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  set +e
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  restore_section >/dev/null 2>&1 || true
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if archphene_android_pid "$package" >/dev/null 2>&1; then
  initially_running=true
fi
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell run-as "$package" test -x "$target_file" ||
  archphene_die "package preflight requires $target_file"
archive="$(archphene_adb_run shell run-as "$package" find "$cache" \
  -maxdepth 1 -type f -name "$target-*.pkg.tar.*" \
  ! -name '*.sig' ! -name '*.part' | tr -d '\r' | head -n1)"
[[ -n "$archive" ]] ||
  archphene_die "package preflight requires cached $target archive"
signature="$archive.sig"
archphene_adb_run shell run-as "$package" test -f "$signature" ||
  archphene_die "package preflight requires cached $target signature"
archive_state="$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$archive" |
  tr -d '\r')"
signature_state="$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$signature" |
  tr -d '\r')"
cache_listing="$(archphene_adb_run exec-out run-as "$package" find "$cache" \
  -maxdepth 1 -type f | tr -d '\r')"
[[ "$cache_listing" != *".part"* ]] ||
  archphene_die "package preflight requires a fully published cache"
cache_inventory_before="$(
  archphene_adb_run shell \
    "run-as $package sh -c 'cd $cache && find . -maxdepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
)"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
initial_ui="$(archphene_capture_ui "package-preflight-initial-$serial")"
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal", "Settings"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$initial_ui"
)"
[[ -n "$original_section" ]] ||
  archphene_die "could not determine the original manager section"
archphene_open_manager_section Packages "package-preflight-packages-$serial"
archphene_wait_ui 'text="Package catalog ready"' "package-preflight-catalog-$serial" 20
archphene_wait_ui 'text="Package name"' "package-preflight-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$target" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui \
  'text="(?:DETAILS|Details)"' "package-preflight-details-$serial" 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:DETAILS|Details)"' 'package details'
archphene_wait_ui "text=\"[^\"]*/$target [^\"]+.*Installed: [^\"]+\"" \
  "package-preflight-resolution-$serial" 20
archphene_wait_ui 'text="(?:VERIFY|Verify)"' "package-preflight-verify-$serial" 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:VERIFY|Verify)"' 'verify package'
archphene_wait_ui 'text="(?:Install|Update) · Complete · 100%"' \
  "package-preflight-complete-$serial" 120
archphene_wait_ui "text=\"Verified $target [^\"]+\"" \
  "package-preflight-result-$serial" 20
archphene_wait_log "Verified $target: [1-9][0-9]* signed packages" 15 >/dev/null
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
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:REMOVE|Remove)"' 'remove package'
if archphene_wait_ui_optional 'text="Remove unused dependencies\?"' \
  "package-preflight-remove-review-$serial" 30; then
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="Keep dependencies"' 'Keep dependencies'
fi
archphene_wait_ui 'text="Remove · Complete · 100%"' \
  "package-preflight-remove-$serial" 90
archphene_wait_ui "text=\"Removed $target [^\"]+\"" \
  "package-preflight-remove-result-$serial" 20
archphene_adb_run shell run-as "$package" test ! -e "$target_file" ||
  archphene_die "cache-only transaction gate did not remove $target"
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:INSTALL|Install)"' 'reinstall package'
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "package-preflight-reinstall-$serial" 120
archphene_wait_ui "text=\"Installed $target [^\"]+\"" \
  "package-preflight-reinstall-result-$serial" 20
archphene_wait_log "Installed $target: [1-9][0-9]* signed packages" 15 >/dev/null
archphene_adb_run shell run-as "$package" test -x "$target_file" ||
  archphene_die "cache-only transaction gate did not reinstall $target"
[[ "$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$archive" |
  tr -d '\r')" == "$archive_state" ]] ||
  archphene_die "cache-only reinstall unexpectedly rewrote the cached archive"
[[ "$(archphene_adb_run shell run-as "$package" stat -c '%s:%Y' "$signature" |
  tr -d '\r')" == "$signature_state" ]] ||
  archphene_die "cache-only reinstall unexpectedly rewrote the cached signature"
cache_inventory_after="$(
  archphene_adb_run shell \
    "run-as $package sh -c 'cd $cache && find . -maxdepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
)"
[[ "$cache_inventory_after" == "$cache_inventory_before" ]] ||
  archphene_die "cache-only transaction changed the complete package cache"

archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

printf 'org.archphene.package-install-reasons.v1\n%s\n' "$target" |
  archphene_adb_run shell run-as "$package" tee "$reason_intent" >/dev/null
archphene_adb_run shell run-as "$package" chmod 600 "$reason_intent"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_adb_run shell run-as "$package" test ! -e "$reason_intent" ||
  archphene_die "startup did not recover the durable install-reason intent"
local_target="$(archphene_adb_run exec-out run-as "$package" find \
  files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name "$target-*" |
  tr -d '\r' | head -n1)"
[[ -n "$local_target" ]] ||
  archphene_die "startup recovery lost the $target database entry"
target_description="$(archphene_adb_run exec-out run-as "$package" \
  cat "$local_target/desc" | tr -d '\r')"
target_reason="$(awk '/^%REASON%$/{getline; print; exit}' <<<"$target_description")"
[[ -z "$target_reason" ]] ||
  archphene_die "startup recovery did not restore $target's explicit install reason"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package preflight emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
if [[ "$initially_running" == true ]]; then
  archphene_android_pid "$package" >/dev/null ||
    archphene_die "package preflight did not restore the running manager"
else
  ! archphene_android_pid "$package" >/dev/null 2>&1 ||
    archphene_die "package preflight left a previously stopped manager running"
fi
archphene_note "Archphene package closure preflight passed on $serial"
archphene_note "  Verified, removed, and reinstalled $target without rewriting cached payloads"
archphene_note "  Durable explicit-install reason recovery passed after process restart"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
