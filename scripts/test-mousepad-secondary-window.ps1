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

function Read-BridgeLog {
    return (Adb @("logcat", "-d", "-s", "ArchpheneInput:V", "*:S")) -join "`n"
}

function Wait-BridgeLog([string]$Pattern, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $log = Read-BridgeLog
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for bridge log: $Pattern"
}

function Read-ActiveImageBounds([string]$Name) {
    $remote = "/sdcard/$Name.xml"
    $local = Join-Path $Root "artifacts/$Name.xml"
    Adb @("shell", "uiautomator", "dump", $remote) | Out-Null
    Adb @("pull", $remote, $local) | Out-Null
    $xml = Get-Content -LiteralPath $local -Raw
    $matches = [regex]::Matches(
        $xml,
        'class="android\.widget\.ImageView"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"')
    if ($matches.Count -ne 1) {
        throw "Expected one active compositor ImageView, found $($matches.Count)"
    }
    $match = $matches[0]
    return @(
        [int]$match.Groups[1].Value,
        [int]$match.Groups[2].Value,
        [int]$match.Groups[3].Value,
        [int]$match.Groups[4].Value)
}

function Map-Point([int[]]$Bounds, [int]$FrameWidth, [int]$FrameHeight,
        [int]$LocalX, [int]$LocalY) {
    $width = $Bounds[2] - $Bounds[0]
    $height = $Bounds[3] - $Bounds[1]
    return @(
        ($Bounds[0] + [Math]::Round($LocalX * $width / $FrameWidth)),
        ($Bounds[1] + [Math]::Round($LocalY * $height / $FrameHeight)))
}

Adb @("shell", "pm", "clear", $Package) | Out-Null
Adb @("logcat", "-c") | Out-Null
Adb @("shell", "am", "start", "--windowingMode", "5", "-n", "$Package/.MainActivity") | Out-Null
$mainLog = Wait-BridgeLog 'window id=([0-9]+).*mapped=true.*active=true.*primary=true.*geometry=[^ ]+ ([0-9]+)x([0-9]+).*title=.*Mousepad' 30
$main = [regex]::Match(
    $mainLog,
    'window id=([0-9]+).*mapped=true.*active=true.*primary=true.*geometry=[^ ]+ ([0-9]+)x([0-9]+).*title=.*Mousepad')
$mainId = [int]$main.Groups[1].Value
$mainWidth = [int]$main.Groups[2].Value
$mainHeight = [int]$main.Groups[3].Value
$mainBounds = Read-ActiveImageBounds "mousepad-freeform-main"
$settledFrames = [regex]::Matches((Read-BridgeLog), 'output frame=([0-9]+)x([0-9]+)')
if ($settledFrames.Count -eq 0) {
    throw "Mousepad did not publish a settled freeform frame"
}
$settledFrame = $settledFrames[$settledFrames.Count - 1]
$mainWidth = [int]$settledFrame.Groups[1].Value
$mainHeight = [int]$settledFrame.Groups[2].Value
$edit = Map-Point $mainBounds $mainWidth $mainHeight 90 76
Adb @("shell", "input", "tap", [string]$edit[0], [string]$edit[1]) | Out-Null
Wait-BridgeLog 'popup registry=.*:\d+,\d+,\d+,\d+,[1-9]\d*,[1-9]\d*,1,0;' 10 | Out-Null
Adb @("shell", "input", "keyevent", "KEYCODE_MOVE_END") | Out-Null
Adb @("shell", "input", "keyevent", "KEYCODE_ENTER") | Out-Null

$childLog = Wait-BridgeLog 'window id=([0-9]+) parent=([0-9]+) mapped=true active=true primary=false geometry=[^ ]+ ([0-9]+)x([0-9]+) title=Mousepad Preferences' 15
$child = [regex]::Match(
    $childLog,
    'window id=([0-9]+) parent=([0-9]+) mapped=true active=true primary=false geometry=[^ ]+ ([0-9]+)x([0-9]+) title=Mousepad Preferences')
$childId = [int]$child.Groups[1].Value
if ([int]$child.Groups[2].Value -ne $mainId) {
    throw "Preferences window is not parented to the Mousepad toplevel"
}
$childWidth = [int]$child.Groups[3].Value
$childHeight = [int]$child.Groups[4].Value
$childBounds = Read-ActiveImageBounds "mousepad-freeform-preferences"

Adb @("logcat", "-c") | Out-Null
$toggle = Map-Point $childBounds $childWidth $childHeight 33 118
Adb @("shell", "input", "tap", [string]$toggle[0], [string]$toggle[1]) | Out-Null
$toggleLog = Wait-BridgeLog 'touch down.*result=1' 5
if ($toggleLog -notmatch "touch up.*result=1") {
    throw "Preferences control did not receive a complete Wayland touch"
}

Adb @("logcat", "-c") | Out-Null
$close = Map-Point $childBounds $childWidth $childHeight ($childWidth - 14) 13
Adb @("shell", "input", "tap", [string]$close[0], [string]$close[1]) | Out-Null
$closeLog = Wait-BridgeLog "window id=$mainId parent=0 mapped=true active=true primary=true" 10
if ($closeLog -match "window id=$childId .*mapped=true") {
    throw "Preferences child remained mapped after its close control"
}
$appPid = (Adb @("shell", "pidof", $Package)) -join ""
if (-not $appPid.Trim()) { throw "Mousepad parent exited when the child closed" }

Write-Host "Mousepad secondary-window bridge passed: freeform child $childId received input, closed, and parent $mainId remained active."