param([switch]$SkipBuild, [string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$OldApk = Join-Path $Root "tooling/build/kcalc-version-fixtures/kcalc-26.04.0-1.apk"
$LatestApk = Join-Path $Root "prototypes/kcalc-android-app/out/archpheneos-kcalc.apk"
$Package = "org.archphene.linux.kcalc"

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build-kcalc-version-fixture.ps1")
    & (Join-Path $PSScriptRoot "build-install-kcalc-app.ps1") -SkipInstall
}
foreach ($apk in @($OldApk, $LatestApk)) {
    if (-not (Test-Path -LiteralPath $apk)) { throw "Missing update fixture: $apk" }
}
& $Adb -s $Serial uninstall $Package 2>$null | Out-Null
& $Adb -s $Serial install $OldApk | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not install KCalc 26.04.0-1" }
$before = (& $Adb -s $Serial shell dumpsys package $Package) -join "`n"
if ($before -notmatch 'versionName=26\.04\.0-1') { throw "Older KCalc version was not installed" }
& (Join-Path $PSScriptRoot "test-kcalc-calculation.ps1") -Serial $Serial
& (Join-Path $PSScriptRoot "test-linux-manager-package-installer.ps1") -Serial $Serial
$after = (& $Adb -s $Serial shell dumpsys package $Package) -join "`n"
if ($after -notmatch 'versionName=26\.04\.3-1') { throw "KCalc did not update to 26.04.3-1" }
Write-Host "KCalc version update passed: signed Arch 26.04.0-1 -> 26.04.3-1 with GUI health checks."