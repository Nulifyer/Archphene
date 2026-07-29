#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=false
require_extended_shells=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --skip-install) skip_install=true; shift ;;
    --require-extended-shells) require_extended_shells=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH] [--skip-install] [--require-extended-shells]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
if [[ -z "$apk" ]]; then
  apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
output_dir="$ARCHPHENE_ROOT/tooling/build/shell-selection-regression"
mkdir -p "$output_dir"
original_shells_mode=

cleanup() {
  if [[ -n "$original_shells_mode" ]]; then
    archphene_adb_run shell run-as "$package" chmod "$original_shells_mode" \
      files/arch-root/etc/shells >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell cmd statusbar collapse >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm grant "$package" android.permission.POST_NOTIFICATIONS \
  >/dev/null 2>&1 || true
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/bash ||
  archphene_die "shell-selection regression requires installed bash"
archphene_adb_run shell run-as "$package" test -x files/arch-root/usr/bin/sh ||
  archphene_die "shell-selection regression requires installed sh"
extended_shells=true
for shell in zsh fish; do
  if ! archphene_adb_run shell run-as "$package" test -x \
      "files/arch-root/usr/bin/$shell"; then
    extended_shells=false
  fi
done
if [[ "$require_extended_shells" == true && "$extended_shells" == false ]]; then
  archphene_die "extended shell regression requires installed zsh and fish"
fi
original_shells_mode="$(
  archphene_adb_run shell run-as "$package" stat -c %a files/arch-root/etc/shells |
    tr -d '\r'
)"
[[ "$original_shells_mode" =~ ^[0-7]{3,4}$ ]] ||
  archphene_die "could not read the original /etc/shells mode"

start_manager() {
  local name="$1"
  archphene_adb_run shell am force-stop "$package" >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
  archphene_open_manager_section Terminal "$name-terminal"
  archphene_wait_ui 'class="android.widget.Spinner"[^>]*content-desc="Shell"' \
    "$name-shell-selector" 20
}

select_shell() {
  local label="$1" name="$2"
  archphene_tap_ui_pattern "$ARCHPHENE_UI" \
    'class="android.widget.Spinner"[^>]*content-desc="Shell"' 'shell selector'
  archphene_wait_ui "text=\"$label\"" "$name-shell-popup" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" "text=\"$label\"" "$label"
  archphene_wait_ui \
    "class=\"android.widget.Spinner\"[^>]*enabled=\"true\"[^>]*>.*text=\"$label\"" \
    "$name-shell-selected" 10
}

assert_selected_shell() {
  local label="$1" name="$2"
  archphene_wait_ui \
    "class=\"android.widget.Spinner\"[^>]*enabled=\"true\"[^>]*>.*text=\"$label\"" \
    "$name-shell-persisted" 20
}

archphene_adb_run logcat -c
start_manager "archphene-shell-selection-$serial"
select_shell "POSIX shell" "archphene-posix-$serial"

# A real process restart proves the stable shell identifier was durably saved,
# rather than surviving only in the Activity or Service heap.
start_manager "archphene-posix-restart-$serial"
archphene_open_manager_section Terminal "archphene-posix-terminal-$serial"
assert_selected_shell "POSIX shell" "archphene-posix-restart-$serial"
archphene_wait_ui 'text="Start shell"' "archphene-posix-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start POSIX shell'
archphene_wait_log 'Shared POSIX shell session started' 20 >/dev/null
archphene_wait_ui 'text="Shared shell ready"' "archphene-posix-ready-$serial" 20
archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  "archphene-posix-terminal-$serial" 20
archphene_wait_ui 'sh-[0-9][^"]*\$' "archphene-posix-prompt-$serial" 20
if archphene_regex_contains "$ARCHPHENE_UI" \
  'class="android.widget.Spinner"[^>]*content-desc="Shell"'; then
  archphene_die "shell selector remained visible while the POSIX shell was running"
fi
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-posix.png"
archphene_wait_ui 'text="Stop shell"' "archphene-posix-stop-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop POSIX shell'
archphene_wait_log 'Shared POSIX shell session finished with status stopped' 20 >/dev/null
archphene_wait_ui 'Shared shell stopped' "archphene-posix-stopped-$serial" 20

