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
    --skip-install) skip_install=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
archphene_require_command unzip
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-open-vsx-test"
device_temporary="/data/local/tmp/archphene-open-vsx-test-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-open-vsx-test
installed_reload_probe=files/arch-root/usr/bin/archphene-resolver-reload-test
reload_build=
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/open-vsx-regression"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f "$installed_fixture" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" rm -f "$installed_reload_probe" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f \
    "$device_temporary" "$device_temporary.reload" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ -n "$reload_build" && -d "$reload_build" ]]; then
    rm -rf -- "$reload_build"
  fi
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_require_file "$fixture"
for program in bash curl getent grep wc; do
  archphene_adb_run shell run-as "$package" \
    test -x "files/arch-root/usr/bin/$program" ||
    archphene_die "Open VSX regression requires installed $program"
done

abi="$(
  archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r'
)"
reload_source="$ARCHPHENE_ROOT/native/archphene-glibc-path-bridge/resolver_reload_probe.c"
archphene_require_file "$reload_source"
reload_build="$(archphene_mktemp_dir resolver-reload-probe)"
case "$abi" in
  x86_64)
    archphene_require_command podman
    x86_image=docker.io/library/archlinux:base-devel
    archphene_podman_image_exists "$x86_image" ||
      archphene_die "missing existing x86_64 build image: $x86_image"
    podman run --rm --network=none \
      -v "$reload_source:/source/resolver_reload_probe.c:ro" \
      -v "$reload_build:/out" \
      "$x86_image" \
      gcc -march=x86-64 -mtune=generic -O2 -Wall -Wextra -Werror \
        -o /out/archphene-resolver-reload-test \
        /source/resolver_reload_probe.c
    ;;
  arm64-v8a)
    archphene_require_command podman
    arm_image=localhost/archphene-arm-runtime-builder:latest
    archphene_podman_image_exists "$arm_image" ||
      archphene_die "missing existing AArch64 build image: $arm_image"
    podman run --rm --network=none \
      -v "$reload_source:/source/resolver_reload_probe.c:ro" \
      -v "$reload_build:/out" \
      "$arm_image" \
      aarch64-linux-gnu-gcc -O2 -Wall -Wextra -Werror \
        -o /out/archphene-resolver-reload-test \
        /source/resolver_reload_probe.c
    ;;
  *) archphene_die "unsupported device ABI: $abi" ;;
esac
archphene_require_file "$reload_build/archphene-resolver-reload-test"

archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp \
  "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"
archphene_adb_run push \
  "$reload_build/archphene-resolver-reload-test" "$device_temporary.reload" \
  >/dev/null
archphene_adb_run shell run-as "$package" cp \
  "$device_temporary.reload" "$installed_reload_probe"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_reload_probe"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Published [1-4] Android DNS server\(s\)' 15 >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null

resolver="$(
  archphene_adb_run exec-out run-as "$package" \
    cat files/arch-root/etc/resolv.conf | tr -d '\r'
)"
grep -Eq '^nameserver [^[:space:]]+$' <<<"$resolver" ||
  archphene_die "private resolver configuration is missing a nameserver"
resolver_mode="$(
  archphene_adb_run shell run-as "$package" \
    stat -c %a files/arch-root/etc/resolv.conf | tr -d '\r'
)"
[[ "$resolver_mode" == 600 ]] ||
  archphene_die "private resolver mode is $resolver_mode, expected 600"
trust_bundle=files/arch-root/etc/ssl/certs/ca-certificates.crt
trust_metadata="$(
  archphene_adb_run shell run-as "$package" \
    stat -L -c %a:%s "$trust_bundle" | tr -d '\r'
)"
IFS=: read -r trust_mode trust_size <<<"$trust_metadata"
[[ "$trust_mode" == 444 ]] ||
  archphene_die "system trust bundle mode is $trust_mode, expected 444"
[[ "$trust_size" =~ ^[0-9]+$ ]] ||
  archphene_die "system trust bundle size is invalid: $trust_size"
((trust_size > 0 && trust_size <= 8 * 1024 * 1024)) ||
  archphene_die "system trust bundle size is outside its bound: $trust_size"

archphene_open_manager_section Terminal "open-vsx-section-$serial"
archphene_wait_ui 'text="Start shell"' "open-vsx-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
archphene_wait_ui \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows".*text="Shared shell ready"' \
  "open-vsx-prompt-$serial" 20

archphene_enter_terminal_line \
  "bash /usr/bin/archphene-open-vsx-test" \
  "open-vsx-fixture-$serial"

archphene_wait_ui 'open-vsx-ready' "open-vsx-ready-$serial" 60
for marker in 'resolver-config-ready' 'resolver-initial-result-ready' \
    'resolver-unavailable-result-ready' 'resolver-reload-ready' \
    'dns-open-vsx=' 'open-vsx-bytes='; do
  [[ "$ARCHPHENE_UI" == *"$marker"* ]] ||
    archphene_die "Open VSX output did not render marker: $marker"
done

case "$abi" in
  x86_64) runtime_manifest=package-runtime-x86_64.tsv ;;
  arm64-v8a) runtime_manifest=package-runtime-aarch64.tsv ;;
  *) archphene_die "unsupported device ABI: $abi" ;;
esac
expected_libc="$(
  unzip -p "$apk" "assets/$runtime_manifest" |
    awk -F '\t' '$1 == "library" && $2 == "libc.so.6" { print $3 }'
)"
[[ "$expected_libc" == libarchphene_pkg_*.so ]] ||
  archphene_die "APK does not declare one valid libc in $runtime_manifest"
android_pid="$(archphene_android_pid "$package")"
[[ -n "$android_pid" ]] || archphene_die "Archphene process is missing"
linux_pid="$(archphene_linux_loader_pid "$android_pid" || true)"
[[ -n "$linux_pid" ]] || archphene_die "running shared shell process is missing"
maps="$(
  archphene_adb_run exec-out run-as "$package" \
    cat "/proc/$linux_pid/maps" 2>/dev/null || true
)"
[[ "$maps" == *"$expected_libc"* ]] ||
  archphene_die "running shared shell did not map exact patched libc $expected_libc"

archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"
python3 -c '
import struct, sys
data = open(sys.argv[1], "rb").read(24)
if data[:8] != b"\x89PNG\r\n\x1a\n":
    raise SystemExit("device screenshot is not PNG")
width, height = struct.unpack(">II", data[16:24])
if width < 320 or height < 320:
    raise SystemExit(f"device screenshot is unexpectedly small: {width}x{height}")
' "$output_dir/$serial.png"

archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  'terminal surface'
archphene_adb_run shell input text x >/dev/null
archphene_wait_ui '(?:archphene:~\$|sh-[0-9.]+\$)' \
  "open-vsx-exit-$serial" 15

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Open VSX regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene private-root DNS/Open VSX regression passed on $serial"
archphene_note "  Exact patched libc: $expected_libc"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
