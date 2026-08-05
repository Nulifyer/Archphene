#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCHITECTURE="${1:-x86_64}"

case "$ARCHITECTURE" in
  x86_64)
    exec podman run --rm \
      -v "$ROOT:/workspace:Z" \
      -w /workspace \
      docker.io/library/archlinux:base-devel \
      bash -lc '
        pacman -Sy --noconfirm --needed \
          curl expat libdrm libx11 libxcb libxfixes libxrandr libxshmfence \
          meson ninja patch python-mako python-packaging python-pyyaml \
          wayland wayland-protocols
        bash scripts/build-mesa-virpipe-sender.sh x86_64
      '
    ;;
  aarch64)
    exec podman run --rm --platform linux/amd64 \
      -v "$ROOT:/workspace:Z" \
      -w /workspace \
      docker.io/library/debian:trixie \
      bash -lc '
        dpkg --add-architecture arm64
        apt-get update
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
          bison build-essential ca-certificates crossbuild-essential-arm64 curl flex \
          libdrm-dev:arm64 libexpat1-dev:arm64 libwayland-dev:arm64 \
          libwayland-egl-backend-dev:arm64 \
          libx11-dev:arm64 libx11-xcb-dev:arm64 libxcb-shm0-dev:arm64 \
          libxfixes-dev:arm64 libxrandr-dev:arm64 libxshmfence-dev:arm64 \
          meson ninja-build patch pkg-config python3-mako python3-packaging \
          python3-yaml wayland-protocols zlib1g-dev:arm64
        q="$(printf "\\047")"
        cat > /tmp/archphene-aarch64.ini <<EOF
[binaries]
c = ${q}aarch64-linux-gnu-gcc${q}
cpp = ${q}aarch64-linux-gnu-g++${q}
ar = ${q}aarch64-linux-gnu-ar${q}
strip = ${q}aarch64-linux-gnu-strip${q}
pkg-config = ${q}pkg-config${q}

[host_machine]
system = ${q}linux${q}
cpu_family = ${q}aarch64${q}
cpu = ${q}aarch64${q}
endian = ${q}little${q}

[properties]
needs_exe_wrapper = true
EOF
        export PKG_CONFIG_LIBDIR=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig
        MESA_CROSS_FILE=/tmp/archphene-aarch64.ini \
          bash scripts/build-mesa-virpipe-sender.sh aarch64
      '
    ;;
  *)
    echo "Unsupported Mesa virpipe sender architecture: $ARCHITECTURE" >&2
    exit 2
    ;;
esac
