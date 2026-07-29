#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
install_bash=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --skip-install) skip_install=true; shift ;;
    --install-bash) install_bash=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--install-apk] [--install-bash]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
fixture="$ARCHPHENE_ROOT/tests/fixtures/archphene-command-script"
device_temporary="/data/local/tmp/archphene-command-script-$serial"
installed_fixture=files/arch-root/usr/bin/archphene-command-test
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/script-command-regression"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell run-as "$package" rm -f "$installed_fixture" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_temporary" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_require_file "$fixture"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null

if ! archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash; then
  [[ "$install_bash" == true ]] ||
    archphene_die "script regression requires installed bash; rerun with --install-bash"
  archphene_wait_ui 'text="Package name"' "archphene-script-package-field-$serial" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
  archphene_adb_run shell input text bash >/dev/null
  archphene_wait_ui 'text="bash"' "archphene-script-package-entered-$serial" 10
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_wait_ui 'text="DETAILS"' "archphene-script-details-visible-$serial" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="DETAILS"' 'package details'
  archphene_wait_ui 'text="[^"]*/bash [^"]+.*Dependency closure: [1-9][0-9]* packages' \
    "archphene-script-bash-resolution-$serial" 20
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="INSTALL"' 'install bash'
  archphene_wait_ui 'text="bash · Complete · 100%.*Installed bash [^"]+"' \
    "archphene-script-bash-installed-$serial" 180
fi
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "bash installation did not publish an executable"

archphene_adb_run push "$fixture" "$device_temporary" >/dev/null
archphene_adb_run shell run-as "$package" cp "$device_temporary" "$installed_fixture"
archphene_adb_run shell run-as "$package" chmod 755 "$installed_fixture"
archphene_adb_run shell run-as "$package" test -x "$installed_fixture" ||
  archphene_die "script fixture was not published"

archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 15 >/dev/null
archphene_open_manager_section Terminal "archphene-script-terminal-$serial"
archphene_run_debug_linux_command "$package" "archphene-command-test verified"
archphene_wait_ui 'text="Exited 0[^"]*archphene-script-ok:verified' \
  "archphene-script-complete-$serial" 45
archphene_wait_ui \
  'archphene-env:/home/archphene\|/home/archphene\|/home/archphene/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/bin\|UTF-8\|1' \
  "archphene-script-environment-$serial" 15
[[ "$ARCHPHENE_UI" != *"cannot change locale"* ]] ||
  archphene_die "script command exposed an unavailable locale warning"
archphene_wait_log 'Linux command archphene-command-test exited 0' 15 >/dev/null
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "script command emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene shared script command regression passed on $serial"
archphene_note "  Root-contained Bash script, conventional paths, and C.UTF-8 passed"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
