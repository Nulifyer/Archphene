$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.kcalc"
$Report = "files/kcalc-report.txt"
$Screenshot = Join-Path $Root "tooling/build/kcalc-menu-switch.png"

& $Adb shell am force-stop $Package
& $Adb shell pm clear $Package | Out-Null
& $Adb shell am start -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 10

# File, then Settings while the File popup owns the pointer grab.
& $Adb shell input tap 45 136
Start-Sleep -Seconds 2
& $Adb shell input tap 225 136
Start-Sleep -Seconds 6

& $Adb shell screencap -p /sdcard/kcalc-menu-switch.png
& $Adb pull /sdcard/kcalc-menu-switch.png $Screenshot | Out-Null
if (-not (Test-Path -LiteralPath $Screenshot) -or (Get-Item $Screenshot).Length -lt 50000) {
    throw "KCalc menu screenshot is missing or unexpectedly small"
}

$Processes = & $Adb shell ps -A
$ChildLine = $Processes | Select-String "libarchphene_ld.so" | Select-Object -Last 1
if (-not $ChildLine) {
    throw "The real KCalc process is not alive after switching menus"
}
$ChildPid = ($ChildLine.ToString().Trim() -split '\s+')[1]
& $Adb shell run-as $Package kill $ChildPid
Start-Sleep -Seconds 3
$Text = (& $Adb shell run-as $Package cat $Report) -join "`n"

if (($Text | Select-String -Pattern "xdg_surface.get_popup" -AllMatches).Matches.Count -lt 2) {
    throw "File-to-Settings switching did not create both native popup roles"
}
if ($Text -notmatch "xdg_popup.grab .*valid=true") {
    throw "KCalc popup did not receive a valid Wayland pointer grab"
}
if (($Text | Select-String -Pattern "promoted-to-primary" -AllMatches).Matches.Count -ne 1) {
    throw "A popup surface was incorrectly promoted to the primary app window"
}
if ($Text -notmatch "Android Wayland API interactive pointer bitmap ready: true") {
    throw "KCalc did not retain a rendered frame"
}

Write-Output "KCalc native menu switching passed"
Write-Output "Screenshot: $Screenshot"
