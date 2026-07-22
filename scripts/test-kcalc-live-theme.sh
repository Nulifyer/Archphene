#!/usr/bin/env bash
set -euo pipefail;source "$(dirname "$0")/lib/live-theme-test.sh";serial=emulator-5554;package=org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2;while (($#));do case "$1" in --serial)serial="${2:?}";shift 2;;--package)package="${2:?}";shift 2;;*)archphene_die "unknown argument: $1";;esac;done;archphene_test_live_theme "$serial" "$package" KCalc
