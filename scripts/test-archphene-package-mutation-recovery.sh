#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.PackagePhaseTestReceiver"
action=org.archphene.app.debug.action.START_INTERRUPTED_PACKAGE_REMOVAL
target=strace
intent=files/arch-root/run/package-mutation-v1
output_dir="$ARCHPHENE_ROOT/tooling/build/package-mutation-recovery"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null

local_entry="$(
  archphene_adb_run exec-out run-as "$package" find \
    files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name "$target-*" |
    tr -d '\r' |
    head -n1
)"
[[ -n "$local_entry" ]] || archphene_die "$target must be installed before the recovery gate"
version="$(
  archphene_adb_run exec-out run-as "$package" cat "$local_entry/desc" |
    tr -d '\r' |
    awk '/^%VERSION%$/{getline; print; exit}'
)"
[[ "$version" =~ ^[^[:space:]]{1,128}$ ]] ||
  archphene_die "could not read the installed $target version"

archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "mutation-recovery" \
  --es package "$target" \
  --ei hold-ms 30000 >/dev/null
archphene_wait_log \
  'Started package phases=true token=mutation-recovery' 15 \
  'ArchphenePackagePhaseProbe:V *:S' >/dev/null
archphene_wait_log \
  'Debug interrupted removal fixture entered mutation' 15 >/dev/null

archphene_adb_run shell run-as "$package" sh -c \
  "'umask 077; printf \"org.archphene.package-mutation.v1\\nremove\\t$target\\t$version\\n\" > $intent.tmp && mv $intent.tmp $intent'"
[[ "$(archphene_adb_run shell run-as "$package" stat -c %a "$intent" | tr -d '\r')" == 600 ]] ||
  archphene_die "package mutation intent mode is not private"

android_pid="$(archphene_android_pid "$package")"
archphene_adb_run shell run-as "$package" kill -9 "$android_pid" >/dev/null
deadline=$((SECONDS + 15))
while archphene_android_pid "$package" >/dev/null 2>&1 && ((SECONDS < deadline)); do
  sleep 0.2
done
if archphene_android_pid "$package" >/dev/null 2>&1; then
  archphene_die "manager process survived package-mutation SIGKILL"
fi

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Packages "package-recovery-packages-$serial"
archphene_wait_ui 'text="Package name"' "package-recovery-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input text "$target" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Remove · Failed · 60%"' \
  "package-recovery-failed-$serial" 20
archphene_wait_ui 'text="Repair"' "package-recovery-action-$serial" 15
archphene_wait_ui 'Package mutation was interrupted' \
  "package-recovery-message-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-interrupted.png"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Repair"' 'Repair'
archphene_wait_ui 'text="Remove · Complete · 100%"' \
  "package-recovery-complete-$serial" 60
archphene_wait_ui 'text="Repaired package transaction for strace"' \
  "package-recovery-result-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-repaired.png"

if archphene_adb_run shell run-as "$package" test -e "$intent"; then
  archphene_die "successful repair retained the mutation intent"
fi
if archphene_adb_run shell run-as "$package" test -e files/arch-root/usr/bin/strace; then
  archphene_die "repaired removal retained the strace executable"
fi
if archphene_adb_run shell run-as "$package" test -e "$local_entry"; then
  archphene_die "repaired removal retained the strace database entry"
fi

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:DETAILS|Details)"' 'package details'
archphene_wait_ui \
  'text="[^"]*/strace [^"]+.*Dependency closure: [1-9][0-9]* packages' \
  "package-recovery-resolution-$serial" 20
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:INSTALL|Install)"' 'reinstall strace'
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "package-recovery-reinstall-$serial" 120
archphene_wait_ui 'text="Installed strace [^"]+"' \
  "package-recovery-reinstalled-$serial" 15
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/strace ||
  archphene_die "strace was not restored after the recovery gate"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package mutation recovery emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Interrupted package mutation repair passed on $serial"
archphene_note "  SIGKILL recovery retained an exact removal baseline and required Repair"
archphene_note "  Repair removed strace, cleared the intent, and normal reinstall restored it"
archphene_note "  Full-device screenshots: $output_dir/$serial-{interrupted,repaired}.png"
