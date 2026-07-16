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
Invoke-Adb install -r $Apk | Out-Null
Invoke-Adb logcat -c
Invoke-Adb shell am force-stop org.archphene.compositorprobe
Invoke-Adb shell am start -n org.archphene.compositorprobe/.MainActivity --ez drag_only true | Out-Null

$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 250
    $output = (& adb -s $Serial logcat -d -s "ArchpheneCompositorProbe:I" "*:S") -join [Environment]::NewLine
    if ($output.Contains("Native drag-and-drop probe passed")) {
        Write-Host "Android/Wayland drag-and-drop bridge passed on $Serial ($AndroidAbi)."
        exit 0
    }
    if ($output.Contains("Native drag-and-drop probe failed")) {
        throw ("Drag-and-drop probe reported failure:" + [Environment]::NewLine + $output)
    }
} while ([DateTime]::UtcNow -lt $deadline)

throw "Timed out waiting for drag-and-drop probe on $Serial ($AndroidAbi)."