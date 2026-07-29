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
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
root=files/arch-root
output_dir="$ARCHPHENE_ROOT/tooling/build/transitive-runpath"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
for required in usr/bin/bash usr/bin/foot; do
  archphene_adb_run shell run-as "$package" test -e "$root/$required" ||
    archphene_die "transitive RUNPATH gate requires installed $required"
done
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
if archphene_wait_ui_optional \
    'text="Don’t allow"' "transitive-runpath-notification-$serial" 3; then
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" 'text="Don’t allow"' 'deny optional debug notification permission'
  # Android's permission controller can return to the launcher after denying
  # this first-run prompt. Bring the already-initialized manager task forward.
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
fi
archphene_wait_ui 'text="Archphene is ready"' "transitive-runpath-ready-$serial" 30
archphene_open_manager_section Terminal "transitive-runpath-section-$serial"
if ! archphene_wait_ui_optional \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "transitive-runpath-existing-$serial" 2 >/dev/null 2>&1; then
  archphene_wait_ui 'text="Start shell"' "transitive-runpath-start-$serial" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
fi
archphene_wait_ui \
  'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  "transitive-runpath-prompt-$serial" 20

# This is deliberately entered through the interactive Bash PTY. Foot is a
# nested exec here, so the exact-ABI preload bridge traverses its installed ELF
# graph rather than relying on Rust's initial launcher preparation alone. The
# host bridge gate separately supplies a deterministic transitive absolute
# RUNPATH fixture.
archphene_enter_terminal_line \
  "foot --version" \
  "transitive-runpath-command-$serial"
archphene_wait_ui 'foot version: [0-9]' "transitive-runpath-output-$serial" 20
sleep 0.5
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "transitive RUNPATH gate emitted a fatal error: $fatal_log"

archphene_note "Archphene transitive RUNPATH gate passed on $serial"
archphene_note "  Interactive Bash launched Foot through its installed ELF closure"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
