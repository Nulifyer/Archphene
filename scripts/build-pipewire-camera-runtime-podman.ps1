param(
    [ValidateSet("x86_64", "aarch64")]
    [string]$Architecture = "x86_64"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Source = Join-Path $Root "tooling/external/pipewire"
$ExpectedCommit = "b741e0c74f5436f0c925f7741140db0efd32cf4e"
$Upstream = "https://gitlab.freedesktop.org/pipewire/pipewire.git"

if (-not (Test-Path -LiteralPath (Join-Path $Source ".git") -PathType Container)) {
    if (Test-Path -LiteralPath $Source) {
        throw "Unmanaged PipeWire source exists: $Source"
    }
    New-Item -ItemType Directory -Path $Source | Out-Null
    git init $Source | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "PipeWire source initialization failed" }
    git -C $Source remote add origin $Upstream
    if ($LASTEXITCODE -ne 0) { throw "PipeWire upstream configuration failed" }
}
$origin = (git -C $Source remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $origin -ne $Upstream) {
    throw "PipeWire source origin is not the official upstream: $origin"
}
$currentOutput = git -C $Source rev-parse HEAD 2>$null
$current = if ($LASTEXITCODE -eq 0) { ($currentOutput -join "").Trim() } else { "" }
if ($current -ne $ExpectedCommit) {
    $dirty = git -C $Source status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) {
        throw "PipeWire source has local changes and cannot select $ExpectedCommit"
    }
    git -C $Source fetch --depth=1 origin $ExpectedCommit
    if ($LASTEXITCODE -ne 0) { throw "Pinned PipeWire source fetch failed" }
    git -C $Source checkout --detach FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw "Pinned PipeWire source checkout failed" }
}
$verified = (git -C $Source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $verified -ne $ExpectedCommit) {
    throw "PipeWire source verification failed: $verified"
}
$Image = "localhost/archphene-arm-runtime-builder:latest"
podman image exists $Image
if ($LASTEXITCODE -ne 0) {
    podman build -f (Join-Path $Root "containers/arm-runtime-builder.Containerfile") -t $Image $Root
    if ($LASTEXITCODE -ne 0) { throw "PipeWire runtime builder image failed" }
}
podman run --rm -v "${Root}:/workspace" -w /workspace $Image bash -lc `
    "pacman -Sy --noconfirm --needed meson >/dev/null && bash scripts/build-pipewire-camera-runtime.sh $Architecture"
if ($LASTEXITCODE -ne 0) { throw "PipeWire camera runtime build failed for $Architecture" }
