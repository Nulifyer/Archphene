#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
exercise_consent=false
exercise_denial=false
disable_privacy=false
expect_privacy_block=false
timeout=60
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    --exercise-consent) exercise_consent=true; shift ;;
    --exercise-denial) exercise_denial=true; shift ;;
    --temporarily-disable-microphone-privacy) disable_privacy=true; shift ;;
    --expect-microphone-privacy-block) expect_privacy_block=true; shift ;;
    --timeout-seconds) timeout="${2:?missing value for --timeout-seconds}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package CURRENT_AUDIO_INPUT_LAUNCHER [--manager PACKAGE] [--artifact-dir PATH] [--exercise-consent|--exercise-denial] [--temporarily-disable-microphone-privacy|--expect-microphone-privacy-block] [--timeout-seconds 30..180]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package must be a generated Archphene launcher"
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 30 && timeout <= 180)) ||
  archphene_die "--timeout-seconds must be 30..180"
[[ "$exercise_consent" != true || "$exercise_denial" != true ]] ||
  archphene_die "--exercise-consent and --exercise-denial are mutually exclusive"
[[ "$disable_privacy" != true || "$expect_privacy_block" != true ]] ||
  archphene_die "privacy disable and privacy-block expectation are mutually exclusive"

archphene_test_init "$serial"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/microphone-input/$serial_slug}"
mkdir -p "$artifact_dir"
temporary="$(mktemp -d "$ARCHPHENE_ROOT/tooling/build/microphone-test.XXXXXX")"
permission=android.permission.RECORD_AUDIO

manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
archphene_regex_contains "$manager_dump" "(?m)^\\s*$permission\\s*$" ||
  archphene_die "current manager does not declare microphone permission"
archphene_regex_contains \
  "$manager_dump" '(?m)^\s*android\.permission\.FOREGROUND_SERVICE_MICROPHONE\s*$' ||
  archphene_die "current manager does not declare microphone foreground-service permission"
initial_granted=false
archphene_regex_contains "$manager_dump" "$permission: granted=true" &&
  initial_granted=true

privacy_enabled=false
privacy_dump="$(archphene_adb_run shell dumpsys audio)"
archphene_regex_contains "$privacy_dump" 'mic mute .*from system=true' &&
  privacy_enabled=true
if [[
  "$privacy_enabled" == true &&
  "$disable_privacy" != true &&
  "$expect_privacy_block" != true
]]; then
  archphene_die \
    "Android microphone privacy is enabled; use --temporarily-disable-microphone-privacy to restore it after the gate"
fi
privacy_changed=false
broadcast_pid=

restore_permission() {
  if [[ "$initial_granted" == true ]]; then
    archphene_adb_run shell pm grant "$manager" "$permission" >/dev/null 2>&1 || true
  else
    archphene_adb_run shell pm revoke "$manager" "$permission" >/dev/null 2>&1 || true
    archphene_adb_run shell pm clear-permission-flags \
      "$manager" "$permission" user-set user-fixed >/dev/null 2>&1 || true
  fi
}
cleanup() {
  if [[ -n "$broadcast_pid" ]]; then
    kill "$broadcast_pid" >/dev/null 2>&1 || true
    wait "$broadcast_pid" >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  restore_permission
  if [[ "$privacy_changed" == true ]]; then
    archphene_adb_run shell cmd sensor_privacy enable 0 microphone >/dev/null 2>&1 ||
      archphene_note "warning: could not restore Android microphone privacy"
  fi
  rm -rf "$temporary"
}
trap cleanup EXIT

if [[ "$privacy_enabled" == true ]]; then
  if [[ "$disable_privacy" == true ]]; then
    archphene_adb_run shell cmd sensor_privacy disable 0 microphone >/dev/null
    privacy_changed=true
  fi
elif [[ "$expect_privacy_block" == true ]]; then
  archphene_die "Android microphone privacy is not enabled"
fi

if [[ "$exercise_consent" == true || "$exercise_denial" == true ]]; then
  archphene_adb_run shell pm revoke "$manager" "$permission" >/dev/null 2>&1 || true
  archphene_adb_run shell pm clear-permission-flags \
    "$manager" "$permission" user-set user-fixed >/dev/null
