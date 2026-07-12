param(
    [string]$Serial = "emulator-5554",
    [string]$Name = "archphene-android-workflow.txt",
    [string]$Marker = "archphenedocsync7419"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.linux.mousepad"
$Local = Join-Path $Root "artifacts/$Name"
$Remote = "/sdcard/Download/$Name"
$Imported = "files/linux-home/Documents/Android/$Name"
$EncodedRaw = [Uri]::EscapeDataString("raw:/storage/emulated/0/Download/$Name")
$SourceUri = "content://com.android.providers.downloads.documents/document/$EncodedRaw"
$ProviderId = [Uri]::EscapeDataString("home/Documents/Android/$Name")
$ProviderUri = "content://$Package.documents/document/$ProviderId"

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code ${LASTEXITCODE}: $($output -join "`n")"
    }
    return $output
}

function Wait-For([scriptblock]$Condition, [string]$Failure, [int]$Seconds = 15) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    throw $Failure
}

function Dump-Ui([string]$Name) {
    $remoteXml = "/sdcard/$Name.xml"
    $localXml = Join-Path $Root "artifacts/$Name.xml"
    Invoke-Adb @("shell", "uiautomator", "dump", $remoteXml) "dump $Name UI" | Out-Null
    Invoke-Adb @("pull", $remoteXml, $localXml) "pull $Name UI" | Out-Null
    return [xml](Get-Content -LiteralPath $localXml -Raw)
}

function Tap-UiNode([xml]$Ui, [string]$Attribute, [string]$Value, [string]$Step) {
    $target = $null
    foreach ($candidate in $Ui.SelectNodes("//*[@$Attribute='$Value']")) {
        if ($candidate.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
            $target = $candidate
        }
    }
    if (-not $target) { throw "$Step has no bounded match: $Attribute=$Value" }
    $null = $target.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'
    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    Invoke-Adb @("shell", "input", "tap", [int]$x, [int]$y) $Step | Out-Null
}
New-Item -ItemType Directory -Force -Path (Split-Path $Local) | Out-Null
[IO.File]::WriteAllText($Local, "Android workflow original`nsecond source line`n",
        (New-Object Text.UTF8Encoding($false)))
Invoke-Adb @("push", $Local, $Remote) "stage Downloads test document" | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $Package) "stop Mousepad" | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/org.archphene.bridge.DocumentOpenActivity") "open Android document portal" | Out-Null
Start-Sleep -Seconds 2

$ui = Dump-Ui "mousepad-document-picker-recent"
Tap-UiNode $ui "content-desc" "Show roots" "open document roots"
Start-Sleep -Milliseconds 700
$ui = Dump-Ui "mousepad-document-picker-roots"
Tap-UiNode $ui "text" "Downloads" "open Downloads"
Start-Sleep -Seconds 1
$ui = Dump-Ui "mousepad-document-picker-downloads"
Tap-UiNode $ui "text" $Name "select Downloads document"

Wait-For {
    $result = & $Adb -s $Serial shell run-as $Package test -f $Imported 2>$null
    $LASTEXITCODE -eq 0
} "Mousepad did not import the Android document" 30
Start-Sleep -Seconds 5

Invoke-Adb @("shell", "input", "keycombination", "113", "123") "move to document end" | Out-Null
Invoke-Adb @("shell", "input", "keyevent", "66") "insert newline" | Out-Null
Invoke-Adb @("shell", "input", "text", $Marker) "type workflow marker" | Out-Null
Invoke-Adb @("shell", "input", "keycombination", "113", "47") "save Mousepad document" | Out-Null
Wait-For {
    $text = (& $Adb -s $Serial shell cat $Remote 2>$null) -join "`n"
    $text.Contains($Marker)
} "Mousepad save did not immediately write back to Downloads"
Invoke-Adb @("shell", "input", "keyevent", "3") "background Mousepad" | Out-Null
Start-Sleep -Seconds 1

Invoke-Adb @("shell", "am", "force-stop", $Package) "cold-stop Mousepad" | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-a", "android.intent.action.EDIT",
        "-c", "android.intent.category.DEFAULT", "-t", "text/plain", "-d", $SourceUri, $Package) `
        "reopen persisted Android document" | Out-Null
Wait-For {
    $text = (& $Adb -s $Serial shell run-as $Package cat $Imported 2>$null) -join "`n"
    $text.Contains($Marker)
} "Cold-reopened Mousepad document did not contain the saved marker"

$providerText = (Invoke-Adb @("shell", "content", "read", "--uri", $ProviderUri) `
        "read document through Archphene DocumentsProvider") -join "`n"
if (-not $providerText.Contains($Marker)) {
    throw "DocumentsProvider did not expose the edited marker"
}

Invoke-Adb @("shell", "screencap", "-p", "/sdcard/mousepad-android-document-workflow.png") `
        "capture final workflow screenshot" | Out-Null
Invoke-Adb @("pull", "/sdcard/mousepad-android-document-workflow.png",
        (Join-Path $Root "artifacts/mousepad-android-document-workflow.png")) `
        "pull final workflow screenshot" | Out-Null

Write-Host "Mousepad Android document workflow passed: picker -> edit -> save -> Downloads write-back -> cold reopen -> DocumentsProvider read."