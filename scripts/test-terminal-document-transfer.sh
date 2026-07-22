#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"
serial=emulator-5554
while (($#)); do case "$1" in --serial) serial="${2:?}"; shift 2;; *) archphene_die "unknown argument: $1";; esac; done
archphene_test_init "$serial"; package=org.archpheneos.terminal; source_name=archphene-transfer-source.txt; export_name=archphene-transfer-export.txt
archphene_adb_run shell rm -f "/sdcard/Download/$source_name" "/sdcard/Download/$export_name"; archphene_adb_run shell mkdir -p /sdcard/Download; archphene_adb_run shell sh -c "'printf android-import-verified > /sdcard/Download/$source_name'"; archphene_adb_run shell run-as "$package" sh -c "'mkdir -p files/terminal/home/Documents; printf terminal-export-verified > files/terminal/home/Documents/$export_name'"
archphene_adb_run shell am start -W -n "$package/.TerminalActivity" >/dev/null; sleep 2; archphene_adb_run shell input text archphene-import%sDownloads; archphene_adb_run shell input keyevent 66
archphene_wait_ui_text 'Show roots' transfer-roots 10; archphene_tap_ui_pattern "$ARCHPHENE_UI" 'content-desc="Show roots"' roots; archphene_wait_ui_text Downloads transfer-downloads 10; archphene_tap_text "$ARCHPHENE_UI" Downloads; archphene_wait_ui_text "$source_name" transfer-source 10; archphene_tap_text "$ARCHPHENE_UI" "$source_name"; sleep 2
imported="$(archphene_adb_run shell run-as "$package" cat "files/terminal/home/Downloads/$source_name" | tr -d '\r')"; [[ "$imported" == android-import-verified ]] || archphene_die 'import mismatch'
archphene_adb_run shell input text "archphene-export%sDocuments/$export_name"; archphene_adb_run shell input keyevent 66; archphene_wait_ui_text SAVE transfer-save 10; archphene_tap_text "$ARCHPHENE_UI" SAVE; sleep 2; exported="$(archphene_adb_run shell cat "/sdcard/Download/$export_name" | tr -d '\r')"; [[ "$exported" == terminal-export-verified ]] || archphene_die 'export mismatch'; archphene_note "Terminal import/export and path-boundary flow passed on $serial."
