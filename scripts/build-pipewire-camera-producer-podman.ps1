$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
podman run --rm -v "${Root}:/workspace" -w /workspace docker.io/library/archlinux:base-devel bash -lc "pacman -Sy --noconfirm --needed pipewire pkgconf >/dev/null && bash scripts/build-pipewire-camera-producer.sh"
if ($LASTEXITCODE -ne 0) { throw "PipeWire camera producer build failed" }
