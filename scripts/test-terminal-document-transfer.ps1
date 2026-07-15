param(
    [string]$Serial = "emulator-5554"
)

$ErrorActionPreference = "Stop"
$Package = "org.archpheneos.terminal"
$SourceName = "archphene-transfer-source.txt"
$ExportName = "archphene-transfer-export.txt"
$UiDump = "/sdcard/archphene-transfer-ui.xml"

function Invoke-Adb {
    $arguments = @($args)
    $output = & adb -s $Serial @arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($arguments -join ' ') $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Get-UiNodes {
    Invoke-Adb shell uiautomator dump $UiDump | Out-Null
    [xml]$document = (Invoke-Adb shell cat $UiDump) -join ""
    return @($document.SelectNodes("//node"))
}

function Tap-UiNode([string]$Attribute, [string]$Value, [string]$Description,
        [string]$ResourceId = "") {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $node = Get-UiNodes | Where-Object {
            $_.GetAttribute($Attribute) -eq $Value -and
                    ([string]::IsNullOrEmpty($ResourceId) -or
                            $_.GetAttribute("resource-id") -eq $ResourceId)
        } | Select-Object -First 1
        if ($null -ne $node) {
            $match = [regex]::Match($node.GetAttribute("bounds"),
                "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")
            if (-not $match.Success) { throw "Invalid bounds for $Description" }
            $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
            $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
            Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
            Start-Sleep -Milliseconds 800
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Could not find $Description ($Attribute=$Value)"
}

Invoke-Adb shell "rm -f /sdcard/Download/$SourceName /sdcard/Download/$ExportName" | Out-Null
Invoke-Adb shell "mkdir -p /sdcard/Download; printf android-import-verified > /sdcard/Download/$SourceName" | Out-Null
Invoke-Adb shell "run-as $Package rm -f files/terminal/home/Downloads/$SourceName" | Out-Null
Invoke-Adb shell "run-as $Package sh -c 'mkdir -p files/terminal/home/Documents; printf terminal-export-verified > files/terminal/home/Documents/$ExportName'" | Out-Null
Invoke-Adb shell logcat -c | Out-Null
Invoke-Adb shell am force-stop com.google.android.documentsui | Out-Null
Invoke-Adb shell am force-stop $Package | Out-Null
Invoke-Adb shell am start -W -n "$Package/.TerminalActivity" | Out-Null
Start-Sleep -Seconds 2

Invoke-Adb shell input text "archphene-import%sDownloads" | Out-Null
Invoke-Adb shell input keyevent 66 | Out-Null
Tap-UiNode "content-desc" "Show roots" "document roots button"
Tap-UiNode "text" "Downloads" "Android Downloads root" "android:id/title"
Tap-UiNode "text" $SourceName "Android import source"
$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    $imported = & adb -s $Serial shell "run-as $Package cat files/terminal/home/Downloads/$SourceName" 2>$null
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)
if (($imported -join "") -ne "android-import-verified") {
    throw "Imported terminal file content mismatch: $($imported -join '')"
}

Invoke-Adb shell input text "archphene-export%sDocuments/$ExportName" | Out-Null
Invoke-Adb shell input keyevent 66 | Out-Null
Tap-UiNode "text" "SAVE" "Android save button"
$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    $exported = & adb -s $Serial shell cat "/sdcard/Download/$ExportName" 2>$null
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)
if (($exported -join "") -ne "terminal-export-verified") {
    throw "Exported Android file content mismatch: $($exported -join '')"
}

Invoke-Adb shell input text "archphene-export%s.config/private.txt" | Out-Null
Invoke-Adb shell input keyevent 66 | Out-Null
Start-Sleep -Seconds 1
Invoke-Adb shell input text "archphene-import%s../escape" | Out-Null
Invoke-Adb shell input keyevent 66 | Out-Null
Start-Sleep -Seconds 1
$logs = (Invoke-Adb shell logcat -d -s "ArchpheneTerminal:I" "*:S") -join [Environment]::NewLine
if ($logs -notmatch "hidden Terminal paths are private" -or
        $logs -notmatch "path is outside Archphene Home") {
    throw "Document bridge did not reject hidden and traversal paths"
}

Write-Host "Terminal import/export and path-boundary flow passed on $Serial."
