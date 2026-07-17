param(
    [string]$AndroidSdk = "",
    [switch]$SkipNativeBuild
)

$ErrorActionPreference = "Stop"
function Run-Native([scriptblock]$Command, [string]$Step) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$LocalSdk = Join-Path $Root "tooling/android-sdk"
$SdkCandidate = if ($AndroidSdk) { $AndroidSdk }
    elseif (Test-Path -LiteralPath $LocalSdk) { $LocalSdk }
    elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
    else { "" }
if (-not $SdkCandidate -or -not (Test-Path -LiteralPath $SdkCandidate)) {
    throw "Android SDK not found"
}
$Sdk = Resolve-Path $SdkCandidate
$BuildTools = Join-Path $Sdk "build-tools/36.0.0"
$App = Join-Path $Root "prototypes/kcalc-android-app"
$Out = Join-Path $Root "tooling/build/wrapper-templates/qt"
if (-not $SkipNativeBuild) {
    & (Join-Path $PSScriptRoot "build-native-compositor-podman.ps1") -Architecture x86_64 -Release
    if ($LASTEXITCODE -ne 0) { throw "Shared native compositor build failed" }
    & (Join-Path $PSScriptRoot "build-native-compositor-podman.ps1") -Architecture aarch64 -Release
    if ($LASTEXITCODE -ne 0) { throw "AArch64 shared native compositor build failed" }
}
$Compositor = Join-Path $Root "native/archphene-compositor/target/x86_64-linux-android/release/libarchphene_compositor.so"
$Arm64Compositor = Join-Path $Root "native/archphene-compositor/target/aarch64-linux-android/release/libarchphene_compositor.so"
if (-not (Test-Path -LiteralPath $Compositor -PathType Leaf) -or
        -not (Test-Path -LiteralPath $Arm64Compositor -PathType Leaf)) { throw "Shared native compositors are missing" }
