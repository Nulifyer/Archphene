param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64",
    [string]$Serial = "emulator-5554"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Apk = Join-Path $Root "prototypes/native-compositor-probe/out-$AndroidAbi/archphene-compositor-probe.apk"
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "Prebuilt probe APK missing. Build it in Podman first: ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi $AndroidAbi"
}

function Invoke-Adb([Parameter(ValueFromRemainingArguments)][string[]]$Arguments) {
    & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

Invoke-Adb get-state | Out-Null
Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Invoke-Adb shell wm dismiss-keyguard | Out-Null
Invoke-Adb logcat -c
& (Join-Path $PSScriptRoot "install-apk.ps1") -Apk $Apk -Serial $Serial -Package org.archphene.compositorprobe
Invoke-Adb shell wm dismiss-keyguard | Out-Null

$keySent = $false
$tapSent = $false
$scrollSent = $false
$touchSent = $false
$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    Start-Sleep -Milliseconds 500
    $output = (& adb -s $Serial logcat -d -s "ArchpheneCompositorProbe:I" "*:S") -join [Environment]::NewLine
    if (-not $keySent -and $output.Contains("keyboard target ready")) {
        Invoke-Adb shell input keyevent KEYCODE_DPAD_LEFT | Out-Null
        $keySent = $true
    }
    if (-not $tapSent) {
        $target = [regex]::Match($output, 'pointer target screen=([0-9]+),([0-9]+)')
        if ($target.Success) {
            Invoke-Adb shell input tap $target.Groups[1].Value $target.Groups[2].Value | Out-Null
            $tapSent = $true
        }
    }
    if (-not $scrollSent) {
        $target = [regex]::Match($output, 'scroll target ready screen=([0-9]+),([0-9]+)')
        if ($target.Success) {
            Invoke-Adb shell input mouse scroll $target.Groups[1].Value $target.Groups[2].Value --axis "VSCROLL,2" --axis "HSCROLL,1" | Out-Null
            $scrollSent = $true
        }
    }
    if (-not $touchSent) {
        $target = [regex]::Match($output, 'touch target screen=([0-9]+),([0-9]+)')
        if ($target.Success) {
            $startX = [int]$target.Groups[1].Value
            $startY = [int]$target.Groups[2].Value
            Invoke-Adb shell input swipe $startX $startY ($startX + 20) $startY 300 | Out-Null
            $touchSent = $true
        }
    }
    if ($output.Contains("registry, Android bitmap, xdg toplevel, keyboard input, damage-batched buffer scale/transform, viewporter/fractional scaling, Choreographer-paced frames, MotionEvent pointer/wheel/touch input, cursor surfaces, pointer gestures, nested popup grabs, synchronized subsurface trees, committed parent geometry, demand-driven clipboard, and Android InputConnection UTF-8 text-input v3 lifecycle complete")) {
        Write-Host "Native compositor Android MotionEvent probe passed on $Serial ($AndroidAbi)."
        exit 0
    }
    if ($output.Contains("Native compositor probe failed")) {
        throw ("Native compositor probe reported failure:" + [Environment]::NewLine + $output)
    }
} while ([DateTime]::UtcNow -lt $deadline)

throw "Timed out waiting for native compositor result on $Serial ($AndroidAbi)."
