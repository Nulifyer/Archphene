param(
    [string]$Serial = "emulator-5554",
    [string]$TerminalApk = "",
    [string]$Alias = "device-project",
    [string]$Folder = "archphene-project-tree-test",
    [switch]$SkipInstall,
    [switch]$PreserveAppData
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Package = "org.archpheneos.terminal"
$Remote = "/sdcard/Download/$Folder"
$UiDump = "/sdcard/archphene-project-ui.xml"
if ([string]::IsNullOrWhiteSpace($TerminalApk)) {
    $TerminalApk = Join-Path $Root "prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
}

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
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $node = Get-UiNodes | Where-Object {
            $_.GetAttribute($Attribute) -eq $Value -and
                    ([string]::IsNullOrEmpty($ResourceId) -or
                            $_.GetAttribute("resource-id") -eq $ResourceId)
        } | Sort-Object { $_.GetAttribute("clickable") -ne "true" } | Select-Object -First 1
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

function Send-TerminalCommand([string]$Command) {
    $encoded = $Command.Replace(" ", "%s")
    Invoke-Adb shell input text $encoded | Out-Null
    Invoke-Adb shell input keyevent 66 | Out-Null
}

function Wait-For([scriptblock]$Condition, [string]$Description, [int]$Seconds = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Description"
}

function Read-Local([string]$Path) {
    $output = & adb -s $Serial shell "run-as $Package cat files/terminal/home/Projects/$Alias/$Path" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($output -join "")
}

function Read-Remote([string]$Path) {
    $output = & adb -s $Serial shell cat "$Remote/$Path" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($output -join "")
}

function Assert-Equal([string]$Expected, [AllowNull()][string]$Actual, [string]$Description) {
    if ($Expected -ne $Actual) {
        throw "$Description mismatch: expected '$Expected', got '$Actual'"
    }
}

if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $TerminalApk -PathType Leaf)) {
        throw "Terminal APK missing: $TerminalApk"
    }
    Invoke-Adb install -r -d $TerminalApk | Out-Null
}

if (-not $PreserveAppData) { Invoke-Adb shell pm clear $Package | Out-Null }
Invoke-Adb shell "rm -rf $Remote; mkdir -p $Remote/sub; printf android-initial > $Remote/android.txt; printf nested-initial > $Remote/sub/nested.txt" | Out-Null
Invoke-Adb shell am force-stop com.google.android.documentsui | Out-Null
Invoke-Adb shell logcat -c | Out-Null
Invoke-Adb shell am start -W -n "$Package/.TerminalActivity" | Out-Null
Start-Sleep -Seconds 2
for ($dialog = 0; $dialog -lt 4; $dialog++) {
    $nodes = Get-UiNodes
    $pageCompatibility = $nodes | Where-Object {
        $_.GetAttribute("package") -eq "android" -and
                $_.GetAttribute("text") -eq "OK" -and
                $_.GetAttribute("clickable") -eq "true"
    } | Select-Object -First 1
    if ($null -ne $pageCompatibility) {
        Tap-UiNode "text" "OK" "page-size compatibility notice"
        continue
    }
    $notificationPermission = $nodes | Where-Object {
        $_.GetAttribute("package") -eq "com.google.android.permissioncontroller" -and
                $_.GetAttribute("text") -eq "Allow" -and
                $_.GetAttribute("clickable") -eq "true"
    } | Select-Object -First 1
    if ($null -ne $notificationPermission) {
        Tap-UiNode "text" "Allow" "notification permission"
        continue
    }
    break
}

Send-TerminalCommand "archphene-project add $Alias"
Tap-UiNode "content-desc" "Show roots" "document roots button"
$deviceRoot = Get-UiNodes | Where-Object {
    $_.GetAttribute("package") -eq "com.google.android.documentsui" -and
            $_.GetAttribute("resource-id") -eq "android:id/title" -and
            $_.GetAttribute("bounds").StartsWith("[168,") -and
            $_.GetAttribute("text") -ne "Archphene Home"
} | Select-Object -First 1
if ($null -eq $deviceRoot) { throw "Could not discover Android device storage root" }
Tap-UiNode "text" $deviceRoot.GetAttribute("text") "Android device storage root" "android:id/title"
Tap-UiNode "text" "Download" "Android Download directory" "android:id/title"
Tap-UiNode "text" $Folder "project test folder"
Tap-UiNode "text" "USE THIS FOLDER" "tree selection button"
Tap-UiNode "text" "ALLOW" "tree permission confirmation"

Wait-For { (Read-Local "android.txt") -eq "android-initial" } "initial project pull"
Assert-Equal "nested-initial" (Read-Local "sub/nested.txt") "nested initial pull"

Invoke-Adb shell "run-as $Package sh -c 'printf local-created > files/terminal/home/Projects/$Alias/local.txt; printf local-updated > files/terminal/home/Projects/$Alias/sub/nested.txt'" | Out-Null
Send-TerminalCommand "archphene-project sync $Alias"
Wait-For { (Read-Remote "local.txt") -eq "local-created" } "local project push"
Assert-Equal "local-updated" (Read-Remote "sub/nested.txt") "nested local push"

