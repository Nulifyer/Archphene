param(
    [string]$Serial = "emulator-5554",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$TerminalApk = Join-Path $Root "prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
$Package = "org.archpheneos.terminal"
$Authority = "org.archpheneos.terminal.documents"

function Invoke-Adb {
    $arguments = @($args)
    $output = & adb -s $Serial @arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($arguments -join ' ') $($output -join [Environment]::NewLine)"
    }
    return $output
}

if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $TerminalApk -PathType Leaf)) {
        throw "Missing Terminal APK: $TerminalApk"
    }
    Invoke-Adb install -r $TerminalApk | Out-Null
}

$providerDump = (Invoke-Adb shell dumpsys package $Package) -join [Environment]::NewLine
if ($providerDump -notmatch [regex]::Escape($Authority) -or
        $providerDump -notmatch "android.content.action.DOCUMENTS_PROVIDER") {
    throw "Terminal document provider is not registered correctly"
}

Invoke-Adb shell "run-as $Package sh -c 'mkdir -p files/terminal/home/Documents'" | Out-Null
Invoke-Adb shell "run-as $Package sh -c 'printf archphene-terminal-home > files/terminal/home/Documents/provider-test.txt'" | Out-Null
Invoke-Adb shell "run-as $Package sh -c 'printf private > files/terminal/home/.private-test'" | Out-Null

$roots = (Invoke-Adb shell content query --uri "content://$Authority/root") -join [Environment]::NewLine
if ($roots -notmatch "archphene-terminal-home" -or $roots -notmatch "Archphene Home") {
    throw "Terminal document root is unavailable"
}

$children = (Invoke-Adb shell content query --uri "content://$Authority/document/home/children") -join [Environment]::NewLine
if ($children -notmatch "Documents" -or $children -match "private-test") {
    throw "Terminal home document filtering is incorrect"
}

$documents = (Invoke-Adb shell content query --uri "content://$Authority/document/home%2FDocuments/children") -join [Environment]::NewLine
if ($documents -notmatch "provider-test.txt") {
    throw "Terminal home file is not visible through the document provider"
}

$file = (Invoke-Adb shell content read --uri "content://$Authority/document/home%2FDocuments%2Fprovider-test.txt") -join [Environment]::NewLine
if ($file -notmatch "archphene-terminal-home") {
    throw "Terminal home file contents could not be read through Android"
}

$privateQuery = & adb -s $Serial shell content query --uri "content://$Authority/document/home%2F.private-test" 2>&1
if (($privateQuery -join [Environment]::NewLine) -notmatch "No result found|FileNotFoundException|Private document") {
    throw "Hidden Terminal state was unexpectedly exposed"
}

Write-Host "Terminal Storage Access Framework home passed on $Serial."
