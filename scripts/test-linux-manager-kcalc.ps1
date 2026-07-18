param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"

function Read-Ui([string]$RemotePath) {
    & $Adb -s $Serial shell uiautomator dump --compressed $RemotePath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not dump Android UI: $RemotePath" }
    return ((& $Adb -s $Serial shell cat $RemotePath) -join "`n")
}

function Tap-Node([string]$Xml, [string]$Text) {
    $escaped = [regex]::Escape($Text)
    $node = [regex]::Match(
            $Xml, 'text="' + $escaped + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $node.Success) { throw "Could not find Android control: $Text" }
    $x = ([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2
    $y = ([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2
    & $Adb -s $Serial shell input tap ([int]$x) ([int]$y) | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not tap Android control: $Text" }
}

& $Adb -s $Serial shell am force-stop $Manager | Out-Null
& $Adb -s $Serial shell am start -S -W -n "$Manager/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
$ui = Read-Ui "/sdcard/archphene-manager-test.xml"
foreach ($expected in @("Apps", "KCalc", "extra/kcalc", "26.04.3-1", "glibc-x86_64")) {
    if (-not $ui.Contains($expected)) {
        throw "Manager catalog evidence missing: $expected"
    }
}

Tap-Node $ui "KCalc"
Start-Sleep -Seconds 1
$detail = Read-Ui "/sdcard/archphene-manager-kcalc-detail.xml"
$packageMatch = [regex]::Match(
        $detail, 'text="(org\.archphene\.linux\.p[0-9a-f]{32})"')
if (-not $packageMatch.Success) {
    throw "KCalc detail view did not expose its generated Android package"
}
$kcalc = $packageMatch.Groups[1].Value
& $Adb -s $Serial shell am force-stop $kcalc | Out-Null
Tap-Node $detail "Launch"

$deadline = [DateTime]::UtcNow.AddSeconds(15)
do {
    $activities = (& $Adb -s $Serial shell dumpsys activity activities) -join "`n"
    if ($activities -match ('topResumedActivity=.*' + [regex]::Escape($kcalc) +
            '/org\.archphene\.linux\.kcalc\.MainActivity')) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)
if ([DateTime]::UtcNow -ge $deadline) {
    throw "Generated KCalc package did not become the resumed Activity: $kcalc"
}

$loaderDeadline = [DateTime]::UtcNow.AddSeconds(15)
$appPid = ""
$loaderPid = $null
do {
    $appPid = ((& $Adb -s $Serial shell pidof $kcalc 2>$null) -join "").Trim()
    if ($appPid) {
        $processes = (& $Adb -s $Serial shell ps -A -o PID,PPID,NAME) -join "`n"
        $children = [regex]::Matches(
                $processes, "(?m)^\s*(\d+)\s+$appPid\s+\S+\s*$")
        foreach ($child in $children) {
            $candidate = $child.Groups[1].Value
            $executable = ((& $Adb -s $Serial shell run-as $kcalc readlink "/proc/$candidate/exe" 2>$null) -join "").Trim()
            if ($LASTEXITCODE -eq 0 -and $executable -match 'libarchphene_ld\.so$') {
                $loaderPid = $candidate
                break
            }
        }
    }
    if (-not $loaderPid) { Start-Sleep -Milliseconds 500 }
} while (-not $loaderPid -and [DateTime]::UtcNow -lt $loaderDeadline)if (-not $loaderPid) {
    throw "Manager launched $kcalc, but its managed Linux loader is missing"
}

Write-Host "Linux app manager discovered and launched canonical KCalc $kcalc (Android $appPid, Linux $loaderPid)."