if (Test-Path -LiteralPath $Out) { Remove-Item -LiteralPath $Out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Out,(Join-Path $Out "compiled"),(Join-Path $Out "gen"),(Join-Path $Out "classes"),(Join-Path $Out "dex"),(Join-Path $Out "stage/lib/x86_64"),(Join-Path $Out "stage/lib/arm64-v8a"),(Join-Path $Out "stage/assets") | Out-Null

$placeholder = "org.archphene.linux.p00000000000000000000000000000000"
$manifest = [IO.File]::ReadAllText((Join-Path $App "AndroidManifest.xml"))
$manifest = $manifest.Replace('package="org.archphene.linux.kcalc"', 'package="' + $placeholder + '"')
$fixedAuthority = 'org.archphene.linux.kcalc.documents'
$placeholderAuthority = $placeholder + '.documents'
$manifest = $manifest.Replace($fixedAuthority, $placeholderAuthority)
if ($manifest.Contains($fixedAuthority) -or -not $manifest.Contains($placeholderAuthority)) {
    throw "Wrapper document-provider authority placeholder was not applied"
}
$manifest = $manifest.Replace('android:debuggable="true"', 'android:debuggable="false"')
$manifest = $manifest.Replace('@drawable/kcalc_icon', '@drawable/linux_app_icon_png')
[IO.File]::WriteAllText((Join-Path $Out "AndroidManifest.xml"),$manifest,[Text.UTF8Encoding]::new($false))
Run-Native { & (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $App "res") -o (Join-Path $Out "compiled/res.zip") } "aapt2 compile template"
Run-Native { & (Join-Path $BuildTools "aapt2.exe") link -o (Join-Path $Out "unsigned.apk") -I (Join-Path $Sdk "platforms/android-36/android.jar") --manifest (Join-Path $Out "AndroidManifest.xml") --java (Join-Path $Out "gen") (Join-Path $Out "compiled/res.zip") } "aapt2 link template"
$javaFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $App "src") -Recurse -File -Filter *.java
    Get-ChildItem -LiteralPath (Join-Path $Root "prototypes/shared-android-bridge/src") -Recurse -File -Filter *.java
) | ForEach-Object FullName
Run-Native { & javac --release 17 -classpath (Join-Path $Sdk "platforms/android-36/android.jar") -d (Join-Path $Out "classes") $javaFiles } "javac template"
$classFiles = Get-ChildItem (Join-Path $Out "classes") -Recurse -File -Filter *.class | ForEach-Object FullName
Run-Native { & (Join-Path $BuildTools "d8.bat") --lib (Join-Path $Sdk "platforms/android-36/android.jar") --min-api 23 --output (Join-Path $Out "dex") $classFiles } "d8 template"
Copy-Item (Join-Path $Out "dex/classes.dex") (Join-Path $Out "stage/classes.dex")
Copy-Item (Join-Path $App "assets/fonts.conf") (Join-Path $Out "stage/assets/fonts.conf")
Copy-Item (Join-Path $App "assets/kcalc.PKGINFO") (Join-Path $Out "stage/assets/kcalc.PKGINFO")
$PrebuiltBridge = Join-Path $Root "prebuilt/qt-bridge/x86_64"
if (Test-Path -LiteralPath $PrebuiltBridge -PathType Container) {
    $PrebuiltManifestPath = Join-Path $Root "prebuilt/qt-bridge/manifest.json"
    if (-not (Test-Path -LiteralPath $PrebuiltManifestPath -PathType Leaf)) {
        throw "Prebuilt bridge manifest missing: $PrebuiltManifestPath"
    }
    $PrebuiltManifest = Get-Content -LiteralPath $PrebuiltManifestPath -Raw | ConvertFrom-Json
    if ($PrebuiltManifest.schema -ne "org.archphene.prebuilt-bridge.v1" -or
            $PrebuiltManifest.architecture -ne "x86_64") {
        throw "Unsupported prebuilt bridge manifest"
    }
    $ExpectedNames = @($PrebuiltManifest.files | ForEach-Object { $_.name } | Sort-Object)
    $ActualNames = @(Get-ChildItem -LiteralPath $PrebuiltBridge -File -Filter "*.so" |
        ForEach-Object { $_.Name } | Sort-Object)
    if (($ExpectedNames -join ",") -ne ($ActualNames -join ",")) {
        throw "Prebuilt bridge files do not match manifest"
    }
    foreach ($Entry in $PrebuiltManifest.files) {
        $Source = Join-Path $PrebuiltBridge $Entry.name
        $Length = (Get-Item -LiteralPath $Source).Length
        $Hash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($Length -ne [int64]$Entry.bytes -or $Hash -ne $Entry.sha256) {
            throw "Prebuilt bridge verification failed: $($Entry.name)"
        }
        Copy-Item -LiteralPath $Source -Destination (Join-Path $Out "stage/lib/x86_64")
    }
} else {
    Get-ChildItem (Join-Path $App "lib/x86_64") -File | Where-Object {
        $_.Name -like "libarchphene_*" -and $_.Name -notin @("libarchphene_kcalc.so","libarchphene_ld.so","libarchphene_ld.so.orig","libarchphene_syscall_probe.so")
    } | Copy-Item -Destination (Join-Path $Out "stage/lib/x86_64")
}
$DesktopPayloads = [ordered]@{
    "dbus-daemon" = "libarchphene_dbus_daemon.so"
    "portal-service" = "libarchphene_portal_service.so"
    "xdg-open" = "libarchphene_xdg_open.so"
}
foreach ($Architecture in @("x86_64", "aarch64")) {
    $AndroidAbi = if ($Architecture -eq "aarch64") { "arm64-v8a" } else { "x86_64" }
    foreach ($Payload in $DesktopPayloads.GetEnumerator()) {
        $Source = Join-Path $Root "tooling/build/android-dbus/$Architecture/$($Payload.Key)"
        if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
            throw "Verified desktop integration payload is missing: $Source"
        }
        Copy-Item -LiteralPath $Source -Destination `
            (Join-Path $Out "stage/lib/$AndroidAbi/$($Payload.Value)") -Force
    }
}
Copy-Item -LiteralPath $Compositor -Destination (Join-Path $Out "stage/lib/x86_64/libarchphene_compositor.so") -Force
Copy-Item -LiteralPath $Arm64Compositor -Destination (Join-Path $Out "stage/lib/arm64-v8a/libarchphene_compositor.so") -Force
Push-Location (Join-Path $Out "stage")
$entries = Get-ChildItem -Recurse -File | ForEach-Object { $_.FullName.Substring((Get-Location).Path.Length + 1) }
Run-Native { & jar uf "..\unsigned.apk" $entries } "add template bridge files"
Pop-Location
Run-Native { & (Join-Path $BuildTools "zipalign.exe") -P 16 -f 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "qt-wrapper-template.apk") } "align template"
& (Join-Path $PSScriptRoot "build-wrapper-manifest-variants.ps1") -AndroidSdk $Sdk -OutputDirectory $Out
if ($LASTEXITCODE -ne 0) { throw "Wrapper manifest variant build failed" }
$compiledManifest = (& (Join-Path $Sdk "cmdline-tools/latest/bin/apkanalyzer.bat") manifest print (Join-Path $Out "qt-wrapper-template.apk")) -join "`n"
if ($LASTEXITCODE -ne 0 -or $compiledManifest -notmatch [regex]::Escape($placeholderAuthority) -or $compiledManifest -match [regex]::Escape($fixedAuthority)) {
    throw "Compiled wrapper template has an invalid document-provider authority"
}
Write-Host "Qt wrapper template: $(Join-Path $Out "qt-wrapper-template.apk")"
