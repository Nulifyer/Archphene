param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64",
    [string]$Serial = "emulator-5554",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.accessibilityprobe"
$Activity = "org.archphene.bridge.AccessibilityProbeActivity"
$Service = "$Package/org.archphene.bridge.ProbeAccessibilityService"
$script:SettingsCaptured = $false
$script:OriginalServices = $null
$script:OriginalAccessibilityEnabled = $null

function Restore-AccessibilitySettings {
    if (-not $script:SettingsCaptured) { return }
    if ([string]::IsNullOrWhiteSpace($script:OriginalServices) `
            -or $script:OriginalServices -eq "null") {
        & $Adb -s $Serial shell settings delete secure enabled_accessibility_services 2>&1 |
                Out-Null
    } else {
        & $Adb -s $Serial shell settings put secure enabled_accessibility_services `
                $script:OriginalServices 2>&1 | Out-Null
    }
    if ([string]::IsNullOrWhiteSpace($script:OriginalAccessibilityEnabled) `
            -or $script:OriginalAccessibilityEnabled -eq "null") {
        & $Adb -s $Serial shell settings delete secure accessibility_enabled 2>&1 | Out-Null
    } else {
        & $Adb -s $Serial shell settings put secure accessibility_enabled `
                $script:OriginalAccessibilityEnabled 2>&1 | Out-Null
    }
    $script:SettingsCaptured = $false
}

trap {
    Restore-AccessibilitySettings
    throw $_
}
$Apk = Join-Path $Root ("prototypes/accessibility-capability-probe/out-" +
        "$AndroidAbi/archphene-accessibility-probe.apk")

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code ${LASTEXITCODE}: $($output -join "`n")"
    }
    return $output
}

function Start-Probe {
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/$Activity") `
            "start accessibility probe" | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $value = & $Adb -s $Serial shell run-as $Package cat `
                files/accessibility-broker-name 2>$null
        if ($LASTEXITCODE -eq 0 -and $value) { return ($value -join "").Trim() }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    $logs = & $Adb -s $Serial logcat -d -v brief -s ArchpheneCapabilities AndroidRuntime
    throw "Accessibility broker did not start: $($logs -join "`n")"
}

function Native-Path {
    $dump = (Invoke-Adb @("shell", "dumpsys", "package", $Package) `
            "read accessibility probe package") -join "`n"
    $native = [regex]::Match($dump, "legacyNativeLibraryDir=(\S+)").Groups[1].Value
    if (-not $native) { throw "Accessibility native library directory is unavailable" }
    $subdirectory = if ($AndroidAbi -eq "arm64-v8a") { "arm64" } else { "x86_64" }
    return "$native/$subdirectory/libarchphene_accessibility_probe.so"
}

function Invoke-Probe([string]$Socket, [string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $Adb -s $Serial shell run-as $Package (Native-Path) `
            --socket "@$Socket" @Arguments 2>&1
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) {
        throw "Accessibility request failed: $($output -join "`n")"
    }
    return (($output -join "`n") -replace "[\r\n]+$", "")
}

function Dump-Accessibility {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $tree = & $Adb -s $Serial shell run-as $Package cat `
                files/framework-accessibility-tree.txt 2>$null
        if ($LASTEXITCODE -eq 0 -and $tree) { return $tree -join "`n" }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw "Android AccessibilityService did not receive the virtual tree"
}

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "Accessibility probe APK is missing: $Apk"
}
Invoke-Adb @("wait-for-device") "wait for accessibility device" | Out-Null
$script:OriginalServices = ((Invoke-Adb @("shell", "settings", "get", "secure", `
        "enabled_accessibility_services") "read enabled accessibility services") -join "").Trim()
$script:OriginalAccessibilityEnabled = ((Invoke-Adb @("shell", "settings", "get", "secure", `
        "accessibility_enabled") "read accessibility state") -join "").Trim()
