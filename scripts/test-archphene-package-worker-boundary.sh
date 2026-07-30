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
receiver="$package/org.archphene.app.PackagePhaseTestReceiver"
action=org.archphene.app.debug.action.START_PACKAGE_PHASES
output_dir="$ARCHPHENE_ROOT/tooling/build/package-worker-boundary"
mkdir -p "$output_dir"
raw_log="$output_dir/$serial.log"
screenshot="$output_dir/$serial.png"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
backup_root="files/test-fixtures/package-worker-$serial_slug"
job_store="files/arch-root/var/lib/archphene/package-jobs.v1"
job_backup="$backup_root/package-jobs.v1"
recovery_preferences="shared_prefs/package_recovery.xml"
recovery_backup="$backup_root/package-recovery.xml"
job_existed=false
recovery_existed=false
job_sha_before=
recovery_sha_before=
backup_ready=false
database_inventory_before=
cache_inventory_before=

initially_running=false
if [[ -n "$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')" ]]; then
  initially_running=true
fi
original_section=

database_inventory() {
  archphene_adb_run shell \
    "run-as $package sh -c 'cd files/arch-root/var/lib/pacman/local && find . -mindepth 2 -maxdepth 2 -type f \\( -name desc -o -name files \\) -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

cache_inventory() {
  archphene_adb_run shell \
    "run-as $package sh -c 'cd files/arch-root/var/cache/pacman/pkg && find . -maxdepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local center ui x y
  ui="$(archphene_capture_ui "package-worker-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    center="$(
      archphene_ui_node_center \
        "$ui" \
        "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
        "$original_section" 2>/dev/null || true
    )"
    if [[ "$center" =~ ^[0-9]+[[:space:]][0-9]+$ ]]; then
      read -r x y <<<"$center"
      archphene_adb_run shell input tap "$x" "$y" >/dev/null 2>&1 || true
      archphene_wait_ui_optional \
        "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"[^>]*selected=\"true\"" \
        "package-worker-restore-selected-$serial" 10 >/dev/null 2>&1 || true
    fi
  fi
}

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$backup_ready" == true ]]; then
    if [[ "$job_existed" == true ]]; then
      archphene_adb_run shell run-as "$package" cp -p "$job_backup" "$job_store" \
        >/dev/null 2>&1 || true
    else
      archphene_adb_run shell run-as "$package" rm -f "$job_store" \
        >/dev/null 2>&1 || true
    fi
    if [[ "$recovery_existed" == true ]]; then
      archphene_adb_run shell run-as "$package" cp -p \
        "$recovery_backup" "$recovery_preferences" >/dev/null 2>&1 || true
    else
      archphene_adb_run shell run-as "$package" rm -f "$recovery_preferences" \
        >/dev/null 2>&1 || true
    fi
    archphene_adb_run shell run-as "$package" rm -f \
      "$job_backup" "$recovery_backup" >/dev/null 2>&1 || true
    archphene_adb_run shell run-as "$package" rmdir "$backup_root" \
      >/dev/null 2>&1 || true
    backup_ready=false
  fi
  archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  restore_section >/dev/null 2>&1 || true
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

assert_restored() {
  local actual_job_sha actual_recovery_sha
  if [[ "$job_existed" == true ]]; then
    actual_job_sha="$(
      archphene_adb_run exec-out run-as "$package" sha256sum "$job_store" |
        tr -d '\r' |
        awk '{print $1}'
    )"
    [[ "$actual_job_sha" == "$job_sha_before" ]] ||
      archphene_die "package job store was not restored exactly"
  else
    archphene_adb_run shell run-as "$package" test ! -e "$job_store" ||
      archphene_die "package worker left a new job store"
  fi
  if [[ "$recovery_existed" == true ]]; then
    actual_recovery_sha="$(
      archphene_adb_run exec-out run-as "$package" \
        sha256sum "$recovery_preferences" |
        tr -d '\r' |
        awk '{print $1}'
    )"
    [[ "$actual_recovery_sha" == "$recovery_sha_before" ]] ||
      archphene_die "package recovery preferences were not restored exactly"
  else
    archphene_adb_run shell run-as "$package" test ! -e "$recovery_preferences" ||
      archphene_die "package worker left new recovery preferences"
  fi
  archphene_adb_run shell run-as "$package" test ! -e "$backup_root" ||
    archphene_die "package worker left its backup directory"
  if [[ "$initially_running" == true ]]; then
    archphene_android_pid "$package" >/dev/null ||
      archphene_die "package worker did not restore the running manager"
  else
    ! archphene_android_pid "$package" >/dev/null 2>&1 ||
      archphene_die "package worker left a previously stopped manager running"
  fi
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell run-as "$package" test ! -e "$backup_root" ||
  archphene_die "package-worker backup path already exists"
