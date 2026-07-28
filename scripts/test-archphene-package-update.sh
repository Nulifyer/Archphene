#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
package_name=
old_version=
new_version=
install_reason=
old_dependencies=()
new_dependencies=()
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --package) package_name="${2:?missing value for --package}"; shift 2 ;;
    --old-version) old_version="${2:?missing value for --old-version}"; shift 2 ;;
    --new-version) new_version="${2:?missing value for --new-version}"; shift 2 ;;
    --install-reason) install_reason="${2:?missing value for --install-reason}"; shift 2 ;;
    --old-dependency) old_dependencies+=("${2:?missing value for --old-dependency}"); shift 2 ;;
    --new-dependency) new_dependencies+=("${2:?missing value for --new-dependency}"); shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --package NAME --old-version VERSION --new-version VERSION --install-reason explicit|dependency [--old-dependency NAME] [--new-dependency NAME]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$package_name" =~ ^[a-zA-Z0-9@._+-]{1,128}$ ]] ||
  archphene_die "--package is invalid"
[[ "$old_version" =~ ^[^[:space:]/]{1,128}$ ]] ||
  archphene_die "--old-version is invalid"
[[ "$new_version" =~ ^[^[:space:]/]{1,128}$ ]] ||
  archphene_die "--new-version is invalid"
[[ "$old_version" != "$new_version" ]] ||
  archphene_die "old and new versions must differ"
[[ "$install_reason" == explicit || "$install_reason" == dependency ]] ||
  archphene_die "--install-reason must be explicit or dependency"
for dependency in "${old_dependencies[@]}" "${new_dependencies[@]}"; do
  [[ "$dependency" =~ ^[a-zA-Z0-9@._+-]{1,128}$ ]] ||
    archphene_die "dependency package name is invalid: $dependency"
done

archphene_test_init "$serial"
archphene_require_file "$apk"
archphene_require_command unzip
manager=org.archphene.app.debug
activity="$manager/org.archphene.app.MainActivity"
root=files/arch-root
cache="$root/var/cache/pacman/pkg"
local_database="$root/var/lib/pacman/local"
output_dir="$ARCHPHENE_ROOT/tooling/build/package-update"
mkdir -p "$output_dir"

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
[[ "$pacman_payload" =~ ^libarchphene_pkg_[0-9a-f]{24}\.so$ ]] ||
  archphene_die "APK does not declare one valid pacman payload"
[[ "$gpgv_payload" =~ ^libarchphene_pkg_[0-9a-f]{24}\.so$ ]] ||
  archphene_die "APK does not declare one valid gpgv payload"
[[ "$bsdtar_payload" =~ ^libarchphene_pkg_[0-9a-f]{24}\.so$ ]] ||
  archphene_die "APK does not declare one valid bsdtar payload"

archive="$(
  archphene_adb_run exec-out run-as "$manager" find "$cache" \
    -maxdepth 1 -type f -name "$package_name-$old_version-*.pkg.tar.*" \
    ! -name '*.sig' | tr -d '\r' | head -n1
)"
[[ -n "$archive" ]] ||
  archphene_die "signed older archive is not cached: $package_name $old_version"
archphene_adb_run shell run-as "$manager" test -f "$archive.sig" ||
  archphene_die "older archive has no detached signature: $archive"

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_adb_run shell am force-stop "$manager" >/dev/null

reason_flag=--asdeps
expected_reason=1
if [[ "$install_reason" == explicit ]]; then
  reason_flag=--asexplicit
  expected_reason=
