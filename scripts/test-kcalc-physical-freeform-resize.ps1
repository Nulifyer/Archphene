param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.kcalc"

$features = (& $Adb -s $Serial shell pm list features) -join "`n"
if ($features -notmatch 'android\.software\.freeform_window_management') {
    throw "$Serial does not advertise Android freeform window management"
}

function Get-Child([string]$AppPid) {
    $processes = (& $Adb -s $Serial shell ps -A -o PID,PPID,NAME) -join "`n"
    return [regex]::Match($processes,
            "(?m)^\s*(\d+)\s+$AppPid\s+libarchphene_ld\.so\s*$").Groups[1].Value
}

& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W --windowingMode 5 -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 8
$appPid = (& $Adb -s $Serial shell pidof $Package).Trim()
$child = Get-Child $appPid
$activities = (& $Adb -s $Serial shell dumpsys activity activities) -join "`n"
$task = [regex]::Match($activities,
        'Task\{[^\r\n]*#(\d+)[^\r\n]*org\.archphene\.linux\.kcalc').Groups[1].Value
if (-not $appPid -or -not $child -or -not $task) {
    throw "Could not identify freeform KCalc app=$appPid child=$child task=$task"
}

try {
    & $Adb -s $Serial shell am task resize $task 80 180 920 1500 | Out-Null
    Start-Sleep -Seconds 4
    $smallChild = Get-Child $appPid
    & $Adb -s $Serial shell am task resize $task 0 0 1026 2200 | Out-Null
    Start-Sleep -Seconds 4
    $restoredChild = Get-Child $appPid
    if ($child -ne $smallChild -or $child -ne $restoredChild) {
        throw "Linux child restarted during freeform resize: $child -> $smallChild -> $restoredChild"
    }

    & $Adb -s $Serial shell run-as $Package kill $child | Out-Null
    Start-Sleep -Seconds 2
    $report = (& $Adb -s $Serial shell run-as $Package cat files/kcalc-report.txt) -join "`n"
    $configures = [regex]::Matches($report, 'xdg_toplevel\.configure width=(\d+) height=(\d+)')
    if ($configures.Count -lt 3) { throw "Expected at least three Wayland resize configures" }
    $sizes = $configures | ForEach-Object {
        "$($_.Groups[1].Value)x$($_.Groups[2].Value)"
    } | Select-Object -Unique
    if ($sizes.Count -lt 2) { throw "Wayland surface did not receive distinct freeform sizes: $sizes" }
    $acks = ([regex]::Matches($report, 'xdg_surface\.ack_configure serial=')).Count
    if ($acks -lt 3) { throw "KCalc did not acknowledge all freeform configures" }

    Write-Host "KCalc physical freeform resize passed on ${Serial}: PID $child, $($sizes -join ' -> ')."
} finally {
    & $Adb -s $Serial shell am start --windowingMode 1 -n "$Package/.MainActivity" | Out-Null
}
