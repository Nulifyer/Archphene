param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [string]$TargetPackage,
    [ValidateSet("KCalc", "Mousepad")]
    [string]$Profile = "KCalc",
    [string]$ProbeApk = "",
    [string]$AdbPath = "",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ProbePackage = "org.archphene.accessibilityprobe"
$ProbeActivity = "org.archphene.bridge.AccessibilityProbeActivity"
$ProbeService = "$ProbePackage/org.archphene.bridge.ProbeAccessibilityService"
$TreeFile = "files/framework-accessibility-tree-$TargetPackage.txt"
$CommandFile = "files/framework-accessibility-command.txt"
$ResponseFile = "files/framework-accessibility-response.txt"
$script:SettingsCaptured = $false
$script:OriginalServices = $null
$script:OriginalAccessibilityEnabled = $null

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $localAdb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
    $AdbPath = if (Test-Path -LiteralPath $localAdb -PathType Leaf) {
        $localAdb
    } else {
        (Get-Command adb -ErrorAction Stop).Source
    }
}

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    $output = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code ${LASTEXITCODE}: $($output -join "`n")"
    }
    return $output
}

function Restore-AccessibilitySettings {
    if (-not $script:SettingsCaptured) { return }
    if ([string]::IsNullOrWhiteSpace($script:OriginalServices) -or
            $script:OriginalServices -eq "null") {
        Invoke-Adb @("shell", "settings", "delete", "secure",
                "enabled_accessibility_services") "restore accessibility services" |
                Out-Null
    } else {
        Invoke-Adb @("shell", "settings", "put", "secure",
                "enabled_accessibility_services", $script:OriginalServices) `
                "restore accessibility services" | Out-Null
    }
    if ([string]::IsNullOrWhiteSpace($script:OriginalAccessibilityEnabled) -or
            $script:OriginalAccessibilityEnabled -eq "null") {
        Invoke-Adb @("shell", "settings", "delete", "secure",
                "accessibility_enabled") "restore accessibility state" | Out-Null
    } else {
        Invoke-Adb @("shell", "settings", "put", "secure",
                "accessibility_enabled", $script:OriginalAccessibilityEnabled) `
                "restore accessibility state" | Out-Null
    }
    $script:SettingsCaptured = $false
}

function ConvertTo-Base64Url([string]$Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-TargetTree {
    $tree = & $AdbPath -s $Serial shell run-as $ProbePackage cat $TreeFile 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $tree) { return $null }
    return $tree -join "`n"
}