select_shell "Bash" "archphene-bash-$serial"
start_manager "archphene-bash-restart-$serial"
assert_selected_shell "Bash" "archphene-bash-restart-$serial"
archphene_wait_ui 'text="Start shell"' "archphene-bash-start-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' 'start Bash'
archphene_wait_log 'Shared Bash session started' 20 >/dev/null
archphene_wait_ui 'text="Shared shell ready"' "archphene-bash-ready-$serial" 20
archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
  "archphene-bash-terminal-$serial" 20
archphene_wait_ui 'archphene:~\$' "archphene-bash-prompt-$serial" 20
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-bash.png"
archphene_wait_ui 'text="Stop shell"' "archphene-bash-stop-$serial" 10
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' 'stop Bash'
archphene_wait_log 'Shared Bash session finished with status stopped' 20 >/dev/null
archphene_wait_ui 'Shared shell stopped' "archphene-bash-stopped-$serial" 20

exercise_extended_shell() {
  local label="$1" id="$2" prompt_pattern="$3"
  select_shell "$label" "archphene-$id-$serial"
  start_manager "archphene-$id-restart-$serial"
  assert_selected_shell "$label" "archphene-$id-restart-$serial"
  archphene_wait_ui 'text="Start shell"' "archphene-$id-start-$serial" 15
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Start shell"' "start $label"
  archphene_wait_log "Shared $label session started" 20 >/dev/null
  archphene_wait_ui 'text="Shared shell ready"' "archphene-$id-ready-$serial" 20
  archphene_wait_ui 'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "archphene-$id-terminal-$serial" 20
  archphene_wait_ui "$prompt_pattern" "archphene-$id-prompt-$serial" 20
  archphene_adb_run exec-out screencap -p >"$output_dir/$serial-$id.png"
  archphene_wait_ui 'text="Stop shell"' "archphene-$id-stop-$serial" 10
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Stop shell"' "stop $label"
  archphene_wait_log "Shared $label session finished with status stopped" 20 >/dev/null
  archphene_wait_ui 'Shared shell stopped' "archphene-$id-stopped-$serial" 20
}

if [[ "$extended_shells" == true ]]; then
  exercise_extended_shell Zsh zsh 'localhost%'
  exercise_extended_shell Fish fish 'archphene@localhost'
  select_shell Bash "archphene-bash-restore-$serial"
  start_manager "archphene-bash-restored-$serial"
  assert_selected_shell Bash "archphene-bash-restored-$serial"
fi

# A same-UID Linux process can damage user-owned configuration. Shell
# discovery must fail closed without taking the package manager down, and must
# recover after the exact file mode is restored.
archphene_adb_run shell run-as "$package" chmod 666 files/arch-root/etc/shells
start_manager "archphene-unsafe-shells-$serial"
archphene_wait_log 'Installed shell catalog unavailable' 20 >/dev/null
archphene_wait_ui 'text="No supported shell"' \
  "archphene-unsafe-shells-message-$serial" 15
archphene_wait_ui 'text="Start shell"[^>]*enabled="false"' \
  "archphene-unsafe-shells-disabled-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-unsafe-catalog.png"
archphene_adb_run shell run-as "$package" chmod "$original_shells_mode" \
  files/arch-root/etc/shells
start_manager "archphene-shells-recovered-$serial"
assert_selected_shell "Bash" "archphene-shells-recovered-$serial"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "shell-selection regression emitted a fatal runtime error: $fatal_log"

archphene_note "Archphene installed-shell selection regression passed on $serial"
archphene_note "  POSIX shell and Bash launch, hide, stop, and process persistence passed"
if [[ "$extended_shells" == true ]]; then
  archphene_note "  Zsh and Fish discovery, persistence, launch, and stop passed"
fi
archphene_note "  Full-device screenshots: $output_dir/$serial-posix.png"
archphene_note "                           $output_dir/$serial-bash.png"
if [[ "$extended_shells" == true ]]; then
  archphene_note "                           $output_dir/$serial-zsh.png"
  archphene_note "                           $output_dir/$serial-fish.png"
fi
archphene_note "                           $output_dir/$serial-unsafe-catalog.png"
