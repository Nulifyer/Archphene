param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "org.archphene.linux.p28ae847c2c818246c42d2ba69544759e",
    [string]$ProbePath = "",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$RemotePdf = "/data/local/tmp/archphene-print-test.pdf"
$RemoteInvalid = "/data/local/tmp/archphene-print-invalid.txt"
$RemoteProbe = "/data/local/tmp/archphene-print-portal-probe"
$PrivateProbe = "cache/archphene-print-portal-probe"

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function New-MinimalPdf([string]$Path) {
    $ascii = [Text.Encoding]::ASCII
    $nl = [string][char]10
    $stream = "BT /F1 24 Tf 72 720 Td (Archphene print bridge) Tj ET" + $nl
    $objects = @(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ("<< /Length " + $ascii.GetByteCount($stream) + " >>" + $nl + "stream" + $nl + $stream + "endstream")
    )
    $builder = [Text.StringBuilder]::new("%PDF-1.4" + $nl)
    $offsets = [Collections.Generic.List[int]]::new()
    for ($index = 0; $index -lt $objects.Count; $index++) {
        $offsets.Add($ascii.GetByteCount($builder.ToString()))
        [void]$builder.Append(($index + 1).ToString() + " 0 obj" + $nl + $objects[$index] + $nl + "endobj" + $nl)
    }
    $xref = $ascii.GetByteCount($builder.ToString())
    [void]$builder.Append("xref" + $nl + "0 " + ($objects.Count + 1) + $nl + "0000000000 65535 f " + $nl)
    foreach ($offset in $offsets) {
        [void]$builder.Append(("{0:D10} 00000 n " -f $offset) + $nl)
    }
    [void]$builder.Append("trailer" + $nl + "<< /Size " + ($objects.Count + 1) + " /Root 1 0 R >>" + $nl + "startxref" + $nl + $xref + $nl + "%%EOF" + $nl)
    [IO.File]::WriteAllBytes($Path, $ascii.GetBytes($builder.ToString()))
}

function Wait-ForTopActivity([string]$Pattern) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $activities = (Invoke-Adb @("shell", "dumpsys", "activity", "activities") "read top Activity") -join [Environment]::NewLine
        if ($activities -match "topResumedActivity=.*$Pattern") { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for top Activity $Pattern"
}

function Wait-ForTopActivityToClose([string]$Pattern) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $activities = (Invoke-Adb @("shell", "dumpsys", "activity", "activities") "read top Activity") -join [Environment]::NewLine
        if ($activities -notmatch "topResumedActivity=.*$Pattern") { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for top Activity $Pattern to close"
}

$BuildDirectory = Join-Path $Root "tooling/build/print-test"
[IO.Directory]::CreateDirectory($BuildDirectory) | Out-Null
$Pdf = Join-Path $BuildDirectory "minimal.pdf"
$Invalid = Join-Path $BuildDirectory "not-pdf.txt"
New-MinimalPdf $Pdf
[IO.File]::WriteAllText($Invalid, "not a PDF", [Text.UTF8Encoding]::new($false))

Invoke-Adb @("wait-for-device") "wait for device" | Out-Null
$component = Invoke-Adb @("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", $Package) "resolve wrapper launcher" |
        Where-Object { $_ -match "^$([regex]::Escape($Package))/" } |
        Select-Object -Last 1
if (-not $component) { throw "Wrapper has no launcher Activity" }

Invoke-Adb @("push", $Pdf, $RemotePdf) "push print PDF" | Out-Null
Invoke-Adb @("push", $Invalid, $RemoteInvalid) "push invalid print fixture" | Out-Null
Invoke-Adb @("shell", "chmod", "644", $RemotePdf, $RemoteInvalid) "make print fixtures readable" | Out-Null
Invoke-Adb @("shell", "run-as", $Package, "cp", $RemotePdf, "cache/print-test.pdf") "stage private print PDF" | Out-Null
Invoke-Adb @("shell", "run-as", $Package, "cp", $RemoteInvalid, "cache/not-pdf.txt") "stage invalid print fixture" | Out-Null

if ($ProbePath) {
    $resolvedProbe = Resolve-Path $ProbePath
    Invoke-Adb @("push", $resolvedProbe, $RemoteProbe) "push portal probe" | Out-Null
    Invoke-Adb @("shell", "chmod", "755", $RemoteProbe) "make portal probe readable" | Out-Null
    Invoke-Adb @("shell", "run-as", $Package, "cp", $RemoteProbe, $PrivateProbe) "stage portal probe" | Out-Null
} else {
    $packageDump = (Invoke-Adb @("shell", "dumpsys", "package", $Package) "read wrapper paths") -join [Environment]::NewLine
    $native = [regex]::Match($packageDump, "legacyNativeLibraryDir=(\S+)").Groups[1].Value
    $abi = [regex]::Match($packageDump, "primaryCpuAbi=(\S+)").Groups[1].Value
    $abiDirectory = if ($abi -eq "arm64-v8a") { "arm64" } else { $abi }
    if (-not $native -or -not $abiDirectory) { throw "Wrapper native path is unavailable" }
    Invoke-Adb @("shell", "run-as", $Package, "cp", "$native/$abiDirectory/libarchphene_portal_probe.so", $PrivateProbe) "stage installed portal probe" | Out-Null
}
Invoke-Adb @("shell", "run-as", $Package, "chmod", "700", $PrivateProbe) "make private portal probe executable" | Out-Null

