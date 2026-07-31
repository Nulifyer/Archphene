#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
old_archive=
old_signature=
old_version=
new_version=
install_apk=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --old-archive) old_archive="${2:?missing value for --old-archive}"; shift 2 ;;
    --old-signature) old_signature="${2:?missing value for --old-signature}"; shift 2 ;;
    --old-version) old_version="${2:?missing value for --old-version}"; shift 2 ;;
    --new-version) new_version="${2:?missing value for --new-version}"; shift 2 ;;
    --install-apk) install_apk=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --old-archive PATH --old-signature PATH --old-version VERSION --new-version VERSION [--install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ -n "$old_archive" ]] || archphene_die "--old-archive is required"
[[ -n "$old_signature" ]] || archphene_die "--old-signature is required"
[[ "$old_version" =~ ^[^[:space:]/]{1,128}$ ]] ||
  archphene_die "--old-version is invalid"
[[ "$new_version" =~ ^[^[:space:]/]{1,128}$ ]] ||
  archphene_die "--new-version is invalid"
[[ "$old_version" != "$new_version" ]] ||
  archphene_die "old and new versions must differ"

archphene_test_init "$serial"
archphene_require_file "$apk"
archphene_require_file "$old_archive"
archphene_require_file "$old_signature"
archphene_require_command sha256sum
archphene_require_command unzip

manager=org.archphene.app.debug
activity="$manager/org.archphene.app.MainActivity"
receiver="$manager/org.archphene.app.PackagePhaseTestReceiver"
package_name=zsh
script_file=etc/shells
script_line=/usr/bin/zsh
root=files/arch-root
cache="$root/var/cache/pacman/pkg"
local_database="$root/var/lib/pacman/local"
intent="$root/run/package-mutation-v1"
reason_intent="$root/run/package-install-reasons-v1"
database_repair="$root/run/package-database-repair-v1"
replacement_repair="$root/run/package-replacement-repair-v1"
database_lock="$root/var/lib/pacman/db.lck"
script_path="$root/$script_file"
run_id="$RANDOM-$RANDOM-$$"
backup_root="files/test-fixtures/signed-scriptlet-rollback-$serial-$run_id"
script_backup="$backup_root/shells"
job_store="$root/var/lib/archphene/package-jobs.v1"
job_backup="$backup_root/package-jobs.v1"
recovery_preferences=shared_prefs/package_recovery.xml
recovery_backup="$backup_root/package-recovery.xml"
output_dir="$ARCHPHENE_ROOT/tooling/build/signed-scriptlet-rollback/$serial"
mkdir -p "$output_dir"

old_archive_name="${old_archive##*/}"
old_signature_name="${old_signature##*/}"
old_archive_temporary="/data/local/tmp/archphene-$run_id-$old_archive_name"
old_signature_temporary="/data/local/tmp/archphene-$run_id-$old_signature_name"
[[ "$old_signature_name" == "$old_archive_name.sig" ]] ||
  archphene_die "older signature must be named $old_archive_name.sig"

device_abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$device_abi" in
  arm64-v8a)
    repository_arch=aarch64
    loader_name=ld-linux-aarch64.so.1
    archive_suffix=pkg.tar.xz
    ;;
  x86_64)
    repository_arch=x86_64
    loader_name=ld-linux-x86-64.so.2
    archive_suffix=pkg.tar.zst
    ;;
  *) archphene_die "unsupported device ABI: $device_abi" ;;
esac
[[ "$old_archive_name" == "$package_name-$old_version-$repository_arch.$archive_suffix" ]] ||
  archphene_die \
    "older archive does not match zsh $old_version for $repository_arch"

package_runtime_manifest="$(
  unzip -p "$apk" "assets/package-runtime-$repository_arch.tsv"
)"
pacman_payload="$(
  awk -F '\t' '$2 == "@pacman" { print $3 }' <<<"$package_runtime_manifest"
)"
gpgv_payload="$(
  awk -F '\t' '$2 == "@gpgv" { print $3 }' <<<"$package_runtime_manifest"
)"
bsdtar_payload="$(
  awk -F '\t' '$2 == "@bsdtar" { print $3 }' <<<"$package_runtime_manifest"
)"
for payload in "$pacman_payload" "$gpgv_payload" "$bsdtar_payload"; do
  [[ "$payload" =~ ^libarchphene_pkg_[0-9a-f]{24}\.so$ ]] ||
    archphene_die "APK package-runtime manifest is invalid"
