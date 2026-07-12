param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"

& $Adb -s $Serial shell pm clear org.archpheneos.manager | Out-Null
& $Adb -s $Serial shell am start -S -W -n org.archpheneos.manager/.MainActivity | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not launch Linux app manager" }
Start-Sleep -Seconds 2
& $Adb -s $Serial shell uiautomator dump /sdcard/archphene-manager-catalog.xml | Out-Null
& $Adb -s $Serial pull /sdcard/archphene-manager-catalog.xml `
        (Join-Path $Root "artifacts/archphene-manager-catalog.xml") | Out-Null
$catalog = Get-Content -LiteralPath (Join-Path $Root "artifacts/archphene-manager-catalog.xml") -Raw
foreach ($required in @("Archphene", "KCalc", "Mousepad", "extra/kcalc", "extra/mousepad", "glibc-x86_64")) {
    if (-not $catalog.Contains($required)) { throw "Manager catalog is missing $required" }
}

$kcalc = (& $Adb -s $Serial shell cmd package list packages -U org.archphene.linux.kcalc |
    Select-String '^package:org\.archphene\.linux\.kcalc uid:\d+$' | Select-Object -First 1).Line
$mousepad = (& $Adb -s $Serial shell cmd package list packages -U org.archphene.linux.mousepad |
    Select-String '^package:org\.archphene\.linux\.mousepad uid:\d+$' | Select-Object -First 1).Line
if (-not $kcalc -or -not $mousepad -or $kcalc -eq $mousepad) {
    throw "KCalc and Mousepad do not have distinct Android UIDs: $kcalc / $mousepad"
}

Write-Host "Manager catalog passed: KCalc and Mousepad discovered with shared ABI metadata and distinct Android UIDs."