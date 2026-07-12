$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.kcalc"

function Get-KCalcChildPid {
    $Line = & $Adb shell ps -A -o PID,PPID,NAME | Select-String "libarchphene_ld.so" | Select-Object -First 1
    if (-not $Line) { return $null }
    return ([regex]::Match($Line.ToString().Trim(), "^\d+")).Value
}

& $Adb shell settings put system accelerometer_rotation 0 | Out-Null
try {
    & $Adb shell settings put system user_rotation 0 | Out-Null
    & $Adb shell am force-stop $Package | Out-Null
    & $Adb shell am start -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 7
    $PortraitPid = Get-KCalcChildPid

    & $Adb shell settings put system user_rotation 1 | Out-Null
    Start-Sleep -Seconds 5
    $LandscapePid = Get-KCalcChildPid

    & $Adb shell settings put system user_rotation 0 | Out-Null
    Start-Sleep -Seconds 5
    $RestoredPid = Get-KCalcChildPid

    if (-not $PortraitPid -or $PortraitPid -ne $LandscapePid -or $PortraitPid -ne $RestoredPid) {
        throw "KCalc process changed during resize: $PortraitPid -> $LandscapePid -> $RestoredPid"
    }

    & $Adb shell input keycombination 57 34 | Out-Null
    Start-Sleep -Milliseconds 400
    & $Adb shell input keyevent 20 | Out-Null
    & $Adb shell input keyevent 66 | Out-Null
    Start-Sleep -Seconds 4

    $Report = (& $Adb shell run-as $Package cat files/kcalc-report.txt) -join "`n"
    $Configures = [regex]::Matches($Report, "xdg_toplevel\.configure width=(\d+) height=(\d+)")
    if ($Configures.Count -lt 3) {
        throw "Expected initial, landscape, and restored xdg_toplevel configures"
    }
    $Sizes = $Configures | ForEach-Object { "$($_.Groups[1].Value)x$($_.Groups[2].Value)" }
    if (-not ($Sizes | Where-Object { ([int]($_ -split 'x')[0]) -gt ([int]($_ -split 'x')[1]) })) {
        throw "No landscape configure found: $($Sizes -join ', ')"
    }
    if ($Sizes[0] -ne $Sizes[$Sizes.Count - 1]) {
        throw "Portrait geometry was not restored: $($Sizes -join ' -> ')"
    }
    $Acks = ([regex]::Matches($Report, "xdg_surface\.ack_configure serial=")).Count
    if ($Acks -lt 3) {
        throw "Expected at least three configure acknowledgements, found $Acks"
    }

    Write-Host "KCalc live resize passed with PID ${PortraitPid}: $($Sizes -join ' -> ')"
}
finally {
    & $Adb shell settings put system user_rotation 0 | Out-Null
}
