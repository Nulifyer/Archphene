#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
clean_data=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --clean-data) clean_data=true; shift ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --clean-data"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ "$clean_data" == true ]] ||
  archphene_die "--clean-data is required because this gate clears Archphene app data"

archphene_test_init "$serial"
archphene_require_file "$apk"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.PackagePhaseTestReceiver"
action=org.archphene.app.debug.action.START_PACKAGE_PHASES
output_dir="$ARCHPHENE_ROOT/tooling/build/package-phases"
mkdir -p "$output_dir"
serial_slug="${serial,,}"
serial_slug="${serial_slug//[^a-z0-9]/-}"

cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell pm clear "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_skip_storage_onboarding "package-phases-onboarding-$serial"
archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "phases-$serial_slug" \
  --es package phase-fixture \
  --ei hold-ms 5000 >/dev/null
archphene_wait_log \
  "Started package phases=true token=phases-$serial_slug" 15 \
  "ArchphenePackagePhaseProbe:V *:S" >/dev/null

states=(
  "Queued"
  "Resolving"
  "Downloading"
  "Verifying"
  "Building"
  "Publishing"
  "Installing"
  "Awaiting Android confirmation"
  "Complete"
)
progress=(0 5 25 50 65 78 88 95 100)
messages=(
  "Queued"
  "Resolving signed dependency closure"
  "Downloading verified package archives"
  "Verifying package signatures"
  "Building Android launcher"
  "Publishing verified runtime pack"
  "Installing Linux package transaction"
  "Awaiting Android installation confirmation"
  "Installed phase-fixture 1.0.0"
)

for index in "${!states[@]}"; do
  state="${states[index]}"
  percent="${progress[index]}"
  message="${messages[index]}"
  slug="${state,,}"
  slug="${slug// /-}"
  archphene_wait_ui_exact_text \
    "Install · $state · $percent%" "package-phases-$slug-$serial" 12
  ui="$ARCHPHENE_UI"
  [[ "$ui" == *"text=\"$message\""* ]] ||
    archphene_die "$state did not render its exact durable message"
  if ((index <= 5)); then
    archphene_regex_contains \
      "$ui" \
      'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"[^>]*enabled="true"' ||
      archphene_die "$state did not expose safe cancellation"
  elif archphene_regex_contains \
    "$ui" 'text="(?:CANCEL|Cancel)"[^>]*class="android.widget.Button"'; then
    archphene_die "$state exposed cancellation after the mutation boundary"
  fi
  archphene_adb_run exec-out screencap -p >"$output_dir/$serial-$slug.png"
done

cache_contents="$(
  archphene_adb_run exec-out run-as "$package" find \
    files/arch-root/var/cache/pacman/pkg -mindepth 1 -maxdepth 1 -type f |
    tr -d '\r'
)"
[[ -z "$cache_contents" ]] ||
  archphene_die "phase presentation fixture created package cache files: $cache_contents"
installed_count=0
if archphene_adb_run shell run-as "$package" test -d \
  files/arch-root/var/lib/pacman/local; then
  installed_count="$(
    archphene_adb_run exec-out run-as "$package" find \
      files/arch-root/var/lib/pacman/local -mindepth 1 -maxdepth 1 -type d \
      -name 'phase-fixture-*' -print |
      tr -cd '\n' |
      wc -l
  )"
fi
((installed_count == 0)) ||
  archphene_die "phase presentation fixture mutated the package database"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_wait_ui_exact_text \
  "Install · Complete · 100%" "package-phases-restored-$serial" 20
archphene_wait_ui_exact_text \
  "Installed phase-fixture 1.0.0" "package-phases-restored-message-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-restored.png"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package phase presentation emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
archphene_note "Archphene durable package phases passed on $serial"
archphene_note "  Every journal state rendered with correct progress and cancellation boundary"
archphene_note "  Complete survived cold restart; no package/cache mutation occurred"
archphene_note "  Full-device screenshots: $output_dir/$serial-{queued..complete,restored}.png"
