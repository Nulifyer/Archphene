#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
damage=filesystem
target=foot
target_file_relative=usr/bin/foot
target_repository=extra
expected_scriptlet_log=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --damage) damage="${2:?missing value for --damage}"; shift 2 ;;
    --package) target="${2:?missing value for --package}"; shift 2 ;;
    --file) target_file_relative="${2:?missing value for --file}"; shift 2 ;;
    --repository) target_repository="${2:?missing value for --repository}"; shift 2 ;;
    --expected-scriptlet-log) expected_scriptlet_log="${2:?missing value for --expected-scriptlet-log}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk] [--damage filesystem|database|database-multi] [--package NAME --file ROOT_RELATIVE_FILE --repository core|extra] [--expected-scriptlet-log TEXT]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$damage" == filesystem || "$damage" == database || "$damage" == database-multi ]] ||
  archphene_die "--damage must be filesystem, database, or database-multi"
[[ "$target" =~ ^[a-zA-Z0-9@._+-]{1,128}$ ]] ||
  archphene_die "invalid target package"
[[ "$target_file_relative" =~ ^[a-zA-Z0-9@._+/-]{1,1024}$ &&
  "$target_file_relative" != /* && "$target_file_relative" != *".."* ]] ||
  archphene_die "invalid target file"
[[ "$target_repository" == core || "$target_repository" == extra ]] ||
  archphene_die "--repository must be core or extra"

archphene_test_init "$serial"
manager=org.archphene.app.debug
activity="$manager/org.archphene.app.MainActivity"
receiver="$manager/org.archphene.app.PackageJobTestReceiver"
seed_action=org.archphene.app.debug.action.SEED_PACKAGE_JOB
root=files/arch-root
local_database="$root/var/lib/pacman/local"
cache="$root/var/cache/pacman/pkg"
pacman_log="$root/var/log/pacman.log"
intent="$root/run/package-mutation-v1"
reason_intent="$root/run/package-install-reasons-v1"
target_file="$root/$target_file_relative"
backup_root="files/test-fixtures/$target-partial-recovery-$serial"
file_backup="$backup_root/executable"
database_backup="$backup_root/database"
base_database_backup="$backup_root/base-database"
target_entry=
base_entry=
output_dir="$ARCHPHENE_ROOT/tooling/build/package-$damage-recovery"
initially_running=false
original_section=
mkdir -p "$output_dir"

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local center ui x y
  ui="$(archphene_capture_ui "partial-recovery-restore-$serial" 2>/dev/null || true)"
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
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  if ! archphene_adb_run shell run-as "$manager" test -x "$target_file" 2>/dev/null &&
      archphene_adb_run shell run-as "$manager" test -f "$file_backup" 2>/dev/null; then
    archphene_adb_run shell run-as "$manager" cp -p "$file_backup" "$target_file" \
      >/dev/null 2>&1 || true
  fi
  if [[ -n "$target_entry" ]] &&
      ! archphene_adb_run shell run-as "$manager" test -f "$target_entry/desc" 2>/dev/null &&
      archphene_adb_run shell run-as "$manager" test -f "$database_backup/desc" 2>/dev/null; then
    archphene_adb_run shell run-as "$manager" mkdir -p "$target_entry" \
      >/dev/null 2>&1 || true
    for database_file in desc files mtree; do
      if archphene_adb_run shell run-as "$manager" \
        test -f "$database_backup/$database_file" 2>/dev/null; then
        archphene_adb_run shell run-as "$manager" cp -p \
          "$database_backup/$database_file" "$target_entry/$database_file" \
          >/dev/null 2>&1 || true
      fi
    done
  fi
  if [[ -n "$base_entry" ]] &&
      ! archphene_adb_run shell run-as "$manager" test -f "$base_entry/desc" 2>/dev/null &&
      archphene_adb_run shell run-as "$manager" test -f "$base_database_backup/desc" 2>/dev/null; then
    archphene_adb_run shell run-as "$manager" mkdir -p "$base_entry" \
      >/dev/null 2>&1 || true
    for database_file in desc files mtree; do
      if archphene_adb_run shell run-as "$manager" \
        test -f "$base_database_backup/$database_file" 2>/dev/null; then
        archphene_adb_run shell run-as "$manager" cp -p \
          "$base_database_backup/$database_file" "$base_entry/$database_file" \
          >/dev/null 2>&1 || true
      fi
    done
  fi
  archphene_adb_run shell run-as "$manager" rm -f \
    "$file_backup" \
    "$database_backup/desc" \
    "$database_backup/files" \
    "$database_backup/mtree" \
    "$base_database_backup/desc" \
    "$base_database_backup/files" \
    "$base_database_backup/mtree" >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rmdir \
    "$database_backup" "$base_database_backup" "$backup_root" >/dev/null 2>&1 || true
  archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  restore_section >/dev/null 2>&1 || true
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

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
  archphene_die "partial-recovery backup path already exists"
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
initial_ui="$(archphene_capture_ui "partial-recovery-initial-$serial")"
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
archphene_adb_run shell run-as "$manager" test -x "$target_file" ||
  archphene_die "$target must be installed before the partial-recovery gate"
archphene_adb_run shell run-as "$manager" test ! -e "$intent" ||
  archphene_die "an existing package mutation must be repaired first"
archphene_adb_run shell run-as "$manager" test ! -e "$reason_intent" ||
  archphene_die "an existing install-reason intent must be repaired first"
pacman_log_lines_before="$(
  archphene_adb_run exec-out run-as "$manager" cat "$pacman_log" 2>/dev/null |
    wc -l
)"

device_abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$device_abi" in
  arm64-v8a)
    core_prefix=https://ca.us.mirror.archlinuxarm.org/aarch64/core/
    extra_prefix=https://ca.us.mirror.archlinuxarm.org/aarch64/extra/
    ;;
  x86_64)
    core_prefix=https://geo.mirror.pkgbuild.com/core/os/x86_64/
    extra_prefix=https://geo.mirror.pkgbuild.com/extra/os/x86_64/
    ;;
  *) archphene_die "unsupported device ABI: $device_abi" ;;
esac

package_record() {
  local package_name="$1"
  local repository="$2"
  local prefix="$3"
  local entry version archive filename size reason
  entry="$(
    archphene_adb_run exec-out run-as "$manager" find "$local_database" \
      -maxdepth 1 -type d -name "$package_name-*" | tr -d '\r'
  )"
  [[ -n "$entry" && "$entry" != *$'\n'* ]] ||
    archphene_die "expected one installed $package_name record"
  version="$(
    archphene_adb_run exec-out run-as "$manager" cat "$entry/desc" |
      tr -d '\r' |
      awk '/^%VERSION%$/{getline; print; exit}'
  )"
  [[ "$version" =~ ^[^[:space:]/]{1,128}$ ]] ||
    archphene_die "invalid installed $package_name version"
  reason="$(
    archphene_adb_run exec-out run-as "$manager" cat "$entry/desc" |
      tr -d '\r' |
      awk '/^%REASON%$/{getline; print; exit}'
  )"
  [[ -z "$reason" || "$reason" == 1 ]] ||
    archphene_die "$package_name has an invalid install reason"
  archive="$(
    archphene_adb_run exec-out run-as "$manager" find "$cache" \
      -maxdepth 1 -type f -name "$package_name-$version-*.pkg.tar.*" \
      ! -name '*.sig' | tr -d '\r'
  )"
  [[ -n "$archive" && "$archive" != *$'\n'* ]] ||
    archphene_die "expected one retained $package_name $version archive"
  archphene_adb_run shell run-as "$manager" test -f "$archive.sig" ||
    archphene_die "$package_name archive has no detached signature"
  filename="${archive##*/}"
  size="$(archphene_adb_run shell run-as "$manager" stat -c %s "$archive" | tr -d '\r')"
  [[ "$size" =~ ^[1-9][0-9]*$ ]] || archphene_die "invalid $package_name archive size"
  printf '%s\t%s\t%s\t%s\t%s%s\t%s\n' \
    "$repository" "$package_name" "$version" "$filename" "$prefix" "$filename" "$size"
}