done

manager_was_running=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_was_running=true
fi
early_setup=true
restore_early_lifecycle() {
  local status=$?
  trap - EXIT
  if [[ "$early_setup" == true && "$manager_was_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap restore_early_lifecycle EXIT
if [[ "$install_apk" == true ]]; then
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "$manager is not installed; pass --install-apk"
app_root="$(
  archphene_adb_run exec-out run-as "$manager" pwd 2>/dev/null | tr -d '\r'
)"
[[ "$app_root" =~ ^/data/(user/0|data)/org\.archphene\.app\.debug$ ]] ||
  archphene_die "manager application root is invalid: $app_root"
runtime_root="$app_root/$root"
runtime_alias="$runtime_root/run/package-runtime-v1"
runtime_loader="$runtime_alias/$loader_name"
runtime_keyring="$runtime_root/run/package-trust-v1/pubring.kbx"

runtime_command() {
  local payload="$1"
  shift
  local quoted= argument
  for argument in "$@"; do
    printf -v argument '%q' "$argument"
    quoted+=" $argument"
  done
  local command
  command="root=$runtime_root; alias=$runtime_alias; loader=$runtime_loader; native=\$(dirname \"\$(readlink \"\$loader\")\"); tool=\"\$native/$payload\"; libs=\"\$alias:\$native:\$root/usr/lib:\$root/usr/lib/pulseaudio\"; exec env -i HOME=\"\$root/home/archphene\" TMPDIR=\"\$root/tmp\" PATH=\"\$alias:\$root/usr/bin\" LANG=C LC_ALL=C GLIBC_TUNABLES=glibc.pthread.rseq=0 LD_PRELOAD=\"\$alias/libarchphene_path_bridge.so\" ARCHPHENE_RUNTIME_LOADER=\"\$loader\" ARCHPHENE_RUNTIME_LIB=\"\$libs\" ARCHPHENE_RUNTIME_COMMAND_DIR=\"\$alias\" ARCHPHENE_RUNTIME_ROOT=\"\$root\" ARCHPHENE_RUNTIME_PROGRAM_PATH=\"\$tool\" ARCHPHENE_ROOT_IDENTITY=1 \"\$loader\" --library-path \"\$libs\" \"\$tool\"$quoted"
  archphene_adb_run exec-out run-as "$manager" sh -c "$command"
}

verify_archive() {
  local archive="$1" signature="$2" status
  status="$(
    runtime_command "$gpgv_payload" \
      --keyring "$runtime_keyring" \
      --status-fd 1 \
      "$signature" "$archive" 2>&1
  )"
  archphene_regex_contains \
    "$status" '\[GNUPG:\] VALIDSIG (?:[0-9A-F]{40}|[0-9A-F]{64}) ' ||
    archphene_die "package archive did not produce an exact valid signature"
  if archphene_regex_contains \
      "$status" \
      '\[GNUPG:\] (?:BADSIG|ERRSIG|REVKEYSIG|EXPKEYSIG|KEYEXPIRED|SIGEXPIRED)'; then
    archphene_die "package archive produced a rejected signature status"
  fi
}

extract_archive_file() {
  runtime_command "$bsdtar_payload" -xOf "$1" "$2" | tr -d '\r'
}

pacman_archive() {
  local archive="$1" reason_flag="$2"
  shift 2
  runtime_command "$pacman_payload" \
    --config "$runtime_root/etc/pacman.conf" \
    --root "$runtime_root" \
    --dbpath "$runtime_root/var/lib/pacman" \
    --gpgdir "$runtime_root/run/package-trust-v1" \
    --cachedir "$runtime_root/var/cache/pacman/pkg" \
    --noconfirm --noprogressbar \
    "$@" "$reason_flag" -U "$archive"
}

installed_entry() {
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$package_name-*" | tr -d '\r'
}

installed_reason() {
  archphene_adb_run exec-out run-as "$manager" cat "$1/desc" |
    tr -d '\r' |
    awk '/^%REASON%$/{getline; print; exit}'
}

line_count() {
  archphene_adb_run exec-out run-as "$manager" cat "$script_path" |
    grep -Fxc "$script_line" ||
    true
}

introduced_archive=false
introduced_signature=false
old_archive_temporary_owned=false
old_signature_temporary_owned=false
backup_root_owned=false
backup_ready=false
backup_hash=
job_existed=false
job_snapshot_ready=false
job_hash=
recovery_existed=false
recovery_snapshot_ready=false
recovery_hash=
original_section=
runtime_ready=false
mutation_started=false
current_archive=
current_signature=
current_reason_flag=

cleanup() {
  local status=$?
  local cleanup_failed=false
  local actual_hash=
  local -a temporaries=()
  set +e
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1
  if [[ "$mutation_started" == true && "$runtime_ready" == true &&
        -n "$current_archive" ]]; then
    archphene_adb_run shell run-as "$manager" rm -f \
      "$intent" "$reason_intent" "$database_repair" "$replacement_repair" \
      "$database_lock" >/dev/null 2>&1
    if ! pacman_archive "$current_archive" "$current_reason_flag" \
        >/dev/null 2>&1; then
      cleanup_failed=true
      printf 'error: could not restore current zsh during cleanup\n' >&2
    fi
  fi
  if [[ "$backup_ready" == true ]]; then
    if ! archphene_adb_run shell run-as "$manager" cp -p \
        "$script_backup" "$script_path" >/dev/null 2>&1; then
      cleanup_failed=true
      printf 'error: could not restore %s during cleanup\n' "$script_file" >&2
    fi
  fi
  if [[ "$job_snapshot_ready" == true ]]; then
    if [[ "$job_existed" == true ]]; then
      if ! archphene_adb_run shell run-as "$manager" cp -p \
          "$job_backup" "$job_store" >/dev/null 2>&1; then
        cleanup_failed=true
        printf 'error: could not restore package job state\n' >&2
      fi
    elif ! archphene_adb_run shell run-as "$manager" rm -f \
        "$job_store" >/dev/null 2>&1; then
      cleanup_failed=true
    fi
  fi
  if [[ "$recovery_snapshot_ready" == true ]]; then
    if [[ "$recovery_existed" == true ]]; then
      if ! archphene_adb_run shell run-as "$manager" cp -p \
          "$recovery_backup" "$recovery_preferences" >/dev/null 2>&1; then
        cleanup_failed=true
        printf 'error: could not restore package recovery preferences\n' >&2
      fi
    elif ! archphene_adb_run shell run-as "$manager" rm -f \
        "$recovery_preferences" >/dev/null 2>&1; then
      cleanup_failed=true
    fi
  fi
  if [[ "$job_existed" == true ]]; then
    actual_hash="$(
      archphene_adb_run exec-out run-as "$manager" sha256sum "$job_store" \
        2>/dev/null |
        tr -d '\r' |
        awk '{print $1}'
    )"
    if [[ "$actual_hash" != "$job_hash" ]]; then
      cleanup_failed=true
      printf 'error: restored package job state has the wrong digest\n' >&2
    fi
  elif [[ "$job_snapshot_ready" == true ]] &&
      archphene_adb_run shell run-as "$manager" test -e "$job_store"; then
    cleanup_failed=true
    printf 'error: cleanup retained a new package job store\n' >&2
  fi
  if [[ "$recovery_existed" == true ]]; then
    actual_hash="$(
      archphene_adb_run exec-out run-as "$manager" \
        sha256sum "$recovery_preferences" 2>/dev/null |
        tr -d '\r' |
        awk '{print $1}'
    )"
    if [[ "$actual_hash" != "$recovery_hash" ]]; then
      cleanup_failed=true
      printf 'error: restored recovery preferences have the wrong digest\n' >&2
    fi
  elif [[ "$recovery_snapshot_ready" == true ]] &&
      archphene_adb_run shell run-as "$manager" test -e "$recovery_preferences"; then
    cleanup_failed=true
    printf 'error: cleanup retained new recovery preferences\n' >&2
  fi
  if [[ "$introduced_archive" == true ]]; then
    if ! archphene_adb_run shell run-as "$manager" rm -f \
        "$cache/$old_archive_name" >/dev/null 2>&1 ||
        archphene_adb_run shell run-as "$manager" test -e \
          "$cache/$old_archive_name"; then
      cleanup_failed=true
      printf 'error: could not remove introduced %s\n' "$old_archive_name" >&2
    fi
  fi
  if [[ "$introduced_signature" == true ]]; then
    if ! archphene_adb_run shell run-as "$manager" rm -f \
        "$cache/$old_signature_name" >/dev/null 2>&1 ||
        archphene_adb_run shell run-as "$manager" test -e \
          "$cache/$old_signature_name"; then
      cleanup_failed=true
      printf 'error: could not remove introduced %s\n' "$old_signature_name" >&2
    fi
  fi
  if [[ "$backup_root_owned" == true ]]; then
    archphene_adb_run shell run-as "$manager" rm -f \
      "$script_backup" "$job_backup" "$recovery_backup" >/dev/null 2>&1
    if ! archphene_adb_run shell run-as "$manager" rmdir \
        "$backup_root" >/dev/null 2>&1 ||
        archphene_adb_run shell run-as "$manager" test -e "$backup_root"; then
      cleanup_failed=true
      printf 'error: could not remove rollback backup directory\n' >&2
    fi
  fi
  if [[ "$old_archive_temporary_owned" == true ]]; then
    temporaries+=("$old_archive_temporary")
  fi
  if [[ "$old_signature_temporary_owned" == true ]]; then
    temporaries+=("$old_signature_temporary")
  fi
  for temporary in "${temporaries[@]}"; do
    if ! archphene_adb_run shell rm -f "$temporary" >/dev/null 2>&1 ||
        archphene_adb_run shell test -e "$temporary"; then
      cleanup_failed=true
      printf 'error: could not remove staging file %s\n' "$temporary" >&2
    fi
  done
  if [[ -n "$original_section" ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1
    sleep 1
    local ui center x y
    ui="$(archphene_capture_ui "scriptlet-restore-$serial" 2>/dev/null || true)"
    center="$(
      archphene_ui_node_center \
        "$ui" \
        "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
        "$original_section" 2>/dev/null || true
    )"
    if [[ "$center" =~ ^[0-9]+[[:space:]][0-9]+$ ]]; then
      read -r x y <<<"$center"
      archphene_adb_run shell input tap "$x" "$y" >/dev/null 2>&1
      sleep 1
      ui="$(archphene_capture_ui "scriptlet-restored-$serial" 2>/dev/null || true)"
      if ! archphene_regex_contains "$ui" \
          "text=\"$original_section\"[^>]*selected=\"true\""; then
        cleanup_failed=true
        printf 'error: manager section %s did not restore\n' "$original_section" >&2
      fi
    else
      cleanup_failed=true
      printf 'error: could not restore manager section %s\n' "$original_section" >&2
    fi
  elif [[ "$manager_was_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 ||
      cleanup_failed=true
  fi
  if [[ "$manager_was_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1
  fi
  trap - EXIT
  if [[ "$cleanup_failed" == true && "$status" == 0 ]]; then
    status=1
  fi
  exit "$status"
}
early_setup=false
trap cleanup EXIT

archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
runtime_ready=true
initial_ui="$(archphene_capture_ui "scriptlet-initial-$serial")"
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

for residue in \
  "$intent" "$reason_intent" "$database_repair" "$replacement_repair" "$database_lock"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "pre-existing package transaction state must be repaired first: $residue"
done

current_entry="$(installed_entry)"
[[ "$current_entry" == "$local_database/$package_name-$new_version" ]] ||
  archphene_die "expected installed zsh $new_version, received: $current_entry"
current_reason="$(installed_reason "$current_entry")"
case "$current_reason" in
  "") current_reason_flag=--asexplicit ;;
  1) current_reason_flag=--asdeps ;;
  *) archphene_die "installed zsh has an invalid install reason: $current_reason" ;;
esac
current_archive_relative="$(
  archphene_adb_run exec-out run-as "$manager" find "$cache" \
    -maxdepth 1 -type f \
    -name "$package_name-$new_version-$repository_arch.$archive_suffix" |
    tr -d '\r'
)"
[[ -n "$current_archive_relative" && "$current_archive_relative" != *$'\n'* ]] ||
  archphene_die "expected one retained current zsh archive"
