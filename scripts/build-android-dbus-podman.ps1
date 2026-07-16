param(
    [ValidateSet("x86_64", "aarch64")]
    [string]$Architecture = "x86_64",
    [switch]$RebuildImage,
    [switch]$DebugBuild
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Image = "localhost/archphene-android-native:ndk29-rust1.88"

podman image exists $Image
$ImageExists = $LASTEXITCODE -eq 0
if ($RebuildImage -or -not $ImageExists) {
    podman build -f (Join-Path $Root "containers/android-native.Containerfile") `
        -t $Image $Root
    if ($LASTEXITCODE -ne 0) { throw "Android native image build failed" }
}

$buildType = if ($DebugBuild) { "debugoptimized" } else { "release" }
podman run --rm -v "${Root}:/workspace" -w /workspace `
    -e "DBUS_BUILD_TYPE=$buildType" `
    $Image bash scripts/build-android-dbus.sh $Architecture
if ($LASTEXITCODE -ne 0) { throw "Android D-Bus build failed" }

$OutputDir = Join-Path $Root "tooling/build/android-dbus/$Architecture"
foreach ($name in @("dbus-daemon", "portal-service", "portal-probe", "xdg-open")) {
    $output = Join-Path $OutputDir $name
    if (-not (Test-Path -LiteralPath $output -PathType Leaf)) {
        throw "Android D-Bus output is missing: $output"
    }
}
Write-Output "Android D-Bus helpers: $OutputDir"
