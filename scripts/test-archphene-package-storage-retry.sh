#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
package_name=strace
dependency=glibc
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --package) package_name="${2:?missing value for --package}"; shift 2 ;;
    --dependency) dependency="${2:?missing value for --dependency}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk] [--package NAME --dependency NAME]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
for name in "$package_name" "$dependency"; do
  [[ "$name" =~ ^[a-zA-Z0-9@._+-]{1,128}$ ]] ||
    archphene_die "invalid package name: $name"
done

archphene_test_init "$serial"
archphene_require_command python3
archphene_require_command unzip
[[ "$(archphene_adb_run shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] ||
  archphene_die "real storage-pressure gate is intentionally limited to an emulator"

manager=org.archphene.app.debug
activity="$manager/org.archphene.app.MainActivity"
root=files/arch-root
cache="$root/var/cache/pacman/pkg"
local_database="$root/var/lib/pacman/local"
compatibility_cache="$root/var/cache/archphene/package-compatibility-v1"
job_store="$root/var/lib/archphene/package-jobs.v1"
recovery_preferences=shared_prefs/package_recovery.xml
session_marker="$root/var/lib/archphene/session-active-v1"
output_dir="$ARCHPHENE_ROOT/tooling/build/package-storage-pressure"
fixture_prefix=archphene-pressure
fixture_paths=()
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
backup_root="files/test-fixtures/package-storage-retry-$serial_slug"
database_backup="$backup_root/package-database"
cache_backup="$backup_root/package-cache"
compatibility_backup="$backup_root/package-compatibility-v1"
job_backup="$backup_root/package-jobs.v1"
recovery_backup="$backup_root/package-recovery.xml"
build_root="$(mktemp -d "${TMPDIR:-/tmp}/archphene-storage-retry.XXXXXX")"
initially_running=false
original_section=
backup_ready=false
cache_moved=false
cache_created=false
compatibility_moved=false
compatibility_created=false
job_existed=false
job_snapshot_ready=false
recovery_existed=false
recovery_snapshot_ready=false
runtime_ready=false
mutation_started=false
database_inventory_before=
cache_inventory_before=
compatibility_inventory_before=
job_sha_before=
recovery_sha_before=

package_database_inventory() {
  archphene_adb_run shell \
    "run-as $manager sh -c 'cd $local_database && find . -mindepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

package_cache_inventory() {
  if ! archphene_adb_run shell run-as "$manager" test -d "$cache"; then
    printf 'absent\n'
    return
  fi
  archphene_adb_run shell \
    "run-as $manager sh -c 'cd $cache && find . -maxdepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

compatibility_cache_inventory() {
  if ! archphene_adb_run shell run-as "$manager" \
    test -d "$compatibility_cache"; then
    printf 'absent\n'
    return
  fi
  archphene_adb_run shell \
    "run-as $manager sh -c 'cd $compatibility_cache && find . -mindepth 1 -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local center ui x y
  ui="$(archphene_capture_ui "storage-restore-$serial" 2>/dev/null || true)"
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

run_test_pacman() {
  archphene_adb_run shell run-as "$manager" env -i \
    HOME="$absolute_root/home/archphene" \
    TMPDIR="$absolute_root/tmp" \
    PATH="$alias_root:$absolute_root/usr/bin" \
    LANG=C \
    LC_ALL=C \
    GLIBC_TUNABLES=glibc.pthread.rseq=0 \
    LD_PRELOAD="$alias_root/libarchphene_path_bridge.so" \
    ARCHPHENE_RUNTIME_LOADER="$loader" \
    ARCHPHENE_RUNTIME_LIB="$libraries" \
    ARCHPHENE_RUNTIME_COMMAND_DIR="$alias_root" \
    ARCHPHENE_RUNTIME_ROOT="$absolute_root" \
    ARCHPHENE_RUNTIME_PROGRAM_PATH="$pacman" \
    ARCHPHENE_ROOT_IDENTITY=1 \
    "$loader" --library-path "$libraries" "$pacman" \
    --config "$absolute_root/etc/pacman.conf" \
    --root "$absolute_root" \
    --dbpath "$absolute_root/var/lib/pacman" \
    --cachedir "$absolute_root/var/cache/pacman/pkg" \
    --noconfirm --noprogressbar "$@"
}

remove_pressure_fixtures() {
  local path
  for path in "${fixture_paths[@]}"; do
    archphene_adb_run shell run-as "$manager" rm -f "$path" >/dev/null 2>&1 || true
  done
}

cleanup() {
  set +e
  local restore_failed=false target_entry
  remove_pressure_fixtures
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  if [[ "$backup_ready" == true && "$runtime_ready" == true &&
      "$mutation_started" == true ]]; then
    target_entry="$(
      archphene_adb_run exec-out run-as "$manager" find "$local_database" \
        -maxdepth 1 -type d -name "$package_name-*" -print -quit |
        tr -d '\r'
    )"
    if [[ -n "$target_entry" ]]; then
      run_test_pacman -R --nodeps "$package_name" \
        >/dev/null 2>&1 || restore_failed=true
    fi
    if [[ "$restore_failed" == false ]] &&
        archphene_adb_run shell run-as "$manager" \
          test -d "$database_backup"; then
      archphene_adb_run shell run-as "$manager" rm -rf \
        "$local_database" >/dev/null 2>&1 || restore_failed=true
      if [[ "$restore_failed" == false ]]; then
        archphene_adb_run shell run-as "$manager" mv \
          "$database_backup" "$local_database" \
          >/dev/null 2>&1 || restore_failed=true
      fi
    fi
  fi
  if [[ "$backup_ready" == true ]]; then
    if [[ "$cache_moved" == true ]]; then
      archphene_adb_run shell run-as "$manager" rm -rf \
        "$cache" >/dev/null 2>&1 || restore_failed=true
      if archphene_adb_run shell run-as "$manager" mv \
        "$cache_backup" "$cache" >/dev/null 2>&1; then
        cache_moved=false
      else
        restore_failed=true
      fi
    elif [[ "$cache_created" == true ]]; then
      archphene_adb_run shell run-as "$manager" rm -rf \
        "$cache" >/dev/null 2>&1 || restore_failed=true
    fi
    if [[ "$compatibility_moved" == true ]]; then
      archphene_adb_run shell run-as "$manager" rm -rf \
        "$compatibility_cache" >/dev/null 2>&1 || restore_failed=true
      if archphene_adb_run shell run-as "$manager" mv \
        "$compatibility_backup" "$compatibility_cache" >/dev/null 2>&1; then
        compatibility_moved=false
      else
        restore_failed=true
      fi
    elif [[ "$compatibility_created" == true ]]; then
      archphene_adb_run shell run-as "$manager" rm -rf \
        "$compatibility_cache" >/dev/null 2>&1 || restore_failed=true
    fi
    if [[ "$job_snapshot_ready" == true ]]; then
      if [[ "$job_existed" == true ]]; then
        archphene_adb_run shell run-as "$manager" cp -p \
          "$job_backup" "$job_store" >/dev/null 2>&1 || restore_failed=true
      else
        archphene_adb_run shell run-as "$manager" rm -f \
          "$job_store" >/dev/null 2>&1 || restore_failed=true
      fi
    fi
    if [[ "$recovery_snapshot_ready" == true ]]; then
      if [[ "$recovery_existed" == true ]]; then
        archphene_adb_run shell run-as "$manager" cp -p \
          "$recovery_backup" "$recovery_preferences" \
          >/dev/null 2>&1 || restore_failed=true
      else
        archphene_adb_run shell run-as "$manager" rm -f \
          "$recovery_preferences" >/dev/null 2>&1 || restore_failed=true
      fi
    fi
    if [[ "$restore_failed" == false ]]; then
      archphene_adb_run shell run-as "$manager" rm -rf \
        "$backup_root" >/dev/null 2>&1 || restore_failed=true
      if [[ "$restore_failed" == false ]]; then
        backup_ready=false
      fi
    fi
  fi
  case "$build_root" in
    "${TMPDIR:-/tmp}"/archphene-storage-retry.*)
      rm -rf -- "$build_root"
      ;;
  esac
  archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  restore_section >/dev/null 2>&1 || true
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
  [[ "$restore_failed" == false ]]
}
trap cleanup EXIT

assert_restored() {
  local actual target_entry
  [[ "$(package_database_inventory)" == "$database_inventory_before" ]] ||
    archphene_die "storage retry did not restore the package database"
  [[ "$(package_cache_inventory)" == "$cache_inventory_before" ]] ||
    archphene_die "storage retry did not restore the package cache"
  [[ "$(compatibility_cache_inventory)" == "$compatibility_inventory_before" ]] ||
    archphene_die "storage retry did not restore the compatibility cache"
  if [[ "$job_existed" == true ]]; then
    actual="$(
      archphene_adb_run exec-out run-as "$manager" sha256sum "$job_store" |
        tr -d '\r' |
        awk '{print $1}'
    )"
    [[ "$actual" == "$job_sha_before" ]] ||
      archphene_die "storage retry did not restore the package job store"
  else
    archphene_adb_run shell run-as "$manager" test ! -e "$job_store" ||
      archphene_die "storage retry retained a new package job store"
  fi
  if [[ "$recovery_existed" == true ]]; then
    actual="$(
      archphene_adb_run exec-out run-as "$manager" \
        sha256sum "$recovery_preferences" |
        tr -d '\r' |
        awk '{print $1}'
    )"
    [[ "$actual" == "$recovery_sha_before" ]] ||
      archphene_die "storage retry did not restore recovery preferences"
  else
    archphene_adb_run shell run-as "$manager" \
      test ! -e "$recovery_preferences" ||
      archphene_die "storage retry retained new recovery preferences"
  fi
  archphene_adb_run shell run-as "$manager" test ! -e "$backup_root" ||
    archphene_die "storage retry retained its backup directory"
  target_entry="$(
    archphene_adb_run exec-out run-as "$manager" find "$local_database" \
      -maxdepth 1 -type d -name "$package_name-*" -print -quit | tr -d '\r'
  )"
  [[ -z "$target_entry" ]] ||
    archphene_die "storage retry did not restore absent $package_name"
  if [[ "$initially_running" == true ]]; then
    archphene_android_pid "$manager" >/dev/null ||
      archphene_die "storage retry did not restore the running manager"
  else
    ! archphene_android_pid "$manager" >/dev/null 2>&1 ||
      archphene_die "storage retry left a previously stopped manager running"
  fi
}

