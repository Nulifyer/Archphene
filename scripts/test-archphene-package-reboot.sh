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
receiver="$package/org.archphene.app.PackagePhaseTestReceiver"
action=org.archphene.app.debug.action.START_PACKAGE_PHASES
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
fixture="reboot-$serial_slug"
output_dir="$ARCHPHENE_ROOT/tooling/build/package-reboot"
output="$output_dir/$serial.png"
mkdir -p "$output_dir"

wait_for_boot() {
  local deadline=$((SECONDS + 180))
  archphene_adb_run wait-for-device
  while ((SECONDS < deadline)); do
    if [[ "$(archphene_adb_run shell getprop sys.boot_completed 2>/dev/null |
        tr -d '\r')" == 1 ]]; then
      return 0
    fi
    sleep 1
  done
  archphene_die "device did not finish booting after reboot"
}

dismiss_onboarding_if_present() {
  local name="$1" ui=
  local attempt
  for attempt in {1..10}; do
    ui="$(archphene_capture_ui "$name-$attempt" 2>/dev/null || true)"
    [[ -n "$ui" ]] && break
    sleep 1
  done
  [[ -n "$ui" ]] || archphene_die "could not inspect the onboarding state"
  if archphene_regex_contains "$ui" 'text="Connect Android files\?"'; then
    archphene_tap_ui_pattern \
      "$ui" 'text="(?:NOT NOW|Not now)"' "Not now"
    archphene_wait_ui \
      'text="Packages"[^>]*class="android\.widget\.Button"' \
      "$name-dismissed" 15
  fi
}

dismiss_usb_access_prompt_if_present() {
  local name="$1" ui=
  local attempt
  for attempt in {1..10}; do
    ui="$(archphene_capture_ui "$name-$attempt" 2>/dev/null || true)"
    [[ -n "$ui" ]] && break
    sleep 1
  done
  if archphene_regex_contains "$ui" 'text="Allow access to your data"'; then
    archphene_tap_ui_pattern "$ui" 'text="Deny"' "Deny USB data access" ||
      archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null 2>&1 ||
      true
  fi
}

start_manager() {
  local attempt
  for attempt in {1..10}; do
    if archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  archphene_die "could not start Archphene after boot"
}

select_package() {
  local name="$1"
  archphene_wait_ui \
    'text="Package name"[^>]*class="android\.widget\.EditText"' \
    "$name-field" 15
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" \
    'text="Package name"[^>]*class="android\.widget\.EditText"' \
    "Package name"
  archphene_adb_run shell input text "$fixture" >/dev/null
}

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
dismiss_usb_access_prompt_if_present "package-reboot-initial-usb-prompt-$serial"
archphene_adb_run logcat -c
start_manager
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
dismiss_onboarding_if_present "package-reboot-onboarding-$serial"
archphene_open_manager_section Packages "package-reboot-packages-$serial"

archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "reboot-$serial_slug" \
  --es package "$fixture" \
  --ei hold-ms 30000 >/dev/null
archphene_wait_log \
  "Started package phases=true token=reboot-$serial_slug" 15 \
  "ArchphenePackagePhaseProbe:V *:S" >/dev/null
archphene_wait_ui_exact_text \
  "Install · Resolving · 5%" "package-reboot-resolving-$serial" 45

archphene_note "Rebooting $serial during durable pre-mutation package work"
archphene_adb_run reboot
wait_for_boot
archphene_adb_run shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
archphene_adb_run shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 3
dismiss_usb_access_prompt_if_present "package-reboot-usb-prompt-$serial"
archphene_adb_run logcat -c >/dev/null 2>&1 || true
start_manager
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 30 >/dev/null
archphene_open_manager_section Packages "package-reboot-restored-packages-$serial"
select_package "package-reboot-restored-$serial"
archphene_wait_ui_exact_text \
  "Install · Failed · 5%" "package-reboot-failed-$serial" 30
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui_exact_text \
  "Install · Failed · 5%" "package-reboot-failed-visible-$serial" 15
ui="$ARCHPHENE_UI"
[[ "$ui" == *'text="Interrupted before package mutation; retry is required"'* ]] ||
  archphene_die "reboot did not publish the bounded interruption reason"
archphene_regex_contains \
  "$ui" \
  'text="(?:REVIEW|Review)"[^>]*class="android.widget.Button"[^>]*enabled="true"' ||
  archphene_die "rebooted package operation did not expose actionable Review"
if archphene_regex_contains \
  "$ui" 'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"'; then
  archphene_die "rebooted terminal package operation still exposed Cancel"
fi
archphene_adb_run exec-out screencap -p >"$output"

cache_contents="$(
  archphene_adb_run exec-out run-as "$package" find \
    files/arch-root/var/cache/pacman/pkg -mindepth 1 -maxdepth 1 -type f \
    -name "$fixture*" |
    tr -d '\r'
)"
[[ -z "$cache_contents" ]] ||
  archphene_die "reboot fixture created package cache files: $cache_contents"
installed_count=0
if archphene_adb_run shell run-as "$package" test -d \
  files/arch-root/var/lib/pacman/local; then
  installed_count="$(
    archphene_adb_run exec-out run-as "$package" find \
      files/arch-root/var/lib/pacman/local -mindepth 1 -maxdepth 1 -type d \
      -name "$fixture-*" -print |
      tr -cd '\n' |
      wc -l
  )"
fi
((installed_count == 0)) ||
  archphene_die "reboot fixture mutated the package database"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package reboot recovery emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene package reboot recovery passed on $serial"
archphene_note "  Active work became durable Failed/Review without package or cache mutation"
archphene_note "  Full-device screenshot: $output"
