param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.p2bb8d769a2318af9bf9b60a9f8b7ec5f",
    [string]$Activity = "org.archphene.linux.kcalc.MainActivity",
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[0-9a-f]{64}$")]
    [string]$RuntimePackId,
    [ValidateSet("Grant", "Deny", "Both")]
    [string]$PermissionAction = "Both",
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BundledAdb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Adb = if (Test-Path -LiteralPath $BundledAdb) { $BundledAdb } else { "adb" }
$ManagerPackage = "org.archpheneos.manager"
$ManagerActivity = "$ManagerPackage/.MainActivity"

function Invoke-Adb([string[]]$Arguments, [string]$Step, [switch]$AllowFailure) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) {
        throw ("{0} failed with exit code {1}: {2}" -f $Step, $LASTEXITCODE,
                ($output -join [Environment]::NewLine))
    }
    return ($output -join [Environment]::NewLine)
}

function Wait-For([scriptblock]$Condition, [int]$TimeoutSeconds, [string]$Failure) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $value = & $Condition
        if ($value) { return $value }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw $Failure
}

function Get-Ui {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/archphene-generated-camera.xml") "dump permission UI" -AllowFailure | Out-Null
    $xml = Invoke-Adb @("shell", "cat", "/sdcard/archphene-generated-camera.xml") "read permission UI" -AllowFailure
    if ($xml -notmatch "<hierarchy") { return $null }
    try { return [xml]$xml } catch { return $null }
}

function Wait-PermissionPrompt {
    return Wait-For {
        $ui = Get-Ui
        if ($null -eq $ui) { return $null }
        $node = $ui.SelectNodes("//node") | Where-Object {
            $_.'resource-id' -match "permission_(allow_.+|deny)_button$"
        } | Select-Object -First 1
        if ($null -ne $node) { return $ui }
        return $null
    } $StartupTimeoutSeconds "Generated app did not open the Android camera permission prompt"
}

function Select-Permission([xml]$Ui, [string]$Action) {
    $pattern = if ($Action -eq "Grant") {
        "permission_allow_(foreground_only|one_time)_button$"
    } else {
        "permission_deny_button$"
    }
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        if ($attempt -gt 0) { $Ui = Get-Ui }
        $node = $Ui.SelectNodes("//node") | Where-Object {
            $_.'resource-id' -match $pattern
        } | Select-Object -First 1
        if ($null -eq $node) { return }
        $bounds = [regex]::Matches($node.bounds, "\d+") |
                ForEach-Object { [int]$_.Value }
        if ($bounds.Count -ne 4) { throw "Permission action bounds are invalid" }
        Start-Sleep -Milliseconds 500
        Invoke-Adb @("shell", "input", "tap",
                [string][int](($bounds[0] + $bounds[2]) / 2),
                [string][int](($bounds[1] + $bounds[3]) / 2)) "select $Action permission action" | Out-Null
        Start-Sleep -Seconds 1
        $after = Get-Ui
        $remaining = $after.SelectNodes("//node") | Where-Object {
            $_.'resource-id' -match "permission_(allow_.+|deny)_button$"
        } | Select-Object -First 1
        if ($null -eq $remaining) { return }
    }
    throw "Permission action $Action did not close the Android prompt"
}

function Get-Logs {
    return Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
            "ArchpheneCapabilities:I", "ArchpheneCamera:I",
            "ArchpheneRuntime:I", "AndroidRuntime:E", "*:S") "read generated camera logs"
}

function Read-AppFile([string]$Path) {
    return Invoke-Adb @("shell", "run-as", $Package, "cat", $Path) "read $Path" -AllowFailure
}

function Bind-RuntimePack {
    Invoke-Adb @("shell", "am", "force-stop", $ManagerPackage) "stop manager before binding" | Out-Null
    Invoke-Adb @("shell", "am", "start", "-n", $ManagerActivity,
            "--ez", "archphene_test_package_runtime", "true",
            "--es", "archphene_test_bind_pack", $RuntimePackId,
            "--es", "archphene_test_bind_package", $Package) "bind generated app runtime pack" | Out-Null
    Start-Sleep -Seconds 4
}

function Assert-Cleanup([string]$LinuxUid) {
    $ui = Get-Ui
    if ($null -ne $ui -and ($ui.SelectNodes("//node") | Where-Object {
            $_.'resource-id' -match "permission_(allow_.+|deny)_button$"
        })) {
        Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") "dismiss permission UI during cleanup" | Out-Null
        Start-Sleep -Milliseconds 500
    }
    $logsBeforeStop = Get-Logs
    $leasePattern = [regex]::Escape("Acquired runtime pack lease $RuntimePackId for $Package")
    $leaseAcquired = $logsBeforeStop -match $leasePattern
    Invoke-Adb @("shell", "am", "force-stop", $Package) "stop generated camera app" | Out-Null
    $logs = if ($leaseAcquired) {
        Wait-For {
            $value = Get-Logs
            if ($value -match "(Released runtime pack lease|Runtime process died; released pack lease) " +
                    [regex]::Escape("$RuntimePackId for $Package")) {
                return $value
            }
            return $null
        } 30 "Runtime-pack lease was not released after generated app exit"
    } else {
        Get-Logs
    }
    if ($LinuxUid) {
        Wait-For {
            $processes = Invoke-Adb @("shell", "ps", "-A") "inspect generated app processes"
            if ($processes -notmatch "(?m)^$([regex]::Escape($LinuxUid))\s") {
                return "clean"
            }
            return $null
        } 10 "Generated app processes survived force-stop: $LinuxUid" | Out-Null
    }
    if ($logs -match "FATAL EXCEPTION") {
        throw "Generated app crashed during cleanup: $logs"
    }
}

