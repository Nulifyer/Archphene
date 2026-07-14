param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.kcalc"
$SafeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$Build = Join-Path $Root "tooling/build"

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

function Get-ProcessState {
    $appPid = (& $Adb -s $Serial shell pidof $Package).Trim()
    if (-not $appPid) { throw "KCalc Android process is not running" }
    $processes = (& $Adb -s $Serial shell ps -A -o PID,PPID,NAME) -join [Environment]::NewLine
    $match = [regex]::Match($processes, "(?m)^\s*(\d+)\s+$appPid\s+libarchphene_ld\.so\s*$")
    if (-not $match.Success) { throw "KCalc Linux child is not owned by Android PID $appPid" }
    [pscustomobject]@{ App = $appPid; Child = $match.Groups[1].Value }
}

function Capture-State([string]$Name) {
    $remoteXml = "/sdcard/kcalc-rotation-$Name.xml"
    $remotePng = "/sdcard/kcalc-rotation-$Name.png"
    $xmlPath = Join-Path $Build "kcalc-rotation-$Name-$SafeSerial.xml"
    $pngPath = Join-Path $Build "kcalc-rotation-$Name-$SafeSerial.png"
    Invoke-Adb @("shell", "uiautomator", "dump", $remoteXml) "dump $Name UI"
    Invoke-Adb @("pull", $remoteXml, $xmlPath) "pull $Name UI"
    Invoke-Adb @("shell", "screencap", "-p", $remotePng) "capture $Name screenshot"
    Invoke-Adb @("pull", $remotePng, $pngPath) "pull $Name screenshot"
    [xml]$ui = Get-Content $xmlPath -Raw
    $imageNode = $ui.SelectSingleNode("//node[@class='android.widget.ImageView']")
    if ($null -eq $imageNode -or $imageNode.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Could not resolve KCalc viewport in $Name"
    }
    $bounds = $Matches
    Add-Type -AssemblyName System.Drawing
    $bitmap = [Drawing.Bitmap]::FromFile($pngPath)
    try {
        $sampledColors = New-Object 'System.Collections.Generic.HashSet[int]'
        for ($y = 0; $y -lt $bitmap.Height; $y += 12) {
            for ($x = 0; $x -lt $bitmap.Width; $x += 12) {
                [void]$sampledColors.Add($bitmap.GetPixel($x, $y).ToArgb())
            }
        }
        [pscustomobject]@{
            Xml = $xmlPath
            Png = $pngPath
            Width = $bitmap.Width
            Height = $bitmap.Height
            ViewWidth = [int]$bounds[3] - [int]$bounds[1]
            ViewHeight = [int]$bounds[4] - [int]$bounds[2]
            ViewTop = [int]$bounds[2]
            Colors = $sampledColors.Count
        }
    } finally {
        $bitmap.Dispose()
    }
}

function Count-ChangedSamples([string]$Before, [string]$After) {
    Add-Type -AssemblyName System.Drawing
    $left = [Drawing.Bitmap]::FromFile($Before)
    $right = [Drawing.Bitmap]::FromFile($After)
    try {
        if ($left.Width -ne $right.Width -or $left.Height -ne $right.Height) {
            throw "Landscape screenshots changed dimensions"
        }
        $changed = 0
        $top = [int]($left.Height * 0.10)
        $bottom = [int]($left.Height * 0.45)
        for ($y = $top; $y -lt $bottom; $y += 4) {
            for ($x = 0; $x -lt $left.Width; $x += 4) {
                if ($left.GetPixel($x, $y).ToArgb() -ne $right.GetPixel($x, $y).ToArgb()) {
                    $changed++
                }
            }
        }
        $changed
    } finally {
        $left.Dispose()
        $right.Dispose()
    }
}

