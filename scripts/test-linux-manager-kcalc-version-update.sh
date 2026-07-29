#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

serial=emulator-5554
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    # Accepted for compatibility with the retired hand-built fixture.
    --skip-build) shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--skip-build]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_note \
  "The hand-built org.archphene.linux.kcalc version fixture is retired; delegating to the supported manager-generated wrapper replacement gate."
exec "$ARCHPHENE_SCRIPTS_DIR/test-kcalc-update-transaction.sh" \
  --serial "$serial" --skip-build
