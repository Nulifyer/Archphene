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

function Wait-Log([string]$Pattern, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $log = (Adb @("logcat", "-d", "-s", "ArchpheneInput:I", "*:S")) -join "`n"
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for compositor log: $Pattern"
}

function Input-Shown {
    return ((Adb @("shell", "dumpsys", "input_method")) -join "`n") -match 'mInputShown=true'
}

$size = (Adb @("shell", "wm", "size")) -join "`n"
if ($size -notmatch '1080x2400') {
    throw "This touch regression currently requires the 1080x2400 emulator; got $size"
}

Adb @("shell", "am", "force-stop", $Package) | Out-Null
Adb @("logcat", "-c") | Out-Null
Adb @("shell", "am", "start", "-n", "$Package/.MainActivity") | Out-Null
Wait-Log 'mapped=true.*title=.*Mousepad' 30 | Out-Null
$activities = (Adb @("shell", "dumpsys", "activity", "activities")) -join "`n"
if ($activities -notmatch 'ResumedActivity:.*org\.archphene\.linux\.mousepad') {
    throw "Mousepad is not the resumed Activity"
}

if (Input-Shown) {
    Adb @("shell", "input", "keyevent", "4") | Out-Null
    Start-Sleep -Milliseconds 500
}
Adb @("shell", "input", "keycombination", "113", "43") | Out-Null
Wait-Log 'mapped=true.*title=Open File' 15 | Out-Null
Adb @("shell", "input", "tap", "865", "197") | Out-Null
$deadline = [DateTime]::UtcNow.AddSeconds(5)
while (-not (Input-Shown) -and [DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Milliseconds 250
}
if (-not (Input-Shown)) { throw "Open dialog search did not show Android IME" }
Adb @("logcat", "-c") | Out-Null

# Gboard centers for a, r, c, h in the 1080x2400 emulator layout.
foreach ($point in @(@(110,1870), @(380,1710), @(430,2030), @(640,1870))) {
    Adb @("shell", "input", "tap", [string]$point[0], [string]$point[1]) | Out-Null
    Start-Sleep -Milliseconds 900
    if (-not (Input-Shown)) {
        throw "Android IME hid while entering the arch query"
    }
}
Start-Sleep -Seconds 1

$beforeTouch = (Adb @("logcat", "-d", "-v", "brief")) -join "`n"
$fallbackEvents = 0
foreach ($match in [regex]::Matches($beforeTouch, 'Android IME keyboard fallback events=([0-9]+)')) {
    $fallbackEvents += [int]$match.Groups[1].Value
}
# Gboard may route initial composing text through zwp_text_input_v3, then use the
# keyboard fallback only after GTK changes focus. Require one full key pair.
if ($fallbackEvents -lt 2) {
    throw "No complete keyboard fallback event was forwarded after GTK changed text-input focus"
}
if ($beforeTouch -match 'org\.archphene\.linux\.mousepad:.*onRequestHide') {
    throw "Android requested IME hide while entering the complete arch query"
}
$artifact = Join-Path $Root "artifacts/mousepad-search-arch-ime.png"
Adb @("shell", "screencap", "-p", "/sdcard/mousepad-search-arch-ime.png") | Out-Null
Adb @("pull", "/sdcard/mousepad-search-arch-ime.png", $artifact) | Out-Null

Adb @("shell", "input", "keyevent", "4") | Out-Null
$deadline = [DateTime]::UtcNow.AddSeconds(5)
while ((Input-Shown) -and [DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Milliseconds 250
}
if (Input-Shown) { throw "Android Back did not dismiss the search keyboard" }

Adb @("logcat", "-c") | Out-Null
Adb @("shell", "input", "tap", "500", "350") | Out-Null
Start-Sleep -Milliseconds 500
$afterTouch = (Adb @("logcat", "-d", "-v", "brief")) -join "`n"
if ($afterTouch -notmatch 'touch down.*result=1') {
    throw "Search result touch was not accepted by the Wayland client"
}

Write-Host "Mousepad open-dialog IME passed: arch query retained Gboard; Back dismissed it; result touch was routed."