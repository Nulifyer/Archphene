#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.PackageJobTestReceiver"
seed_action=org.archphene.app.debug.action.SEED_PACKAGE_CACHE
clean_action=org.archphene.app.debug.action.CLEAN_PACKAGE_CACHE
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
token="cache-$serial_slug"
target="0archphene.cache.target.${token//-/.}"
sibling="0archphene.cache.sibling.${token//-/.}"
output_dir="$ARCHPHENE_ROOT/tooling/build/package-cache"
state_dir="$(archphene_mktemp_dir package-cache-state)"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=
before_count=

cache_listing() {
  archphene_adb_run shell run-as "$package" \
    ls -ln files/arch-root/var/cache/pacman/pkg 2>/dev/null |
    tr -d '\r' |
    awk -v target="$target" -v sibling="$sibling" \
      'index($0, target) == 0 && index($0, sibling) == 0'
}

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "package-cache-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am broadcast \
    -f 0x20 -n "$receiver" -a "$clean_action" \
    --es token "$token" >/dev/null 2>&1 || true
  if [[ -n "$original_section" ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    restore_section || true
  fi
  if [[ "$initial_running" != true ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
  find "$state_dir" -mindepth 1 -maxdepth 1 -type f -delete 2>/dev/null || true
  rmdir "$state_dir" 2>/dev/null || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui_exact_text \
  "Archphene is ready" "package-cache-ready-$serial" 20
ui="$(archphene_capture_ui "package-cache-initial-$serial" 2>/dev/null || true)"
if archphene_regex_contains "$ui" 'text="Connect Android files\?"'; then
  archphene_tap_ui_pattern \
    "$ui" 'text="(?:NOT NOW|Not now)"' "Not now"
  sleep 1
  ui="$(archphene_capture_ui "package-cache-after-onboarding-$serial")"
  if archphene_regex_contains "$ui" 'text="Connect Android files\?"'; then
    archphene_die "storage onboarding did not dismiss"
  fi
fi
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$ui"
)"
archphene_open_manager_section Packages "package-cache-packages-$serial"

before_count="$(
  archphene_adb_run shell run-as "$package" find \
    files/arch-root/var/lib/pacman/local \
    -mindepth 1 -maxdepth 1 -type d 2>/dev/null |
    wc -l
)"
cache_listing >"$state_dir/cache-before"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast \
  -f 0x20 -n "$receiver" -a "$seed_action" \
  --es token "$token" >/dev/null
archphene_wait_log \
  "Seeded selectable package cache token=$token" 20 \
  "ArchphenePackageJobProbe:V *:S" >/dev/null

archphene_wait_ui_exact_text Downloads "package-cache-downloads-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" Downloads
archphene_wait_ui \
  'text="Downloaded packages · [^"]+"' \
  "package-cache-dialog-$serial" 20
archphene_wait_ui \
  "text=\"$target 2 cached versions · [^\"]+ · 3 files\"" \
  "package-cache-target-$serial" 15
archphene_wait_ui \
  "text=\"$sibling 1.0-1 · [^\"]+ · 1 file\"" \
  "package-cache-sibling-$serial" 15
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-before.png"

archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" \
  "text=\"$target 2 cached versions · [^\"]+ · 3 files\"" \
  "$target"
archphene_wait_ui \
  'text="Clear selected"[^>]*enabled="true"' \
  "package-cache-clear-selected-$serial" 15
archphene_tap_text "$ARCHPHENE_UI" "Clear selected"
archphene_wait_log \
  'Cleared 3584 package-cache bytes for 1 selected package' 20 \
  "ArchpheneRuntime:V *:S" >/dev/null
archphene_wait_ui \
  'text="Downloaded packages · [^"]+"' \
  "package-cache-dialog-after-$serial" 20
archphene_wait_ui \
  "text=\"$sibling 1.0-1 · [^\"]+ · 1 file\"" \
  "package-cache-sibling-after-$serial" 15
if archphene_regex_contains "$ARCHPHENE_UI" "text=\"$target "; then
  archphene_die "selected package remained in the package-cache dialog"
fi
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-after.png"

archphene_tap_text "$ARCHPHENE_UI" "Clear all"
archphene_wait_ui_exact_text \
  "Clear all downloaded packages?" "package-cache-clear-all-confirm-$serial" 15
archphene_wait_ui_exact_text \
  "Installed Linux packages remain installed. Future installs or updates may need to download these package files again." \
  "package-cache-clear-all-message-$serial" 15
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-clear-all-confirmation.png"
archphene_tap_text "$ARCHPHENE_UI" Cancel

remaining="$(
  archphene_adb_run shell run-as "$package" \
    ls files/arch-root/var/cache/pacman/pkg |
    tr -d '\r'
)"
[[ "$remaining" != *"$target"* ]] ||
  archphene_die "selected package-cache artifacts remain"
[[ "$remaining" == *"$sibling"* ]] ||
  archphene_die "unselected package-cache artifact was removed"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am broadcast \
  -f 0x20 -n "$receiver" -a "$clean_action" \
  --es token "$token" >/dev/null
archphene_wait_log \
  "Cleaned selectable package cache token=$token" 20 \
  "ArchphenePackageJobProbe:V *:S" >/dev/null
cache_listing >"$state_dir/cache-after"
cmp -s "$state_dir/cache-before" "$state_dir/cache-after" ||
  archphene_die "package-cache test did not restore the prior cache listing"

after_count="$(
  archphene_adb_run shell run-as "$package" find \
    files/arch-root/var/lib/pacman/local \
    -mindepth 1 -maxdepth 1 -type d 2>/dev/null |
    wc -l
)"
[[ "$after_count" == "$before_count" ]] ||
  archphene_die "package-cache controls changed the pacman database"
fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Package-cache controls emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene package-cache controls passed on $serial"
archphene_note "  Inventory, version grouping, selective cleanup, and clear-all confirmation passed"
archphene_note "  Pacman state remained at $after_count packages and prior cache entries were preserved"
archphene_note "  Full-device screenshots: $output_dir/$serial-{before,after,clear-all-confirmation}.png"
