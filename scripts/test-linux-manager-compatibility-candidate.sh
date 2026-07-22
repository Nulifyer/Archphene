#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
serial=emulator-5558; package_name=wev
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; --package-name) package_name="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--serial SERIAL] [--package-name NAME]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
[[ "$package_name" =~ ^[a-z0-9@._+-]{1,96}$ ]] || archphene_die "unsafe package name: $package_name"; archphene_init_adb "$serial"; manager=org.archpheneos.manager
[[ "$(archphene_adb_run shell getconf PAGE_SIZE | tr -d '\r\n')" == 4096 ]] || archphene_die "official Arch x86_64 candidates require the 4 KB lane"
archphene_adb_run shell run-as "$manager" id | grep -F uid= >/dev/null || archphene_die "a debuggable manager is required"
archphene_adb_run shell am force-stop "$manager"
archphene_adb_run shell am start -W -n "$manager/.MainActivity" --ez archphene_test_package_runtime true --es archphene_test_resolve_package "$package_name" >/dev/null
archphene_wait_ui "Resolved $package_name" compatibility-candidate 45; [[ "$ARCHPHENE_UI" == *'packages through libalpm'* ]] || archphene_die "$package_name did not resolve through libalpm"
archphene_adb_run shell am force-stop "$manager"; archphene_adb_run shell am start -W -n "$manager/.MainActivity" --ez archphene_test_package_runtime true --es archphene_test_resolve_package "$package_name" --ez archphene_test_download_target true >/dev/null
archphene_wait_ui "Downloaded and verified $package_name" compatibility-download 60; [[ "$ARCHPHENE_UI" == *'Signer '* ]] || archphene_die "$package_name did not report a verified repository signer"
archphene_note "$package_name resolved through libalpm and its target archive signature verified on $serial."
