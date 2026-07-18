param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"

function Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join "`n")" }
    return $output
}

function Get-Ui([string]$Name) {
    $remote = "/sdcard/$Name.xml"
    Adb @("shell", "uiautomator", "dump", "--compressed", $remote) | Out-Null
    return (Adb @("shell", "cat", $remote)) -join "`n"
}

function Tap-Text([string]$Ui, [string]$Text, [string]$Step) {
    $escaped = [regex]::Escape($Text)
    $node = [regex]::Match($Ui, "text=`"$escaped`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find $Step control '$Text'" }
    $x = ([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2
    $y = ([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2
    Adb @("shell", "input", "tap", [string][int]$x, [string][int]$y) | Out-Null
}

function Tap-Description([string]$Ui, [string]$Description, [string]$Step) {
    $escaped = [regex]::Escape($Description)
    $node = [regex]::Match($Ui, "content-desc=`"$escaped`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find $Step control '$Description'" }
    $x = ([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2
    $y = ([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2
    Adb @("shell", "input", "tap", [string][int]$x, [string][int]$y) | Out-Null
}

function Tap-DescriptionPattern([string]$Ui, [string]$Pattern, [string]$Step) {
    $node = [regex]::Match($Ui, "content-desc=`"$Pattern`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find $Step control matching '$Pattern'" }
    $x = ([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2
    $y = ([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2
    Adb @("shell", "input", "tap", [string][int]$x, [string][int]$y) | Out-Null
}

function Wait-Ui([string]$Pattern, [string]$Name, [int]$Seconds = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 700
        $ui = Get-Ui $Name
    } while ($ui -notmatch $Pattern -and [DateTime]::UtcNow -lt $deadline)
    if ($ui -notmatch $Pattern) { throw "Timed out waiting for $Pattern" }
    return $ui
}

$enabledBackground = $false
Adb @("shell", "pm", "grant", $Package, "android.permission.POST_NOTIFICATIONS") | Out-Null
try {
    Adb @("shell", "am", "force-stop", $Package) | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", "$Package/.MainActivity") | Out-Null
    $ui = Wait-Ui 'text="Apps"' "manager-obtainium-home"
    if ($ui -notmatch 'text="KCalc"') {
        Tap-Description $ui "Filter and sort apps" "reset initial filter"
        $ui = Wait-Ui 'text="Filter and sorting"' "manager-obtainium-reset-filter"
        Tap-Text $ui "All apps" "all apps filter"
        $ui = Wait-Ui 'text="APPLY"' "manager-obtainium-reset-apply"
        Tap-Text $ui "APPLY" "apply all apps filter"
        $ui = Wait-Ui 'text="KCalc"' "manager-obtainium-home-all"
    }
    foreach ($expected in @('text="Search apps"', 'text="KCalc"', 'text="Mousepad"', 'text="Apps"', 'text="Settings"')) {
        if ($ui -notmatch [regex]::Escape($expected)) { throw "Home UI is missing $expected" }
    }

    Tap-DescriptionPattern $ui 'KCalc [^"]*Check again' "KCalc update"
    $ui = Wait-Ui 'content-desc="KCalc 26\.04\.3-1 is up to date\. Check again"' "manager-obtainium-update" 20

    Adb @("shell", "am", "force-stop", $Package) | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", "$Package/.MainActivity") | Out-Null
    $ui = Wait-Ui 'content-desc="KCalc 26\.04\.3-1 is up to date\. Check again"' "manager-obtainium-persisted"

    Tap-Text $ui "KCalc" "KCalc details"
    $ui = Wait-Ui 'text="Package source"' "manager-obtainium-detail"
    foreach ($expected in @('text="Installed package"', 'text="Available"',
            'text="Install version"', 'text="Pin selected version"')) {
        if ($ui -notmatch [regex]::Escape($expected)) { throw "Detail UI is missing $expected" }
    }
    Adb @("shell", "input", "swipe", "540", "1800", "540", "700", "500") | Out-Null
    $ui = Wait-Ui 'text="Uninstall app"' "manager-obtainium-detail-actions"
    foreach ($expected in @('text="Android app settings"', 'text="Uninstall app"')) {
        if ($ui -notmatch [regex]::Escape($expected)) { throw "Detail actions are missing $expected" }
    }

    Tap-Text $ui "Settings" "settings navigation"
    $ui = Wait-Ui 'text="Background update checks"' "manager-obtainium-settings"
    $switch = [regex]::Match($ui, 'class="android\.widget\.Switch"[^>]*checked="(true|false)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $switch.Success) { throw "Could not find background update switch" }
    if ($switch.Groups[1].Value -eq "false") {
        $x = ([int]$switch.Groups[2].Value + [int]$switch.Groups[4].Value) / 2
        $y = ([int]$switch.Groups[3].Value + [int]$switch.Groups[5].Value) / 2
        Adb @("shell", "input", "tap", [string][int]$x, [string][int]$y) | Out-Null
        $enabledBackground = $true
        Start-Sleep -Seconds 1
    }
    $jobs = (Adb @("shell", "dumpsys", "jobscheduler")) -join "`n"
    if ($jobs -notmatch 'org\.archpheneos\.manager\.LinuxAppManagerService') {
        throw "Background update JobService was not scheduled"
    }

    $ui = Get-Ui "manager-obtainium-settings-ready"
    Tap-Text $ui "Apps" "apps navigation"
    $ui = Wait-Ui 'text="Search apps"' "manager-obtainium-search"
    Tap-Text $ui "Search apps" "search field"
    Adb @("shell", "input", "text", "mousepad") | Out-Null
    Start-Sleep -Seconds 1
    $ui = Get-Ui "manager-obtainium-filtered"
    if ($ui -notmatch 'text="Mousepad"' -or $ui -match 'text="KCalc"') {
        throw "Search did not isolate Mousepad"
    }

    Adb @("shell", "input", "keycombination", "113", "29") | Out-Null
    Adb @("shell", "input", "keyevent", "67") | Out-Null
    Start-Sleep -Milliseconds 500
    $ui = Get-Ui "manager-obtainium-search-cleared"
    Tap-Description $ui "Filter and sort apps" "filter and sorting"
    $ui = Wait-Ui 'text="Filter and sorting"' "manager-obtainium-filter-dialog"
    Tap-Text $ui "All apps" "filter choice"
    $ui = Wait-Ui 'text="Updates available"' "manager-obtainium-filter-options"
    Tap-Text $ui "Updates available" "updates filter"
    $ui = Wait-Ui 'text="APPLY"' "manager-obtainium-filter-apply"
    Tap-Text $ui "APPLY" "apply filter"
    $ui = Wait-Ui 'text="No updates available\."' "manager-obtainium-updates-only"

    Tap-Description $ui "Filter and sort apps" "restore filter"
    $ui = Wait-Ui 'text="Filter and sorting"' "manager-obtainium-restore-filter"
    Tap-Text $ui "All apps" "all apps filter"
    $ui = Wait-Ui 'text="APPLY"' "manager-obtainium-restore-apply"
    Tap-Text $ui "APPLY" "restore all apps filter"

    Write-Host "Linux manager Obtainium workflow passed: persistent updates, details, settings, scheduled checks, search, and update filtering."
} finally {
    if ($enabledBackground) {
        $ui = Get-Ui "manager-obtainium-cleanup"
        if ($ui -notmatch 'text="Background update checks"') {
            try { Tap-Text $ui "Settings" "settings cleanup"; $ui = Wait-Ui 'text="Background update checks"' "manager-obtainium-cleanup-settings" } catch {}
        }
        $switch = [regex]::Match($ui, 'class="android\.widget\.Switch"[^>]*checked="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($switch.Success) {
            $x = ([int]$switch.Groups[1].Value + [int]$switch.Groups[3].Value) / 2
            $y = ([int]$switch.Groups[2].Value + [int]$switch.Groups[4].Value) / 2
            Adb @("shell", "input", "tap", [string][int]$x, [string][int]$y) | Out-Null
        }
    }
}