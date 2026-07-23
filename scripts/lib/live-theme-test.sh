#!/usr/bin/env bash

source "$(dirname "${BASH_SOURCE[0]}")/android-test.sh"

archphene_saved_linux_appearance() {
  local manager="$1" preferences
  preferences="$(archphene_adb_run shell run-as "$manager" \
    cat shared_prefs/linux-app-manager-state.xml 2>/dev/null || true)"
  python3 -c '
import sys, xml.etree.ElementTree as ET
text = sys.stdin.read().strip()
values = {}
if text:
    root = ET.fromstring(text)
    values = {node.attrib.get("name"): node for node in root}
theme = values.get("linux-theme-mode")
material = values.get("material-you")
print(theme.text if theme is not None and theme.text else "system",
      "true" if material is not None and material.attrib.get("value") == "true" else "false")
' <<<"$preferences"
}

archphene_set_test_linux_appearance() {
  local manager="$1" theme="$2" material="$3"
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run shell am start -S -W -n "$manager/.MainActivity" \
    --es archphene_test_linux_theme "$theme" \
    --ez archphene_test_material_you "$material" >/dev/null
  sleep 2
}

archphene_saved_control_density() {
  local manager="$1" preferences
  preferences="$(archphene_adb_run shell run-as "$manager" \
    cat shared_prefs/linux-app-manager-state.xml 2>/dev/null || true)"
  python3 -c '
import sys, xml.etree.ElementTree as ET
text = sys.stdin.read().strip()
if not text:
    print("automatic")
else:
    node = next((item for item in ET.fromstring(text)
                 if item.attrib.get("name") == "linux-control-density"), None)
    print(node.text if node is not None and node.text else "automatic")
' <<<"$preferences"
}

archphene_set_test_control_density() {
  local manager="$1" density="$2"
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run shell am start -S -W -n "$manager/.MainActivity" \
    --es archphene_test_control_density "$density" >/dev/null
  sleep 1
}

archphene_wait_theme_runtime() {
  local package="$1" deadline=$((SECONDS + 30)) pid runtime_pid
  while ((SECONDS < deadline)); do
    pid="$(archphene_android_pid "$package" || true)"
    if [[ -n "$pid" ]]; then
      runtime_pid="$(archphene_linux_loader_pid "$pid" || true)"
      if [[ -n "$runtime_pid" ]]; then
        printf '%s %s\n' "$pid" "$runtime_pid"
        return 0
      fi
    fi
    sleep .4
  done
  return 1
}

archphene_wait_appearance_log() {
  local pid="$1" pattern="$2" seconds="${3:-15}" deadline log
  deadline=$((SECONDS + seconds))
  while ((SECONDS < deadline)); do
    log="$(archphene_adb_run logcat -d -v brief --pid="$pid" \
      -s ArchpheneLinuxApp:V AndroidRuntime:E '*:S' 2>/dev/null || true)"
    if archphene_regex_contains "$log" "$pattern"; then
      return 0
    fi
    sleep .3
  done
  archphene_die "timed out waiting for appearance log: $pattern"
}

archphene_assert_foreground() {
  local package="$1" label="$2" activities
  activities="$(archphene_adb_run shell dumpsys activity activities)"
  archphene_regex_contains "$activities" \
    "topResumedActivity=.*${package//./\\.}/" \
    || archphene_die "$label was not the foreground Android activity"
}

archphene_capture_stable_theme_frame() {
  local package="$1" label="$2" destination="$3" toolkit="$4"
  local previous="${destination}.previous" attempt
  local inspect_args=()
  case "$toolkit" in
    gtk3|gtk4|wayland)
      inspect_args=(--top-percent 4 --bottom-percent 20)
      ;;
  esac
  archphene_assert_foreground "$package" "$label"
  archphene_adb_run exec-out screencap >"$previous"
  for attempt in {1..30}; do
    sleep 1
    archphene_adb_run exec-out screencap >"$destination"
    if python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" similar \
        "$previous" "$destination" >/dev/null 2>&1; then
      python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" inspect \
        "$destination" "${inspect_args[@]}" >/dev/null
      return 0
    fi
    cp "$destination" "$previous"
  done
  archphene_die "$label render did not stabilize"
}