elif [[ "$initial_granted" != true ]]; then
  archphene_die \
    "microphone permission is not granted; use --exercise-consent to test and restore first-use consent"
fi

installed_path="$(
  archphene_adb_run shell pm path "$package" |
    head -n1 |
    sed 's/^package://;s/\r$//'
)"
[[ -n "$installed_path" ]] || archphene_die "audio-input launcher is not installed"
archphene_adb_run pull "$installed_path" "$temporary/launcher.apk" >/dev/null
manifest="$(apkanalyzer manifest print "$temporary/launcher.apk")"
archphene_regex_contains \
  "$manifest" \
  'android:name="org\.archphene\.launcher\.CAPABILITIES"[^>]*android:value="c:wayland,input,ime,clipboard,documents,open-uri,notifications,audio-output,audio-input(?:,printing)?"' ||
  archphene_die "launcher does not carry the exact audio-input contract"
[[ "$manifest" != *"$permission"* ]] ||
  archphene_die "thin launcher incorrectly declares the manager microphone permission"
[[ "$manifest" != *"android.permission.FOREGROUND_SERVICE_MICROPHONE"* ]] ||
  archphene_die "thin launcher incorrectly declares manager foreground-service authority"

manager_path="$(
  archphene_adb_run shell pm path "$manager" |
    head -n1 |
    sed 's/^package://;s/\r$//'
)"
[[ -n "$manager_path" ]] || archphene_die "manager APK is not installed"
archphene_adb_run pull "$manager_path" "$temporary/manager.apk" >/dev/null
manager_manifest="$(apkanalyzer manifest print "$temporary/manager.apk")"
archphene_regex_contains \
  "$manager_manifest" \
  'android:name="org\.archphene\.app\.launcher\.LauncherSessionService"[^>]*android:foregroundServiceType="0x80"' ||
  archphene_die "manager launcher service is not scoped to microphone foreground use"

receiver="$manager/org.archphene.app.LauncherSessionTestReceiver"
action=org.archphene.app.debug.action.CAPTURE_LAUNCHER_MICROPHONE
activity="$(archphene_launcher "$package")"
archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private PulseAudio microphone bridge ready session=[0-9]+' "$timeout" \
  'ArchpheneAudio:I ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' >/dev/null

archphene_adb_run shell am broadcast \
  -n "$receiver" \
  -a "$action" \
  --es token launcher-session-gate \
  --es package "$package" >"$temporary/broadcast.txt" 2>&1 &
broadcast_pid=$!

if [[ "$exercise_consent" == true || "$exercise_denial" == true ]]; then
  archphene_wait_ui 'text="Allow microphone for [^"]+\\?"' \
    microphone-manager-explanation "$timeout"
  archphene_adb_run exec-out screencap -p >"$artifact_dir/explanation.png"
  explanation_ui="$(archphene_capture_ui microphone-manager-explanation-tap)"
  if [[ "$exercise_denial" == true ]]; then
    archphene_tap_text "$explanation_ui" "Not now"
  else
    archphene_tap_text "$explanation_ui" Continue
  fi

  if [[ "$exercise_denial" != true ]]; then
    permission_pattern='resource-id="[^"]*:id/(?:permission_allow_(?:foreground_only|one_time)_button|permission_allow_button)"'
    archphene_wait_ui "$permission_pattern" microphone-android-permission "$timeout"
    archphene_adb_run exec-out screencap -p >"$artifact_dir/android-permission.png"
    permission_ui="$(archphene_capture_ui microphone-android-permission-tap)"
    archphene_tap_ui_pattern "$permission_ui" "$permission_pattern" "microphone Allow"
  fi
fi

if ! wait "$broadcast_pid"; then
  broadcast_pid=
  archphene_die "manager microphone probe failed: $(<"$temporary/broadcast.txt")"
fi
broadcast_pid=

