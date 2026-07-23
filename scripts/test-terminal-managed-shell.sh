#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
expected_architecture=
expect_page_size_rejection=false
reset_app_data=false
skip_install=false
refresh_runtime=false
install_timeout=240
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --expected-architecture) expected_architecture="${2:?}"; shift 2 ;;
    --expect-page-size-rejection) expect_page_size_rejection=true; shift ;;
    --reset-app-data) reset_app_data=true; shift ;;
    --skip-install) skip_install=true; shift ;;
    --refresh-runtime) refresh_runtime=true; shift ;;
    --install-timeout-seconds) install_timeout="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--serial SERIAL] [--expected-architecture TRIPLE] [--expect-page-size-rejection] [--reset-app-data] [--skip-install] [--refresh-runtime] [--install-timeout-seconds N]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
((install_timeout >= 30 && install_timeout <= 900)) \
  || archphene_die "install timeout must be from 30 to 900 seconds"

manager_apk="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/out-linux/archphene.apk"
terminal_apk="$ARCHPHENE_ROOT/prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
manager=org.archpheneos.manager
terminal=org.archpheneos.terminal

archphene_test_init "$serial"
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$manager_apk"
  archphene_require_file "$terminal_apk"
  archphene_adb_run install -r "$manager_apk" >/dev/null
  archphene_adb_run install -r "$terminal_apk" >/dev/null
fi

if [[ "$reset_app_data" == true ]]; then
  archphene_adb_run shell pm clear "$manager" >/dev/null
  archphene_adb_run shell pm clear "$terminal" >/dev/null
fi

abi="$(archphene_adb_run shell getprop ro.product.cpu.abi | tr -d '\r')"
page_size="$(archphene_adb_run shell getconf PAGESIZE | tr -d '\r')"
if [[ -z "$expected_architecture" ]]; then
  case "$abi" in
    x86_64) expected_architecture=x86_64-pc-linux-gnu ;;
    arm64-v8a) expected_architecture=aarch64-unknown-linux-gnu ;;
    *) archphene_die "no expected managed-shell architecture for Android ABI $abi" ;;
  esac
fi

terminal_dump="$(archphene_adb_run shell dumpsys package "$terminal")"
restore_notification=false
if ! archphene_regex_contains "$terminal_dump" \
    'android\.permission\.POST_NOTIFICATIONS: granted=true'; then
  archphene_adb_run shell pm grant "$terminal" \
    android.permission.POST_NOTIFICATIONS
  restore_notification=true
fi
cleanup() {
  archphene_adb_run shell am force-stop "$terminal" >/dev/null 2>&1 || true
  if [[ "$restore_notification" == true ]]; then
    archphene_adb_run shell pm revoke "$terminal" \
      android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

start_probe() {
  local command="$1" delay="${2:-8000}" encoded
  encoded="$(printf %s "$command" | base64 -w0)"
  archphene_adb_run shell am force-stop "$terminal"
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W \
    -n "$terminal/.TerminalActivity" \
    --es archphene_test_terminal_command_base64 "$encoded" \
    --ei archphene_test_terminal_capture_delay_ms "$delay" >/dev/null
}

if [[ "$expect_page_size_rejection" == true ]]; then
  start_probe "pacman -S bash" 90000
  rejection_log="$(archphene_wait_log \
    'is not compatible with 16384-byte Android pages|Terminal command probe transcript=' \
    "$((install_timeout < 120 ? install_timeout : 120))" \
    'ArchpheneTerminal:I AndroidRuntime:E *:S')"
  [[ "$rejection_log" == *'is not compatible with 16384-byte Android pages'* ]] \
    || archphene_die "16 KB device did not report the expected compatibility rejection"
  [[ "$rejection_log" != *'shell=Arch Bash'* ]] \
    || archphene_die "Terminal selected Arch Bash after rejecting its runtime pack"
  archphene_note "Managed-shell rejection passed on $serial ($abi, $page_size-byte pages)."
  exit 0
fi

probe='bash --version; pacman -Q; pacman -Qs bash && echo ARCHPHENE_QS_OK; pacman -Qi bash && echo ARCHPHENE_QI_OK; sleep 0 && echo ARCHPHENE_SLEEP_OK; printf "%s\n" "$PWD"; : > terminal-managed-shell-ok; test -f terminal-managed-shell-ok; echo ARCHPHENE_MANAGED_SHELL_OK'
start_probe "$probe"
sleep 10
existing_log="$(archphene_adb_run logcat -d \
  -s 'ArchpheneTerminal:I' 'AndroidRuntime:E' '*:S')"
shell_available=false
if [[ "$existing_log" == *'shell=Arch Bash'*
    && "$existing_log" == *"$expected_architecture"*
    && "$existing_log" == *'ARCHPHENE_QS_OK'*
    && "$existing_log" == *'ARCHPHENE_QI_OK'*
    && "$existing_log" == *'ARCHPHENE_SLEEP_OK'*
    && "$existing_log" == *'ARCHPHENE_MANAGED_SHELL_OK'*
    && "$existing_log" != *'cannot execute'*
    && "$existing_log" != *'Permission denied'*
    && "$existing_log" != *'inaccessible or not found'* ]]; then
  shell_available=true
fi

if [[ "$shell_available" == false || "$refresh_runtime" == true ]]; then
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W \
    -n "$manager/.MainActivity" \
    --ez archphene_test_package_runtime true \
    --es archphene_test_stage_package bash \
    --ez archphene_test_publish_terminal true >/dev/null
  install_log="$(archphene_wait_log \
    'Terminal catalog published \S*/bash/|Package preparation failed|FATAL EXCEPTION|SecurityException' \
    "$install_timeout" \
    'ArchphenePackages:I AndroidRuntime:E *:S')"
  [[ "$install_log" == *'Terminal catalog published '*'/bash/'* ]] \
    || archphene_die "managed Bash provisioning failed: $install_log"
fi

start_probe "$probe"
managed_log="$(archphene_wait_log \
  'ARCHPHENE_MANAGED_SHELL_OK' 45 \
  'ArchpheneTerminal:I AndroidRuntime:E *:S')"
for evidence in \
  'shell=Arch Bash' \
  "$expected_architecture" \
  'bash    ' \
  'ARCHPHENE_QS_OK' \
  'ARCHPHENE_QI_OK' \
  'ARCHPHENE_SLEEP_OK' \
  '/files/terminal/home' \
  'ARCHPHENE_MANAGED_SHELL_OK'; do
  [[ "$managed_log" == *"$evidence"* ]] \
    || archphene_die "managed shell lacks expected evidence '$evidence'"
done
for forbidden in \
  'warning: setlocale' \
  'CANNOT LINK EXECUTABLE' \
  'SIGSYS' \
  'SYS_SECCOMP' \
  'inaccessible or not found'; do
  [[ "$managed_log" != *"$forbidden"* ]] \
    || archphene_die "managed shell emitted forbidden output '$forbidden'"
done

start_probe \
  'test -f terminal-managed-shell-ok && echo ARCHPHENE_MANAGED_SHELL_PERSISTED'
persistence_log="$(archphene_wait_log \
  'ARCHPHENE_MANAGED_SHELL_PERSISTED' 30 \
  'ArchpheneTerminal:I AndroidRuntime:E *:S')"
[[ "$persistence_log" == *'shell=Arch Bash'* ]] \
  || archphene_die "Terminal lost managed Bash across a cold service restart"

archphene_note "Managed Arch Bash passed on $serial ($abi, $page_size-byte pages, $expected_architecture): package catalog, writable persistent home, cold restart, and runtime error checks verified."