open_package() {
  local suffix="$1"
  archphene_open_manager_section Packages "storage-$suffix-section-$serial"
  archphene_wait_ui 'class="android.widget.EditText"' \
    "storage-$suffix-field-$serial" 25
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'class="android.widget.EditText"' 'package name'
  archphene_adb_run shell input keycombination 113 29 >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
  archphene_adb_run shell input text "$package_name" >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_tap_ui_pattern \
    "$(archphene_capture_ui "storage-$suffix-details-$serial")" \
    'text="(?:DETAILS|Details)"' Details
}

remove_target() {
  local suffix="$1"
  archphene_wait_ui 'text="(?:Remove|Retry)"[^>]*enabled="true"' \
    "storage-$suffix-remove-$serial" 120
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'text="(?:Remove|Retry)"[^>]*enabled="true"' Remove
  if archphene_wait_ui_optional 'text="Remove unused dependencies\?"' \
    "storage-$suffix-removal-review-$serial" 45; then
    archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Remove all"' 'Remove all'
  fi
  archphene_wait_ui 'text="Remove · Complete · 100%"' \
    "storage-$suffix-removed-$serial" 180
}

if archphene_android_pid "$manager" >/dev/null 2>&1; then
  initially_running=true
fi
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "$manager is not installed; pass --install-apk with --apk"
archphene_adb_run shell run-as "$manager" test ! -e "$backup_root" ||
  archphene_die "storage-retry backup path already exists"
