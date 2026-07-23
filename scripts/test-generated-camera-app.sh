#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package=org.archphene.linux.p2bb8d769a2318af9bf9b60a9f8b7ec5f
activity=org.archphene.linux.kcalc.MainActivity
runtime_pack_id=
permission_action=Both
startup_timeout=120
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --activity) activity="${2:?}"; shift 2 ;;
    --runtime-pack-id) runtime_pack_id="${2:?}"; shift 2 ;;
    --permission-action) permission_action="${2:?}"; shift 2 ;;
    --startup-timeout-seconds) startup_timeout="${2:?}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --runtime-pack-id HASH [--serial SERIAL] [--permission-action Grant|Deny|Both]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ "$runtime_pack_id" =~ ^[0-9a-f]{64}$ ]] \
  || archphene_die '--runtime-pack-id must be 64 lowercase hex characters'
archphene_validate_choice "$permission_action" action Grant Deny Both
[[ "$startup_timeout" =~ ^[0-9]+$ ]] && ((startup_timeout > 0)) \
  || archphene_die '--startup-timeout-seconds must be a positive integer'

archphene_test_init "$serial"
manager=org.archpheneos.manager
permission_pattern='resource-id="[^"]*:id/permission_(allow_[^"]+|deny)_button"'
preserve_dir="$(mktemp -d /tmp/archphene-camera-state.XXXXXX)"
preserve_archive="$preserve_dir/persistent-state.tar"
remote_preserve="/data/local/tmp/archphene-camera-state-$$.tar"
had_persistent_state=false
original_camera_granted=false
state_snapshot_taken=false
restoration_done=false

get_ui() {
  # Never interpret a previous permission hierarchy when UiAutomation cannot
  # obtain a fresh snapshot during a window transition.
  archphene_adb_run shell rm -f /sdcard/archphene-generated-camera.xml \
    >/dev/null 2>&1 || true
  archphene_capture_ui archphene-generated-camera 2>/dev/null || true
}

wait_permission_prompt() {
  local deadline=$((SECONDS + startup_timeout)) ui
  while ((SECONDS < deadline)); do
    ui="$(get_ui)"
    if archphene_regex_contains "$ui" "$permission_pattern"; then
      printf '%s' "$ui"
      return 0
    fi
    sleep .5
  done
  archphene_die 'generated app did not open the Android camera permission prompt'
}

select_permission() {
  local action="$1" ui="$2" pattern attempt center x y after
  if [[ "$action" == Grant ]]; then
    pattern='resource-id="[^"]*:id/permission_allow_(?:foreground_only|one_time)_button"'
  else
    pattern='resource-id="[^"]*:id/permission_deny_button"'
  fi
  for attempt in 0 1 2; do
    ((attempt == 0)) || ui="$(get_ui)"
    if ! archphene_regex_contains "$ui" "$pattern"; then
      return 0
    fi
    center="$(archphene_ui_node_center "$ui" "$pattern" "$action camera permission action")"
    read -r x y <<<"$center"
    sleep .5
    archphene_adb_run shell input tap "$x" "$y" >/dev/null
    sleep 1
    after="$(get_ui)"
    if ! archphene_regex_contains "$after" "$permission_pattern"; then
      return 0
    fi
    ui="$after"
  done
  archphene_die "permission action $action did not close the Android prompt"
}

get_logs() {
  archphene_adb_run logcat -d -v brief -s \
    ArchpheneCapabilities:I ArchpheneCamera:I ArchpheneRuntime:I \
    AndroidRuntime:E '*:S'
}

read_app_file() {
  archphene_adb_run shell run-as "$package" cat "$1" 2>/dev/null || true
}

wait_for_log() {
  local pattern="$1" seconds="$2" failure="$3" deadline=$((SECONDS + $2)) logs
  while ((SECONDS < deadline)); do
    logs="$(get_logs 2>/dev/null || true)"
    if archphene_regex_contains "$logs" "$pattern"; then
      printf '%s' "$logs"
      return 0
    fi
    sleep .3
  done
  archphene_die "$failure"
}

wait_wrapper_foreground() {
  local deadline=$((SECONDS + startup_timeout)) activities
  while ((SECONDS < deadline)); do
    activities="$(archphene_adb_run shell dumpsys activity activities)"
    if archphene_regex_contains "$activities" \
        "topResumedActivity=.*${package//./\\.}/"; then
      return 0
    fi
    archphene_adb_run shell am start -W -n "$package/$activity" >/dev/null \
      2>&1 || true
    sleep .5
  done
  archphene_die 'generated camera wrapper was not foreground for visual validation'
}

