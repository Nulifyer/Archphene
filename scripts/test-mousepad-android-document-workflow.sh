#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
label=Mousepad
name=
marker=
timeout=60
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --label) label="${2:?}"; shift 2 ;;
    --name) name="${2:?}"; shift 2 ;;
    --marker) marker="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_test_init "$serial"

suffix="$(date +%s)-$RANDOM"
name="${name:-archphene-android-workflow-$suffix.txt}"
marker="${marker:-archphenedocsync-$suffix}"
[[ "$name" =~ ^[A-Za-z0-9._-]+$ && "$marker" =~ ^[A-Za-z0-9._-]+$ ]] \
  || archphene_die 'fixture name and marker may contain only letters, digits, dot, underscore, and hyphen'
label_pattern='^[A-Za-z0-9._+ -]{1,64}$'
[[ "$label" =~ $label_pattern ]] \
  || archphene_die 'application label contains unsupported characters'
[[ "$name" == *.txt ]] || archphene_die 'the document workflow fixture must use a .txt name'
[[ "$timeout" =~ ^[0-9]+$ ]] \
  || archphene_die '--timeout-seconds must be 15..180'
((timeout >= 15 && timeout <= 180)) \
  || archphene_die '--timeout-seconds must be 15..180'

remote="/sdcard/Download/$name"
imported="files/linux-home/Documents/Android/$name"
second_name="${name%.txt} (2).txt"
second_imported="files/linux-home/Documents/Android/$second_name"
encoded_document="$(python3 -c \
  'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' \
  "raw:/storage/emulated/0/Download/$name")"
source_uri="content://com.android.providers.downloads.documents/document/$encoded_document"
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/document-workflow"
documents_ui=com.google.android.documentsui

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$documents_ui" >/dev/null 2>&1 || true
  archphene_adb_run shell rm -f "$remote" \
    /sdcard/mousepad-android-document-workflow.png \
    /sdcard/mousepad-document-picker-recent.xml \
    /sdcard/mousepad-document-picker-roots.xml \
    /sdcard/mousepad-document-picker-downloads.xml \
    /sdcard/mousepad-document-picker-search.xml \
    /sdcard/mousepad-provider-picker.xml \
    /sdcard/mousepad-provider-roots.xml \
    /sdcard/mousepad-provider-apps.xml \
    /sdcard/mousepad-provider-home.xml \
    /sdcard/mousepad-provider-documents.xml \
    /sdcard/mousepad-provider-android.xml >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$package" sh -c \
    "'rm -f \"$imported\" \"$second_imported\"'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_private_contains() {
  local path="$1" expected="$2" failure="$3" deadline content
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    content="$(archphene_adb_run shell run-as "$package" sh -c "'cat \"$path\"'" \
      2>/dev/null | tr -d '\r' || true)"
    [[ "$content" == *"$expected"* ]] && return 0
    sleep 0.4
  done
  archphene_die "$failure"
}

wait_remote_contains() {
  local expected="$1" failure="$2" deadline content
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    content="$(archphene_adb_run shell cat "$remote" 2>/dev/null \
      | tr -d '\r' || true)"
    [[ "$content" == *"$expected"* ]] && return 0
    sleep 0.4
  done
  archphene_die "$failure; remote content was $(printf '%q' "$content")"
}

open_roots() {
  local name="$1" deadline ui
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "$name" 2>/dev/null || true)"
    if [[ "$ui" == *'content-desc="Show roots"'* ]]; then
      archphene_tap_ui_pattern "$ui" 'content-desc="Show roots"' \
        'document roots'
      sleep 0.7
      return 0
    fi
    if [[ "$ui" == *'resource-id="com.google.android.documentsui:id/search_src_text"'* ]]; then
      archphene_adb_run shell input keyevent KEYCODE_BACK
    fi
    sleep 0.5
  done
  archphene_die 'Android document picker did not expose its roots action'
}

archphene_adb_run shell pm path "$package" >/dev/null
activity="$(archphene_launcher "$package")"
archphene_adb_run shell test ! -e "$remote" \
  || archphene_die "refusing to overwrite existing Android document: $remote"
if archphene_adb_run shell run-as "$package" test -e "$imported" 2>/dev/null; then
  archphene_die "refusing to overwrite existing imported document: $imported"
fi

fixture_base64="$(printf 'Android workflow original\nsecond source line\n' | base64 -w0)"
archphene_adb_run shell mkdir -p /sdcard/Download
archphene_adb_run shell sh -c \
  "'printf %s $fixture_base64 | base64 -d > $remote'"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am force-stop "$documents_ui"
archphene_adb_run shell am start -W \
  -n "$package/org.archphene.bridge.DocumentOpenActivity" >/dev/null
open_roots mousepad-document-picker-recent
archphene_wait_ui 'text="Downloads"' mousepad-document-picker-roots "$timeout"
archphene_tap_text "$ARCHPHENE_UI" Downloads
archphene_wait_ui 'content-desc="Search"' mousepad-document-picker-downloads "$timeout"
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'content-desc="Search"' 'Downloads search'
sleep 0.4
archphene_adb_run shell input text "$name"
archphene_wait_ui "text=\"$name\"[^>]*resource-id=\"android:id/title\"" \
  mousepad-document-picker-search "$timeout"
