param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.kcalc"
$SafeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$Before = Join-Path $Root "tooling/build/kcalc-calculation-before-$SafeSerial.png"
$After = Join-Path $Root "tooling/build/kcalc-calculation-after-$SafeSerial.png"

& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -n "$Package/.MainActivity" | Out-Null
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
if ($changed -lt 20) { throw "KCalc display did not visibly change after 1 + 2 = ($changed samples)" }

$appPid = (& $Adb -s $Serial shell pidof $Package).Trim()
$processes = (& $Adb -s $Serial shell ps -A -o PID,PPID,NAME) -join "`n"
$child = [regex]::Match($processes, "(?m)^\s*(\d+)\s+$appPid\s+libarchphene_ld\.so\s*$")
if (-not $child.Success) { throw "KCalc GNU/Linux child is not owned by Android process $appPid" }
& $Adb -s $Serial shell run-as $Package kill $child.Groups[1].Value | Out-Null
Start-Sleep -Seconds 2
$report = (& $Adb -s $Serial shell run-as $Package cat files/kcalc-report.txt) -join "`n"
if (([regex]::Matches($report, "wl_keyboard\.key key=")).Count -lt 4) {
    throw "Wayland report does not contain all calculator key events"
}
if ($report -notmatch "Android Wayland API interactive pointer bitmap ready: true") {
    throw "KCalc did not retain a rendered result frame"
}

Write-Host "KCalc calculation path passed on ${Serial}: 1 + 2 = changed $changed display samples; child PID $($child.Groups[1].Value)."
