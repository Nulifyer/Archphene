param(
    [string]$Serial = "emulator-5558",
    [string]$PackageName = "wev"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Manager = "org.archpheneos.manager"
$UiPath = "/sdcard/archphene-compatibility-candidate.xml"

if ($PackageName -notmatch '^[a-z0-9@._+-]{1,96}$') {
    throw "Unsafe package name: $PackageName"
}

function Invoke-Adb {
    $output = & $Adb -s $Serial @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($args -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Wait-Ui([string]$Expected, [int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 700
        Invoke-Adb shell uiautomator dump --compressed $UiPath | Out-Null
        $ui = (Invoke-Adb shell cat $UiPath) -join "`n"
        if ($ui.Contains($Expected)) { return $ui }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for '$Expected' while testing $PackageName"
}

$pageSize = ((Invoke-Adb shell getconf PAGE_SIZE) -join "").Trim()
if ($pageSize -ne "4096") {
    throw "Official Arch x86_64 candidates require the 4 KB test lane; got $pageSize"
}
$runAs = (Invoke-Adb shell run-as $Manager id) -join "`n"
if ($runAs -notmatch 'uid=') {
    throw "A debuggable manager is required on $Serial"
}

Invoke-Adb shell am force-stop $Manager | Out-Null
Invoke-Adb shell am start -W -n "$Manager/.MainActivity" --ez archphene_test_package_runtime true --es archphene_test_resolve_package $PackageName | Out-Null
$ui = Wait-Ui "Resolved $PackageName" 45
if (-not $ui.Contains("packages through libalpm")) {
    throw "$PackageName did not resolve through libalpm"
}

Invoke-Adb shell am force-stop $Manager | Out-Null
Invoke-Adb shell am start -W -n "$Manager/.MainActivity" --ez archphene_test_package_runtime true --es archphene_test_resolve_package $PackageName --ez archphene_test_download_target true | Out-Null
$ui = Wait-Ui "Downloaded and verified $PackageName" 60
if (-not $ui.Contains("Signer ")) {
    throw "$PackageName did not report a verified repository signer"
}

Write-Host "$PackageName resolved through libalpm and its target archive signature verified on $Serial."