current_archive="$app_root/$current_archive_relative"
current_signature="$current_archive.sig"
archphene_adb_run shell run-as "$manager" test -f \
  "$current_archive_relative.sig" ||
  archphene_die "current zsh archive has no detached signature"

archphene_adb_run shell run-as "$manager" test ! -e "$backup_root" ||
  archphene_die "refusing to replace an existing rollback backup"
for temporary in "$old_archive_temporary" "$old_signature_temporary"; do
  archphene_adb_run shell test ! -e "$temporary" ||
    archphene_die "refusing to replace an existing staging file: $temporary"
done
archphene_adb_run shell run-as "$manager" mkdir "$backup_root"
backup_root_owned=true
archphene_adb_run shell run-as "$manager" cp -p "$script_path" "$script_backup"
backup_ready=true
if archphene_adb_run shell run-as "$manager" test -f "$job_store"; then
  job_existed=true
  archphene_adb_run shell run-as "$manager" cp -p "$job_store" "$job_backup"
  job_hash="$(
    archphene_adb_run exec-out run-as "$manager" sha256sum "$job_backup" |
      tr -d '\r' |
      awk '{print $1}'
  )"
fi
job_snapshot_ready=true
if archphene_adb_run shell run-as "$manager" test -f "$recovery_preferences"; then
  recovery_existed=true
  archphene_adb_run shell run-as "$manager" cp -p \
    "$recovery_preferences" "$recovery_backup"
  recovery_hash="$(
    archphene_adb_run exec-out run-as "$manager" \
      sha256sum "$recovery_backup" |
      tr -d '\r' |
      awk '{print $1}'
  )"
