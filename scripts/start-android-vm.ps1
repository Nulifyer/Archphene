param(
    [string]$AvdName = "ArchpheneOS_x86_64_api36",
    [ValidateSet("host", "auto", "swiftshader_indirect")]
    [string]$GpuMode = "host",
    [ValidateRange(2, 16)]
    [int]$Cores = 6,
    [ValidateRange(2048, 16384)]
    [int]$MemoryMb = 8192
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$env:ANDROID_SDK_ROOT = Join-Path $Root "tooling/android-sdk"
$env:ANDROID_AVD_HOME = Join-Path $Root "tooling/avd"

& (Join-Path $env:ANDROID_SDK_ROOT "emulator/emulator.exe") `
    -avd $AvdName `
    -gpu $GpuMode `
    -cores $Cores `
    -memory $MemoryMb `
    -no-boot-anim `
    -partition-size 2047