archphene_adb_run shell run-as "$manager" test ! -e "$session_marker" ||
  archphene_die "an active shared shell already exists; stop it before this gate"
target_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$package_name-*" -print -quit | tr -d '\r'
)"
[[ -z "$target_entry" ]] ||
  archphene_die "storage retry requires $package_name to be absent"
dependency_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$dependency-*" -print -quit | tr -d '\r'
)"
[[ -n "$dependency_entry" ]] ||
  archphene_die "storage retry requires installed dependency $dependency"
for residue in \
  "$root/run/package-mutation-v1" \
  "$root/run/package-replacement-repair-v1" \
  "$root/var/lib/pacman/db.lck"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "storage retry began with retained transaction state: $residue"
done

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null
initial_ui="$(archphene_capture_ui "storage-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "storage-onboarding-$serial"
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
archphene_adb_run shell am force-stop "$manager" >/dev/null

database_inventory_before="$(package_database_inventory)"
cache_inventory_before="$(package_cache_inventory)"
compatibility_inventory_before="$(compatibility_cache_inventory)"
archphene_adb_run shell run-as "$manager" mkdir -p "$backup_root"
backup_ready=true
archphene_adb_run shell run-as "$manager" cp -R -p \
  "$local_database" "$database_backup"
if archphene_adb_run shell run-as "$manager" test -f "$job_store"; then
  job_existed=true
  archphene_adb_run shell run-as "$manager" cp -p "$job_store" "$job_backup"
  job_sha_before="$(
    archphene_adb_run exec-out run-as "$manager" sha256sum "$job_store" |
      tr -d '\r' |
      awk '{print $1}'
  )"
