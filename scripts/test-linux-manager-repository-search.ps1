param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"
function Get-Ui([string]$Name) {
    & $Adb -s $Serial shell uiautomator dump --compressed "/sdcard/$Name.xml" | Out-Null
    return (& $Adb -s $Serial shell cat "/sdcard/$Name.xml") -join "`n"
}
function Tap-Pattern([string]$Ui, [string]$Pattern, [string]$Step) {
    $node = [regex]::Match($Ui, "$Pattern[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find $Step" }
    & $Adb -s $Serial shell input tap `
        ([int](([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2)) `
        ([int](([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2)) | Out-Null
}
function Wait-Ui([string]$Pattern, [string]$Name, [int]$Seconds = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do { Start-Sleep -Milliseconds 700; $ui = Get-Ui $Name }
    while ($ui -notmatch $Pattern -and [DateTime]::UtcNow -lt $deadline)
    if ($ui -notmatch $Pattern) { throw "Timed out waiting for $Pattern" }
    return $ui
}
function Show-AllApps {
    $ui = Wait-Ui 'content-desc="Filter and sort apps"' "repo-filter-home"
    Tap-Pattern $ui 'content-desc="Filter and sort apps"' "filter and sort"
    $ui = Wait-Ui 'text="Filter and sorting"' "repo-filter-dialog"
    Tap-Pattern $ui 'text="(?:All apps|Updates available|Pinned versions)"' "current app filter"
    $ui = Wait-Ui 'text="All apps"' "repo-filter-options"
    Tap-Pattern $ui 'text="All apps"' "all apps filter"
    $ui = Wait-Ui 'text="APPLY"' "repo-filter-apply"
    Tap-Pattern $ui 'text="APPLY"' "apply all apps filter"
    return Wait-Ui 'content-desc="Add Linux app"' "repo-all-apps"
}

& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null
$ui = Show-AllApps
if ($ui -match 'text="btop"' -and $ui -match 'text="Not installed"') {
    Tap-Pattern $ui 'text="btop"' "stale tracked btop"
    $ui = Wait-Ui 'text="Remove from apps"' "repo-stale-btop"
    Tap-Pattern $ui 'text="Remove from apps"' "remove stale btop"
    $ui = Wait-Ui 'content-desc="Add Linux app"' "repo-clean-home"
}
Tap-Pattern $ui 'content-desc="Add Linux app"' "Add button"
$ui = Wait-Ui 'text="Search official Arch packages"' "repo-add"
Tap-Pattern $ui 'text="Search official Arch packages"' "repository search field"
& $Adb -s $Serial shell input text btop | Out-Null
Tap-Pattern $ui 'content-desc="Search package repositories"' "repository search button"
$ui = Wait-Ui 'text="btop  [^"]+"' "repo-results" 20
Tap-Pattern $ui 'text="btop  [^"]+"' "btop result"
$ui = Wait-Ui 'text="Add to apps"' "repo-detail"
foreach ($expected in @('text="Repository"', 'text="Architecture"', 'text="Wrapper: built and signed on this device"')) {
    if ($ui -notmatch $expected) { throw "Package detail missing $expected" }
}
Tap-Pattern $ui 'text="Add to apps"' "track btop"
$ui = Wait-Ui 'text="Not installed"' "repo-tracked"
if ($ui -notmatch 'text="btop"') { throw "Tracked btop row is missing" }
Tap-Pattern $ui 'text="btop"' "tracked btop"
$ui = Wait-Ui 'text="Remove from apps"' "repo-remove"
Tap-Pattern $ui 'text="Remove from apps"' "remove btop"
Write-Host "Official Arch package search and tracked-package workflow passed."