#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/live-theme-test.sh"

serial=emulator-5554
package=org.archphene.linux.p241d399e14343c53b8b766e9126776aa
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --package) package="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_test_init "$serial"
manager=org.archpheneos.manager
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/mousepad-material-you}"
mkdir -p "$artifact_dir"
old_mode="$(archphene_adb_run shell cmd uimode night \
  | sed -n 's/^Night mode: //p' | tr -d '\r')"
read -r old_theme old_material \
  <<<"$(archphene_saved_linux_appearance "$manager")"
cleanup() {
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_set_test_linux_appearance \
    "$manager" "$old_theme" "$old_material" >/dev/null 2>&1 || true
  archphene_adb_run shell cmd uimode night "$old_mode" >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell cmd uimode night no >/dev/null
archphene_set_test_linux_appearance "$manager" light false
"$ARCHPHENE_SCRIPTS_DIR/test-mousepad-secondary-window.sh" \
  --serial "$serial" --package "$package" \
  --artifact-dir "$artifact_dir/plain"

archphene_set_test_linux_appearance "$manager" light true
"$ARCHPHENE_SCRIPTS_DIR/test-mousepad-secondary-window.sh" \
  --serial "$serial" --package "$package" \
  --artifact-dir "$artifact_dir/material"

! cmp -s "$artifact_dir/plain/gtk.css" "$artifact_dir/material/gtk.css" \
  || archphene_die 'Material You did not change Mousepad GTK configuration'
for color in accent_color accent_bg_color accent_fg_color \
    theme_selected_bg_color theme_selected_fg_color; do
  grep -Fq "@define-color $color " "$artifact_dir/material/gtk.css" \
    || archphene_die "Material You GTK configuration is missing $color"
done

# The blank editor body intentionally remains Adwaita-owned and can be pixel-
# identical. Compare the first checkbox after the interaction gate has toggled
# it on: that is a real selected-state consumer of the semantic accent.
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/theme-frame-check.py" different \
  "$artifact_dir/plain/checkbox-toggled.raw" \
  "$artifact_dir/material/checkbox-toggled.raw" \
  --left-percent 14 --right-percent 25 --top-percent 13 --bottom-percent 23 \
  --minimum-difference .2 --minimum-changed-ratio .005
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$package" \
  --field 'app=Mousepad' --field 'state=Material You checked indicator' \
  --field 'toolkit=gtk3' \
  --artifact "$artifact_dir/plain/checkbox-toggled.raw" \
  --artifact "$artifact_dir/material/checkbox-toggled.raw" \
  --artifact "$artifact_dir/plain/gtk.css" \
  --artifact "$artifact_dir/material/gtk.css"

cleanup
trap - EXIT
archphene_note "Mousepad Material You visual gate passed on $serial: semantic GTK colors changed the rendered checked indicator. Evidence: $artifact_dir"
