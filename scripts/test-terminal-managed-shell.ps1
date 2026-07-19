param(
    [string]$Serial = "emulator-5554",
    [string]$ExpectedArchitecture = "x86_64-pc-linux-gnu",
    [switch]$ExpectPageSizeRejection,
    [switch]$ResetAppData,
    [switch]$SkipInstall,
    [int]$InstallTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Manager = Join-Path $Root "prototypes/linux-app-manager-stub/out-linux/archphene.apk"
$Terminal = Join-Path $Root "prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
$ManagerPackage = "org.archpheneos.manager"
$TerminalPackage = "org.archpheneos.terminal"
$Tag = "ArchpheneTerminal"

function Invoke-Adb {
    $arguments = @($args)
    $output = & adb -s $Serial @arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Get-TaggedLog {
    return (Invoke-Adb logcat -d -s "${Tag}:I" "*:S") -join "`n"
}

function Get-AllLog {
    return (Invoke-Adb logcat -d -s "ArchphenePackages:I" "AndroidRuntime:E" "*:S") -join "`n"
}

function Start-Probe([string]$Command, [int]$CaptureDelayMilliseconds) {
    Invoke-Adb shell am force-stop $TerminalPackage | Out-Null
    Invoke-Adb logcat -c | Out-Null
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command))
    Invoke-Adb shell am start -n "$TerminalPackage/.TerminalActivity" --es archphene_test_terminal_command_base64 $encoded --ei archphene_test_terminal_capture_delay_ms $CaptureDelayMilliseconds | Out-Null
}

function Wait-Probe([string[]]$Patterns, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        $log = Get-TaggedLog
        foreach ($pattern in $Patterns) {
            if ($log -match $pattern) { return $log }
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for Terminal probe.`n$log"
}

$state = (Invoke-Adb get-state) -join ""
if ($state -ne "device") { throw "Device $Serial is not ready: $state" }

if (-not $SkipInstall) {
    foreach ($apk in @($Manager, $Terminal)) {
        if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Missing APK: $apk" }
        Invoke-Adb install -r -d $apk | Out-Null
    }
}

if ($ResetAppData) {
    Invoke-Adb shell pm clear $ManagerPackage | Out-Null
    Invoke-Adb shell pm clear $TerminalPackage | Out-Null
    $sdk = [int](((Invoke-Adb shell getprop ro.build.version.sdk) -join "").Trim())
    if ($sdk -ge 33) {
        Invoke-Adb shell pm grant $TerminalPackage android.permission.POST_NOTIFICATIONS | Out-Null
    }
}

$pageSize = ((Invoke-Adb shell getconf PAGE_SIZE) -join "").Trim()
$abi = ((Invoke-Adb shell getprop ro.product.cpu.abi) -join "").Trim()
Write-Host "Testing ${Serial}: ABI=$abi page-size=$pageSize"

if ($ExpectPageSizeRejection) {
    Start-Probe "pacman -S bash" 90000
    $log = Wait-Probe @(
        'is not compatible with 16384-byte Android pages',
        'Terminal command probe transcript='
    ) ([Math]::Min($InstallTimeoutSeconds, 120))
    if ($log -notmatch 'is not compatible with 16384-byte Android pages') {
        throw "The 16 KB device did not report the expected compatibility rejection.`n$log"
    }
    if ($log -match 'shell=Arch Bash') {
        throw "Terminal selected Arch Bash after a rejected runtime pack.`n$log"
    }
    Write-Host "Managed-shell rejection passed on $Serial ($abi, $pageSize-byte pages)."
    exit 0
}

$probe = 'bash --version; pacman -Q; pwd; touch terminal-managed-shell-ok; test -f terminal-managed-shell-ok; echo ARCHPHENE_MANAGED_SHELL_OK'
if (-not $ResetAppData) {
    Start-Probe $probe 15000
    Start-Sleep -Seconds 18
    $existingLog = Get-TaggedLog
    $shellAvailable = $existingLog -match 'shell=Arch Bash' -and
        $existingLog -match 'ARCHPHENE_MANAGED_SHELL_OK'
} else {
    $shellAvailable = $false
}

if (-not $shellAvailable) {
    Invoke-Adb shell am force-stop $ManagerPackage | Out-Null
    Invoke-Adb logcat -c | Out-Null
    $remote = 'am start -n {0}/.MainActivity --ez archphene_test_package_runtime true --es archphene_test_stage_package bash --ez archphene_test_publish_terminal true' -f $ManagerPackage
    Invoke-Adb shell $remote | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds($InstallTimeoutSeconds)
    do {
        Start-Sleep -Seconds 3
        $installLog = Get-AllLog
        if ($installLog -match 'Terminal catalog published \S*/bash/') { break }
        if ($installLog -match 'Package preparation failed|FATAL EXCEPTION|AndroidRuntime.*SecurityException') {
            throw "Bash installation failed.`n$installLog"
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($installLog -notmatch 'Terminal catalog published \S*/bash/') {
        throw "Timed out provisioning Arch Bash.`n$installLog"
    }
}

Start-Probe $probe 15000
$log = Wait-Probe @('ARCHPHENE_MANAGED_SHELL_OK') 45

$required = @(
    'shell=Arch Bash',
    [regex]::Escape($ExpectedArchitecture),
    '(?m)^.*bash\s+\S+',
    '/files/terminal/home',
    'ARCHPHENE_MANAGED_SHELL_OK'
)
foreach ($pattern in $required) {
    if ($log -notmatch $pattern) { throw "Missing expected Terminal evidence '$pattern'.`n$log" }
}
$forbidden = @('warning: setlocale', 'CANNOT LINK EXECUTABLE', 'SIGSYS', 'SYS_SECCOMP')
foreach ($pattern in $forbidden) {
    if ($log -match $pattern) { throw "Terminal emitted forbidden output '$pattern'.`n$log" }
}

Write-Host "Managed Arch Bash passed on $Serial ($abi, $pageSize-byte pages, $ExpectedArchitecture)."