fi
remote_root="\$PWD/$root"
remote_alias="\$root/run/package-runtime-v1"
remote_loader="\$alias/$loader_name"
remote_native="\$(dirname \"\$(readlink \"\$loader\")\")"
remote_pacman="\$native/$pacman_payload"
remote_gpgv="\$native/$gpgv_payload"
remote_bsdtar="\$native/$bsdtar_payload"
remote_keyring="\$root/run/package-trust-v1/pubring.kbx"
remote_libraries="\$alias:\$native:\$root/usr/lib:\$root/usr/lib/pulseaudio"
remote_verification="root=\"$remote_root\"; alias=\"$remote_alias\"; loader=\"$remote_loader\"; native=\"$remote_native\"; gpgv=\"$remote_gpgv\"; keyring=\"$remote_keyring\"; libs=\"$remote_libraries\"; env -i HOME=\"\$root/home/archphene\" TMPDIR=\"\$root/tmp\" PATH=\"\$alias:\$root/usr/bin\" LANG=C LC_ALL=C GLIBC_TUNABLES=glibc.pthread.rseq=0 LD_PRELOAD=\"\$alias/libarchphene_path_bridge.so\" ARCHPHENE_RUNTIME_LOADER=\"\$loader\" ARCHPHENE_RUNTIME_LIB=\"\$libs\" ARCHPHENE_RUNTIME_COMMAND_DIR=\"\$alias\" ARCHPHENE_RUNTIME_ROOT=\"\$root\" ARCHPHENE_RUNTIME_PROGRAM_PATH=\"\$gpgv\" ARCHPHENE_ROOT_IDENTITY=1 \"\$loader\" --library-path \"\$libs\" \"\$gpgv\" --keyring \"\$keyring\" --status-fd 1 \"\$PWD/$archive.sig\" \"\$PWD/$archive\""
signature_status="$(
  archphene_adb_run exec-out run-as "$manager" sh -c \
    "$remote_verification" 2>&1
)"
archphene_regex_contains \
  "$signature_status" '\[GNUPG:\] VALIDSIG (?:[0-9A-F]{40}|[0-9A-F]{64}) ' ||
  archphene_die "older archive did not produce an exact valid signature status"
if archphene_regex_contains \
    "$signature_status" \
    '\[GNUPG:\] (?:BADSIG|ERRSIG|REVKEYSIG|EXPKEYSIG|KEYEXPIRED|SIGEXPIRED)'; then
  archphene_die "older archive produced a rejected signature status"
fi
remote_metadata="root=\"$remote_root\"; alias=\"$remote_alias\"; loader=\"$remote_loader\"; native=\"$remote_native\"; bsdtar=\"$remote_bsdtar\"; libs=\"$remote_libraries\"; env -i HOME=\"\$root/home/archphene\" TMPDIR=\"\$root/tmp\" PATH=\"\$alias:\$root/usr/bin\" LANG=C LC_ALL=C GLIBC_TUNABLES=glibc.pthread.rseq=0 LD_PRELOAD=\"\$alias/libarchphene_path_bridge.so\" ARCHPHENE_RUNTIME_LOADER=\"\$loader\" ARCHPHENE_RUNTIME_LIB=\"\$libs\" ARCHPHENE_RUNTIME_COMMAND_DIR=\"\$alias\" ARCHPHENE_RUNTIME_ROOT=\"\$root\" ARCHPHENE_RUNTIME_PROGRAM_PATH=\"\$bsdtar\" ARCHPHENE_ROOT_IDENTITY=1 \"\$loader\" --library-path \"\$libs\" \"\$bsdtar\" -xOf \"\$PWD/$archive\" .PKGINFO"
old_metadata="$(
  archphene_adb_run exec-out run-as "$manager" sh -c \
    "$remote_metadata" | tr -d '\r'
)"
for dependency in "${old_dependencies[@]}"; do
  grep -Fqx "depend = $dependency" <<<"$old_metadata" ||
    archphene_die "older archive does not declare expected dependency: $dependency"
done
remote_command="root=\"$remote_root\"; alias=\"$remote_alias\"; loader=\"$remote_loader\"; native=\"$remote_native\"; pacman=\"$remote_pacman\"; libs=\"$remote_libraries\"; env -i HOME=\"\$root/home/archphene\" TMPDIR=\"\$root/tmp\" PATH=\"\$alias:\$root/usr/bin\" LANG=C LC_ALL=C GLIBC_TUNABLES=glibc.pthread.rseq=0 LD_PRELOAD=\"\$alias/libarchphene_path_bridge.so\" ARCHPHENE_RUNTIME_LOADER=\"\$loader\" ARCHPHENE_RUNTIME_LIB=\"\$libs\" ARCHPHENE_RUNTIME_COMMAND_DIR=\"\$alias\" ARCHPHENE_RUNTIME_ROOT=\"\$root\" ARCHPHENE_RUNTIME_PROGRAM_PATH=\"\$pacman\" ARCHPHENE_ROOT_IDENTITY=1 \"\$loader\" --library-path \"\$libs\" \"\$pacman\" --config \"\$root/etc/pacman.conf\" --root \"\$root\" --dbpath \"\$root/var/lib/pacman\" --gpgdir \"\$root/run/package-trust-v1\" --cachedir \"\$root/var/cache/pacman/pkg\" --noconfirm --noprogressbar --noscriptlet $reason_flag -U \"\$PWD/$archive\""
archphene_adb_run shell "run-as $manager sh -c '$remote_command'" >/dev/null

