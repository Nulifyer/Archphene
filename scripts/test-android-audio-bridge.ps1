param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.p28ae847c2c818246c42d2ba69544759e",
    [string]$Activity = "org.archphene.linux.kcalc.MainActivity"
)

$ErrorActionPreference = "Stop"

function Invoke-Adb([string[]]$Arguments) {
    $output = & adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

Invoke-Adb @("logcat", "-c") | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/$Activity") | Out-Null

$deadline = [DateTime]::UtcNow.AddSeconds(20)
$log = ""
do {
    Start-Sleep -Milliseconds 500
    $log = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
            "ArchpheneAudio:I", "ArchpheneLinuxApp:I", "AndroidRuntime:E", "*:S")) -join "`n"
    if ($log -match "Private (AAudio|OpenSL ES) PulseAudio server ready" -and
            $log -match "Client authenticated anonymously" -and
            $log -match 'application.name = "PulseAudio Volume Control"') {
        break
    }
} while ([DateTime]::UtcNow -lt $deadline)

if ($log -match "FATAL EXCEPTION") { throw "Audio wrapper crashed.`n$log" }
if ($log -notmatch "Private AAudio PulseAudio server ready") {
    throw "Android AAudio sink did not start.`n$log"
}
if ($log -notmatch "Client authenticated anonymously") {
    throw "Linux Pulse client did not authenticate.`n$log"
}
if ($log -notmatch 'application.name = "PulseAudio Volume Control"') {
    throw "pavucontrol did not create its Pulse stream.`n$log"
}

$processId = (Invoke-Adb @("shell", "pidof", $Package) | Select-Object -First 1).Trim()
if (-not $processId) { throw "Audio wrapper exited after connecting.`n$log" }

Write-Host "Android audio bridge passed on $Serial (PID $processId)."
