#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
non_audio_package=
artifact_dir="$ARCHPHENE_ROOT/tooling/build/evidence/audio-output"
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --non-audio-package)
      non_audio_package="${2:?missing value for --non-audio-package}"
      shift 2
      ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package CURRENT_AUDIO_LAUNCHER [--non-audio-package PACKAGE] [--artifact-dir DIR]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" && -n "$package" ]] ||
  archphene_die "--serial and --package are required"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package is not a generated Archphene launcher"
if [[ -n "$non_audio_package" ]]; then
  [[ "$non_audio_package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
    archphene_die "--non-audio-package is not a generated Archphene launcher"
fi

archphene_test_init "$serial"
manager=org.archphene.app.debug
receiver="$manager/org.archphene.app.LauncherSessionTestReceiver"
action=org.archphene.app.debug.action.PLAY_LAUNCHER_AUDIO
token=launcher-session-gate
mkdir -p "$artifact_dir"
temporary="$(mktemp -d "$ARCHPHENE_ROOT/tooling/build/audio-bridge-test.XXXXXX")"
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ -n "$non_audio_package" ]]; then
    archphene_adb_run shell am force-stop "$non_audio_package" >/dev/null 2>&1 || true
  fi
  rm -rf "$temporary"
}
trap cleanup EXIT

archphene_adb_run shell pm path "$manager" >/dev/null ||
  archphene_die "current Archphene manager is not installed"
installed_path="$(
  archphene_adb_run shell pm path "$package" |
    head -n1 |
    sed 's/^package://;s/\r$//'
)"
[[ -n "$installed_path" ]] || archphene_die "audio launcher is not installed"
archphene_adb_run pull "$installed_path" "$temporary/audio.apk" >/dev/null
manifest="$(apkanalyzer manifest print "$temporary/audio.apk")"
archphene_regex_contains \
  "$manifest" \
  'android:name="org\.archphene\.launcher\.MANAGER_PACKAGE"[^>]*android:value="org\.archphene\.app\.debug"' ||
  archphene_die "audio launcher is not owned by the current manager"
archphene_regex_contains \
  "$manifest" \
  'android:name="org\.archphene\.launcher\.CAPABILITIES"[^>]*android:value="c:wayland,input,ime,clipboard,documents,open-uri,notifications,audio-output(?:,audio-input)?(?:,printing)?(?:,accessibility)?"' ||
  archphene_die "audio launcher does not carry the exact audio-output contract"
if [[ -n "$non_audio_package" ]]; then
  non_audio_installed_path="$(
    archphene_adb_run shell pm path "$non_audio_package" |
      head -n1 |
      sed 's/^package://;s/\r$//'
  )"
  [[ -n "$non_audio_installed_path" ]] ||
    archphene_die "non-audio launcher is not installed"
  archphene_adb_run pull "$non_audio_installed_path" "$temporary/non-audio.apk" >/dev/null
  non_audio_manifest="$(apkanalyzer manifest print "$temporary/non-audio.apk")"
  archphene_regex_contains \
    "$non_audio_manifest" \
    'android:name="org\.archphene\.launcher\.MANAGER_PACKAGE"[^>]*android:value="org\.archphene\.app\.debug"' ||
    archphene_die "non-audio launcher is not owned by the current manager"
  if archphene_regex_contains \
    "$non_audio_manifest" \
    'android:name="org\.archphene\.launcher\.CAPABILITIES"[^>]*android:value="[^"]*audio-output'; then
    archphene_die "non-audio launcher unexpectedly carries audio-output"
  fi
fi

manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
[[ "$manager_dump" == *"android.permission.RECORD_AUDIO"* ]] ||
  archphene_die "manager does not declare its implemented microphone broker permission"

activity="$(archphene_launcher "$package")"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private (?:AAudio|OpenSL ES) server ready session=[0-9]+' 30 \
  'ArchpheneAudio:I ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' >/dev/null
archphene_wait_log \
  'Linux Wayland client connected session=[0-9]+' 30 \
  'ArchpheneAudio:I ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' >/dev/null
archphene_wait_log \
  'Client authenticated anonymously' 20 \
  'ArchpheneAudio:I ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' >/dev/null

archphene_adb_run shell am broadcast \
  -n "$receiver" \
  -a "$action" \
  --es token "$token" \
  --es package "$package" >/dev/null