function Wait-TargetTree {
    param(
        [string[]]$Contains = @(),
        [string[]]$Absent = @(),
        [switch]$Normalized
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $tree = Get-TargetTree
        if ($null -ne $tree) {
            $present = @($Contains | Where-Object { -not $tree.Contains($_) }).Count -eq 0
            $missing = @($Absent | Where-Object { $tree.Contains($_) }).Count -eq 0
            if ($present -and $missing -and
                    (-not $Normalized -or (Test-NormalizedBounds $tree))) {
                return $tree
            }
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for target accessibility tree. Required=$($Contains -join ', ') absent=$($Absent -join ', ')"
}

function Test-TargetTreeAbsentBriefly {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,
        [int]$TimeoutMilliseconds = 2000
    )
    $deadline = (Get-Date).AddMilliseconds($TimeoutMilliseconds)
    do {
        $tree = Get-TargetTree
        if ($null -ne $tree -and -not $tree.Contains($Value)) { return $true }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Wait-AccessibilityTargetAction {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Selector,
        [Parameter(Mandatory = $true)]
        [int]$RequiredAction
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $tree = Get-TargetTree
        if ($null -ne $tree) {
            foreach ($line in $tree -split "`n") {
                if (-not $line.StartsWith("NODE|")) { continue }
                $fields = $line.TrimEnd("`r").Split('|')
                if ($fields.Length -lt 10 -or
                        ($fields[3] -ne $Selector -and $fields[4] -ne $Selector) -or
                        $fields[6] -ne "true") { continue }
                $actions = 0L
                if ([long]::TryParse($fields[$fields.Length - 1], [ref]$actions) -and
                        ($actions -band $RequiredAction) -ne 0) { return }
            }
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for accessibility action '$Selector' flag $RequiredAction"
}

function Invoke-AccessibilityAction {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Selector,
        [ValidateSet("click", "focus", "scroll-forward", "scroll-backward", "set-text")]
        [string]$Action = "click",
        [string]$Value = ""
    )
    $requiredAction = switch ($Action) {
        "click" { 16 }
        "focus" { 1 }
        "scroll-forward" { 4096 }
        "scroll-backward" { 8192 }
        "set-text" { 2097152 }
    }
    Wait-AccessibilityTargetAction -Selector $Selector -RequiredAction $requiredAction
    $id = "real-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
    $selectorEncoded = ConvertTo-Base64Url $Selector
    $valueEncoded = ConvertTo-Base64Url $Value
    $payload = "$id`t$TargetPackage`t$Action`t$selectorEncoded`t$valueEncoded"
    Invoke-Adb @("shell", "run-as", $ProbePackage, "rm", "-f", $ResponseFile) `
            "clear accessibility response" | Out-Null
    $teeOutput = $payload | & $AdbPath -s $Serial shell run-as $ProbePackage `
            tee $CommandFile 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "write accessibility command failed: $($teeOutput -join "`n")"
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $response = & $AdbPath -s $Serial shell run-as $ProbePackage cat `
                $ResponseFile 2>$null
        if ($LASTEXITCODE -eq 0 -and $response) {
            $rendered = ($response -join "").Trim()
            if ($rendered.StartsWith("$id`t")) {
                if ($rendered -ne "$id`tOK") {
                    throw "Accessibility action '$Action $Selector' was rejected: $rendered"
                }
                return
            }
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for accessibility action '$Action $Selector'"
}

function Get-DisplaySize {
    $output = (Invoke-Adb @("shell", "wm", "size") "read display size") -join "`n"
    $matches = [regex]::Matches($output, '(?:Physical|Override) size: (\d+)x(\d+)')
    if ($matches.Count -eq 0) { throw "Could not determine display size: $output" }
    $match = $matches[$matches.Count - 1]
    return @([int]$match.Groups[1].Value, [int]$match.Groups[2].Value)
}

function Assert-NormalizedBounds([string]$Tree) {
    if (Test-NormalizedBounds $Tree) { return }
    $size = Get-DisplaySize
    throw "Accessibility nodes are outside $($size[0])x$($size[1])"
}

function Test-NormalizedBounds([string]$Tree) {
    $size = Get-DisplaySize
    foreach ($line in $Tree -split "`n") {
        if (-not $line.StartsWith("NODE|")) { continue }
        $fields = $line.Split('|')
        $match = [regex]::Match($line, '\|(-?\d+) (-?\d+) (-?\d+) (-?\d+)\|')
        if (-not $match.Success) {
            if ($fields.Length -lt 10) { continue }
            return $false
        }
        $left = [int]$match.Groups[1].Value
        $top = [int]$match.Groups[2].Value
        $right = [int]$match.Groups[3].Value
        $bottom = [int]$match.Groups[4].Value
        $isHiddenSentinel = ($right - $left -le 1 -or $bottom - $top -le 1) -and
                $fields.Length -ge 9 -and $fields[6] -eq "false" -and
                $fields[7] -eq "false"
        if ($isHiddenSentinel) { continue }
        if ($left -lt 0 -or $top -lt 0 -or $right -le $left -or
                $bottom -le $top -or $right -gt $size[0] -or $bottom -gt $size[1]) {
            return $false
        }
    }
    return $true
}

function Test-KCalc {
    $tree = Wait-TargetTree -Contains @("|KCalc|", "|Clear|", "|Equals|")
    if ($tree.Contains("|Close|")) {
        Invoke-AccessibilityAction -Selector "Close"
        Wait-TargetTree -Absent @("|Close|") | Out-Null
    }
    Invoke-AccessibilityAction -Selector "Clear"
    $tree = Wait-TargetTree -Contains @("|KCalc|", "|Clear|", "|Equals|") -Normalized
    Assert-NormalizedBounds $tree

    foreach ($selector in @("Six", "Add", "Five", "Equals")) {
        Invoke-AccessibilityAction -Selector $selector
    }
    $tree = Wait-TargetTree -Contains @("|11|")
    Assert-NormalizedBounds $tree

    Invoke-AccessibilityAction -Selector "File"
    $tree = Wait-TargetTree -Contains @("|Quit|")
    Assert-NormalizedBounds $tree

    Invoke-AccessibilityAction -Selector "Edit"
    $tree = Wait-TargetTree -Contains @("|Undo|", "|Redo|", "|Copy|", "|Paste|")
    Assert-NormalizedBounds $tree

    Invoke-AccessibilityAction -Selector "Settings"
    $tree = Wait-TargetTree -Contains @("|Simple Mode|", "|Science Mode|", "|Configure KCalc")
    Assert-NormalizedBounds $tree

    Invoke-AccessibilityAction -Selector "Help"
    $tree = Wait-TargetTree -Contains @("|KCalc Handbook|", "|Report Bug", "|About KCalc|")
    Assert-NormalizedBounds $tree

    Invoke-AccessibilityAction -Selector "About KCalc"
    $tree = Wait-TargetTree -Contains @("|About KCalc|", "|Close|")
    Assert-NormalizedBounds $tree
    Invoke-AccessibilityAction -Selector "Close"
    Wait-TargetTree -Absent @("|Close|") | Out-Null
}

function Test-Mousepad {
    $tree = Wait-TargetTree -Contains @(
        "|Untitled 1 - Mousepad|", "|File|File menu|", "|Edit|Edit menu|")

    Invoke-AccessibilityAction -Selector "File"
    $tree = Wait-TargetTree -Contains @(
        "|Open a file|", "|Save current document as another file|") -Normalized
    Assert-NormalizedBounds $tree

    Invoke-AccessibilityAction -Selector "Edit"
    $tree = Wait-TargetTree -Contains @(
        "|Paste the clipboard|", "|Show the preferences dialog|") `
            -Absent @("|Open a file|") -Normalized
    Assert-NormalizedBounds $tree

    Invoke-AccessibilityAction -Selector "Show the preferences dialog"
    $tree = Wait-TargetTree -Contains @("|Mousepad Preferences|") -Normalized
    Assert-NormalizedBounds $tree
    Invoke-Adb @("shell", "input", "keyevent", "4") `
            "dismiss preferences dialog" | Out-Null
    Wait-TargetTree -Absent @("|Mousepad Preferences|") | Out-Null

    Invoke-AccessibilityAction -Selector "File"
    Wait-TargetTree -Contains @("|Open a file|") | Out-Null
    Invoke-AccessibilityAction -Selector "Open a file"
    $tree = Wait-TargetTree -Contains @("|Open File|") -Normalized
    Assert-NormalizedBounds $tree
    Invoke-Adb @("shell", "input", "keyevent", "4") `
            "dismiss open file dialog" | Out-Null
    if (-not (Test-TargetTreeAbsentBriefly -Value "|Open File|")) {
        Invoke-Adb @("shell", "input", "keyevent", "4") `
                "dismiss open file dialog after IME" | Out-Null
    }
    Wait-TargetTree -Absent @("|Open File|") | Out-Null
}

try {
    Invoke-Adb @("wait-for-device") "wait for device" | Out-Null
    $installed = (Invoke-Adb @("shell", "pm", "list", "packages", $TargetPackage) `
            "find target package") -join "`n"
    if ($installed -notmatch "package:$([regex]::Escape($TargetPackage))") {
        throw "Target package is not installed: $TargetPackage"
    }
    if (-not [string]::IsNullOrWhiteSpace($ProbeApk)) {
        $resolvedProbe = (Resolve-Path -LiteralPath $ProbeApk).Path
        Invoke-Adb @("install", "-r", $resolvedProbe) "install accessibility probe" | Out-Null
    }
    $probeInstalled = (Invoke-Adb @("shell", "pm", "list", "packages", $ProbePackage) `
            "find accessibility probe") -join "`n"
    if ($probeInstalled -notmatch "package:$([regex]::Escape($ProbePackage))") {
        throw "Accessibility probe is not installed; pass -ProbeApk"
    }

    $script:OriginalServices = ((Invoke-Adb @("shell", "settings", "get", "secure",
            "enabled_accessibility_services") "read accessibility services") -join "").Trim()
    $script:OriginalAccessibilityEnabled = ((Invoke-Adb @("shell", "settings", "get", "secure",
            "accessibility_enabled") "read accessibility state") -join "").Trim()
    $script:SettingsCaptured = $true

    Invoke-Adb @("shell", "am", "start", "-W", "-n", "$ProbePackage/$ProbeActivity") `
            "start accessibility probe" | Out-Null
    Invoke-Adb @("shell", "settings", "put", "secure", "enabled_accessibility_services",
            $ProbeService) "enable probe accessibility service" | Out-Null
    Invoke-Adb @("shell", "settings", "put", "secure", "accessibility_enabled", "1") `
            "enable Android accessibility" | Out-Null
    Invoke-Adb @("shell", "run-as", $ProbePackage, "rm", "-f", $TreeFile,
            $CommandFile, $ResponseFile) "clear stale accessibility files" | Out-Null
    Invoke-Adb @("shell", "am", "force-stop", $TargetPackage) `
            "stop target app" | Out-Null
    Invoke-Adb @("shell", "monkey", "-p", $TargetPackage, "-c",
            "android.intent.category.LAUNCHER", "1") "launch target app" | Out-Null

    switch ($Profile) {
        "KCalc" { Test-KCalc }
        "Mousepad" { Test-Mousepad }
    }

    Write-Host "Real $Profile accessibility passed on $Serial for ${TargetPackage}: normalized bounds, semantic input, menus, and secondary dialog."
} finally {
    Restore-AccessibilitySettings
}
