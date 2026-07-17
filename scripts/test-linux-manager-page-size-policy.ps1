param(
    [string]$Page4KSerial = "emulator-5554",
    [string]$Page16KSerial = "emulator-5556",
    [string]$ManagerApk = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $ManagerApk) {
    $ManagerApk = Join-Path $Root "prototypes/linux-app-manager-stub/out/archpheneos-manager.apk"
}
$ManagerApk = (Resolve-Path $ManagerApk).Path
$Package = "org.archpheneos.manager"
$Issue = "Package installs unavailable: upstream Arch x86_64 runtime is 4 KB-only on this 16 KB Android device"

function Invoke-Adb([string]$Serial) {
    $adbArguments = @($args)
    $output = & adb -s $Serial @adbArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed on ${Serial}: $($adbArguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Get-Ui([string]$Serial, [string]$Name) {
    Invoke-Adb $Serial shell uiautomator dump --compressed "/sdcard/$Name.xml" | Out-Null
    return (Invoke-Adb $Serial shell cat "/sdcard/$Name.xml") -join ""
}

function Tap-Description([string]$Serial, [string]$Ui, [string]$Description) {
    $node = [regex]::Match($Ui,
        "content-desc=`"$([regex]::Escape($Description))`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find '$Description' on $Serial" }
    $x = ([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2
    $y = ([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2
    Invoke-Adb $Serial shell input tap $x $y | Out-Null
}

foreach ($case in @(
        @{ Serial = $Page4KSerial; PageSize = "4096" },
        @{ Serial = $Page16KSerial; PageSize = "16384" })) {
    $actual = ((Invoke-Adb $case.Serial shell getconf PAGE_SIZE) -join "").Trim()
    if ($actual -ne $case.PageSize) {
        throw "$($case.Serial) page size $actual does not match expected $($case.PageSize)"
    }
    Invoke-Adb $case.Serial install -r -d $ManagerApk | Out-Null
}

Invoke-Adb $Page16KSerial logcat -c | Out-Null
Invoke-Adb $Page16KSerial shell am force-stop $Package | Out-Null
Invoke-Adb $Page16KSerial shell am start -W -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
$page16Ui = Get-Ui $Page16KSerial "archphene-page16"
if ($page16Ui -notmatch [regex]::Escape($Issue)) {
    throw "16 KB manager did not explain the package-runtime restriction"
}
if ($page16Ui -match 'This app isn.t 16 KB compatible') {
    throw "Android displayed the 16 KB mismatch dialog"
}
$page16Log = (Invoke-Adb $Page16KSerial logcat -d) -join "`n"
if ($page16Log -match 'Showing PageSizeMismatchDialog') {
    throw "Android logged a 16 KB mismatch dialog"
}
Tap-Description $Page16KSerial $page16Ui "Add Linux app"
Start-Sleep -Milliseconds 500
$page16Gate = Get-Ui $Page16KSerial "archphene-page16-gate"
if ($page16Gate -match 'text="Add packages"' -or
        $page16Gate -notmatch [regex]::Escape($Issue)) {
    throw "16 KB Add action bypassed the package-runtime gate"
}

Invoke-Adb $Page4KSerial shell am force-stop $Package | Out-Null
Invoke-Adb $Page4KSerial shell am start -W -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
$page4Ui = Get-Ui $Page4KSerial "archphene-page4"
if ($page4Ui -match [regex]::Escape($Issue)) {
    throw "4 KB manager incorrectly disabled package transactions"
}
Tap-Description $Page4KSerial $page4Ui "Add Linux app"
Start-Sleep -Milliseconds 500
$page4Add = Get-Ui $Page4KSerial "archphene-page4-add"
if ($page4Add -notmatch 'text="Add packages"') {
    throw "4 KB Add action did not open package search"
}

Write-Host "Manager page-size policy passed: 4 KB enabled, 16 KB explicitly gated without Android warning."