archphene_live_theme_cleanup() {
  [[ -n "${ARCHPHENE_LIVE_THEME_PACKAGE:-}" ]] \
    && archphene_adb_run shell am force-stop \
      "$ARCHPHENE_LIVE_THEME_PACKAGE" >/dev/null 2>&1 || true
  if [[ -n "${ARCHPHENE_LIVE_THEME_OLD_THEME:-}" \
      && -n "${ARCHPHENE_LIVE_THEME_OLD_MATERIAL:-}" ]]; then
    archphene_set_test_linux_appearance org.archpheneos.manager \
      "$ARCHPHENE_LIVE_THEME_OLD_THEME" \
      "$ARCHPHENE_LIVE_THEME_OLD_MATERIAL" >/dev/null 2>&1 || true
  fi
  [[ -n "${ARCHPHENE_LIVE_THEME_OLD_MODE:-}" ]] \
    && archphene_adb_run shell cmd uimode night \
      "$ARCHPHENE_LIVE_THEME_OLD_MODE" >/dev/null 2>&1 || true
}

archphene_assert_theme_config() {
  local package="$1" toolkit="$2" dark="$3" config
  case "$toolkit" in
    qt6)
      config="$(archphene_adb_run shell run-as "$package" \
        cat files/linux-home/.config/kdeglobals)"
      if [[ "$dark" == true ]]; then
        [[ "$config" == *'Name=Archphene Dark'* ]] \
          || archphene_die 'Qt runtime configuration is not dark'
      else
        [[ "$config" == *'Name=Archphene Light'* ]] \
          || archphene_die 'Qt runtime configuration is not light'
      fi
      ;;
    gtk3|gtk4)
      local version=3
      [[ "$toolkit" == gtk4 ]] && version=4
      config="$(archphene_adb_run shell run-as "$package" \
        cat "files/linux-home/.config/gtk-$version.0/settings.ini")"
      [[ "$config" == *"gtk-application-prefer-dark-theme=$dark"* ]] \
        || archphene_die "$toolkit runtime configuration did not select dark=$dark"
      if [[ "$toolkit" == gtk4 ]]; then
        local diagnostic deadline=$((SECONDS + 10))
        while ((SECONDS < deadline)); do
          diagnostic="$(archphene_adb_run shell run-as "$package" \
            cat files/linux-home/.cache/archphene-gtk-settings.log \
            2>/dev/null || true)"
          [[ "$diagnostic" == *"dark=$dark"* ]] && break
          sleep .2
        done
        [[ "$diagnostic" == *"dark=$dark"* ]] \
          || archphene_die "GTK4 live-settings bridge did not apply dark=$dark"
      fi
      ;;
    wayland)
      config="$(archphene_adb_run shell run-as "$package" \
        cat files/linux-home/.config/archphene/foot.ini)"
      if [[ "$dark" == true ]]; then
        [[ "$config" == *'initial-color-theme=dark'* ]] \
          || archphene_die 'direct-Wayland runtime configuration is not dark'
      else
        [[ "$config" == *'initial-color-theme=light'* ]] \
          || archphene_die 'direct-Wayland runtime configuration is not light'
      fi
      ;;
    *) archphene_die "unsupported themed toolkit: $toolkit" ;;
  esac
}