base_record="$(package_record base core "$core_prefix")"
if [[ "$target_repository" == core ]]; then
  target_record="$(package_record "$target" core "$core_prefix")"
else
  target_record="$(package_record "$target" extra "$extra_prefix")"
fi
base_version="$(cut -f3 <<<"$base_record")"
target_version="$(cut -f3 <<<"$target_record")"
base_entry="$local_database/base-$base_version"
target_entry="$local_database/$target-$target_version"
original_target_reason="$(
  archphene_adb_run exec-out run-as "$manager" cat "$target_entry/desc" |
    tr -d '\r' |
    awk '/^%REASON%$/{getline; print; exit}'
)"
[[ -z "$original_target_reason" || "$original_target_reason" == 1 ]] ||
  archphene_die "$target has an invalid install reason"
intent_content="$(
  printf 'org.archphene.package-mutation.v1\n'
  printf 'install\t%s\n' "$target"
  printf 'explicit\tbase\n'
  if [[ -z "$original_target_reason" ]]; then
    printf 'explicit\t%s\n' "$target"
  fi
  printf 'archive\t%s\n' "$base_record"
  printf 'archive\t%s\n' "$target_record"
)"
intent_content+=$'\n'
reason_content="$(
  printf 'org.archphene.package-install-reasons.v1\n'
  printf 'base\n'
  if [[ -z "$original_target_reason" ]]; then
    printf '%s\n' "$target"
  fi
)"
reason_content+=$'\n'

archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run shell run-as "$manager" mkdir -p \
  "$database_backup" "$base_database_backup"
archphene_adb_run shell run-as "$manager" cp -p "$target_file" "$file_backup"
for database_file in desc files mtree; do
  if archphene_adb_run shell run-as "$manager" \
    test -f "$target_entry/$database_file"; then
    archphene_adb_run shell run-as "$manager" cp -p \
      "$target_entry/$database_file" "$database_backup/$database_file"
  fi
  if archphene_adb_run shell run-as "$manager" \
    test -f "$base_entry/$database_file"; then
    archphene_adb_run shell run-as "$manager" cp -p \
      "$base_entry/$database_file" "$base_database_backup/$database_file"
  fi
done
printf '%s' "$intent_content" |
  archphene_adb_run shell run-as "$manager" tee "$intent.tmp" >/dev/null
archphene_adb_run shell run-as "$manager" chmod 600 "$intent.tmp"
archphene_adb_run shell run-as "$manager" mv "$intent.tmp" "$intent"
printf '%s' "$reason_content" |
  archphene_adb_run shell run-as "$manager" tee "$reason_intent.tmp" >/dev/null
archphene_adb_run shell run-as "$manager" chmod 600 "$reason_intent.tmp"
archphene_adb_run shell run-as "$manager" mv "$reason_intent.tmp" "$reason_intent"
archphene_adb_run shell run-as "$manager" rm "$target_file"
if [[ "$damage" == database || "$damage" == database-multi ]]; then
  archphene_adb_run shell run-as "$manager" rm "$target_entry/desc"
fi
if [[ "$damage" == database-multi ]]; then
  archphene_adb_run shell run-as "$manager" rm "$base_entry/desc"
fi
archphene_adb_run shell run-as "$manager" test ! -e "$target_file" ||
  archphene_die "could not establish the partial package filesystem"
if [[ "$damage" == database || "$damage" == database-multi ]]; then
  archphene_adb_run shell run-as "$manager" test ! -e "$target_entry/desc" ||
    archphene_die "could not establish the partial package database"
fi
if [[ "$damage" == database-multi ]]; then
  archphene_adb_run shell run-as "$manager" test ! -e "$base_entry/desc" ||
    archphene_die "could not establish the multi-entry partial package database"
fi

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$seed_action" \
  --es token partial-install-recovery \
  --es package "$target" \
  --es state failed \
  --es operation install \
  --es failure mutation >/dev/null
archphene_wait_log \
  'Seeded package job state=failed token=partial-install-recovery' 20 \
  'ArchphenePackageJobProbe:V *:S' >/dev/null

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
current_focus="$(archphene_adb_run shell dumpsys window | grep -m1 'mCurrentFocus=' || true)"
[[ "$current_focus" == *"$activity"* ]] ||
  archphene_die \
    "an interrupted package mutation launched another Android activity: $current_focus"