bind_runtime_pack() {
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" \
    --ez archphene_test_package_runtime true \
    --es archphene_test_bind_pack "$runtime_pack_id" \
    --es archphene_test_bind_package "$package" >/dev/null
  sleep 4
}

assert_process_cleanup() {
  local linux_user="$1" deadline processes
  [[ -n "$linux_user" ]] || return 0
  deadline=$((SECONDS + 10))
  while ((SECONDS < deadline)); do
    processes="$(archphene_adb_run shell ps -A -o USER 2>/dev/null || true)"
    if ! grep -Fxq "$linux_user" <<<"$processes"; then
      return 0
    fi
    sleep .3
  done
  archphene_die "generated app processes survived force-stop: $linux_user"
}

assert_cleanup() {
  local linux_user="$1" ui logs_before logs deadline
  ui="$(get_ui)"
  if archphene_regex_contains "$ui" "$permission_pattern"; then
    archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
    sleep .5
  fi
  logs_before="$(get_logs 2>/dev/null || true)"
  archphene_regex_contains "$logs_before" \
    "Acquired runtime pack lease $runtime_pack_id for $package" \
    || archphene_die 'generated app never acquired its bound runtime-pack lease'

  archphene_adb_run shell am force-stop "$package"
  deadline=$((SECONDS + 30))
  while ((SECONDS < deadline)); do
    logs="$(get_logs 2>/dev/null || true)"
    if archphene_regex_contains "$logs" \
        "(Released runtime pack lease|Runtime process died; released pack lease) $runtime_pack_id for $package"; then
      break
    fi
    sleep .3
  done
  archphene_regex_contains "${logs:-}" \
    "(Released runtime pack lease|Runtime process died; released pack lease) $runtime_pack_id for $package" \
    || archphene_die 'runtime-pack lease was not released after generated app exit'
  assert_process_cleanup "$linux_user"
  [[ "$logs" != *'FATAL EXCEPTION'* ]] \
    || archphene_die "generated app crashed during cleanup: $logs"
}

run_case() {
  local action="$1" linux_user="$2" ui logs pipewire gstreamer deadline denied_logs ui_after
  local artifact_dir frame_name
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run shell pm clear "$package" >/dev/null
  bind_runtime_pack
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$package/$activity" \
    --ez archphene_test_media_debug true >/dev/null

  ui="$(wait_permission_prompt)"
  archphene_note "Generated camera app opened the Android permission prompt ($action)"
  wait_for_log 'Requested Android camera permission' 5 \
    'Linux camera portal did not log its Android permission request' >/dev/null
  select_permission "$action" "$ui"

  if [[ "$action" == Grant ]]; then
    deadline=$((SECONDS + 45))
    while ((SECONDS < deadline)); do
      logs="$(get_logs 2>/dev/null || true)"
      pipewire="$(read_app_file cache/pipewire-debug.log)"
      gstreamer="$(read_app_file cache/gstreamer-debug.log)"
      if archphene_regex_contains "$logs" 'Android camera permission granted' \
          && archphene_regex_contains "$logs" 'Starting Android camera I420 stream' \
          && archphene_regex_contains "$pipewire" 'Archphene camera link=\d+->\d+' \
          && archphene_regex_contains "$gstreamer" 'pts [1-9]\d+' \
          && [[ "$gstreamer" != *'pts 18446744073709551615'* ]] \
          && [[ "$gstreamer" != *'Failed to change camerabin state'* ]]; then
        break
      fi
      sleep .5
    done
    archphene_regex_contains "${logs:-}" 'Android camera permission granted' \
      && archphene_regex_contains "${logs:-}" 'Starting Android camera I420 stream' \
      && archphene_regex_contains "${pipewire:-}" 'Archphene camera link=\d+->\d+' \
      && archphene_regex_contains "${gstreamer:-}" 'pts [1-9]\d+' \
      && [[ "$gstreamer" != *'pts 18446744073709551615'* ]] \
      && [[ "$gstreamer" != *'Failed to change camerabin state'* ]] \
      || archphene_die 'generated app did not consume timestamped PipeWire camera frames'
    wait_wrapper_foreground
    sleep 2
    artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts/generated-camera"
    frame_name="${serial//[^A-Za-z0-9_.-]/_}-grant"
    mkdir -p "$artifact_dir"
    archphene_adb_run exec-out screencap >"$artifact_dir/$frame_name.raw"
    archphene_adb_run exec-out screencap -p >"$artifact_dir/$frame_name.png"
    python3 "$ARCHPHENE_SCRIPTS_DIR/lib/camera-frame-check.py" \
      "$artifact_dir/$frame_name.raw"
  else
    denied_logs="$(wait_for_log 'Android camera permission denied' 15 \
      'generated app did not receive the Android camera denial')"
    sleep 3
    ui_after="$(get_ui)"
    ! archphene_regex_contains "$ui_after" "$permission_pattern" \
      || archphene_die 'camera permission denial unexpectedly reprompted'
    pipewire="$(read_app_file cache/pipewire-debug.log)"
    [[ "$pipewire" != *'Archphene camera link='* \
        && "$denied_logs" != *'Starting Android camera I420 stream'* ]] \
      || archphene_die 'camera stream started after Android permission denial'
  fi

  assert_cleanup "$linux_user"
  archphene_note "Generated camera app $action path passed on $serial"
}

