param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.mousepad"

function Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join "`n")" }
    return $output
}

$size = (Adb @("shell", "wm", "size")) -join "`n"
if ($size -notmatch '1080x2400') {
    throw "This touch regression currently requires the 1080x2400 emulator; got $size"
}

Adb @("shell", "am", "force-stop", $Package) | Out-Null
Adb @("shell", "am", "start", "-n", "$Package/.MainActivity") | Out-Null
Start-Sleep -Seconds 6
Adb @("shell", "input", "keyevent", "4") | Out-Null
Start-Sleep -Milliseconds 600
Adb @("shell", "input", "keycombination", "113", "43") | Out-Null
Start-Sleep -Seconds 3
Adb @("shell", "input", "tap", "865", "197") | Out-Null
Start-Sleep -Seconds 2
Adb @("logcat", "-c") | Out-Null

# Gboard centers for a, r, c, h in the 1080x2400 emulator layout.
foreach ($point in @(@(110,1870), @(380,1710), @(430,2030), @(640,1870))) {
    Adb @("shell", "input", "tap", [string]$point[0], [string]$point[1]) | Out-Null
    Start-Sleep -Milliseconds 900
}
Start-Sleep -Seconds 2

$beforeTouch = (Adb @("logcat", "-d", "-v", "brief")) -join "`n"
if ($beforeTouch -notmatch 'Wayland text input retained for keyboard navigation') {
    throw "GTK result-focus transition did not exercise retained Android IME behavior"
}
if ($beforeTouch -match 'onRequestHide') {
    throw "Android IME hid while entering the complete arch query"
}
Adb @("shell", "screencap", "-p", "/sdcard/mousepad-search-arch-ime.png") | Out-Null
Adb @("pull", "/sdcard/mousepad-search-arch-ime.png",
        (Join-Path $Root "artifacts/mousepad-search-arch-ime.png")) | Out-Null

Adb @("logcat", "-c") | Out-Null
Adb @("shell", "input", "tap", "500", "350") | Out-Null
Start-Sleep -Seconds 2
$afterTouch = (Adb @("logcat", "-d", "-v", "brief")) -join "`n"
if (($afterTouch -notmatch 'Wayland text input applied enabled=false') -or
        ($afterTouch -notmatch 'onRequestHide')) {
    throw "Touching a search result did not release the retained Android IME"
}

Write-Host "Mousepad open-dialog IME passed: arch query retained Gboard; result touch dismissed it."