fi
recovery_snapshot_ready=true
backup_without_script_hash="$(
  archphene_adb_run exec-out run-as "$manager" cat "$script_backup" |
    grep -Fvx "$script_line" |
    sha256sum |
    awk '{print $1}'
)"
[[ "$(line_count)" == 1 ]] ||
  archphene_die "$script_file must contain exactly one $script_line baseline"

stage_file() {
  local source="$1" name="$2" introduced_variable="$3"
  local destination="$cache/$name"
  local temporary temporary_owned_variable
  if [[ "$name" == "$old_archive_name" ]]; then
    temporary="$old_archive_temporary"
    temporary_owned_variable=old_archive_temporary_owned
  else
    temporary="$old_signature_temporary"
    temporary_owned_variable=old_signature_temporary_owned
  fi
  local source_hash destination_hash
  source_hash="$(sha256sum "$source" | awk '{print $1}')"
  if archphene_adb_run shell run-as "$manager" test -f "$destination"; then
    destination_hash="$(
      archphene_adb_run exec-out run-as "$manager" cat "$destination" |
        sha256sum |
        awk '{print $1}'
    )"
    [[ "$destination_hash" == "$source_hash" ]] ||
      archphene_die "cached test input differs from $source"
    return
  fi
  printf -v "$temporary_owned_variable" true
  archphene_adb_run push "$source" "$temporary" >/dev/null
  destination_hash="$(
    archphene_adb_run exec-out cat "$temporary" | sha256sum | awk '{print $1}'
  )"
  [[ "$destination_hash" == "$source_hash" ]] ||
    archphene_die "staged test input hash mismatch"
  printf -v "$introduced_variable" true
  archphene_adb_run shell run-as "$manager" cp "$temporary" "$destination"
  archphene_adb_run shell run-as "$manager" chmod 600 "$destination"
  archphene_adb_run shell rm -f "$temporary"
  archphene_adb_run shell test ! -e "$temporary" ||
    archphene_die "could not remove staging file: $temporary"
  printf -v "$temporary_owned_variable" false
}

