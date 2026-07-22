#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5554
while (($#)); do
  case "$1" in --serial) serial="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--serial SERIAL]"; exit 0;; *) archphene_die "unknown argument: $1";; esac
done
archphene_init_adb "$serial"
mkdir -p "$ARCHPHENE_ROOT/artifacts"
archphene_adb_run shell am start -S -W -n org.archpheneos.manager/.MainActivity >/dev/null
sleep 2
archphene_adb_run shell uiautomator dump /sdcard/archphene-manager-catalog.xml >/dev/null
archphene_adb_run pull /sdcard/archphene-manager-catalog.xml "$ARCHPHENE_ROOT/artifacts/archphene-manager-catalog.xml" >/dev/null
catalog="$(<"$ARCHPHENE_ROOT/artifacts/archphene-manager-catalog.xml")"
for required in Archphene KCalc Mousepad extra/kcalc extra/mousepad glibc-x86_64; do
  [[ "$catalog" == *"$required"* ]] || archphene_die "manager catalog is missing $required"
done
kcalc_package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2
mousepad_package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
kcalc="$(archphene_adb_run shell cmd package list packages -U "$kcalc_package" | grep -E "^package:$kcalc_package uid:[0-9]+$" | head -n1 || true)"
mousepad="$(archphene_adb_run shell cmd package list packages -U "$mousepad_package" | grep -E "^package:$mousepad_package uid:[0-9]+$" | head -n1 || true)"
[[ -n "$kcalc" && -n "$mousepad" && "$kcalc" != "$mousepad" ]] || archphene_die "KCalc and Mousepad do not have distinct Android UIDs: $kcalc / $mousepad"
archphene_note "Manager catalog passed: KCalc and Mousepad discovered with shared ABI metadata and distinct Android UIDs."