archphene_adb_run shell run-as "$package" mkdir -p "$backup_root"
if archphene_adb_run shell run-as "$package" test -f "$job_store"; then
  job_existed=true
  archphene_adb_run shell run-as "$package" cp -p "$job_store" "$job_backup"
  job_sha_before="$(
    archphene_adb_run exec-out run-as "$package" sha256sum "$job_store" |
      tr -d '\r' |
      awk '{print $1}'
  )"
fi
if archphene_adb_run shell run-as "$package" test -f "$recovery_preferences"; then
  recovery_existed=true
  archphene_adb_run shell run-as "$package" cp -p \
    "$recovery_preferences" "$recovery_backup"
  recovery_sha_before="$(
    archphene_adb_run exec-out run-as "$package" \
      sha256sum "$recovery_preferences" |
      tr -d '\r' |
      awk '{print $1}'
  )"
fi
backup_ready=true
database_inventory_before="$(database_inventory)"
cache_inventory_before="$(cache_inventory)"
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Package runtime ready:.*Pacman v[0-9]' 30 \
  'ArchpheneRuntime:V AndroidRuntime:E *:S' >/dev/null
initial_ui="$(archphene_capture_ui "package-worker-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "package-worker-onboarding-$serial"
  initial_ui="$ARCHPHENE_UI"
fi
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
archphene_open_manager_section Packages "package-worker-packages-$serial"
package_ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$package_ui" \
  'class="android.widget.EditText"[^>]*(?:text|hint)="Package name"|text="Package name"[^>]*class="android.widget.EditText"' \
  "Package name"
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text worker-boundary-fixture >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_adb_run logcat -c

archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "worker-$serial_slug" \
  --es package worker-boundary-fixture \
  --ei hold-ms 10000 >/dev/null
archphene_wait_log \
  "Started package phases=true token=worker-$serial_slug" 15 \
  'ArchphenePackagePhaseProbe:V AndroidRuntime:E *:S' >/dev/null
archphene_wait_log \
  'Durable package job queued on ArchphenePackagePhases' 15 \
  'ArchpheneRuntime:V AndroidRuntime:E *:S' >/dev/null

archphene_wait_ui_exact_text \
  'Install · Queued · 0%' "package-worker-queued-$serial" 15
[[ "$ARCHPHENE_UI" == *'text="Queued"'* ]] ||
  archphene_die "durably queued package job did not expose immediate progress"
archphene_adb_run exec-out screencap -p >"$screenshot"

if archphene_regex_contains \
  "$ARCHPHENE_UI" \
  'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"[^>]*enabled="true"'; then
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" \
    'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"[^>]*enabled="true"' \
    "Cancel"
else
  archphene_die "queued package job did not expose cancellation"
fi
archphene_wait_ui \
  'Install · Cancelled · [0-9]+%' \
  "package-worker-cancelled-$serial" 15 >/dev/null
[[ "$(database_inventory)" == "$database_inventory_before" ]] ||
  archphene_die "package worker fixture changed the installed package database"
[[ "$(cache_inventory)" == "$cache_inventory_before" ]] ||
  archphene_die "package worker fixture changed the package cache"

archphene_adb_run logcat -d -v threadtime \
  ArchpheneRuntime:V StrictMode:D AndroidRuntime:E libc:F '*:S' >"$raw_log"
python3 - "$raw_log" <<'PY'
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
marker = "StrictMode policy violation; "
owned = []
for index, line in enumerate(lines):
    if marker not in line:
        continue
    block = []
    for candidate in lines[index + 1 :]:
        if marker in candidate:
            break
        block.append(candidate)
    if any("\tat org.archphene." in candidate for candidate in block):
        owned.append("\n".join([line, *block]))
fatal = [
    line
    for line in lines
    if "FATAL EXCEPTION" in line or "Fatal signal" in line
]
if owned:
    print("\n\n".join(owned), file=sys.stderr)
    raise SystemExit(f"{len(owned)} Archphene StrictMode violation(s)")
if fatal:
    print("\n".join(fatal), file=sys.stderr)
    raise SystemExit(f"{len(fatal)} fatal runtime event(s)")
PY

trap - EXIT
cleanup
assert_restored
archphene_note "Archphene package worker boundary passed on $serial"
archphene_note "  Accepted UI state, off-main durable queue, progress, and cancellation passed"
archphene_note "  Existing app data and Linux package database were not cleared"
archphene_note "  Raw log: $raw_log"
archphene_note "  Full-device screenshot: $screenshot"