stage_file "$old_archive" "$old_archive_name" introduced_archive
stage_file "$old_signature" "$old_signature_name" introduced_signature
old_archive_device="$app_root/$cache/$old_archive_name"
old_signature_device="$app_root/$cache/$old_signature_name"

verify_archive "$current_archive" "$current_signature"
verify_archive "$old_archive_device" "$old_signature_device"
old_metadata="$(extract_archive_file "$old_archive_device" .PKGINFO)"
new_metadata="$(extract_archive_file "$current_archive" .PKGINFO)"
for metadata in "$old_metadata" "$new_metadata"; do
  grep -Fqx "pkgname = $package_name" <<<"$metadata" ||
    archphene_die "signed package metadata is not zsh"
  grep -Fqx "arch = $repository_arch" <<<"$metadata" ||
    archphene_die "signed package metadata has the wrong architecture"
done
grep -Fqx "pkgver = $old_version" <<<"$old_metadata" ||
  archphene_die "older signed package metadata has the wrong version"
grep -Fqx "pkgver = $new_version" <<<"$new_metadata" ||
  archphene_die "current signed package metadata has the wrong version"
old_install="$(extract_archive_file "$old_archive_device" .INSTALL)"
new_install="$(extract_archive_file "$current_archive" .INSTALL)"
for install_script in "$old_install" "$new_install"; do
  grep -Fq "post_upgrade()" <<<"$install_script" ||
    archphene_die "signed zsh package has no post-upgrade script"
  grep -Fq "$script_line" <<<"$install_script" ||
    archphene_die "signed zsh package does not manage $script_line"
