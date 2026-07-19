param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.p241d399e14343c53b8b766e9126776aa"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"

function Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$Step failed: $($output -join "`n")" }
    return @($output)
}

function Capture-Metrics([string]$Name, [string]$Activity) {
    $remote = "/sdcard/Download/archphene-$Name.png"
    $local = Join-Path $Root ".tmp/archphene-$Serial-$Name.png"
    $previous = "$local.previous.png"
    New-Item -ItemType Directory -Force (Split-Path $local) | Out-Null
    Remove-Item -Force -ErrorAction SilentlyContinue $previous
    $resumed = (Adb @("shell", "dumpsys", "activity", "activities") "check $Name foreground") -join "`n"
    if ($resumed -notmatch "topResumedActivity=.*$([regex]::Escape($Package))/") {
        Adb @("shell", "am", "start", "-W", "-n", $Activity) "resume Mousepad for $Name" | Out-Null
        Start-Sleep -Seconds 3
        $resumed = (Adb @("shell", "dumpsys", "activity", "activities") "recheck $Name foreground") -join "`n"
    }
    if ($resumed -notmatch "topResumedActivity=.*$([regex]::Escape($Package))/") {
        throw "Mousepad was not foreground for $Name"
    }

    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Adb @("shell", "screencap", "-p", $remote) "capture $Name" | Out-Null
        Adb @("pull", $remote, $local) "pull $Name" | Out-Null
        $script = 'from PIL import Image,ImageStat; import sys; i=Image.open(sys.argv[1]).convert("RGB"); w,h=i.size; p=i.crop((0,int(h*.04),w,int(h*.95))); s=ImageStat.Stat(p); bright=sum(p.convert("L").histogram()[201:]); print(f"{s.mean[0]},{s.stddev[0]},{bright}")'
        $value = (& python -c $script $local).Trim()
        if ($LASTEXITCODE -ne 0 -or $value -notmatch '^\d') {
            throw "Could not measure $Name screenshot; Python Pillow is required"
        }
        $metrics = $value -split ','
        $mean = [double]$metrics[0]
        $deviation = [double]$metrics[1]
        $brightPixels = [int]$metrics[2]
        if ($deviation -gt 5) {
            return [pscustomobject]@{
                Mean = $mean
                Path = $local
                BrightPixels = $brightPixels
            }
        }
        $previousMean = $mean
        $previousDeviation = $deviation
        Start-Sleep -Seconds 1
    }
    throw "Mousepad render did not stabilize for $Name"
}
function Compare-RenderedApp([string]$Left, [string]$Right) {
    $script = 'from PIL import Image,ImageChops,ImageStat; import sys; a=Image.open(sys.argv[1]).convert("RGB"); b=Image.open(sys.argv[2]).convert("RGB"); assert a.size == b.size; w,h=a.size; box=(0,int(h*.04),w,int(h*.95)); print(max(ImageStat.Stat(ImageChops.difference(a.crop(box),b.crop(box))).mean))'
    $value = (& python -c $script $Left $Right).Trim()
    if ($LASTEXITCODE -ne 0 -or $value -notmatch '^\d') {
        throw "Could not compare Mousepad screenshots; Python Pillow is required"
    }
    return [double]$value
}

function Wait-ProcessState {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $pidValue = ((& $Adb -s $Serial shell pidof $Package 2>$null) -join "").Trim()
        if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) {
            throw "Could not query Mousepad process"
        }
        if ($pidValue) {
            $processes = (Adb @("shell", "ps", "-A", "-o", "PID,PPID,NAME") "read process tree") -join "`n"
            $child = [regex]::Match($processes,
                    "(?m)^\s*(\d+)\s+$pidValue\s+(?:loader|libarchphene_ld\.so)\s*$").Groups[1].Value
            if ($child) { return [pscustomobject]@{ App = $pidValue; Child = $child } }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Mousepad did not start"
}

