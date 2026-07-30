#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

while (($#)); do
  case "$1" in
    -h|--help)
      echo "usage: $0"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_require_command rg
self="$(realpath "$0")"

mapfile -t test_scripts < <(
  find "$ARCHPHENE_SCRIPTS_DIR" -maxdepth 1 -type f -name 'test-*.sh' -print |
    sort
)
(( ${#test_scripts[@]} > 0 )) ||
  archphene_die "no standalone Bash tests were found"

clear_count=0
optional_install_count=0
reboot_count=0
settings_count=0
for script in "${test_scripts[@]}"; do
  [[ "$script" != "$self" ]] || continue

  if rg -q 'shell pm clear[[:space:]]' "$script"; then
    clear_count=$((clear_count + 1))
    if [[ "${script##*/}" == test-generated-camera-app.sh ]]; then
      rg -q 'preserve_archive=' "$script" &&
        rg -q 'state_snapshot_taken=' "$script" &&
        rg -q 'restoration_done=' "$script" &&
        rg -q 'original_camera_granted' "$script" ||
        archphene_die \
          "$script clears app data without its exact sandbox/permission restoration contract"
    else
      rg -q -- '--(clean-data|reset-data|reset-app-data)' "$script" ||
        archphene_die \
          "$script clears app data without an explicit destructive option"
    fi
  fi

  if rg -q '^skip_install=' "$script" &&
    rg -q '(archphene_adb_run|adb_for).*install ' "$script"; then
    optional_install_count=$((optional_install_count + 1))
    rg -q '^skip_install=true$' "$script" ||
      archphene_die "$script installs an APK by default"
    rg -q -- '--install-apk\) skip_install=false' "$script" ||
      archphene_die "$script has no explicit --install-apk action"
  fi

  if rg -q 'archphene_adb_run reboot' "$script"; then
    reboot_count=$((reboot_count + 1))
    rg -q -- '--allow-reboot' "$script" ||
      archphene_die "$script can reboot a device without explicit authorization"
  fi

  if rg -q \
    'shell (pm (grant|revoke)|appops set)|shell settings (put|delete)' \
    "$script"; then
    settings_count=$((settings_count + 1))
    rg -q '(cleanup\(\)|restore|original_|old_|initial_)' "$script" ||
      archphene_die \
        "$script changes device permission/settings state without a restoration path"
  fi
done

((clear_count >= 10)) ||
  archphene_die "app-data reset policy scan covered too few scripts: $clear_count"
((optional_install_count >= 50)) ||
  archphene_die \
    "optional APK-install policy scan covered too few scripts: $optional_install_count"
((reboot_count >= 2)) ||
  archphene_die "reboot policy scan covered too few scripts: $reboot_count"
((settings_count >= 10)) ||
  archphene_die \
    "permission/settings policy scan covered too few scripts: $settings_count"

archphene_note \
  "Standalone device-mutation policy passed: $clear_count reset, $optional_install_count optional-install, $reboot_count reboot, and $settings_count permission/settings scripts fail closed or restore state."