done

archphene_adb_run shell am force-stop "$manager" >/dev/null
mutation_started=true
pacman_archive "$old_archive_device" "$current_reason_flag" --noscriptlet >/dev/null
current_entry="$(installed_entry)"
[[ "$current_entry" == "$local_database/$package_name-$old_version" ]] ||
  archphene_die "could not establish the signed older zsh baseline"
[[ "$(installed_reason "$current_entry")" == "$current_reason" ]] ||
  archphene_die "older baseline changed the zsh install reason"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Packages "scriptlet-rollback-section-$serial"
archphene_wait_ui 'text="Package catalog ready"' \
  "scriptlet-rollback-catalog-$serial" 25
archphene_wait_ui 'class="android.widget.EditText"' \
  "scriptlet-rollback-field-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'class="android.widget.EditText"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$package_name" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Search"[^>]*enabled="true"' \
  "scriptlet-rollback-search-$serial" 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Search"[^>]*enabled="true"' 'search package'
archphene_wait_ui 'text="Details"[^>]*enabled="true"' \
  "scriptlet-rollback-details-$serial" 30
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Details"[^>]*enabled="true"' 'package details'
archphene_wait_ui_unwrapped \
  "text=\"[^\"]*/$package_name ${new_version}Installed: $old_version" \
  "scriptlet-rollback-review-$serial" 30
archphene_wait_ui 'text="Update"[^>]*enabled="true"' \
  "scriptlet-rollback-update-$serial" 10
archphene_adb_run exec-out screencap -p >"$output_dir/review.png"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a org.archphene.app.debug.action.ARM_PACKAGE_POST_TRANSACTION \
  --es token signed-scriptlet-rollback \
  --es package "$package_name" \
  --ei hold-ms 30000 >/dev/null
archphene_wait_log \
  'Started package phases=true token=signed-scriptlet-rollback' 15 \
  'ArchphenePackagePhaseProbe:V *:S' >/dev/null
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Update"[^>]*enabled="true"' 'update package'

deadline=$((SECONDS + 120))
while ((SECONDS < deadline)); do
  current_entry="$(installed_entry)"
  if [[ "$current_entry" == "$local_database/$package_name-$new_version" ]] &&
      archphene_adb_run shell run-as "$manager" test -f "$intent"; then
    break
  fi
  sleep 0.3
done
[[ "$current_entry" == "$local_database/$package_name-$new_version" ]] ||
  archphene_die "post-transaction hold did not reach current zsh"