$state = (& $Adb -s $Serial get-state 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne "device") { throw "$Serial is not an authorized ADB device" }
$oldAccelerometer = (& $Adb -s $Serial shell settings get system accelerometer_rotation).Trim()
$oldRotation = (& $Adb -s $Serial shell settings get system user_rotation).Trim()

try {
    Invoke-Adb @("shell", "input", "keyevent", "WAKEUP") "wake device"
    Invoke-Adb @("shell", "wm", "dismiss-keyguard") "request keyguard dismissal"
    Invoke-Adb @("shell", "settings", "put", "system", "accelerometer_rotation", "0") "disable sensor rotation"
    Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "0") "force portrait"
    Invoke-Adb @("shell", "am", "force-stop", $Package) "force-stop KCalc"
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/.MainActivity") "cold-launch portrait KCalc"
    Start-Sleep -Seconds 8

    $portraitProcess = Get-ProcessState
    $portrait = Capture-State "portrait-before"
    if ($portrait.Width -ge $portrait.Height -or $portrait.ViewWidth -ge $portrait.ViewHeight) {
        throw "Portrait geometry is invalid: screenshot $($portrait.Width)x$($portrait.Height), viewport $($portrait.ViewWidth)x$($portrait.ViewHeight)"
    }

    Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "1") "rotate landscape"
    Start-Sleep -Seconds 7
    $landscapeProcess = Get-ProcessState
    $landscapeBefore = Capture-State "landscape-before"
    if ($landscapeBefore.Width -le $landscapeBefore.Height -or $landscapeBefore.ViewWidth -le $landscapeBefore.ViewHeight) {
        throw "Landscape geometry is invalid: screenshot $($landscapeBefore.Width)x$($landscapeBefore.Height), viewport $($landscapeBefore.ViewWidth)x$($landscapeBefore.ViewHeight)"
    }
    if ($landscapeBefore.Colors -lt 40) { throw "Landscape frame is blank or visually incomplete" }

    foreach ($keyCode in @(8, 81, 9, 70)) {
        Invoke-Adb @("shell", "input", "keyevent", "$keyCode") "send landscape calculator key"
        Start-Sleep -Milliseconds 250
    }
    Start-Sleep -Seconds 2
    $landscapeAfter = Capture-State "landscape-after"
    $changed = Count-ChangedSamples $landscapeBefore.Png $landscapeAfter.Png
    if ($changed -lt 15) { throw "Landscape KCalc input did not visibly update the display ($changed samples)" }

    Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "0") "restore portrait"
    Start-Sleep -Seconds 7
    $restoredProcess = Get-ProcessState
    $restored = Capture-State "portrait-restored"
    if ($portraitProcess.App -ne $landscapeProcess.App -or $portraitProcess.App -ne $restoredProcess.App -or
            $portraitProcess.Child -ne $landscapeProcess.Child -or $portraitProcess.Child -ne $restoredProcess.Child) {
        throw "Rotation restarted KCalc: Android $($portraitProcess.App)/$($landscapeProcess.App)/$($restoredProcess.App), Linux $($portraitProcess.Child)/$($landscapeProcess.Child)/$($restoredProcess.Child)"
    }

    $log = (& $Adb -s $Serial logcat -d -s "ArchpheneInput:V" "*:S") -join [Environment]::NewLine
    if ($log -match "protocol error|InvalidGrab|UnconfiguredBuffer|native dispatch failed") {
        throw "Rotation produced a shared compositor protocol failure"
    }
    Write-Host "KCalc live rotation passed on $($Serial): Android PID $($portraitProcess.App), Linux PID $($portraitProcess.Child), landscape input changed $changed samples."
} finally {
    & $Adb -s $Serial shell settings put system user_rotation $oldRotation | Out-Null
    & $Adb -s $Serial shell settings put system accelerometer_rotation $oldAccelerometer | Out-Null
    & $Adb -s $Serial shell am force-stop $Package | Out-Null
    & $Adb -s $Serial shell am start -n "$Package/.MainActivity" | Out-Null
}
