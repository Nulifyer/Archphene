#!/usr/bin/env bash

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

archphene_test_init() {
  archphene_init_adb "$1"
  [[ "$(archphene_adb_run get-state 2>/dev/null | tr -d '\r')" == device ]] \
    || archphene_die "device $1 is not ready"
}

archphene_launcher() {
  local package="$1" activity
  activity="$(archphene_adb_run shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package" \
    | tail -n1 | tr -d '\r')"
  [[ "$activity" == */* ]] || archphene_die "could not resolve launcher for $package"
  printf '%s' "$activity"
}

archphene_prepare_portal_probe() {
  local manager="$1"
  local bus deadline package_dump native_dir abi abi_directory
  bus=
  deadline=$((SECONDS + 10))
  while ((SECONDS < deadline)); do
    bus="$(
      archphene_adb_run shell run-as "$manager" find cache -name bus -print |
        tr -d '\r'
    )"
    [[ "$bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] && break
    sleep 0.3
  done
  [[ "$bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] ||
    archphene_die "expected exactly one private launcher bus, received: $bus"

  package_dump="$(archphene_adb_run shell dumpsys package "$manager")"
  native_dir="$(
    sed -n 's/.*legacyNativeLibraryDir=//p' <<<"$package_dump" |
      head -n 1 |
      tr -d '\r'
  )"
  [[ "$native_dir" =~ ^/data/app/[A-Za-z0-9_~+./=-]+/lib$ ]] ||
    archphene_die "manager native library directory is invalid"
  abi="$(
    sed -n 's/.*primaryCpuAbi=//p' <<<"$package_dump" |
      head -n 1 |
      tr -d '\r'
  )"
  case "$abi" in
    arm64-v8a) abi_directory=arm64 ;;
    x86_64) abi_directory=x86_64 ;;
    *) archphene_die "unsupported manager ABI: $abi" ;;
  esac
  ARCHPHENE_PORTAL_ADDRESS="unix:path=/data/user/0/$manager/$bus"
  ARCHPHENE_PORTAL_PROBE="$native_dir/$abi_directory/libarchphene_portal_probe.so"
}

archphene_run_portal_probe() {
  local manager="$1"
  local probe_command="$2"
  local output="$3"
  local command
  [[ -n "${ARCHPHENE_PORTAL_ADDRESS:-}" && -n "${ARCHPHENE_PORTAL_PROBE:-}" ]] ||
    archphene_die "portal probe context is not prepared"
  [[ "$probe_command" =~ ^[a-z][a-z-]*$ ]] ||
    archphene_die "portal probe command is invalid: $probe_command"
  archphene_adb_run shell run-as "$manager" rm -f "$output"
  command="run-as $manager sh -c 'DBUS_SESSION_BUS_ADDRESS=$ARCHPHENE_PORTAL_ADDRESS \"$ARCHPHENE_PORTAL_PROBE\" \"$probe_command\" > \"$output\" 2>&1 &'"
  archphene_adb_run shell "$command"
}

archphene_run_portal_directory_probe() {
  archphene_run_portal_probe "$1" open-directory "$2"
}

archphene_wait_log() {
  local pattern="$1"
  local seconds="${2:-20}"
  local tags="${3:-ArchpheneRuntime:V *:S}"
  local deadline=$((SECONDS + seconds))
  local log

  while ((SECONDS < deadline)); do
    # Intentional word splitting turns the caller's logcat filter string into
    # the separate tag arguments expected by `logcat -s`.
    # shellcheck disable=SC2086
    log="$(archphene_adb_run logcat -d -v brief -s $tags 2>/dev/null || true)"
    if archphene_regex_contains "$log" "$pattern"; then
      printf '%s' "$log"
      return 0
    fi
    sleep 0.3
  done
  archphene_die "timed out waiting for log pattern: $pattern"
}

archphene_wait_ui_text() {
  local text="$1"
  local name="$2"
  local seconds="${3:-20}"
  local deadline=$((SECONDS + seconds))
  local ui

  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "$name" 2>/dev/null || true)"
    if [[ "$ui" == *"text=\"$text\""* || "$ui" == *"$text"* ]]; then
      ARCHPHENE_UI="$ui"
      return 0
    fi
    sleep 0.5
  done
  archphene_die "timed out waiting for UI text: $text"
}

archphene_wait_ui_exact_text() {
  local value="$1"
  local name="$2"
  local seconds="${3:-20}"
  local escaped
  escaped="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$value")"
  archphene_wait_ui "text=\"$escaped\"" "$name" "$seconds"
}

archphene_wait_ui_unwrapped() {
  local pattern="$1"
  local name="$2"
  local seconds="${3:-20}"
  local deadline=$((SECONDS + seconds))
  local ui normalized

  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "$name" 2>/dev/null || true)"
    normalized="${ui//&#10;/}"
    if archphene_regex_contains "$normalized" "$pattern"; then
      ARCHPHENE_UI="$ui"
      return 0
    fi
    sleep 0.5
  done
  archphene_die "timed out waiting for unwrapped UI pattern: $pattern"
}

archphene_wait_android_chooser_ui() {
  local name="$1"
  local seconds="${2:-20}"
  local deadline=$((SECONDS + seconds))
  local ui

  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "$name" 2>/dev/null || true)"
    if archphene_regex_contains "$ui" \
      'package="(?:com\.android\.intentresolver|com\.(?:google\.)?android\.permissioncontroller)"' &&
      archphene_regex_contains "$ui" 'clickable="true"[^>]*enabled="true"'; then
      ARCHPHENE_UI="$ui"
      return 0
    fi
    sleep 0.5
  done
  archphene_die "timed out waiting for a visible Android chooser"
}

archphene_tap_text() {
  local escaped
  escaped="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$2")"
  archphene_tap_ui_pattern "$1" "text=\"$escaped\"" "$2"
}

archphene_skip_storage_onboarding() {
  local name="${1:-storage-onboarding}"
  local deadline=$((SECONDS + 20))
  local ui
  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "$name" 2>/dev/null || true)"
    if archphene_regex_contains "$ui" 'text="Connect Android files\?"'; then
      archphene_tap_ui_pattern \
        "$ui" 'text="(?:NOT NOW|Not now)"' "Not now"
      break
    fi
    sleep 0.5
  done
  ((SECONDS < deadline)) ||
    archphene_die "timed out waiting for first-run storage onboarding"
  while ((SECONDS < deadline)); do
    ui="$(archphene_capture_ui "$name-dismissed" 2>/dev/null || true)"
    if ! archphene_regex_contains "$ui" 'text="Connect Android files\?"'; then
      ARCHPHENE_UI="$ui"
      return 0
    fi
    sleep 0.5
  done
  archphene_die "storage onboarding did not dismiss"
}

archphene_open_manager_section() {
  local section="$1"
  local name="${2:-manager-section-${section,,}}"
  local ui
  archphene_wait_ui \
    "text=\"$section\"[^>]*class=\"android\\.widget\\.Button\"" \
    "$name-navigation" 15
  ui="$ARCHPHENE_UI"
  archphene_tap_ui_pattern \
    "$ui" "text=\"$section\"[^>]*class=\"android\\.widget\\.Button\"" \
    "$section"
  archphene_wait_ui \
    "text=\"$section\"[^>]*class=\"android\\.widget\\.Button\"[^>]*selected=\"true\"" \
    "$name-selected" 15
}

archphene_enter_terminal_line() {
  local line="$1"
  local name="${2:-terminal-line}"
  archphene_wait_ui \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    "$name-surface" 15
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" \
    'content-desc="Linux terminal, [0-9]+ columns by [0-9]+ rows"' \
    'terminal surface'
  sleep 0.5
  archphene_adb_run shell input text "${line// /%s}" >/dev/null
  # The terminal is a real Android text editor. Dismiss the IME without
  # changing focus, then submit through its hardware-key input path.
  archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
  archphene_adb_run shell input keyevent KEYCODE_ENTER >/dev/null
}

archphene_run_debug_linux_command() {
  local package="$1"
  local command="$2"
  local encoded
  encoded="$(printf '%s' "$command" | base64 -w0)"
  archphene_adb_run shell am broadcast \
    -a org.archphene.app.debug.action.RUN_LINUX_COMMAND \
    --es command_base64 "$encoded" \
    -n "$package/org.archphene.app.LinuxCommandTestReceiver" >/dev/null
  archphene_wait_log \
    'Submitted Linux command probe' 15 'ArchpheneLinuxCommandProbe:I *:S' \
    >/dev/null
}

archphene_open_documents_download_root() {
  local ui="$1"
  local name="${2:-documents-download-root}"
  local root_label
  archphene_regex_contains \
    "$ui" 'content-desc="Show roots"' ||
    archphene_die "DocumentsUI does not expose its roots drawer"
  archphene_tap_ui_pattern \
    "$ui" 'content-desc="Show roots"' "DocumentsUI roots"
  archphene_wait_ui 'text="(?:Open from|Save to)"' "$name-drawer" 15
  ui="$ARCHPHENE_UI"
  if [[ "$ui" == *'text="Downloads"'* ]]; then
    archphene_tap_text "$ui" "Downloads"
    archphene_wait_ui_exact_text "Downloads" "$name-download" 15
    return 0
  fi
  root_label="$(
    python3 -c '
import sys
import xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
virtual = {
    "Recent", "Images", "Audio", "Videos", "Documents", "Downloads",
    "Archphene Apps", "Archphene Home", "Bug reports",
}
for node in root.iter("node"):
    if (
        node.attrib.get("resource-id") == "android:id/title"
        and node.attrib.get("text")
        and node.attrib.get("text") not in virtual
    ):
        print(node.attrib["text"])
        break
' <<<"$ui"
  )"
  [[ -n "$root_label" ]] ||
    archphene_die "DocumentsUI does not expose device storage"
  archphene_tap_text "$ui" "$root_label"
  archphene_wait_ui 'text="Downloads?"' "$name-download" 15
  if [[ "$ARCHPHENE_UI" == *'text="Downloads"'* ]]; then
    archphene_tap_text "$ARCHPHENE_UI" "Downloads"
  else
    archphene_tap_text "$ARCHPHENE_UI" "Download"
  fi
}

archphene_open_documents_archphene_home_root() {
  local ui="$1"
  local name="${2:-documents-archphene-home-root}"
  local root_x root_y
  archphene_regex_contains \
    "$ui" 'content-desc="Show roots"' ||
    archphene_die "DocumentsUI does not expose its roots drawer"
  archphene_tap_ui_pattern \
    "$ui" 'content-desc="Show roots"' "DocumentsUI roots"
  archphene_wait_ui_exact_text \
    "Linux home files" "$name-drawer" 20
  read -r root_x root_y < <(
    python3 -c '
import re, sys
import xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
candidates = []
for parent in root.iter("node"):
    descendants = list(parent.iter("node"))
    if (
        any(node.attrib.get("text") == "Archphene Home" for node in descendants)
        and any(node.attrib.get("text") == "Linux home files" for node in descendants)
    ):
        candidates.append((len(descendants), descendants))
if not candidates:
    raise SystemExit("manager Archphene Home root is unavailable")
descendants = min(candidates, key=lambda candidate: candidate[0])[1]
title = next(
    node for node in descendants
    if node.attrib.get("text") == "Archphene Home"
)
values = list(map(int, re.findall(r"\d+", title.attrib["bounds"])))
print((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
' <<<"$ARCHPHENE_UI"
  )
  archphene_adb_run shell input tap "$root_x" "$root_y"
}
