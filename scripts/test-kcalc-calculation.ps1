param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$SafeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$Before = Join-Path $Root "tooling/build/kcalc-calculation-before-$SafeSerial.png"
$After = Join-Path $Root "tooling/build/kcalc-calculation-after-$SafeSerial.png"

$resolved = & $Adb -s $Serial shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $Package
if ($LASTEXITCODE -ne 0) { throw "Could not query launcher activity for $Package" }
$Activity = ($resolved | Select-Object -Last 1).Trim()
if ($Activity -notmatch '/') { throw "Could not resolve launcher activity for $Package" }

& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -n $Activity | Out-Null
Start-Sleep -Seconds 8
& $Adb -s $Serial shell screencap -p /sdcard/kcalc-before.png
& $Adb -s $Serial pull /sdcard/kcalc-before.png $Before | Out-Null

foreach ($keyCode in @(8, 81, 9, 70)) {
    & $Adb -s $Serial shell input keyevent $keyCode | Out-Null
    Start-Sleep -Milliseconds 250
}
Start-Sleep -Seconds 2
& $Adb -s $Serial shell screencap -p /sdcard/kcalc-after.png
& $Adb -s $Serial pull /sdcard/kcalc-after.png $After | Out-Null

Add-Type -AssemblyName System.Drawing
$beforeImage = [Drawing.Bitmap]::FromFile($Before)
$afterImage = [Drawing.Bitmap]::FromFile($After)
try {
    if ($beforeImage.Width -ne $afterImage.Width -or $beforeImage.Height -ne $afterImage.Height) {
        throw "KCalc screenshots changed dimensions"
    }
    $changed = 0
    $top = [int]($beforeImage.Height * 0.08)
    $bottom = [int]($beforeImage.Height * 0.45)
    for ($y = $top; $y -lt $bottom; $y += 3) {
        for ($x = 0; $x -lt $beforeImage.Width; $x += 3) {
            if ($beforeImage.GetPixel($x, $y).ToArgb() -ne $afterImage.GetPixel($x, $y).ToArgb()) {
                $changed++
            }
        }
    }
} finally {
    $beforeImage.Dispose()
    $afterImage.Dispose()
}
if ($changed -lt 10) { throw "KCalc display did not visibly change after 1 + 2 = ($changed samples)" }
$appPid = ((& $Adb -s $Serial shell pidof $Package) -join "").Trim()
$processes = (& $Adb -s $Serial shell ps -A -o PID,PPID,NAME) -join "`n"
$children = [regex]::Matches($processes, "(?m)^\s*(\d+)\s+$appPid\s+\S+\s*$")
$payloadPid = ""
foreach ($child in $children) {
    $candidate = ((& $Adb -s $Serial shell run-as $Package readlink `
            "/proc/$($child.Groups[1].Value)/exe" 2>$null) -join "").Trim()
    if ($candidate -match 'libarchphene_ld\.so$') {
        $payloadPid = $child.Groups[1].Value
        break
    }
}
if (-not $payloadPid) {
    throw "KCalc patched-glibc child is not owned by Android process $appPid"
}
$log = (& $Adb -s $Serial logcat -d -s "ArchpheneInput:V" "*:S") -join [Environment]::NewLine
if ($log -match "protocol error|native dispatch failed") {
    throw "KCalc calculation triggered a shared compositor failure"
}

Write-Host "KCalc calculation path passed on ${Serial}: 1 + 2 = changed $changed display samples; child PID $payloadPid."
