#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/live-theme-test.sh"
serial=emulator-5554
package=org.archphene.linux.p73ccc00a787cdc19febdd4a01d4b9d10
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_test_live_theme "$serial" "$package" Foot wayland