fi
job_snapshot_ready=true
if archphene_adb_run shell run-as "$manager" \
  test -f "$recovery_preferences"; then
  recovery_existed=true
  archphene_adb_run shell run-as "$manager" cp -p \
    "$recovery_preferences" "$recovery_backup"
  recovery_sha_before="$(
    archphene_adb_run exec-out run-as "$manager" \
      sha256sum "$recovery_preferences" |
      tr -d '\r' |
      awk '{print $1}'
  )"
fi
recovery_snapshot_ready=true
if archphene_adb_run shell run-as "$manager" test -d "$cache"; then
  archphene_adb_run shell run-as "$manager" mv "$cache" "$cache_backup"
  cache_moved=true
else
  cache_created=true
fi
archphene_adb_run shell run-as "$manager" mkdir -p "$cache"
if archphene_adb_run shell run-as "$manager" test -d "$compatibility_cache"; then
  archphene_adb_run shell run-as "$manager" mv \
    "$compatibility_cache" "$compatibility_backup"
  compatibility_moved=true
else
  compatibility_created=true
fi
archphene_adb_run shell run-as "$manager" mkdir -p "$compatibility_cache"

device_abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$device_abi" in
  x86_64)
    repository_arch=x86_64
    loader_name=ld-linux-x86-64.so.2
    ;;
  *) archphene_die "storage retry requires an x86_64 emulator" ;;
