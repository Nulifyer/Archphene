param(
    [string]$Serial = "emulator-5554",
    [string]$KCalcPackage = "org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"
$Profiles = @(
    @{ Name = "phone-light"; Width = 1080; Height = 2400; Density = 420; Font = "1.0"; Night = "no" },
    @{ Name = "phone-landscape-dark"; Width = 2400; Height = 1080; Density = 420; Font = "1.0"; Night = "yes" },
    @{ Name = "tablet-light"; Width = 1280; Height = 1920; Density = 280; Font = "1.15"; Night = "no" },
    @{ Name = "tablet-landscape-dark"; Width = 1920; Height = 1280; Density = 280; Font = "1.15"; Night = "yes" },
    @{ Name = "docked-dark"; Width = 1920; Height = 1080; Density = 240; Font = "1.0"; Night = "yes" }
)

function Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed: $($output -join "`n")"
    }
    return @($output)
}

function Dump-Ui([string]$Name) {
    $remote = "/sdcard/archphene-matrix-$Name.xml"
    Adb @("shell", "uiautomator", "dump", $remote) "dump $Name UI" | Out-Null
    return (Adb @("shell", "cat", $remote) "read $Name UI") -join "`n"
}

function Assert-Bounds([string]$Ui, [string]$Pattern, [int]$Width, [int]$Height,
        [string]$Control) {
    $node = [regex]::Match($Ui,
            '<node[^>]*' + $Pattern + '[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $node.Success) { throw "$Control is missing from the UI tree" }
    $left = [int]$node.Groups[1].Value
    $top = [int]$node.Groups[2].Value
    $right = [int]$node.Groups[3].Value
    $bottom = [int]$node.Groups[4].Value
    if ($left -lt 0 -or $top -lt 0 -or $right -gt $Width -or $bottom -gt $Height -or
            $right -le $left -or $bottom -le $top) {
        throw "$Control is outside ${Width}x${Height}: [$left,$top][$right,$bottom]"
    }
    return @($left, $top, $right, $bottom)
}

function Get-ProcessState {
    $deadline = [DateTime]::UtcNow.AddSeconds(35)
    do {
        $app = ((Adb @("shell", "pidof", $KCalcPackage) "find KCalc process") -join "").Trim()
        if ($app) {
            $ps = (Adb @("shell", "ps", "-A", "-o", "PID,PPID,NAME") "read process tree") -join "`n"
            $child = [regex]::Match($ps,
                    "(?m)^\s*(\d+)\s+$app\s+(?:loader|libarchphene_ld\.so)\s*$").Groups[1].Value
            if ($child) { return [pscustomobject]@{ App = $app; Child = $child } }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "KCalc Linux child did not become ready under Android PID $app"
}

function Apply-Profile([hashtable]$Profile) {
    Adb @("shell", "wm", "size", "$($Profile.Width)x$($Profile.Height)") "set display size" | Out-Null
    Adb @("shell", "wm", "density", [string]$Profile.Density) "set display density" | Out-Null
    Adb @("shell", "settings", "put", "system", "font_scale", $Profile.Font) "set font scale" | Out-Null
    Adb @("shell", "cmd", "uimode", "night", $Profile.Night) "set night mode" | Out-Null
    Start-Sleep -Seconds 4
}

$state = ((Adb @("get-state") "read device state") -join "").Trim()
if ($state -ne "device") { throw "$Serial is not ready" }
$features = (Adb @("shell", "pm", "list", "features") "read device features") -join "`n"
if ($features -notmatch 'android\.hardware\.type\.automotive' -and
        ((Adb @("shell", "getprop", "ro.kernel.qemu") "read emulator property") -join "").Trim() -ne "1") {
    throw "Display matrix changes wm size/density and must run on an emulator"
}

$originalSize = (Adb @("shell", "wm", "size") "read original size") -join "`n"
$originalDensity = (Adb @("shell", "wm", "density") "read original density") -join "`n"
$originalFont = ((Adb @("shell", "settings", "get", "system", "font_scale") "read original font scale") -join "").Trim()
$originalNight = (Adb @("shell", "cmd", "uimode", "night") "read original night mode") -join "`n"

try {
    Adb @("shell", "am", "force-stop", $KCalcPackage) "stop KCalc" | Out-Null
    $activity = ((Adb @("shell", "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            $KCalcPackage) "resolve KCalc") | Select-Object -Last 1).Trim()
    if ($activity -notmatch '/') { throw "KCalc launcher is unavailable" }
    Adb @("shell", "am", "start", "-W", "-n", $activity) "launch KCalc" | Out-Null
    Start-Sleep -Seconds 7
    $initial = Get-ProcessState

    foreach ($profile in $Profiles) {
        Apply-Profile $profile

        Adb @("shell", "am", "start", "-W", "-n", "$Manager/.MainActivity") "open manager" | Out-Null
        Start-Sleep -Seconds 2
        $managerUi = Dump-Ui "$($profile.Name)-manager"
        foreach ($required in @('text="Apps"', 'text="Search apps"', 'text="Settings"',
                'content-desc="Add Linux app"')) {
            Assert-Bounds $managerUi $required $profile.Width $profile.Height $required | Out-Null
        }

        Adb @("shell", "am", "start", "-W", "-n", $activity) "resume KCalc" | Out-Null
        Start-Sleep -Seconds 3
        $current = Get-ProcessState
        if ($current.App -ne $initial.App -or $current.Child -ne $initial.Child) {
            throw "KCalc restarted in $($profile.Name): Android $($initial.App)/$($current.App), Linux $($initial.Child)/$($current.Child)"
        }
        $kcalcUi = Dump-Ui "$($profile.Name)-kcalc"
        $viewport = Assert-Bounds $kcalcUi 'class="android\.widget\.ImageView"' `
                $profile.Width $profile.Height "KCalc viewport"
        $viewportWidth = $viewport[2] - $viewport[0]
        $viewportHeight = $viewport[3] - $viewport[1]
        if ($viewportWidth -lt [int]($profile.Width * 0.8) -or
                $viewportHeight -lt [int]($profile.Height * 0.7)) {
            throw "KCalc did not fill $($profile.Name): ${viewportWidth}x${viewportHeight}"
        }
        Write-Host "$($profile.Name) passed: $($profile.Width)x$($profile.Height) density=$($profile.Density) font=$($profile.Font) night=$($profile.Night)"
    }

    $log = (Adb @("logcat", "-d", "-s", "ArchpheneInput:V", "ArchpheneLinuxApp:I", "AndroidRuntime:E", "*:S") "read matrix logs") -join "`n"
    if ($log -match 'FATAL EXCEPTION|protocol error|InvalidGrab|UnconfiguredBuffer|native dispatch failed') {
        throw "Display matrix produced a runtime or protocol failure`n$log"
    }
    Write-Host "Release display matrix passed on $Serial with stable Android PID $($initial.App) and Linux PID $($initial.Child)."
} finally {
    & $Adb -s $Serial shell wm size reset | Out-Null
    & $Adb -s $Serial shell wm density reset | Out-Null
    if ($originalFont -and $originalFont -ne "null") {
        & $Adb -s $Serial shell settings put system font_scale $originalFont | Out-Null
    }
    $night = if ($originalNight -match '(?i)yes') { "yes" }
        elseif ($originalNight -match '(?i)no') { "no" } else { "auto" }
    & $Adb -s $Serial shell cmd uimode night $night | Out-Null
}
