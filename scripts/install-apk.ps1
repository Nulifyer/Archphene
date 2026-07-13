param(
    [string]$Apk = "prototypes/linux-app-manager-stub/out-linux/archphene.apk",
    [string]$Serial,
    [string]$Package = "org.archpheneos.manager",
    [string]$Activity,
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ApkPath = if ([IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $Root $Apk }
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK not found: $ApkPath"
}

$SdkCandidates = @(
    (Join-Path $Root "tooling/android-sdk"),
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME
) | Where-Object { $_ }
$Sdk = $SdkCandidates | Where-Object {
    Test-Path -LiteralPath (Join-Path $_ "platform-tools/adb.exe") -PathType Leaf
} | Select-Object -First 1
if (-not $Sdk) {
    throw "adb.exe not found in tooling/android-sdk, ANDROID_SDK_ROOT, or ANDROID_HOME"
}

$Adb = Join-Path $Sdk "platform-tools/adb.exe"
$Target = if ($Serial) { @("-s", $Serial) } else { @() }

function Invoke-Adb([string[]]$Arguments) {
    & $Adb @Target @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

Invoke-Adb @("get-state")
Invoke-Adb @("install", "-r", $ApkPath)

if (-not $NoLaunch) {
    if ($Activity) {
        Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity")
    } else {
        Invoke-Adb @("shell", "monkey", "-p", $Package, "-c", "android.intent.category.LAUNCHER", "1")
    }
}

$targetName = if ($Serial) { $Serial } else { "the selected ADB device" }
Write-Host "Installed $ApkPath on $targetName."