esac
installed_apk_path="$(
  archphene_adb_run shell pm path "$manager" |
    tr -d '\r' |
    sed -n 's/^package://p' |
    head -n1
)"
[[ "$installed_apk_path" == /* ]] ||
  archphene_die "could not resolve the installed manager APK"
installed_apk="$build_root/installed-manager.apk"
archphene_adb_run pull "$installed_apk_path" "$installed_apk" >/dev/null
package_runtime_manifest="$(
  unzip -p "$installed_apk" "assets/package-runtime-$repository_arch.tsv"
)"
pacman_payload="$(
  awk -F '\t' '$2 == "@pacman" { print $3 }' <<<"$package_runtime_manifest"
)"
[[ "$pacman_payload" =~ ^libarchphene_pkg_[0-9a-f]{24}\.so$ ]] ||
  archphene_die "installed manager does not declare one valid pacman payload"
absolute_root="/data/user/0/$manager/$root"
alias_root="$absolute_root/run/package-runtime-v1"
loader="$alias_root/$loader_name"
native="$(
  archphene_adb_run shell run-as "$manager" readlink "$loader" |
    tr -d '\r' |
    xargs dirname
)"
pacman="$native/$pacman_payload"
archphene_adb_run shell run-as "$manager" test -f "$pacman" ||
  archphene_die "installed manager pacman payload is missing"
libraries="$alias_root:$native:$absolute_root/usr/lib:$absolute_root/usr/lib/pulseaudio"
runtime_ready=true

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null
open_package prime
archphene_wait_ui \
  "text=\"[^\"]*/$package_name [^\"]*Dependency closure: 2 packages [^\"]*\"" \
  "storage-prime-resolution-$serial" 60

# Establish a freshly verified cache through the public flow, then return to
# an absent target so the pressure attempt exercises a complete install.
archphene_wait_ui 'text="Install"[^>]*enabled="true"' \
  "storage-prime-install-$serial" 120
mutation_started=true
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Install"[^>]*enabled="true"' Install
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "storage-prime-complete-$serial" 240
remove_target prime

available_kib="$(
  archphene_adb_run shell df -k /data |
    tr -d '\r' |
    awk 'END {print $4}'
)"
[[ "$available_kib" =~ ^[0-9]+$ ]] ||
  archphene_die "could not read emulator free space"
leave_bytes=$((48 * 1024 * 1024))
allocate_bytes=$((available_kib * 1024 - leave_bytes))
((allocate_bytes > 256 * 1024 * 1024)) ||
  archphene_die "emulator has too little free space for a safe pressure fixture"
((allocate_bytes <= 32 * 1024 * 1024 * 1024)) ||
  archphene_die "emulator data volume is too large for this bounded gate"

index=0
while ((allocate_bytes > 0)); do
  chunk_bytes=3000000000
  if ((allocate_bytes < chunk_bytes)); then
    chunk_bytes=$allocate_bytes
  fi
  index=$((index + 1))
  ((index <= 12)) || archphene_die "pressure fixture exceeded its file bound"
  path="$cache/$fixture_prefix-$index-1-1-any.pkg.tar.zst"
  fixture_paths+=("$path")
  archphene_adb_run shell run-as "$manager" \
    fallocate -l "$chunk_bytes" "$path"
  allocate_bytes=$((allocate_bytes - chunk_bytes))
done

open_package pressure
archphene_wait_ui 'text="Install"[^>]*enabled="true"' \
  "storage-pressure-install-$serial" 120
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Install"[^>]*enabled="true"' Install
archphene_wait_ui 'text="Clear cache"' "storage-pressure-failed-$serial" 240
archphene_wait_ui 'text="Not enough Linux storage: [^"]+ is required and [^"]+ is available' \
  "storage-pressure-capacity-$serial" 30
mkdir -p "$output_dir"
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-real-pressure.png"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Clear cache"' 'Clear cache'
archphene_wait_ui 'text="Freed [^"]+ of unrelated downloads and retained this package' \
  "storage-pressure-cleared-$serial" 120
archphene_wait_ui 'text="Review"' "storage-pressure-review-$serial" 30
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-cache-recovered.png"
for path in "${fixture_paths[@]}"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$path" ||
    archphene_die "cache recovery retained pressure fixture: $path"
done
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Review"' Review
archphene_wait_ui 'text="Retry"[^>]*enabled="true"' \
  "storage-pressure-retry-$serial" 120
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="Retry"[^>]*enabled="true"' Retry
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "storage-pressure-complete-$serial" 240
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-retry-complete.png"

for name in "$package_name" "$dependency"; do
  entry="$(
    archphene_adb_run exec-out run-as "$manager" find "$local_database" \
      -maxdepth 1 -type d -name "$name-*" -print -quit | tr -d '\r'
  )"
  [[ -n "$entry" ]] || archphene_die "$name was not installed after storage retry"
done
for residue in \
  "$root/run/package-mutation-v1" \
  "$root/run/package-replacement-repair-v1" \
  "$root/var/lib/pacman/db.lck"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "storage retry retained transaction state: $residue"
done

remove_target restore
[[ "$(package_database_inventory)" == "$database_inventory_before" ]] ||
  archphene_die "ordinary storage-retry cleanup did not restore the package database"
fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"Process: $manager"* ]] ||
  archphene_die "storage retry emitted a manager fatal error: $fatal_log"
trap - EXIT
cleanup || archphene_die "storage retry could not restore its borrowed device state"
assert_restored
archphene_note "Archphene real storage-pressure retry passed on $serial"
archphene_note "  Real capacity rejection, selective cache recovery, Review, signed Retry, and baseline restoration passed"
archphene_note "  Package/cache/job/preferences and manager lifecycle were restored"
archphene_note "  Full-device screenshots: $output_dir/$serial-{real-pressure,cache-recovered,retry-complete}.png"
