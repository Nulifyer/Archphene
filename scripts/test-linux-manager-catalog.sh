#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
manager=org.archpheneos.manager
kcalc_package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
mousepad_package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --kcalc-package) kcalc_package="${2:?missing value for --kcalc-package}"; shift 2 ;;
    --mousepad-package)
      mousepad_package="${2:?missing value for --mousepad-package}"
      shift 2
      ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--manager PACKAGE] [--kcalc-package PACKAGE] [--mousepad-package PACKAGE]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
activity="$(archphene_launcher "$manager")"
case "$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')" in
  arm64-v8a) runtime_label=glibc-aarch64 ;;
  x86_64) runtime_label=glibc-x86_64 ;;
  *) archphene_die "unsupported device ABI for legacy catalog" ;;
esac
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
output_dir="$ARCHPHENE_ROOT/tooling/build/legacy-manager-catalog/$safe_serial"
mkdir -p "$output_dir"
was_running=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  was_running=true
fi
cleanup() {
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  if [[ "$was_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

archphene_adb_run shell am force-stop "$manager"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_ui 'text="Archphene"' "legacy-manager-catalog-$serial" 20
catalog="$ARCHPHENE_UI"
for required in Archphene KCalc Mousepad extra/kcalc extra/mousepad "$runtime_label"; do
  [[ "$catalog" == *"$required"* ]] ||
    archphene_die "manager catalog is missing $required"
done

kcalc="$(
  archphene_adb_run shell cmd package list packages -U "$kcalc_package" |
    grep -E "^package:$kcalc_package uid:[0-9]+$" |
    head -n1 ||
    true
)"
mousepad="$(
  archphene_adb_run shell cmd package list packages -U "$mousepad_package" |
    grep -E "^package:$mousepad_package uid:[0-9]+$" |
    head -n1 ||
    true
)"
[[ -n "$kcalc" && -n "$mousepad" && "$kcalc" != "$mousepad" ]] ||
  archphene_die \
    "KCalc and Mousepad do not have distinct Android UIDs: $kcalc / $mousepad"
fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null ||
    true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "legacy manager catalog emitted a fatal runtime error: $fatal_log"
printf '%s' "$catalog" >"$output_dir/catalog.xml"
archphene_adb_run exec-out screencap -p >"$output_dir/catalog.png"

cleanup
trap - EXIT
archphene_note \
  "Legacy manager catalog passed on $serial: KCalc and Mousepad metadata are visible under distinct Android UIDs."
archphene_note "  Full-device screenshot: $output_dir/catalog.png"
