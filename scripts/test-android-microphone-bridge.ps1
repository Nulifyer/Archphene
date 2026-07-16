param(
    [string]$Serial = "RFCT90AEEFA",
    [string]$Package = "org.archphene.linux.p28ae847c2c818246c42d2ba69544759e",
    [string]$Activity = "org.archphene.linux.kcalc.MainActivity",
    [ValidateRange(1, 30)]
    [int]$CaptureSeconds = 5,
    [switch]$TemporarilyDisableMicrophonePrivacy
)

$ErrorActionPreference = "Stop"

function Invoke-Adb([string[]]$Arguments) {
    $output = & adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

$packageDump = (Invoke-Adb @("shell", "dumpsys", "package", $Package)) -join "`n"
if ($packageDump -notmatch "android.permission.RECORD_AUDIO") {
    throw "$Package does not declare RECORD_AUDIO; rebuild it with audio-input enabled."
}
if ($packageDump -notmatch "android.permission.RECORD_AUDIO: granted=true") {
    throw "Grant microphone access to $Package through its Android permission dialog first."
}

$audioDump = (Invoke-Adb @("shell", "dumpsys", "audio")) -join "`n"
$privacyEnabled = $audioDump -match "mic mute .*from system=true"
$privacyChanged = $false
$privateRoot = "/data/data/$Package"
$capture = "$privateRoot/cache/archphene-microphone-test.raw"

try {
    if ($privacyEnabled) {
        if (-not $TemporarilyDisableMicrophonePrivacy) {
            throw "The device-wide microphone privacy switch is enabled. Use " +
                    "-TemporarilyDisableMicrophonePrivacy to disable and restore it for this test."
        }
        Invoke-Adb @("shell", "cmd", "sensor_privacy", "disable", "0", "microphone") |
                Out-Null
        $privacyChanged = $true
    }

    Invoke-Adb @("logcat", "-c") | Out-Null
    Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/$Activity") | Out-Null

    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    $log = ""
    do {
        Start-Sleep -Milliseconds 500
        $log = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
                "ArchpheneAudio:I", "ArchpheneBridge:I", "AndroidRuntime:E", "*:S")) -join "`n"
        if ($log -match "Android AAudio microphone capture started") { break }
    } while ([DateTime]::UtcNow -lt $deadline)

    if ($log -match "FATAL EXCEPTION") { throw "Audio wrapper crashed.`n$log" }
    if ($log -notmatch "Private PulseAudio microphone bridge ready") {
        throw "The private microphone bridge did not start.`n$log"
    }
    if ($log -notmatch "Linux microphone stream attached") {
        throw "No Linux stream attached to archphene_input.`n$log"
    }
    if ($log -notmatch "Android AAudio microphone capture started") {
        throw "AAudio capture did not start after permission was granted.`n$log"
    }

    $client = "$privateRoot/files/linux-runtime/lib/libarchphene_pulse_probe.so"
    $libraries = "$privateRoot/files/linux-runtime/lib"
    $server = "unix:$privateRoot/cache/pa/s"
    $remote = "LD_LIBRARY_PATH=$libraries PULSE_SERVER=$server timeout " +
            "$CaptureSeconds $client --record --raw --device=archphene_input " +
            "--rate=48000 --channels=1 > $capture"
    & adb -s $Serial shell "run-as $Package sh -c '$remote'" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 124) {
        throw "The Pulse capture client exited unexpectedly with code $LASTEXITCODE."
    }

    $values = Invoke-Adb @("shell", "run-as", $Package, "od", "-An", "-tu1", "-v",
            "cache/archphene-microphone-test.raw")
    $bytes = @($values -split "\s+" | Where-Object { $_ })
    $nonzero = @($bytes | Where-Object { $_ -ne "0" })
    $minimumBytes = [int](48000 * 2 * $CaptureSeconds * 0.8)
    if ($bytes.Count -lt $minimumBytes) {
        throw "Capture was truncated: $($bytes.Count) bytes, expected at least $minimumBytes."
    }
    if ($nonzero.Count -eq 0) {
        throw "Capture contains only silence; verify the device microphone and privacy state."
    }

    $summary = "Android microphone bridge passed on ${Serial}: " +
            "$($bytes.Count) bytes, $($nonzero.Count) nonzero."
    Write-Host $summary
} finally {
    & adb -s $Serial shell am force-stop $Package 2>&1 | Out-Null
    if ($privacyChanged) {
        & adb -s $Serial shell cmd sensor_privacy enable 0 microphone 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not restore the device-wide microphone privacy switch."
        }
    }
}
