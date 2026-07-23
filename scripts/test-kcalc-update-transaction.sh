#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

serial=emulator-5554
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    # Retained for compatibility with pre-manager invocations. The supported
    # transaction updates the already generated wrapper and does not rebuild
    # the retired hand-authored KCalc APK.
    --skip-build) shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--skip-build]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_note \
  "KCalc updates use the manager-generated wrapper transaction; running its Android replacement gate."
"$ARCHPHENE_SCRIPTS_DIR/test-linux-manager-package-installer.sh" \
  --serial "$serial"
