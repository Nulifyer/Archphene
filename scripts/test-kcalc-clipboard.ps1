$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.kcalc"
$Seed = "ARCHPHENE_ANDROID_TO_WAYLAND"

& $Adb shell am force-stop $Package | Out-Null
& $Adb shell am start -n "$Package/.MainActivity" `
    --ez archphene_qt_clipboard_probe true `
    --es archphene_android_clipboard_text $Seed | Out-Null

$Deadline = [DateTime]::UtcNow.AddSeconds(15)
do {
    Start-Sleep -Milliseconds 500
    $Report = (& $Adb shell run-as $Package cat files/kcalc-report.txt 2>$null) -join "`n"
} while ($Report -notmatch "ARCHPHENE_INBOUND_FINAL=" -and [DateTime]::UtcNow -lt $Deadline)

$Expected = @(
    "Exit code: 0"
    "ARCHPHENE_INBOUND_FINAL=$Seed"
    "android->wayland clipboard bytes=$($Seed.Length)"
    "wayland->android clipboard bytes=25"
)

foreach ($Evidence in $Expected) {
    if (-not $Report.Contains($Evidence)) {
        throw "Clipboard bridge evidence missing: $Evidence"
    }
}

$OfferCount = ([regex]::Matches($Report, "wl_data_device\.data_offer id=")).Count
if ($OfferCount -ne 1) {
    throw "Expected one Android clipboard offer, found $OfferCount"
}

Write-Host "KCalc clipboard bridge passed in both directions (one offer, no feedback loop)."
