param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64",
    [string]$Serial = "emulator-5554",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "build-native-compositor-probe-podman.ps1") -AndroidAbi $AndroidAbi
if ($LASTEXITCODE -ne 0) { throw "Native compositor probe build failed" }

if (-not $SkipInstall) {
    $apk = "prototypes/native-compositor-probe/out-$AndroidAbi/archphene-compositor-probe.apk"
    & (Join-Path $PSScriptRoot "install-apk.ps1") -Apk $apk -Serial $Serial -Package org.archphene.compositorprobe
}
