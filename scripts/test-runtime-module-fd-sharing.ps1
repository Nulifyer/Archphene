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
    $fds = (Adb @("shell", "run-as", $Wrapper, "ls", "-l", "/proc/$linuxPid/fd")) -join "`n"
    $runtimeFds = @($fds -split "`n" | Where-Object { $_ -match 'runtime-packs|runtime-fd-' })
    $fdSummary = ($runtimeFds | Select-Object -First 12) -join "`n"
    if ($fds -notmatch [regex]::Escape("org.archpheneos.manager/files/runtime-packs/$packId")) {
        throw "Linux loader is not consuming manager-owned runtime-pack descriptors`n$fdSummary"
    }
    if ($fds -match 'runtime-fd-[^/]+/\.(program|library-)') {
        throw "Linux closure was copied into the wrapper cache instead of using descriptors`n$fdSummary"
    }

    $cacheSize = ((Adb @("shell", "run-as", $Wrapper, "du", "-sk", "cache")) -join "`n")
    $cacheKiB = [int64]([regex]::Match($cacheSize, '^(\d+)').Groups[1].Value)
    if ($cacheKiB -ge 65536) {
        throw "Wrapper cache contains an unexpected runtime closure: $cacheSize"
    }

    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Wait-RuntimeLog "(Released runtime pack lease|Runtime process died; released pack lease) $packId" 20 | Out-Null

    Write-Host "Runtime FD sharing passed on ${Serial}: manager UID $managerUid -> wrapper UID $wrapperUid; pack $packId executed through inherited descriptors with a ${cacheKiB} KiB wrapper cache."
} finally {
    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Adb @("shell", "am", "force-stop", $Manager) | Out-Null
}
