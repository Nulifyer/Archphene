param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Apk = Join-Path $Root "prototypes/kcalc-android-app/out/archpheneos-kcalc.apk"
$Manager = "org.archpheneos.manager"
$KCalc = "org.archphene.linux.kcalc"
$Remote = "/data/local/tmp/archpheneos-kcalc-update.apk"
$Private = "/data/user/0/$Manager/cache/archpheneos-kcalc-update.apk"
$Hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()

function Get-Ui([string]$Path) {
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        & $Adb -s $Serial shell uiautomator dump --compressed $Path 2>$null | Out-Null
        $ui = ((& $Adb -s $Serial shell cat $Path 2>$null) -join "`n")
        if ($ui -match '<hierarchy') { return $ui }
        Start-Sleep -Milliseconds 400
    }
    return ""
}

& $Adb -s $Serial push $Apk $Remote | Out-Null
& $Adb -s $Serial shell run-as $Manager cp $Remote cache/archpheneos-kcalc-update.apk
& $Adb -s $Serial shell run-as $Manager chmod 600 cache/archpheneos-kcalc-update.apk
& $Adb -s $Serial shell appops set $Manager REQUEST_INSTALL_PACKAGES allow
try {
    & $Adb -s $Serial shell am force-stop $Manager | Out-Null
    & $Adb -s $Serial shell am start -n "$Manager/.MainActivity" `
        --es archphene_test_apk_url "file://$Private" `
        --es archphene_test_apk_sha256 $Hash `
        --es archphene_test_apk_package $KCalc | Out-Null

    $Deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        Start-Sleep -Seconds 1
        $Confirmation = Get-Ui "/sdcard/archphene-package-installer-confirm.xml"
    } while ($Confirmation -notmatch "Do you want to update this app" -and [DateTime]::UtcNow -lt $Deadline)
    if ($Confirmation -notmatch 'package="com\.google\.android\.packageinstaller"' `
            -or $Confirmation -notmatch 'text="KCalc"') {
        throw "Android's KCalc update confirmation did not appear"
    }
    $Update = [regex]::Match($Confirmation, 'text="Update"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $Update.Success) {
        throw "Could not find Android's Update confirmation control"
    }
    $X = ([int]$Update.Groups[1].Value + [int]$Update.Groups[3].Value) / 2
    $Y = ([int]$Update.Groups[2].Value + [int]$Update.Groups[4].Value) / 2
    & $Adb -s $Serial shell input tap ([int]$X) ([int]$Y) | Out-Null

    $Deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        Start-Sleep -Seconds 1
        $Result = Get-Ui "/sdcard/archphene-package-installer-result.xml"
    } while ($Result -notmatch "Android package update installed" -and [DateTime]::UtcNow -lt $Deadline)
    if ($Result -notmatch "Android package update installed") {
        throw "Manager did not receive PackageInstaller success"
    }

    & (Join-Path $PSScriptRoot "test-kcalc-calculation.ps1") -Serial $Serial
    Write-Host "Manager PackageInstaller update passed with Android confirmation and KCalc health check."
}
finally {
    & $Adb -s $Serial shell appops set $Manager REQUEST_INSTALL_PACKAGES default | Out-Null
    & $Adb -s $Serial shell rm -f $Remote | Out-Null
    & $Adb -s $Serial shell run-as $Manager rm -f cache/archpheneos-kcalc-update.apk | Out-Null
}
