param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"
$Wrapper = "org.archphene.linux.kcalc"
$Uri = "content://org.archpheneos.manager.runtime/v1/76136d0afafb480c67517dea36450ec28b120ab4b73c29e036c74c6a2c00101c"
$DynamicUri = "content://org.archpheneos.manager.runtime/v1/6adbf15a76ef673ee66b8af66b3717383cbefea55c9d65809d909c7597fe099b"
$LoaderUri = "content://org.archpheneos.manager.runtime/v1/d1763646c97e95ed93ad72c43365cab8747a83170c849002002c7675749a1915"
$LibcUri = "content://org.archpheneos.manager.runtime/v1/1e31d1a9cb4ddf13d1bb61ed0be1e4e04309b32d1f6f1f0a68820f2e3099101a"

function Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join "`n")" }
    return $output
}

function Wait-RuntimeLog([string]$Pattern, [int]$Seconds = 10) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $log = (Adb @("logcat", "-d", "-v", "brief", "-s", "ArchpheneRuntime:V", "*:S")) -join "`n"
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for runtime log: $Pattern`n$log"
}

$packages = (Adb @("shell", "cmd", "package", "list", "packages", "-U")) -join "`n"
$managerUid = [regex]::Match($packages, "package:$([regex]::Escape($Manager)) uid:(\d+)").Groups[1].Value
$wrapperUid = [regex]::Match($packages, "package:$([regex]::Escape($Wrapper)) uid:(\d+)").Groups[1].Value
if (-not $managerUid -or -not $wrapperUid) { throw "Manager and KCalc must be installed" }
if ($managerUid -eq $wrapperUid) { throw "Runtime test requires distinct Android UIDs" }

try {
    Adb @("shell", "am", "force-stop", $Manager) | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", "$Manager/.MainActivity",
        "--es", "archphene_test_runtime_module_package", $Wrapper,
        "--es", "archphene_test_runtime_module_action", "revoke") | Out-Null
    Wait-RuntimeLog "Revoked runtime module" | Out-Null

    Adb @("logcat", "-c") | Out-Null
    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", "$Wrapper/.MainActivity",
        "--es", "archphene_test_runtime_module_uri", $Uri) | Out-Null
    $denied = Wait-RuntimeLog "Runtime FD probe failed"
    if ($denied -match "Runtime FD probe exit=0") {
        throw "Wrapper opened the runtime module without a URI grant"
    }

    Adb @("logcat", "-c") | Out-Null
    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Adb @("shell", "am", "force-stop", $Manager) | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", "$Manager/.MainActivity",
        "--es", "archphene_test_runtime_module_package", $Wrapper,
        "--es", "archphene_test_runtime_module_action", "launch") | Out-Null
    $executed = Wait-RuntimeLog "Runtime FD probe exit=0 output=hello from linux elf goos=linux goarch=amd64"
    if ($executed -notmatch "Launched runtime module for $([regex]::Escape($Wrapper))") {
        throw "Manager launch did not record the wrapper target"
    }

    Adb @("logcat", "-c") | Out-Null
    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Adb @("shell", "am", "force-stop", $Manager) | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", "$Wrapper/.MainActivity",
        "--es", "archphene_test_runtime_module_uri", $DynamicUri,
        "--es", "archphene_test_runtime_loader_uri", $LoaderUri,
        "--es", "archphene_test_runtime_libc_uri", $LibcUri) | Out-Null
    Wait-RuntimeLog "Runtime glibc probe failed" | Out-Null

    Adb @("logcat", "-c") | Out-Null
    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Adb @("shell", "am", "force-stop", $Manager) | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", "$Manager/.MainActivity",
        "--es", "archphene_test_runtime_module_package", $Wrapper,
        "--es", "archphene_test_runtime_module_action", "launch_dynamic") | Out-Null
    $dynamic = Wait-RuntimeLog "Runtime glibc probe exit=0 output=hello from shared glibc closure" 20
    if ($dynamic -notmatch "Launched glibc runtime modules for $([regex]::Escape($Wrapper))") {
        throw "Manager glibc launch did not record the wrapper target"
    }

    Write-Host "Runtime FD sharing passed on ${Serial}: manager UID $managerUid -> wrapper UID $wrapperUid; static and glibc modules denied without grants and executed without wrapper copies."
} finally {
    Adb @("shell", "am", "force-stop", $Wrapper) | Out-Null
    Adb @("shell", "am", "force-stop", $Manager) | Out-Null
}
