param([switch]$SkipBuild, [string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"
$OldApk = Join-Path $Root "tooling/build/manager-self-update/manager-0.9.0.apk"
$LatestApk = Join-Path $Root "prototypes/linux-app-manager-stub/out/archpheneos-manager.apk"

function Get-Ui([string]$Name) {
    $path = "/sdcard/$Name.xml"
    & $Adb -s $Serial shell rm -f $path | Out-Null
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        & $Adb -s $Serial shell uiautomator dump --compressed $path 2>$null | Out-Null
        $ui = (& $Adb -s $Serial shell cat $path 2>$null) -join "`n"
        if ($ui -match '<hierarchy') { return $ui }
        Start-Sleep -Milliseconds 500
    }
    return ""
}
function Tap-Text([string]$Ui, [string]$Text) {
    $node = [regex]::Match($Ui, "text=`"$([regex]::Escape($Text))`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find '$Text'" }
    & $Adb -s $Serial shell input tap `
        ([int](([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2)) `
        ([int](([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2)) | Out-Null
}
if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build-install-linux-manager-stub.ps1") -SkipInstall `
        -VersionCode 9000 -VersionName "0.9.0"
    New-Item -ItemType Directory -Force -Path (Split-Path $OldApk) | Out-Null
    Copy-Item (Join-Path $Root "prototypes/linux-app-manager-stub/out/archpheneos-manager.apk") $OldApk -Force
    & (Join-Path $PSScriptRoot "build-install-linux-manager-stub.ps1") -SkipInstall
}
& $Adb -s $Serial uninstall $Package 2>$null | Out-Null
& $Adb -s $Serial install $OldApk | Out-Null
& $Adb -s $Serial push $LatestApk /data/local/tmp/manager-self-update.apk | Out-Null
& $Adb -s $Serial shell run-as $Package cp /data/local/tmp/manager-self-update.apk cache/manager-self-update.apk
& $Adb -s $Serial shell run-as $Package chmod 600 cache/manager-self-update.apk
& $Adb -s $Serial shell appops set $Package REQUEST_INSTALL_PACKAGES allow
& $Adb -s $Serial shell am force-stop com.android.settings | Out-Null
& $Adb -s $Serial shell am start -W -a android.settings.MANAGE_UNKNOWN_APP_SOURCES -d "package:$Package" | Out-Null
Start-Sleep -Seconds 1
$settingsUi = Get-Ui "manager-self-update-source-access"
$toggle = [regex]::Match($settingsUi, 'text="Allow from this source"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($settingsUi -match 'checkable="true" checked="false"' -and $toggle.Success) {
    & $Adb -s $Serial shell input tap 900 ([int](([int]$toggle.Groups[2].Value + [int]$toggle.Groups[4].Value) / 2)) | Out-Null
    Start-Sleep -Milliseconds 500
}& $Adb -s $Serial shell input keyevent 4 | Out-Null
$hash = (Get-FileHash $LatestApk -Algorithm SHA256).Hash.ToLowerInvariant()
function Start-Update {
    & $Adb -s $Serial shell am force-stop $Package | Out-Null
    & $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" `
        --es archphene_test_apk_url "file:///data/user/0/$Package/cache/manager-self-update.apk" `
        --es archphene_test_apk_sha256 $hash `
        --es archphene_test_apk_package $Package | Out-Null
    Start-Sleep -Seconds 3
    return Get-Ui "manager-self-update"
}
$ui = Start-Update
if ($ui -match 'text="Settings"' -and $ui -match 'isn.t allowed to install unknown apps') {
    Tap-Text $ui "Settings"
    Start-Sleep -Seconds 1
    & $Adb -s $Serial shell input keyevent 4 | Out-Null
    $ui = Start-Update
}
if ($ui -match 'text="Continue"') {
    Tap-Text $ui "Continue"
    Start-Sleep -Seconds 1
    $ui = Get-Ui "manager-self-update-confirm"
}
Tap-Text $ui "Update"
$deadline = [DateTime]::UtcNow.AddSeconds(20)
do {
    Start-Sleep -Seconds 1
    $installed = (& $Adb -s $Serial shell dumpsys package $Package) -join "`n"
} while (($installed -notmatch 'versionCode=10000' -or $installed -notmatch 'versionName=1\.0\.0') -and [DateTime]::UtcNow -lt $deadline)
if ($installed -notmatch 'versionCode=10000' -or $installed -notmatch 'versionName=1\.0\.0') {
    throw "Manager self-update did not install 1.0.0"
}
& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
$relaunchedUi = Get-Ui "manager-self-update-relaunched"
if ($relaunchedUi -notmatch 'text="1\.0\.0"') {
    throw "Relaunched manager does not show its installed 1.0.0 version"
}
if ($relaunchedUi -match 'content-desc="Archphene update .* available') {
    throw "Relaunched manager retained a stale self-update state"
}
Write-Host "Manager self-update passed: 0.9.0 -> 1.0.0 with reconciled restart state."