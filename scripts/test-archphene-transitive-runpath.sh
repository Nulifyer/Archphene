#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
root=files/arch-root
session_marker="$root/var/lib/archphene/session-active-v1"
output_dir="$ARCHPHENE_ROOT/tooling/build/transitive-runpath"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "transitive-runpath-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$initial_running" == true ]]; then
    archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
    restore_section || true
  fi
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
if archphene_adb_run shell run-as "$package" test -e "$session_marker"; then
  archphene_die "an active shared shell already exists; stop it before this gate"
fi
for required in usr/bin/bash usr/bin/foot; do
  archphene_adb_run shell run-as "$package" test -e "$root/$required" ||
    archphene_die "transitive RUNPATH gate requires installed $required"
done
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
if archphene_wait_ui_optional \
    'text="Don’t allow"' "transitive-runpath-notification-$serial" 3; then
  archphene_die \
    "notification permission is unresolved; initialize the installed manager before this gate"
fi
archphene_wait_ui 'text="Archphene is ready"' "transitive-runpath-ready-$serial" 30
initial_ui="$ARCHPHENE_UI"
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
' <<<"$initial_ui"
)"
archphene_open_manager_section Terminal "transitive-runpath-section-$serial"
archphene_wait_ui 'text="Start shell"' "transitive-runpath-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start shell'
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

archphene_wait_ui 'text="Stop shell"' "transitive-runpath-stop-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop shell'
archphene_wait_ui 'Shared shell stopped' \
  "transitive-runpath-stopped-$serial" 20
archphene_adb_run shell run-as "$package" test ! -e "$session_marker" ||
  archphene_die "transitive RUNPATH gate left an active shell marker"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "transitive RUNPATH gate emitted a fatal error: $fatal_log"

archphene_note "Archphene transitive RUNPATH gate passed on $serial"
archphene_note "  Interactive Bash launched Foot through its installed ELF closure"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
