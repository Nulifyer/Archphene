#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
allow_reboot=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --allow-reboot) allow_reboot=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --allow-reboot [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$allow_reboot" == true ]] ||
  archphene_die "--allow-reboot is required because this gate reboots the device"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
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
backup_root="files/test-fixtures/package-reboot-$serial_slug-$$"
job_store="files/arch-root/var/lib/archphene/package-jobs.v1"
job_backup="$backup_root/package-jobs.v1"
recovery_preferences="shared_prefs/package_recovery.xml"
recovery_backup="$backup_root/package-recovery.xml"
job_existed=false
recovery_existed=false
backup_ready=false
was_running=false
job_hash_before=
recovery_hash_before=

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
  if [[ "$backup_ready" == true ]]; then
    if [[ "$job_existed" == true ]]; then
      archphene_adb_run shell run-as "$package" cp -p \
        "$job_backup" "$job_store" >/dev/null 2>&1 || true
    else
      archphene_adb_run shell run-as "$package" rm -f \
        "$job_store" >/dev/null 2>&1 || true
    fi
    if [[ "$recovery_existed" == true ]]; then
      archphene_adb_run shell run-as "$package" cp -p \
        "$recovery_backup" "$recovery_preferences" >/dev/null 2>&1 || true
    else
      archphene_adb_run shell run-as "$package" rm -f \
        "$recovery_preferences" >/dev/null 2>&1 || true
    fi
    archphene_adb_run shell run-as "$package" rm -rf \
      "$backup_root" >/dev/null 2>&1 || true
  fi
  if [[ "$was_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  fi
}
assert_restored() {
  local actual
  if [[ "$job_existed" == true ]]; then
    actual="$(
      archphene_adb_run shell run-as "$package" sha256sum "$job_store" |
        awk '{print $1}' |
        tr -d '\r'
    )"
    [[ "$actual" == "$job_hash_before" ]] ||
      archphene_die "durable package jobs were not restored exactly"
  else
    ! archphene_adb_run shell run-as "$package" test -e "$job_store" ||
      archphene_die "the package reboot fixture left a durable job store"
  fi
  if [[ "$recovery_existed" == true ]]; then
    actual="$(
      archphene_adb_run shell run-as "$package" \
        sha256sum "$recovery_preferences" |
        awk '{print $1}' |
        tr -d '\r'
    )"
    [[ "$actual" == "$recovery_hash_before" ]] ||
      archphene_die "package recovery preferences were not restored exactly"
  else
    ! archphene_adb_run shell run-as "$package" \
      test -e "$recovery_preferences" ||
      archphene_die "the package reboot fixture left recovery preferences"
  fi
  ! archphene_adb_run shell run-as "$package" test -e "$backup_root" ||
    archphene_die "package reboot backup residue remains"
  if [[ "$was_running" == true ]]; then
    archphene_android_pid "$package" >/dev/null ||
      archphene_die "manager running state was not restored"
  else
    ! archphene_android_pid "$package" >/dev/null 2>&1 ||
      archphene_die "manager was left running after the package reboot gate"
  fi
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
if archphene_android_pid "$package" >/dev/null 2>&1; then
  was_running=true
fi
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell run-as "$package" test ! -e "$backup_root" ||
  archphene_die "package reboot backup path already exists: $backup_root"
archphene_adb_run shell run-as "$package" mkdir -p "$backup_root"
if archphene_adb_run shell run-as "$package" test -f "$job_store"; then
  job_existed=true
  job_hash_before="$(
    archphene_adb_run shell run-as "$package" sha256sum "$job_store" |
      awk '{print $1}' |
      tr -d '\r'
  )"
  archphene_adb_run shell run-as "$package" cp -p "$job_store" "$job_backup"
fi
if archphene_adb_run shell run-as "$package" test -f "$recovery_preferences"; then
  recovery_existed=true
  recovery_hash_before="$(
    archphene_adb_run shell run-as "$package" \
      sha256sum "$recovery_preferences" |
      awk '{print $1}' |
      tr -d '\r'
  )"
  archphene_adb_run shell run-as "$package" cp -p \
    "$recovery_preferences" "$recovery_backup"
fi
backup_ready=true
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

cleanup
assert_restored
trap - EXIT
archphene_note "Archphene package reboot recovery passed on $serial"
archphene_note "  Active work became durable Failed/Review without package or cache mutation"
archphene_note "  Full-device screenshot: $output"
