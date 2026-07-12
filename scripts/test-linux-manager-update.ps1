param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"

& $Adb -s $Serial shell am force-stop $Manager | Out-Null
& $Adb -s $Serial shell am start -S -W -n org.archpheneos.manager/.MainActivity | Out-Null
Start-Sleep -Seconds 2
& $Adb -s $Serial shell uiautomator dump /sdcard/archphene-update-before.xml | Out-Null
$Ui = (& $Adb -s $Serial shell cat /sdcard/archphene-update-before.xml) -join "`n"
$Check = [regex]::Match($Ui, 'content-desc="(?:Check KCalc for updates\.[^"]*|KCalc [^"]+\. Check again)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $Check.Success) {
    throw "Could not find KCalc update-check control"
}
$X = ([int]$Check.Groups[1].Value + [int]$Check.Groups[3].Value) / 2
$Y = ([int]$Check.Groups[2].Value + [int]$Check.Groups[4].Value) / 2
& $Adb -s $Serial shell input tap ([int]$X) ([int]$Y) | Out-Null

$Deadline = [DateTime]::UtcNow.AddSeconds(15)
do {
    Start-Sleep -Seconds 1
    & $Adb -s $Serial shell uiautomator dump /sdcard/archphene-update-after.xml | Out-Null
    $Result = (& $Adb -s $Serial shell cat /sdcard/archphene-update-after.xml) -join "`n"
} while ($Result -notmatch 'content-desc="KCalc 26\.04\.3-1 is up to date\. Check again"' -and [DateTime]::UtcNow -lt $Deadline)

if ($Result -notmatch 'content-desc="KCalc 26\.04\.3-1 is up to date\. Check again"') {
    throw "Official Arch update comparison did not return the expected installed version"
}

Write-Host "Manager verified KCalc 26.04.3-1 is current via Arch's official package endpoint."
