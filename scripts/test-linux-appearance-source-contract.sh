#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

bridge="$ARCHPHENE_ROOT/prototypes/shared-android-bridge/src/org/archphene/bridge/ArchpheneCompositorActivity.java"
store="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/src/org/archpheneos/manager/ManagerStateStore.java"
provider="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/src/org/archpheneos/manager/RuntimeModuleProvider.java"
manager="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/src/org/archpheneos/manager/MainActivity.java"
style="$ARCHPHENE_ROOT/native/archphene-qt-platform-theme/archphenestyle.cpp"
platform_theme="$ARCHPHENE_ROOT/native/archphene-qt-platform-theme/archpheneplatformtheme.cpp"
process_runtime="$ARCHPHENE_ROOT/crates/archphene-process/src/lib.rs"
gtk_live="$ARCHPHENE_ROOT/native/archphene-gtk3-settings/archphene_gtk3_settings.c"
gtk_build="$ARCHPHENE_ROOT/scripts/build-gtk3-settings-podman.sh"
runtime_service="$ARCHPHENE_ROOT/android/app/src/main/kotlin/org/archphene/app/runtime/ArchpheneRuntimeService.kt"
session_service="$ARCHPHENE_ROOT/android/app/src/main/kotlin/org/archphene/app/launcher/LauncherSessionService.kt"
launcher_activity="$ARCHPHENE_ROOT/android/launcher-template/src/main/kotlin/org/archphene/launcher/LauncherActivity.kt"

for file in "$bridge" "$store" "$provider" "$manager" "$style" "$platform_theme" \
    "$process_runtime" "$gtk_live" "$gtk_build" "$runtime_service" "$session_service" \
    "$launcher_activity"; do
  archphene_require_file "$file"
done

gtk_method="$(sed -n '/private void writeGtkTheme(/,/private void writeKdeTheme(/p' "$bridge")"
[[ "$gtk_method" == *'gtk-theme-name=Adwaita'* \
    && "$gtk_method" == *'gtk-application-prefer-dark-theme='* ]] \
  || archphene_die 'GTK policy must let one complete Adwaita variant own light/dark colors'
[[ "$gtk_method" != *'window, dialog, popover, menu {\n"\n                + "  background-color:'* \
    && "$gtk_method" != *'\n                + "*:disabled'* ]] \
  || archphene_die 'GTK policy must not overlay partial base or disabled-state colors'
[[ "$gtk_method" == *'@define-color accent_color'* \
    && "$gtk_method" == *'@define-color accent_bg_color'* \
    && "$gtk_method" == *'@define-color accent_fg_color'* \
    && "$gtk_method" == *'checkbutton check:checked, check:checked'* \
    && "$gtk_method" == *'checkbutton check:checked:disabled'* \
    && "$gtk_method" == *'background-image: none'* \
    && "$gtk_method" == *'background-color: @accent_bg_color'* \
    && "$gtk_method" == *'color: @accent_fg_color'* ]] \
  || archphene_die 'GTK Material You must apply complete semantic accent states'
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
python3 - "$provider" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
branch = re.search(
    r"if \(APPEARANCE_METHOD\.equals\(method\)\) \{(?P<body>.*?)\n\s*\}",
    source,
    re.DOTALL,
)
if branch is None:
    raise SystemExit("appearance provider method is not a guarded block")
body = branch.group("body")
authorization = body.find("requireWrapperCaller();")
response = body.find("return appearanceBundle();")
if authorization < 0 or response < 0 or authorization > response:
    raise SystemExit("appearance provider publishes policy before authenticating its caller")
PY
grep -Fq 'getString("linux-control-density", "automatic")' "$store" \
  || archphene_die 'automatic control sizing is not the fresh-install default'
grep -Fq 'getInt("linux-font-percent", 0)' "$store" \
  || archphene_die 'automatic text sizing is not the fresh-install default'
grep -Fq 'notifyChange(APPEARANCE_URI' "$store" \
  || archphene_die 'manager appearance changes are not published to running wrappers'
grep -Fq 'registerContentObserver(' "$bridge" \
  || archphene_die 'running wrappers do not observe manager appearance changes'
for slider in 'Linux app scale' 'Linux app text' 'Linux app controls'; do
  grep -Fq "appearanceSlider(\"$slider\"" "$manager" \
    || archphene_die "$slider is not a described discrete slider"
