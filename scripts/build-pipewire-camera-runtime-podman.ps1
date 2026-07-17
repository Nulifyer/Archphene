param(
    [ValidateSet("x86_64", "aarch64")]
    [string]$Architecture = "x86_64"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Image = "localhost/archphene-arm-runtime-builder:latest"
podman image exists $Image
if ($LASTEXITCODE -ne 0) {
    podman build -f (Join-Path $Root "containers/arm-runtime-builder.Containerfile") -t $Image $Root
    if ($LASTEXITCODE -ne 0) { throw "PipeWire runtime builder image failed" }
}
podman run --rm -v "${Root}:/workspace" -w /workspace $Image bash -lc `
    "pacman -Sy --noconfirm --needed meson >/dev/null && bash scripts/build-pipewire-camera-runtime.sh $Architecture"
if ($LASTEXITCODE -ne 0) { throw "PipeWire camera runtime build failed for $Architecture" }
