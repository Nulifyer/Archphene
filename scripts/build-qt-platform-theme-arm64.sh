#!/usr/bin/env bash
set -euo pipefail

root="${ROOT:-/workspace}"
qt_version=6.11.1
package_name=qt6-base-6.11.1-1-aarch64.pkg.tar.xz
package_sha256=502bd6689b9ff5761be4dd21512738ff26e75333a6cc744c4e6354ef097448e0
package_url=https://ca.us.mirror.archlinuxarm.org/aarch64/extra/$package_name
download_dir=$root/tooling/downloads/qt-platform-theme
package=$download_dir/$package_name
work=/tmp/archphene-qt-platform-theme-arm64
sysroot=$work/sysroot
source_dir=$root/native/archphene-qt-platform-theme
output=$root/prebuilt/qt-bridge/arm64-v8a
runtime_root=$root/tooling/downloads/arch-curated-kcalc-aarch64/runtime-root

command -v aarch64-linux-gnu-g++ >/dev/null
test "$(pkg-config --modversion Qt6Core)" = "$qt_version"
test -f "$runtime_root/usr/include/KF6/KConfig/kconfig_version.h"
test -f "$runtime_root/usr/include/KF6/KConfigCore/KSharedConfig"
test -f "$runtime_root/usr/lib/libKF6ConfigCore.so"
mkdir -p "$download_dir"
if ! printf "%s  %s\n" "$package_sha256" "$package" | sha256sum -c - >/dev/null 2>&1; then
  rm -f "$package"
  curl --proto =https --tlsv1.2 --fail --location --retry 3 \
    --silent --show-error --output "$package" "$package_url"
fi
printf "%s  %s\n" "$package_sha256" "$package" | sha256sum -c -

rm -rf "$work"
mkdir -p "$sysroot" "$work/platform" "$work/style" "$work/kde-config" "$output"
bsdtar -xf "$package" -C "$sysroot"

/usr/lib/qt6/moc -DQT_NO_DEBUG -DQT_PLUGIN -DQT_WIDGETS_LIB -DQT_GUI_LIB -DQT_CORE_LIB \
  -I/usr/include/qt6 -I/usr/include/qt6/QtWidgets -I/usr/include/qt6/QtGui \
  -I/usr/include/qt6/QtCore "$source_dir/archphenestyle.cpp" \
  -o "$work/style/archphenestyle.moc"
/usr/lib/qt6/moc -DQT_NO_DEBUG -DQT_PLUGIN -DQT_WIDGETS_LIB -DQT_GUI_LIB -DQT_CORE_LIB \
  -I/usr/include/qt6/QtGui/$qt_version -I/usr/include/qt6/QtGui/$qt_version/QtGui \
  -I/usr/include/qt6 -I/usr/include/qt6/QtWidgets -I/usr/include/qt6/QtGui \
  -I/usr/include/qt6/QtCore/$qt_version -I/usr/include/qt6/QtCore/$qt_version/QtCore \
  -I/usr/include/qt6/QtCore "$source_dir/archpheneplatformtheme.cpp" \
  -o "$work/platform/archpheneplatformtheme.moc"

aarch64-linux-gnu-g++ -c -O2 -std=gnu++17 -Wall -Wextra -fPIC \
  -DQT_NO_DEBUG -DQT_PLUGIN -DQT_WIDGETS_LIB -DQT_GUI_LIB -DQT_CORE_LIB \
  -I"$source_dir" -I"$work/style" -I"$sysroot/usr/include/qt6" \
  -I"$sysroot/usr/include/qt6/QtWidgets" -I"$sysroot/usr/include/qt6/QtGui" \
  -I"$sysroot/usr/include/qt6/QtCore" -o "$work/style/archphenestyle.o" \
  "$source_dir/archphenestyle.cpp"
aarch64-linux-gnu-g++ -shared -fPIC -Wl,-O1 -Wl,-rpath,/usr/lib \
  -Wl,-rpath-link,"$sysroot/usr/lib" -Wl,--allow-shlib-undefined \
  -o "$output/libarchphene_qt_style.so" "$work/style/archphenestyle.o" \
  -L"$sysroot/usr/lib" -lQt6Widgets -lQt6Gui -lQt6Core -lpthread

aarch64-linux-gnu-g++ -c -O2 -std=gnu++17 -Wall -Wextra -fPIC \
  -DQT_NO_DEBUG -DQT_PLUGIN -DQT_WIDGETS_LIB -DQT_GUI_LIB -DQT_CORE_LIB \
  -I"$source_dir" -I"$work/platform" \
  -I"$sysroot/usr/include/qt6/QtGui/$qt_version" \
  -I"$sysroot/usr/include/qt6/QtGui/$qt_version/QtGui" \
  -I"$sysroot/usr/include/qt6" -I"$sysroot/usr/include/qt6/QtWidgets" \
  -I"$sysroot/usr/include/qt6/QtGui" \
  -I"$sysroot/usr/include/qt6/QtCore/$qt_version" \
  -I"$sysroot/usr/include/qt6/QtCore/$qt_version/QtCore" \
  -I"$sysroot/usr/include/qt6/QtCore" \
  -o "$work/platform/archpheneplatformtheme.o" \
  "$source_dir/archpheneplatformtheme.cpp"
aarch64-linux-gnu-g++ -shared -fPIC -Wl,-O1 -Wl,-rpath,/usr/lib \
  -Wl,-rpath-link,"$sysroot/usr/lib" -Wl,--allow-shlib-undefined \
  -o "$output/libarchphene_qt_platform_theme.so" \
  "$work/platform/archpheneplatformtheme.o" \
  -L"$sysroot/usr/lib" -lQt6Widgets -lQt6Gui -lQt6Core -lpthread
aarch64-linux-gnu-g++ -c -O2 -std=gnu++17 -Wall -Wextra -fPIC \
  -DQT_NO_DEBUG -DQT_PLUGIN -DQT_CORE_LIB \
  -I"$source_dir" -I"$runtime_root/usr/include/KF6/KConfig" \
  -I"$runtime_root/usr/include/KF6/KConfigCore" \
  -I"$sysroot/usr/include/qt6" -I"$sysroot/usr/include/qt6/QtCore" \
  -o "$work/kde-config/archphenekdeconfig.o" \
  "$source_dir/archphenekdeconfig.cpp"
aarch64-linux-gnu-g++ -shared -fPIC -Wl,-O1 -Wl,-rpath,/usr/lib \
  -Wl,-rpath-link,"$sysroot/usr/lib" -Wl,--allow-shlib-undefined \
  -o "$output/libarchphene_kde_config.so" \
  "$work/kde-config/archphenekdeconfig.o" \
  "$runtime_root/usr/lib/libKF6ConfigCore.so" \
  -L"$sysroot/usr/lib" -lQt6Core -lpthread

aarch64-linux-gnu-strip --strip-unneeded \
  "$output/libarchphene_qt_style.so" \
  "$output/libarchphene_qt_platform_theme.so" \
  "$output/libarchphene_kde_config.so"

for file in "$output/libarchphene_qt_style.so" "$output/libarchphene_qt_platform_theme.so" "$output/libarchphene_kde_config.so"; do
  test "$(aarch64-linux-gnu-readelf -h "$file" | sed -n "s/.*Machine:[[:space:]]*//p")" = AArch64
done
