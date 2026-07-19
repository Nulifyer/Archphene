param(
    [string]$Serial = "emulator-5554",
    [string]$Wrapper = "org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"

function RuntimeUri([string]$Path) {
    $hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    return "content://org.archpheneos.manager.runtime/v1/$hash"
}

function Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join "`n")" }
    return $output
}

function Invoke-PrivateAdb([string]$Package, [string[]]$Arguments) {
    $output = & $Adb -s $Serial shell run-as $Package @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) { return $output }
    if (-not $AdbIsRoot) {
        throw "run-as failed for $Package`: $($output -join "`n")"
    }
    $root = "/data/user/0/$Package"
    $mapped = @($Arguments | ForEach-Object {
        $value = [string]$_
        if ($value -eq "cache") { "$root/cache" } else { $value }
    })
    return Adb (@("shell") + $mapped)
}

function Wait-RuntimeLog([string]$Pattern, [int]$Seconds = 20) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $log = (Adb @("logcat", "-d", "-v", "brief", "-s", "ArchpheneRuntime:V", "*:S")) -join "`n"
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for runtime log: $Pattern`n$log"
}

function Wait-Loader([int]$AndroidPid, [int]$Seconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $processes = (Adb @("shell", "ps", "-A", "-o", "PID,PPID,NAME")) -join "`n"
        foreach ($line in $processes -split "`n") {
            $match = [regex]::Match($line.Trim(), '^(\d+)\s+(\d+)\s+(\S+)$')
            if ($match.Success -and [int]$match.Groups[2].Value -eq $AndroidPid -and
                    $match.Groups[3].Value -eq "loader") {
                return [int]$match.Groups[1].Value
            }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for the managed Linux loader child of Android PID $AndroidPid"
}

$AdbIsRoot = (((Adb @("shell", "id", "-u")) -join "").Trim() -eq "0")
$managerDump = (Adb @("shell", "dumpsys", "package", $Manager)) -join "`n"
$ManagerDebuggable = $managerDump -match '(?m)^\s*flags=\[[^\]]*DEBUGGABLE'

$legacyUri = RuntimeUri (Join-Path $Root "prototypes/linux-app-manager-stub/assets/payload-hello-linux-amd64")
$packages = (Adb @("shell", "cmd", "package", "list", "packages", "-U")) -join "`n"
$managerUid = [regex]::Match($packages, "package:$([regex]::Escape($Manager)) uid:(\d+)").Groups[1].Value
$wrapperUid = [regex]::Match($packages, "package:$([regex]::Escape($Wrapper)) uid:(\d+)").Groups[1].Value
if (-not $managerUid -or -not $wrapperUid) { throw "Manager and runtime wrapper must be installed" }
if ($managerUid -eq $wrapperUid) { throw "Runtime test requires distinct Android UIDs" }
$wrapperActivity = Adb @("shell", "cmd", "package", "resolve-activity", "--brief", $Wrapper) |
    Where-Object { $_ -match '^[^\s]+/[^\s]+$' } | Select-Object -Last 1
if (-not $wrapperActivity) { throw "Runtime wrapper has no resolved launcher Activity" }

try {
    if ($ManagerDebuggable) {
        Adb @("logcat", "-c") | Out-Null
        Adb @("shell", "am", "force-stop", $Manager) | Out-Null
        Adb @("shell", "am", "start", "-W", "-n", "$Manager/.MainActivity",
            "--es", "archphene_test_runtime_module_package", $Wrapper,
            "--es", "archphene_test_runtime_module_action", "verify_catalog") | Out-Null
        Wait-RuntimeLog "Runtime catalog parser passed" | Out-Null

        Adb @("logcat", "-c") | Out-Null
        Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
        Adb @("shell", "am", "start", "-W", "-n", $wrapperActivity,
            "--es", "archphene_test_runtime_module_uri", $legacyUri) | Out-Null
        $denied = Wait-RuntimeLog "Runtime FD probe failed"
        if ($denied -match "Runtime FD probe exit=0") {
            throw "Wrapper opened an unbound legacy runtime module"
        }
    } else {
        $denied = (Adb @("shell", "content", "read", "--uri", $legacyUri)) -join "`n"
        if ($denied -notmatch 'Runtime module is unavailable to this caller|Permission Denial') {
            throw "Production runtime provider did not reject the ungranted shell caller`n$denied"
        }
    }

    Adb @("logcat", "-c") | Out-Null
    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", $wrapperActivity) | Out-Null
    $lease = Wait-RuntimeLog "Acquired runtime pack lease ([a-f0-9]{64}) for $([regex]::Escape($Wrapper))" 30
    $packId = [regex]::Match($lease, "Acquired runtime pack lease ([a-f0-9]{64})").Groups[1].Value
    if (-not $packId) { throw "Runtime pack lease did not identify its immutable pack" }

    $androidPidText = (Adb @("shell", "pidof", $Wrapper) | Select-Object -First 1).Trim()
    if ($androidPidText -notmatch '^\d+$') { throw "Could not identify the wrapper Android PID" }
    $androidPid = [int]$androidPidText
    $linuxPid = Wait-Loader $androidPid
    $runtimeLog = (Adb @("logcat", "-d", "-v", "brief", "-s", "ArchpheneRuntime:V", "*:S")) -join "`n"
    if ($runtimeLog -match 'Runtime GUI exit=') {
        throw "Runtime failed before the Linux loader became interactive`n$runtimeLog"
    }
    $fds = (Invoke-PrivateAdb -Package $Wrapper -Arguments @("ls", "-l", "/proc/$linuxPid/fd")) -join "`n"
    $runtimeFds = @($fds -split "`n" | Where-Object { $_ -match 'runtime-fd-' })
    $fdSummary = ($runtimeFds | Select-Object -First 12) -join "`n"
    if ($fds -notmatch 'runtime-fd-[^/]+/\.program' -or
            $fds -notmatch 'runtime-fd-[^/]+/\.library-') {
        throw "Linux loader is missing its bounded wrapper-private executable view`n$fdSummary"
    }

    $liveCacheSize = ((Invoke-PrivateAdb -Package $Wrapper -Arguments @("du", "-sk", "cache")) -join "`n")
    $liveCacheKiB = [int64]([regex]::Match($liveCacheSize, '^(\d+)').Groups[1].Value)
    if ($liveCacheKiB -le 0 -or $liveCacheKiB -ge 524288) {
        throw "Wrapper execution cache is outside the 512 MiB bound: $liveCacheSize"
    }

    Adb @("shell", "input", "keyevent", "4") | Out-Null
    Wait-RuntimeLog "(Released runtime pack lease|Runtime process died; released pack lease) $packId" 20 | Out-Null
    $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
        $cleanCacheSize = ((Invoke-PrivateAdb -Package $Wrapper -Arguments @("du", "-sk", "cache")) -join "`n")
        $cleanCacheKiB = [int64]([regex]::Match($cleanCacheSize, '^(\d+)').Groups[1].Value)
    } while ($cleanCacheKiB -ge 65536 -and [DateTime]::UtcNow -lt $cleanupDeadline)
    if ($cleanCacheKiB -ge 65536) {
        throw "Wrapper retained a runtime closure after process exit: $cleanCacheSize"
    }

    Write-Host "Runtime-pack execution passed on ${Serial}: manager UID $managerUid -> wrapper UID $wrapperUid; pack $packId used a bounded ${liveCacheKiB} KiB executable view and cleaned to ${cleanCacheKiB} KiB on exit."
} finally {
    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Adb @("shell", "am", "force-stop", $Manager) | Out-Null
}
