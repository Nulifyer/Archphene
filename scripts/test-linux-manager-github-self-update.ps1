param(
    [string]$Serial = "emulator-5554",
    [string]$FromVersion = "0.9.0",
    [int]$FromVersionCode = 9000,
    [string]$ToVersion = "1.0.1",
    [switch]$RebuildBaseline,
    [switch]$PublishedV100Migration,
    [switch]$PrepareBaselineOnly
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archpheneos.manager"
$BuildDir = Join-Path $Root "tooling/build/manager-github-self-update"
$Baseline = Join-Path $BuildDir "Archphene-$FromVersion-production.apk"
$DeviceAbi = (& $Adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
$ArtifactAbi = switch ($DeviceAbi) {
    "x86_64" { "x86_64" }
    "arm64-v8a" { "arm64-v8a" }
    default { throw "Unsupported Android ABI for self-update validation: $DeviceAbi" }
}

if ($PrepareBaselineOnly -and -not $PublishedV100Migration) {
    throw "-PrepareBaselineOnly requires -PublishedV100Migration"
}
if ($PublishedV100Migration) {
    if ($DeviceAbi -ne "x86_64") {
        throw "The published v1.0.0 migration baseline is x86_64-only, not $DeviceAbi"
    }
    $FromVersion = "1.0.0"
    $FromVersionCode = 1000000002
    $ToVersion = "1.0.1"
    $Baseline = Join-Path $BuildDir "Archphene-1.0.0-production.apk"
    $download = Join-Path $BuildDir "v1.0.0"
    New-Item -ItemType Directory -Force -Path $download | Out-Null
    if ($RebuildBaseline -or -not (Test-Path -LiteralPath $Baseline -PathType Leaf)) {
        & gh release download v1.0.0 --repo Nulifyer/Archphene --clobber `
            --pattern "Archphene-1.0.0.apk*" --dir $download
        if ($LASTEXITCODE -ne 0) { throw "Could not download published v1.0.0 baseline" }
        $apk = Join-Path $download "Archphene-1.0.0.apk"
        $checksumPath = Join-Path $download "Archphene-1.0.0.apk.sha256"
        $checksum = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
        $fields = $checksum -split "\s+", 2
        $actual = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($fields.Count -ne 2 -or $fields[0] -notmatch "^[0-9a-f]{64}$" -or
                $fields[1].TrimStart("*") -ne "Archphene-1.0.0.apk" -or
                $actual -ne $fields[0]) {
            throw "Published v1.0.0 checksum verification failed"
        }
        Copy-Item -LiteralPath $apk -Destination $Baseline -Force
    }
}

if ($PrepareBaselineOnly) {
    Write-Host "Published v1.0.0 migration baseline verified: $Baseline"
    return
}
function Invoke-Adb([string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "adb failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Get-Ui([string]$Name) {
    $remote = "/sdcard/$Name.xml"
    Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", $remote) | Out-Null
    return (Invoke-Adb @("shell", "cat", $remote)) -join "`n"
}

function Wait-Ui([string]$Pattern, [string]$Name, [int]$Seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 750
        $ui = Get-Ui $Name
    } while ($ui -notmatch $Pattern -and [DateTime]::UtcNow -lt $deadline)
    if ($ui -notmatch $Pattern) { throw "Timed out waiting for $Pattern" }
    return $ui
}

function Tap-Match([string]$Ui, [string]$Pattern, [string]$Step) {
    $node = [regex]::Match($Ui,
        "<node(?=[^>]*$Pattern)[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"[^>]*/>")
    if (-not $node.Success) { throw "Could not find $Step control matching $Pattern" }
    $x = [int](([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2)
    $y = [int](([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2)
    Invoke-Adb @("shell", "input", "tap", [string]$x, [string]$y) | Out-Null
}

if (-not $PublishedV100Migration -and ($RebuildBaseline -or
        -not (Test-Path -LiteralPath $Baseline -PathType Leaf))) {
    & (Join-Path $PSScriptRoot "build-manager-podman.ps1") -SkipRuntime -ReleaseBuild `
        -ArtifactAbi $ArtifactAbi -VersionCode $FromVersionCode -VersionName $FromVersion
    if ($LASTEXITCODE -ne 0) { throw "Production baseline build failed" }
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root `
        "prototypes/linux-app-manager-stub/out-linux/archphene.apk") `
        -Destination $Baseline -Force
}

Invoke-Adb @("uninstall", $Package) -AllowFailure | Out-Null
Invoke-Adb @("install", $Baseline) | Out-Null
Invoke-Adb @("shell", "appops", "set", $Package, "REQUEST_INSTALL_PACKAGES", "allow") | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/.MainActivity") | Out-Null

$ui = Wait-Ui "Installed $([regex]::Escape($FromVersion))\. Not checked" `
    "archphene-github-baseline"
Tap-Match $ui "content-desc=`"Check Archphene for updates[^`"]*`"" "update check"
$ui = Wait-Ui "Archphene update $([regex]::Escape($ToVersion)) available" `
    "archphene-github-discovered"

Tap-Match $ui 'text="Archphene"' "Archphene details"
$ui = Wait-Ui 'resource-id="android:id/text1"' "archphene-github-details"
Tap-Match $ui 'resource-id="android:id/text1"' "version selector"
$ui = Wait-Ui "text=`"$([regex]::Escape($ToVersion))`"" "archphene-github-versions"
Tap-Match $ui "text=`"$([regex]::Escape($ToVersion))`"" "release version"
$ui = Wait-Ui 'text="Install selected version"' "archphene-github-selected"
Tap-Match $ui 'text="Install selected version"' "install release"

$ui = Wait-Ui 'package="com\.google\.android\.packageinstaller"' `
    "archphene-github-system-confirm" 90
if ($ui -notmatch 'text="Do you want to update this app\?"') {
    throw "Android did not present an update confirmation"
}
Tap-Match $ui 'text="Update"' "Android update confirmation"

$deadline = [DateTime]::UtcNow.AddSeconds(45)
do {
    Start-Sleep -Seconds 1
    $packageState = (Invoke-Adb @("shell", "dumpsys", "package", $Package)) -join "`n"
} while (
    $packageState -notmatch "versionName=$([regex]::Escape($ToVersion))" -and
    [DateTime]::UtcNow -lt $deadline
)
if ($packageState -notmatch "versionName=$([regex]::Escape($ToVersion))") {
    throw "GitHub release $ToVersion was not installed"
}

Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/.MainActivity") | Out-Null
$ui = Wait-Ui "Archphene $([regex]::Escape($ToVersion)) is up to date" `
    "archphene-github-reconciled"
if ($ui -match "update $([regex]::Escape($ToVersion)) available") {
    throw "Manager retained stale update state after replacement"
}

$mode = if ($PublishedV100Migration) { "published v1.0.0 migration" } else { "exact-ABI release" }
Write-Host "Live GitHub self-update passed ($mode): $FromVersion -> $ToVersion with Android confirmation and reconciled restart state."