Invoke-Adb @("logcat", "-c") "clear logs" | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $Package) "stop wrapper" | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-n", $component) "start wrapper" | Out-Null
$busPath = "/data/user/0/$Package/cache/desktop-integration/bus"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $ready = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s", "ArchpheneDesktop") "read desktop bridge logs") -join [Environment]::NewLine
    if ($ready.Contains("Private session bus and desktop adapters ready")) { break }
    Start-Sleep -Milliseconds 250
} while ((Get-Date) -lt $deadline)
if (-not $ready.Contains("Private session bus and desktop adapters ready")) {
    throw "Private D-Bus socket did not start: $ready"
}
$bus = "DBUS_SESSION_BUS_ADDRESS=unix:path=$busPath"

$contract = Invoke-Adb @("shell", "run-as", $Package, "env", $bus, $PrivateProbe, "contract") "probe XDG portal contract"
if (($contract -join [Environment]::NewLine) -notmatch "PASS portal PreparePrint accepted") {
    throw "XDG PreparePrint contract was not validated"
}
$printed = Invoke-Adb @("shell", "run-as", $Package, "env", $bus, $PrivateProbe, "print", "cache/print-test.pdf") "print valid PDF"
if (($printed -join [Environment]::NewLine) -notmatch "PASS portal Print accepted") {
    throw "XDG Print did not return success"
}
Wait-ForTopActivity "com\.android\.printspooler"

$uiPath = "/sdcard/archphene-print-preview.xml"
Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", $uiPath) "dump print preview" | Out-Null
$ui = (Invoke-Adb @("shell", "cat", $uiPath) "read print preview") -join ""
if ($ui -notmatch 'content-desc="Page 1 of 1"') {
    throw "Android print preview did not render the PDF"
}
$destination = [regex]::Match($ui, 'resource-id="com.android.printspooler:id/destination_spinner"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $destination.Success) { throw "Print destination selector is unavailable" }
$x = ([int]$destination.Groups[1].Value + [int]$destination.Groups[3].Value) / 2
$y = ([int]$destination.Groups[2].Value + [int]$destination.Groups[4].Value) / 2
Invoke-Adb @("shell", "input", "tap", [string][int]$x, [string][int]$y) "open print destinations" | Out-Null
Start-Sleep -Milliseconds 500
Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", $uiPath) "dump print destinations" | Out-Null
$destinations = (Invoke-Adb @("shell", "cat", $uiPath) "read print destinations") -join ""
if ($destinations -notmatch 'text="Save as PDF"') {
    throw "Android Save as PDF destination is unavailable"
}
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") "close destinations" | Out-Null
Start-Sleep -Milliseconds 750
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") "cancel print" | Out-Null
Wait-ForTopActivityToClose "com\.android\.printspooler"
$cleanupDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $pending = (Invoke-Adb @("shell", "run-as", $Package, "find", "cache/print", "-type", "f", "-print") "inspect private print staging") -join ""
    if (-not $pending) { break }
    Start-Sleep -Milliseconds 250
} while ((Get-Date) -lt $cleanupDeadline)
if ($pending) { throw "Cancelled print left a private staged document: $pending" }

$invalidOutput = & $Adb -s $Serial shell run-as $Package env $bus $PrivateProbe print cache/not-pdf.txt 2>&1
$invalidExit = $LASTEXITCODE
if ($invalidExit -eq 0 -or ($invalidOutput -join [Environment]::NewLine) -notmatch "response=2") {
    throw "Invalid PDF did not return an XDG failure: $($invalidOutput -join [Environment]::NewLine)"
}
$activities = (Invoke-Adb @("shell", "dumpsys", "activity", "activities") "verify invalid PDF UI") -join [Environment]::NewLine
if ($activities -match "topResumedActivity=.*com\.android\.printspooler") {
    throw "Invalid PDF opened Android print UI"
}

$pipeOutput = Invoke-Adb @("shell", "run-as", $Package, "env", $bus, $PrivateProbe, "print-pipe") "reject pipe print descriptor"
if (($pipeOutput -join [Environment]::NewLine) -notmatch "PASS portal Print rejected non-regular descriptor") {
    throw "A non-regular print descriptor was not rejected"
}
$activities = (Invoke-Adb @("shell", "dumpsys", "activity", "activities") "verify pipe rejection UI") -join [Environment]::NewLine
if ($activities -match "topResumedActivity=.*com\.android\.printspooler") {
    throw "A non-regular print descriptor opened Android print UI"
}

Invoke-Adb @("shell", "rm", "-f", $RemotePdf, $RemoteInvalid, $RemoteProbe) "remove remote print fixtures" | Out-Null
Write-Host ("Android XDG printing passed on {0}: PreparePrint, PDF FD transfer, preview, Save as PDF, cancellation cleanup, invalid-PDF rejection, and non-regular-FD rejection." -f $Serial)
