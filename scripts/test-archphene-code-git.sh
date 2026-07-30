#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
code_package=
skip_install=true
install_if_missing=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --code-package) code_package="${2:?missing value for --code-package}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --skip-manager-install) skip_install=true; shift ;;
    --install-if-missing) install_if_missing=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --code-package PACKAGE [--apk PATH --install-apk] [--install-if-missing]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$code_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--code-package is not a generated Archphene launcher"
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
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
code_config=
code_config_backup="files/arch-root/run/code-git-config-$serial_slug"
code_config_backed_up=false
code_config_inventory_before=
manager_was_running=false
original_section=
manager_state_restored=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  manager_was_running=true
fi
if archphene_android_pid "$code_package" >/dev/null 2>&1; then
  archphene_die "refusing to replace an active Code session"
fi

code_linux_pgid() {
  local process_tree
  process_tree="$(
    archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
      tr -d '\r'
  )"
  awk '
    $4 ~ /^libarchphene_(pkg_[0-9a-f]+|ld)\.so$/ &&
    $0 ~ /--argv0 (code|code-oss) / {
      print $3
      exit
    }
  ' <<<"$process_tree"
}

stop_code_session() {
  local linux_pgid deadline process_tree
  linux_pgid="$(code_linux_pgid)"
  archphene_adb_run shell am force-stop "$code_package" >/dev/null 2>&1 || true
  if [[ "$linux_pgid" =~ ^[1-9][0-9]*$ ]]; then
    deadline=$((SECONDS + 20))
    while ((SECONDS < deadline)); do
      process_tree="$(
        archphene_adb_run shell ps -A -o PID,PPID,PGID,NAME,ARGS |
          tr -d '\r'
      )"
      if ! awk -v pgid="$linux_pgid" '$3 == pgid { found = 1 } END { exit !found }' \
        <<<"$process_tree"; then
        sleep 1
        return 0
      fi
      sleep 0.25
    done
    archphene_die "Code Linux process group did not stop before configuration restoration"
  fi
  sleep 1
}

cleanup() {
  stop_code_session
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
  if [[ "$code_config_backed_up" == true ]]; then
    archphene_adb_run shell \
      "run-as $manager sh -c 'rm -rf -- \"$code_config\" && mv -- \"$code_config_backup\" \"$code_config\"'" \
      >/dev/null
    code_config_backed_up=false
  fi
  if [[ "$manager_state_restored" == false ]]; then
    if [[ -n "$original_section" ]]; then
      archphene_adb_run shell am start -W -n "$manager_activity" \
        >/dev/null 2>&1 || true
      local ui
      ui="$(archphene_capture_ui "code-git-restore-section-$serial" 2>/dev/null || true)"
      if archphene_regex_contains \
        "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
        archphene_tap_ui_pattern \
          "$ui" \
          "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
          "$original_section" >/dev/null 2>&1 || true
      fi
    fi
    if [[ "$manager_was_running" == true ]]; then
      archphene_adb_run shell am start -W -n "$manager_activity" \
        >/dev/null 2>&1 || true
    else
      archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
    fi
    manager_state_restored=true
  fi
}
trap cleanup EXIT

assert_manager_restored() {
  if [[ "$manager_was_running" == true ]]; then
    archphene_android_pid "$manager" >/dev/null ||
      archphene_die "Code Git gate did not restore the running manager"
  else
    ! archphene_android_pid "$manager" >/dev/null 2>&1 ||
      archphene_die "Code Git gate left the manager running"
  fi
}

code_config_inventory() {
  archphene_adb_run shell \
    "run-as $manager sh -c 'cd \"$code_config\" && find . -type f -exec sha256sum {} \\; | sort'" |
    tr -d '\r'
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "$manager is not installed; pass --install-apk with --apk"
archphene_adb_run shell pm path "$code_package" >/dev/null ||
  archphene_die "Code launcher is not installed: $code_package"
[[ -z "$(code_linux_pgid)" ]] ||
  archphene_die "refusing to snapshot an active manager-owned Code process"

code_configs="$(
  archphene_adb_run exec-out run-as "$manager" find \
    files/arch-root/home/archphene/.config -mindepth 1 -maxdepth 1 -type d \
    -print 2>/dev/null |
    tr -d '\r' |
    sed -n '\#/\(Code\|Code - OSS\)$#p'
)"
[[ "$(sed '/^$/d' <<<"$code_configs" | wc -l)" == 1 ]] ||
  archphene_die "could not identify one exact Code configuration directory"
code_config="$(sed -n '1p' <<<"$code_configs")"
archphene_adb_run shell run-as "$manager" test ! -e "$code_config_backup" ||
  archphene_die "Code configuration backup path already exists"
archphene_adb_run shell \
  "run-as $manager sh -c 'cp -a -- \"$code_config\" \"$code_config_backup\"'"
code_config_backed_up=true
code_config_inventory_before="$(code_config_inventory)"

start_manager() {
  archphene_adb_run shell am force-stop "$manager" >/dev/null
  archphene_adb_run shell am start -W -n "$manager_activity" >/dev/null
  archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 30 >/dev/null
  if [[ -z "$original_section" ]]; then
    local ui
    ui="$(archphene_capture_ui "code-git-initial-section-$serial")"
    original_section="$(
      python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal", "Settings"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$ui"
    )"
    [[ -n "$original_section" ]] ||
      archphene_die "could not determine the original manager section"
  fi
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

trap - EXIT
cleanup
[[ "$(code_config_inventory)" == "$code_config_inventory_before" ]] ||
  archphene_die "Code Git gate did not restore the exact Code configuration"
archphene_adb_run shell run-as "$manager" test ! -e "$code_config_backup" ||
  archphene_die "Code Git gate left its configuration backup"
assert_manager_restored
archphene_note "Archphene Code Git regression passed on $serial"
archphene_note "  Code integrated Bash completed git init/add/status: ${probe_result//$'\n'/; }"
archphene_note "  Full-device screenshot: $output_dir/$serial-code-git.png"
