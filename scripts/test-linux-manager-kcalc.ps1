param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"
$KCalc = "org.archphene.linux.kcalc"

& $Adb -s $Serial shell am force-stop $Manager | Out-Null
& $Adb -s $Serial shell am force-stop $KCalc | Out-Null
& $Adb -s $Serial shell monkey -p $Manager -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2
& $Adb -s $Serial shell uiautomator dump /sdcard/archphene-manager-test.xml | Out-Null
$Ui = (& $Adb -s $Serial shell cat /sdcard/archphene-manager-test.xml) -join "`n"

foreach ($Expected in @("Archphene Linux Apps", "KCalc", "extra/kcalc 26.04.3-1", "glibc-x86_64")) {
    if (-not $Ui.Contains($Expected)) {
        throw "Manager catalog evidence missing: $Expected"
    }
}

$LaunchNode = [regex]::Match($Ui, 'text="LAUNCH"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $LaunchNode.Success) {
    throw "Could not find KCalc launch control bounds"
}
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
