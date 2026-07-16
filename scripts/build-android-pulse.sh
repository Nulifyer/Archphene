#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
architecture="${1:-x86_64}"
case "$architecture" in
  x86_64|aarch64) ;;
  *) echo "architecture must be x86_64 or aarch64" >&2; exit 2 ;;
esac

for command in curl sha256sum bsdtar patchelf readelf; do
  command -v "$command" >/dev/null || { echo "$command is required" >&2; exit 1; }
done

catalog="$root/native/archphene-audio/termux-pulse-packages.tsv"
download="$root/tooling/downloads/termux-pulse/$architecture"
work="$root/tooling/build/android-pulse/$architecture"
extracted="$work/root"
output="$work/out"
base_url="https://packages.termux.dev/apt/termux-main"
rm -rf "$work"
mkdir -p "$download" "$extracted" "$output"

while IFS='|' read -r package arch version bytes digest relative; do
  [[ -n "$package" && "$package" != \#* && "$arch" == "$architecture" ]] || continue
  deb="$download/$(basename "$relative")"
  if [[ ! -f "$deb" || "$(stat -c '%s' "$deb")" != "$bytes" ]] \
      || ! printf '%s  %s\n' "$digest" "$deb" | sha256sum --check --quiet; then
    rm -f "$deb"
    curl --proto '=https' --tlsv1.2 --fail --location --retry 4 \
      --output "$deb" "$base_url/$relative"
  fi
  [[ "$(stat -c '%s' "$deb")" == "$bytes" ]] || { echo "size mismatch: $package" >&2; exit 1; }
  printf '%s  %s\n' "$digest" "$deb" | sha256sum --check --quiet
  bsdtar -xOf "$deb" data.tar.xz | bsdtar -xJf - -C "$extracted"
done < "$catalog"

prefix="$extracted/data/data/com.termux/files/usr"
copy_file() {
  local source="$1" destination="$2"
  [[ -f "$source" ]] || { echo "missing audio payload input: $source" >&2; exit 1; }
  cp -L "$source" "$output/$destination"
}

copy_file "$prefix/bin/pulseaudio" libarchphene_pulseaudio.so
copy_file "$prefix/bin/pacat" libarchphene_pulse_probe.so
copy_file "$prefix/lib/pulseaudio/modules/module-aaudio-sink.so" \
  libarchphene_pulse_module_aaudio_sink.so
copy_file "$prefix/lib/pulseaudio/modules/module-sles-sink.so" \
  libarchphene_pulse_module_sles_sink.so
copy_file "$prefix/lib/pulseaudio/modules/module-native-protocol-unix.so" \
  libarchphene_pulse_module_native_protocol_unix.so

libraries=(
  libprotocol-native.so libpulsecore-17.0.so libpulsecommon-17.0.so
  libpulse.so libltdl.so libdbus-1.so libsndfile.so libsoxr.so
  libspeexdsp.so libiconv.so libandroid-execinfo.so libFLAC.so
  libvorbis.so libvorbisenc.so libopus.so libogg.so libmp3lame.so
)
for library in "${libraries[@]}"; do
  if [[ -f "$prefix/lib/pulseaudio/modules/$library" ]]; then
    copy_file "$prefix/lib/pulseaudio/modules/$library" "$library"
  elif [[ -f "$prefix/lib/pulseaudio/$library" ]]; then
    copy_file "$prefix/lib/pulseaudio/$library" "$library"
  else
    copy_file "$prefix/lib/$library" "$library"
  fi
done

for file in "$output"/*.so; do
  patchelf --remove-rpath "$file"
  readelf -h "$file" | grep -q 'Type:.*DYN' || {
    echo "audio payload is not ET_DYN: $file" >&2; exit 1;
  }
done

platform_libraries=' libc.so libm.so libdl.so libaaudio.so libOpenSLES.so liblog.so '
for file in "$output"/*.so; do
  while read -r needed; do
    [[ -f "$output/$needed" || "$platform_libraries" == *" $needed "* ]] || {
      echo "unresolved audio dependency: $(basename "$file") -> $needed" >&2
      exit 1
    }
  done < <(readelf -d "$file" | awk '/Shared library:/ {gsub(/\[|\]/, "", $5); print $5}')
done

(cd "$output" && sha256sum ./*.so | sort -k2 > SHA256SUMS)
printf 'Android PulseAudio payload ready: %s (%s bytes)\n' \
  "$output" "$(du -sb "$output" | cut -f1)"
