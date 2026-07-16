param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.kcalc",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$ProbeCopy = "/data/local/tmp/archphene-capability-probe"
$ProbeCopied = $false

trap {
    if ($ProbeCopied) {
        & $Adb -s $Serial shell rm -f $ProbeCopy 2>&1 | Out-Null
    }
    throw $_
}

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code ${LASTEXITCODE}: $($output -join "`n")"
    }
    return $output
}

function Wait-Broker {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $logs = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
                "ArchpheneCapabilities", "AndroidRuntime") "read broker logs") -join "`n"
        $matches = [regex]::Matches($logs,
                "Capability broker ready abstract=(archphene-[A-Za-z0-9_-]+)")
        if ($matches.Count -gt 0) {
            return $matches[$matches.Count - 1].Groups[1].Value
        }
        if ($logs.Contains("FATAL EXCEPTION")) { throw "Wrapper crashed`n$logs" }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Capability broker did not start`n$logs"
}

function Invoke-Probe([string]$Socket, [string[]]$Arguments,
        [switch]$AllowFailure) {
    $output = & $Adb -s $Serial shell run-as $Package $script:Probe `
            --socket "@$Socket" @Arguments 2>&1
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) {
        throw "Capability probe failed: $($output -join "`n")"
    }
    return ($output -join "`n").Trim()
}

Invoke-Adb @("wait-for-device") "wait for device" | Out-Null
Invoke-Adb @("shell", "pm", "path", $Package) "find wrapper" | Out-Null
Invoke-Adb @("shell", "pm", "clear", $Package) "reset capability fixture" | Out-Null
Invoke-Adb @("logcat", "-c") "clear logs" | Out-Null
$component = Invoke-Adb @("shell", "cmd", "package", "resolve-activity", "--brief",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
        $Package) "resolve wrapper launcher" |
        Where-Object { $_ -match "^$([regex]::Escape($Package))/" } |
        Select-Object -Last 1
if (-not $component) { throw "Wrapper has no launcher Activity" }
Invoke-Adb @("shell", "am", "start", "-n", $component) "start wrapper" | Out-Null
$socket = Wait-Broker

$packageDump = (Invoke-Adb @("shell", "dumpsys", "package", $Package) `
        "read package paths") -join "`n"
$native = [regex]::Match($packageDump, "legacyNativeLibraryDir=(\S+)").Groups[1].Value
if (-not $native) { throw "Wrapper native library directory is unavailable" }
$script:Probe = "$native/x86_64/libarchphene_capability_probe.so"
Invoke-Adb @("shell", "run-as", $Package, "test", "-x", $script:Probe) `
        "find native capability probe" | Out-Null

$first = Invoke-Probe -Socket $socket -Arguments @("notify", "capability-test", "Archphene",
        "Linux-notification-broker-test") -AllowFailure
if ($first -ne "ERROR`tPERMISSION_REQUESTED") {
    throw "First notification did not request Android permission: $first"
}
Start-Sleep -Milliseconds 500
Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/archphene-capability.xml") `
        "dump notification permission UI" | Out-Null
[xml]$permissionUi = (Invoke-Adb @("shell", "cat",
        "/sdcard/archphene-capability.xml") "read notification permission UI") -join ""
$allow = $permissionUi.SelectNodes("//node") | Where-Object {
    $_.text -in @("Allow", "While using the app")
} | Select-Object -First 1
if ($null -eq $allow) { throw "Notification permission prompt has no Allow action" }
$bounds = [regex]::Matches($allow.bounds, "\d+") | ForEach-Object { [int]$_.Value }
Invoke-Adb @("shell", "input", "tap",
        [string][int](($bounds[0] + $bounds[2]) / 2),
        [string][int](($bounds[1] + $bounds[3]) / 2)) `
        "allow notification permission" | Out-Null
Start-Sleep -Milliseconds 500

$second = Invoke-Probe -Socket $socket -Arguments @("notify", "capability-test", "Archphene",
        "Linux-notification-broker-test")
if ($second -ne "OK") { throw "Notification retry failed: $second" }
$notifications = (Invoke-Adb @("shell", "dumpsys", "notification", "--noredact") `
        "inspect notifications") -join "`n"
if (-not $notifications.Contains("capability-test") -or
        -not $notifications.Contains("Linux-notification-broker-test")) {
    throw "Android did not publish the Linux notification"
}

$invalid = Invoke-Probe -Socket $socket -Arguments @("open-uri", "file:///data/local/tmp/secret") `
        -AllowFailure
if ($invalid -ne "ERROR`tINVALID_REQUEST") {
    throw "Unsafe URI was not rejected: $invalid"
}
Invoke-Adb @("shell", "cp", $script:Probe, $ProbeCopy) "copy cross-UID probe" | Out-Null
$ProbeCopied = $true
Invoke-Adb @("shell", "chmod", "755", $ProbeCopy) "make cross-UID probe executable" `
        | Out-Null
$unauthorized = & $Adb -s $Serial shell $ProbeCopy --socket "@$socket" `
        withdraw capability-test 2>&1
if (($unauthorized -join "`n").Trim() -ne "ERROR`tUNAUTHORIZED") {
    throw "Cross-UID broker request was not rejected: $($unauthorized -join "`n")"
}

$opened = Invoke-Probe -Socket $socket -Arguments @("open-uri",
        "https://example.com/archphene-capability-test")
if ($opened -ne "OK") { throw "HTTPS URI did not open: $opened" }
Start-Sleep -Milliseconds 500
$logs = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
        "ArchpheneCapabilities") "verify URL logs") -join "`n"
if (-not $logs.Contains("Opened Android URI scheme=https")) {
    throw "Android URL bridge did not complete"
}
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") "return to wrapper" | Out-Null
Start-Sleep -Milliseconds 250
$withdrawn = Invoke-Probe -Socket $socket -Arguments @("withdraw", "capability-test")
if ($withdrawn -ne "OK") { throw "Notification withdrawal failed: $withdrawn" }
Invoke-Adb @("shell", "rm", "-f", $ProbeCopy) "remove cross-UID probe" | Out-Null
$ProbeCopied = $false

Write-Host "Android capability broker passed on ${Serial}: same-UID IPC, notification permission/post/withdraw, HTTPS open, unsafe-URI rejection, and cross-UID denial."