archphene_adb_run exec-out run-as "$manager" cat "$intent" |
  grep -Fqx $'rollback\tready' ||
  archphene_die "mutation intent did not retain a complete rollback plan"
[[ "$(line_count)" == 1 ]] ||
  archphene_die "forward zsh update damaged the shell registration"
archphene_adb_run shell \
  "run-as $manager sed -i '\\#^${script_line}\$#d' '$script_path'"
[[ "$(line_count)" == 0 ]] ||
  archphene_die "could not remove the scriptlet evidence line"

android_pid="$(archphene_android_pid "$manager")"
archphene_adb_run shell run-as "$manager" kill -9 "$android_pid" >/dev/null
deadline=$((SECONDS + 15))
while archphene_android_pid "$manager" >/dev/null 2>&1 && ((SECONDS < deadline)); do
  sleep 0.2
done
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  archphene_die "manager survived post-transaction rollback SIGKILL"
fi

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Packages "scriptlet-recovery-section-$serial"
archphene_wait_ui 'class="android.widget.EditText"' \
  "scriptlet-recovery-field-$serial" 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'class="android.widget.EditText"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$package_name" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="(?:Roll back|Review)"' \
  "scriptlet-recovery-route-$serial" 30
if archphene_regex_contains "$ARCHPHENE_UI" 'text="Review"'; then
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Review"' 'Review'
fi
archphene_wait_ui 'text="Roll back"' "scriptlet-recovery-action-$serial" 30
archphene_wait_ui 'text="Repair"' "scriptlet-recovery-repair-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/interrupted.png"
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Roll back"' 'Roll back'
archphene_wait_ui 'text="Update · Complete · 100%"' \
  "scriptlet-recovery-complete-$serial" 120
archphene_wait_ui "text=\"Rolled back package transaction for $package_name\"" \
  "scriptlet-recovery-result-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/rolled-back.png"

current_entry="$(installed_entry)"
[[ "$current_entry" == "$local_database/$package_name-$old_version" ]] ||
  archphene_die "rollback did not restore signed zsh $old_version"
[[ "$(installed_reason "$current_entry")" == "$current_reason" ]] ||
  archphene_die "rollback changed the zsh install reason"
[[ "$(line_count)" == 1 ]] ||
  archphene_die "restored zsh post-upgrade script did not restore its shell entry"
archphene_adb_run exec-out run-as "$manager" cat "$script_backup" \
  >"$output_dir/shells-before"
archphene_adb_run exec-out run-as "$manager" cat "$script_path" \
  >"$output_dir/shells-rolled-back"
restored_without_script_hash="$(
  archphene_adb_run exec-out run-as "$manager" cat "$script_path" |
    grep -Fvx "$script_line" |
    sha256sum |
    awk '{print $1}'
)"
[[ "$restored_without_script_hash" == "$backup_without_script_hash" ]] ||
  archphene_die "restored zsh script changed unrelated $script_file content"
for residue in \
  "$intent" "$reason_intent" "$database_repair" "$replacement_repair" "$database_lock"; do
  archphene_adb_run shell run-as "$manager" test ! -e "$residue" ||
    archphene_die "rollback retained transaction state: $residue"
done

archphene_adb_run shell am force-stop "$manager" >/dev/null
pacman_archive "$current_archive" "$current_reason_flag" >/dev/null
current_entry="$(installed_entry)"
[[ "$current_entry" == "$local_database/$package_name-$new_version" ]] ||
  archphene_die "test cleanup did not restore current zsh"
[[ "$(installed_reason "$current_entry")" == "$current_reason" ]] ||
  archphene_die "test cleanup changed the original zsh install reason"
archphene_adb_run shell run-as "$manager" cp -p "$script_backup" "$script_path"
backup_ready=false
mutation_started=false

archphene_note "Archphene signed reverse-scriptlet rollback passed on $serial"
archphene_note \
  "  zsh $old_version -> $new_version -> $old_version; restored $script_line exactly once"
archphene_note "  Device restored to zsh $new_version with its original package reason and $script_file"
archphene_note "  Full-device screenshots: $output_dir/{review,interrupted,rolled-back}.png"