archphene_tap_ui_pattern "$ARCHPHENE_UI" \
  "text=\"$name\"[^>]*resource-id=\"android:id/title\"" "$name result"

wait_private_contains "$imported" 'Android workflow original' \
  "$label did not import the selected Android document"
archphene_wait_log 'mapped=true.*primary=true' "$timeout" \
  'ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E *:S' >/dev/null

read -r display_width display_height <<<"$(archphene_adb_run shell wm size \
  | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
[[ -n "${display_width:-}" && -n "${display_height:-}" ]] \
  || archphene_die 'could not read the Android display size for editor focus'
archphene_adb_run shell input tap \
  "$((display_width / 4))" "$((display_height * 3 / 5))"
sleep 0.5
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_MOVE_END
archphene_adb_run shell input keyevent KEYCODE_ENTER
marker_base64="$(printf %s "$marker" | base64 -w0)"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" \
  --es archphene_test_ime_commit_base64 "$marker_base64" >/dev/null
injected=false
injection_deadline=$((SECONDS + 10))
while ((SECONDS < injection_deadline)); do
  injection_log="$(archphene_adb_run logcat -d -v brief \
    -s ArchpheneInput:I AndroidRuntime:E '*:S')"
  [[ "$injection_log" != *'FATAL EXCEPTION'* ]] \
    || archphene_die "$label crashed during exact IME injection: $injection_log"
  if [[ "$injection_log" == *"Injected test IME preeditBytes=0 commitBytes=${#marker} submit=false"* ]]; then
    injected=true
    break
  fi
  sleep .25
done
if [[ "$injected" == false ]]; then
  # Older maintained debug wrappers may predate warm-intent IME injection.
  # Keep this document gate runnable through Android's normal keyboard path;
  # the focused IME suites independently require exact InputConnection bytes.
  archphene_adb_run shell input text "$marker"
fi
archphene_adb_run shell input keyboard keycombination \
  KEYCODE_CTRL_LEFT KEYCODE_S
wait_remote_contains "$marker" \
  "$label save did not write the edited marker back to Downloads"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -a android.intent.action.EDIT \
  -c android.intent.category.DEFAULT -t text/plain -d "$source_uri" "$package" \
  >/dev/null
cold_log="$(archphene_wait_log 'Imported Android document uri=.* path=.* writable=' \
  "$timeout" 'ArchpheneLinuxApp:I AndroidRuntime:E *:S')"
[[ "$cold_log" == *"Documents/Android/$second_name writable="* ]] \
  || archphene_die "cold reopen did not preserve the first import as $second_name: $cold_log"
cold_map="$(archphene_wait_log 'mapped=true.*primary=true' "$timeout" \
  'ArchpheneInput:V ArchpheneLinuxApp:I AndroidRuntime:E *:S')"
[[ "$cold_map" == *"Documents/Android/$second_name"* && "$cold_map" == *"$label"* ]] \
  || archphene_die "cold reopen did not map the collision-safe document in $label: $cold_map"
wait_private_contains "$second_imported" "$marker" \
  "cold-reopened $label document did not retain the saved marker"

archphene_adb_run shell am start -W -a android.intent.action.OPEN_DOCUMENT \
  -c android.intent.category.OPENABLE -t text/plain >/dev/null
open_roots mousepad-provider-picker
archphene_wait_ui 'text="Archphene (Apps|Home)"' mousepad-provider-roots "$timeout"
if [[ "$ARCHPHENE_UI" == *'text="Archphene Apps"'* ]]; then
  archphene_tap_text "$ARCHPHENE_UI" 'Archphene Apps'
else
  archphene_tap_text "$ARCHPHENE_UI" 'Archphene Home'
fi
sleep 0.8
provider_ui="$(archphene_capture_ui mousepad-provider-apps)"
if [[ "$provider_ui" == *"text=\"$label\""* ]]; then
  archphene_tap_text "$provider_ui" "$label"
  archphene_wait_ui 'text="Documents"' mousepad-provider-home "$timeout"
  provider_ui="$ARCHPHENE_UI"
fi
[[ "$provider_ui" == *'text="Documents"'* ]] \
  || archphene_die 'Archphene document provider did not expose Documents'
archphene_tap_text "$provider_ui" Documents
archphene_wait_ui 'text="Android"' mousepad-provider-documents "$timeout"
archphene_tap_text "$ARCHPHENE_UI" Android
archphene_wait_ui "text=\"$name\"" mousepad-provider-android "$timeout"

mkdir -p "$artifact_dir"
archphene_adb_run shell screencap -p /sdcard/mousepad-android-document-workflow.png
archphene_adb_run pull /sdcard/mousepad-android-document-workflow.png \
  "$artifact_dir/$serial-$suffix.png" >/dev/null
logs="$(archphene_adb_run logcat -d -v brief \
  -s ArchpheneDocuments:I ArchpheneLinuxApp:I AndroidRuntime:E '*:S')"
[[ "$logs" != *'FATAL EXCEPTION'* ]] \
  || archphene_die "$label document workflow crashed: $logs"

cleanup
trap - EXIT
archphene_note "$label Android document workflow passed on $serial: SAF picker, exact import, edit/save writeback, cold reopen, and DocumentsUI provider browse validated for $name."
