param(
    [string]$Serial = "emulator-5554",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Manager = Join-Path $Root "prototypes/linux-app-manager-stub/out-linux/archphene.apk"
$Terminal = Join-Path $Root "prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
$ManagerPackage = "org.archpheneos.manager"
$TerminalPackage = "org.archpheneos.terminal"

function Invoke-Adb {
    $arguments = @($args)
    $output = & adb -s $Serial @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($arguments -join ' ')`n$($output -join "`n")" }
    return $output
}

function Package-Value([string]$Package, [string]$Pattern) {
    $dump = (Invoke-Adb shell dumpsys package $Package) -join "`n"
    $match = [regex]::Match($dump, $Pattern)
    if (-not $match.Success) { throw "Could not read $Pattern for $Package" }
    return $match.Groups[1].Value
}
function Tap-Text([string]$Ui, [string]$Text) {
    $node = [regex]::Match($Ui,
        "text=`"$([regex]::Escape($Text))`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $node.Success) { throw "Could not find '$Text'" }
    $x = ([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2
    $y = ([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2
    Invoke-Adb shell input tap $x $y | Out-Null
}
if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build-manager-podman.ps1") -SkipRuntime -SkipGpuHelperBuild
    if ($LASTEXITCODE -ne 0) { throw "Terminal companion build failed" }
}
foreach ($apk in @($Manager, $Terminal)) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Missing APK: $apk" }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($Manager)
try {
    $entry = $archive.GetEntry("assets/package-runtime/archphene-terminal.apk")
    if ($null -eq $entry) { throw "Manager APK does not embed the Terminal companion" }
    $stream = $entry.Open()
    try {
        $embeddedHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()
    } finally { $stream.Dispose() }
} finally { $archive.Dispose() }
$terminalHash = (Get-FileHash -LiteralPath $Terminal -Algorithm SHA256).Hash.ToLowerInvariant()
if ($embeddedHash -ne $terminalHash) { throw "Embedded Terminal APK differs from build output" }

Invoke-Adb install -r $Manager | Out-Null
Invoke-Adb install -r $Terminal | Out-Null
$managerVersion = Package-Value $ManagerPackage 'versionCode=(\d+)'
$terminalVersion = Package-Value $TerminalPackage 'versionCode=(\d+)'
if ($managerVersion -ne $terminalVersion) {
    throw "Manager/Terminal version mismatch: $managerVersion != $terminalVersion"
}
Invoke-Adb shell am force-stop $ManagerPackage | Out-Null
Invoke-Adb shell am start -W -n "$ManagerPackage/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
Invoke-Adb shell uiautomator dump --compressed /sdcard/archphene-terminal-main.xml | Out-Null
$mainUi = (Invoke-Adb shell cat /sdcard/archphene-terminal-main.xml) -join ""
Tap-Text $mainUi "Settings"
Start-Sleep -Seconds 1
Invoke-Adb shell uiautomator dump --compressed /sdcard/archphene-terminal-settings.xml | Out-Null
$settingsUi = (Invoke-Adb shell cat /sdcard/archphene-terminal-settings.xml) -join ""
if ($settingsUi -notmatch 'text="Archphene Terminal"' -or
        $settingsUi -notmatch 'text="Ready \| [^"]+"' -or
        $settingsUi -notmatch 'text="Open Terminal"') {
    throw "Settings does not expose the ready Terminal companion"
}

$packages = (Invoke-Adb shell cmd package list packages -U) -join "`n"
$managerUid = [regex]::Match($packages, "package:$([regex]::Escape($ManagerPackage)) uid:(\d+)").Groups[1].Value
$terminalUid = [regex]::Match($packages, "package:$([regex]::Escape($TerminalPackage)) uid:(\d+)").Groups[1].Value
if ([string]::IsNullOrEmpty($managerUid) -or [string]::IsNullOrEmpty($terminalUid) -or $managerUid -eq $terminalUid) {
    throw "Manager and Terminal do not have distinct Android UIDs"
}

Invoke-Adb shell am force-stop $TerminalPackage | Out-Null
Invoke-Adb shell am start -W -n "$TerminalPackage/.TerminalActivity" | Out-Null
Start-Sleep -Seconds 3
$processes = (Invoke-Adb shell ps -A) -join "`n"
if ($processes -notmatch [regex]::Escape($TerminalPackage) -or $processes -notmatch "(?m)^u0_a\d+.*\sS sh$") {
    throw "Terminal PTY shell did not start"
}

Invoke-Adb shell input text 'pacman%s-Ss%sbtop' | Out-Null
Invoke-Adb shell input keyevent 66 | Out-Null
Start-Sleep -Seconds 3
Invoke-Adb shell uiautomator dump /sdcard/archphene-terminal-gate.xml | Out-Null
$ui = (Invoke-Adb shell cat /sdcard/archphene-terminal-gate.xml) -join "`n"
if ($ui -notmatch 'Review the btop package before installing' -or $ui -notmatch 'text="btop".*hint="Search official Arch packages"') {
    throw "Signature-protected pacman request did not reach manager review"
}

Invoke-Adb shell am force-stop $ManagerPackage | Out-Null
Invoke-Adb shell am start -n "$ManagerPackage/.MainActivity" --es `
    archphene_terminal_action search --es archphene_terminal_query forged | Out-Null
Start-Sleep -Seconds 2
Invoke-Adb shell uiautomator dump /sdcard/archphene-forged-request.xml | Out-Null
$forgedUi = (Invoke-Adb shell cat /sdcard/archphene-forged-request.xml) -join "`n"
if ($forgedUi -match 'Review the forged|text="forged"') {
    throw "Exported manager launcher accepted forged Terminal extras"
}

$untrusted = & adb -s $Serial shell content call --uri `
    content://org.archpheneos.manager.runtime --method `
    org.archphene.runtime.TERMINAL_CATALOG_V1 2>&1
if (($untrusted -join "`n") -notmatch 'SecurityException') {
    throw "Untrusted caller unexpectedly accessed the Terminal runtime catalog"
}

Write-Host "Terminal companion isolation passed: manager UID $managerUid, Terminal UID $terminalUid, version $managerVersion."
