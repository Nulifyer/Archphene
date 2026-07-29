#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
code_package=
skip_manager_install=false
install_if_missing=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --code-package) code_package="${2:?missing value for --code-package}"; shift 2 ;;
    --skip-manager-install) skip_manager_install=true; shift ;;
    --install-if-missing) install_if_missing=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --code-package PACKAGE [--skip-manager-install] [--install-if-missing]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$code_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--code-package is not a generated Archphene launcher"
archphene_require_file "$apk"
probe="$ARCHPHENE_ROOT/tests/fixtures/archphene-code-git-probe"
archphene_require_file "$probe"
archphene_test_init "$serial"

manager=org.archphene.app.debug
manager_activity="$manager/org.archphene.app.MainActivity"
fixture=files/arch-root/home/archphene/.local/bin/archphenecodegitprobe
result=files/arch-root/home/archphene/.archphene-code-git-probe-result
workspace=files/arch-root/home/archphene/.archphene-code-git-probe-work
device_stage="/data/local/tmp/archphenecodegitprobe-$$"
output_dir="$ARCHPHENE_ROOT/tooling/build/code-git"
mkdir -p "$output_dir"
fixture_owned=false
probe_scope_owned=false

cleanup() {
  archphene_adb_run shell am force-stop "$code_package" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$device_stage" >/dev/null 2>&1 || true
  if [[ "$fixture_owned" == true ]]; then
    archphene_adb_run shell run-as "$manager" rm -f "$fixture" \
      >/dev/null 2>&1 || true
  fi
  if [[ "$probe_scope_owned" == true ]]; then
    archphene_adb_run shell run-as "$manager" rm -f \
      "$result" >/dev/null 2>&1 || true
    archphene_adb_run shell run-as "$manager" rm -rf "$workspace" \
      >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$skip_manager_install" == false ]]; then
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$code_package" >/dev/null ||
  archphene_die "Code launcher is not installed: $code_package"

start_manager() {
  archphene_adb_run shell am force-stop "$manager" >/dev/null
  archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
  archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 30 >/dev/null
}

if ! archphene_adb_run shell run-as "$manager" \
  test -x files/arch-root/usr/bin/git; then
  [[ "$install_if_missing" == true ]] ||
    archphene_die "git is not installed; rerun with --install-if-missing after approval"
  start_manager
  archphene_open_manager_section Packages "code-git-packages-$serial"
  archphene_wait_ui 'class="android.widget.EditText"' \
    "code-git-package-field-$serial" 15
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'class="android.widget.EditText"' 'package search input'
  archphene_adb_run shell input keycombination 113 29 >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
  archphene_adb_run shell input text git >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_wait_ui 'text="git"' "code-git-query-$serial" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Details"' 'package details'
  archphene_wait_ui \
    'text="[^"]*/git [^"]+.*Dependency closure: [1-9][0-9]* packages' \
    "code-git-resolution-$serial" 45
  archphene_adb_run exec-out screencap -p >"$output_dir/$serial-review.png"
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="Install"[^>]*enabled="true"' 'install git'
  archphene_wait_ui 'text="Install · Complete · 100%"' \
    "code-git-install-complete-$serial" 240
  archphene_wait_ui 'text="Installed git [^"]+"' \
    "code-git-installed-$serial" 20
  archphene_adb_run exec-out screencap -p >"$output_dir/$serial-installed.png"
fi

archphene_adb_run shell run-as "$manager" \
  test -x files/arch-root/usr/bin/git ||
  archphene_die "the installed git executable is unavailable"
for path in "$fixture" "$result" "$workspace"; do
  if archphene_adb_run shell run-as "$manager" test -e "$path"; then
    archphene_die "refusing to replace pre-existing probe path: $path"
  fi
done
probe_scope_owned=true

archphene_adb_run push "$probe" "$device_stage" >/dev/null
archphene_adb_run shell run-as "$manager" cp "$device_stage" "$fixture"
archphene_adb_run shell run-as "$manager" chmod 700 "$fixture"
archphene_adb_run shell rm -f "$device_stage"
fixture_owned=true

code_activity="$(
  archphene_adb_run shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    "$code_package" |
    tail -n1 |
    tr -d '\r'
)"
[[ "$code_activity" == "$code_package/"* ]] ||
  archphene_die "could not resolve the Code launcher Activity"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$code_package" >/dev/null
archphene_adb_run shell am start -W -n "$code_activity" >/dev/null
archphene_wait_log \
  'Linux Wayland client connected' 30 'ArchpheneLauncherSession:I *:S' \
  >/dev/null
sleep 12

# Ctrl+Shift+` always creates and focuses an integrated terminal. The probe
# command is deliberately alphanumeric so OEM key-character maps cannot drop
# punctuation from an ADB-driven hardware-key regression.
archphene_adb_run shell input keycombination 113 59 68 >/dev/null
sleep 5
archphene_adb_run shell input text archphenecodegitprobe >/dev/null
archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null

deadline=$((SECONDS + 45))
while ((SECONDS < deadline)); do
  if archphene_adb_run shell run-as "$manager" test -s "$result"; then
    break
  fi
  sleep 1
done
archphene_adb_run shell run-as "$manager" test -s "$result" ||
  archphene_die "Code integrated terminal did not complete the Git probe"
probe_result="$(
  archphene_adb_run exec-out run-as "$manager" cat "$result" |
    tr -d '\r'
)"
archphene_regex_contains "$probe_result" \
  '^git version [0-9]+\.[0-9]+\.[0-9]+[^\n]*\nA  sample\.txt\n?$' ||
  archphene_die "Code integrated terminal returned unexpected Git evidence: $probe_result"
archphene_adb_run shell run-as "$manager" test ! -e "$workspace" ||
  archphene_die "Git probe workspace was not cleaned"
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-code-git.png"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "Code Git regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene Code Git regression passed on $serial"
archphene_note "  Code integrated Bash completed git init/add/status: ${probe_result//$'\n'/; }"
archphene_note "  Full-device screenshot: $output_dir/$serial-code-git.png"
