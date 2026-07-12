param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.bridgeprobe"
$SafeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$Xml = Join-Path $Root "artifacts/arm64-physical-$SafeSerial.xml"
$Png = Join-Path $Root "artifacts/arm64-physical-$SafeSerial.png"

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

$state = (& $Adb -s $Serial get-state 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne "device") { throw "$Serial is not an authorized ADB device" }
$abis = (& $Adb -s $Serial shell getprop ro.product.cpu.abilist).Trim()
if ($abis -notmatch '(^|,)arm64-v8a(,|$)') { throw "$Serial is not ARM64: $abis" }

Invoke-Adb @("shell", "am", "force-stop", $Package) "force-stop probe"
Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/.MainActivity") "cold-launch probe"
$reportNode = $null
for ($attempt = 0; $attempt -lt 10 -and $null -eq $reportNode; $attempt++) {
    Start-Sleep -Seconds 1
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/arm64-physical.xml") "dump probe UI"
    Invoke-Adb @("pull", "/sdcard/arm64-physical.xml", $Xml) "pull probe UI"
    [xml]$ui = Get-Content $Xml -Raw
    $reportNode = $ui.SelectSingleNode("//node[contains(@text,'Native checks:')]")
    if ($null -eq $reportNode -and $ui.hierarchy.node.package -eq "com.android.systemui") {
        throw "Device screen is locked; unlock $Serial before running physical UI tests"
    }
}
if ($null -eq $reportNode) { throw "Native check report did not become visible within 10 seconds" }
$report = $reportNode.text
foreach ($expected in @(
    "ABIs: [arm64-v8a",
    "DocumentsProvider: PASS read/write",
    "Arch Linux ARM glibc: PASS ARCHPHENE_GLIBC_PASS",
    "uname.machine=aarch64",
    "AF_UNIX socketpair=PASS",
    "socket transfer=PASS",
    "shared mmap=PASS",
    "Wayland shim dlopen/exports=PASS"
)) {
    if (-not $report.Contains($expected)) { throw "Missing ARM64 probe result: $expected" }
}

$button = $ui.SelectSingleNode("//node[@text='RUN BRIDGE CHECKS']")
if ($null -eq $button -or $button.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
    throw "Could not locate bridge-check button"
}
$x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
$y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
Invoke-Adb @("shell", "input", "tap", "$x", "$y") "tap bridge-check button"
Start-Sleep -Milliseconds 500

$uidLine = (& $Adb -s $Serial shell cmd package list packages -U $Package |
    Select-String "^package:$([regex]::Escape($Package)) uid:\d+$" | Select-Object -First 1).Line
if (-not $uidLine) { throw "Android did not assign an app UID to $Package" }
$processId = (& $Adb -s $Serial shell pidof $Package).Trim()
if (-not $processId) { throw "Probe process is not running after interaction" }
$context = (& $Adb -s $Serial shell ps -AZ | Select-String "\s$([regex]::Escape($Package))$" | Select-Object -First 1).Line
if (-not $context -or $context -notmatch 'u:r:untrusted_app') {
    throw "Probe is not running in the expected Android untrusted_app SELinux domain: $context"
}

Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_HOME") "background probe"
Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/.MainActivity") "resume probe"
Start-Sleep -Milliseconds 500
Invoke-Adb @("shell", "screencap", "-p", "/sdcard/arm64-physical.png") "capture probe screenshot"
Invoke-Adb @("pull", "/sdcard/arm64-physical.png", $Png) "pull probe screenshot"

Write-Host "ARM64 physical-device bridge passed on $Serial ($abis), $uidLine, pid=$processId."
Write-Host "Evidence: $Xml"
Write-Host "Screenshot: $Png"