archphene_open_manager_section Packages "partial-recovery-packages-$serial"
archphene_wait_ui 'class="android.widget.EditText"' "partial-recovery-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'class="android.widget.EditText"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$target" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Install · Failed · 97%"' "partial-recovery-failed-$serial" 20
archphene_wait_ui 'text="Repair"[^>]*enabled="true"' "partial-recovery-action-$serial" 15
archphene_wait_ui 'Package mutation was interrupted' "partial-recovery-message-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-interrupted.png"

archphene_adb_run logcat -c
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Repair"[^>]*enabled="true"' 'Repair'
archphene_wait_log "Repaired interrupted package transaction for $target" 120 >/dev/null
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "partial-recovery-complete-$serial" 20
archphene_wait_ui "text=\"Repaired package transaction for $target\"" \
  "partial-recovery-result-$serial" 15
# Accessibility text can publish one frame before TextView layout/draw catches
# up. Give the device a bounded render interval so the visual artifact records
# the settled phase label rather than the preceding horizontal position.
sleep 0.5
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-repaired.png"

archphene_adb_run shell run-as "$manager" test -x "$target_file" ||
  archphene_die "Repair did not restore $target_file from the retained archive"
for database_file in desc files mtree; do
  archphene_adb_run shell run-as "$manager" test -f "$target_entry/$database_file" ||
    archphene_die "Repair did not restore $target database file: $database_file"
  if [[ "$damage" == database-multi ]]; then
    archphene_adb_run shell run-as "$manager" test -f "$base_entry/$database_file" ||
      archphene_die "Repair did not restore base database file: $database_file"
  fi
done
local_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$target-*" | tr -d '\r'
)"
[[ "$local_entry" == "$local_database/$target-$target_version" ]] ||
  archphene_die "Repair changed the exact installed $target version"
target_reason="$(
  archphene_adb_run exec-out run-as "$manager" cat "$local_entry/desc" |
    tr -d '\r' |
    awk '/^%REASON%$/{getline; print; exit}'
)"
[[ "$target_reason" == "$original_target_reason" ]] ||
  archphene_die "Repair changed $target install reason"
for residue in "$intent" "$intent.tmp" "$reason_intent" "$reason_intent.tmp" \
  "$root/run/package-database-repair-v1" \
  "$root/var/lib/pacman/db.lck"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "Repair left transaction residue: $residue"
done
new_pacman_log="$(
  archphene_adb_run exec-out run-as "$manager" cat "$pacman_log" |
    tail -n "+$((pacman_log_lines_before + 1))"
)"
if [[ -n "$expected_scriptlet_log" ]] &&
    ! grep -F "$expected_scriptlet_log" <<<"$new_pacman_log" >/dev/null; then
  archphene_die "Repair did not execute the expected signed package scriptlet"
fi
for rejected_scriptlet_log in \
  'command not found' 'arithmetic syntax error' 'command failed to execute correctly'; do
  if grep -F "$rejected_scriptlet_log" <<<"$new_pacman_log" >/dev/null; then
    archphene_die \
      "Repair scriptlet command environment failed: $rejected_scriptlet_log"
  fi
done
partial="$(
  archphene_adb_run exec-out run-as "$manager" find "$cache" \
    -maxdepth 1 -type f -name '*.part' -print -quit | tr -d '\r'
)"
[[ -z "$partial" ]] || archphene_die "Repair left a partial cache payload"

fatal_log="$(
  archphene_adb_run logcat -d -v brief -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "partial package repair emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
if [[ "$initially_running" == true ]]; then
  archphene_android_pid "$manager" >/dev/null ||
    archphene_die "partial recovery did not restore the running manager"
else
  ! archphene_android_pid "$manager" >/dev/null 2>&1 ||
    archphene_die "partial recovery left a previously stopped manager running"
fi
archphene_note "Archphene partial-package repair passed on $serial"
archphene_note "  Damage model: $damage"
archphene_note "  Missing package state converged from retained signed archives"
archphene_note "  Exact version/reason and transaction cleanup passed"
archphene_note "  Full-device screenshots: $output_dir/$serial-{interrupted,repaired}.png"
