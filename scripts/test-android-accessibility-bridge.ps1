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
    param([string]$Contains = "", [string]$Absent = "")
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $tree = & $Adb -s $Serial shell run-as $Package cat `
                files/framework-accessibility-tree.txt 2>$null
        if ($LASTEXITCODE -eq 0 -and $tree) {
            $rendered = $tree -join "`n"
            if (($Contains.Length -eq 0 -or $rendered.Contains($Contains)) -and
                    ($Absent.Length -eq 0 -or -not $rendered.Contains($Absent))) {
                return $rendered
            }
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw "Android AccessibilityService did not receive the expected virtual tree"
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
$refreshAction = @("shell", "am", "start", "-n", "$Package/$Activity",
        "--ei", "archphene_node", "2", "--es", "archphene_action", "click")
Invoke-Adb $refreshAction "queue accessibility action before semantic refresh" | Out-Null
$republished = Invoke-Probe $socket @("publish-accessibility-tree", "files/accessibility-tree.json")
if ($republished -ne "OK") { throw "Accessibility tree refresh failed: $republished" }
$preservedClick = Invoke-Probe $socket @("take-accessibility-action", "250")
$expectedPreservedClick = "OK" + [char]9 + "2" + [char]9 + "click" + [char]9
if ($preservedClick -ne $expectedPreservedClick) {
    throw "Semantic refresh dropped a pending accessibility action: $preservedClick"
}
Start-Sleep -Milliseconds 1500
$frameworkEvent = Invoke-Probe $socket @("accessibility-event", "0", "content")
if ($frameworkEvent -ne "OK") { throw "Accessibility readiness event failed" }
$secondaryTree = Dump-Accessibility -Contains "Secondary accessible button"
if (-not $secondaryTree.Contains("Secondary accessible button")) {
    throw "Android did not enumerate the secondary virtual controls: $secondaryTree"
}
$secondaryWindowCount = ([regex]::Matches($secondaryTree, '(?m)^WINDOW\|')).Count
if ($secondaryWindowCount -lt 2) {
    throw "Android did not expose the active secondary accessibility window: $secondaryTree"
}
$secondaryButton = [regex]::Match($secondaryTree,
        '(?m)^\d+\|android\.widget\.Button\|Secondary accessible button\|[^|]*\|' +
        '(-?\d+) (-?\d+) (-?\d+) (-?\d+)$')
$displayText = (Invoke-Adb @("shell", "wm", "size") "read display size") -join [Environment]::NewLine
$displayMatches = [regex]::Matches($displayText,
        '(?:Physical|Override) size: (\d+)x(\d+)')
if (-not $secondaryButton.Success -or $displayMatches.Count -eq 0) {
    throw "Could not validate normalized secondary accessibility bounds"
}
$display = $displayMatches[$displayMatches.Count - 1]
$left = [int]$secondaryButton.Groups[1].Value
$top = [int]$secondaryButton.Groups[2].Value
$right = [int]$secondaryButton.Groups[3].Value
$bottom = [int]$secondaryButton.Groups[4].Value
$displayWidth = [int]$display.Groups[1].Value
$displayHeight = [int]$display.Groups[2].Value
$boundsInvalid = (
        $left -lt 0 -or $top -lt 0 -or
        $right -le $left -or $bottom -le $top -or
        $right -gt $displayWidth -or $bottom -gt $displayHeight)
if ($boundsInvalid) {
    throw "Secondary accessibility bounds were not normalized: $($secondaryButton.Value)"
}
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity", `
        "--ez", "archphene_reorder_windows", "true") `
        "reorder ambiguous accessibility windows" | Out-Null
Start-Sleep -Milliseconds 250
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity", `
        "--ei", "archphene_node", "12", "--es", "archphene_action", "click", `
        "--es", "archphene_provider", "secondary") `
        "invoke secondary Android accessibility click" | Out-Null
