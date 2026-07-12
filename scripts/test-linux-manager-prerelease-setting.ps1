param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"

function Get-Ui([string]$Name) {
    & $Adb -s $Serial shell uiautomator dump --compressed "/sdcard/$Name.xml" | Out-Null
    return (& $Adb -s $Serial shell cat "/sdcard/$Name.xml") -join "`n"
}
function Open-Settings {
    & $Adb -s $Serial shell am force-stop $Package | Out-Null
    & $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds 800
    & $Adb -s $Serial shell input tap 850 2270 | Out-Null
    Start-Sleep -Milliseconds 800
    return Get-Ui "manager-prerelease-settings"
}
function Get-PrereleaseSwitch([string]$Ui) {
    $label = $Ui.IndexOf('text="Allow pre-release versions"')
    if ($label -lt 0) { throw "Pre-release setting label is missing" }
    $tail = $Ui.Substring($label, [Math]::Min(2200, $Ui.Length - $label))
    $switch = [regex]::Match($tail,
        'class="android.widget.Switch"[^>]*checked="(true|false)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $switch.Success) { throw "Pre-release setting switch is missing" }
    return $switch
}

& $Adb -s $Serial shell pm clear $Package | Out-Null
$ui = Open-Settings
$switch = Get-PrereleaseSwitch $ui
if ($switch.Groups[1].Value -ne "false") { throw "Pre-release setting must default to disabled" }
& $Adb -s $Serial shell input tap `
    ([int](([int]$switch.Groups[2].Value + [int]$switch.Groups[4].Value) / 2)) `
    ([int](([int]$switch.Groups[3].Value + [int]$switch.Groups[5].Value) / 2)) | Out-Null
Start-Sleep -Milliseconds 500
$ui = Get-Ui "manager-prerelease-enabled"
if ((Get-PrereleaseSwitch $ui).Groups[1].Value -ne "true") {
    throw "Pre-release setting did not enable"
}
$preferences = (& $Adb -s $Serial shell run-as $Package cat shared_prefs/linux-app-manager-state.xml) -join "`n"
if ($preferences -notmatch 'name="allow-prereleases" value="true"') {
    throw "Pre-release setting was not persisted"
}
$ui = Open-Settings
if ((Get-PrereleaseSwitch $ui).Groups[1].Value -ne "true") {
    throw "Pre-release setting did not survive manager restart"
}
& $Adb -s $Serial shell pm clear $Package | Out-Null
Write-Host "Pre-release setting passed: disabled by default, enabled explicitly, and persisted across restart."