function Invoke-Case([ValidateSet("Grant", "Deny")][string]$Action,
        [string]$LinuxUid) {
    Invoke-Adb @("shell", "am", "force-stop", $Package) "stop generated app fixture" | Out-Null
    Invoke-Adb @("shell", "pm", "clear", $Package) "reset generated app fixture" | Out-Null
    Bind-RuntimePack
    Invoke-Adb @("logcat", "-c") "clear generated camera logs" | Out-Null

    $started = $false
    $primaryFailure = $null
    try {
        Invoke-Adb @("shell", "am", "start", "-n", "$Package/$Activity",
                "--ez", "archphene_test_media_debug", "true") "launch generated camera app" | Out-Null
        $started = $true
        $ui = Wait-PermissionPrompt
        Write-Host "Generated camera app opened the Android permission prompt ($Action)"
        Wait-For {
            $logs = Get-Logs
            if ($logs -match "Requested Android camera permission") { return $logs }
            return $null
        } 5 "Linux camera portal did not log its Android permission request" | Out-Null
        Select-Permission $ui $Action

        if ($Action -eq "Grant") {
            Wait-For {
                $logs = Get-Logs
                $pipewire = Read-AppFile "cache/pipewire-debug.log"
                $gstreamer = Read-AppFile "cache/gstreamer-debug.log"
                if ($logs -match "Android camera permission granted" -and
                        $logs -match "Starting Android camera I420 stream" -and
                        $pipewire -match "Archphene camera link=\d+->\d+" -and
                        $gstreamer -match "pts [1-9]\d+" -and
                        $gstreamer -notmatch "pts 18446744073709551615" -and
                        $gstreamer -notmatch "Failed to change camerabin state") {
                    return "$logs$([Environment]::NewLine)$pipewire$([Environment]::NewLine)$gstreamer"
                }
                return $null
            } 45 "Generated app did not consume timestamped PipeWire camera frames" | Out-Null
        } else {
            $deniedLogs = Wait-For {
                $logs = Get-Logs
                if ($logs -match "Android camera permission denied") { return $logs }
                return $null
            } 15 "Generated app did not receive the Android camera denial"
            Start-Sleep -Seconds 3
            $uiAfter = Get-Ui
            if ($null -ne $uiAfter -and
                    ($uiAfter.SelectNodes("//node") | Where-Object {
                        $_.'resource-id' -match "permission_(allow_.+|deny)_button$"
                    })) {
                throw "Camera permission denial unexpectedly reprompted"
            }
            $pipewire = Read-AppFile "cache/pipewire-debug.log"
            if ($pipewire -match "Archphene camera link=" -or
                    $deniedLogs -match "Starting Android camera I420 stream") {
                throw "Camera stream started after Android permission denial"
            }
        }
    } catch {
        $primaryFailure = $_
        throw
    } finally {
        if ($started) {
            try {
                Assert-Cleanup $LinuxUid
            } catch {
                if ($null -eq $primaryFailure) { throw }
                Write-Warning "Cleanup after camera test failure also failed: $($_.Exception.Message)"
            }
        }
    }
    Write-Host "Generated camera app $Action path passed on $Serial"
}

Invoke-Adb @("wait-for-device") "wait for Android device" | Out-Null
$packageDump = Invoke-Adb @("shell", "dumpsys", "package", $Package) "inspect generated camera package"
if ($packageDump -notmatch "android.permission.CAMERA") {
    throw "Generated app does not declare android.permission.CAMERA"
}
$uidOutput = Invoke-Adb @("shell", "cmd", "package", "list", "packages", "-U",
        $Package) "read generated app UID"
$uidMatch = [regex]::Match($uidOutput, "uid:(\d+)")
$linuxUid = ""
if ($uidMatch.Success) {
    $uid = [int]$uidMatch.Groups[1].Value
    if ($uid -ge 10000 -and $uid -lt 20000) { $linuxUid = "u0_a$($uid - 10000)" }
}

$actions = if ($PermissionAction -eq "Both") { @("Grant", "Deny") } else {
    @($PermissionAction)
}
foreach ($action in $actions) { Invoke-Case $action $linuxUid }
Write-Host ("Generated unmodified camera consumer passed on {0}: {1} permission paths, PipeWire frames, timestamps, and cleanup." -f $Serial, ($actions -join ", "))
