param(
    [string]$Serial = "emulator-5554",
    [string]$SourcePackage = "",
    [string]$TargetPackage = "org.archphene.linux.mousepad",
    [string]$TargetLogTag = "ArchpheneMousepad",
    [int]$TimeoutSeconds = 35
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code ${LASTEXITCODE}: $($output -join "`n")"
    }
    return $output
}

function Wait-Log([string]$Expected, [string]$Failure) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $log = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
                "ArchpheneDocuments", $TargetLogTag, "AndroidRuntime") "read logs") -join "`n"
        if ($log.Contains($Expected)) { return $log }
        if ($log.Contains("GUI document broker failed") -or
                $log.Contains("Document conflict probe failed") -or
                $log.Contains("FATAL EXCEPTION")) {
            throw "$Failure`n$log"
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$Failure`n$log"
}

Invoke-Adb @("wait-for-device") "wait for device" | Out-Null
Invoke-Adb @("shell", "pm", "path", $Manager) "find manager" | Out-Null
Invoke-Adb @("shell", "pm", "path", $TargetPackage) "find target wrapper" | Out-Null
$TargetComponent = Invoke-Adb @("shell", "cmd", "package", "resolve-activity", "--brief",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
        $TargetPackage) "resolve target launcher" |
        Where-Object { $_ -match "^$([regex]::Escape($TargetPackage))/" } |
        Select-Object -Last 1
if (-not $TargetComponent) { throw "Target wrapper has no launcher Activity" }

if (-not $SourcePackage) {
    $rows = (Invoke-Adb @("shell", "content", "query", "--uri",
            "content://org.archpheneos.manager.documents/document/apps/children") `
            "query Archphene Apps root") -join "`n"
    if ($rows -notmatch 'document_id=app/(org\.archphene\.linux\.p[a-f0-9]{32})/home') {
        throw "No manager-brokered generated GUI wrapper is installed"
    }
    $SourcePackage = $Matches[1]
}

Invoke-Adb @("logcat", "-c") "clear logs" | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $Manager) "stop manager" | Out-Null
Invoke-Adb @("shell", "am", "start", "-n", "$Manager/.MainActivity", "--es",
        "archphene_test_gui_documents", $SourcePackage) "start manager CRUD probe" | Out-Null
Wait-Log "GUI document broker passed package=$SourcePackage" `
        "Manager GUI document CRUD probe failed" | Out-Null

Invoke-Adb @("logcat", "-c") "clear logs" | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $TargetPackage) "stop target wrapper" | Out-Null
Invoke-Adb @("shell", "am", "start", "-n", $TargetComponent, "--es",
        "archphene_test_private_broker_authority", "$SourcePackage.documents") `
        "start private-provider denial probe" | Out-Null
$denial = Wait-Log "Private GUI home provider" "Private provider denial probe failed"
if (-not ($denial.Contains("denied unauthorized caller") -or
        $denial.Contains("unavailable to unauthorized caller"))) {
    throw "Private provider did not report a denied or invisible caller`n$denial"
}

Invoke-Adb @("logcat", "-c") "clear logs" | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $TargetPackage) "stop target wrapper" | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $Manager) "stop manager" | Out-Null
Invoke-Adb @("shell", "am", "start", "-n", "$Manager/.MainActivity", "--es",
        "archphene_test_document_session_source", $SourcePackage, "--es",
        "archphene_test_document_session_target", $TargetPackage) `
        "start multi-document conflict probe" | Out-Null
Wait-Log "Running document restart probe passed documents=2" `
        "Running multi-document restart probe failed" | Out-Null

Write-Host "GUI document broker passed on ${Serial}: manager CRUD, private-provider denial, running-app restart, same-name import, conflict preservation, and writeback."
