#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

# Exercise the default timeout initialization paths without an Android target.
# Before the Bash migration fix these fail under `set -u` while evaluating the
# local deadline declaration.
archphene_adb_run() { printf '%s\n' 'expected-log'; }
archphene_capture_ui() { printf '%s\n' 'text="expected-ui"'; }

archphene_wait_log 'expected-log' >/dev/null
archphene_wait_ui_text 'expected-ui' bash-helper-ui >/dev/null
archphene_wait_ui_exact_text 'expected-ui' bash-helper-exact-ui >/dev/null
if archphene_regex_contains 'text="Installed 1.0.0"' 'text="Install"'; then
  archphene_die 'exact Install selector matched Installed'
fi

archphene_note 'Bash Android test helper initialization passed under set -u.'
