param(
    [string]$Serial = "emulator-5554",
    [string]$Apk = "",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $Apk) {
    $Apk = Join-Path $Root "tooling/build/terminal-prompt/archphene-terminal.apk"
}
$TerminalPackage = "org.archpheneos.terminal"
$Tag = "ArchpheneTerminal"
$localAdb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$script:Adb = if (Get-Command adb -ErrorAction SilentlyContinue) {
    (Get-Command adb).Source
} elseif (Test-Path -LiteralPath $localAdb) {
    $localAdb
} else {
    throw "adb was not found"
}

function Invoke-Adb {
    $arguments = @($args)
    $output = & $script:Adb -s $Serial @arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($arguments -join ' ') $([Environment]::NewLine)$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Get-TerminalLog {
    return (Invoke-Adb logcat -d -v brief -s ($Tag + ":I") "*:S") -join [Environment]::NewLine
}

function Invoke-PromptProbe([string]$Command) {
    Invoke-Adb shell am force-stop $TerminalPackage | Out-Null
    Invoke-Adb logcat -c | Out-Null
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command))
    Invoke-Adb shell am start -W -n ($TerminalPackage + "/.TerminalActivity") --ei archphene_test_terminal_send_delay_ms 3000 --ei archphene_test_terminal_capture_delay_ms 3000 --es archphene_test_terminal_command_base64 $encoded | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Seconds 1
        $log = Get-TerminalLog
        if ($log -match "Terminal command probe transcript=") { return $log }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for Terminal prompt probe. $([Environment]::NewLine)$log"
}

function Assert-Matches([string]$Value, [string]$Pattern, [string]$Message) {
    if ($Value -notmatch $Pattern) {
        throw "$Message $([Environment]::NewLine)$Value"
    }
}

if (((Invoke-Adb get-state) -join "").Trim() -ne "device") {
    throw "Device $Serial is not ready"
}
if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "Missing APK: $Apk" }
    Invoke-Adb install -r $Apk | Out-Null
}

Invoke-Adb shell run-as $TerminalPackage mkdir -p files/terminal/home/Documents/source/ArchpheneOS | Out-Null

$homeLog = Invoke-PromptProbe "echo HOME_PROMPT_OK"
Assert-Matches $homeLog '(?m): \$ echo HOME_PROMPT_OK\r?$' "Submitted command was not retained"
Assert-Matches $homeLog '(?m): HOME_PROMPT_OK\r?$' "Submitted command did not execute"
Assert-Matches $homeLog '(?m): archphene ~\r?$' "Home prompt was not abbreviated to ~"
if ([regex]::Matches($homeLog, '(?m): archphene ').Count -ne 1) {
    throw "Submitted prompt context was not collapsed after the IME resize. $([Environment]::NewLine)$homeLog"
}

$nestedLog = Invoke-PromptProbe "cd ~/Documents/source/ArchpheneOS"
Assert-Matches $nestedLog '(?m): \$ cd ~/Documents/source/ArchpheneOS\r?$' "Nested-directory command was not retained"
Assert-Matches $nestedLog '(?m): archphene ~/D/s/ArchpheneOS\r?$' "Fish-style path abbreviation was not rendered"
if ([regex]::Matches($nestedLog, '(?m): archphene ').Count -ne 1) {
    throw "Nested submitted prompt context was not collapsed. $([Environment]::NewLine)$nestedLog"
}
if ($nestedLog -match 'Shell marker=') {
    throw "Diagnostic shell-marker logging remains enabled. $([Environment]::NewLine)$nestedLog"
}

Write-Host "Terminal transient two-line prompt passed on $Serial."
