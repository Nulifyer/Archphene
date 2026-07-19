param(
    [string]$Serial = "emulator-5554",
    [switch]$SkipDocumentWorkflow,
    [switch]$SkipPackageInstaller
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$managerDump = (& $Adb -s $Serial shell dumpsys package org.archpheneos.manager) -join "`n"
$ManagerDebuggable = $managerDump -match '(?m)^\s*flags=\[[^\]]*DEBUGGABLE'
$tests = @(
    @{ Name = "Linux manager update"; Command = { & "$PSScriptRoot/test-linux-manager-update.ps1" -Serial $Serial } },
    @{ Name = "Linux manager pull refresh"; Command = { & "$PSScriptRoot/test-linux-manager-pull-refresh.ps1" -Serial $Serial } },
    @{ Name = "Linux manager KCalc launch"; Command = { & "$PSScriptRoot/test-linux-manager-kcalc.ps1" -Serial $Serial } },
    @{ Name = "Shared runtime FD execution"; Command = { & "$PSScriptRoot/test-runtime-module-fd-sharing.ps1" -Serial $Serial } },
    @{ Name = "Linux manager catalog isolation"; Command = { & "$PSScriptRoot/test-linux-manager-catalog.ps1" -Serial $Serial } },
    @{ Name = "Linux manager Obtainium workflow"; Command = { & "$PSScriptRoot/test-linux-manager-obtainium-workflow.ps1" -Serial $Serial } },
    @{ Name = "Linux manager repository search"; Command = { & "$PSScriptRoot/test-linux-manager-repository-search.ps1" -Serial $Serial } },
    @{ Name = "Linux manager version selector"; Command = { & "$PSScriptRoot/test-linux-manager-version-selector.ps1" -Serial $Serial } },
    @{ Name = "KCalc native menus"; Command = { & "$PSScriptRoot/test-kcalc-menu-switch.ps1" -Serial $Serial } },
    @{ Name = "Native compositor protocols"; Command = {
        & "$PSScriptRoot/build-install-native-compositor-probe.ps1" -Serial $Serial -AndroidAbi x86_64
        if ($LASTEXITCODE -eq 0) {
            & "$PSScriptRoot/test-native-compositor-probe.ps1" -Serial $Serial -AndroidAbi x86_64
        }
    } },
    @{ Name = "KCalc calculation"; Command = { & "$PSScriptRoot/test-kcalc-calculation.ps1" -Serial $Serial } },
    @{ Name = "KCalc live resize"; Command = { & "$PSScriptRoot/test-kcalc-live-resize.ps1" -Serial $Serial } }
)
if (-not $SkipPackageInstaller -and $ManagerDebuggable) {
    $tests += @{ Name = "Manager PackageInstaller"; Command = { & "$PSScriptRoot/test-linux-manager-package-installer.ps1" -Serial $Serial } }
} elseif (-not $SkipPackageInstaller) {
    Write-Host "Skipping debug-hook PackageInstaller fixture for non-debuggable manager; use the production self-update/package workflow regressions."
}
if (-not $SkipDocumentWorkflow) {
    $tests += @{ Name = "Mousepad Android document workflow"; Command = { & "$PSScriptRoot/test-mousepad-android-document-workflow.ps1" -Serial $Serial } }
    $tests += @{ Name = "Mousepad open-dialog IME"; Command = { & "$PSScriptRoot/test-mousepad-open-dialog-ime.ps1" -Serial $Serial } }
    $tests += @{ Name = "Mousepad touch routing"; Command = { & "$PSScriptRoot/test-mousepad-touch-routing.ps1" -Serial $Serial } }
    $tests += @{ Name = "Mousepad secondary windows"; Command = { & "$PSScriptRoot/test-mousepad-secondary-window.ps1" -Serial $Serial } }
}

$results = @()
foreach ($test in $tests) {
    $started = Get-Date
    Write-Host "`n==> $($test.Name)"
    & $test.Command
    if ($LASTEXITCODE -ne 0) {
        throw "$($test.Name) failed with exit code $LASTEXITCODE"
    }
    $results += [pscustomobject]@{
        Test = $test.Name
        Status = "PASS"
        Seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    }
}

Write-Host "`nArchphene emulator regression suite passed."
$results | Format-Table -AutoSize