archphene_wait_log \
  'Created input [0-9]+ "Archphene output probe" on archphene_output' 20 \
  'ArchpheneAudio:I ArchpheneLauncherSessionProbe:V AndroidRuntime:E libc:F *:S' >/dev/null
archphene_wait_log \
  "Manager audio request package=$package session=[0-9]+ result=played" 20 \
  'ArchpheneAudio:I ArchpheneLauncherSessionProbe:V AndroidRuntime:E libc:F *:S' >/dev/null

# The client has authenticated and rendered by this point, but GTK can still be
# presenting its initial connection view for a few frames.
sleep 4
archphene_adb_run exec-out screencap -p >"$artifact_dir/full-device.png"

archphene_adb_run shell am force-stop "$package"
archphene_wait_log \
  'Releasing launcher resources session=[0-9]+ close=true' 20 \
  'ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' >/dev/null
audio_runtime_removed=false
audio_cleanup_deadline=$((SECONDS + 15))
while ((SECONDS < audio_cleanup_deadline)); do
  audio_cleanup_remaining=$((audio_cleanup_deadline - SECONDS))
  if ! audio_cache="$(
    timeout "${audio_cleanup_remaining}s" \
      "$ARCHPHENE_ADB" "${ARCHPHENE_ADB_ARGS[@]}" \
      shell run-as "$manager" ls -1 cache
  )"; then
    continue
  fi
  if ! printf '%s\n' "$audio_cache" | rg -q '^audio-'; then
    audio_runtime_removed=true
    break
  fi
  sleep 0.25
done
if [[ "$audio_runtime_removed" != true ]]; then
  archphene_die "manager retained a private audio runtime after launcher teardown"
fi
manager_user="$(archphene_adb_run shell run-as "$manager" id -un | tr -d '\r')"
audio_helpers="$(
  archphene_adb_run shell ps -A -o USER,PID,NAME |
    awk -v user="$manager_user" \
      '$1 == user && ($3 ~ /^libarchphene_pu/ || $3 ~ /^libarchphene_au/)'
)"
[[ -z "$audio_helpers" ]] ||
  archphene_die "manager retained an audio helper after launcher teardown: $audio_helpers"

if [[ -n "$non_audio_package" ]]; then
  non_audio_activity="$(archphene_launcher "$non_audio_package")"
  archphene_adb_run shell am force-stop "$non_audio_package"
  archphene_adb_run shell am start -W -n "$non_audio_activity" >/dev/null
  archphene_wait_log \
    "Authorized launcher package=$non_audio_package" 20 \
    'ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' >/dev/null
  archphene_wait_log \
    'Started manager-owned Linux process session=[0-9]+' 30 \
    'ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' >/dev/null
  archphene_adb_run shell am broadcast \
    -n "$receiver" \
    -a "$action" \
    --es token "$token" \
    --es package "$non_audio_package" >/dev/null
  archphene_wait_log \
    "Manager audio request package=$non_audio_package session=[0-9]+ result=audio-capability-denied" 15 \
    'ArchpheneLauncherSessionProbe:V *:S' >/dev/null
fi

log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneAudio:V ArchpheneLauncherSession:V \
    ArchpheneLauncherSessionProbe:V AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$log" != *"invalid ELF header"* && "$log" != *"CANNOT LINK EXECUTABLE"* ]] ||
  archphene_die "Android audio payload contaminated the glibc application path"
[[ "$log" != *"FATAL EXCEPTION"* && "$log" != *"Fatal signal"* ]] ||
  archphene_die "audio bridge emitted a fatal runtime error: $log"
[[ "$log" != *"did not report exit after forced termination"* &&
  "$log" != *"Pulse playback control did not terminate"* &&
  "$log" != *"Audio helper start did not finish"* &&
  "$log" != *"Timed out waiting for Pulse playback control"* &&
  "$log" != *"Could not remove audio runtime while a helper remains alive"* &&
  "$log" != *"Pulse playback control retries exhausted"* ]] ||
  archphene_die "audio bridge failed to reap a helper: $log"
printf '%s\n' "$log" >"$artifact_dir/log.txt"

trap - EXIT
cleanup
archphene_note "Manager-owned audio output passed on $serial"
archphene_note "  Stock Pulse client authenticated and remained a live Wayland app"
archphene_note "  Bounded 48 kHz stereo probe created and drained an AAudio sink input"
archphene_note "  Full-device screenshot: $artifact_dir/full-device.png"