$secondaryClick = Invoke-Probe $socket @("take-accessibility-action", "250")
if ($secondaryClick -ne "OK`t12`tclick`t") {
    throw "Secondary Android click was not routed to Linux: $secondaryClick"
}
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity", `
        "--ei", "archphene_node", "12", "--es", "archphene_action", "click", `
        "--es", "archphene_provider", "primary", `
        "--ez", "archphene_expect_rejected", "true") `
        "reject secondary node through primary accessibility provider" | Out-Null
$afterWrongWindow = Invoke-Probe $socket @("take-accessibility-action", "0") -AllowFailure
if ($afterWrongWindow -ne "ERROR$([char]9)EMPTY") {
    throw "Secondary node leaked into the primary provider: $afterWrongWindow"
}
Invoke-Adb @("shell", "run-as", $Package, "rm", "-f",
        "files/framework-accessibility-tree.txt") "clear secondary framework tree" | Out-Null
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity", `
        "--ez", "archphene_hide_secondary", "true") `
        "dismiss secondary accessibility window" | Out-Null
$tree = Dump-Accessibility -Contains "Archphene accessible button" `
            -Absent "Secondary accessible button"
if (-not $tree.Contains("Archphene accessible button") `
        -or -not $tree.Contains("Accessible editor") `
        -or -not $tree.Contains("Scrollable list") `
        -or $tree.Contains("Secondary accessible button")) {
    throw "Android did not restore the isolated primary virtual controls: $tree"
}
$windowCount = ([regex]::Matches($tree, '(?m)^WINDOW\|')).Count
if ($windowCount -lt 2) {
    throw "Android did not expose the restored primary accessibility window: $tree"
}
$button = [regex]::Match($tree,
        '(?m)^\d+\|android\.widget\.Button\|Archphene accessible button\|[^|]*\|' +
        '(-?\d+) (-?\d+) (-?\d+) (-?\d+)$')
if (-not $button.Success -or [int]$button.Groups[3].Value -le [int]$button.Groups[1].Value `
        -or [int]$button.Groups[4].Value -le [int]$button.Groups[2].Value) {
    throw "Published accessibility button has invalid screen bounds"
}
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity", `
        "--ei", "archphene_node", "4", "--es", "archphene_action", "scroll-forward") `
        "invoke Android accessibility scroll" | Out-Null
$scroll = Invoke-Probe $socket @("take-accessibility-action", "250")
if ($scroll -ne "OK`t4`tscroll-forward`t") {
    throw "Android scroll was not routed to Linux: $scroll"
}
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity", `
        "--ei", "archphene_node", "5", "--es", "archphene_action", "click", `
        "--ez", "archphene_expect_rejected", "true") `
        "reject disabled Android accessibility action" | Out-Null
$afterDisabled = Invoke-Probe $socket @("take-accessibility-action", "0") -AllowFailure
if ($afterDisabled -ne "ERROR$([char]9)EMPTY") {
    throw "Disabled accessibility action reached Linux: $afterDisabled"
}
$oversizedText = "x" * 1025
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity", `
        "--ei", "archphene_node", "3", "--es", "archphene_action", "set-text", `
        "--es", "archphene_text", $oversizedText, `
        "--ez", "archphene_expect_rejected", "true") `
        "reject oversized Android accessibility text" | Out-Null
$afterOversized = Invoke-Probe $socket @("take-accessibility-action", "0") -AllowFailure
if ($afterOversized -ne "ERROR$([char]9)EMPTY") {
    throw "Oversized accessibility text reached Linux: $afterOversized"
}
$multibyteText = ([string][char]0x20ac) * 400
Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity", `
        "--ei", "archphene_node", "3", "--es", "archphene_action", "set-text", `
        "--es", "archphene_text", $multibyteText, `
        "--ez", "archphene_expect_rejected", "true") `
        "reject oversized multibyte Android accessibility text" | Out-Null
$afterMultibyte = Invoke-Probe $socket @("take-accessibility-action", "0") -AllowFailure
if ($afterMultibyte -ne "ERROR$([char]9)EMPTY") {
    throw "Oversized multibyte accessibility text reached Linux: $afterMultibyte"
}
$event = Invoke-Probe $socket @("accessibility-event", "3", "text")
if ($event -ne "OK") { throw "Accessibility event publication failed: $event" }
$invalid = Invoke-Probe $socket @("publish-accessibility-tree",
        "files/bad-accessibility-tree.json") -AllowFailure
if ($invalid -ne "ERROR`tINVALID_REQUEST") {
    throw "Cyclic accessibility tree was accepted: $invalid"
}
$treeAfterInvalid = Dump-Accessibility -Contains "Archphene accessible button"
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
        "sticky two-window ownership, cross-window rejection, virtual trees, bounds, " +
        "events, invalid-tree rollback, primary/secondary click, edit, scroll, " +
        "disabled/oversized rejection, lifecycle.")
