param()

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
podman run --rm -v "$($Root):/workspace" -w /workspace archlinux:base-devel bash scripts/build-kwallet-compat-runtime.sh
if ($LASTEXITCODE -ne 0) { throw "KWallet compatibility daemon build failed" }
