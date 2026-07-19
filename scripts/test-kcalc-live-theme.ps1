param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.p0392be9c9f103a39d951c2f39c3644d2"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"

function Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$Step failed: $($output -join "`n")" }
    return @($output)
}

function Capture-Mean([string]$Name, [string]$Activity) {
    $remote = "/sdcard/Download/archphene-$Name.png"
    $local = Join-Path $Root ".tmp/archphene-$Serial-$Name.png"
    New-Item -ItemType Directory -Force (Split-Path $local) | Out-Null
    $resumed = (Adb @("shell", "dumpsys", "activity", "activities") "check $Name foreground") -join "`n"
    if ($resumed -notmatch "topResumedActivity=.*$([regex]::Escape($Package))/") {
        Adb @("shell", "am", "start", "-W", "-n", $Activity) "resume KCalc for $Name" | Out-Null
        Start-Sleep -Seconds 3
        $resumed = (Adb @("shell", "dumpsys", "activity", "activities") "recheck $Name foreground") -join "`n"
    }
    if ($resumed -notmatch "topResumedActivity=.*$([regex]::Escape($Package))/") {
        throw "KCalc was not foreground for $Name"
    }
    Adb @("shell", "screencap", "-p", $remote) "capture $Name" | Out-Null
    Adb @("pull", $remote, $local) "pull $Name" | Out-Null
    $script = 'from PIL import Image,ImageStat; import sys; i=Image.open(sys.argv[1]).convert("RGB"); w,h=i.size; print(",".join(str(x) for x in ImageStat.Stat(i.crop((0,int(h*.22),w,int(h*.95)))).mean))'
    $value = (& python -c $script $local).Trim()
    if ($LASTEXITCODE -ne 0 -or $value -notmatch '^\d') {
        throw "Could not measure $Name screenshot; Python Pillow is required"
    }
    return [double](($value -split ',')[0])
}

function Wait-ProcessState {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $pidValue = ((& $Adb -s $Serial shell pidof $Package 2>$null) -join "").Trim()
        if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) {
            throw "Could not query KCalc process"
        }
        if ($pidValue) {
            $processes = (Adb @("shell", "ps", "-A", "-o", "PID,PPID,NAME") "read process tree") -join "`n"
            $child = [regex]::Match($processes,
                    "(?m)^\s*(\d+)\s+$pidValue\s+(?:loader|libarchphene_ld\.so)\s*$").Groups[1].Value
            if ($child) { return [pscustomobject]@{ App = $pidValue; Child = $child } }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "KCalc did not start"
}

$originalNight = (Adb @("shell", "cmd", "uimode", "night") "read night mode") -join "`n"
try {
    $activity = ((Adb @("shell", "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            $Package) "resolve KCalc") | Select-Object -Last 1).Trim()
    if ($activity -notmatch '/') { throw "KCalc launcher is unavailable" }

    Adb @("shell", "am", "force-stop", $Package) "stop KCalc" | Out-Null
    Adb @("shell", "cmd", "uimode", "night", "no") "select light mode" | Out-Null
    Start-Sleep -Seconds 2
    Adb @("shell", "am", "start", "-W", "-n", $activity) "launch KCalc" | Out-Null
    Start-Sleep -Seconds 7
    $initial = Wait-ProcessState
    $light = Capture-Mean "kcalc-live-light" $activity

    Adb @("shell", "cmd", "uimode", "night", "yes") "select dark mode" | Out-Null
    Start-Sleep -Seconds 3
    $darkState = Wait-ProcessState
    $dark = Capture-Mean "kcalc-live-dark" $activity

    Adb @("shell", "cmd", "uimode", "night", "no") "return to light mode" | Out-Null
    Start-Sleep -Seconds 3
    $returnState = Wait-ProcessState
    $lightReturn = Capture-Mean "kcalc-live-light-return" $activity

    if ($initial.App -ne $darkState.App -or $initial.App -ne $returnState.App -or
            $initial.Child -ne $darkState.Child -or $initial.Child -ne $returnState.Child) {
        throw "KCalc restarted during theme changes: Android $($initial.App)/$($darkState.App)/$($returnState.App), Linux $($initial.Child)/$($darkState.Child)/$($returnState.Child)"
    }
    if ($light -lt 180 -or $dark -gt 100 -or $lightReturn -lt 180) {
        throw "KCalc palette did not follow Android: light=$light dark=$dark return=$lightReturn"
    }
    if ([Math]::Abs($light - $lightReturn) -gt 8) {
        throw "KCalc did not restore its light palette: initial=$light return=$lightReturn"
    }
    Write-Host "KCalc live theme passed on $Serial with Android PID $($initial.App) and Linux PID $($initial.Child) (light=$([Math]::Round($light,1)), dark=$([Math]::Round($dark,1)), return=$([Math]::Round($lightReturn,1)))."
} finally {
    $night = if ($originalNight -match '(?i)yes') { "yes" }
        elseif ($originalNight -match '(?i)no') { "no" } else { "auto" }
    & $Adb -s $Serial shell cmd uimode night $night | Out-Null
}