$originalNight = (Adb @("shell", "cmd", "uimode", "night") "read night mode") -join "`n"
try {
    $activity = ((Adb @("shell", "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            $Package) "resolve Mousepad") | Select-Object -Last 1).Trim()
    if ($activity -notmatch '/') { throw "Mousepad launcher is unavailable" }

    Adb @("shell", "am", "force-stop", $Package) "stop Mousepad" | Out-Null
    Adb @("shell", "cmd", "uimode", "night", "no") "select light mode" | Out-Null
    Start-Sleep -Seconds 2
    Adb @("shell", "am", "start", "-W", "-n", $activity) "launch Mousepad" | Out-Null
    Start-Sleep -Seconds 7
    $initial = Wait-ProcessState
    $light = Capture-Metrics "mousepad-live-light" $activity

    Adb @("shell", "cmd", "uimode", "night", "yes") "select dark mode" | Out-Null
    Start-Sleep -Seconds 3
    $darkState = Wait-ProcessState
    $dark = Capture-Metrics "mousepad-live-dark" $activity

    Adb @("shell", "cmd", "uimode", "night", "no") "return to light mode" | Out-Null
    Start-Sleep -Seconds 3
    $returnState = Wait-ProcessState
    $lightReturn = Capture-Metrics "mousepad-live-light-return" $activity

    if ($initial.App -ne $darkState.App -or $initial.App -ne $returnState.App -or
            $initial.Child -ne $darkState.Child -or $initial.Child -ne $returnState.Child) {
        throw "Mousepad restarted during theme changes: Android $($initial.App)/$($darkState.App)/$($returnState.App), Linux $($initial.Child)/$($darkState.Child)/$($returnState.Child)"
    }
    if ($light.Mean -lt 180 -or $dark.Mean -gt 100 -or $lightReturn.Mean -lt 180) {
        throw "Mousepad palette did not follow Android: light=$($light.Mean) dark=$($dark.Mean) return=$($lightReturn.Mean)"
    }
    $barScript = 'from PIL import Image,ImageStat; import sys; i=Image.open(sys.argv[1]).convert("RGB"); w,h=i.size; print(max(ImageStat.Stat(i.crop((0,0,int(w*.25),int(h*.03)))).mean))'
    $darkBarMean = [double]((& python -c $barScript $dark.Path).Trim())
    if ($LASTEXITCODE -ne 0 -or $darkBarMean -gt 100) {
        throw "Android system bars did not switch to dark appearance: mean=$darkBarMean"
    }
    if ($dark.BrightPixels -lt 3500) {
        throw "Mousepad foreground text did not switch to a light foreground: bright pixels=$($dark.BrightPixels)"
    }
    if ([Math]::Abs($light.Mean - $lightReturn.Mean) -gt 8) {
        throw "Mousepad did not restore its light palette: initial=$($light.Mean) return=$($lightReturn.Mean)"
    }

    Adb @("shell", "am", "force-stop", $Package) "stop Mousepad before cold dark reference" | Out-Null
    Adb @("shell", "cmd", "uimode", "night", "yes") "select cold dark mode" | Out-Null
    Adb @("shell", "am", "start", "-W", "-n", $activity) "cold launch Mousepad in dark mode" | Out-Null
    Start-Sleep -Seconds 15
    $coldDark = Capture-Metrics "mousepad-cold-dark-reference" $activity
    $darkDelta = Compare-RenderedApp $dark.Path $coldDark.Path
    if ($darkDelta -gt 15) {
        throw "Live Mousepad dark render differs from cold dark render: delta=$darkDelta"
    }
    Write-Host "Mousepad live theme passed on $Serial with Android PID $($initial.App) and Linux PID $($initial.Child) (light=$([Math]::Round($light.Mean,1)), dark=$([Math]::Round($dark.Mean,1)), return=$([Math]::Round($lightReturn.Mean,1)), cold-dark delta=$([Math]::Round($darkDelta,1)))."
} finally {
    $night = if ($originalNight -match '(?i)yes') { "yes" }
        elseif ($originalNight -match '(?i)no') { "no" } else { "auto" }
    & $Adb -s $Serial shell cmd uimode night $night | Out-Null
}
