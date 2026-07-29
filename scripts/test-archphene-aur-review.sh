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
manager=org.archphene.app.debug
package=visual-studio-code-bin
output_dir="$ARCHPHENE_ROOT/tooling/build/aur-review"
mkdir -p "$output_dir"

initial_running=false
if archphene_android_pid "$manager" >/dev/null 2>&1; then
  initial_running=true
fi

cleanup() {
  if [[ "$initial_running" == false ]]; then
    archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

local_package_count() {
  archphene_adb_run shell run-as "$manager" \
    ls files/arch-root/var/lib/pacman/local |
    tr -d '\r' |
    awk 'NF { count++ } END { print count + 0 }'
}

if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "$manager is not installed; pass --install-apk with --apk"
archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell monkey -p "$manager" \
  -c android.intent.category.LAUNCHER 1 >/dev/null
archphene_wait_ui 'text="Archphene is ready"' aur-review-ready 30
before_count="$(local_package_count)"
[[ "$before_count" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "could not read the installed-package count"

ui="$(archphene_capture_ui aur-review-input)"
archphene_tap_ui_pattern \
  "$ui" 'class="android\.widget\.EditText"' "package input"
archphene_adb_run shell input text "$package"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui \
  "text=\"$package\"[^>]*class=\"android\\.widget\\.EditText\"" \
  aur-review-package 10
archphene_wait_ui 'text="AUR"[^>]*enabled="true"' aur-review-action 10
ui="$ARCHPHENE_UI"
archphene_tap_ui_pattern \
  "$ui" 'text="AUR"[^>]*enabled="true"' "AUR"

archphene_wait_log \
  "Reviewed AUR $package .* commit=[0-9a-f]{40}" 45 \
  'ArchpheneRuntime:I *:S' >/dev/null
archphene_wait_ui 'AUR community package' aur-review-result 20
ui="$ARCHPHENE_UI"
for pattern in \
  "$package [^<]*" \
  'Community PKGBUILD; not an official signed Arch package\.' \
  'AUR commit: [0-9a-f]{40}' \
  'Snapshot SHA-256: [0-9a-f]{64}' \
  'Unverified sources: none' \
  'Insecure source transports: none' \
  'linux-deb-arm64' \
  'Install script: visual-studio-code-bin\.install' \
  'PKGBUILD'
do
  archphene_regex_contains "$ui" "$pattern" ||
    archphene_die "AUR review omits required UI evidence: $pattern"
done
archphene_regex_contains \
  "$ui" 'text="Install"[^>]*enabled="false"' ||
  archphene_die "reviewing AUR content unexpectedly enabled official install"

after_count="$(local_package_count)"
[[ "$after_count" == "$before_count" ]] ||
  archphene_die "AUR review mutated the pacman database"

archphene_adb_run shell screencap -p /sdcard/archphene-aur-review.png
archphene_adb_run pull /sdcard/archphene-aur-review.png \
  "$output_dir/$serial.png" >/dev/null
archphene_adb_run shell rm /sdcard/archphene-aur-review.png

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "AUR review emitted a fatal Android error: $fatal_log"

archphene_note "Archphene AUR review passed on $serial"
archphene_note "  Live $package metadata and AArch64 snapshot were reviewed"
archphene_note "  Pacman state remained at $after_count local database entries"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
