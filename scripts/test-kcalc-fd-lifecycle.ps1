param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [int]$Cycles = 6,
    [string]$Package = "org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Activity = ((& $Adb -s $Serial shell cmd package resolve-activity --brief `
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $Package) |
    Select-Object -Last 1).Trim()
if ($Activity -notmatch '/') { throw "Could not resolve launcher activity for $Package" }

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    & $Adb -s $Serial @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

function Get-AppPid {
    $value = (& $Adb -s $Serial shell pidof $Package).Trim()
    if (-not $value) { throw "KCalc Android process is not running" }
    $value
}

function Get-FdSnapshot([string]$AppPid) {
    $command = "run-as $Package sh -c 'for f in /proc/$AppPid/fd/*; do readlink `$f; done'"
    $targets = @(& $Adb -s $Serial shell $command)
    if ($LASTEXITCODE -ne 0 -or $targets.Count -eq 0) {
        throw "Could not inspect KCalc descriptors; install a debuggable test build"
    }
    [pscustomobject]@{
        Total = $targets.Count
        WaylandShm = @($targets | Where-Object { $_ -like '*/memfd:wayland-shm*' }).Count
        SyncFence = @($targets | Where-Object { $_ -eq 'anon_inode:sync_file' }).Count
        Ashmem = @($targets | Where-Object { $_ -like '/dev/ashmem*' }).Count
    }
}

if ($Cycles -lt 1) { throw "Cycles must be at least 1" }
$state = (& $Adb -s $Serial get-state 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne "device") { throw "$Serial is not an authorized ADB device" }
$oldAccelerometer = (& $Adb -s $Serial shell settings get system accelerometer_rotation).Trim()
$oldRotation = (& $Adb -s $Serial shell settings get system user_rotation).Trim()

try {
    Invoke-Adb @("shell", "settings", "put", "system", "accelerometer_rotation", "0") "disable sensor rotation"
    Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "0") "force portrait"
    Invoke-Adb @("shell", "am", "force-stop", $Package) "force-stop KCalc"
    Invoke-Adb @("shell", "am", "start", "-W", "-n", $Activity) "launch KCalc"
    Start-Sleep -Seconds 8

    $appPid = Get-AppPid
    $before = Get-FdSnapshot $appPid
    for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
        Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "1") "rotate landscape $cycle"
        Start-Sleep -Milliseconds 900
        Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "0") "rotate portrait $cycle"
        Start-Sleep -Milliseconds 900
    }
    Start-Sleep -Seconds 15

    $afterPid = Get-AppPid
    if ($afterPid -ne $appPid) { throw "KCalc restarted during rotation: $appPid -> $afterPid" }
    $after = Get-FdSnapshot $appPid
    if ($after.WaylandShm -gt ($before.WaylandShm + 1)) {
        throw "Wayland SHM descriptors leaked: $($before.WaylandShm) -> $($after.WaylandShm)"
    }

    Write-Host "KCalc FD lifecycle passed on $Serial after $Cycles cycles."
    Write-Host "Total: $($before.Total) -> $($after.Total); wayland-shm: $($before.WaylandShm) -> $($after.WaylandShm); sync fences: $($before.SyncFence) -> $($after.SyncFence); ashmem: $($before.Ashmem) -> $($after.Ashmem)"
} finally {
    & $Adb -s $Serial shell settings put system accelerometer_rotation $oldAccelerometer | Out-Null
    & $Adb -s $Serial shell settings put system user_rotation $oldRotation | Out-Null
}