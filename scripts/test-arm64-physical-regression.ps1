param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [switch]$SkipSignatureGate,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"

function Run-Script([string]$Name, [hashtable]$Arguments = @{}) {
    Write-Host "`n=== $Name ==="
    & (Join-Path $PSScriptRoot $Name) @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

$state = (& $Adb -s $Serial get-state 2>$null)
if ($LASTEXITCODE -ne 0 -or $state.Trim() -ne "device") {
    throw "ADB device $Serial is not authorized and online"
}
$abis = (& $Adb -s $Serial shell getprop ro.product.cpu.abilist).Trim()
if ($abis -notmatch '(^|,)arm64-v8a(,|$)') { throw "$Serial is not ARM64: $abis" }

if (-not $SkipSignatureGate) {
    Run-Script "test-archlinuxarm-package-signatures.ps1"
}
if (-not $SkipInstall) {
    Run-Script "build-install-arm64-bridge-probe.ps1" @{ Serial = $Serial }
    Run-Script "build-install-kcalc-app.ps1" @{
        DescriptorPath = "prototypes/kcalc-android-app/archphene-app-aarch64.json"
        AndroidAbi = "arm64-v8a"
        Serial = $Serial
    }
}

Run-Script "test-arm64-physical-device.ps1" @{ Serial = $Serial }
Run-Script "test-kcalc-menu-switch.ps1" @{ Serial = $Serial }
Run-Script "build-install-native-compositor-probe.ps1" @{ Serial = $Serial; AndroidAbi = "arm64-v8a" }
Run-Script "test-native-compositor-probe.ps1" @{ Serial = $Serial; AndroidAbi = "arm64-v8a" }
Run-Script "test-kcalc-calculation.ps1" @{ Serial = $Serial }
Run-Script "test-kcalc-live-resize.ps1" @{ Serial = $Serial }
Run-Script "test-kcalc-fd-lifecycle.ps1" @{ Serial = $Serial }
Run-Script "test-kcalc-physical-freeform-resize.ps1" @{ Serial = $Serial }

Write-Host "`nARM64 physical regression passed on $Serial ($abis)."
