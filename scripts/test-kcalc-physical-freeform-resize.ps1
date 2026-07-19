param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$Package = "org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"

$Activity = ((& $Adb -s $Serial shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $Package) | Select-Object -Last 1).Trim()
if ($Activity -notmatch '/') { throw "Could not resolve launcher activity for $Package" }

function Get-Child([string]$AppPid) {
    $processes = (& $Adb -s $Serial shell ps -A -o PID,PPID,NAME) -join "`n"
    return [regex]::Match($processes,
            "(?m)^\s*(\d+)\s+$AppPid\s+(?:loader|libarchphene_ld\.so)\s*$").Groups[1].Value
}
function Get-ImageBounds([string]$Name) {
    $remote = "/sdcard/$Name.xml"
    & $Adb -s $Serial shell uiautomator dump $remote | Out-Null
    $xml = (& $Adb -s $Serial shell cat $remote) -join "`n"
    $match = [regex]::Match(
            $xml,
            'class="android\.widget\.ImageView"[^>]*bounds="(\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\])"')
    if (-not $match.Success) { throw "Could not read KCalc ImageView bounds" }
    return $match.Groups[1].Value
}

& $Adb -s $Serial shell pm clear $Package | Out-Null
& $Adb -s $Serial shell am start -W --windowingMode 5 -n $Activity | Out-Null
Start-Sleep -Seconds 8
$appPid = (& $Adb -s $Serial shell pidof $Package).Trim()
$child = Get-Child $appPid
$activities = (& $Adb -s $Serial shell dumpsys activity activities) -join "`n"
$taskPattern = 'Task\{[^\r\n]*#(\d+)[^\r\n]*' + [regex]::Escape($Package) + '[^\r\n]*mode=freeform'
$taskMatch = [regex]::Match($activities, $taskPattern)
$task = $taskMatch.Groups[1].Value
if (-not $appPid -or -not $child -or -not $task) {
    throw "Could not identify freeform KCalc app=$appPid child=$child task=$task"
}

try {
    & $Adb -s $Serial logcat -c | Out-Null
    & $Adb -s $Serial shell am task resize $task 80 180 920 1500 | Out-Null
    Start-Sleep -Seconds 4
    $smallChild = Get-Child $appPid
    $smallBounds = Get-ImageBounds "kcalc-freeform-small"
    & $Adb -s $Serial shell am task resize $task 0 0 1026 2200 | Out-Null
    Start-Sleep -Seconds 4
    $restoredChild = Get-Child $appPid
    $restoredBounds = Get-ImageBounds "kcalc-freeform-restored"
    if ($child -ne $smallChild -or $child -ne $restoredChild) {
        throw "Linux child restarted during freeform resize: $child -> $smallChild -> $restoredChild"
    }

    if ($smallBounds -eq $restoredBounds) {
        throw "Android freeform viewport did not change: $smallBounds"
    }
    if (-not ((& $Adb -s $Serial shell pidof $Package).Trim())) {
        throw "KCalc exited after freeform resize"
    }

    Write-Host "KCalc physical freeform resize passed on ${Serial}: PID $child, $smallBounds -> $restoredBounds."
} finally {
    & $Adb -s $Serial shell am force-stop $Package | Out-Null
}
