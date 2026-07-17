$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
podman run --rm -v "${Root}:/workspace" -w /workspace docker.io/library/archlinux:base-devel bash -lc "pacman -Sy --noconfirm --needed pipewire gstreamer gst-plugin-pipewire gst-plugins-base jq >/dev/null && bash scripts/test-pipewire-camera-runtime.sh"
if ($LASTEXITCODE -ne 0) { throw "PipeWire camera runtime test failed" }
