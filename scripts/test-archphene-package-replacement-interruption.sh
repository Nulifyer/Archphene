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
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
archphene_require_command makepkg
archphene_require_command python3
archphene_require_command unzip

manager=org.archphene.app.debug
activity="$manager/org.archphene.app.MainActivity"
receiver="$manager/org.archphene.app.PackagePhaseTestReceiver"
target=curl
baseline=wcurl
root=files/arch-root
local_database="$root/var/lib/pacman/local"
intent="$root/run/package-mutation-v1"
replacement_repair="$root/run/package-replacement-repair-v1"
replacement_repair_temp="$root/run/package-replacement-repair-v1.tmp"
replacement_local_temp="$local_database/.archphene-replacement-repair.tmp"
preview="$root/run/package-transaction-preview-v1"
cache="$root/var/cache/pacman/pkg"
compatibility_cache="$root/var/cache/archphene/package-compatibility-v1"
job_store="$root/var/lib/archphene/package-jobs.v1"
recovery_preferences=shared_prefs/package_recovery.xml
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"
backup_root="files/test-fixtures/package-replacement-interruption-$serial_slug"
database_backup="$backup_root/package-database"
cache_backup="$backup_root/package-cache"
compatibility_backup="$backup_root/package-compatibility-v1"
job_backup="$backup_root/package-jobs.v1"
recovery_backup="$backup_root/package-recovery.xml"
output_dir="$ARCHPHENE_ROOT/tooling/build/package-replacement-interruption"
fixture="$ARCHPHENE_ROOT/tests/fixtures/package-replacement-device"
build_root="$(mktemp -d "${TMPDIR:-/tmp}/archphene-replacement-interruption.XXXXXX")"
device_archive=files/wcurl-0.0-1-any.pkg.tar.zst
device_config=files/replacement-pacman.conf
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
original_entry=
original_archive=
original_archive_backup=
original_signature_backup=
runtime_ready=false
package_mutated=false
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
  ui="$(archphene_capture_ui "replacement-restore-$serial" 2>/dev/null || true)"
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
    --config "/data/user/0/$manager/$device_config" \
    --root "$absolute_root" \
    --dbpath "$absolute_root/var/lib/pacman" \
    --cachedir "$absolute_root/var/cache/pacman/pkg" \
    --noconfirm --noprogressbar --noscriptlet --ask 4 \
    --nodeps --nodeps --asexplicit "$@"
}

restore_package_baseline() {
  [[ "$backup_ready" == true && "$runtime_ready" == true &&
    "$package_mutated" == true ]] || return 0
  if [[ -n "$original_archive_backup" ]] &&
      archphene_adb_run shell run-as "$manager" \
        test -f "$original_archive_backup"; then
    run_test_pacman -U "/data/user/0/$manager/$original_archive_backup" \
      >/dev/null 2>&1 || return 1
  fi
  if archphene_adb_run shell run-as "$manager" \
      test -d "$database_backup"; then
    archphene_adb_run shell run-as "$manager" rm -rf \
      "$local_database" || return 1
    archphene_adb_run shell run-as "$manager" mv \
      "$database_backup" "$local_database" || return 1
  fi
  archphene_adb_run shell run-as "$manager" rm -f \
    "$root/usr/share/archphene-test/wcurl-baseline" || return 1
}

cleanup() {
  set +e
  local restore_failed=false
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  restore_package_baseline >/dev/null 2>&1 || restore_failed=true
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
        "$backup_root" >/dev/null 2>&1 || true
      backup_ready=false
    fi
  fi
  archphene_adb_run shell rm -f \
    /data/local/tmp/wcurl-0.0-1-any.pkg.tar.zst \
    /data/local/tmp/archphene-replacement-pacman.conf >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -f \
    "$device_archive" "$device_config" >/dev/null 2>&1 || true
  case "$build_root" in
    "${TMPDIR:-/tmp}"/archphene-replacement-interruption.*)
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
  local actual database_inventory_after
  database_inventory_after="$(package_database_inventory)"
  if [[ "$database_inventory_after" != "$database_inventory_before" ]]; then
    diff -u \
      <(printf '%s\n' "$database_inventory_before") \
      <(printf '%s\n' "$database_inventory_after") >&2 || true
    archphene_die "replacement gate did not restore the package database"
  fi
  [[ "$(package_cache_inventory)" == "$cache_inventory_before" ]] ||
    archphene_die "replacement gate did not restore the package cache"
  [[ "$(compatibility_cache_inventory)" == "$compatibility_inventory_before" ]] ||
    archphene_die "replacement gate did not restore the compatibility cache"
  if [[ "$job_existed" == true ]]; then
    actual="$(
      archphene_adb_run exec-out run-as "$manager" sha256sum "$job_store" |
        tr -d '\r' |
        awk '{print $1}'
    )"
    [[ "$actual" == "$job_sha_before" ]] ||
      archphene_die "replacement gate did not restore the package job store"
  else
    archphene_adb_run shell run-as "$manager" test ! -e "$job_store" ||
      archphene_die "replacement gate retained a new package job store"
  fi
  if [[ "$recovery_existed" == true ]]; then
    actual="$(
      archphene_adb_run exec-out run-as "$manager" \
        sha256sum "$recovery_preferences" |
        tr -d '\r' |
        awk '{print $1}'
    )"
    [[ "$actual" == "$recovery_sha_before" ]] ||
      archphene_die "replacement gate did not restore recovery preferences"
  else
    archphene_adb_run shell run-as "$manager" \
      test ! -e "$recovery_preferences" ||
      archphene_die "replacement gate retained new recovery preferences"
  fi
  archphene_adb_run shell run-as "$manager" test ! -e "$backup_root" ||
    archphene_die "replacement gate retained its backup directory"
  archphene_adb_run shell run-as "$manager" test -x "$root/usr/bin/curl" ||
    archphene_die "replacement gate did not restore curl"
  archphene_adb_run shell run-as "$manager" test ! -e \
    "$root/usr/share/archphene-test/wcurl-baseline" ||
    archphene_die "replacement gate retained its fixture payload"
  if [[ "$initially_running" == true ]]; then
    archphene_android_pid "$manager" >/dev/null ||
      archphene_die "replacement gate did not restore the running manager"
  else
    ! archphene_android_pid "$manager" >/dev/null 2>&1 ||
      archphene_die "replacement gate left a previously stopped manager running"
  fi
}

