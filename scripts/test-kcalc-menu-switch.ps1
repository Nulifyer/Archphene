param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$SafeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$Screenshot = Join-Path $Root "artifacts/kcalc-menu-switch-$SafeSerial.png"

function Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Resolve-LauncherActivity {
    $resolved = Adb @("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", $Package)
    $activity = ($resolved | Select-Object -Last 1).Trim()
    if ($activity -notmatch '/') { throw "Could not resolve launcher activity for $Package" }
    return $activity
}

function Wait-Log([string]$Pattern, [int]$Seconds = 20) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $log = (Adb @("logcat", "-d", "-s", "ArchpheneInput:V", "*:S")) -join [Environment]::NewLine
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for compositor log: $Pattern"
}

function Map-RootPoint(
        [int]$LocalX, [int]$LocalY,
        [int]$ViewLeft, [int]$ViewTop, [int]$ViewWidth, [int]$ViewHeight,
        [int]$RootWidth, [int]$RootHeight) {
    $fallbackWidth = [Math]::Max($RootWidth, [int]($ViewWidth / 2))
    $fallbackHeight = [int](($ViewHeight * [long]$fallbackWidth) / $ViewWidth)
    $compact = ($RootWidth -le $fallbackWidth -and $RootHeight * 4L -le $fallbackHeight * 3L)
    if ($compact) {
        $outputWidth = $fallbackWidth
        $outputHeight = $fallbackHeight
        $rootX = [int](($fallbackWidth - $RootWidth) / 2)
        $rootY = [int](($fallbackHeight - $RootHeight) / 2)
    } else {
        $outputWidth = $RootWidth
        $outputHeight = $RootHeight
        $rootX = 0
        $rootY = 0
    }
    return @(
        [int]($ViewLeft + (($rootX + $LocalX) * [long]$ViewWidth) / $outputWidth),
        [int]($ViewTop + (($rootY + $LocalY) * [long]$ViewHeight) / $outputHeight))
}

Adb @("shell", "am", "force-stop", $Package) | Out-Null
Adb @("logcat", "-c") | Out-Null
$activity = Resolve-LauncherActivity
Adb @("shell", "am", "start", "-n", $activity) | Out-Null
$mapped = Wait-Log 'mapped=true.*geometry=0,0 (\d+)x(\d+).*title=KCalc' 30
$match = [regex]::Match($mapped, 'mapped=true.*geometry=0,0 (\d+)x(\d+).*title=KCalc')
$rootWidth = [int]$match.Groups[1].Value
$rootHeight = [int]$match.Groups[2].Value

Adb @("shell", "uiautomator", "dump", "/sdcard/kcalc-menu-ui.xml") | Out-Null
$ui = (Adb @("shell", "cat", "/sdcard/kcalc-menu-ui.xml")) -join [Environment]::NewLine
$bounds = [regex]::Match(
        $ui,
        'class="android.widget.ImageView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $bounds.Success) { throw "Could not resolve KCalc viewport bounds" }
$left = [int]$bounds.Groups[1].Value
$top = [int]$bounds.Groups[2].Value
$width = [int]$bounds.Groups[3].Value - $left
$height = [int]$bounds.Groups[4].Value - $top

$file = Map-RootPoint 25 10 $left $top $width $height $rootWidth $rootHeight
Adb @("shell", "input", "tap", [string]$file[0], [string]$file[1]) | Out-Null
Wait-Log 'popup registry=0:[^;]+,(\d+),(\d+),1,0;' 10 | Out-Null

$settings = Map-RootPoint 250 10 $left $top $width $height $rootWidth $rootHeight
Adb @("shell", "input", "tap", [string]$settings[0], [string]$settings[1]) | Out-Null
Start-Sleep -Seconds 2
$finalLog = (Adb @("logcat", "-d", "-s", "ArchpheneInput:V", "*:S")) -join [Environment]::NewLine
$completeFrames = [regex]::Matches(
        $finalLog, 'popup registry=0:(\d+),(\d+),(\d+),(\d+),\3,\4,1,0;')
if ($completeFrames.Count -lt 2) {
    throw "File-to-Settings switching did not produce two complete grabbed popup frames"
}
if ($finalLog -match 'protocol error|InvalidGrab|UnconfiguredBuffer') {
    throw "KCalc popup switching triggered a Wayland protocol error"
}

New-Item -ItemType Directory -Force -Path (Split-Path $Screenshot) | Out-Null
Adb @("shell", "screencap", "-p", "/sdcard/kcalc-menu-switch.png") | Out-Null
Adb @("pull", "/sdcard/kcalc-menu-switch.png", $Screenshot) | Out-Null
if ((Get-Item -LiteralPath $Screenshot).Length -lt 20000) {
    throw "KCalc menu screenshot is unexpectedly small"
}
$appPid = ((Adb @("shell", "pidof", $Package)) | Select-Object -Last 1).Trim()
$processes = (Adb @("shell", "ps", "-A", "-o", "PID,PPID,NAME")) -join [Environment]::NewLine
$child = [regex]::Match($processes, "(?m)^\s*(\d+)\s+$appPid\s+loader\s*$")
if (-not $appPid -or -not $child.Success) {
    throw "KCalc or its Linux child exited during popup switching"
}
$childExe = ((Adb @("shell", "readlink", "/proc/$($child.Groups[1].Value)/exe")) | Select-Object -Last 1).Trim()
if ($childExe -notmatch 'libarchphene_ld\.so$') {
    throw "KCalc child does not use the Archphene loader: $childExe"
}

Write-Host "KCalc shared-compositor menu switching passed: File -> Settings."
Write-Host "Screenshot: $Screenshot"
