param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"

function Get-Ui([string]$Name) {
    & $Adb -s $Serial shell uiautomator dump --compressed "/sdcard/$Name.xml" | Out-Null
    return (& $Adb -s $Serial shell cat "/sdcard/$Name.xml") -join "`n"
}
function Tap([string]$Ui, [string]$Pattern, [string]$Step) {
    $node = [regex]::Match($Ui, "$Pattern[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find $Step" }
    & $Adb -s $Serial shell input tap `
        ([int](([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2)) `
        ([int](([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2)) | Out-Null
}
function Wait-Ui([string]$Pattern, [string]$Name, [int]$Seconds = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do { Start-Sleep -Milliseconds 700; $ui = Get-Ui $Name }
    while ($ui -notmatch $Pattern -and [DateTime]::UtcNow -le $deadline)
    if ($ui -notmatch $Pattern) { throw "Timed out waiting for $Pattern" }
    return $ui
}

& $Adb -s $Serial shell pm clear $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null
$ui = Wait-Ui 'text="KCalc"' "version-home"
Tap $ui 'text="KCalc"' "KCalc detail"
$ui = Wait-Ui 'content-desc="Version selector, 4 versions"' "version-detail" 20
Tap $ui 'text="Check for update"' "check for update"
$ui = Wait-Ui 'content-desc="Version selector, 4 versions"' "version-after-check" 20
Tap $ui 'content-desc="Version selector, 4 versions"' "version selector"
$ui = Wait-Ui 'text="26\.04\.0-1"' "version-options" 20
Tap $ui 'text="26\.04\.0-1"' "archived KCalc version"
$ui = Wait-Ui 'text="Archived version; compatibility not verified"' "version-selected"
Tap $ui 'text="Pin selected version"' "pin selected version"
Tap $ui 'text="Apps"' "back to apps"
$ui = Wait-Ui 'content-desc="Pinned to 26\.04\.0-1\. glibc-x86_64"' "version-pinned-list"
Tap $ui 'text="KCalc"' "pinned KCalc detail"
$ui = Wait-Ui 'content-desc="26\.04\.3-1, newer version available"' "newer-version-indicator"
& $Adb -s $Serial shell pm clear $Package | Out-Null
Write-Host "Version selector passed: pin icon shown and newer available version remains selectable."