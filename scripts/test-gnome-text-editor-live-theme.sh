#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/live-theme-test.sh"
serial=emulator-5554
package=org.archphene.linux.pe7675d0e278efaeade715635f437a43d
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_test_live_theme "$serial" "$package" "GNOME Text Editor" gtk4
