param(
    [string]$Serial = "emulator-5554",
    [string]$CandidateApk = ""
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"
if (-not $CandidateApk) {
    $CandidateApk = Join-Path $Root "tooling/build/manager-self-update/manager-0.9.0.apk"
}
$CandidateApk = Resolve-Path $CandidateApk
$Remote = "/data/local/tmp/archphene-rejected-update.apk"
$Private = "/data/user/0/$Package/cache/archphene-rejected-update.apk"

function Get-PackageVersion {
    $dump = (& $Adb -s $Serial shell dumpsys package $Package) -join "`n"
    $code = [regex]::Match($dump, 'versionCode=(\d+)').Groups[1].Value
    $name = [regex]::Match($dump, 'versionName=([^\s]+)').Groups[1].Value
    if (-not $code -or -not $name) { throw "Archphene is not installed on $Serial" }
    return "$code|$name"
}

function Wait-ForBanner([string]$Expected) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 500
        & $Adb -s $Serial shell uiautomator dump --compressed /sdcard/archphene-rejection.xml `
            2>$null | Out-Null
        $ui = (& $Adb -s $Serial shell cat /sdcard/archphene-rejection.xml 2>$null) -join ""
        if ($ui -match [regex]::Escape($Expected)) { return }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Did not observe rejection banner: $Expected"
}

function Invoke-RejectedUpdate([string]$Hash, [string]$Expected) {
    & $Adb -s $Serial shell am force-stop $Package | Out-Null
    & $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" `
        --es archphene_test_apk_url "file://$Private" `
        --es archphene_test_apk_sha256 $Hash `
        --es archphene_test_apk_package $Package | Out-Null
    Wait-ForBanner $Expected
    $resumed = (& $Adb -s $Serial shell dumpsys activity activities) -join "`n"
    if ($resumed -match 'com\.android\.(packageinstaller|permissioncontroller)') {
        throw "Rejected update incorrectly opened Android Package Installer"
    }
}

$Before = Get-PackageVersion
& $Adb -s $Serial push $CandidateApk $Remote | Out-Null
& $Adb -s $Serial shell run-as $Package cp $Remote cache/archphene-rejected-update.apk
& $Adb -s $Serial shell run-as $Package chmod 600 cache/archphene-rejected-update.apk
$Hash = (Get-FileHash $CandidateApk -Algorithm SHA256).Hash.ToLowerInvariant()

Invoke-RejectedUpdate $Hash "Install failed: APK version downgrade rejected"
if ((Get-PackageVersion) -ne $Before) { throw "Downgrade rejection changed manager version" }

$BadHash = "0" * 64
if ($BadHash -eq $Hash) { $BadHash = "f" * 64 }
Invoke-RejectedUpdate $BadHash "Install failed: APK SHA-256 mismatch"
if ((Get-PackageVersion) -ne $Before) { throw "Checksum rejection changed manager version" }

Write-Host "Manager update rejections passed on ${Serial}: downgrade and checksum failures retained $Before."
