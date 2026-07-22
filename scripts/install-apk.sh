#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

apk=prototypes/linux-app-manager-stub/out-linux/archphene.apk
serial=
package=org.archpheneos.manager
activity=
no_launch=false
while (($#)); do
  case "$1" in
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --activity) activity="${2:?missing value for --activity}"; shift 2 ;;
    --no-launch) no_launch=true; shift ;;
    -h|--help) echo "usage: $0 [--apk PATH] [--serial SERIAL] [--package NAME] [--activity NAME] [--no-launch]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$apk" == /* ]] || apk="$ARCHPHENE_ROOT/$apk"
archphene_require_file "$apk"
ARCHPHENE_ADB="$(archphene_adb)"
archphene_adb_args "$serial"
archphene_adb_run get-state >/dev/null
archphene_adb_run install -r "$apk"
if [[ "$no_launch" == false ]]; then
  if [[ -n "$activity" ]]; then
    archphene_adb_run shell am start -n "$package/$activity"
  else
    archphene_adb_run shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
  fi
fi
target_name="${serial:-the selected ADB device}"
archphene_note "Installed $apk on $target_name."

