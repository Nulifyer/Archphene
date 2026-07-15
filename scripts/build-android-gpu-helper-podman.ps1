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
    if ($LASTEXITCODE -ne 0) { throw "Android GPU helper image build failed" }
}

$buildType = if ($DebugBuild) { "debugoptimized" } else { "release" }
podman run --rm -v "${Root}:/workspace" -w /workspace `
    -e "GPU_BUILD_TYPE=$buildType" `
    $Image bash scripts/build-android-gpu-helper.sh $Architecture
if ($LASTEXITCODE -ne 0) { throw "Android GPU helper build failed" }

$Output = Join-Path $Root "tooling/build/android-gpu/$Architecture/virgl_test_server_android"
if (-not (Test-Path -LiteralPath $Output -PathType Leaf)) {
    throw "Android GPU helper output is missing: $Output"
}
Write-Output "Android GPU helper: $Output"
