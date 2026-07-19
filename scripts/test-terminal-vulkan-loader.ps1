param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archpheneos.terminal",
    [int]$CaptureDelayMilliseconds = 30000,
    [switch]$RequireDevice,
    [string]$ExpectedDevicePattern = ""
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
Invoke-Adb @("shell", "run-as", $Package, "id") | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null
Invoke-Adb @("logcat", "-c") | Out-Null
$remote = "am start -n $Package/.TerminalActivity " +
        '--es archphene_test_terminal_command "vulkaninfo --summary" ' +
        "--ei archphene_test_terminal_capture_delay_ms $CaptureDelayMilliseconds"
Invoke-Adb @("shell", $remote) | Out-Null

$deadline = [DateTime]::UtcNow.AddMilliseconds($CaptureDelayMilliseconds + 20000)
$log = ""
do {
    Start-Sleep -Seconds 2
    $log = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
            "ArchpheneTerminal:I", "*:S")) -join [Environment]::NewLine
} while ($log -notmatch 'Terminal command probe transcript=' -and
        [DateTime]::UtcNow -lt $deadline)

if ($log -notmatch 'Terminal command probe transcript=') {
    throw ("Timed out waiting for vulkaninfo transcript." + [Environment]::NewLine + $log)
}
if ($log -match 'Vulkan loader is not installed, not found, or failed to load') {
    throw ("The runtime-loaded Vulkan loader was omitted from the closure." +
            [Environment]::NewLine + $log)
}
if ($log -notmatch 'Vulkan Instance Version|Found no drivers') {
    throw ("vulkaninfo did not reach Vulkan loader device discovery." +
            [Environment]::NewLine + $log)
}

if ($RequireDevice -and $log -notmatch 'GPU0:') {
    throw ("Vulkan loader reached ICD discovery but exposed no device." +
            [Environment]::NewLine + $log)
}
if ($ExpectedDevicePattern -and $log -notmatch $ExpectedDevicePattern) {
    throw ("Vulkan device output did not match '$ExpectedDevicePattern'." +
            [Environment]::NewLine + $log)
}

Write-Host "Terminal Vulkan loader/device discovery passed on $Serial."