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
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
archphene_require_command makepkg
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
output_dir="$ARCHPHENE_ROOT/tooling/build/package-replacement-interruption"
fixture="$ARCHPHENE_ROOT/tests/fixtures/package-replacement-device"
build_root="$(mktemp -d "${TMPDIR:-/tmp}/archphene-replacement-interruption.XXXXXX")"
device_archive=files/wcurl-0.0-1-any.pkg.tar.zst
device_config=files/replacement-pacman.conf

cleanup() {
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
}
trap cleanup EXIT

mkdir -p "$output_dir"
cp "$fixture/PKGBUILD" "$build_root/PKGBUILD"
(
  cd "$build_root"
  makepkg --noconfirm >/dev/null
)
archive="$build_root/wcurl-0.0-1-any.pkg.tar.zst"
archphene_require_file "$archive"

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
[[ "$pacman_payload" =~ ^libarchphene_pkg_[0-9a-f]{24}\.so$ ]] ||
  archphene_die "APK does not declare one valid pacman payload"

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_adb_run shell am force-stop "$manager" >/dev/null

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
libraries="$alias_root:$native:$absolute_root/usr/lib:$absolute_root/usr/lib/pulseaudio"
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
  --nodeps --nodeps --asexplicit \
  -U "/data/user/0/$manager/$device_archive" >/dev/null

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
cleanup
archphene_note "Interrupted package replacement repair passed on $serial"
archphene_note "  SIGKILL occurred after the durable replacement snapshot and before pacman"
archphene_note "  Repair restored the baseline, completed official curl, and cleared all residue"
archphene_note "  Full-device screenshots: $output_dir/$serial-{review,interrupted,repaired}.png"