$script:SettingsCaptured = $true
Invoke-Adb @("install", "-r", $Apk) "install accessibility probe" | Out-Null
Invoke-Adb @("shell", "pm", "clear", $Package) "clear accessibility probe" | Out-Null
Invoke-Adb @("logcat", "-c") "clear accessibility logs" | Out-Null
$socket = Start-Probe
Invoke-Adb @("shell", "settings", "put", "secure", "enabled_accessibility_services", `
        $Service) "enable probe accessibility service" | Out-Null
Invoke-Adb @("shell", "settings", "put", "secure", "accessibility_enabled", "1") `
        "enable Android accessibility" | Out-Null
$serviceDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $serviceLogs = (& $Adb -s $Serial logcat -d -v brief -s `
            "ArchpheneAccessibilityProbe:I" "*:S") -join "`n"
    if ($serviceLogs -match "Accessibility service connected") { break }
    Start-Sleep -Milliseconds 200
} while ((Get-Date) -lt $serviceDeadline)
if ($serviceLogs -notmatch "Accessibility service connected") {
    throw "Android did not bind the probe AccessibilityService"
}

$empty = Invoke-Probe $socket @("take-accessibility-action", "0") -AllowFailure
if ($empty -ne "ERROR`tEMPTY") { throw "Accessibility action queue was not empty: $empty" }
$published = Invoke-Probe $socket @("publish-accessibility-tree",
        "files/accessibility-tree.json")
if ($published -ne "OK") { throw "Accessibility tree publication failed: $published" }
Start-Sleep -Milliseconds 1500
$frameworkEvent = Invoke-Probe $socket @("accessibility-event", "0", "content")
if ($frameworkEvent -ne "OK") { throw "Accessibility readiness event failed" }
$tree = Dump-Accessibility
if (-not $tree.Contains("Archphene accessible button") `
        -or -not $tree.Contains("Accessible editor")) {
    throw "Android did not enumerate the published virtual controls: $tree"
}
$button = [regex]::Match($tree,
        '(?m)^\d+\|android\.widget\.Button\|Archphene accessible button\|[^|]*\|' +
        '(-?\d+) (-?\d+) (-?\d+) (-?\d+)$')
if (-not $button.Success -or [int]$button.Groups[3].Value -le [int]$button.Groups[1].Value `
        -or [int]$button.Groups[4].Value -le [int]$button.Groups[2].Value) {
    throw "Published accessibility button has invalid screen bounds"
}
$event = Invoke-Probe $socket @("accessibility-event", "3", "text")
if ($event -ne "OK") { throw "Accessibility event publication failed: $event" }
$invalid = Invoke-Probe $socket @("publish-accessibility-tree",
        "files/bad-accessibility-tree.json") -AllowFailure
if ($invalid -ne "ERROR`tINVALID_REQUEST") {
    throw "Cyclic accessibility tree was accepted: $invalid"
}
$treeAfterInvalid = Dump-Accessibility
if (-not $treeAfterInvalid.Contains("Archphene accessible button")) {
    throw "Invalid publication replaced the last valid accessibility tree"
}

Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity",
        "--ei", "archphene_node", "2", "--es", "archphene_action", "click") `
        "invoke Android accessibility click" | Out-Null
$click = Invoke-Probe $socket @("take-accessibility-action", "250")
if ($click -ne "OK`t2`tclick`t") {
    throw "Android click was not routed to Linux: $click"
}
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity",
        "--ei", "archphene_node", "3", "--es", "archphene_action", "set-text",
        "--es", "archphene_text", "edited-from-Android") `
        "invoke Android accessibility edit" | Out-Null
$edit = Invoke-Probe $socket @("take-accessibility-action", "250")
if ($edit -ne "OK`t3`tset-text`tZWRpdGVkLWZyb20tQW5kcm9pZA") {
    throw "Android set-text was not routed to Linux: $edit"
}

Invoke-Adb @("shell", "am", "force-stop", $Package) "stop accessibility lifecycle" | Out-Null
$stale = Invoke-Probe $socket @("take-accessibility-action", "0") -AllowFailure
if ($stale -notmatch "(Connection refused|No such file|Archphene Android capability request)") {
    throw "Stopped accessibility broker unexpectedly accepted work: $stale"
}
$logs = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
        "ArchpheneCapabilities:I", "AndroidRuntime:E", "*:S") `
        "read accessibility logs") -join "`n"
if ($logs -match "FATAL EXCEPTION") { throw "Accessibility probe crashed: $logs" }
Restore-AccessibilitySettings
Write-Host ("Android accessibility bridge passed on $Serial ($AndroidAbi): " +
        "virtual tree, bounds, events, invalid-tree rollback, click/edit actions, lifecycle.")