if [[ "$exercise_denial" == true ]]; then
  denial_log="$(
    archphene_wait_log \
      "Manager microphone consent session=[0-9]+ result=denied" \
      "$timeout" \
      'ArchpheneAudio:V ArchpheneMicrophone:V ArchpheneLauncherSession:V ArchpheneLauncherSessionProbe:V AndroidRuntime:E libc:F *:S'
  )"
  [[ "$denial_log" != *"Android AAudio microphone capture started"* ]] ||
    archphene_die "denied microphone consent started Android capture"
  denied_dump="$(archphene_adb_run shell dumpsys package "$manager")"
  [[ "$denied_dump" != *"$permission: granted=true"* ]] ||
    archphene_die "denied microphone consent granted Android permission"
  archphene_adb_run exec-out screencap -p >"$artifact_dir/denied-full-device.png"
  archphene_adb_run shell am force-stop "$package"
  trap - EXIT
  cleanup
  archphene_note "Manager-owned microphone denial passed on $serial"
  archphene_note "  Denial screenshot: $artifact_dir/denied-full-device.png"
  exit 0
fi

capture_log="$(
  archphene_wait_log \
    "Manager microphone request package=$package session=[0-9]+ result=captured-[0-9]+-[0-9]+" \
    "$timeout" \
    'ArchpheneAudio:V ArchpheneMicrophone:V ArchpheneLauncher:V ArchpheneLauncherSession:V ArchpheneLauncherSessionProbe:V AndroidRuntime:E libc:F *:S'
)"
capture_result="$(
  sed -n \
    "s/.*Manager microphone request package=$package session=[0-9][0-9]* result=captured-\\([0-9][0-9]*\\)-\\([0-9][0-9]*\\).*/\\1 \\2/p" \
    <<<"$capture_log" |
    tail -n1
)"
read -r bytes nonzero <<<"$capture_result"
[[ "$bytes" =~ ^[0-9]+$ && "$nonzero" =~ ^[0-9]+$ ]] ||
  archphene_die "could not parse bounded microphone capture result"
((bytes >= 76800)) ||
  archphene_die "microphone capture was truncated: $bytes bytes"
if [[ "$expect_privacy_block" == true ]]; then
  ((nonzero == 0)) ||
    archphene_die "Android microphone privacy leaked $nonzero nonzero bytes"
else
  ((nonzero >= 1000)) ||
    archphene_die "microphone capture contains only silence: $nonzero nonzero bytes"
fi

archphene_adb_run exec-out screencap -p >"$artifact_dir/full-device.png"
log="$(
  archphene_adb_run logcat -d -v brief \
    -s ArchpheneAudio:V ArchpheneMicrophone:V ArchpheneLauncher:V \
    ArchpheneLauncherSession:V ArchpheneLauncherSessionProbe:V \
    AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
for evidence in \
  'Linux microphone stream attached' \
  'Android microphone permission granted' \
  'Android AAudio microphone capture started'; do
  [[ "$log" == *"$evidence"* ]] ||
    archphene_die "missing microphone evidence: $evidence"
done
[[ "$log" != *"FATAL EXCEPTION"* && "$log" != *"Fatal signal"* ]] ||
  archphene_die "microphone bridge emitted a fatal runtime error"
printf '%s\n' "$log" >"$artifact_dir/log.txt"

archphene_adb_run shell am force-stop "$package"
archphene_wait_log \
  'Microphone foreground stopped' "$timeout" \
  'ArchpheneLauncherSession:V AndroidRuntime:E libc:F *:S' >/dev/null
audio_runtime_removed=false
for _ in $(seq 1 60); do
  audio_cache="$(archphene_adb_run shell run-as "$manager" ls -1 cache)"
  if ! printf '%s\n' "$audio_cache" | rg -q '^audio-'; then
    audio_runtime_removed=true
    break
  fi
  sleep 0.25
done
[[ "$audio_runtime_removed" == true ]] ||
  archphene_die "manager retained private microphone state after launcher teardown"

trap - EXIT
cleanup
if [[ "$expect_privacy_block" == true ]]; then
  archphene_note \
    "Android microphone privacy passed on $serial: $bytes bytes, $nonzero nonzero"
else
  archphene_note \
    "Manager-owned microphone input passed on $serial: $bytes bytes, $nonzero nonzero"
fi
archphene_note "  Consent and Android privacy remain outside the thin launcher UID"
archphene_note "  Full-device screenshot: $artifact_dir/full-device.png"
