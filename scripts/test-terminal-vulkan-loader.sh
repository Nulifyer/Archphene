#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archpheneos.terminal
delay_ms=30000
require_device=false
expected_pattern=
provision=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --capture-delay-ms) delay_ms="${2:?}"; shift 2 ;;
    --require-device) require_device=true; shift ;;
    --expected-device-pattern) expected_pattern="${2:?}"; shift 2 ;;
    --provision) provision=true; shift ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--package NAME] [--capture-delay-ms MS] [--require-device] [--expected-device-pattern REGEX] [--provision]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ "$delay_ms" =~ ^[0-9]+$ && "$delay_ms" -ge 1000 ]] \
  || archphene_die '--capture-delay-ms must be an integer of at least 1000'
archphene_test_init "$serial"
archphene_adb_run shell run-as "$package" id >/dev/null
manager=org.archpheneos.manager
provision_package() {
  local name="$1" escaped log
  escaped="$(python3 -c \
    'import re,sys; print(re.escape(sys.argv[1]))' "$name")"
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" \
    --ez archphene_test_package_runtime true \
    --es archphene_test_stage_package "$name" \
    --ez archphene_test_publish_terminal true >/dev/null
  log="$(archphene_wait_log \
    "Terminal catalog published \\S*/$escaped/|Package preparation failed|FATAL EXCEPTION" \
    600 'ArchphenePackages:I ArchpheneManager:E AndroidRuntime:E *:S')"
  [[ "$log" == *'Terminal catalog published '*"/$name/"* ]] \
    || archphene_die "manager could not provision $name for Terminal"
}
if [[ "$provision" == true ]]; then
  manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
  archphene_regex_contains "$manager_dump" \
    '(?m)^\s*flags=\[[^]]*DEBUGGABLE' \
    || archphene_die '--provision requires a debuggable manager'
  provision_package vulkan-tools
  if [[ "$require_device" == true ]]; then
    provision_package vulkan-swrast
  fi
fi
restore_notification=false
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$restore_notification" == true ]]; then
    archphene_adb_run shell pm revoke "$package" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

package_dump="$(archphene_adb_run shell dumpsys package "$package")"
if archphene_regex_contains "$package_dump" \
    'android\.permission\.POST_NOTIFICATIONS' \
    && ! archphene_regex_contains "$package_dump" \
      'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  archphene_adb_run shell pm grant "$package" \
    android.permission.POST_NOTIFICATIONS
  restore_notification=true
fi

archphene_adb_run shell am force-stop "$package"
archphene_adb_run logcat -c
encoded_command="$(printf %s 'vulkaninfo --summary' | base64 -w0)"
archphene_adb_run shell am start -W -n "$package/.TerminalActivity" \
  --es archphene_test_terminal_command_base64 "$encoded_command" \
  --ei archphene_test_terminal_capture_delay_ms "$delay_ms" >/dev/null
deadline=$((SECONDS + delay_ms / 1000 + 20))
log=
while ((SECONDS < deadline)); do
  sleep 2
  log="$(archphene_adb_run logcat -d -v brief \
    -s ArchpheneTerminal:I AndroidRuntime:E '*:S')"
  if [[ "$log" == *'Terminal command probe transcript='* ]]; then
    break
  fi
done
[[ "$log" == *'Terminal command probe transcript='* ]] \
  || archphene_die 'timed out waiting for vulkaninfo transcript'
! archphene_regex_contains "$log" \
  'FATAL EXCEPTION|not compatible with this device|CANNOT LINK EXECUTABLE|linker:|Segmentation fault' \
  || archphene_die 'vulkaninfo crashed or failed in the managed runtime'
[[ "$log" != *'Vulkan loader is not installed, not found, or failed to load'* ]] \
  || archphene_die 'runtime-loaded Vulkan loader was omitted from the closure'
[[ "$log" == *'Vulkan Instance Version'* || "$log" == *'Found no drivers'* ]] \
  || archphene_die 'vulkaninfo did not reach Vulkan loader device discovery'
[[ "$require_device" == false || "$log" == *'GPU0:'* ]] \
  || archphene_die 'Vulkan loader exposed no device'
[[ -z "$expected_pattern" ]] \
  || archphene_regex_contains "$log" "$expected_pattern" \
  || archphene_die "Vulkan output did not match $expected_pattern"

cleanup
trap - EXIT
archphene_note "Terminal Vulkan loader/device discovery passed on $serial with notification permission restored."
