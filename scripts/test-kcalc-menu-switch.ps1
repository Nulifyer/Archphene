param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.kcalc"
$Report = "files/kcalc-report.txt"
$SafeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$Screenshot = Join-Path $Root "tooling/build/kcalc-menu-switch-$SafeSerial.png"

& $Adb -s $Serial shell am force-stop $Package
& $Adb -s $Serial shell pm clear $Package | Out-Null
& $Adb -s $Serial shell am start -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 10

# File, then Settings while the File popup owns the pointer grab. Resolve the
# Android content inset so these remain Wayland-surface-relative coordinates.
& $Adb -s $Serial shell uiautomator dump /sdcard/kcalc-menu-ui.xml | Out-Null
$Ui = (& $Adb -s $Serial shell cat /sdcard/kcalc-menu-ui.xml) -join "`n"
$ImageBounds = [regex]::Match($Ui, 'class="android.widget.ImageView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $ImageBounds.Success) { throw "Could not resolve KCalc viewport bounds" }
$MenuY = [int]$ImageBounds.Groups[2].Value + 30
& $Adb -s $Serial shell input tap 45 $MenuY
Start-Sleep -Seconds 2
& $Adb -s $Serial shell input tap 225 $MenuY
Start-Sleep -Seconds 6

& $Adb -s $Serial shell screencap -p /sdcard/kcalc-menu-switch.png
& $Adb -s $Serial pull /sdcard/kcalc-menu-switch.png $Screenshot | Out-Null
if (-not (Test-Path -LiteralPath $Screenshot) -or (Get-Item $Screenshot).Length -lt 50000) {
    throw "KCalc menu screenshot is missing or unexpectedly small"
}

$Processes = & $Adb -s $Serial shell ps -A
$ChildLine = $Processes | Select-String "libarchphene_ld.so" | Select-Object -Last 1
if (-not $ChildLine) {
    throw "The real KCalc process is not alive after switching menus"
}
$ChildPid = ($ChildLine.ToString().Trim() -split '\s+')[1]
& $Adb -s $Serial shell run-as $Package kill $ChildPid
Start-Sleep -Seconds 3
$Text = (& $Adb -s $Serial shell run-as $Package cat $Report) -join "`n"

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
