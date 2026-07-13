param(
    [ValidateSet("x86_64", "aarch64")]
    [string]$Architecture = "x86_64",
    [switch]$RebuildImage,
    [switch]$Release
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Image = "localhost/archphene-android-native:ndk29-rust1.88"
$Target = if ($Architecture -eq "aarch64") {
    "aarch64-linux-android"
} else {
    "x86_64-linux-android"
}

podman image exists $Image
$ImageExists = $LASTEXITCODE -eq 0
if ($RebuildImage -or -not $ImageExists) {
    podman build -f (Join-Path $Root "containers/android-native.Containerfile") `
        -t $Image $Root
    if ($LASTEXITCODE -ne 0) { throw "Android native build image failed" }
}

$CargoArguments = @("build", "--target", $Target)
if (Test-Path -LiteralPath (Join-Path $Root "native/archphene-compositor/Cargo.lock")) {
    $CargoArguments += "--locked"
}
if ($Release) { $CargoArguments += "--release" }
$Command = "cargo " + ($CargoArguments -join " ")
podman run --rm -v "${Root}:/workspace" -v archphene-cargo-registry:/opt/cargo/registry -w /workspace/native/archphene-compositor `
    $Image bash -lc $Command
if ($LASTEXITCODE -ne 0) { throw "Native compositor build failed" }

$Profile = if ($Release) { "release" } else { "debug" }
$Library = Join-Path $Root `
    "native/archphene-compositor/target/$Target/$Profile/libarchphene_compositor.so"
if (-not (Test-Path -LiteralPath $Library -PathType Leaf)) {
    throw "Native compositor library missing: $Library"
}
Write-Host "Native compositor library: $Library"
