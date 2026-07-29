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
cache_path="$root/var/cache/fontconfig"
output_dir="$ARCHPHENE_ROOT/tooling/build/system-fonts"
state_dir="$(archphene_mktemp_dir system-fonts-state)"
cache_archive="$state_dir/fontconfig.tar"
restored_archive="$state_dir/fontconfig-restored.tar"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=
cache_existed=false
cache_snapshot_taken=false

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "system-fonts-restore-$serial" 2>/dev/null || true)"
  if archphene_regex_contains \
    "$ui" "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

restore_cache() {
  [[ "$cache_snapshot_taken" == true ]] || return 0
  archphene_adb_run shell run-as "$package" rm -rf "$cache_path" \
    >/dev/null
  if [[ "$cache_existed" == true ]]; then
    archphene_adb_run shell run-as "$package" tar -C "$root" -xf - \
      <"$cache_archive" >/dev/null
    archphene_adb_run exec-out run-as "$package" tar -C "$root" \
      -cf - var/cache/fontconfig >"$restored_archive"
    cmp -s "$cache_archive" "$restored_archive" ||
      archphene_die "fontconfig cache was not restored exactly"
  else
    archphene_adb_run shell run-as "$package" test ! -e "$cache_path" ||
      archphene_die "fontconfig cache was created but not removed"
  fi
}

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  restore_cache
  rm -f "$cache_archive" "$restored_archive"
  rmdir "$state_dir" >/dev/null 2>&1 || true
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
for command in fc-cache fc-match; do
  archphene_adb_run shell run-as "$package" test -x "$root/usr/bin/$command" ||
    archphene_die "system-font gate requires installed $command"
done
if archphene_adb_run shell run-as "$package" test -d "$cache_path"; then
  cache_existed=true
  archphene_adb_run exec-out run-as "$package" tar -C "$root" \
    -cf - var/cache/fontconfig >"$cache_archive"
fi
cache_snapshot_taken=true

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
if archphene_wait_ui_optional \
    'text="Don’t allow"' "system-font-notification-$serial" 3; then
  archphene_die \
    "notification permission is unresolved; initialize the installed manager before this gate"
fi
archphene_wait_ui 'text="Archphene is ready"' "system-font-ready-$serial" 30
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
archphene_open_manager_section Terminal "system-font-terminal-$serial"

archphene_run_debug_linux_command "$package" "fc-cache -v /system/fonts"
archphene_wait_ui \
  'text="Exited 0[^"]*/system/fonts[^"]*[1-9][0-9]* fonts' \
  "system-font-cache-$serial" 30

archphene_run_debug_linux_command "$package" "fc-match DroidSansMono"
archphene_wait_ui \
  'Exited 0[^>]*DroidSansMono\.ttf[^>]*Droid Sans Mono' \
  "system-font-match-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "system-font gate emitted a fatal error: $fatal_log"

archphene_note "Archphene Android system-font gate passed on $serial"
archphene_note "  Fontconfig enumerated /system/fonts and matched Droid Sans Mono"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
