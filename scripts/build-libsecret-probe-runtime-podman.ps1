param()

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
podman run --rm -v "$($Root):/workspace" -w /workspace archlinux:base-devel bash scripts/build-libsecret-probe-runtime.sh
if ($LASTEXITCODE -ne 0) { throw "Arch libsecret probe runtime build failed" }
