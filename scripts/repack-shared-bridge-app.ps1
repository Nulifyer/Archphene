param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("mousepad", "kcalc")]
    [string]$App,
    [string]$Serial = "",
    [switch]$BuildNative,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

function Run-Step([scriptblock]$Command, [string]$Name) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Sdk = Join-Path $Root "tooling/android-sdk"
$BuildTools = Join-Path $Sdk "build-tools/36.0.0"
$PlatformJar = Join-Path $Sdk "platforms/android-36/android.jar"
$AppDirectory = Join-Path $Root "prototypes/$App-android-app"
$Out = Join-Path $AppDirectory "out"
$BaseApk = Join-Path $Out "archpheneos-$App.apk"
$Package = if ($App -eq "mousepad") { "org.archphene.linux.mousepad" } else { "org.archphene.linux.kcalc" }
$Keystore = Join-Path $Root "tooling/signing/archpheneos-$App-debug.keystore"
$Work = Join-Path $Out ("bridge-repack-" + [guid]::NewGuid().ToString("N"))

foreach ($Required in @($BaseApk, $Keystore, $PlatformJar)) {
    if (-not (Test-Path -LiteralPath $Required)) {
        throw "Required repack input is missing: $Required"
    }
}

if ($BuildNative) {
    & (Join-Path $PSScriptRoot "build-native-compositor-podman.ps1") -Architecture x86_64 -Release
    if ($LASTEXITCODE -ne 0) { throw "Shared native compositor build failed" }
    Copy-Item -LiteralPath (Join-Path $Root "native/archphene-compositor/target/x86_64-linux-android/release/libarchphene_compositor.so") `
        -Destination (Join-Path $AppDirectory "lib/x86_64/libarchphene_compositor.so") -Force
}


New-Item -ItemType Directory -Force -Path (Join-Path $Work "classes"), (Join-Path $Work "dex") | Out-Null

$JavaFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $AppDirectory "src") -Recurse -Filter *.java
    Get-ChildItem -LiteralPath (Join-Path $Root "prototypes/shared-android-bridge/src") -Recurse -Filter *.java
) | ForEach-Object { $_.FullName }
Run-Step { & javac --release 17 -classpath $PlatformJar -d (Join-Path $Work "classes") $JavaFiles } "javac"

$ClassFiles = Get-ChildItem -LiteralPath (Join-Path $Work "classes") -Recurse -Filter *.class |
    ForEach-Object { $_.FullName }
$D8ArgFile = Join-Path $Work "d8-inputs.txt"
[IO.File]::WriteAllLines($D8ArgFile, $ClassFiles, [Text.UTF8Encoding]::new($false))
Run-Step { & cmd.exe /d /c call (Join-Path $BuildTools "d8.bat") --lib $PlatformJar --min-api 23 `
        --output (Join-Path $Work "dex") "@$D8ArgFile" } "d8"

$UnsignedApk = Join-Path $Work "unsigned.apk"
$AlignedApk = Join-Path $Work "aligned.apk"
$RepackedApk = Join-Path $Out "archpheneos-$App-bridge.apk"
Copy-Item -LiteralPath $BaseApk -Destination $UnsignedApk
$DexDirectory = Join-Path $Work "dex"
$StagedNativeDirectory = Join-Path $DexDirectory "lib/x86_64"
New-Item -ItemType Directory -Force -Path $StagedNativeDirectory | Out-Null
Copy-Item -LiteralPath (Join-Path $AppDirectory "lib/x86_64/libarchphene_compositor.so") `
    -Destination (Join-Path $StagedNativeDirectory "libarchphene_compositor.so")
Push-Location $DexDirectory
try {
    Run-Step { & jar uf $UnsignedApk "classes.dex" "lib/x86_64/libarchphene_compositor.so" } `
        "replace bridge classes and native compositor"
}
finally {
    Pop-Location
}

Run-Step { & (Join-Path $BuildTools "zipalign.exe") -P 16 -f 4 $UnsignedApk $AlignedApk } "zipalign"
Run-Step { & (Join-Path $BuildTools "apksigner.bat") sign --ks $Keystore --ks-pass pass:android `
        --key-pass pass:android --out $RepackedApk $AlignedApk } "apksigner sign"
Run-Step { & (Join-Path $BuildTools "apksigner.bat") verify --verbose $RepackedApk } "apksigner verify"

if (-not $SkipInstall) {
    if (-not $Serial) { throw "-Serial is required unless -SkipInstall is used" }
    $Adb = Join-Path $Sdk "platform-tools/adb.exe"
    Run-Step { & $Adb -s $Serial install -r $RepackedApk } "adb install"
    Run-Step { & $Adb -s $Serial shell am force-stop $Package } "adb force-stop"
    Run-Step { & $Adb -s $Serial shell am start -n "$Package/$Package.MainActivity" } "adb launch"
}

Write-Host "Repacked shared bridge APK: $RepackedApk"
