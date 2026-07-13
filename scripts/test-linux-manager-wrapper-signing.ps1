param(
    [string]$Serial = "emulator-5554",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"
$Apk = Join-Path $Root "prototypes/linux-app-manager-stub/out/archpheneos-manager.apk"

function Get-Ui {
    $path = "/sdcard/archphene-wrapper-signing.xml"
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 500
        & $Adb -s $Serial shell uiautomator dump --compressed $path 2>$null | Out-Null
        $ui = (& $Adb -s $Serial shell cat $path 2>$null) -join "`n"
        if ($ui.Contains("Signed generated APK") -or $ui.Contains("APK signing failed")) { return $ui }
    }
    return ""
}

function Invoke-Signing {
    & $Adb -s $Serial push $Apk /data/local/tmp/archphene-signing-input.apk | Out-Null
    & $Adb -s $Serial shell run-as $Package mkdir -p files/package-runtime | Out-Null
    & $Adb -s $Serial shell run-as $Package cp /data/local/tmp/archphene-signing-input.apk `
        files/package-runtime/signing-input.apk | Out-Null
    $inputPath = "/data/user/0/$Package/files/package-runtime/signing-input.apk"
    & $Adb -s $Serial shell am force-stop $Package | Out-Null
    & $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" `
        --es archphene_test_sign_apk_file $inputPath | Out-Null
    $ui = Get-Ui
    $match = [regex]::Match($ui, 'Signer ([0-9a-f]{64})')
    if (-not $ui.Contains("v2=true v3=true") -or -not $match.Success) {
        throw "Manager did not generate and verify a v2/v3 APK: $ui"
    }
    return $match.Groups[1].Value
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build-install-linux-manager-stub.ps1") -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw "Manager build failed" }
}
$first = Invoke-Signing
& $Adb -s $Serial install -r $Apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Manager replacement install failed" }
$second = Invoke-Signing
if ($first -ne $second) {
    throw "Android Keystore wrapper signer changed across manager update: $first -> $second"
}
Write-Host "Persistent Android Keystore APK signer passed: $first"