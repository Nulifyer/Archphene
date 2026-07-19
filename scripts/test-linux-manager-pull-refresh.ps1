param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"

function Get-Ui([string]$Name) {
    & $Adb -s $Serial shell uiautomator dump --compressed "/sdcard/$Name.xml" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not capture $Name UI" }
    return (& $Adb -s $Serial shell cat "/sdcard/$Name.xml") -join "`n"
}

& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
$before = Get-Ui "manager-pull-before"
if ($before -match 'text="Check all"|text="Check package updates"') {
    throw "Persistent check-all controls remain on the apps page"
}
$boundsPattern = 'text="KCalc"[^>]*bounds="([^"]+)"'
$beforeBounds = [regex]::Match($before, $boundsPattern).Groups[1].Value
if (-not $beforeBounds) { throw "Could not locate KCalc before refresh" }

& $Adb -s $Serial shell input swipe 540 900 540 1020 250 | Out-Null
Start-Sleep -Seconds 1
$shortPull = Get-Ui "manager-pull-short"
if ($shortPull -match 'Checking KCalc for updates' -or [regex]::Match($shortPull, $boundsPattern).Groups[1].Value -ne $beforeBounds) {
    throw "A below-threshold pull triggered refresh or displaced the list"
}

& $Adb -s $Serial shell input swipe 540 900 540 1900 700 | Out-Null
$deadline = [DateTime]::UtcNow.AddSeconds(20)
do {
    Start-Sleep -Seconds 1
    $after = Get-Ui "manager-pull-after"
} while (($after -notmatch 'KCalc 26\.04\.3-1 is up to date' -or $after -notmatch 'Mousepad 0\.7\.0-1 is up to date') -and [DateTime]::UtcNow -lt $deadline)

if ($after -notmatch 'KCalc 26\.04\.3-1 is up to date' -or $after -notmatch 'Mousepad 0\.7\.0-1 is up to date') {
    throw "Pull-to-refresh did not check every installed Linux app"
}
$afterBounds = [regex]::Match($after, $boundsPattern).Groups[1].Value
if ($afterBounds -ne $beforeBounds) {
    throw "Apps list did not settle after refresh: $beforeBounds -> $afterBounds"
}

Write-Host "Linux manager pull-to-refresh passed: batch update completed and list position settled."