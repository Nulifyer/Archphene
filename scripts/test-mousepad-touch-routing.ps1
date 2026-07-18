param([string]$Serial = "emulator-5554")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.p241d399e14343c53b8b766e9126776aa"

function Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join "`n")" }
    return $output
}

Adb @("shell", "am", "force-stop", $Package) | Out-Null
Adb @("logcat", "-c") | Out-Null
Adb @("shell", "am", "start", "-n",
        "$Package/org.archphene.linux.kcalc.MainActivity") | Out-Null
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    $startup = (Adb @("logcat", "-d", "-s", "ArchpheneInput:I", "*:S")) -join "`n"
    if ($startup -match 'mapped=true.*title=.*Mousepad') { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)
if ($startup -notmatch 'mapped=true.*title=.*Mousepad') {
    throw "Mousepad Wayland client did not map before the drag test"
}
Adb @("logcat", "-c") | Out-Null
Adb @("shell", "input", "swipe", "800", "1500", "800", "900", "350") | Out-Null
Start-Sleep -Seconds 1
$log = (Adb @("logcat", "-d", "-s", "ArchpheneInput:D", "*:S")) -join "`n"
if ($log -notmatch 'touch down.*result=1' -or $log -notmatch 'touch up.*result=1') {
    throw "One-finger drag did not produce a complete Wayland touch sequence"
}
if ($log -match 'pointer button pressed=true') {
    throw "One-finger drag incorrectly activated a pointer click"
}
Write-Host "Mousepad touch routing passed: drag used wl_touch without a pointer click."