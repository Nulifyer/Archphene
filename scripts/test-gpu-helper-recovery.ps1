param(
    [string]$Serial = "emulator-5554",
    [Parameter(Mandatory = $true)]
    [string]$Package,
    [string]$Activity = "org.archphene.linux.kcalc.MainActivity",
    [int]$StartupTimeoutSeconds = 45,
    [int]$RecoveryTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$Adb = Join-Path $PSScriptRoot "../tooling/android-sdk/platform-tools/adb.exe"
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { $Adb = "adb" }

function Invoke-Adb([Parameter(ValueFromRemainingArguments)][string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("adb failed: " + ($Arguments -join " ") + [Environment]::NewLine +
                ($output -join [Environment]::NewLine))
    }
    return $output
}

Invoke-Adb @("get-state") | Out-Null
$runAs = (Invoke-Adb @("shell", "run-as", $Package, "id")) -join [Environment]::NewLine
if ($runAs -notmatch 'uid=([0-9]+)') {
    throw "Target must be an installed debuggable Archphene wrapper: $Package"
}
$uid = $Matches[1]

Invoke-Adb @("logcat", "-c") | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/$Activity") | Out-Null

$startupDeadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
$helperPid = $null
do {
    Start-Sleep -Milliseconds 500
    $processes = (Invoke-Adb @("shell", "ps", "-A")) -join [Environment]::NewLine
    $helper = $processes -split [Environment]::NewLine | Where-Object {
        $_ -match '^\S+\s+([0-9]+)\s+' -and $_ -match 'libarchphene_virgl_server\.so'
    } | Select-Object -First 1
    if ($helper -and $helper -match '^\S+\s+([0-9]+)\s+') { $helperPid = $Matches[1] }
    $startupLog = (Invoke-Adb @("logcat", "-d", "-v", "brief")) -join [Environment]::NewLine
    if ($startupLog -match "FATAL EXCEPTION") {
        throw ("Wrapper crashed during startup." + [Environment]::NewLine + $startupLog)
    }
} while (-not $helperPid -and [DateTime]::UtcNow -lt $startupDeadline)

if (-not $helperPid) { throw "Timed out waiting for the virgl helper." }
$helperStatus = (Invoke-Adb @("shell", "run-as", $Package, "cat", "/proc/$helperPid/status")) -join [Environment]::NewLine
if ($helperStatus -notmatch '(?m)^Uid:\s+([0-9]+)' -or $Matches[1] -ne $uid) {
    throw "Refusing to kill helper PID $helperPid because it is not owned by target UID $uid."
}

Invoke-Adb @("logcat", "-c") | Out-Null
Invoke-Adb @("shell", "run-as", $Package, "kill", "-9", $helperPid) | Out-Null

$recoveryDeadline = [DateTime]::UtcNow.AddSeconds($RecoveryTimeoutSeconds)
$log = ""
do {
    Start-Sleep -Milliseconds 500
    $log = (Invoke-Adb @("logcat", "-d", "-v", "brief")) -join [Environment]::NewLine
    if ($log -match 'GPU helper exited unexpectedly' -and
            $log -match 'restarting runtime once with llvmpipe' -and
            $log -match 'Graphics renderer=llvmpipe helper-loss fallback' -and
            $log -match 'Linux Wayland client connected to shared native compositor') {
        break
    }
} while ([DateTime]::UtcNow -lt $recoveryDeadline)

if ($log -notmatch 'GPU helper exited unexpectedly') {
    throw ("Helper death was not detected." + [Environment]::NewLine + $log)
}
if ($log -notmatch 'restarting runtime once with llvmpipe') {
    throw ("Runtime was not restarted." + [Environment]::NewLine + $log)
}
if ($log -notmatch 'Graphics renderer=llvmpipe helper-loss fallback') {
    throw ("Software fallback was not selected." + [Environment]::NewLine + $log)
}
if (($log | Select-String 'restarting runtime once with llvmpipe' -AllMatches).Matches.Count -ne 1) {
    throw ("Runtime fallback was attempted more than once." + [Environment]::NewLine + $log)
}
$appPid = ((Invoke-Adb @("shell", "pidof", $Package)) | Select-Object -First 1).Trim()
if (-not $appPid) {
    throw ("Android Activity process exited during recovery." + [Environment]::NewLine + $log)
}

Write-Host "GPU helper-loss recovery passed on $Serial (app PID $appPid, helper PID $helperPid)."