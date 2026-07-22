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

archphene_tap_text() {
  local escaped
  escaped="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$2")"
  archphene_tap_ui_pattern "$1" "text=\"$escaped\"" "$2"
}