local_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$package_name-*" | tr -d '\r'
)"
[[ "$local_entry" == "$local_database/$package_name-$old_version" ]] ||
  archphene_die "older transaction did not establish the requested baseline"
description="$(
  archphene_adb_run exec-out run-as "$manager" cat "$local_entry/desc" | tr -d '\r'
)"
reason="$(awk '/^%REASON%$/{getline; print; exit}' <<<"$description")"
[[ "$reason" == "$expected_reason" ]] ||
  archphene_die "baseline install reason is $reason, expected ${expected_reason:-explicit}"

archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Packages "package-update-section-$serial"
archphene_wait_ui 'text="Package catalog ready"' "package-update-catalog-$serial" 25
archphene_wait_ui 'class="android.widget.EditText"' "package-update-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'class="android.widget.EditText"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$package_name" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Search"[^>]*enabled="true"' "package-update-search-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Search"[^>]*enabled="true"' 'search package'
archphene_wait_ui 'text="Details"[^>]*enabled="true"' "package-update-details-$serial" 30
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Details"[^>]*enabled="true"' 'package details'
archphene_wait_ui_unwrapped \
  "text=\"[^\"]*/$package_name ${new_version}Installed: $old_version" \
  "package-update-review-$serial" 30
archphene_wait_ui 'text="Update"[^>]*enabled="true"' "package-update-action-$serial" 10
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-review.png"
archphene_adb_run logcat -c
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Update"[^>]*enabled="true"' 'update package'
archphene_wait_log "Updated $package_name: [1-9][0-9]* signed packages" 120 >/dev/null
archphene_wait_ui 'text="Update · Complete · 100%"' "package-update-complete-$serial" 20
archphene_wait_ui "text=\"$package_name\".*text=\"Installed\"" \
  "package-update-result-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-complete.png"

local_entry="$(
  archphene_adb_run exec-out run-as "$manager" find "$local_database" \
    -maxdepth 1 -type d -name "$package_name-*" | tr -d '\r'
)"
[[ "$local_entry" == "$local_database/$package_name-$new_version" ]] ||
  archphene_die "manager update did not install $package_name $new_version"
description="$(
  archphene_adb_run exec-out run-as "$manager" cat "$local_entry/desc" | tr -d '\r'
)"
reason="$(awk '/^%REASON%$/{getline; print; exit}' <<<"$description")"
[[ "$reason" == "$expected_reason" ]] ||
  archphene_die "update changed install reason to $reason"
new_archive="$(
  archphene_adb_run exec-out run-as "$manager" find "$cache" \
    -maxdepth 1 -type f -name "$package_name-$new_version-*.pkg.tar.*" \
    ! -name '*.sig' | tr -d '\r' | head -n1
)"
[[ -n "$new_archive" ]] ||
  archphene_die "manager update did not retain the new verified archive"
remote_new_metadata="${remote_metadata//$archive/$new_archive}"
new_metadata="$(
  archphene_adb_run exec-out run-as "$manager" sh -c \
    "$remote_new_metadata" | tr -d '\r'
)"
for dependency in "${new_dependencies[@]}"; do
  grep -Fqx "depend = $dependency" <<<"$new_metadata" ||
    archphene_die "new archive does not declare expected dependency: $dependency"
  installed_dependency="$(
    archphene_adb_run exec-out run-as "$manager" find "$local_database" \
      -maxdepth 1 -type d -name "$dependency-*" -print -quit | tr -d '\r'
  )"
  [[ -n "$installed_dependency" ]] ||
    archphene_die "new dependency was not installed in the shared package database: $dependency"
done
archphene_adb_run shell run-as "$manager" test ! -e "$root/var/lib/pacman/db.lck" ||
  archphene_die "update left the pacman database locked"
temporary="$(
  archphene_adb_run exec-out run-as "$manager" find "$cache" \
    -maxdepth 1 -type f -name '*.part' -print -quit | tr -d '\r'
)"
[[ -z "$temporary" ]] || archphene_die "update left a partial package payload"

fatal_log="$(
  archphene_adb_run logcat -d -v brief -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package update emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene older-to-newer package update passed on $serial"
archphene_note "  $package_name $old_version -> $new_version; reason=$install_reason"
if ((${#old_dependencies[@]} + ${#new_dependencies[@]} > 0)); then
  archphene_note "  Dependency metadata: ${old_dependencies[*]:-none} -> ${new_dependencies[*]:-none}"
fi
archphene_note "  Full-device screenshots: $output_dir/$serial-{review,complete}.png"
