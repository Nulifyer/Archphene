param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.p241d399e14343c53b8b766e9126776aa"

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

function Dump-Ui([string]$Name) {
    $remote = "/sdcard/$Name.xml"
    $local = Join-Path $Root "tooling/build/$Name.xml"
    Adb @("shell", "uiautomator", "dump", $remote) | Out-Null
    Adb @("pull", $remote, $local) | Out-Null
    return [xml](Get-Content -LiteralPath $local -Raw)
}

function Wait-UiNode([scriptblock]$Predicate, [string]$Name, [int]$TimeoutSeconds = 10) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $ui = Dump-Ui "mousepad-$($Name -replace '[^a-zA-Z0-9-]', '-')"
        $node = $ui.SelectNodes("//node") | Where-Object $Predicate | Select-Object -First 1
        if ($null -ne $node) { return $node }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for UI node: $Name"
}

function Tap-UiNode($Node, [string]$Name) {
    if ($Node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        throw "Invalid UI bounds for ${Name}: $($Node.bounds)"
    }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Adb @("shell", "input", "tap", [string]$x, [string]$y) | Out-Null
}
function Input-Shown {
    return ((Adb @("shell", "dumpsys", "input_method")) -join "`n") -match 'mInputShown=true'
}

$recentPath = "/data/user/0/$Package/files/linux-home/.local/share/recently-used.xbel"
try {
    $recent = (Adb @("shell", "run-as", $Package, "cat",
            "files/linux-home/.local/share/recently-used.xbel")) -join "`n"
} catch {
    # Production wrappers are intentionally not debuggable. The emulator is
    # rooted by the document workflow, so inspect the fixture as root there.
    try {
        $recent = (Adb @("shell", "cat", $recentPath)) -join "`n"
    } catch {
        throw "Mousepad recent-file fixture is unavailable; run test-mousepad-android-document-workflow.ps1 first"
    }
}
if ($recent -notmatch '(?i)archphene') {
    throw "Mousepad recent-file fixture has no archphene document; run test-mousepad-android-document-workflow.ps1 first"
}

$size = (Adb @("shell", "wm", "size")) -join "`n"
if ($size -notmatch '1080x2400') {
    throw "This touch regression currently requires the 1080x2400 emulator; got $size"
}

Adb @("shell", "am", "force-stop", $Package) | Out-Null
Adb @("logcat", "-c") | Out-Null
Adb @("shell", "am", "start", "-n", "$Package/org.archphene.linux.kcalc.MainActivity") | Out-Null
Wait-Log 'mapped=true.*title=.*Mousepad' 30 | Out-Null
$activities = (Adb @("shell", "dumpsys", "activity", "activities")) -join "`n"
if ($activities -notmatch ('ResumedActivity:.*' + [regex]::Escape($Package))) {
    throw "Mousepad is not the resumed Activity"
}

if (Input-Shown) {
    Adb @("shell", "input", "keyevent", "4") | Out-Null
    Start-Sleep -Milliseconds 500
}
Adb @("shell", "input", "keycombination", "113", "43") | Out-Null
$openLog = Wait-Log 'mapped=true.*title=Open File' 15
$openLayouts = [regex]::Matches($openLog,
    'mapped=true.*content=(-?[0-9]+),(-?[0-9]+) ([0-9]+)x([0-9]+) canvas=([0-9]+)x([0-9]+) compositedFrame=(-?[0-9]+),(-?[0-9]+) ([0-9]+)x([0-9]+).*title=Open File')
if ($openLayouts.Count -eq 0) {
    throw "Open dialog did not publish composited frame geometry"
}
$openLayout = $openLayouts[$openLayouts.Count - 1]
$contentX = [int]$openLayout.Groups[1].Value
$contentY = [int]$openLayout.Groups[2].Value
$contentWidth = [int]$openLayout.Groups[3].Value
$contentHeight = [int]$openLayout.Groups[4].Value
$canvasWidth = [int]$openLayout.Groups[5].Value
$canvasHeight = [int]$openLayout.Groups[6].Value
$frameX = [int]$openLayout.Groups[7].Value
$frameY = [int]$openLayout.Groups[8].Value
$frameWidth = [int]$openLayout.Groups[9].Value
$frameHeight = [int]$openLayout.Groups[10].Value
if ($frameX -lt 0 -or $frameY -lt 0 -or
        $frameX + $frameWidth -gt $canvasWidth -or
        $frameY + $frameHeight -gt $canvasHeight) {
    throw "Open dialog frame is outside its Android viewport"
}
if ($contentX -lt 0 -or $contentY -lt 0 -or
        $contentX + $contentWidth -gt $canvasWidth -or
        $contentY + $contentHeight -gt $canvasHeight) {
    throw "Open dialog content is outside its Android viewport"
}
$search = Wait-UiNode {
    $_.GetAttribute("content-desc") -eq "Search" -and $_.clickable -eq "true"
} "open-dialog-search"
Tap-UiNode $search "open-dialog search"
$searchInput = Wait-UiNode {
    $_.text -eq "Search" -and $_.clickable -eq "true" -and
            $_.GetAttribute("content-desc") -ne "Search"
} "open-dialog-search-input"
Tap-UiNode $searchInput "open-dialog search input"
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
$hidePattern = [regex]::Escape($Package) + ':.*onRequestHide'
if ($beforeTouch -match $hidePattern) {
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
Wait-UiNode {
    $_.text -eq "Open File"
} "open-dialog-after-ime-back" | Out-Null

Adb @("logcat", "-c") | Out-Null
$result = Wait-UiNode {
    $_.text -match '(?i)^archphene' -and $_.clickable -eq "true" -and
            $_.class -ne "android.widget.EditText"
} "arch-search-result"
Tap-UiNode $result "arch search result"
Start-Sleep -Milliseconds 500
$afterTouch = (Adb @("logcat", "-d", "-v", "brief")) -join "`n"
if (($afterTouch -notmatch 'touch down.*result=1' -or
        $afterTouch -notmatch 'touch up.*result=1') -and
        ($afterTouch -notmatch 'pointer button pressed=true.*result=1' -or
        $afterTouch -notmatch 'pointer button pressed=false.*result=1')) {
    throw "Search result tap was not accepted as Wayland input"
}

Write-Host "Mousepad open-dialog IME passed: arch query retained Gboard; Back dismissed it; result tap was routed."