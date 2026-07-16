param(
    [ValidateSet("x86_64", "aarch64")]
    [string]$Architecture = "x86_64",
    [switch]$RebuildImage
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Image = "localhost/archphene-android-native:ndk29-rust1.88"

function Invoke-Native([string]$Step, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

Invoke-Native "Podman availability check" { podman info --format "{{.Host.OS}}/{{.Host.Arch}}" }
$exists = podman image exists $Image
if ($LASTEXITCODE -ne 0 -or $RebuildImage) {
    Invoke-Native "Android native image build" {
        podman build -f (Join-Path $Root "containers/android-native.Containerfile") -t $Image $Root
    }
}

Invoke-Native "Android PulseAudio payload build" {
    podman run --rm -v "${Root}:/workspace" -w /workspace $Image `
        bash scripts/build-android-pulse.sh $Architecture
}

$Output = Join-Path $Root "tooling/build/android-pulse/$Architecture/out"
Invoke-Native "Android PulseAudio checksum verification" {
    podman run --rm -v "${Root}:/workspace" -w /workspace $Image `
        bash -lc "cd 'tooling/build/android-pulse/$Architecture/out' && sha256sum --check SHA256SUMS"
}
Write-Host "Android PulseAudio payload: $Output"