done
grep -Fq 'int[] fontValues = {0, 100, 125, 150, 175, 200}' "$manager" \
  || archphene_die 'Linux text slider must offer Auto and 100 through 200 percent'
grep -Fq 'String[] controlLabels = {"Auto", "18 dp", "20 dp", "22 dp"}' "$manager" \
  || archphene_die 'Linux control slider must expose visible control dp values'
[[ "$(grep -c 'applyTestAppearancePreferences();' "$manager")" -eq 2 ]] \
  || archphene_die 'appearance test policy must apply on cold and warm manager intents'
grep -Fq 'font == 175 || font == 200' "$bridge" \
  || archphene_die 'wrapper does not accept the upper text slider values'
grep -Fq 'Math.min(48,' "$bridge" \
  || archphene_die 'wrapper font conversion still prevents a real 200-percent setting'
grep -Fq 'qBound(9, pointSize, 48)' "$platform_theme" \
  || archphene_die 'Qt platform theme still prevents a real 200-percent setting'
grep -Fq 'ARCHPHENE_QT_CONTROL_MIN_SIZE' "$bridge" \
  || archphene_die 'wrapper does not publish Qt control metrics'
grep -Fq 'ARCHPHENE_QT_CONTROL_VISUAL_SIZE' "$bridge" \
  || archphene_die 'wrapper does not publish independent Qt visible-control metrics'
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
for metric in PM_IndicatorWidth PM_IndicatorHeight PM_ExclusiveIndicatorWidth PM_ExclusiveIndicatorHeight; do
  grep -Fq "$metric" "$style" \
    || archphene_die "Qt style is missing $metric visible-control sizing"
done
grep -Fq 'PM_ScrollBarExtent' "$style" \
  || archphene_die 'Qt style is missing scrollbar density handling'
if grep -Fq 'setFixedHeight' "$style"; then
  archphene_die 'Qt style must not impose app-specific fixed widget heights'
fi

grep -Fq 'let theme = "Adwaita";' "$process_runtime" \
  || archphene_die 'current GTK launch path does not use the embedded Adwaita variant'
! grep -Fq '.env("GTK_THEME"' "$process_runtime" \
  || archphene_die 'current GTK launch path still pins the debug-only GTK_THEME override'
grep -Fq 'nativeUpdateGuiColors(' "$runtime_service" \
  || archphene_die 'current manager runtime does not publish live Linux colors'
grep -Fq 'runtimeBinder?.updateGuiColors(' "$session_service" \
  || archphene_die 'launcher sessions do not bridge Android appearance changes to Linux'
grep -Fq 'g_file_monitor_directory(' "$gtk_live" \
  || archphene_die 'GTK live settings do not use an event-driven file monitor'
! grep -Fq 'g_timeout_add(' "$gtk_live" \
  || archphene_die 'GTK live settings still poll and allocate continuously'
! grep -Fq -- '--allow-shlib-undefined' "$gtk_build" \
  || archphene_die 'GTK settings bridge must not defer its GLib symbols to an arbitrary target process'
grep -Fq '662ee8c1c9546b10e394cac1d25205417b76580fab5d51524c5377e10024b34c' "$gtk_build" \
  || archphene_die 'GTK settings bridge is missing its pinned AArch64 GLib sysroot digest'
for architecture in x86_64 aarch64; do
  settings_module="$ARCHPHENE_ROOT/prebuilt/gtk3-compat/$architecture/libarchphene_gtk3_settings.so"
  archphene_require_file "$settings_module"
  dynamic="$(readelf -d "$settings_module")"
  for dependency in libgio-2.0.so.0 libgobject-2.0.so.0 libgmodule-2.0.so.0 libglib-2.0.so.0; do
    [[ "$dynamic" == *"Shared library: [$dependency]"* ]] \
      || archphene_die "$architecture GTK settings bridge is not linked to $dependency"
  done
done
grep -Fq 'QFileSystemWatcher' "$platform_theme" \
  || archphene_die 'Qt live settings do not use an event-driven file monitor'
! grep -Fq 'setInterval(500)' "$platform_theme" \
  || archphene_die 'Qt live settings still poll and allocate continuously'
grep -Fq 'window.statusBarColor = background' "$launcher_activity" \
  || archphene_die 'generated launchers do not repaint their status bar on mode changes'

archphene_note 'Linux appearance source contract passed: live Adwaita selection, event-driven updates, system bars, and independent Qt/GTK control density are present.'