mkdir -p "$output_dir"
cp "$fixture/PKGBUILD" "$build_root/PKGBUILD"
(
  cd "$build_root"
  makepkg --noconfirm >/dev/null
)
archive="$build_root/wcurl-0.0-1-any.pkg.tar.zst"
archphene_require_file "$archive"

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
  archphene_die "replacement-interruption backup path already exists"
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
initial_ui="$(archphene_capture_ui "replacement-initial-$serial")"
if archphene_regex_contains "$initial_ui" 'text="Connect Android files\?"'; then
  archphene_skip_storage_onboarding "replacement-onboarding-$serial"
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

original_entries="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$target-*" | tr -d '\r'
)"
[[ -n "$original_entries" && "$original_entries" != *$'\n'* ]] ||
  archphene_die "replacement gate requires exactly one installed $target record"
original_entry="$original_entries"
original_version="$(
  archphene_adb_run exec-out run-as "$manager" cat "$original_entry/desc" |
    tr -d '\r' |
    awk '/^%VERSION%$/{getline; print; exit}'
)"
[[ "$original_version" =~ ^[^[:space:]]{1,128}$ ]] ||
  archphene_die "could not read the installed $target version"
original_reason="$(
  archphene_adb_run exec-out run-as "$manager" cat "$original_entry/desc" |
    tr -d '\r' |
    awk '/^%REASON%$/{getline; print; exit}'
)"
[[ -z "$original_reason" || "$original_reason" == 1 ]] ||
  archphene_die "installed $target has an invalid package reason"
original_archives="$(
  archphene_adb_run exec-out run-as "$manager" find "$cache" \
    -maxdepth 1 -type f -name "$target-$original_version-*.pkg.tar.*" \
    ! -name '*.sig' ! -name '*.part' | tr -d '\r'
)"
[[ -n "$original_archives" && "$original_archives" != *$'\n'* ]] ||
  archphene_die "replacement gate requires one cached $target $original_version archive"
original_archive="$original_archives"
archphene_adb_run shell run-as "$manager" test -f "$original_archive.sig" ||
  archphene_die "replacement gate requires the cached $target signature"
database_inventory_before="$(package_database_inventory)"
cache_inventory_before="$(package_cache_inventory)"
compatibility_inventory_before="$(compatibility_cache_inventory)"

archphene_adb_run shell run-as "$manager" mkdir -p "$backup_root"
backup_ready=true
archphene_adb_run shell run-as "$manager" cp -R -p \
  "$local_database" "$database_backup"
archive_basename="${original_archive##*/}"
original_archive_backup="$backup_root/$archive_basename"
original_signature_backup="$original_archive_backup.sig"
archphene_adb_run shell run-as "$manager" cp -p \
  "$original_archive" "$original_archive_backup"
archphene_adb_run shell run-as "$manager" cp -p \
  "$original_archive.sig" "$original_signature_backup"
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
  arm64-v8a)
    repository_arch=aarch64
    loader_name=ld-linux-aarch64.so.1
    ;;
  x86_64)
    repository_arch=x86_64
    loader_name=ld-linux-x86-64.so.2
    ;;
  *) archphene_die "unsupported device ABI: $device_abi" ;;
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

archphene_adb_run push "$archive" /data/local/tmp/wcurl-0.0-1-any.pkg.tar.zst >/dev/null
archphene_adb_run push \
  "$fixture/pacman.conf" /data/local/tmp/archphene-replacement-pacman.conf >/dev/null
archphene_adb_run shell run-as "$manager" cp \
  /data/local/tmp/wcurl-0.0-1-any.pkg.tar.zst "$device_archive"
archphene_adb_run shell run-as "$manager" cp \
  /data/local/tmp/archphene-replacement-pacman.conf "$device_config"

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
package_mutated=true
run_test_pacman -U "/data/user/0/$manager/$device_archive" >/dev/null