Invoke-Adb shell "printf android-updated > $Remote/android.txt" | Out-Null
Send-TerminalCommand "archphene-project sync $Alias"
Wait-For { (Read-Local "android.txt") -eq "android-updated" } "Android project pull"

Invoke-Adb shell "run-as $Package sh -c 'printf local-conflict > files/terminal/home/Projects/$Alias/android.txt'" | Out-Null
Invoke-Adb shell "printf android-conflict > $Remote/android.txt" | Out-Null
Send-TerminalCommand "archphene-project sync $Alias"
Wait-For {
    $matches = & adb -s $Serial shell "run-as $Package sh -c 'ls files/terminal/home/Projects/$Alias/android.txt.android-conflict-* 2>/dev/null'" 2>$null
    $LASTEXITCODE -eq 0 -and ($matches -join "").Length -gt 0
} "conflict copy"
Assert-Equal "local-conflict" (Read-Local "android.txt") "preserved local conflict side"
Assert-Equal "android-conflict" (Read-Remote "android.txt") "preserved Android conflict side"
$conflictsBefore = @(& adb -s $Serial shell "run-as $Package sh -c 'ls files/terminal/home/Projects/$Alias/android.txt.android-conflict-* 2>/dev/null'")
Send-TerminalCommand "archphene-project sync $Alias"
Start-Sleep -Seconds 2
$conflictsAfter = @(& adb -s $Serial shell "run-as $Package sh -c 'ls files/terminal/home/Projects/$Alias/android.txt.android-conflict-* 2>/dev/null'")
if ($conflictsAfter.Count -ne $conflictsBefore.Count) {
    throw "Repeated sync duplicated an already preserved conflict"
}

Invoke-Adb shell "rm -f $Remote/local.txt" | Out-Null
Send-TerminalCommand "archphene-project sync $Alias"
Start-Sleep -Seconds 2
Send-TerminalCommand "archphene-project sync $Alias"
Start-Sleep -Seconds 2
Assert-Equal "local-created" (Read-Local "local.txt") "deferred remote deletion local copy"
if ($null -ne (Read-Remote "local.txt")) { throw "Deferred remote deletion was incorrectly recreated" }

Invoke-Adb shell "run-as $Package rm -f files/terminal/home/Projects/$Alias/sub/nested.txt" | Out-Null
Send-TerminalCommand "archphene-project sync $Alias"
Start-Sleep -Seconds 2
Send-TerminalCommand "archphene-project sync $Alias"
Start-Sleep -Seconds 2
if ($null -ne (Read-Local "sub/nested.txt")) { throw "Deferred local deletion was incorrectly restored" }
Assert-Equal "local-updated" (Read-Remote "sub/nested.txt") "deferred local deletion remote copy"

Invoke-Adb shell "run-as $Package ln -s /sdcard files/terminal/home/Projects/$Alias/escape-link" | Out-Null
Send-TerminalCommand "archphene-project sync $Alias"
Start-Sleep -Seconds 2
$logs = (Invoke-Adb shell logcat -d -s "ArchpheneTerminal:I" "*:S") -join [Environment]::NewLine
if ($logs -notmatch "symbolic links are not supported") {
    throw "Project sync did not reject a symbolic-link escape"
}
Invoke-Adb shell "run-as $Package rm files/terminal/home/Projects/$Alias/escape-link" | Out-Null

Invoke-Adb shell "printf restart-pull > $Remote/restart.txt" | Out-Null
Invoke-Adb shell am force-stop $Package | Out-Null
Invoke-Adb shell am start -W -n "$Package/.TerminalActivity" | Out-Null
Start-Sleep -Seconds 2
Send-TerminalCommand "archphene-project sync $Alias"
Wait-For { (Read-Local "restart.txt") -eq "restart-pull" } "persisted grant after restart"
$resumed = (Invoke-Adb shell dumpsys activity activities) -join [Environment]::NewLine
if ($resumed -match "com.google.android.documentsui") {
    throw "Persisted project sync unexpectedly reopened the document picker"
}

Send-TerminalCommand "archphene-project remove $Alias"
Start-Sleep -Seconds 2
Assert-Equal "restart-pull" (Read-Local "restart.txt") "local mirror retained after mapping removal"
$preferences = & adb -s $Serial shell "run-as $Package cat shared_prefs/archphene-terminal-projects-v1.xml" 2>$null
if ($LASTEXITCODE -eq 0 -and ($preferences -join "") -match "project\.$Alias") {
    throw "Project mapping remained after removal"
}
& adb -s $Serial shell "run-as $Package test -e files/terminal/project-state/$Alias" 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) { throw "Private project manifest remained after removal" }
Send-TerminalCommand "archphene-project sync $Alias"
Start-Sleep -Seconds 2
$logs = (Invoke-Adb shell logcat -d -s "ArchpheneTerminal:I" "*:S") -join [Environment]::NewLine
if ($logs -notmatch "unknown project: $Alias") {
    throw "Removed project did not fail closed on a later sync"
}

if ($PreserveAppData) {
    Invoke-Adb shell "run-as $Package rm -rf files/terminal/home/Projects/$Alias files/terminal/project-state/$Alias" | Out-Null
    Invoke-Adb shell "rm -rf $Remote" | Out-Null
}
Write-Host "Terminal persisted project-tree mapping and guarded sync passed on $Serial."
