param(
    [string]$Serial = "emulator-5554",
    [string]$KCalcPackage = "org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2",
    [string]$MousepadPackage = "org.archphene.linux.p241d399e14343c53b8b766e9126776aa"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"

function Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$Step failed: $($output -join "`n")" }
    return @($output)
}

function Wait-Log([string]$Pattern, [string]$Step, [int]$Seconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $logArguments = @(
            "logcat", "-d", "-v", "brief", "-s",
            "ArchpheneRuntime:V", "ArchpheneLinuxApp:I", "AndroidRuntime:E", "*:S"
        )
        $log = (Adb -Arguments $logArguments -Step "read $Step log") -join "`n"
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Step`: $Pattern`n$log"
}

function Test-Wrapper([string]$Package, [string]$MissingLibrary) {
    $activity = (Adb -Arguments @("shell", "cmd", "package", "resolve-activity", "--brief", $Package) `
            -Step "resolve $Package") | Where-Object { $_ -match '^[^\s]+/[^\s]+$' } |
        Select-Object -Last 1
    if (-not $activity) { throw "$Package has no launcher activity" }
    $dump = (Adb -Arguments @("shell", "dumpsys", "package", $Package) `
            -Step "inspect $Package") -join "`n"
    if ($dump -notmatch '(?m)^\s*flags=\[[^\]]*DEBUGGABLE') {
        throw "$Package must be a debug wrapper for the descriptor-library probe"
    }

    Adb -Arguments @("logcat", "-c") -Step "clear log" | Out-Null
    Adb -Arguments @("shell", "am", "force-stop", $Package) `
        -Step "stop $Package" | Out-Null
    Adb -Arguments @("shell", "am", "start", "-W", "-n", $activity,
            "--ez", "archphene_test_descriptor_libraries_runtime", "true") `
        -Step "launch descriptor-library $Package" | Out-Null
    $failure = Wait-Log "Runtime GUI exit=127" "$Package descriptor-library failure"
    if ($failure -notmatch 'Runtime module view=named-program-descriptor-libraries' -or
            $failure -notmatch [regex]::Escape($MissingLibrary)) {
        throw "$Package did not expose the expected stock-glibc descriptor limitation`n$failure"
    }
    $cache = (Adb -Arguments @("shell", "run-as", $Package, "du", "-sk", "cache") `
            -Step "measure $Package probe cache") -join "`n"
    $cacheKiB = [int64]([regex]::Match($cache, '^\s*(\d+)').Groups[1].Value)
    if ($cacheKiB -ge 65536) {
        throw "$Package retained a materialized runtime closure after the failed probe: $cache"
    }

    Adb -Arguments @("logcat", "-c") -Step "clear production launch log" | Out-Null
    Adb -Arguments @("shell", "am", "force-stop", $Package) `
        -Step "stop failed $Package probe" | Out-Null
    Adb -Arguments @("shell", "am", "start", "-W", "-n", $activity) `
        -Step "launch normal $Package" | Out-Null
    Wait-Log 'Linux Wayland client connected to shared native compositor' `
        "$Package normal named-cache launch" 45 | Out-Null
    Adb -Arguments @("shell", "am", "force-stop", $Package) `
        -Step "stop normal $Package" | Out-Null
    Write-Host "$Package passed: descriptor-library mode failed closed on $MissingLibrary; normal launch remained healthy."
}

Test-Wrapper $KCalcPackage "libKF6Notifications.so.6"
Test-Wrapper $MousepadPackage "libmousepad.so.0"
Write-Host "Qt and GTK descriptor-library compatibility gate passed on $Serial; retain the bounded named runtime cache."
