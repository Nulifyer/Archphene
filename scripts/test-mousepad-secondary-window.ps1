param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.p241d399e14343c53b8b766e9126776aa",
    [string]$Activity = "org.archphene.linux.kcalc.MainActivity"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$ProbePackage = "org.archphene.accessibilityprobe"
$ProbeActivity = "org.archphene.bridge.AccessibilityProbeActivity"
$ProbeService = "$ProbePackage/org.archphene.bridge.ProbeAccessibilityService"
$TreeFile = "files/framework-accessibility-tree-$Package.txt"
$CommandFile = "files/framework-accessibility-command.txt"
$ResponseFile = "files/framework-accessibility-response.txt"
$script:SettingsCaptured = $false
$script:OriginalServices = $null
$script:OriginalAccessibilityEnabled = $null

function Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join "`n")" }
    return $output
}

function Read-BridgeLog {
    return (Adb @("logcat", "-d", "-s", "ArchpheneInput:V", "*:S")) -join "`n"
}

function Wait-BridgeLog([string]$Pattern, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $log = Read-BridgeLog
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for bridge log: $Pattern"
}

function Wait-PrimaryLayoutStable([int]$TimeoutSeconds = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastLayout = $null
    $stablePolls = 0
    do {
        $log = Read-BridgeLog
        $layouts = [regex]::Matches($log,
            'window id=([0-9]+).*mapped=true.*primary=true.*geometry=.* ([0-9]+)x([0-9]+).*canvas=([0-9]+)x([0-9]+).*title=.*Mousepad')
        if ($layouts.Count -gt 0) {
            $layout = $layouts[$layouts.Count - 1]
            $signature = $layout.Groups[1].Value + ':' +
                    $layout.Groups[2].Value + 'x' + $layout.Groups[3].Value + ':' +
                    $layout.Groups[4].Value + 'x' + $layout.Groups[5].Value
            if ($signature -eq $lastLayout) { $stablePolls++ } else {
                $lastLayout = $signature
                $stablePolls = 0
            }
            if ($stablePolls -ge 3) { return $log }
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for a stable Mousepad primary layout"
}
function ConvertTo-Base64Url([string]$Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-TargetTree {
    $tree = & $Adb -s $Serial shell run-as $ProbePackage cat $TreeFile 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $tree) { return $null }
    return $tree -join "`n"
}

function Wait-TargetTree([string[]]$Contains, [string[]]$Absent = @(),
        [int]$TimeoutSeconds = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $tree = Get-TargetTree
        if ($null -ne $tree) {
            $present = @($Contains | Where-Object { -not $tree.Contains($_) }).Count -eq 0
            $missing = @($Absent | Where-Object { $tree.Contains($_) }).Count -eq 0
            if ($present -and $missing) { return $tree }
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for accessibility tree: $($Contains -join ', ')"
}

function Invoke-AccessibilityAction([string]$Selector) {
    $id = "secondary-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
    $selectorEncoded = ConvertTo-Base64Url $Selector
    $payload = "$id`t$Package`tclick`t$selectorEncoded`t"
    Adb @("shell", "run-as", $ProbePackage, "rm", "-f", $ResponseFile) | Out-Null
    $teeOutput = $payload | & $Adb -s $Serial shell run-as $ProbePackage tee $CommandFile 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Could not write accessibility command: $($teeOutput -join "`n")"
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $response = & $Adb -s $Serial shell run-as $ProbePackage cat $ResponseFile 2>$null
        if ($LASTEXITCODE -eq 0 -and $response) {
            $rendered = ($response -join "").Trim()
            if ($rendered.StartsWith("$id`t")) {
                if ($rendered -ne "$id`tOK") {
                    throw "Accessibility action '$Selector' was rejected: $rendered"
                }
                return
            }
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for accessibility action '$Selector'"
}

function Restore-AccessibilitySettings {
    if (-not $script:SettingsCaptured) { return }
    if ([string]::IsNullOrWhiteSpace($script:OriginalServices) -or
            $script:OriginalServices -eq "null") {
        Adb @("shell", "settings", "delete", "secure",
                "enabled_accessibility_services") | Out-Null
    } else {
        Adb @("shell", "settings", "put", "secure",
                "enabled_accessibility_services", $script:OriginalServices) | Out-Null
    }
    if ([string]::IsNullOrWhiteSpace($script:OriginalAccessibilityEnabled) -or
            $script:OriginalAccessibilityEnabled -eq "null") {
        Adb @("shell", "settings", "delete", "secure", "accessibility_enabled") | Out-Null
    } else {
        Adb @("shell", "settings", "put", "secure", "accessibility_enabled",
                $script:OriginalAccessibilityEnabled) | Out-Null
    }
    $script:SettingsCaptured = $false
}

try {
    $probeInstalled = (Adb @("shell", "pm", "list", "packages", $ProbePackage)) -join "`n"
    if ($probeInstalled -notmatch "package:$([regex]::Escape($ProbePackage))") {
        throw "Accessibility probe is not installed"
    }
    $script:OriginalServices = ((Adb @("shell", "settings", "get", "secure",
            "enabled_accessibility_services")) -join "").Trim()
    $script:OriginalAccessibilityEnabled = ((Adb @("shell", "settings", "get", "secure",
            "accessibility_enabled")) -join "").Trim()
    $script:SettingsCaptured = $true

    Adb @("shell", "am", "start", "-W", "-n", "$ProbePackage/$ProbeActivity") | Out-Null
    Adb @("shell", "settings", "put", "secure", "enabled_accessibility_services",
            $ProbeService) | Out-Null
    Adb @("shell", "settings", "put", "secure", "accessibility_enabled", "1") | Out-Null
    Adb @("shell", "run-as", $ProbePackage, "rm", "-f", $TreeFile,
            $CommandFile, $ResponseFile) | Out-Null

    Adb @("shell", "pm", "clear", $Package) | Out-Null
    Adb @("logcat", "-c") | Out-Null
    $freeformSupport = ((Adb @("shell", "settings", "get", "global",
            "enable_freeform_support")) -join "").Trim() -eq "1"
    $startArguments = @("shell", "am", "start")
    if ($freeformSupport) { $startArguments += @("--windowingMode", "5") }
    $startArguments += @("-n", "$Package/$Activity")
    Adb $startArguments | Out-Null
    Wait-BridgeLog 'window id=([0-9]+).*mapped=true.*active=true.*primary=true.*title=.*Mousepad' 30 | Out-Null
    $mainLog = Wait-PrimaryLayoutStable
    $mainMatches = [regex]::Matches($mainLog,
        'window id=([0-9]+).*mapped=true.*active=true.*primary=true.*title=.*Mousepad')
    $mainId = [int]$mainMatches[$mainMatches.Count - 1].Groups[1].Value
    Wait-TargetTree @("|Untitled 1 - Mousepad|", "|Edit|Edit menu|") | Out-Null

    Invoke-AccessibilityAction "Edit"
    Wait-BridgeLog 'popup registry=.*:[0-9]+,[0-9]+,[0-9]+,[0-9]+,[1-9][0-9]*,[1-9][0-9]*,1,0;' 10 | Out-Null
    Wait-TargetTree @("|Show the preferences dialog|") | Out-Null
    Invoke-AccessibilityAction "Show the preferences dialog"

    $childLog = Wait-BridgeLog 'window id=([0-9]+) parent=([0-9]+) mapped=true active=(?:true|false) primary=false .*title=Mousepad Preferences' 15
    $childMatches = [regex]::Matches($childLog,
        'window id=([0-9]+) parent=([0-9]+) mapped=true active=(?:true|false) primary=false .*title=Mousepad Preferences')
    $child = $childMatches[$childMatches.Count - 1]
    $childId = [int]$child.Groups[1].Value
    if ([int]$child.Groups[2].Value -ne $mainId) {
        throw "Preferences window is not parented to the Mousepad toplevel"
    }

    Wait-TargetTree @("|Mousepad Preferences|", "|Show line numbers|",
            "|Use system monospace font|") | Out-Null
    Invoke-AccessibilityAction "Show line numbers"

    Adb @("logcat", "-c") | Out-Null
    Invoke-AccessibilityAction "Close"
    Wait-BridgeLog "window id=$mainId parent=0 mapped=true active=true primary=true" 10 | Out-Null
    Wait-TargetTree @("|Untitled 1 - Mousepad|") @("|Mousepad Preferences|") | Out-Null
    $appPid = (Adb @("shell", "pidof", $Package)) -join ""
    if (-not $appPid.Trim()) { throw "Mousepad parent exited when the child closed" }

    Write-Host "Mousepad secondary-window bridge passed: child $childId accepted a semantic control action, closed, and parent $mainId remained active."
} finally {
    Restore-AccessibilitySettings
}