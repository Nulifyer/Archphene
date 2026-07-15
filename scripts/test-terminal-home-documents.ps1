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

$rootsOutput = & adb -s $Serial shell content query --uri "content://$Authority/root" 2>&1
$roots = $rootsOutput -join [Environment]::NewLine
if ($roots -match "SecurityException|Permission Denial") {
    Invoke-Adb shell am force-stop com.google.android.documentsui | Out-Null
    Invoke-Adb shell am start -W -a android.intent.action.OPEN_DOCUMENT -c android.intent.category.OPENABLE -t "text/plain" --eu android.provider.extra.INITIAL_URI "content://$Authority/root/archphene-terminal-home" | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb shell uiautomator dump /sdcard/archphene-terminal-home-ui.xml | Out-Null
    $homeUi = (Invoke-Adb shell cat /sdcard/archphene-terminal-home-ui.xml) -join ""
    if ($homeUi -notmatch "Archphene Home" -or $homeUi -notmatch "Documents" -or
            $homeUi -match "private-test") {
        throw "SAF-only Terminal home root is incorrect"
    }

    $documentsNode = [regex]::Match($homeUi,
        'text="Documents"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $documentsNode.Success) {
        throw "Could not locate the Terminal Documents directory"
    }
    $x = ([int]$documentsNode.Groups[1].Value + [int]$documentsNode.Groups[3].Value) / 2
    $y = ([int]$documentsNode.Groups[2].Value + [int]$documentsNode.Groups[4].Value) / 2
    Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb shell uiautomator dump /sdcard/archphene-terminal-documents-ui.xml | Out-Null
    $documentsUi = (Invoke-Adb shell cat /sdcard/archphene-terminal-documents-ui.xml) -join ""
    if ($documentsUi -notmatch "provider-test.txt") {
        throw "SAF-only Terminal document is not visible in DocumentsUI"
    }
    Invoke-Adb shell input keyevent 4 | Out-Null
    Write-Host "Terminal Storage Access Framework home passed on $Serial through DocumentsUI."
    return
}
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
