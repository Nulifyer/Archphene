param(
    [string]$AvdName = "ArchpheneOS_x86_64_api36",
    [ValidateSet("host", "auto", "swiftshader_indirect")]
    [string]$GpuMode = "host",
    [ValidateRange(2, 16)]
    [int]$Cores = 6,
    [ValidateRange(2048, 16384)]
    [int]$MemoryMb = 8192,
    [switch]$AllowSnapshots
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$env:ANDROID_SDK_ROOT = Join-Path $Root "tooling/android-sdk"
$env:ANDROID_AVD_HOME = Join-Path $Root "tooling/avd"
$env:ANDROID_EMULATOR_HOME = Join-Path $Root "tooling/emulator-home"
$env:ANDROID_PREFS_ROOT = $env:ANDROID_EMULATOR_HOME
New-Item -ItemType Directory -Force -Path $env:ANDROID_EMULATOR_HOME | Out-Null

$Arguments = @(
    "-avd", $AvdName,
    "-gpu", $GpuMode,
    "-cores", [string]$Cores,
    "-memory", [string]$MemoryMb,
    "-no-boot-anim",
    "-partition-size", "2047",
    "-no-metrics")
if (-not $AllowSnapshots) { $Arguments += "-no-snapshot-load" }

$Emulator = Join-Path $env:ANDROID_SDK_ROOT "emulator/emulator.exe"
$Process = Start-Process -FilePath $Emulator -ArgumentList $Arguments -WindowStyle Normal -PassThru
Write-Output "Started visible emulator $AvdName (PID $($Process.Id))"
