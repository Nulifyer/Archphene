param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"
$KCalc = "org.archphene.linux.kcalc"

& $Adb -s $Serial shell am force-stop $Manager | Out-Null
& $Adb -s $Serial shell am force-stop $KCalc | Out-Null
& $Adb -s $Serial shell am start -S -W -n org.archpheneos.manager/.MainActivity | Out-Null
Start-Sleep -Seconds 2
& $Adb -s $Serial shell uiautomator dump /sdcard/archphene-manager-test.xml | Out-Null
$Ui = (& $Adb -s $Serial shell cat /sdcard/archphene-manager-test.xml) -join "`n"

foreach ($Expected in @("Apps", "KCalc", "extra/kcalc", "26.04.3-1", "glibc-x86_64")) {
    if (-not $Ui.Contains($Expected)) {
        throw "Manager catalog evidence missing: $Expected"
    }
}

$KCalcNode = [regex]::Match($Ui, 'text="KCalc"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $KCalcNode.Success) { throw "Could not find KCalc catalog row" }
$X = ([int]$KCalcNode.Groups[1].Value + [int]$KCalcNode.Groups[3].Value) / 2
$Y = ([int]$KCalcNode.Groups[2].Value + [int]$KCalcNode.Groups[4].Value) / 2
& $Adb -s $Serial shell input tap ([int]$X) ([int]$Y) | Out-Null
Start-Sleep -Seconds 1
& $Adb -s $Serial shell uiautomator dump --compressed /sdcard/archphene-manager-kcalc-detail.xml | Out-Null
$Detail = (& $Adb -s $Serial shell cat /sdcard/archphene-manager-kcalc-detail.xml) -join "`n"
$LaunchNode = [regex]::Match($Detail, 'text="Launch"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $LaunchNode.Success) { throw "Could not find KCalc detail launch control" }
$X = ([int]$LaunchNode.Groups[1].Value + [int]$LaunchNode.Groups[3].Value) / 2
$Y = ([int]$LaunchNode.Groups[2].Value + [int]$LaunchNode.Groups[4].Value) / 2
& $Adb -s $Serial shell input tap ([int]$X) ([int]$Y) | Out-Null
Start-Sleep -Seconds 7

$Activities = (& $Adb -s $Serial shell dumpsys activity activities) -join "`n"
if ($Activities -notmatch 'topResumedActivity=.*org\.archphene\.linux\.kcalc/\.MainActivity') {
    throw "KCalc did not become the resumed Activity"
}
$Processes = (& $Adb -s $Serial shell ps -A -o PID,PPID,NAME,ARGS) -join "`n"
if ($Processes -notmatch 'libarchphene_kcalc\.so') {
    throw "Manager launched KCalc Activity, but the Linux KCalc child is missing"
}

Write-Host "Linux app manager discovered and launched the generated KCalc APK."
