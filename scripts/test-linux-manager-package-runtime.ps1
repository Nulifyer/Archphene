param(
    [string]$Serial = "emulator-5554",
    [string]$AndroidSdk = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$SdkCandidate = if ($AndroidSdk) { $AndroidSdk }
    elseif (Test-Path -LiteralPath (Join-Path $Root "tooling/android-sdk")) { Join-Path $Root "tooling/android-sdk" }
    elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
    else { "" }
if (-not $SdkCandidate -or -not (Test-Path -LiteralPath $SdkCandidate)) {
    throw "Android SDK not found; pass -AndroidSdk or set ANDROID_SDK_ROOT"
}
$Adb = Join-Path (Resolve-Path $SdkCandidate) "platform-tools/adb.exe"
$Package = "org.archpheneos.manager"

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build-install-linux-manager-stub.ps1") `
        -IncludePackageRuntime -Serial $Serial -AndroidSdk $SdkCandidate
    if ($LASTEXITCODE -ne 0) { throw "Manager package-runtime build or install failed" }
}

$packageDump = & $Adb -s $Serial shell dumpsys package $Package
if ($LASTEXITCODE -ne 0) { throw "Could not inspect installed manager package" }
$nativeLine = $packageDump | Where-Object { $_ -match '^\s*(?:legacy)?nativeLibraryDir=' } | Select-Object -First 1
if (-not $nativeLine -or $nativeLine -notmatch '(?:legacy)?nativeLibraryDir=(.+)$') {
    throw "Installed manager native library directory was not reported"
}
$nativeDir = $matches[1].Trim()
if ($nativeLine -match 'legacyNativeLibraryDir=') {
    $abiLine = $packageDump | Where-Object { $_ -match '^\s*primaryCpuAbi=' } | Select-Object -First 1
    if (-not $abiLine -or $abiLine -notmatch 'primaryCpuAbi=(.+)$') {
        throw "Installed manager primary ABI was not reported"
    }
    $nativeDir = "$nativeDir/$($matches[1].Trim())"
}

& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" `
    --ez archphene_test_package_runtime true | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not launch manager package-runtime test hook" }
$uiPath = "/sdcard/archphene-package-runtime.xml"
$ui = ""
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Start-Sleep -Milliseconds 500
    & $Adb -s $Serial shell uiautomator dump --compressed $uiPath 2>$null | Out-Null
    $ui = (& $Adb -s $Serial shell cat $uiPath 2>$null) -join "`n"
    if ($ui.Contains("Package runtime exit 0") -and $ui.Contains("Pacman v")) { break }
}
if (-not $ui.Contains("Package runtime exit 0") -or -not $ui.Contains("Pacman v")) {
    throw "Manager Java package-runtime hook did not report a successful pacman launch"
}
& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" `
    --ez archphene_test_package_runtime true `
    --es archphene_test_resolve_package kcalc | Out-Null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Milliseconds 500
    & $Adb -s $Serial shell uiautomator dump --compressed $uiPath 2>$null | Out-Null
    $ui = (& $Adb -s $Serial shell cat $uiPath 2>$null) -join "`n"
    if ($ui.Contains("Resolved kcalc") -and $ui.Contains("packages through libalpm")) { break }
}
if (-not $ui.Contains("Resolved kcalc") -or -not $ui.Contains("packages through libalpm")) {
    throw "Manager did not refresh official databases and resolve KCalc through libalpm"
}

& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" `
    --ez archphene_test_package_runtime true `
    --es archphene_test_resolve_package kcalc `
    --ez archphene_test_download_target true | Out-Null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Milliseconds 500
    & $Adb -s $Serial shell uiautomator dump --compressed $uiPath 2>$null | Out-Null
    $ui = (& $Adb -s $Serial shell cat $uiPath 2>$null) -join "`n"
    if ($ui.Contains("Downloaded and verified kcalc") -and $ui.Contains("Signer ")) { break }
}
if (-not $ui.Contains("Downloaded and verified kcalc") -or -not $ui.Contains("Signer ")) {
    throw "Manager did not download and verify KCalc through Android HTTPS and the Arch trust anchor"
}