archphene_test_live_theme() {
  local serial="$1" package="$2" label="$3" toolkit="$4"
  archphene_test_init "$serial"
  local manager=org.archpheneos.manager activity old_mode old_theme old_material tmp
  local pid runtime_pid dark_pid dark_runtime_pid
  local return_pid return_runtime_pid cold_pid cold_runtime_pid
  activity="$(archphene_launcher "$package")"
  old_mode="$(archphene_adb_run shell cmd uimode night \
    | sed -n 's/^Night mode: //p' | tr -d '\r')"
  read -r old_theme old_material \
    <<<"$(archphene_saved_linux_appearance "$manager")"
  tmp="$(archphene_mktemp_dir live-theme)"
  ARCHPHENE_LIVE_THEME_PACKAGE="$package"
  ARCHPHENE_LIVE_THEME_OLD_THEME="$old_theme"
  ARCHPHENE_LIVE_THEME_OLD_MATERIAL="$old_material"
  ARCHPHENE_LIVE_THEME_OLD_MODE="$old_mode"
  trap archphene_live_theme_cleanup EXIT

  archphene_set_test_linux_appearance "$manager" system false
  archphene_adb_run shell cmd uimode night no >/dev/null
  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run logcat -c
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  read -r pid runtime_pid \
    <<<"$(archphene_wait_theme_runtime "$package")" \
    || archphene_die "$label Linux runtime is missing"
  archphene_wait_appearance_log "$pid" \
    'Appearance theme=system resolved=light.*materialYou=false'
  archphene_assert_theme_config "$package" "$toolkit" false
  archphene_capture_stable_theme_frame \
    "$package" "$label light render" "$tmp/light.raw" "$toolkit"

  archphene_adb_run shell cmd uimode night yes >/dev/null
  if [[ "$toolkit" == wayland ]]; then
    archphene_wait_appearance_log "$pid" \
      'Direct-Wayland appearance configuration changed resolved=dark liveApplied=true' 20
  else
    archphene_wait_appearance_log "$pid" \
      'Appearance configuration changed resolved=dark' 20
  fi
  dark_pid="$(archphene_android_pid "$package")"
  dark_runtime_pid="$(archphene_linux_loader_pid "$dark_pid")"
  [[ "$pid" == "$dark_pid" && "$runtime_pid" == "$dark_runtime_pid" ]] \
    || archphene_die "$label restarted during live theme change"
  archphene_assert_theme_config "$package" "$toolkit" true
  archphene_capture_stable_theme_frame \
    "$package" "$label live dark render" "$tmp/dark.raw" "$toolkit"
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" light-dark \
    "$tmp/light.raw" "$tmp/dark.raw"

  archphene_adb_run shell cmd uimode night no >/dev/null
  if [[ "$toolkit" == wayland ]]; then
    archphene_wait_appearance_log "$pid" \
      'Direct-Wayland appearance configuration changed resolved=light liveApplied=true' 20
  else
    archphene_wait_appearance_log "$pid" \
      'Appearance configuration changed resolved=light' 20
  fi
  return_pid="$(archphene_android_pid "$package")"
  return_runtime_pid="$(archphene_linux_loader_pid "$return_pid")"
  [[ "$pid" == "$return_pid" && "$runtime_pid" == "$return_runtime_pid" ]] \
    || archphene_die "$label restarted while returning to light theme"
  archphene_assert_theme_config "$package" "$toolkit" false
  archphene_capture_stable_theme_frame \
    "$package" "$label returned light render" "$tmp/light-return.raw" "$toolkit"
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" similar \
    "$tmp/light.raw" "$tmp/light-return.raw" \
    --maximum-difference 8 --maximum-changed-ratio .35

  archphene_adb_run shell am force-stop "$package"
  archphene_adb_run shell cmd uimode night yes >/dev/null
  archphene_adb_run shell am start -W -n "$activity" >/dev/null
  read -r cold_pid cold_runtime_pid \
    <<<"$(archphene_wait_theme_runtime "$package")" \
    || archphene_die "$label cold dark runtime is missing"
  archphene_wait_appearance_log "$cold_pid" \
    'Appearance theme=system resolved=dark.*materialYou=false'
  archphene_assert_theme_config "$package" "$toolkit" true
  archphene_capture_stable_theme_frame \
    "$package" "$label cold dark render" "$tmp/cold-dark.raw" "$toolkit"
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" similar \
    "$tmp/dark.raw" "$tmp/cold-dark.raw" \
    --maximum-difference 15 --maximum-changed-ratio .5

  archphene_live_theme_cleanup
  trap - EXIT
  unset ARCHPHENE_LIVE_THEME_PACKAGE ARCHPHENE_LIVE_THEME_OLD_THEME \
    ARCHPHENE_LIVE_THEME_OLD_MATERIAL ARCHPHENE_LIVE_THEME_OLD_MODE
  archphene_note "$label live Android light/dark/light theme passed on $serial with stable Android PID $pid and Linux PID $runtime_pid; cold dark matched PID $cold_pid/$cold_runtime_pid."
}
