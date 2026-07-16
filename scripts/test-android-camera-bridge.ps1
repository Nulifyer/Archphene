param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64",
    [string]$Serial = "emulator-5554",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.cameraprobe"
$Activity = "org.archphene.bridge.CameraProbeActivity"
$Apk = Join-Path $Root "prototypes/camera-capability-probe/out-$AndroidAbi/archphene-camera-probe.apk"

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code ${LASTEXITCODE}: $($output -join "`n")"
    }
    return $output
}

function Start-Probe {
    Invoke-Adb @("shell", "am", "force-stop", $Package) "stop camera probe" | Out-Null
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/$Activity") `
            "start camera probe" | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $value = & $Adb -s $Serial shell run-as $Package cat files/camera-broker-name 2>$null
        if ($LASTEXITCODE -eq 0 -and $value) { return ($value -join "").Trim() }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    $logs = & $Adb -s $Serial logcat -d -v brief -s ArchpheneCapabilities AndroidRuntime
    throw "Camera broker did not start: $($logs -join "`n")"
}

function Native-Path {
    $dump = (Invoke-Adb @("shell", "dumpsys", "package", $Package) `
            "read camera probe package") -join "`n"
    $native = [regex]::Match($dump, "legacyNativeLibraryDir=(\S+)").Groups[1].Value
    if (-not $native) { throw "Camera probe native library directory is unavailable" }
    $nativeSubdirectory = if ($AndroidAbi -eq "arm64-v8a") { "arm64" } else { "x86_64" }
    return "$native/$nativeSubdirectory/libarchphene_camera_probe.so"
}

function Invoke-Probe([string]$Socket, [string[]]$Arguments, [switch]$AllowFailure) {
    $native = Native-Path
    $output = & $Adb -s $Serial shell run-as $Package $native `
            --socket "@$Socket" @Arguments 2>&1
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) {
        throw "Camera capability request failed: $($output -join "`n")"
    }
    return ($output -join "`n").Trim()
}

function Select-PermissionAction([string[]]$Labels) {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/archphene-camera.xml") `
            "dump camera permission UI" | Out-Null
    [xml]$ui = (Invoke-Adb @("shell", "cat", "/sdcard/archphene-camera.xml") `
            "read camera permission UI") -join ""
    $action = $ui.SelectNodes("//node") | Where-Object {
        $_.text -in $Labels
    } | Select-Object -First 1
    if ($null -eq $action -and $Labels -contains "Deny") {
        $action = $ui.SelectNodes("//node") | Where-Object {
            $_.'resource-id' -match "permission_deny_button$"
        } | Select-Object -First 1
    }
    if ($null -eq $action) {
        throw "Camera permission prompt has none of: $($Labels -join ', ')"
    }
    $bounds = [regex]::Matches($action.bounds, "\d+") | ForEach-Object { [int]$_.Value }
    Invoke-Adb @("shell", "input", "tap",
            [string][int](($bounds[0] + $bounds[2]) / 2),
            [string][int](($bounds[1] + $bounds[3]) / 2)) `
            "select camera permission action" | Out-Null
    Start-Sleep -Milliseconds 750
}

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "Camera probe APK is missing: $Apk"
}
Invoke-Adb @("wait-for-device") "wait for camera device" | Out-Null
Invoke-Adb @("install", "-r", $Apk) "install camera probe" | Out-Null
Invoke-Adb @("shell", "pm", "clear", $Package) "clear camera probe" | Out-Null
Invoke-Adb @("logcat", "-c") "clear camera logs" | Out-Null
$socket = Start-Probe

$initial = Invoke-Probe $socket @("check-camera") -AllowFailure
if ($initial -ne "ERROR`tPERMISSION_NOT_REQUESTED") {
    throw "Unexpected initial camera state: $initial"
}
$beforeGrant = Invoke-Probe $socket @("capture-camera-jpeg", "files/before.jpg",
        "1280", "720", "back") -AllowFailure
if ($beforeGrant -ne "ERROR`tPERMISSION_NOT_REQUESTED") {
    throw "Camera capture bypassed Android permission: $beforeGrant"
}
$requested = Invoke-Probe $socket @("request-camera") -AllowFailure
if ($requested -ne "ERROR`tPERMISSION_REQUESTED") {
    throw "Camera request did not launch Android permission UI: $requested"
}
Start-Sleep -Milliseconds 500
Select-PermissionAction @("While using the app", "Only this time", "Allow")
$granted = Invoke-Probe $socket @("check-camera")
if ($granted -ne "OK") { throw "Camera permission was not granted: $granted" }

$capture = Invoke-Probe $socket @("capture-camera-jpeg", "files/camera-test.jpg",
        "1280", "720", "back")
if ($capture -notmatch '^OK\t(\d+)\t(\d+)\t(\d+)$') {
    throw "Camera capture metadata is invalid: $capture"
}
$captureWidth = [int]$Matches[1]
$captureHeight = [int]$Matches[2]
$reportedBytes = [int64]$Matches[3]
$actualBytes = [int64]((Invoke-Adb @("shell", "run-as", $Package, "stat", "-c", "%s",
        "files/camera-test.jpg") "measure camera JPEG") -join "").Trim()
if ($reportedBytes -ne $actualBytes -or $actualBytes -lt 1024) {
    throw "Camera JPEG byte count is invalid: reported=$reportedBytes actual=$actualBytes"
}
$header = ((Invoke-Adb @("shell", "run-as", $Package, "od", "-An", "-tx1", "-N2",
        "files/camera-test.jpg") "read camera JPEG header") -join " ").Trim()
if ($header -notmatch '^ff\s+d8$') { throw "Camera output is not a JPEG: $header" }
$invalid = Invoke-Probe $socket @("capture-camera-jpeg", "files/invalid.jpg",
        "0", "720", "back") -AllowFailure
if ($invalid -ne "invalid camera capture arguments") {
    throw "Invalid camera dimensions were not rejected by the native API: $invalid"
}

Invoke-Adb @("shell", "pm", "clear", $Package) "reset camera denial fixture" | Out-Null
$socket = Start-Probe
$requested = Invoke-Probe $socket @("request-camera") -AllowFailure
if ($requested -ne "ERROR`tPERMISSION_REQUESTED") {
    throw "Camera denial fixture did not request permission: $requested"
}
Start-Sleep -Milliseconds 500
Select-PermissionAction @("Don't allow", "Deny")
$denied = Invoke-Probe $socket @("check-camera") -AllowFailure
if ($denied -ne "ERROR`tPERMISSION_DENIED") {
    throw "Camera denial was not persisted: $denied"
}
$noReprompt = Invoke-Probe $socket @("request-camera") -AllowFailure
if ($noReprompt -ne "ERROR`tPERMISSION_DENIED") {
    throw "Camera permission denial unexpectedly reprompted: $noReprompt"
}

$logs = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
        "ArchpheneCapabilities:I", "AndroidRuntime:E", "*:S") "read camera logs") -join "`n"
if ($logs -match "FATAL EXCEPTION") { throw "Camera probe crashed: $logs" }
if ($logs -notmatch "Captured Android camera JPEG") {
    throw "Camera broker did not log a completed capture: $logs"
}
Write-Host ("Android camera bridge passed on $Serial ($AndroidAbi): " +
        "${captureWidth}x${captureHeight}, $actualBytes bytes; grant and denial validated.")