cleanup() {
  local restore_failed=false
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ "$state_snapshot_taken" == true && "$restoration_done" == false ]]; then
    if ! archphene_adb_run shell pm clear "$package" >/dev/null 2>&1; then
      restore_failed=true
    fi
    if [[ "$had_persistent_state" == true && -s "$preserve_archive" ]]; then
      if ! archphene_adb_run push "$preserve_archive" "$remote_preserve" \
          >/dev/null 2>&1; then
        restore_failed=true
      fi
      if ! archphene_adb_run shell chmod 644 "$remote_preserve" \
          >/dev/null 2>&1; then
        restore_failed=true
      fi
      if ! archphene_adb_run shell run-as "$package" tar -xf "$remote_preserve" \
          >/dev/null 2>&1; then
        restore_failed=true
      fi
    fi
    if [[ "$original_camera_granted" == true ]]; then
      if ! archphene_adb_run shell pm grant "$package" android.permission.CAMERA \
          >/dev/null 2>&1; then
        restore_failed=true
      fi
    fi
    archphene_adb_run shell rm -f "$remote_preserve" \
      /sdcard/archphene-generated-camera.xml >/dev/null 2>&1 || true
    restoration_done=true
  fi
  rm -f "$preserve_archive"
  rmdir "$preserve_dir" >/dev/null 2>&1 || true
  if [[ "$restore_failed" == true ]]; then
    echo 'error: generated camera test could not restore the original app state' >&2
    return 1
  fi
}
trap cleanup EXIT

package_dump="$(archphene_adb_run shell dumpsys package "$package")"
[[ "$package_dump" == *android.permission.CAMERA* ]] \
  || archphene_die 'generated app does not declare android.permission.CAMERA'
archphene_regex_contains "$package_dump" \
  'android\.permission\.CAMERA: granted=true' \
  && original_camera_granted=true
preserve_paths=()
for path in files/linux-home shared_prefs; do
  if archphene_adb_run shell run-as "$package" test -e "$path" \
      >/dev/null 2>&1; then
    preserve_paths+=("$path")
  fi
done
if ((${#preserve_paths[@]})); then
  archphene_adb_run exec-out run-as "$package" tar -cf - \
    "${preserve_paths[@]}" >"$preserve_archive"
  [[ -s "$preserve_archive" ]] \
    || archphene_die 'generated app persistent-state backup is empty'
  had_persistent_state=true
fi
state_snapshot_taken=true
uid_output="$(archphene_adb_run shell cmd package list packages -U "$package")"
uid="$(sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' <<<"$uid_output" | head -n1)"
linux_user=
if [[ "$uid" =~ ^[0-9]+$ ]]; then
  app_id=$((uid % 100000))
  ((app_id >= 10000 && app_id < 20000)) && linux_user="u0_a$((app_id - 10000))"
fi

actions=("$permission_action")
[[ "$permission_action" == Both ]] && actions=(Grant Deny)
for action in "${actions[@]}"; do
  run_case "$action" "$linux_user"
done
cleanup
trap - EXIT
archphene_note "Generated unmodified camera consumer passed on $serial: ${actions[*]} permission paths, PipeWire frames, timestamps, presented pixels, and cleanup."
