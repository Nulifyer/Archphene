#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

image=localhost/archphene-qt-platform-theme:qt6.11.1
archphene_podman_image_exists "$image" \
  || archphene_die "missing $image; run build-qt-platform-theme-podman.sh first"

podman run --rm -v "$ARCHPHENE_ROOT:/workspace:ro" "$image" bash -lc '
set -euo pipefail
work="$(mktemp -d)"
trap '"'"'rm -rf "$work"'"'"' EXIT
mkdir -p "$work/plugins/platformthemes" "$work/plugins/styles" \
  "$work/home/.cache" "$work/home/.config" "$work/linux-runtime/lib"
cp /workspace/prebuilt/qt-bridge/x86_64/libarchphene_qt_platform_theme.so \
  "$work/plugins/platformthemes/"
cp /workspace/prebuilt/qt-bridge/x86_64/libarchphene_qt_style.so \
  "$work/plugins/styles/"
cp /workspace/prebuilt/qt-bridge/x86_64/libarchphene_kde_config.so \
  "$work/linux-runtime/lib/"
g++ -O2 -std=gnu++17 -Wall -Wextra -fPIE -pie -mno-direct-extern-access \
  $(pkg-config --cflags Qt6Widgets) \
  /workspace/native/archphene-qt-platform-theme/archphene-live-theme-smoke.cpp \
  -o "$work/archphene-live-theme-smoke" \
  $(pkg-config --libs Qt6Widgets)
HOME="$work/home" \
XDG_CONFIG_HOME="$work/home/.config" \
QT_QPA_PLATFORM=offscreen \
QT_QPA_PLATFORMTHEME=archphene \
QT_STYLE_OVERRIDE=archphene \
QT_PLUGIN_PATH="$work/plugins" \
"$work/archphene-live-theme-smoke" || {
  test ! -f "$work/home/.cache/archphene-qt-theme.log" \
    || cat "$work/home/.cache/archphene-qt-theme.log" >&2
  exit 1
}
'

archphene_note "Production Qt plugin passed an atomic light-to-dark live update."
