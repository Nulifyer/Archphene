param(
    [string]$Serial = "emulator-5554",
    [string]$Query = "glmark2-es2-wayland",
    [string]$ExpectedPackage = "glmark2",
    [string]$ExpectedFile = "usr/bin/glmark2-es2-wayland"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"

function Get-Ui([string]$Name) {
    & $Adb -s $Serial shell uiautomator dump --compressed "/sdcard/$Name.xml" | Out-Null
    return (& $Adb -s $Serial shell cat "/sdcard/$Name.xml") -join "`n"
}

function Find-Node([string]$Ui, [string]$Pattern, [string]$Step) {
    $node = [regex]::Match($Ui, "$Pattern[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find $Step" }
    return $node
}

function Tap-Pattern([string]$Ui, [string]$Pattern, [string]$Step) {
    $node = Find-Node $Ui $Pattern $Step
    & $Adb -s $Serial shell input tap `
        ([int](([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2)) `
        ([int](([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2)) | Out-Null
}

function Wait-Ui([string]$Pattern, [string]$Name, [int]$Seconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 700
        $ui = Get-Ui $Name
    } while ($ui -notmatch $Pattern -and [DateTime]::UtcNow -lt $deadline)
    if ($ui -notmatch $Pattern) { throw "Timed out waiting for $Pattern" }
    return $ui
}

& $Adb -s $Serial shell am force-stop $Package
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null
$ui = Wait-Ui 'content-desc="Add Linux app"' "ranking-home"
Tap-Pattern $ui 'content-desc="Add Linux app"' "Add button"
$ui = Wait-Ui 'text="Search official Arch packages"' "ranking-add"
Tap-Pattern $ui 'text="Search official Arch packages"' "repository search field"
& $Adb -s $Serial shell input text $Query | Out-Null
$ui = Get-Ui "ranking-query"
Tap-Pattern $ui 'content-desc="Search package repositories"' "repository search button"
$escapedPackage = [regex]::Escape($ExpectedPackage)
$ui = Wait-Ui "text=`"$escapedPackage  [^`"]+`"" "ranking-results" 60
if ($ExpectedFile) {
    $escapedFile = [regex]::Escape("Matched file: /$ExpectedFile")
    if ($ui -notmatch "text=`"$escapedFile`"") {
        throw "Expected executable match $ExpectedFile was not shown"
    }
}
if ($ui.IndexOf("text=`"$ExpectedPackage  ", [StringComparison]::OrdinalIgnoreCase) -lt 0) {
    throw "$ExpectedPackage was not the first ranked result"
}
Tap-Pattern $ui 'text="Apps"' "Apps navigation"
$ui = Wait-Ui 'text="Search apps"' "ranking-apps"
Tap-Pattern $ui 'text="Search apps"' "installed app search"
& $Adb -s $Serial shell input text 'extra%smousepad' | Out-Null
$ui = Wait-Ui 'text="Mousepad"' "ranking-installed"
if ($ui -match 'text="KCalc"') { throw "Multi-term app search retained an unrelated app" }

Write-Host "Repository executable discovery and shared search ranking passed."
