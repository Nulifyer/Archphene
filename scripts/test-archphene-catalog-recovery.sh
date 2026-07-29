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
seed_action=org.archphene.app.debug.action.SEED_PACKAGE_JOB
fixture=archphene-catalog-recovery
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
token="catalog-recovery-$serial_slug"
output_dir="$ARCHPHENE_ROOT/tooling/build/catalog-recovery"
backup_dir="$(archphene_mktemp_dir catalog-recovery-state)"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=
before_count=

backup_app_file() {
  local relative="$1" label="$2"
  if archphene_adb_run shell run-as "$package" test -f "$relative" \
    >/dev/null 2>&1; then
    archphene_adb_run exec-out run-as "$package" cat "$relative" \
      >"$backup_dir/$label"
    : >"$backup_dir/$label.present"
  fi
}

restore_app_file() {
  local relative="$1" label="$2"
  local temporary="/data/local/tmp/archphene-$token-$label"
  if [[ -f "$backup_dir/$label.present" ]]; then
    archphene_adb_run push "$backup_dir/$label" "$temporary" >/dev/null
    archphene_adb_run shell run-as "$package" cp "$temporary" "$relative"
    archphene_adb_run shell run-as "$package" chmod 600 "$relative"
    archphene_adb_run shell rm -f "$temporary"
  else
    archphene_adb_run shell run-as "$package" rm -f "$relative"
  fi
}

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "catalog-recovery-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

select_fixture_package() {
  archphene_open_manager_section Packages "catalog-recovery-packages-$serial"
  archphene_wait_ui \
    'text="Package name"[^>]*class="android\.widget\.EditText"' \
    "catalog-recovery-input-$serial" 15
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" \
    'text="Package name"[^>]*class="android\.widget\.EditText"' \
    "Package name"
  archphene_adb_run shell input keycombination \
    KEYCODE_CTRL_LEFT KEYCODE_A >/dev/null
  archphene_adb_run shell input text "$fixture" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
}

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  restore_app_file \
    files/arch-root/var/lib/archphene/package-jobs.v1 package-jobs.v1 || true
  archphene_adb_run shell run-as "$package" rm -f \
    files/arch-root/var/lib/archphene/package-jobs.v1.tmp >/dev/null 2>&1 || true
  restore_app_file shared_prefs/package_recovery.xml package_recovery.xml || true
  restore_app_file shared_prefs/package_job_test.xml package_job_test.xml || true
  if [[ "$initial_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    restore_section || true
  fi
  find "$backup_dir" -mindepth 1 -maxdepth 1 -type f -delete 2>/dev/null || true
  rmdir "$backup_dir" 2>/dev/null || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell am force-stop "$package" >/dev/null
backup_app_file files/arch-root/var/lib/archphene/package-jobs.v1 package-jobs.v1
backup_app_file shared_prefs/package_recovery.xml package_recovery.xml
backup_app_file shared_prefs/package_job_test.xml package_job_test.xml
before_count="$(
  archphene_adb_run shell run-as "$package" find \
    files/arch-root/var/lib/pacman/local \
    -mindepth 1 -maxdepth 1 -type d 2>/dev/null |
    wc -l
)"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast \
  -f 0x20 -n "$receiver" -a "$seed_action" \
  --es token "$token" \
  --es package "$fixture" \
  --es state failed \
  --es operation install \
  --es failure catalog \
  --ez catalog-recovery-fixture true >/dev/null
archphene_wait_log \
  "Seeded package job state=failed token=$token" 20 \
  "ArchphenePackageJobProbe:V *:S" >/dev/null

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null
archphene_wait_ui_exact_text \
  "Archphene is ready" "catalog-recovery-ready-$serial" 20
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
' <<<"$ARCHPHENE_UI"
)"
select_fixture_package

archphene_wait_ui_exact_text \
  "Package catalog is unavailable or invalid. Refresh catalogs, then Review." \
  "catalog-recovery-failure-$serial" 20
archphene_wait_ui_exact_text \
  "Refresh package catalogs" "catalog-recovery-action-$serial" 15
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-before.png"
archphene_tap_text "$ARCHPHENE_UI" "Refresh package catalogs"

archphene_wait_ui_exact_text \
  "Catalogs refreshed. Review the current package before retrying." \
  "catalog-recovery-complete-$serial" 20
archphene_wait_ui_exact_text \
  "Review" "catalog-recovery-review-$serial" 15
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-after.png"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
select_fixture_package
archphene_wait_ui_exact_text \
  "Catalogs refreshed. Review the current package before retrying." \
  "catalog-recovery-restored-$serial" 25
archphene_wait_ui_exact_text \
  "Review" "catalog-recovery-restored-review-$serial" 15

after_count="$(
  archphene_adb_run shell run-as "$package" find \
    files/arch-root/var/lib/pacman/local \
    -mindepth 1 -maxdepth 1 -type d 2>/dev/null |
    wc -l
)"
[[ "$after_count" == "$before_count" ]] ||
  archphene_die "catalog recovery changed the pacman database"
fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Catalog recovery emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene one-tap catalog recovery passed on $serial"
archphene_note "  Failure action, refresh completion, restart persistence, and Review passed"
archphene_note "  Pacman state remained at $after_count packages; no network was used"
archphene_note "  Full-device screenshots: $output_dir/$serial-{before,after}.png"
