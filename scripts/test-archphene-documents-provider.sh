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
receiver="$package/org.archphene.app.DocumentsProviderTestReceiver"
authority="$package.documents"
token="$(printf '%08x' "$((RANDOM * 65536 + RANDOM))")"
fixture="Archphene-Documents-$token"
fixture_path="files/arch-root/home/archphene/$fixture"
action_run=org.archphene.app.debug.action.RUN_DOCUMENTS_PROVIDER_TEST
action_clean=org.archphene.app.debug.action.CLEAN_DOCUMENTS_PROVIDER_TEST
output_dir="$ARCHPHENE_ROOT/tooling/build/documents-provider"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am broadcast -n "$receiver" -a "$action_clean" \
    --es token "$token" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null

package_dump="$(archphene_adb_run shell dumpsys package "$package")"
[[ "$package_dump" == *"$authority"* &&
    "$package_dump" == *android.content.action.DOCUMENTS_PROVIDER* ]] ||
  archphene_die "Archphene DocumentsProvider is not registered"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_run" \
  --es token "$token" --ez retain_visual true >/dev/null
probe_log="$(archphene_wait_log \
  'DocumentsProvider probe (passed|failed).*token='"$token" \
  30 'ArchpheneDocumentsTest:V AndroidRuntime:E *:S')"
[[ "$probe_log" == *"DocumentsProvider probe passed token=$token"* ]] ||
  archphene_die "DocumentsProvider CRUD/security probe failed: $probe_log"

visual_content="$(archphene_adb_run shell run-as "$package" \
  cat "$fixture_path/Welcome.txt" | tr -d '\r')"
[[ "$visual_content" == "This file is shared from Archphene Home." ]] ||
  archphene_die "DocumentsProvider visual fixture content mismatch"
archphene_adb_run shell run-as "$package" test ! -e "$fixture_path/private-link" ||
  archphene_die "DocumentsProvider probe left a symbolic link"

archphene_adb_run shell am force-stop com.google.android.documentsui \
  >/dev/null 2>&1 || true
archphene_adb_run shell am force-stop com.android.documentsui \
  >/dev/null 2>&1 || true
archphene_adb_run shell am start -W -a android.intent.action.OPEN_DOCUMENT \
  -c android.intent.category.OPENABLE -t text/plain \
  --eu android.provider.extra.INITIAL_URI \
  "content://$authority/root/archphene-home" >/dev/null
archphene_wait_ui 'text="Archphene Home"' "documents-home-$serial" 20
archphene_wait_ui "text=\"$fixture\"" "documents-fixture-$serial" 20
archphene_wait_ui 'text="Shell startup files"' "documents-startup-folder-$serial" 20
home_ui="$ARCHPHENE_UI"
[[ "$home_ui" != *'text=".bashrc"'* &&
    "$home_ui" != *'text=".bash_profile"'* &&
    "$home_ui" != *'text="private-link"'* ]] ||
  archphene_die "DocumentsUI exposed a private or symbolic-link entry"
archphene_tap_text "$home_ui" "Shell startup files"
archphene_wait_ui 'text="Edit .bashrc"' \
  "documents-bashrc-$serial" 15
archphene_wait_ui 'text="Edit .bash_profile"' \
  "documents-bash-profile-$serial" 15
startup_ui="$ARCHPHENE_UI"
[[ "$startup_ui" != *'text=".secret"'* &&
    "$startup_ui" != *'text="private-link"'* ]] ||
  archphene_die "DocumentsUI exposed an unreviewed private startup entry"
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-shell-startup.png"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui "text=\"$fixture\"" "documents-fixture-return-$serial" 15
home_ui="$ARCHPHENE_UI"
archphene_tap_text "$home_ui" "$fixture"
archphene_wait_ui 'text="Welcome.txt"' "documents-welcome-$serial" 15

archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

archphene_adb_run logcat -c
archphene_adb_run shell am broadcast -n "$receiver" -a "$action_clean" \
  --es token "$token" >/dev/null
archphene_wait_log "DocumentsProvider cleanup passed token=$token" \
  15 'ArchpheneDocumentsTest:V *:S' >/dev/null
archphene_adb_run shell run-as "$package" test ! -e "$fixture_path" ||
  archphene_die "DocumentsProvider cleanup left its fixture"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "DocumentsProvider emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene DocumentsProvider passed on $serial"
archphene_note "  Exact create/read/write/rename/delete and collision behavior passed"
archphene_note "  Only .bashrc/.bash_profile are exposed through the reviewed startup folder"
archphene_note "  Hidden, traversal, bidi-spoof, and symlink access were rejected"
archphene_note \
  "  Full-device screenshots: $output_dir/$serial{,-shell-startup}.png"