$packageFixture = Get-ChildItem (Join-Path $Root "tooling/downloads/arch-runtime-pacman-x86_64/packages") `
    -File | Where-Object Name -like "pacman-*.pkg.tar.zst" | Select-Object -First 1
$signatureFixture = if ($packageFixture) { Get-Item "$($packageFixture.FullName).sig" -ErrorAction SilentlyContinue } else { $null }
if (-not $packageFixture -or -not $signatureFixture) {
    throw "Signed pacman package fixture is missing"
}
& $Adb -s $Serial push $packageFixture.FullName /data/local/tmp/archphene-package.pkg.tar.zst | Out-Null
& $Adb -s $Serial push $signatureFixture.FullName /data/local/tmp/archphene-package.pkg.tar.zst.sig | Out-Null
& $Adb -s $Serial shell run-as $Package mkdir -p files/package-runtime/verify | Out-Null
& $Adb -s $Serial shell run-as $Package cp /data/local/tmp/archphene-package.pkg.tar.zst `
    files/package-runtime/verify/package.pkg.tar.zst | Out-Null
& $Adb -s $Serial shell run-as $Package cp /data/local/tmp/archphene-package.pkg.tar.zst.sig `
    files/package-runtime/verify/package.pkg.tar.zst.sig | Out-Null
$deviceFiles = "/data/user/0/$Package/files/package-runtime/verify"
& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" `
    --ez archphene_test_package_runtime true `
    --es archphene_test_package_file "$deviceFiles/package.pkg.tar.zst" `
    --es archphene_test_signature_file "$deviceFiles/package.pkg.tar.zst.sig" | Out-Null
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Milliseconds 500
    & $Adb -s $Serial shell uiautomator dump --compressed $uiPath 2>$null | Out-Null
    $ui = (& $Adb -s $Serial shell cat $uiPath 2>$null) -join "`n"
    if ($ui.Contains("Verified Arch package") -and $ui.Contains("Signer 0429897DE5F3BDAC")) { break }
}
if (-not $ui.Contains("Verified Arch package") -or -not $ui.Contains("Signer 0429897DE5F3BDAC")) {
    throw "Manager did not verify the signed pacman fixture through the bundled Arch trust anchor"
}

$tampered = Join-Path $Root "tooling/build/package-runtime/tampered.pkg.tar.zst"
New-Item -ItemType Directory -Force (Split-Path $tampered) | Out-Null
Copy-Item -LiteralPath $packageFixture.FullName -Destination $tampered -Force
[IO.File]::AppendAllText($tampered, "tampered", [Text.UTF8Encoding]::new($false))
& $Adb -s $Serial push $tampered /data/local/tmp/archphene-tampered.pkg.tar.zst | Out-Null
& $Adb -s $Serial shell run-as $Package cp /data/local/tmp/archphene-tampered.pkg.tar.zst `
    files/package-runtime/verify/tampered.pkg.tar.zst | Out-Null
& $Adb -s $Serial shell am force-stop $Package | Out-Null
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" `
    --ez archphene_test_package_runtime true `
    --es archphene_test_package_file "$deviceFiles/tampered.pkg.tar.zst" `
    --es archphene_test_signature_file "$deviceFiles/package.pkg.tar.zst.sig" | Out-Null
for ($attempt = 0; $attempt -lt 12; $attempt++) {
    Start-Sleep -Milliseconds 500
    & $Adb -s $Serial shell uiautomator dump --compressed $uiPath 2>$null | Out-Null
    $ui = (& $Adb -s $Serial shell cat $uiPath 2>$null) -join "`n"
    if ($ui.Contains("Package runtime failed")) { break }
}
if (-not $ui.Contains("Package runtime failed")) {
    throw "Manager accepted a tampered Arch package"
}
Write-Host "Manager package runtime and detached signature checks passed under Android UID: $nativeDir"