archphene_adb_run shell run-as "$manager" test \
  -d "$local_database/wcurl-0.0-1" ||
  archphene_die "test baseline was not installed"
if archphene_adb_run shell run-as "$manager" find "$local_database" \
  -maxdepth 1 -type d -name 'curl-*' | grep -q .; then
  archphene_die "test baseline retained the replaced curl record"
fi
for residue in \
  "$intent" "$replacement_repair" "$replacement_repair_temp" \
  "$replacement_local_temp" "$preview" "$root/var/lib/pacman/db.lck"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "replacement gate began with retained state: $residue"
done

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a org.archphene.app.debug.action.ARM_PACKAGE_PRE_TRANSACTION \
  --es token replacement-interrupt \
  --es package "$target" \
  --ei hold-ms 30000 >/dev/null
archphene_wait_log \
  'Started package phases=true token=replacement-interrupt' 15 \
  'ArchphenePackagePhaseProbe:V *:S' >/dev/null

archphene_open_manager_section Packages "replacement-interrupt-section-$serial"
archphene_wait_ui 'class="android.widget.EditText"' \
  "replacement-interrupt-field-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'class="android.widget.EditText"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$target" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_tap_ui_pattern \
  "$(archphene_capture_ui "replacement-interrupt-search-$serial")" \
  'text="(?:DETAILS|Details)"' 'package details'
archphene_wait_ui \
  'text="[^"]*/curl [^"]+.*Dependency closure: [1-9][0-9]* packages' \
  "replacement-interrupt-resolution-$serial" 40
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="(?:INSTALL|Install)"' Install
archphene_wait_ui 'text="Confirm package replacement"' \
  "replacement-interrupt-review-$serial" 60
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-review.png"
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'text="(?:REPLACE|Replace)"' Replace

deadline=$((SECONDS + 20))
while ((SECONDS < deadline)); do
  if archphene_adb_run shell run-as "$manager" test -f "$intent" &&
      archphene_adb_run shell run-as "$manager" test -d "$replacement_repair"; then
    break
  fi
  sleep 0.2
done
archphene_adb_run shell run-as "$manager" test -f "$intent" ||
  archphene_die "mutation intent was not durable before the test hold"
archphene_adb_run shell run-as "$manager" test -d "$replacement_repair" ||
  archphene_die "replacement snapshot was not durable before the test hold"
archphene_adb_run shell run-as "$manager" test -d "$local_database/wcurl-0.0-1" ||
  archphene_die "pacman began before the pre-transaction hold"

android_pid="$(archphene_android_pid "$manager")"
archphene_adb_run shell run-as "$manager" kill -9 "$android_pid" >/dev/null
deadline=$((SECONDS + 15))
while archphene_android_pid "$manager" >/dev/null 2>&1 && ((SECONDS < deadline)); do
  sleep 0.2
done
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  archphene_die "manager survived replacement-boundary SIGKILL"
fi

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Packages "replacement-repair-section-$serial"
archphene_wait_ui 'class="android.widget.EditText"' \
  "replacement-repair-field-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'class="android.widget.EditText"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$target" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Install · Failed · 97%"' \
  "replacement-repair-failed-$serial" 30
archphene_wait_ui 'text="Repair"' "replacement-repair-action-$serial" 15
archphene_wait_ui 'Package mutation was interrupted' \
  "replacement-repair-message-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-interrupted.png"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Repair"' Repair
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "replacement-repair-complete-$serial" 120
archphene_wait_ui 'text="Repaired package transaction for curl"' \
  "replacement-repair-result-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-repaired.png"

curl_entries="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name 'curl-*' | tr -d '\r'
)"
[[ "$(wc -l <<<"$curl_entries")" == 1 ]] ||
  archphene_die "repair did not retain exactly one curl database record"
[[ "$curl_entries" == "$local_database/curl-"* ]] ||
  archphene_die "repair produced an invalid curl database record"
archphene_adb_run shell run-as "$manager" test ! -e \
  "$local_database/wcurl-0.0-1" ||
  archphene_die "repair retained the replaced baseline"
for residue in \
  "$intent" "$replacement_repair" "$replacement_repair_temp" \
  "$replacement_local_temp" "$preview" "$root/var/lib/pacman/db.lck"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "successful replacement repair retained state: $residue"
done

fatal_log="$(
  archphene_adb_run logcat -d -v brief -s AndroidRuntime:E libc:F '*:S' \
    2>/dev/null || true
)"
if [[ "$fatal_log" == *"Process: $manager"* ]]; then
  archphene_die "replacement repair emitted a manager fatal error: $fatal_log"
fi

trap - EXIT
cleanup || archphene_die \
  "replacement gate could not restore the borrowed package baseline"
assert_restored
archphene_note "Interrupted package replacement repair passed on $serial"
archphene_note "  SIGKILL occurred after the durable replacement snapshot and before pacman"
archphene_note "  Repair restored the baseline, completed official curl, and cleared all residue"
archphene_note "  Original package/cache/job/preferences and manager lifecycle were restored"
archphene_note "  Full-device screenshots: $output_dir/$serial-{review,interrupted,repaired}.png"
