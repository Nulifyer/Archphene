#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

bridge="$ARCHPHENE_ROOT/prototypes/shared-android-bridge/src/org/archphene/bridge/ArchpheneCompositorActivity.java"
store="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/src/org/archpheneos/manager/ManagerStateStore.java"
provider="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/src/org/archpheneos/manager/RuntimeModuleProvider.java"
manager="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/src/org/archpheneos/manager/MainActivity.java"
style="$ARCHPHENE_ROOT/native/archphene-qt-platform-theme/archphenestyle.cpp"
platform_theme="$ARCHPHENE_ROOT/native/archphene-qt-platform-theme/archpheneplatformtheme.cpp"

for file in "$bridge" "$store" "$provider" "$manager" "$style" "$platform_theme"; do
  archphene_require_file "$file"
done

gtk_method="$(sed -n '/private void writeGtkTheme(/,/private void writeKdeTheme(/p' "$bridge")"
[[ "$gtk_method" == *'gtk-theme-name=Adwaita'* \
    && "$gtk_method" == *'gtk-application-prefer-dark-theme='* ]] \
  || archphene_die 'GTK policy must let one complete Adwaita variant own light/dark colors'
[[ "$gtk_method" != *'background-color:'* \
    && "$gtk_method" != *'\n                + "*:disabled'* ]] \
  || archphene_die 'GTK policy must not overlay partial base or disabled-state colors'
[[ "$gtk_method" == *'@define-color accent_color'* \
    && "$gtk_method" == *'@define-color accent_bg_color'* \
    && "$gtk_method" == *'@define-color accent_fg_color'* ]] \
  || archphene_die 'GTK Material You must use semantic accent color names'
[[ "$gtk_method" == *'checkbutton check, check, radiobutton radio, radio'* \
    && "$gtk_method" == *'visibleAffordanceDp'* \
    && "$gtk_method" == *'visibleAffordanceSize / 16f'* ]] \
  || archphene_die 'GTK phone policy must scale visible checks, radios, and title icons independently from their touch targets'

for density in automatic compact comfortable touch; do
  grep -Fq "\"$density\"" "$store" \
    || archphene_die "manager is missing $density control density"
done
grep -Fq 'result.putString("control_density"' "$provider" \
  || archphene_die 'runtime provider does not publish control density'
grep -Fq 'getString("linux-control-density", "compact")' "$store" \
  || archphene_die 'compact control density is not the fresh-install default'
grep -Fq 'notifyChange(APPEARANCE_URI' "$store" \
  || archphene_die 'manager appearance changes are not published to running wrappers'
grep -Fq 'registerContentObserver(' "$bridge" \
  || archphene_die 'running wrappers do not observe manager appearance changes'
for slider in 'Linux app scale' 'Linux app text' 'Linux app controls'; do
  grep -Fq "appearanceSlider(\"$slider\"" "$manager" \
    || archphene_die "$slider is not a described discrete slider"
done
grep -Fq 'int[] fontValues = {100, 125, 150, 175, 200}' "$manager" \
  || archphene_die 'Linux text slider must cover 100 through 200 percent'
grep -Fq 'String[] controlLabels = {"Auto", "32 dp", "40 dp", "48 dp"}' "$manager" \
  || archphene_die 'Linux control slider must expose its actual dp targets'
grep -Fq 'font == 175 || font == 200' "$bridge" \
  || archphene_die 'wrapper does not accept the upper text slider values'
grep -Fq 'Math.min(48,' "$bridge" \
  || archphene_die 'wrapper font conversion still prevents a real 200-percent setting'
grep -Fq 'qBound(9, pointSize, 48)' "$platform_theme" \
  || archphene_die 'Qt platform theme still prevents a real 200-percent setting'
grep -Fq 'ARCHPHENE_QT_CONTROL_MIN_SIZE' "$bridge" \
  || archphene_die 'wrapper does not publish Qt control metrics'
grep -Fq 'ControlMinSize=' "$bridge" \
  || archphene_die 'live Qt configuration does not contain control metrics'
grep -Fq 'display.getDisplayId() != Display.DEFAULT_DISPLAY' "$bridge" \
  || archphene_die 'automatic appearance does not distinguish a real external display'
for foot_setting in 'font=monospace:pixelsize=' 'initial-color-theme=' \
    'button-width=' 'include='; do
  grep -Fq "$foot_setting" "$bridge" \
    || archphene_die "direct-Wayland Foot policy is missing $foot_setting"
done
grep -Fq 'execution.signalUser(dark)' "$bridge" \
  || archphene_die 'direct-Wayland live theme does not use the isolated runtime signal path'
grep -Fq 'target.to_ne_bytes()' \
  "$ARCHPHENE_ROOT/native/archphene-compositor/src/lib.rs" \
  || archphene_die 'runtime supervisor does not record the exact signal target'

for content_type in CT_MenuItem CT_MenuBarItem CT_PushButton CT_ToolButton CT_ComboBox; do
  grep -Fq "$content_type" "$style" \
    || archphene_die "Qt style is missing $content_type density handling"
done
grep -Fq 'PM_ScrollBarExtent' "$style" \
  || archphene_die 'Qt style is missing scrollbar density handling'
if grep -Fq 'setFixedHeight' "$style"; then
  archphene_die 'Qt style must not impose app-specific fixed widget heights'
fi

archphene_note 'Linux appearance source contract passed: complete GTK theme ownership and independent Qt/GTK control density are present.'
